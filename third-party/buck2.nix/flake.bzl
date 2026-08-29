# HOW TO USE THIS MODULE:
#
#    load("@nix/flake.bzl", "flake")
#
#    flake.package(name = "pkg", path = "path/to/flake/dir", ...)

load("@prelude//decls/common.bzl", "buck")
load("@prelude//os_lookup:defs.bzl", "Os", "OsLookup")

## ---------------------------------------------------------------------------------------------------------------------
def __flake_package_impl(
        ctx: AnalysisContext,
        path: Artifact,
        package_set: str,
        package: str,
        output: str,
        binary: str | None,
        binaries: list[str],
        target_os_info: OsLookup) -> list[Provider]:
    # calls nix build path:<path>#package.<arch-os>.<package>

    if target_os_info.os == Os("linux"):
        os = "linux"
    elif target_os_info.os == Os("macos"):
        os = "darwin"
    else:
        fail("host os not supported: {}".format(target_os_info.os))

    if target_os_info.cpu == "x86_64":
        cpu = "x86_64"
    elif target_os_info.cpu == "arm64":
        cpu = "aarch64"
    else:
        fail("host arch is not supported: {}".format(target_os_info.cpu))

    system = "{cpu}-{os}".format(os = os, cpu = cpu)

    attribute = package_set + "." + system + "." + package + "." + output

    # nix will build the first output by default, but we do not know what the first output is called.
    # That's why we build the "out" output by default.
    installable = cmd_args(cmd_args(path, format = "path:{}"), attribute, delimiter = "#")
    nix = ["env", "--", "nix", "--extra-experimental-features", "nix-command flakes"]

    if ctx.attrs.wrappers:
        # No out-link. The package is realised with --no-link and the outputs are small forwarding
        # scripts, one per binary, that exec into the store path: regular files, which is what
        # remote execution needs -- a symlink into /nix/store is followed by the executor when it
        # uploads outputs (the executor walks a symlinked directory and moves its files into the
        # CAS), and cannot be projected into by buck2 either. Each script realises the store path
        # again if it is missing on the machine that runs it, so a worker that did not build the
        # package can still substitute it. The store path is also written out on its own for
        # rules that need the package root rather than a binary.
        bindir = ctx.actions.declare_output("bin", dir = True)
        store_path = ctx.actions.declare_output("store-path")
        script = ctx.actions.write(
            "wrap.sh",
            [
                "#!/bin/sh",
                "set -eu",
                'out=$("$@")',
                'bindir=$WRAP_BINDIR',
                'mkdir -p "$bindir"',
                'printf "%s\\n" "$out" > "$WRAP_STORE_PATH"',
                'for b in $WRAP_BINARIES; do',
                '  {',
                '    printf "#!/bin/sh\\n"',
                '    printf "[ -e %s ] || nix-store --realise %s >/dev/null\\n" "$out" "$out"',
                '    printf "exec %s/bin/%s \\"\\$@\\"\\n" "$out" "$b"',
                '  } > "$bindir/$b"',
                '  chmod +x "$bindir/$b"',
                'done',
            ],
            is_executable = True,
        )
        names = ([binary] if binary else []) + [b for b in binaries if b != binary]
        ctx.actions.run(
            cmd_args(
                [script] + nix + ["build", "--no-link", "--print-out-paths", installable],
                hidden = [bindir.as_output(), store_path.as_output()],
            ),
            env = {
                "WRAP_BINDIR": bindir.as_output(),
                "WRAP_STORE_PATH": store_path.as_output(),
                "WRAP_BINARIES": " ".join(names),
            },
            category = "nix_flake",
            local_only = ctx.attrs.local_only,
        )
        run_info = []
        if binary:
            run_info.append(RunInfo(args = cmd_args(bindir, binary, delimiter = "/")))
        sub_targets = {
            bin: [DefaultInfo(default_output = bindir), RunInfo(args = cmd_args(bindir, bin, delimiter = "/"))]
            for bin in binaries
        }
        sub_targets["store-path"] = [DefaultInfo(default_output = store_path)]
        return [
            DefaultInfo(
                default_output = bindir,
                other_outputs = [store_path],
                sub_targets = sub_targets,
            ),
        ] + run_info

    # Note, nix does not append a suffix to the out-link for the "out" output.
    out_link = ctx.actions.declare_output("out.link" if output == "out" else "out.link-" + output)

    nix_build = cmd_args(nix + [
        "build",
        #"--show-trace",         # for debugging
        cmd_args("--out-link", cmd_args(out_link.as_output(), parent = 1, absolute_suffix = "/out.link")),
        installable,
    ])
    ctx.actions.run(nix_build, category = "nix_flake", local_only = ctx.attrs.local_only)

    run_info = []
    if binary:
        run_info.append(
            RunInfo(
                args = cmd_args(out_link, "bin", ctx.attrs.binary, delimiter = "/"),
            ),
        )

    sub_targets = {
        bin: [DefaultInfo(default_output = out_link), RunInfo(args = cmd_args(out_link, "bin", bin, delimiter = "/"))]
        for bin in binaries
    }

    return [
        DefaultInfo(
            default_output = out_link,
            sub_targets = sub_targets,
        ),
    ] + run_info

__common_attrs = {
    "binary": attrs.option(attrs.string(), default = None, doc = """
      specify the default binary of this package

      This provides `RunInfo` for a binary in the `bin` directory of the package.
    """),
    "binaries": attrs.list(attrs.string(), default = [], doc = """
      add auxiliary binaries for this package

      These can be accessed as sub-targets with the given name in dependent rules.
    """),
    "local_only": attrs.bool(default = True, doc = """
      run `nix build` locally instead of allowing it onto remote execution

      (optional, default: `True`)

      Whether this can be `False` is a property of the deployment, not of the
      rule. The action shells out to `nix`, so the executor needs a nix store
      and a daemon; and the out-link it produces is a symlink to an absolute
      `/nix/store` path, so it only resolves on a machine that already has that
      path. Workers that have nix and share a store with the client (or can
      substitute from the same cache) satisfy both; a stock RE worker satisfies
      neither, which is why the default is the conservative one.
    """),
    "path": attrs.source(allow_directory = True, doc = "the path to the flake"),
    "wrappers": attrs.bool(default = False, doc = """
      expose the binaries as forwarding scripts instead of through an out-link into the store

      (optional, default: `False`)

      With this, the target's outputs are regular files -- one `bin/<name>` script per binary,
      plus `store-path` -- and never a symlink into /nix/store. That is what remote execution
      needs: an executor uploading outputs follows a symlinked directory into the store. The
      scripts realise the store path on the machine that runs them if it is absent.
    """),
    "output": attrs.string(default = "out", doc = """
      specify the output to build instead of the default

      (optional, default: `"out"`)
    """),
    "package": attrs.option(attrs.string(), doc = """
      name of the flake output

      (optional, default: same as `name`)
    """, default = None),
    "_target_os_type": buck.target_os_type_arg(),
}

__flake_package = rule(
    impl = lambda ctx: __flake_package_impl(
        ctx,
        ctx.attrs.path,
        "packages",
        ctx.attrs.package or ctx.label.name,
        ctx.attrs.output,
        ctx.attrs.binary,
        ctx.attrs.binaries,
        ctx.attrs._target_os_type[OsLookup],
    ),
    attrs = __common_attrs,
    doc = """
    A `flake.package()` rule builds a nix package of a given flake.

    ## Examples

    ```starlark
    flake.package(
        name = "curl",
        path = "nix",
        output = "bin",
        binary = "curl",
    )
    ```

    This creates a target called `curl` from the nix flake in `./nix`, building `path:nix#packages.<system>.curl.bin`.
    """,
)

__flake_legacyPackage = rule(
    impl = lambda ctx: __flake_package_impl(
        ctx,
        ctx.attrs.path,
        "legacyPackages",
        ctx.attrs.package or ctx.label.name,
        ctx.attrs.output,
        ctx.attrs.binary,
        ctx.attrs.binaries,
        ctx.attrs._target_os_type[OsLookup],
    ),
    attrs = __common_attrs,
    doc = """
    A `flake.legacyPackage()` rule builds a nix package of a given flake.

    ## Examples

    ```starlark
    flake.legacyPackage(
        name = "curl",
        path = ":nixpkgs",
        output = "bin",
        binary = "curl",
    )
    ```

    This creates a target called `curl` from the nix flake in `./nix`, building `path:nix#legacyPackages.<system>.curl.bin`.
    """,
)

## ---------------------------------------------------------------------------------------------------------------------

flake = struct(
    legacyPackage = __flake_legacyPackage,
    package = __flake_package,
)

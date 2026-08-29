"""The devshell's zig, presented the way the prelude's cxx_zig_toolchain wants a distribution.

The prelude ships download_zig_distribution (fetch a release from ziglang.org by sha256); here zig
comes from the flake like every other tool, so this is the same provider pair -- RunInfo for
`zig` (through bzl/zig-wrapper.sh), ZigDistributionInfo for what it is -- pointed at PATH. Bare name, not a /nix/store path,
for the reason bzl/java.bzl gives; the version lands in the action key through .buckconfig.local."""

load("@prelude//toolchains/cxx/zig:defs.bzl", "ZigDistributionInfo")

def _host_arch():
    arch = host_info().arch
    if arch.is_aarch64:
        return "aarch64"
    elif arch.is_x86_64:
        return "x86_64"
    fail("unsupported host arch for zig")

def _host_os():
    os = host_info().os
    if os.is_linux:
        return "linux"
    elif os.is_macos:
        return "macos"
    fail("unsupported host os for zig")

def _system_zig_distribution_impl(ctx):
    return [
        DefaultInfo(),
        RunInfo(args = [ctx.attrs._wrapper[DefaultInfo].default_outputs[0], ctx.attrs.zig]),
        ZigDistributionInfo(
            version = ctx.attrs.version,
            arch = _host_arch(),
            os = _host_os(),
        ),
    ]

system_zig_distribution = rule(
    impl = _system_zig_distribution_impl,
    attrs = {
        "zig": attrs.string(default = "zig"),
        # Filters the one rustc link flag zig rejects; see the script.
        "_wrapper": attrs.default_only(attrs.dep(default = "@root//bzl:zig-wrapper.sh")),
        # Written by `just bootstrap`; "unknown" until then.
        "version": attrs.string(default = "unknown"),
    },
)

"""zig from the flake, presented the way the prelude's cxx_zig_toolchain wants a distribution.

The prelude ships download_zig_distribution (fetch a release from ziglang.org by sha256); here
zig is a buck2.nix flake package like every other tool, so this is the same provider pair --
RunInfo for `zig` (through bzl/zig-wrapper.sh), ZigDistributionInfo for what it is -- built from
that target. An exec dep: under remote execution it is the worker's zig."""

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

def _zig_distribution_impl(ctx):
    return [
        DefaultInfo(),
        RunInfo(args = cmd_args(ctx.attrs._wrapper[DefaultInfo].default_outputs[0], ctx.attrs.zig[RunInfo])),
        ZigDistributionInfo(
            version = ctx.attrs.version,
            arch = _host_arch(),
            os = _host_os(),
        ),
    ]

zig_distribution = rule(
    impl = _zig_distribution_impl,
    attrs = {
        "zig": attrs.exec_dep(providers = [RunInfo]),
        # Filters the one rustc link flag zig rejects; see the script.
        "_wrapper": attrs.default_only(attrs.dep(default = "@root//bzl:zig-wrapper.sh")),
        "version": attrs.string(default = ""),
    },
)

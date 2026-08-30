"""zig as the C toolchain for the shipping cdylibs: cross-links for any of the three slots from
any host, so under remote execution a Linux worker can produce the macOS one too.

Built on the prelude's cxx_toolchain_infos() like bzl/nix_cxx.bzl, rather than on the prelude's
cxx_zig_toolchain: that one derives the linker type from the machine zig runs on, so a Mach-O
link from Linux would get gnu-style flags, and takes nm/strip/objcopy from PATH, which a worker
does not have. Here the target string decides -- `*-macos*` is a darwin link -- and the binutils
come from the nix cc package.

zig reaches the actions through bzl/zig-wrapper.sh (see it for the two flags it rewrites)."""

load(
    "@prelude//cxx:cxx_toolchain_types.bzl",
    "BinaryUtilitiesInfo",
    "CCompilerInfo",
    "CxxCompilerInfo",
    "CxxInternalTools",
    "LinkerInfo",
    "LinkerType",
    "PicBehavior",
    "ShlibInterfacesMode",
    "cxx_toolchain_infos",
)
load("@prelude//cxx:headers.bzl", "HeaderMode")
load("@prelude//cxx:linker.bzl", "is_pdb_generated")
load("@prelude//linking:link_info.bzl", "LinkStyle")

def _zig_cxx_toolchain_impl(ctx):
    zig = cmd_args(ctx.attrs._wrapper[DefaultInfo].default_outputs[0], ctx.attrs.zig[RunInfo])
    binutils = ctx.attrs.nix_cc[DefaultInfo].sub_targets
    target = ["-target", ctx.attrs.target]
    macos = "-macos" in ctx.attrs.target
    linker_type = LinkerType("darwin") if macos else LinkerType("gnu")

    def tool(sub):
        return RunInfo(args = cmd_args(zig, sub))

    return [DefaultInfo()] + cxx_toolchain_infos(
        internal_tools = ctx.attrs._cxx_internal_tools[CxxInternalTools],
        platform_name = ctx.attrs.target,
        c_compiler_info = CCompilerInfo(
            compiler = tool("cc"),
            compiler_type = "clang",
            compiler_flags = cmd_args(target, ctx.attrs.c_flags),
            preprocessor_flags = cmd_args(),
        ),
        cxx_compiler_info = CxxCompilerInfo(
            compiler = tool("c++"),
            compiler_type = "clang",
            compiler_flags = cmd_args(target, ctx.attrs.cxx_flags),
            preprocessor_flags = cmd_args(),
        ),
        linker_info = LinkerInfo(
            archiver = tool("ar"),
            archiver_type = "gnu",
            archiver_supports_argfiles = True,
            archive_objects_locally = False,
            binary_extension = "",
            generate_linker_maps = False,
            link_binaries_locally = False,
            link_libraries_locally = False,
            link_style = LinkStyle("shared"),
            link_weight = 1,
            # cc, not c++: nothing here is C++, and on a cold cache `zig c++` first compiles
            # libc++ for the target -- megabytes of warnings that end up in one rustc JSON line.
            linker = tool("cc"),
            linker_flags = cmd_args(target, ctx.attrs.link_flags),
            object_file_extension = "o",
            shlib_interfaces = ShlibInterfacesMode("disabled"),
            shared_dep_runtime_ld_flags = [],
            shared_library_name_default_prefix = "lib",
            shared_library_name_format = "{}.dylib" if macos else "{}.so",
            shared_library_versioned_name_format = "{}.{}.dylib" if macos else "{}.so.{}",
            static_dep_runtime_ld_flags = [],
            static_library_extension = "a",
            static_pic_dep_runtime_ld_flags = [],
            independent_shlib_interface_linker_flags = [],
            type = linker_type,
            use_archiver_flags = True,
            is_pdb_generated = is_pdb_generated(linker_type, ctx.attrs.link_flags),
        ),
        binary_utilities_info = BinaryUtilitiesInfo(
            dwp = None,
            nm = binutils["nm"][RunInfo],
            objcopy = binutils["objcopy"][RunInfo],
            ranlib = tool("ranlib"),
            strip = binutils["strip"][RunInfo],
        ),
        header_mode = HeaderMode("symlink_tree_only"),
        pic_behavior = PicBehavior("always_enabled") if macos else PicBehavior("supported"),
    )

zig_cxx_toolchain = rule(
    impl = _zig_cxx_toolchain_impl,
    is_toolchain_rule = True,
    attrs = {
        # zig, as a RunInfo target (a flake package); an exec dep, so the worker's own.
        "zig": attrs.exec_dep(providers = [RunInfo]),
        # The nix cc package, for nm/objcopy/strip, which zig does not ship.
        "nix_cc": attrs.exec_dep(providers = [DefaultInfo]),
        # zig target triple, e.g. "x86_64-linux-gnu.2.17" or "aarch64-macos".
        "target": attrs.string(),
        "c_flags": attrs.list(attrs.arg(), default = []),
        "cxx_flags": attrs.list(attrs.arg(), default = []),
        "link_flags": attrs.list(attrs.arg(), default = []),
        "_wrapper": attrs.default_only(attrs.dep(default = "@root//bzl:zig-wrapper.sh")),
        "_cxx_internal_tools": attrs.default_only(attrs.dep(providers = [CxxInternalTools], default = "prelude//cxx/tools:internal_tools")),
    },
)

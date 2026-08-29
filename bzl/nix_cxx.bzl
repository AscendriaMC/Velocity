"""A C toolchain out of a buck2.nix flake package (nix/flake.nix's `cxx`: cc, c++, ar, nm,
objcopy, ranlib, strip), for code that runs on our own machines and workers.

Built on the prelude's cxx_toolchain_infos() the way its zig toolchain is, rather than by
constructing CxxToolchainInfo by hand as buck2.nix's nix_cxx_toolchain does -- that one is a
prelude release behind the bundled prelude. OS specifics come from the TARGET configuration,
which for an exec dep is the platform the action runs on."""

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
load("@prelude//decls:common.bzl", "buck")
load("@prelude//linking:link_info.bzl", "LinkStyle")
load("@prelude//os_lookup:defs.bzl", "Os", "OsLookup")

def _nix_cxx_toolchain_impl(ctx):
    tools = ctx.attrs.nix_cc[DefaultInfo].sub_targets
    for name in ["cc", "c++", "ar", "nm", "objcopy", "ranlib", "strip"]:
        if name not in tools:
            fail("{}: {} exposes no binary {}".format(ctx.label, ctx.attrs.nix_cc.label, name))
    cc = tools["cc"][RunInfo]
    cxx = tools["c++"][RunInfo]
    macos = ctx.attrs._target_os_type[OsLookup].os == Os("macos")
    linker_type = LinkerType("darwin") if macos else LinkerType("gnu")
    compiler_type = "clang" if macos else "gcc"
    return [DefaultInfo()] + cxx_toolchain_infos(
        internal_tools = ctx.attrs._cxx_internal_tools[CxxInternalTools],
        platform_name = "macos" if macos else "linux",
        c_compiler_info = CCompilerInfo(
            compiler = cc,
            compiler_type = compiler_type,
            compiler_flags = cmd_args(ctx.attrs.c_flags),
            preprocessor_flags = cmd_args(),
        ),
        cxx_compiler_info = CxxCompilerInfo(
            compiler = cxx,
            compiler_type = compiler_type,
            compiler_flags = cmd_args(ctx.attrs.cxx_flags),
            preprocessor_flags = cmd_args(),
        ),
        linker_info = LinkerInfo(
            archiver = tools["ar"][RunInfo],
            archiver_type = "bsd" if macos else "gnu",
            archiver_supports_argfiles = not macos,
            archive_objects_locally = False,
            binary_extension = "",
            generate_linker_maps = False,
            link_binaries_locally = False,
            link_libraries_locally = False,
            link_style = LinkStyle("shared"),
            link_weight = 1,
            linker = cxx,
            linker_flags = cmd_args(ctx.attrs.link_flags),
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
            nm = tools["nm"][RunInfo],
            objcopy = tools["objcopy"][RunInfo],
            ranlib = tools["ranlib"][RunInfo],
            strip = tools["strip"][RunInfo],
        ),
        header_mode = HeaderMode("symlink_tree_only"),
        pic_behavior = PicBehavior("always_enabled") if macos else PicBehavior("supported"),
    )

nix_cxx_toolchain = rule(
    impl = _nix_cxx_toolchain_impl,
    is_toolchain_rule = True,
    attrs = {
        "nix_cc": attrs.exec_dep(providers = [DefaultInfo]),
        "c_flags": attrs.list(attrs.arg(), default = []),
        "cxx_flags": attrs.list(attrs.arg(), default = []),
        "link_flags": attrs.list(attrs.arg(), default = []),
        "_cxx_internal_tools": attrs.default_only(attrs.dep(providers = [CxxInternalTools], default = "prelude//cxx/tools:internal_tools")),
        "_target_os_type": buck.target_os_type_arg(),
    },
)

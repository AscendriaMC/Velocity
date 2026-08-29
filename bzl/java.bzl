"""Just enough Java for this repo: a jar fetched by checksum, and a library compiled by javac.

No transitive classpath propagation on purpose. Gradle already resolved the transitive closure
of every configuration, and third-party/jvm/classpaths.bzl carries those flat lists; a target's
`deps` IS its classpath, in gradle's order. Keeping the graph flat is what lets the generated
part stay a mechanical transcription of gradle's answer instead of a second resolver."""

load("//toolchains:defs.bzl", "JavaToolchainInfo")

_TOOLCHAIN = attrs.toolchain_dep(default = "//toolchains:java", providers = [JavaToolchainInfo])

JavaLibraryInfo = provider(fields = ["jar"])

def _remote_jar_impl(ctx):
    out = ctx.actions.declare_output(ctx.label.name + ".jar")
    ctx.actions.download_file(out, ctx.attrs.url, sha256 = ctx.attrs.sha256)
    return [DefaultInfo(default_output = out), JavaLibraryInfo(jar = out)]

remote_jar = rule(
    impl = _remote_jar_impl,
    attrs = {
        "url": attrs.string(),
        "sha256": attrs.string(),
    },
)

def _prebuilt_jar_impl(ctx):
    return [DefaultInfo(default_output = ctx.attrs.jar), JavaLibraryInfo(jar = ctx.attrs.jar)]

prebuilt_jar = rule(
    impl = _prebuilt_jar_impl,
    attrs = {"jar": attrs.source()},
)

def _jars(deps):
    return [d[JavaLibraryInfo].jar for d in deps]

def _java_library_impl(ctx):
    tc = ctx.attrs._toolchain[JavaToolchainInfo]
    classes = ctx.actions.declare_output("classes", dir = True)
    gen = ctx.actions.declare_output("gen", dir = True)
    jar = ctx.actions.declare_output(ctx.label.name + ".jar")

    # Resources keep their path below the declared root: src/main/resources/log4j2.xml lands in
    # the jar as log4j2.xml. short_path is package-relative, which is what the roots are too.
    res = None
    entries = {}
    for root, files in ctx.attrs.resources.items():
        prefix = root.rstrip("/") + "/"
        for f in files:
            p = f.short_path
            if not p.startswith(prefix):
                fail("{}: resource {} is not under its declared root {}".format(ctx.label, p, root))
            entries[p[len(prefix):]] = f
    if entries:
        res = ctx.actions.symlinked_dir("resources", entries)

    cmd = cmd_args([
        ctx.attrs._runner[DefaultInfo].default_outputs[0],
        tc.javac,
        tc.jar,
        classes.as_output(),
        gen.as_output(),
        jar.as_output(),
        res if res else "-",
        "--",
        "-encoding", "UTF-8",
        "-nowarn",
        "-implicit:none",
    ])
    if ctx.attrs.deps:
        cmd.add("-cp", cmd_args(_jars(ctx.attrs.deps), delimiter = ":"))
    if ctx.attrs.processors:
        cmd.add("-proc:full")
        cmd.add("-processorpath", cmd_args(_jars(ctx.attrs.processors), delimiter = ":"))
        for k, v in ctx.attrs.processor_options.items():
            cmd.add("-A{}={}".format(k, v))
    else:
        cmd.add("-proc:none")
    cmd.add(ctx.attrs.javac_flags)
    cmd.add(ctx.attrs.srcs)

    ctx.actions.run(
        cmd,
        category = "javac",
        identifier = ctx.label.name,
        env = {"JAVA_TOOLCHAIN_VERSION": tc.version},
    )
    return [
        DefaultInfo(default_output = jar, other_outputs = [classes, gen]),
        JavaLibraryInfo(jar = jar),
    ]

java_library = rule(
    impl = _java_library_impl,
    attrs = {
        "srcs": attrs.list(attrs.source(), default = []),
        # root (package-relative) -> files under it
        "resources": attrs.dict(attrs.string(), attrs.list(attrs.source()), default = {}),
        "deps": attrs.list(attrs.dep(providers = [JavaLibraryInfo]), default = []),
        "processors": attrs.list(attrs.dep(providers = [JavaLibraryInfo]), default = []),
        "processor_options": attrs.dict(attrs.string(), attrs.string(), default = {}),
        "javac_flags": attrs.list(attrs.string(), default = []),
        "_runner": attrs.default_only(attrs.dep(default = "//bzl:javac.sh")),
        "_toolchain": _TOOLCHAIN,
    },
)

def _export_file_impl(ctx):
    return [DefaultInfo(default_output = ctx.attrs.src)]

export_file = rule(impl = _export_file_impl, attrs = {"src": attrs.source()})

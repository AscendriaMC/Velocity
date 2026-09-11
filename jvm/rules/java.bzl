"""Just enough Java for this repo: a jar fetched by checksum, and a library compiled by javac.

No transitive classpath propagation on purpose. Gradle already resolved the transitive closure
of every configuration, and third-party/jvm/classpaths.bzl carries those flat lists; a target's
`deps` IS its classpath, in gradle's order. Keeping the graph flat is what lets the generated
part stay a mechanical transcription of gradle's answer instead of a second resolver."""

JavaToolchainInfo = provider(fields = ["java", "javac", "jar", "worker", "version"])

JavaBinaryInfo = provider(fields = ["main_class", "classpath"])

def _java_toolchain_impl(ctx):
    return [
        DefaultInfo(),
        JavaToolchainInfo(
            java = ctx.attrs.java[RunInfo],
            javac = ctx.attrs.javac[RunInfo],
            jar = ctx.attrs.jar[RunInfo],
            worker = ctx.attrs.worker,
            version = ctx.attrs.version,
        ),
    ]

# The tools are targets providing RunInfo -- a nix flake package, a system_tool, anything -- and
# exec deps, so under cross-compilation and remote execution they are built for the machine that
# runs the action, not the one the code is for. Their identity is then part of every action's
# inputs; `version` is only needed for system_tool, whose identity is not (it names a PATH entry).
java_toolchain = rule(
    impl = _java_toolchain_impl,
    is_toolchain_rule = True,
    attrs = {
        "java": attrs.exec_dep(providers = [RunInfo]),
        "javac": attrs.exec_dep(providers = [RunInfo]),
        "jar": attrs.exec_dep(providers = [RunInfo]),
        # A Bazel-protocol persistent worker for the compile step (javac_worker below); when
        # set, compile actions attach it and a capable executor keeps the JVM warm across
        # actions. Off by default; the one-shot path is always the fallback and produces the
        # same bytes.
        "worker": attrs.option(attrs.exec_dep(providers = [WorkerInfo]), default = None),
        "version": attrs.string(default = ""),
    },
)

def _system_tool_impl(ctx):
    return [DefaultInfo(), RunInfo(args = [ctx.attrs.tool])]

# A tool taken from PATH at action time, for consumers without nix. Not an input of anything:
# pair it with java_toolchain's `version` so a changed JDK still invalidates.
system_tool = rule(
    impl = _system_tool_impl,
    attrs = {"tool": attrs.string()},
)

_TOOLCHAIN = attrs.toolchain_dep(default = "toolchains//:java", providers = [JavaToolchainInfo])

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

def _java_bootstrap_library_impl(ctx):
    """Plain javac + the jar CLI. The compile tool itself (jartool, javacworker) cannot be
    compiled by the compile tool; everything else uses java_library, whose worker/one-shot
    runner produces deterministic jars. A separate rule rather than a flag because the
    dependency edge to the tool exists per rule, not per branch."""
    tc = ctx.attrs._toolchain[JavaToolchainInfo]
    classes = ctx.actions.declare_output("classes", dir = True)
    gen = ctx.actions.declare_output("gen", dir = True)
    jar = ctx.actions.declare_output(ctx.label.name + ".jar")
    cmd = cmd_args([
        ctx.attrs._runner[DefaultInfo].default_outputs[0],
        cmd_args(tc.javac, delimiter = " "),
        cmd_args(tc.jar, delimiter = " "),
        classes.as_output(),
        gen.as_output(),
        jar.as_output(),
        "-",
        "--",
        "-encoding", "UTF-8",
        "-nowarn",
        "-implicit:none",
        "-proc:none",
    ])
    if ctx.attrs.deps:
        cmd.add("-cp", cmd_args(_jars(ctx.attrs.deps), delimiter = ":"))
    cmd.add(ctx.attrs.srcs)
    ctx.actions.run(cmd, category = "javac", identifier = ctx.label.name,
                    env = {"JAVA_TOOLCHAIN_VERSION": tc.version})
    return [
        DefaultInfo(default_output = jar, other_outputs = [classes, gen]),
        JavaLibraryInfo(jar = jar),
    ]

java_bootstrap_library = rule(
    impl = _java_bootstrap_library_impl,
    attrs = {
        "srcs": attrs.list(attrs.source(), default = []),
        "deps": attrs.list(attrs.dep(providers = [JavaLibraryInfo]), default = []),
        "_runner": attrs.default_only(attrs.dep(default = "@jvm//rules:javac.sh")),
        # A worker-less toolchain instance, or the worker's own compiler needs the worker:
        # toolchains//:java -> javac-worker -> javacworker -> jartool:lib -> toolchains//:java.
        "_toolchain": attrs.toolchain_dep(default = "toolchains//:java_bootstrap", providers = [JavaToolchainInfo]),
    },
)

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

    tool = ctx.attrs._tool[JavaBinaryInfo]
    plan = [
        cmd_args("--classes", classes.as_output(), delimiter = "\n"),
        cmd_args("--gen", gen.as_output(), delimiter = "\n"),
        cmd_args("--jar", jar.as_output(), delimiter = "\n"),
    ]
    if res:
        plan.append(cmd_args("--res", res, delimiter = "\n"))
    plan.append("--")
    plan += ["-encoding", "UTF-8", "-nowarn", "-implicit:none"]
    if ctx.attrs.deps:
        plan += ["-cp", cmd_args(_jars(ctx.attrs.deps), delimiter = ":")]
    if ctx.attrs.processors:
        plan += ["-proc:full", "-processorpath", cmd_args(_jars(ctx.attrs.processors), delimiter = ":")]
        for k, v in ctx.attrs.processor_options.items():
            plan.append("-A{}={}".format(k, v))
    else:
        plan.append("-proc:none")
    plan += ctx.attrs.javac_flags
    plan += ctx.attrs.srcs
    plan_file, plan_extras = ctx.actions.write("javac.plan", plan, allow_args = True)

    # One token per line in the plan; the command is the tool plus @plan, which is also the
    # shape the Bazel persistent-worker protocol requires (everything behind one @argfile).
    # The executable goes through run()'s `exe` -- as a WorkerRunInfo when a worker is
    # attached, so a capable executor keeps the JVM across actions and everyone else falls
    # back to the same one-shot command.
    exe_cmd = cmd_args(tc.java, "-cp", cmd_args(tool.classpath, delimiter = ":"), tool.main_class)
    worker_inputs = []
    if tc.worker != None:
        worker_info = tc.worker[WorkerInfo]
        exe = WorkerRunInfo(worker = worker_info, exe = exe_cmd)
        # The worker's launcher (and everything it references) must be materialised in every
        # action's sandbox: the executor canonicalises the worker argv against it at spawn.
        worker_inputs = [worker_info.exe]
    else:
        exe = RunInfo(args = exe_cmd)
    cmd = cmd_args(
        cmd_args(plan_file, format = "@{}"),
        # `plan` rides along hidden for two reasons: the output bindings (--classes/--gen/--jar
        # exist only inside the argfile) and the input artifacts the file references.
        hidden = [plan, plan_extras, ctx.attrs.srcs, _jars(ctx.attrs.deps), _jars(ctx.attrs.processors)] + worker_inputs + ([res] if res else []),
    )
    ctx.actions.run(
        cmd,
        exe = exe,
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
        "_tool": attrs.default_only(attrs.exec_dep(default = "@jvm//tools/javacworker:javacworker", providers = [JavaBinaryInfo])),
        "_toolchain": _TOOLCHAIN,
    },
)


def _java_binary_impl(ctx):
    # Flat, like everything else here: `deps` is the runtime classpath, in order.
    return [
        DefaultInfo(),
        JavaBinaryInfo(main_class = ctx.attrs.main_class, classpath = _jars(ctx.attrs.deps)),
    ]

java_binary = rule(
    impl = _java_binary_impl,
    attrs = {
        "main_class": attrs.string(),
        "deps": attrs.list(attrs.dep(providers = [JavaLibraryInfo]), default = []),
    },
)

def _java_test_impl(ctx):
    """A JVM run, as a target you can `buck2 run`.

    Not a compile step: the tests are an ordinary java_library, and this is the command that puts
    a JVM in front of them. Knowing nothing about JUnit is the point -- `main_class` and `args`
    name whatever runner the repo has on the classpath, so the rule stays as generic as the rest
    of this cell. Same flat-classpath rule as everywhere else: `deps` IS the classpath, in order.
    """
    tc = ctx.attrs._toolchain[JavaToolchainInfo]
    jars = _jars(ctx.attrs.deps)
    cmd = cmd_args(
        tc.java,
        ctx.attrs.jvm_flags,
        "-cp",
        cmd_args(jars, delimiter = ":"),
        ctx.attrs.main_class,
        ctx.attrs.args,
        hidden = jars,
    )
    return [
        DefaultInfo(),
        RunInfo(args = cmd),
        # The same command again, for `buck2 test`. Without this the target is invisible to it --
        # and `buck2 test //...` still says "Tests finished: Pass 1" off the prelude's own
        # trivially-passing targets, so the absence does not look like an absence.
        ExternalRunnerTestInfo(
            type = ctx.attrs.test_type,
            command = [cmd],
            labels = ctx.attrs.labels,
            run_from_project_root = True,
            use_project_relative_paths = True,
        ),
    ]

# Deliberately not folded into java_binary: that one is depended on by the compile toolchain
# itself (javacworker), and giving it a toolchain_dep would close the cycle the bootstrap
# toolchain exists to break.
java_test = rule(
    impl = _java_test_impl,
    attrs = {
        "main_class": attrs.string(),
        "deps": attrs.list(attrs.dep(providers = [JavaLibraryInfo]), default = []),
        # $(location ...) works here: a javaagent path, a classpath root to scan.
        "jvm_flags": attrs.list(attrs.arg(), default = []),
        "args": attrs.list(attrs.arg(), default = []),
        # What `buck2 test` reports the target as, and what its selectors filter on.
        "test_type": attrs.string(default = "junit"),
        "labels": attrs.list(attrs.string(), default = []),
        "_toolchain": _TOOLCHAIN,
    },
)

def _javac_worker_impl(ctx):
    tool = ctx.attrs.tool[JavaBinaryInfo]
    java = ctx.attrs.java[RunInfo]

    # A single self-locating launcher, because of how a pooled remote worker is spawned: the
    # executor canonicalises argv[0] against the action's sandbox but passes the remaining
    # startup arguments through untouched, and runs the process from its own root -- so a
    # relative `-cp` never resolves. The script derives the sandbox root from its own realpath
    # (everything of ours lives under buck-out/) and execs with absolute paths; the JVM holds
    # the jars as open file descriptors, so the worker outlives the sandbox it started in.
    script, _ = ctx.actions.write(
        "worker.sh",
        cmd_args(
            "#!/bin/sh",
            'SELF="$(realpath "$0")"',
            'ROOT="${SELF%%/buck-out/*}"',
            cmd_args(
                'exec "$ROOT/',
                cmd_args(java, delimiter = ""),
                '" -cp "',
                cmd_args(tool.classpath, format = '$ROOT/{}', delimiter = ":"),
                '" ',
                tool.main_class,
                ' "$@"',
                delimiter = "",
            ),
            delimiter = "\n",
        ),
        is_executable = True,
        allow_args = True,
    )
    return [
        DefaultInfo(default_output = script),
        WorkerInfo(
            exe = cmd_args(script, hidden = [java, tool.classpath]),
            supports_bazel_remote_persistent_worker_protocol = True,
        ),
    ]

# The executor spawns exe + `--persistent_worker` once per key (key = this argv), then streams
# WorkRequests; buck2 keys the remote pool by the digest of these inputs.
javac_worker = rule(
    impl = _javac_worker_impl,
    attrs = {
        "java": attrs.exec_dep(providers = [RunInfo]),
        "tool": attrs.exec_dep(providers = [JavaBinaryInfo], default = "@jvm//tools/javacworker:javacworker"),
    },
)

def _jar_assembly_impl(ctx):
    """One jar out of many, the shadow way: see tools/jartool for the semantics of each line."""
    tc = ctx.attrs._toolchain[JavaToolchainInfo]
    tool = ctx.attrs._tool[JavaBinaryInfo]
    out = ctx.actions.declare_output(ctx.label.name + ".jar")

    lines = []
    for d in ctx.attrs.inputs:
        lines.append(cmd_args("in", d[JavaLibraryInfo].jar, delimiter = "\t"))
    for path, f in ctx.attrs.resource_files.items():
        lines.append(cmd_args("file", path, f, delimiter = "\t"))
    for src, dst in ctx.attrs.relocations.items():
        excludes = ctx.attrs.relocation_excludes.get(src, [])
        lines.append(cmd_args(["relocate", src, dst] + excludes, delimiter = "\t"))
    for g in ctx.attrs.excludes:
        lines.append(cmd_args("exclude", g, delimiter = "\t"))
    for g, policy in ctx.attrs.merges.items():
        lines.append(cmd_args("merge", g, policy, delimiter = "\t"))
    lines.append(cmd_args("duplicates", ctx.attrs.duplicates, delimiter = "\t"))
    lines.append(cmd_args("module-info", ctx.attrs.module_info, delimiter = "\t"))
    lines.append(cmd_args("strip-signatures", "true" if ctx.attrs.strip_signatures else "false", delimiter = "\t"))
    if ctx.attrs.main_class:
        lines.append(cmd_args("main-class", ctx.attrs.main_class, delimiter = "\t"))
    for k, v in ctx.attrs.manifest.items():
        lines.append(cmd_args("manifest", k, v, delimiter = "\t"))
    for k, (f, key) in ctx.attrs.manifest_from_properties.items():
        lines.append(cmd_args("manifest-from-properties", f, key, k, delimiter = "\t"))

    plan, _ = ctx.actions.write("plan.txt", lines, allow_args = True)
    # Everything the plan names, declared as inputs of the command that reads it.
    inputs = [d[JavaLibraryInfo].jar for d in ctx.attrs.inputs]
    inputs += ctx.attrs.resource_files.values()
    inputs += [f for f, _ in ctx.attrs.manifest_from_properties.values()]
    cmd = cmd_args(
        [tc.java, "-cp", cmd_args(tool.classpath, delimiter = ":"), tool.main_class,
         "--plan", plan, "--out", out.as_output()],
        hidden = inputs,
    )
    ctx.actions.run(
        cmd,
        category = "jar_assembly",
        identifier = ctx.label.name,
        env = {"JAVA_TOOLCHAIN_VERSION": tc.version},
    )
    return [DefaultInfo(default_output = out), JavaLibraryInfo(jar = out)]

jar_assembly = rule(
    impl = _jar_assembly_impl,
    attrs = {
        # Jars, in classpath order; the order decides `first`/`last`.
        "inputs": attrs.list(attrs.dep(providers = [JavaLibraryInfo])),
        # path in jar -> file, for artifacts other rules built (the mccrypt cdylibs land at the
        # slot names Natives.java loads)
        "resource_files": attrs.dict(attrs.string(), attrs.source(), default = {}),
        # "org.bstats": "com.velocitypowered.proxy.bstats"
        "relocations": attrs.dict(attrs.string(), attrs.string(), default = {}),
        # from-package -> globs over dotted class names that stay put
        "relocation_excludes": attrs.dict(attrs.string(), attrs.list(attrs.string()), default = {}),
        # Ant globs over entry paths
        "excludes": attrs.list(attrs.string(), default = []),
        # glob -> first | last | error | services | append | log4j2-plugin-cache
        "merges": attrs.dict(attrs.string(), attrs.string(), default = {}),
        "duplicates": attrs.enum(["first", "last", "error"], default = "first"),
        "module_info": attrs.enum(["drop", "keep"], default = "drop"),
        "strip_signatures": attrs.bool(default = True),
        "main_class": attrs.option(attrs.string(), default = None),
        "manifest": attrs.dict(attrs.string(), attrs.string(), default = {}),
        # "Implementation-Version": (properties file, "version")
        "manifest_from_properties": attrs.dict(attrs.string(), attrs.tuple(attrs.source(), attrs.string()), default = {}),
        "_tool": attrs.default_only(attrs.exec_dep(default = "@jvm//tools/jartool:jartool", providers = [JavaBinaryInfo])),
        "_toolchain": _TOOLCHAIN,
    },
)

def _export_file_impl(ctx):
    return [DefaultInfo(default_output = ctx.attrs.src)]

export_file = rule(impl = _export_file_impl, attrs = {"src": attrs.source()})

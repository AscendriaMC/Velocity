"""Execution platforms.

Two kinds. `execution_platform` describes one place actions can run: the local machine, or the
Remote workers, one per architecture (the scheduler routes on the `arch`
property, so each remote platform names exactly one worker kind). `execution_platforms` is the
ordered list buck2 chooses from -- first platform that satisfies an action's exec constraints
wins -- and it is assembled from `re.enabled` so that a plain build never depends on the network.

Remote platforms are remote-only on purpose: their configuration says linux, so a hybrid
fallback would run a linux-configured action (with, say, an aarch64-linux JDK) on this Mac.
"""

def _execution_platform_impl(ctx):
    constraints = {}
    constraints.update(ctx.attrs.cpu[ConfigurationInfo].constraints)
    constraints.update(ctx.attrs.os[ConfigurationInfo].constraints)
    cfg = ConfigurationInfo(constraints = constraints, values = {})
    name = ctx.label.raw_target()
    if ctx.attrs.remote_arch:
        executor = CommandExecutorConfig(
            local_enabled = False,
            remote_enabled = True,
            remote_execution_properties = {
                "OSFamily": "linux",
                "arch": ctx.attrs.remote_arch,
                "container-image": "",
                # The scheduler treats cpu_count as a consumable slot (the worker advertises nproc
                # of them); without it every queued action runs at once and a cold build is
                # sixty rustc and javac processes on one container.
                "cpu_count": "1",
            },
            remote_execution_use_case = "buck2-default",
            remote_output_paths = "output_paths",
            # Only takes effect for actions that carry a WorkerInfo (the javac worker); the
            # client then sends the Bazel worker properties and rewrites argv to exe + @argfile.
            use_bazel_protocol_remote_persistent_workers = True,
        )
    else:
        executor = CommandExecutorConfig(
            local_enabled = True,
            remote_enabled = False,
            # Results of local actions still go to the shared action cache when RE is on, so
            # the macOS-only steps are shared between Macs too.
            remote_cache_enabled = ctx.attrs.remote_cache,
            allow_cache_uploads = ctx.attrs.remote_cache,
            remote_execution_use_case = "buck2-default",
        )
    platform = ExecutionPlatformInfo(label = name, configuration = cfg, executor_config = executor)
    return [
        DefaultInfo(),
        platform,
        PlatformInfo(label = str(name), configuration = cfg),
        ExecutionPlatformRegistrationInfo(platforms = [platform]),
    ]

execution_platform = rule(
    impl = _execution_platform_impl,
    attrs = {
        "cpu": attrs.dep(providers = [ConfigurationInfo]),
        "os": attrs.dep(providers = [ConfigurationInfo]),
        # `arch` property of the worker that runs this platform; None = local.
        "remote_arch": attrs.option(attrs.string(), default = None),
        "remote_cache": attrs.bool(default = False),
    },
)

def _execution_platforms_impl(ctx):
    return [
        DefaultInfo(),
        ExecutionPlatformRegistrationInfo(
            platforms = [p[ExecutionPlatformInfo] for p in ctx.attrs.platforms],
        ),
    ]

execution_platforms = rule(
    impl = _execution_platforms_impl,
    attrs = {"platforms": attrs.list(attrs.dep(providers = [ExecutionPlatformInfo]))},
)

"""Toolchains. Same shape as season-two's: tools are bare names resolved from PATH, and the
identity of the JDK travels in the action env (via .buckconfig.local) rather than as a
/nix/store path in every argv -- a rebuild of the same jdk invalidates nothing, a major bump
invalidates everything, which is exactly when javac starts emitting different class files."""

JavaToolchainInfo = provider(fields = ["java", "javac", "jar", "version"])

def _java_toolchain_impl(ctx):
    return [
        DefaultInfo(),
        JavaToolchainInfo(
            java = ctx.attrs.java,
            javac = ctx.attrs.javac,
            jar = ctx.attrs.jar,
            version = ctx.attrs.version,
        ),
    ]

java_toolchain = rule(
    impl = _java_toolchain_impl,
    is_toolchain_rule = True,
    attrs = {
        "java": attrs.string(default = "java"),
        "javac": attrs.string(default = "javac"),
        "jar": attrs.string(default = "jar"),
        # Written by `just bootstrap`; "unknown" until then.
        "version": attrs.string(default = "unknown"),
    },
)

def _platforms_impl(ctx):
    configuration = ConfigurationInfo(constraints = {}, values = {})
    platform = ExecutionPlatformInfo(
        label = ctx.label.raw_target(),
        configuration = configuration,
        executor_config = CommandExecutorConfig(
            local_enabled = True,
            remote_enabled = read_config("build", "remote_enabled", "false").lower() == "true",
        ),
    )
    return [DefaultInfo(), ExecutionPlatformRegistrationInfo(platforms = [platform])]

platforms = rule(impl = _platforms_impl, attrs = {})

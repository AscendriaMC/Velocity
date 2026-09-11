plugins {
    `java-library`
    id("velocity-checkstyle") apply false
    id("velocity-spotless") apply false
}

subprojects {
    apply<JavaLibraryPlugin>()

    apply(plugin = "velocity-checkstyle")
    apply(plugin = "velocity-spotless")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        testImplementation(rootProject.libs.junit)
        // Only so it lands in testRuntimeClasspath, which is what third-party/jvm is generated
        // from: buck2's java_test runs the tests through this CLI. Gradle uses its own launcher.
        testRuntimeOnly(rootProject.libs.junit.console)
    }

    testing.suites.named<JvmTestSuite>("test") {
        useJUnitJupiter()
        targets.all {
            testTask.configure {
                reports.junitXml.required = true
            }
        }
    }
}

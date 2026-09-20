import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    alias(libs.plugins.test.logger)
    id("org.jetbrains.intellij.platform.base")
}

base {
    archivesName.set("${rootProject.name}.${project.name}")
}

sourceSets {
    test {
        java.srcDir("tests")
    }
}

// This module runs in IntelliJ's external build process, not the IDE, so main is pinned to
// jpsJavaLevel (gradle.properties) rather than the root's platform-derived level. Only main:
// the tests run on the Gradle JVM and use later language features.
val jpsJavaLevel = property("jpsJavaLevel") as String
tasks.named<JavaCompile>("compileJava") { options.release.set(jpsJavaLevel.toInt()) }

// Ensuring the necessary tasks are executed before tests
tasks.test {
    // Elixir stdlib ebin comes from the SDK resolved by the root `resolveElixirErlangSdks`
    // (mise/PATH/env aware) - no from-source Elixir build. Resolved paths are known only at
    // execution, so set env in a doFirst reading the resolver's output.
    dependsOn(":resolveElixirErlangSdks")

    useJUnit()
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )

    val sdkProps = rootProject.layout.buildDirectory.file("elixir-erlang-sdks.properties")
    doFirst {
        environment(sdk.elixirTestEnvironment(sdkProps.get().asFile))
    }

    // Same reason as the root `test` task: the versions arrive as environment variables set in that
    // doFirst, so without declaring them a version switch leaves this task UP-TO-DATE (or restores the
    // previous pair's results from the build cache) and reports the wrong pair's numbers.
    inputs.property("elixirVersion", rootProject.extra["expectedElixirVersion"])
    inputs.property("otpVersion", rootProject.extra["expectedOtpVersion"])

    // This project applies only the platform `base` plugin, so nothing sets up a sandbox: without these
    // the system and config paths default into the extracted IDE, and TestLoggerFactory's testlog/ lands
    // in the Gradle transform CI caches, which Gradle then reports as modified and re-extracts. Set in
    // doFirst so the absolute paths stay out of the build-cache key.
    val ideaPaths = layout.buildDirectory.dir("idea-test")
    doFirst {
        systemProperty("idea.system.path", ideaPaths.get().dir("system").asFile.absolutePath)
        systemProperty("idea.config.path", ideaPaths.get().dir("config").asFile.absolutePath)
    }

    include("**/*Test.class")

    // Allow the task to succeed when a global --tests filter matches nothing in this subproject
    // (e.g., when running `gradlew test --tests SomeTestInRootProject`)
    filter.isFailOnNoMatchingTests = false
}

configurations {
    named("testRuntimeClasspath") {
        extendsFrom(
            getByName("intellijPlatformTestClasspath"),
            getByName("intellijPlatformTestRuntimeFixClasspath")
        )
    }
}

dependencies {
    intellijPlatform {
        testFramework(TestFrameworkType.Platform)
        bundledPlugins("com.intellij.java")
    }
    implementation(project(":jps-shared"))
    testImplementation(libs.junit)
}

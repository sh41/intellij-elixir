import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm")
    alias(libs.plugins.test.logger)
    id("org.jetbrains.intellij.platform.base")
}
base {
    archivesName.set("${rootProject.name}.${project.name}")
}

tasks.testClasses {
    enabled = false
}

// Restricts stdlib references to what 2026.1 (minimumSupported) bundles - the root project's
// own copy of this (build.gradle.kts) doesn't reach this separate project.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_3
    }
}

dependencies {
    // compileOnly: the external JPS build process this module runs in already gets kotlin-stdlib
    // from the platform (ClasspathBootstrap.addKotlinStdlib in intellij-community). implementation
    // would additionally leak a bundled copy onto main's runtime classpath and into the shipped
    // plugin, via implementation(project(":jps-shared")).
    compileOnly(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
}
// This module runs in IntelliJ's external build process, not the IDE, so main is pinned to
// jpsJavaLevel (gradle.properties) rather than the root's platform-derived level. Only main:
// the tests run on the Gradle JVM and use later language features.
val jpsJavaLevel = property("jpsJavaLevel") as String
tasks.named<JavaCompile>("compileJava") { options.release.set(jpsJavaLevel.toInt()) }
tasks.named<KotlinJvmCompile>("compileKotlin") {
    compilerOptions {
        // jvmTarget sets only the class-file version; -Xjdk-release also restricts the JDK API
        // surface, as javac's --release does.
        jvmTarget.set(JvmTarget.fromTarget(jpsJavaLevel))
        freeCompilerArgs.add("-Xjdk-release=$jpsJavaLevel")
    }
}

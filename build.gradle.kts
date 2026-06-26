import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.File

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType").get(),
            providers.gradleProperty("platformVersion").get(),
        )
        testFramework(TestFrameworkType.Platform)
    }

    // Bundle only serialization; exclude the Kotlin stdlib (provided by the platform).
    // Do NOT bundle kotlinx-coroutines — the platform provides it, and shipping a
    // second copy breaks coroutine-based extension points (e.g. ProjectActivity).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            val until = providers.gradleProperty("pluginUntilBuild").orNull
            if (until.isNullOrBlank()) untilBuild = provider { null } else untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10.2"
    }

    // `./gradlew runIde` opens a throwaway sample project so the Karato tool
    // window is visible immediately (no New Project wizard). The mock transports
    // under tools/ can be pointed at via Settings → Tools → Karato.
    runIde {
        val sampleDir = layout.buildDirectory.dir("sandbox-project")
        doFirst {
            val dir = sampleDir.get().asFile
            dir.mkdirs()
            File(dir, "README.md").writeText(
                "# Karato sandbox\n\nA throwaway project for testing the Karato plugin.\n",
            )
            File(dir, "hello.txt").writeText("edit me\n")
        }
        argumentProviders.add(
            CommandLineArgumentProvider { listOf(sampleDir.get().asFile.absolutePath) },
        )
    }
}

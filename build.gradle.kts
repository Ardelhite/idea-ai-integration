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

        // Marketplace publishing toolchain (2.x requires these explicitly):
        //  - zipSigner: signs the distribution before publishPlugin
        //  - pluginVerifier: binary-compatibility check across target IDEs
        zipSigner()
        pluginVerifier()
    }

    // Bundle only serialization; exclude the Kotlin stdlib (provided by the platform).
    // Do NOT bundle kotlinx-coroutines — the platform provides it, and shipping a
    // second copy breaks coroutine-based extension points (e.g. ProjectActivity).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
    // JetBrains' Markdown parser (org.intellij.markdown) for rendering chat messages.
    // The IDE already bundles this package, so compile against it but DON'T ship a copy
    // (bundling an IDE package trips a Plugin Verifier warning); the IDE provides it at runtime.
    compileOnly("org.jetbrains:markdown:0.7.3") {
        exclude(group = "org.jetbrains.kotlin")
    }

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    // Building the settings-search index launches a headless IDE, which crashes on this
    // 2024.2.x/JDK combo via the bundled Gradle plugin (same GradleJvmSupportMatrix issue
    // worked around for runIde). The index is optional, so turn the whole feature off —
    // this is the supported switch; disabling only the task leaves prepareJarSearchableOptions
    // expecting a missing input dir.
    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        changeNotes = """
            <ul>
              <li><b>0.1.0</b> — Initial release. Multi-tab chat tool window for the
                  Sakana AI Fugu agent (via the Codex CLI): inline tool cards,
                  interactive approvals, GUI-only setup, Claude/Codex project-file
                  awareness (CLAUDE.md, .claude/, memory) and MCP-server mirroring.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            val until = providers.gradleProperty("pluginUntilBuild").orNull
            if (until.isNullOrBlank()) untilBuild = provider { null } else untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    // Plugin signing — credentials come from the environment, never the repo.
    // signPlugin runs automatically before publishPlugin when these are present;
    // it is skipped (with no error) during normal local builds.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Marketplace upload (2nd release onward; the first upload must be manual).
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // channels = listOf("beta") // uncomment to publish to the beta channel
    }

    // `./gradlew verifyPlugin` checks binary compatibility against target IDEs.
    // `recommended()` pulls the newest IDEs, which don't resolve here (macOS aarch64
    // ships .dmg-only builds and the latest coordinate may be unavailable), so verify
    // against the build target. JetBrains re-runs the full-range verifier during the
    // Marketplace review.
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion").get())
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10.2"
    }

    // Wipes the sandbox IDE's user state — the sample project (incl. its Karato
    // session), and the IDE config (app settings, the stored API key, layout) and
    // caches — so the next launch starts from scratch (Set up Fugu → Install Codex).
    // The deployed plugin under .../plugins is intentionally left alone.
    val sandboxName = "${providers.gradleProperty("platformType").get()}-${providers.gradleProperty("platformVersion").get()}"
    val clearSandbox by registering(Delete::class) {
        delete(
            layout.buildDirectory.dir("sandbox-project"),
            layout.buildDirectory.dir("idea-sandbox/$sandboxName/config"),
            layout.buildDirectory.dir("idea-sandbox/$sandboxName/system"),
            layout.buildDirectory.dir("idea-sandbox/$sandboxName/log"),
        )
    }

    // `./gradlew runIde` opens a throwaway sample project so the Karato tool
    // window is visible immediately (no New Project wizard). The mock transports
    // under tools/ can be pointed at via Settings → Tools → Karato.
    runIde {
        // When both are requested ("Clear sample project" run config), clear first.
        mustRunAfter(clearSandbox)
        val sampleDir = layout.buildDirectory.dir("sandbox-project")
        val configDir = layout.buildDirectory.dir("idea-sandbox/$sandboxName/config")
        doFirst {
            val dir = sampleDir.get().asFile
            dir.mkdirs()
            File(dir, "README.md").writeText(
                "# Karato sandbox\n\nA throwaway project for testing the Karato plugin.\n",
            )
            File(dir, "hello.txt").writeText("edit me\n")
            // A project-scoped MCP server (Claude `.mcp.json` format) so the MCP-scope
            // dropdown has something to mirror into Codex. Playwright is a local stdio
            // launcher (no auth) — handy for testing.
            File(dir, ".mcp.json").writeText(
                """
                {
                  "mcpServers": {
                    "playwright": { "command": "npx", "args": ["@playwright/mcp@latest"] }
                  }
                }
                """.trimIndent() + "\n",
            )

            // The bundled Gradle plugin's JVM-support matrix crashes parsing JDK 25 on
            // 2024.2.x (GradleJvmSupportMatrix → JavaVersion.parse("25"), owned by plugin
            // com.intellij.gradle). Karato doesn't need Gradle in the sandbox, so disable
            // it (and its dependents) to silence the noise.
            val cfg = configDir.get().asFile.also { it.mkdirs() }
            val disabled = File(cfg, "disabled_plugins.txt")
            val want = listOf("com.intellij.gradle", "org.jetbrains.plugins.gradle")
            val ids = (if (disabled.isFile) disabled.readLines() else emptyList()).map { it.trim() }
                .filter { it.isNotEmpty() }.toMutableSet()
            if (ids.addAll(want)) disabled.writeText(ids.joinToString("\n") + "\n")
        }
        argumentProviders.add(
            CommandLineArgumentProvider { listOf(sampleDir.get().asFile.absolutePath) },
        )
    }
}

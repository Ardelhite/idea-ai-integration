# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`README.md` is the canonical reference (full protocol tables, settings, mocks, install). This file
covers only what isn't obvious from the README or a directory listing.

## Build & run

JDK 21 is mandatory — the Gradle toolchain pins it and this machine resolves it via:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # required before every gradle invocation
./gradlew buildPlugin   # → build/distributions/fugu-intellij-0.1.0.zip
./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew test          # JUnit4 + IntelliJ Platform test framework
./gradlew test --tests "ai.sanakan.fugu.SomeTest"   # single test class
```

There are no committed tests yet, but the test framework (`junit:4.13.2`, `TestFrameworkType.Platform`)
is wired in `build.gradle.kts`. Gradle config-cache and build-cache are **on** — if you change build
logic and see stale behavior, run with `--no-configuration-cache`.

## Architecture (what the source layout doesn't show)

This is a thin IntelliJ plugin that wraps the **Codex CLI** (Sakana/Fugu provider) as a subprocess and
renders its streamed events as a chat tool window. Three layers, one data flow:

```
ui/ (Swing, EDT) ──Listener──> core/FuguSession ──FuguTransport──> cli/ ──GeneralCommandLine──> codex
```

- **`cli/FuguTransport`** is the seam. Two implementations are swapped by the `transportKind` setting:
  - `FuguCliClient` — `codex exec --json`, one process per turn, no approvals.
  - `FuguAppServerClient` — long-lived `codex app-server` JSON-RPC, supports interactive approvals.
  Both normalize their different wire formats into the shared `FuguEvent` set and report via
  `FuguAgentListener`. When adding a transport feature, keep the normalization in the cli layer so
  `FuguSession` stays transport-agnostic.

- **`core/FuguSession`** (`@Service(PROJECT)`, `PersistentStateComponent`) is the hub: holds the
  transcript, dispatches turns, owns the approval dialog, and persists `messages` + Codex `threadId`
  into the **workspace file** (`StoragePathMacros.WORKSPACE_FILE`) so conversations survive restarts.
  The next turn resumes the same Codex thread via the captured `threadId`.

- **Setup is GUI-only — no terminal.** `ui/FuguSetupDialog` (from the banner/toolbar "Set up Fugu")
  drives the three prerequisites aggregated by `core/FuguSetup.isReady()`: API key
  (`settings/FuguSecrets` → PasswordSafe, verified via `core/SakanaApi` → `/v1/models`), the Codex
  binary (`core/CodexInstaller`), and the `[model_providers.sakana]` block written idempotently by
  `core/CodexConfig` into `$CODEX_HOME/config.toml`. The stored key is injected as `SAKANA_API_KEY`
  into **both** the agent and installer processes via `FuguEnv.codexEnvironment()`. New setup
  prerequisites should be surfaced through `FuguSetup` so the banner stays accurate.

## Conventions that will bite you

- **EDT discipline.** Transport callbacks (`FuguAgentListener`) fire on process reader threads, never
  the EDT. `FuguSession` funnels every callback through `onEdt { }` before touching Swing state. Any
  new listener code that mutates UI must do the same. Conversely, process launch can block, so
  `FuguSession.submit` dispatches `client.send` onto a pooled thread off the EDT.

- **Lenient event parsing.** Codex's experimental JSON schema is parsed leniently in `cli/StreamJson.kt`
  — unknown event types/fields/versions must degrade to `FuguEvent.Other`/`Raw`, not throw. Preserve
  this when extending parsing; the schema drifts across Codex builds.

- **Version-sensitive enum casing.** The app-server `approvalPolicy`/`sandbox` and `ApprovalDecision`
  wire values (kebab-case / `acceptForSession`) track the Codex `main` branch and are fragile across
  builds. If a turn fails to start, regenerate truth with `codex app-server generate-json-schema`.

- **`<extensions>` must use `defaultExtensionNs` (not `defaultExtensionId`).** With the wrong attribute
  the plugin still loads by name but EVERY extension is silently dropped (no tool window, no services,
  no settings page) — symptomless except nothing works. Also **never bundle `kotlinx-coroutines`** (or
  the Kotlin stdlib): the platform provides them, and a second copy breaks coroutine-based EPs like
  `ProjectActivity`. `build.gradle.kts` excludes `org.jetbrains.kotlin*` from the serialization dep.

- **The Fugu installer needs a TTY.** `sakana.ai/fugu/install` re-`exec`s with `< /dev/tty`, which
  fails from a terminal-less subprocess. `core/CodexInstaller` bypasses the bootstrap: clone
  `SakanaAI/fugu` and run `scripts/install.sh --yes --force` with `SAKANA_API_KEY` injected. The key
  must be set first (`--yes` + missing key → the installer `die`s).

- **Secrets.** The Sakana API key lives in PasswordSafe (`settings/FuguSecrets.kt`) and is injected
  into the Codex process env as `SAKANA_API_KEY` — never persisted in settings state.

## Testing changes without the real CLI

`tools/mock-codex-appserver` (app-server JSON-RPC + approval flow) and `tools/mock-fugu` (`exec --json`)
let you exercise both transports offline. Point **Settings → Tools → Karato → Codex CLI path**
at the matching mock and turn the Sakana provider override **off**. See README "Trying it without the
real CLI".

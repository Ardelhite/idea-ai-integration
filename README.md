# Karato — Agent for Sakana AI fugu

<!-- After the plugin is approved, replace 00000 with the numeric Marketplace ID
     (shown in the plugin page URL) so these badges render. -->
[![JetBrains Marketplace Version](https://img.shields.io/jetbrains/plugin/v/00000.svg?label=Marketplace)](https://plugins.jetbrains.com/plugin/00000)
[![JetBrains Marketplace Downloads](https://img.shields.io/jetbrains/plugin/d/00000.svg)](https://plugins.jetbrains.com/plugin/00000)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%2B-orange.svg)](https://plugins.jetbrains.com/docs/intellij/)

**Karato** brings the **Sakana AI Fugu** coding agent (and **fugu-ultra**) into JetBrains IDEs
as a multi-tab chat tool window. It drives the OpenAI **Codex CLI** — configured with Sakana's
Fugu provider — as a subprocess and renders its streamed events live: chat with the agent, watch
it run commands and edit files in your project, approve actions inline, and review changes as they
happen.

> Fugu has no standalone binary — it's a multi-agent system reached through an OpenAI-compatible
> API and used for coding via Codex with the Sakana provider. Karato wraps that for you, end to end,
> from the IDE — **no terminal required**.

## Screenshots

<!-- Add images under docs/img/ and they'll render here. -->
| Chat & inline tool cards | MCP servers (Playwright) |
|---|---|
| ![Chat with inline tool cards](docs/img/chat.png) | ![Using an MCP server](docs/img/mcp.png) |

| GUI-only setup | Per-tab sessions |
|---|---|
| ![Set up Fugu dialog](docs/img/setup.png) | ![Multiple chat tabs](docs/img/tabs.png) |

## Features

- **Multi-tab chat** — each tab is an independent conversation (own transcript + Codex thread);
  open with **+**, close with the tab's **×** (ends the session and discards its log). A spinner
  by the tab number shows which tab is working.
- **Inline tool cards** — run-command / web-search / file-edit / MCP-tool items appear at the point
  they happen in the response, not piled at the bottom. File-change cards open the file on click.
- **Interactive approvals** — with the app-server transport you approve / decline edits and commands
  inline before they run.
- **GUI-only setup** — install Codex, write the provider config, and store your API key from a dialog;
  no shell commands.
- **Reads your Claude/Codex project files** — `CLAUDE.md`, `.claude/`, and project memory are sent to
  the agent automatically so you don't paste them every turn (Codex reads its own `AGENTS.md` natively).
- **MCP server mirroring** — reuses the MCP servers you already configured for Claude Code (e.g.
  **Playwright**) by mirroring them into Codex, with an **Off / Project / All** scope dropdown.
- **Configurable send shortcut** — `Enter` or `⌘/Alt + Enter` to send, with a hint under the button.
- **Persistent** — tabs, transcripts and Codex threads survive IDE restarts.

## Install

**From the JetBrains Marketplace** (recommended, once approved):
Settings → **Plugins** → **Marketplace** → search **“Karato”** → **Install**.

**From disk:**
1. Download `Karato-<version>.zip` (or build it — see below).
2. Settings → **Plugins** → ⚙ → **Install Plugin from Disk…** → pick the ZIP → restart.

**Build it yourself:**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # JDK 21 is required
./gradlew buildPlugin     # → build/distributions/*.zip
./gradlew runIde          # launches a sandbox IDE with the plugin loaded
```

Open the **Karato** tool window on the right and start chatting.

## Setup — no terminal required

Open the **Karato** tool window and click **Set up Fugu** (also in the warning banner shown until
setup is complete). The dialog does everything that used to require shell commands:

1. **Sakana API key** — paste a key (from `console.sakana.ai/api-keys`, linked in the dialog) and
   click **Verify**; it pings `GET /v1/models`. The key is stored in **PasswordSafe** and injected
   into every Codex process as `SAKANA_API_KEY` — never written to disk in cleartext.
2. **Codex CLI** — **Install Codex CLI** runs the Fugu installer (with the key already in the
   environment) and streams the log into the dialog.
3. **Sakana provider** — **Write provider config** appends the `[model_providers.sakana]` block to
   `$CODEX_HOME/config.toml` (default `~/.codex/config.toml`), idempotently and preserving your
   existing config.

### Requirements

- A **Sakana account / API key** and network access.
- **JDK 21** to build (the Gradle toolchain pins it).
- macOS/Linux for the one-click Codex installer; on Windows, install Codex separately and use
  **Write provider config** + the API key field.

## Using MCP servers (e.g. Playwright)

Karato mirrors the MCP servers you configured for Claude Code into Codex, so they work here too.
The **MCP** dropdown in the header (left of **+**) sets the scope, applied immediately:

| Scope | Servers used |
|---|---|
| **Off** | none |
| **Project** | this project's servers — `<project>/.mcp.json` + `~/.claude.json` project-local |
| **All** | the above **plus** user-global servers (`~/.claude.json` `mcpServers`) |

The selected servers are written into `~/.codex/config.toml` (in a managed region that preserves your
hand-written entries) and each tab's Codex process reloads to pick them up. Servers added directly to
Codex (`codex mcp add …`) keep working regardless of the dropdown.

> Note: Claude’s **claude.ai connectors** (OAuth-based, e.g. Gmail/Drive) can't be mirrored to Codex.
> Use local/stdio servers — e.g. add `{"mcpServers":{"playwright":{"command":"npx","args":["@playwright/mcp@latest"]}}}`
> to your project's `.mcp.json`.

## Configuration — Settings → Tools → Karato

| Setting | Default | Notes |
|---|---|---|
| Transport | codex app-server | `app-server` (interactive approvals) or `exec` (headless) |
| Codex CLI path | `codex` | or `codex-fugu`, or an absolute path |
| Model | `fugu` | `fugu` / `fugu-ultra` (also fetched live from your account) |
| Permission mode | Ask before each edit/command | sandbox + approval policy (see below) |
| Send shortcut | Enter to send | or `⌘/Alt + Enter` (Enter inserts a newline) |
| Load Claude/Codex project files | on | inject `CLAUDE.md` / `.claude/` / memory at thread start |
| Sakana provider override | on | adds `-c model_provider=sakana` (turn **off** for `codex-fugu`) |
| Sakana API key | — | stored in PasswordSafe, injected as `SAKANA_API_KEY` |
| Extra CLI args | — | appended verbatim |

The header also has a **CLEAR** button (clears the current tab and starts a fresh Codex thread) and the
**MCP** scope dropdown described above.

Permission modes (sandbox + approval policy):

| Mode | sandbox | approval | notes |
|---|---|---|---|
| Ask before each edit/command | workspace-write | on-request | **app-server only** — prompts inline; `exec` clamps to never |
| Read-only (plan) | read-only | never | no edits |
| Auto-edit workspace | workspace-write | never | edits without prompting |
| Full access | danger-full-access | never | bypasses the sandbox |

## Data & privacy

Karato is a client for your own accounts and tools. To function it: sends your prompts and the selected
project context to the **Sakana AI API** via the Codex CLI; runs the **Codex CLI** and, when MCP is
enabled, MCP servers (e.g. via `npx`) as subprocesses; reads the Claude/Codex configuration files listed
above; and writes MCP entries to `~/.codex/config.toml`. Your API key lives in the IDE’s PasswordSafe and
is passed to Codex via an environment variable. **Karato collects no telemetry and sends nothing to the
author.**

## Architecture

```
ui/ (Swing, EDT) ──Listener──> core/FuguSession ──FuguTransport──> cli/ ──GeneralCommandLine──> codex
                                      ▲
                        owned/persisted by FuguSessionManager (one tab each)
```

- **`cli/FuguTransport`** — the seam swapped by the `transportKind` setting:
  `FuguAppServerClient` (long-lived `codex app-server` JSON-RPC, interactive approvals) and
  `FuguCliClient` (`codex exec --json`, one process per turn). Both normalize their different wire
  formats into the shared `FuguEvent` set.
- **`core/FuguSessionManager`** (`@Service`, persisted to the workspace file) owns the ordered list of
  chat tabs; the tool window mirrors it as content tabs.
- **`core/FuguSession`** — one tab: transcript, turn dispatch, approval/prompt callbacks, and its own
  Codex `thread_id` for resume. The transport is created lazily on first send.
- **`core/AgentContext`** / **`core/McpConfig`** — gather Claude/Codex project files and mirror MCP
  servers, respectively.

### Protocol

The **app-server** transport keeps one process and speaks JSON-RPC:
`initialize → initialized → thread/start|thread/resume → turn/start`, with `item/*` and `turn/*`
notifications and `item/.../requestApproval` requests. The **exec** transport spawns one process per
turn and resumes via the captured `thread_id`. `stdout` is JSON Lines, parsed leniently into a small
`FuguEvent` set (unknown fields/versions degrade gracefully).

## Trying it without the real CLI

Two mocks ship in `tools/`:
- `tools/mock-codex-appserver` — the **app-server JSON-RPC** protocol incl. the **approval flow**
  (Transport = `app-server`).
- `tools/mock-fugu` — the **`codex exec --json`** protocol (Transport = `exec`).

Point **Codex CLI path** at the matching mock and turn the Sakana override **off**.

## License

[Apache License 2.0](LICENSE). “Sakana AI”, “Fugu”, and “Codex” are names of their respective owners,
used only to describe the services this plugin integrates with; this project is not affiliated with or
endorsed by them.

# Sanakan AI Fugu — IntelliJ Plugin

IntelliJ Platform plugin that integrates **Sakana AI Fugu** (and **fugu-ultra**)
into the IDE. Fugu has no standalone binary — it is a multi-agent system exposed
through an OpenAI-compatible API and used for coding via the **Codex CLI** with
the Sakana provider. This plugin drives Codex as a subprocess, shows the
conversation in a tool window, renders each command/file-change item as a card,
and reflects edits back into the IDE.

Two transports are selectable:
- **`codex app-server`** (default) — a long-lived JSON-RPC service supporting
  **interactive approvals** (approve/decline before each edit or command).
- **`codex exec --json`** — headless, one process per turn, no approvals.

The design follows open-source Claude-Code GUIs (wrap the agent CLI, stream its
events, render a chat surface) — adapted to Codex's `thread/turn/item` protocol.

## Architecture

```
┌──────────────────────────────────────────────┐
│  ui/                                            │
│   FuguToolWindowFactory → FuguChatPanel         │  Swing transcript + composer
│   MessageComponent (bubbles, tool cards)        │
└───────────────┬─────────────────────────────────┘
                │ FuguAgentListener (EDT) — incl. onApproval
┌───────────────▼─────────────────────────────────┐
│  core/                                           │
│   FuguSession  (@Service, persisted per project) │  state, dispatch, approval UI
│   ChatMessage / ToolCall                         │
└───────────────┬─────────────────────────────────┘
                │ FuguTransport
┌───────────────▼─────────────────────────────────┐
│  cli/                                            │
│   FuguAppServerClient  (JSON-RPC, approvals)     │  initialize→thread→turn
│   FuguCliClient        (one `codex exec` /turn)  │  spawn + JSONL pump + resume
│   FuguEvent / parsers                            │
└───────────────┬─────────────────────────────────┘
                │ GeneralCommandLine
        ┌───────▼────────────────────────────────┐
        │ codex app-server | codex exec  (Sakana → Fugu) │
        └─────────────────────────────────────────┘

  settings/  FuguSettings (persisted) + FuguConfigurable (Tools → Sanakan AI Fugu)
```

### Protocol

The **app-server** transport (default) keeps one process and speaks JSON-RPC:
`initialize → initialized → thread/start|thread/resume → turn/start`, with
`item/*` and `turn/*` notifications and `item/.../requestApproval` requests — see
[Approvals](#configuration--settings--tools--sanakan-ai-fugu) below.

The **exec** transport spawns one process per turn; the first turn starts a
thread, later turns resume it via the captured `thread_id`:

```bash
# first turn
codex exec --json -m fugu -c model_provider=sakana \
     --sandbox workspace-write -a never --skip-git-repo-check "<prompt>"

# subsequent turns
codex exec resume <thread_id> --json -m fugu … "<follow-up>"
```

`stdout` is JSON Lines. The parser normalizes Codex's experimental schema into a
small `FuguEvent` set (lenient — unknown fields/versions degrade gracefully):

| Codex event | → FuguEvent |
|---|---|
| `thread.started` (`thread_id`) | `Init` (captured for `resume`) |
| `item.completed` `item_type=agent_message` | `AgentMessage` |
| `item.started` / `item.completed` `command_execution` / `file_change` / `mcp_tool_call` / `web_search` / `todo_list` | `ToolStarted` / `ToolCompleted` |
| `turn.completed` (`usage`) | `Result` (success) |
| `turn.failed` / `error` | `Result` (error) |

## Setup — no terminal required

Open the **Fugu** tool window and click **Set up Fugu** (also on the toolbar, and
in the warning banner shown until setup is complete). The dialog does everything
that used to require shell commands:

1. **Sakana API key** — paste a key (from `console.sakana.ai/api-keys`, linked in
   the dialog) and click **Verify**; it pings `GET /v1/models`. The key is stored
   in PasswordSafe and injected into every Codex process as `SAKANA_API_KEY` — so
   no `export` is needed, and it's never written to disk in cleartext.
2. **Codex CLI** — **Install Codex CLI** runs the Fugu installer (with the key
   already in the environment) and streams the log into the dialog.
3. **Sakana provider** — **Write provider config** appends the
   `[model_providers.sakana]` block to `$CODEX_HOME/config.toml` (default
   `~/.codex/config.toml`), idempotently and preserving your existing config — so
   you never hand-edit TOML.

The chat composer stays usable, but the banner reminds you which of the three
pieces are still missing until `FuguSetup.isReady()`.

### Requirements

- JDK 21 (Gradle toolchain; this machine: `/opt/homebrew/opt/openjdk@21`)
- macOS/Linux (the Codex installer supports these; on Windows, install Codex
  separately and use **Write provider config** + the API key field)

> Equivalent manual commands, if you prefer the terminal: `export SAKANA_API_KEY=…`,
> `curl -fsSL https://sakana.ai/fugu/install | bash`. See
> <https://console.sakana.ai/get-started>.

## Build & run

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

./gradlew buildPlugin     # → build/distributions/fugu-intellij-0.1.0.zip
./gradlew runIde          # launches a sandbox IDE with the plugin loaded
```

Open the **Fugu** tool window on the right and start chatting.

### Configuration — Settings → Tools → Sanakan AI Fugu

| Setting | Default | Notes |
|---|---|---|
| Codex CLI path | `codex` | or `codex-fugu`, or an absolute path |
| Model | `fugu` | `fugu` / `fugu-ultra` (editable) → `-m` |
| Transport | codex app-server | `app-server` (approvals) or `exec` (headless) |
| Permission mode | Ask before each edit/command | sandbox + approval policy (see below) |
| Sakana provider override | on | adds `-c model_provider=sakana` (turn **off** for `codex-fugu`) |
| Sakana API key | — | stored in PasswordSafe, injected into Codex as `SAKANA_API_KEY` |
| Extra CLI args | — | appended verbatim |

Permission modes (sandbox + approval policy):

| Mode | sandbox | approval | notes |
|---|---|---|---|
| Ask before each edit/command | workspace-write | on-request | **app-server only** — prompts via a dialog; `exec` clamps to never |
| Read-only (plan) | read-only | never | no edits |
| Auto-edit workspace | workspace-write | never | edits without prompting |
| Full access | danger-full-access | never | bypasses the sandbox |

**Approvals.** With the app-server transport + "Ask" mode, the server sends
`item/fileChange/requestApproval` / `item/commandExecution/requestApproval`
before acting; the plugin shows an **Approve / Approve for session / Decline**
dialog and replies `{ "decision": … }`. (Enum casing for `approvalPolicy` /
`sandbox` is version-sensitive across Codex builds — kebab-case here follows the
`main` source; pin with `codex app-server generate-json-schema` if a turn fails
to start.)

## Trying it without the real CLI

Two mocks ship in `tools/`, both off-by-default for the Sakana provider:

- `tools/mock-codex-appserver` — speaks the **app-server JSON-RPC** protocol and
  exercises the **approval flow** (asks to apply a README.md change, then applies
  or skips based on your decision). Use with Transport = `app-server`.
- `tools/mock-fugu` — speaks the **`codex exec --json`** protocol. Use with
  Transport = `exec`.

Point **Codex CLI path** at the matching mock and turn the Sakana override off:

```
Transport:        codex app-server
Codex CLI path:   <repo>/tools/mock-codex-appserver
Sakana provider:  ☐
```

## A note on auth

Sakana's console supports Google SSO, but there is **no API to auto-fetch a key
or account usage** — keys are created manually in the console (the Setup dialog
deep-links there) and pasted once; the plugin stores them in PasswordSafe. The
only usage signal available is the per-request token `usage` Codex reports, which
the plugin tallies and shows in the status bar after each turn.

## Persistence

The transcript and the Codex `thread_id` are saved per project (workspace file),
so the conversation survives tool-window reopens and IDE restarts; the next turn
resumes the same Codex thread. **New Conversation** clears it and starts fresh.

## Status

Implemented: chat, file editing, setup/install, persistence, per-request usage,
secure API key, and **interactive approvals** via the app-server transport
(approve/decline before each edit or command). File-change cards open the
affected file on click; the VFS is refreshed after edits.

**Caveat.** The app-server protocol is experimental and version-sensitive; this
client follows the `main`-branch schema and has been validated against the
included `tools/mock-codex-appserver`, but not yet against a specific live Codex
build. If a turn fails to start, switch Transport to `exec`, or pin the schema
with `codex app-server generate-json-schema`. Next candidates: streaming agent
deltas, richer diff preview in the approval dialog, and `acceptForSession`
memory so a session-approved tool isn't re-prompted.

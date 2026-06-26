# Marketplace listing — copy & assets

Draft text and assets for the JetBrains Marketplace page. The listing description is
sourced from `plugin.xml` `<description>` on upload, but you can also edit the HTML on
the plugin page — paste the HTML block below if you want the longer version there.

---

## Name
`Karato - Agent for Sakana AI fugu`

## Tagline (one line)
An in-IDE chat agent for Sakana AI Fugu — powered by the Codex CLI, with inline tool
cards, approvals, project-file awareness, and MCP support.

## Suggested category & tags
- **Category:** AI Coding Assistants (or “Code tools”)
- **Tags:** `AI`, `Agent`, `Chat`, `Codex`, `MCP`, `LLM`, `Coding Assistant`

---

## Short description (≈ 270 chars, for search results)
Karato brings the Sakana AI Fugu coding agent into your IDE as a multi-tab chat tool
window. It drives the Codex CLI, shows commands and edits as inline cards, lets you
approve actions, reads your CLAUDE.md/.claude project files, and reuses your Claude MCP
servers (e.g. Playwright).

---

## Full description (HTML — mirrors plugin.xml, paste on the listing if desired)
```html
<p><b>Karato</b> is an in-IDE agent for <b>Sakana AI Fugu</b> (and <b>fugu-ultra</b>).
It drives the OpenAI <b>Codex CLI</b> — configured with Sakana's Fugu provider — as a
subprocess and renders its streamed events as a multi-tab chat tool window: chat with the
agent, watch it run commands and edit files in your project, approve actions inline, and
review changes as they happen.</p>

<h3>Features</h3>
<ul>
  <li><b>Multi-tab chat</b> — each tab is an independent conversation (own transcript and
      Codex thread). Open with <b>+</b>, close with the tab's <b>×</b>; a spinner by the
      tab number shows which tab is working.</li>
  <li><b>Inline tool cards</b> — run-command, web-search, file-edit and MCP-tool items
      appear at the point they happen in the response. File-change cards open the file on click.</li>
  <li><b>Interactive approvals</b> — approve or decline edits and commands inline before they run.</li>
  <li><b>GUI-only setup</b> — install Codex, write the provider config, and store your API
      key from a dialog; no shell commands.</li>
  <li><b>Reads your Claude/Codex project files</b> — <code>CLAUDE.md</code>, <code>.claude/</code>
      and project memory are sent to the agent automatically (Codex reads its own
      <code>AGENTS.md</code> natively).</li>
  <li><b>MCP server mirroring</b> — reuse the MCP servers you configured for Claude Code
      (e.g. <b>Playwright</b>) via an Off / Project / All scope dropdown.</li>
  <li><b>Configurable send shortcut</b> and persistent tabs/threads across restarts.</li>
</ul>

<h3>Requirements</h3>
<p>A Sakana account / API key and network access. The plugin guides you through installing
the Codex CLI and writing its provider config from the IDE.</p>

<h3>Data &amp; external services</h3>
<p>Karato is a client for your own accounts and tools. It sends your prompts and the selected
project context to the <b>Sakana AI API</b> via the Codex CLI; runs the Codex CLI and, when MCP
is enabled, MCP servers (e.g. via <code>npx</code>) as subprocesses; reads the listed Claude/Codex
config files; and writes MCP entries to <code>~/.codex/config.toml</code>. The API key is stored in
the IDE's PasswordSafe. <b>Karato collects no telemetry and sends nothing to the author.</b></p>

<p>Open source (Apache-2.0):
<a href="https://github.com/Ardelhite/idea-ai-integration">github.com/Ardelhite/idea-ai-integration</a></p>
```

---

## Screenshot captions (upload order — first image leads the page)
1. **chat.png** — “Chat with the agent; commands and file edits appear as inline cards.”
2. **mcp.png** — “Reuse your Claude MCP servers — here Playwright drives a browser.”
3. **setup.png** — “One dialog: API key, install Codex, write the provider config — no terminal.”
4. **tabs.png** — “Independent chat tabs, each its own Codex thread; a spinner marks the active one.”

## Change notes (for this version)
`0.1.0` — Initial release: multi-tab chat tool window for the Sakana AI Fugu agent (via the
Codex CLI) — inline tool cards, interactive approvals, GUI-only setup, Claude/Codex project-file
awareness (CLAUDE.md, .claude/, memory), and MCP-server mirroring.

## Links to fill in on the listing
- **Plugin homepage / repo:** https://github.com/Ardelhite/idea-ai-integration
- **Issue tracker:** https://github.com/Ardelhite/idea-ai-integration/issues
- **License:** Apache-2.0

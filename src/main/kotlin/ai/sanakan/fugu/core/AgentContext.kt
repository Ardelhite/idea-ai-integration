package ai.sanakan.fugu.core

import com.intellij.openapi.project.Project
import java.io.File

/** The gathered agent files plus the labels of what was included (for a status line). */
data class AgentContextResult(val text: String, val files: List<String>)

/**
 * Aggregates the agent instruction & memory files that Claude Code (and Codex)
 * keep in a project, so Fugu honours them automatically instead of the user
 * pasting them into every prompt.
 *
 * Codex already reads its own `AGENTS.md` natively, so this fills the gap for the
 * Claude-ecosystem files Codex ignores:
 *  - `~/.claude/CLAUDE.md`                      (user-global memory)
 *  - `<project>/CLAUDE.md`, `CLAUDE.local.md`   (project memory)
 *  - `<project>/.claude/` markdown              (project instructions/commands)
 *  - `~/.claude/projects/<enc>/MEMORY.md` + memory markdown  (per-project memory)
 *
 * All reads are plain file I/O — call OFF the EDT.
 */
object AgentContext {
    private const val PER_FILE_CHARS = 200_000
    private const val TOTAL_CHARS = 800_000

    fun collect(project: Project): AgentContextResult {
        val base = project.basePath?.let { File(it) } ?: return AgentContextResult("", emptyList())
        val home = File(System.getProperty("user.home"))
        val encoded = base.absolutePath.replace('/', '-')

        val sources = buildList {
            add(File(home, ".claude/CLAUDE.md"))
            add(File(base, "CLAUDE.md"))
            add(File(base, "CLAUDE.local.md"))
            File(base, ".claude").listFiles { f -> f.isFile && f.extension == "md" }
                ?.sortedBy { it.name }?.forEach { add(it) }
            add(File(home, ".claude/projects/$encoded/MEMORY.md"))
            File(home, ".claude/projects/$encoded/memory").listFiles { f -> f.isFile && f.extension == "md" }
                ?.sortedBy { it.name }?.forEach { add(it) }
        }.distinctBy { it.absolutePath }

        val body = StringBuilder()
        val labels = mutableListOf<String>()
        var total = 0
        for (file in sources) {
            if (!file.isFile || total >= TOTAL_CHARS) continue
            val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val clipped = if (text.length > PER_FILE_CHARS) text.take(PER_FILE_CHARS) + "\n…(truncated)" else text
            val label = labelFor(file, base, home)
            body.append("\n===== ").append(label).append(" =====\n").append(clipped).append('\n')
            labels.add(label)
            total += clipped.length
        }
        if (labels.isEmpty()) return AgentContextResult("", emptyList())

        val text = buildString {
            append("The following are this project's agent instruction and memory files ")
            append("(written for Claude Code / Codex). Treat them as authoritative project ")
            append("instructions and persistent memory, exactly as those tools would, for this ")
            append("and every following turn.\n")
            append(body)
        }
        return AgentContextResult(text, labels)
    }

    private fun labelFor(file: File, base: File, home: File): String {
        val abs = file.absolutePath
        return when {
            abs.startsWith(base.absolutePath) -> abs.removePrefix(base.absolutePath).trimStart('/')
            abs.startsWith(home.absolutePath) -> "~/" + abs.removePrefix(home.absolutePath).trimStart('/')
            else -> file.name
        }
    }
}

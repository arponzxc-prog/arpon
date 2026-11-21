package com.example.data.ai.agent

import com.example.data.ai.IdentityResponder
import com.example.data.local.entity.ProjectEntity
import com.example.tools.ProjectSearchEngine
import java.io.File

/** Builds the project map, NEXA.md memory, system prompt and trims conversation history. */
object AgentContext {
    const val MEMORY_FILE = "NEXA.md"
    const val MAX_MAP_FILES = 300
    const val DEFAULT_BUDGET_CHARS = 60_000 // ~15k tokens of history; project map is separate

    /** File tree with a one-line summary for each file. */
    fun buildProjectMap(project: ProjectEntity?): String {
        if (project == null) return "Active project: none. You may create one with create_project (after asking the user and getting plan approval)."
        val root = File(project.rootPath)
        if (!root.isDirectory) return "Active project: ${project.name} (root folder is missing)."
        val files = ProjectSearchEngine.listFiles(root).filterNot { ProjectGuard.isSecret(it) }
            .sortedBy { it.relativeTo(root).path }.toList()
        return buildString {
            appendLine("Active project: ${project.name} | package ${project.packageName} | template ${project.templateType} | language ${project.language}")
            appendLine("Files (${files.size}):")
            files.take(MAX_MAP_FILES).forEach { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                val summary = oneLineSummary(f)
                appendLine("- $rel" + if (summary.isNotEmpty()) " — $summary" else "")
            }
            if (files.size > MAX_MAP_FILES) appendLine("… ${files.size - MAX_MAP_FILES} more files (use list_files / search_in_project).")
        }.trim()
    }

    fun readMemory(project: ProjectEntity?): String? {
        if (project == null) return null
        val f = File(project.rootPath, MEMORY_FILE)
        return if (f.isFile && f.length() < 20_000) f.readText().takeIf { it.isNotBlank() } else null
    }

    private val TEXT_EXT = setOf("kt", "kts", "java", "xml", "gradle", "html", "htm", "css", "js", "ts", "json", "md", "txt", "properties", "toml", "yml", "yaml")

    fun oneLineSummary(file: File): String {
        val ext = file.extension.lowercase()
        if (ext !in TEXT_EXT || file.length() > 300_000) return ""
        return try {
            file.useLines { seq ->
                val lines = seq.take(60).toList()
                val pkgLine = lines.firstOrNull { it.startsWith("package ") }
                val decl = lines.firstOrNull {
                    Regex("^\\s*(public |internal |private |abstract |open |data |sealed |enum )*(class|object|interface|fun)\\s+\\w+").containsMatchIn(it)
                }
                val title = lines.firstOrNull { it.contains("<title>", true) }
                val comment = lines.firstOrNull { it.trim().startsWith("//") || it.trim().startsWith("*") && !it.trim().startsWith("*/") }
                val text = when {
                    decl != null -> decl.trim()
                    title != null -> title.trim()
                    comment != null -> comment.trim().trimStart('/', '*', ' ')
                    pkgLine != null -> pkgLine.trim()
                    else -> ""
                }
                text.take(90)
            }
        } catch (_: Exception) { "" }
    }

    fun systemPrompt(project: ProjectEntity?, memory: String?, currentFile: String?, selectedSnippet: String? = null): String {
        val rules = """
You are Nexa AI, an autonomous senior Android & Web engineer inside Nexa IDE. You work by calling tools, one small step at a time, and you finish with a short summary.

WORKFLOW
1. Existing project: first read the relevant files (read_file / search_in_project), then change them with edit_file (small patches). Never rewrite a whole file unless the user asked for a rewrite.
2. New project: if the request is vague, call ask_user ONCE with 3-6 short questions (app type, main features, design/colors, UI language, data storage, login, permissions). Skip questions the user already answered. After the answers, present a short plan (name, package, template, screens/features, file list) and WAIT for approval before create_project. Only after approval create the project and write files, ONE FILE PER write_file call.
3. After writing, call get_diagnostics. Fix problems yourself (at most 3 rounds). Then reply with a brief summary: what was done, what is left.
4. When a tool returns ERROR, read the message, correct your arguments and retry differently. Do not repeat the identical call.
5. Keep $MEMORY_FILE in the project root up to date for new projects or big decisions (goal, structure, decisions) using write_file/edit_file.

CODE RULES
- Complete, compilable code. Never output TODO placeholders or "...existing code...".
- Kotlin/Compose/Java projects: package statements, AndroidManifest.xml and app/build.gradle.kts namespace/applicationId must agree; MainActivity lives under app/src/main/java/<package path>/.
- HTML projects: put files in app/src/main/assets/ (index.html, style.css, script.js).
- Stay inside the project folder. Never touch .git, keystores, API keys or tokens, and never print secrets.
- Reply in the user's language (Bengali or English). Keep replies short.
""".trimIndent().replace("\$MEMORY_FILE", MEMORY_FILE)

        return buildString {
            append(rules)
            append(IdentityResponder.SYSTEM_PROMPT_RULE)
            append("\n\n")
            append(buildProjectMap(project))
            if (!memory.isNullOrBlank()) {
                append("\n\n$MEMORY_FILE (project memory written by you earlier):\n")
                append(memory.take(6000))
            }
            if (!currentFile.isNullOrBlank()) append("\n\nFile currently open in the editor: $currentFile")
            if (!selectedSnippet.isNullOrBlank()) append("\nSelected code:\n```\n${selectedSnippet.take(3000)}\n```")
        }
    }

    // ---- history trimming -------------------------------------------------

    fun sizeOf(m: AgentMessage): Int = when (m) {
        is AgentMessage.User -> m.text.length
        is AgentMessage.Assistant -> m.text.length + m.toolCalls.sumOf { it.args.toString().length + it.name.length }
        is AgentMessage.ToolResults -> m.results.sumOf { it.content.length + it.name.length }
    }

    /**
     * Returns a copy that fits [budgetChars]. Order of measures: (1) shorten old tool outputs,
     * (2) drop the oldest whole turns. A turn starts at a [AgentMessage.User]; tool call/result pairs are
     * never separated and the latest turn is always kept.
     */
    fun trim(messages: List<AgentMessage>, budgetChars: Int = DEFAULT_BUDGET_CHARS): List<AgentMessage> {
        var list = messages.toList()
        if (list.sumOf { sizeOf(it) } <= budgetChars) return list
        val keepRecent = 8
        list = list.mapIndexed { i, m ->
            if (i < list.size - keepRecent && m is AgentMessage.ToolResults) {
                AgentMessage.ToolResults(m.results.map { r ->
                    if (r.content.length > 1200) r.copy(content = r.content.take(800) + "\n…[older output shortened]…\n" + r.content.takeLast(300)) else r
                })
            } else m
        }
        var turnStarts = list.indices.filter { list[it] is AgentMessage.User }
        while (list.sumOf { sizeOf(it) } > budgetChars && turnStarts.size > 1) {
            val cut = turnStarts[1]
            list = list.drop(cut)
            turnStarts = list.indices.filter { list[it] is AgentMessage.User }
        }
        // First message must be a User message (Gemini/Anthropic requirement).
        val firstUser = list.indexOfFirst { it is AgentMessage.User }
        return if (firstUser > 0) list.drop(firstUser) else list
    }
}

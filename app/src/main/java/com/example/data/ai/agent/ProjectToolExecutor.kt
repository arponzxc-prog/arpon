package com.example.data.ai.agent

import com.example.data.ai.NexaAiAgentEngine
import com.example.data.local.entity.ProjectEntity
import com.example.tools.ProjectSearchEngine
import com.example.tools.SearchOptions
import org.json.JSONObject
import java.io.File

/** Bridges tools that need app services (DB, GitHub). Implemented by the UI layer. */
interface AgentProjectHost {
    fun currentProject(): ProjectEntity?
    /** Creates the project on disk + DB and makes it the active project. */
    suspend fun createProject(name: String, packageName: String, template: String, language: String): ProjectEntity
    fun onProjectTouched(project: ProjectEntity) {}
    val buildTools: AgentBuildHost? get() = null
}

interface AgentBuildHost {
    suspend fun triggerBuild(): String
    suspend fun latestBuildLog(): String
}

/** Executes tool calls against the project folder. Never throws: every failure becomes an error [ToolResult]. */
class ProjectToolExecutor(
    private val host: AgentProjectHost,
    private val journalFactory: (File) -> UndoJournal = { UndoJournal(it) }
) {
    private var journal: UndoJournal? = null
    private var journalRoot: String? = null

    val writtenFiles = LinkedHashSet<String>()
    val editedFiles = LinkedHashSet<String>()
    val deletedFiles = LinkedHashSet<String>()
    /** Files changed in this run; used by get_diagnostics and the verify pass. */
    val touched: Set<String> get() = LinkedHashSet<String>().apply { addAll(writtenFiles); addAll(editedFiles) }

    fun undoJournal(): UndoJournal? = journal

    private fun journalFor(root: File): UndoJournal {
        val key = root.canonicalPath
        if (journal == null || journalRoot != key) {
            journal = journalFactory(root); journalRoot = key
        }
        return journal!!
    }

    suspend fun execute(call: ToolCall): ToolResult {
        if (call.argsError != null) {
            return err(call, "Tool arguments could not be parsed (${call.argsError}). The output was probably cut off. " +
                "Retry with smaller content: write large files in several steps (create the file with a first part, then add the rest with edit_file).")
        }
        return try {
            when (call.name) {
                AgentToolSpecs.LIST_FILES -> listFiles(call)
                AgentToolSpecs.READ_FILE -> readFile(call)
                AgentToolSpecs.SEARCH -> search(call)
                AgentToolSpecs.PROJECT_INFO -> projectInfo(call)
                AgentToolSpecs.CREATE_PROJECT -> createProject(call)
                AgentToolSpecs.WRITE_FILE -> writeFile(call)
                AgentToolSpecs.EDIT_FILE -> editFile(call)
                AgentToolSpecs.DELETE_FILE -> deleteFile(call)
                AgentToolSpecs.RENAME_FILE -> renameFile(call)
                AgentToolSpecs.DIAGNOSTICS -> diagnostics(call)
                AgentToolSpecs.TRIGGER_BUILD -> build(call, true)
                AgentToolSpecs.BUILD_LOG -> build(call, false)
                else -> err(call, "Unknown tool '${call.name}'. Available: ${AgentToolSpecs.all(true).joinToString { it.name }}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err(call, "Tool failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun ok(call: ToolCall, text: String) = ToolResult(call.id, call.name, text, false)
    private fun err(call: ToolCall, text: String) = ToolResult(call.id, call.name, "ERROR: $text", true)

    private fun project(): ProjectEntity? = host.currentProject()

    private fun requireProject(call: ToolCall): Pair<ProjectEntity, File>? {
        val p = project() ?: return null
        val root = File(p.rootPath)
        return if (root.isDirectory) p to root else null
    }

    private val noProject = "No active project. Use create_project first (after the user approved the plan) or ask the user to open one."

    private fun arg(call: ToolCall, key: String): String = call.args.optString(key, "")

    private fun resolve(call: ToolCall, p: ProjectEntity, root: File, key: String, forWrite: Boolean): ProjectGuard.Resolved =
        ProjectGuard.resolve(root, arg(call, key), p.packageName, forWrite)

    // ---- read-only tools -------------------------------------------------

    private fun listFiles(call: ToolCall): ToolResult {
        val (_, root) = requireProject(call) ?: return err(call, noProject)
        val sub = arg(call, "path").trim().trim('/')
        val depth = call.args.optInt("depth", 3).coerceIn(1, 8)
        val base = if (sub.isEmpty()) root.canonicalFile else {
            val p = project()!!
            when (val r = ProjectGuard.resolve(root, sub, p.packageName, false)) {
                is ProjectGuard.Resolved.Denied -> return err(call, r.reason)
                is ProjectGuard.Resolved.Ok -> r.file
            }
        }
        if (!base.isDirectory) return err(call, "'$sub' is not a folder.")
        val lines = ArrayList<String>()
        var truncated = false
        fun walk(dir: File, level: Int) {
            val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (c in children) {
                if (c.name in ProjectGuard.HIDDEN_DIRS) continue
                if (lines.size >= MAX_LIST) { truncated = true; return }
                val rel = c.relativeTo(root.canonicalFile).path.replace('\\', '/')
                if (c.isDirectory) {
                    lines.add("$rel/")
                    if (level < depth) walk(c, level + 1)
                } else if (!ProjectGuard.isSecret(c)) {
                    lines.add("$rel (${c.length()} B)")
                }
            }
        }
        walk(base, 1)
        return ok(call, if (lines.isEmpty()) "(empty)" else lines.joinToString("\n") + if (truncated) "\n… list truncated at $MAX_LIST entries; narrow with path." else "")
    }

    private fun readFile(call: ToolCall): ToolResult {
        val (p, root) = requireProject(call) ?: return err(call, noProject)
        val r = resolve(call, p, root, "path", false)
        val file = when (r) { is ProjectGuard.Resolved.Denied -> return err(call, r.reason); is ProjectGuard.Resolved.Ok -> r.file }
        if (!file.isFile) return err(call, "File not found: ${arg(call, "path")}. Use list_files to see what exists.")
        if (file.length() > MAX_READ_BYTES) return err(call, "File is ${file.length()} bytes (> $MAX_READ_BYTES); use search_in_project or read a line range.")
        val lines = file.readLines()
        val total = lines.size
        val start = call.args.optInt("start_line", 1).coerceAtLeast(1)
        val requestedEnd = call.args.optInt("end_line", Int.MAX_VALUE)
        if (total == 0) return ok(call, "(empty file)")
        if (start > total) return err(call, "start_line $start is beyond the end of the file ($total lines).")
        val end = minOf(requestedEnd, total, start + MAX_READ_LINES - 1)
        val body = (start..end).joinToString("\n") { "${it}| ${lines[it - 1]}" }
        val tail = if (end < total) "\n… (showing lines $start-$end of $total; call again with start_line=${end + 1})" else ""
        return ok(call, "File: ${(r as ProjectGuard.Resolved.Ok).relative} ($total lines)\n$body$tail")
    }

    private fun search(call: ToolCall): ToolResult {
        val (_, root) = requireProject(call) ?: return err(call, noProject)
        val q = arg(call, "query")
        if (q.isBlank()) return err(call, "query is empty.")
        val exts = arg(call, "extensions").split(",").map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()
        val opts = SearchOptions(q, call.args.optBoolean("regex", false), call.args.optBoolean("case_sensitive", false), false, exts)
        if (ProjectSearchEngine.buildPattern(opts) == null) return err(call, "Invalid regex: $q")
        val matches = ProjectSearchEngine.search(root, opts, MAX_SEARCH)
            .filterNot { ProjectGuard.isSecret(it.file) }
        if (matches.isEmpty()) return ok(call, "No matches.")
        return ok(call, matches.joinToString("\n") { "${it.relativePath}:${it.line}: ${it.preview}" } +
            if (matches.size >= MAX_SEARCH) "\n… results capped at $MAX_SEARCH." else "")
    }

    private fun projectInfo(call: ToolCall): ToolResult {
        val (p, root) = requireProject(call) ?: return err(call, noProject)
        val count = ProjectSearchEngine.listFiles(root).count()
        val hasMemory = File(root, AgentContext.MEMORY_FILE).isFile
        return ok(call, "name=${p.name}\npackage=${p.packageName}\ntemplate=${p.templateType}\nlanguage=${p.language}\nfiles=$count\n${AgentContext.MEMORY_FILE}=${if (hasMemory) "present" else "absent"}")
    }

    // ---- project creation ------------------------------------------------

    private suspend fun createProject(call: ToolCall): ToolResult {
        val name = NexaAiAgentEngine.sanitizeAppName(arg(call, "name"))
        val pkg = NexaAiAgentEngine.sanitizePackageName(arg(call, "package_name"), name)
        val (template, language) = NexaAiAgentEngine.normalizeTemplateAndLanguage(arg(call, "template"), arg(call, "language"))
        val created = host.createProject(name, pkg, template, language)
        journal = null; journalRoot = null
        return ok(call, "Project created and active: name=${created.name}, package=${created.packageName}, template=${created.templateType}, language=${created.language}. " +
            "Starter files already exist; call list_files, then edit or add files.")
    }

    // ---- write tools -----------------------------------------------------

    private fun writeFile(call: ToolCall): ToolResult {
        val (p, root) = requireProject(call) ?: return err(call, noProject)
        val r = resolve(call, p, root, "path", true)
        val res = when (r) { is ProjectGuard.Resolved.Denied -> return err(call, r.reason); is ProjectGuard.Resolved.Ok -> r }
        val content = call.args.optString("content", "")
        if (!call.args.has("content")) return err(call, "Missing 'content'.")
        if (res.file.isDirectory) return err(call, "'${res.relative}' is a folder.")
        val overwrite = call.args.optBoolean("overwrite", false)
        if (res.file.exists() && !overwrite) {
            return err(call, "'${res.relative}' already exists. Use edit_file to change it, or pass overwrite=true only for a deliberate full rewrite.")
        }
        if (ProjectDiagnostics.PLACEHOLDER.containsMatchIn(content)) {
            return err(call, "Content contains a placeholder like '...existing code...'. Write the complete file.")
        }
        journalFor(root).record(res.file)
        atomicWrite(res.file, content)
        writtenFiles.add(res.relative)
        host.onProjectTouched(p)
        val problems = ProjectDiagnostics.check(res.relative, content)
        return ok(call, "Wrote ${res.relative} (${content.lines().size} lines)." + problemsSuffix(problems))
    }

    private fun editFile(call: ToolCall): ToolResult {
        val (p, root) = requireProject(call) ?: return err(call, noProject)
        val r = resolve(call, p, root, "path", true)
        val res = when (r) { is ProjectGuard.Resolved.Denied -> return err(call, r.reason); is ProjectGuard.Resolved.Ok -> r }
        if (!res.file.isFile) return err(call, "File not found: ${res.relative}. Use write_file to create new files.")
        if (!call.args.has("old_text") || !call.args.has("new_text")) return err(call, "edit_file needs old_text and new_text.")
        val old = call.args.getString("old_text")
        val new = call.args.getString("new_text")
        if (old.isEmpty()) return err(call, "old_text must not be empty.")
        if (old == new) return err(call, "old_text and new_text are identical; nothing to change.")
        if (ProjectDiagnostics.PLACEHOLDER.containsMatchIn(new)) return err(call, "new_text contains a placeholder; write the real code.")
        val text = res.file.readText()
        val count = countOccurrences(text, old)
        if (count == 0) {
            return err(call, "old_text was not found in ${res.relative}. ${nearMatchHint(text, old)}Re-read the file with read_file and copy the text exactly (indentation matters).")
        }
        if (count > 1) {
            return err(call, "old_text matches $count places in ${res.relative}. Include more surrounding lines so exactly one place matches.")
        }
        val idx = text.indexOf(old)
        val updated = text.substring(0, idx) + new + text.substring(idx + old.length)
        journalFor(root).record(res.file)
        atomicWrite(res.file, updated)
        if (res.relative !in writtenFiles) editedFiles.add(res.relative)
        host.onProjectTouched(p)
        val line = text.substring(0, idx).count { it == '\n' } + 1
        return ok(call, "Edited ${res.relative} at line $line." + problemsSuffix(ProjectDiagnostics.check(res.relative, updated)))
    }

    private fun deleteFile(call: ToolCall): ToolResult {
        val (p, root) = requireProject(call) ?: return err(call, noProject)
        val r = resolve(call, p, root, "path", true)
        val res = when (r) { is ProjectGuard.Resolved.Denied -> return err(call, r.reason); is ProjectGuard.Resolved.Ok -> r }
        if (!res.file.isFile) return err(call, "File not found: ${res.relative} (only single files can be deleted).")
        journalFor(root).record(res.file)
        if (!res.file.delete()) return err(call, "Could not delete ${res.relative}.")
        deletedFiles.add(res.relative); writtenFiles.remove(res.relative); editedFiles.remove(res.relative)
        host.onProjectTouched(p)
        return ok(call, "Deleted ${res.relative}.")
    }

    private fun renameFile(call: ToolCall): ToolResult {
        val (p, root) = requireProject(call) ?: return err(call, noProject)
        val from = resolve(call, p, root, "path", true)
        val to = resolve(call, p, root, "new_path", true)
        val src = when (from) { is ProjectGuard.Resolved.Denied -> return err(call, from.reason); is ProjectGuard.Resolved.Ok -> from }
        val dst = when (to) { is ProjectGuard.Resolved.Denied -> return err(call, to.reason); is ProjectGuard.Resolved.Ok -> to }
        if (!src.file.isFile) return err(call, "File not found: ${src.relative}.")
        if (dst.file.exists()) return err(call, "Target ${dst.relative} already exists.")
        val j = journalFor(root)
        j.record(src.file); j.record(dst.file)
        dst.file.parentFile?.mkdirs()
        if (!src.file.renameTo(dst.file)) return err(call, "Rename failed.")
        deletedFiles.add(src.relative); writtenFiles.add(dst.relative)
        host.onProjectTouched(p)
        return ok(call, "Renamed ${src.relative} → ${dst.relative}. Update imports/references that mention the old name.")
    }

    // ---- diagnostics & build ----------------------------------------------

    fun collectProblems(): List<Problem> {
        val (_, root) = requireProject(ToolCall("x", "x", JSONObject())) ?: return emptyList()
        return touched.flatMap { rel -> ProjectDiagnostics.checkFile(root, File(root.canonicalFile, rel)) }
    }

    private fun diagnostics(call: ToolCall): ToolResult {
        val (p, root) = requireProject(call) ?: return err(call, noProject)
        val path = arg(call, "path").trim()
        val problems = if (path.isEmpty()) {
            if (touched.isEmpty()) return ok(call, "No files changed in this task yet. Pass a path to check a specific file.")
            collectProblems()
        } else {
            val r = ProjectGuard.resolve(root, path, p.packageName, false)
            val res = when (r) { is ProjectGuard.Resolved.Denied -> return err(call, r.reason); is ProjectGuard.Resolved.Ok -> r }
            if (!res.file.isFile) return err(call, "File not found: ${res.relative}")
            ProjectDiagnostics.checkFile(root, res.file)
        }
        return ok(call, formatProblems(problems))
    }

    private suspend fun build(call: ToolCall, trigger: Boolean): ToolResult {
        val b = host.buildTools ?: return err(call, "Cloud build is not available (GitHub is not connected).")
        return ok(call, if (trigger) b.triggerBuild() else b.latestBuildLog())
    }

    // ---- helpers ----------------------------------------------------------

    private fun problemsSuffix(problems: List<Problem>): String =
        if (problems.isEmpty()) "" else "\nWARNING: static check found issues:\n" + formatProblems(problems)

    private fun nearMatchHint(text: String, old: String): String {
        val firstLine = old.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        val idx = text.lines().indexOfFirst { it.trim() == firstLine }
        return if (idx >= 0) "The first line of old_text does exist at line ${idx + 1}, but the following lines differ. " else ""
    }

    companion object {
        const val MAX_LIST = 400
        const val MAX_READ_LINES = 400
        const val MAX_READ_BYTES = 1_000_000L
        const val MAX_SEARCH = 80

        fun countOccurrences(text: String, needle: String): Int {
            var count = 0; var from = 0
            while (true) {
                val i = text.indexOf(needle, from)
                if (i < 0) return count
                count++; from = i + needle.length
            }
        }

        fun formatProblems(problems: List<Problem>): String =
            if (problems.isEmpty()) "No problems found."
            else problems.joinToString("\n") { "${it.file}:${it.line}:${it.column} ${it.severity.name.lowercase()}: ${it.message}" }

        fun atomicWrite(file: File, content: String) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".nexa_tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                file.writeText(content)
                tmp.delete()
            }
        }
    }
}

package com.example.data.ai

import com.example.data.filesystem.FileNode
import com.example.data.filesystem.ProjectFileManager
import com.example.data.local.entity.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AiProjectSpec(
    val create: Boolean,
    val name: String,
    val packageName: String,
    val templateType: String,
    val language: String
)

data class AiFileWriteSpec(
    val path: String,
    val content: String
)

data class AiAgentPlan(
    val project: AiProjectSpec?,
    val files: List<AiFileWriteSpec>,
    val summary: String,
    val rawResponse: String
)

data class AiExecutionResult(
    val project: ProjectEntity,
    val createdNewProject: Boolean,
    val writtenFiles: List<String>,
    val skippedFiles: List<String>,
    val summary: String
)

object NexaAiAgentEngine {

    private val ACTION_BLOCK_REGEX = Regex(
        "```(?:nexa_actions|json)\\s*\\n([\\s\\S]*?)\\n```",
        RegexOption.IGNORE_CASE
    )

    fun sanitizeAppName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return cleaned.ifBlank { "NexaAiApp" }.take(30)
    }

    fun sanitizePackageName(raw: String?, fallbackAppName: String): String {
        val candidate = raw.orEmpty().trim().lowercase()
        val parts = candidate.split(".")
            .map { seg -> seg.replace(Regex("[^a-z0-9_]"), "") }
            .filter { it.isNotBlank() }
            .map { seg -> if (seg.first().isDigit()) "app$seg" else seg }

        if (parts.size >= 2) {
            return parts.joinToString(".")
        }

        val slug = fallbackAppName.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { "nexaapp" }
            .let { if (it.first().isDigit()) "app$it" else it }
        return "com.nexa.$slug"
    }

    fun normalizeTemplateAndLanguage(templateRaw: String?, languageRaw: String?): Pair<String, String> {
        val t = templateRaw.orEmpty().trim()
        val l = languageRaw.orEmpty().trim()
        val template = when {
            t.contains("HTML Multi", ignoreCase = true) || t.contains("Web", ignoreCase = true) && t.contains("Multi", ignoreCase = true) -> "HTML Multi-File Project"
            t.contains("HTML", ignoreCase = true) -> "HTML Single File Project"
            t.contains("WebView", ignoreCase = true) -> "Android WebView App"
            t.contains("Compose", ignoreCase = true) -> "Kotlin Compose App"
            t.contains("Java", ignoreCase = true) || l.equals("Java", ignoreCase = true) -> "Java Android App"
            else -> "Kotlin Android App"
        }
        val language = when {
            template.contains("HTML", ignoreCase = true) || l.equals("Webapp", ignoreCase = true) -> "Webapp"
            template.contains("Java", ignoreCase = true) || l.equals("Java", ignoreCase = true) -> "Java"
            else -> "Kotlin"
        }
        return template to language
    }

    /**
     * Parses structured nexa_actions / json project + multi-file plans out of an AI response.
     * Returns null when the AI response is purely conversational and contains no executable project/file plan.
     */
    fun parseAgentPlan(aiResponse: String): AiAgentPlan? {
        val candidates = mutableListOf<Pair<String, IntRange>>()

        for (match in ACTION_BLOCK_REGEX.findAll(aiResponse)) {
            val payload = match.groupValues[1].trim()
            if (payload.startsWith("{") && (payload.contains("\"files\"") || payload.contains("\"project\""))) {
                candidates.add(payload to match.range)
            }
        }

        // Fallback: detect raw top-level JSON object containing "project" or "files"
        if (candidates.isEmpty()) {
            val firstBrace = aiResponse.indexOf('{')
            val lastBrace = aiResponse.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                val slice = aiResponse.substring(firstBrace, lastBrace + 1)
                if (slice.contains("\"files\"") || slice.contains("\"project\"")) {
                    candidates.add(slice to (firstBrace..lastBrace))
                }
            }
        }

        for ((jsonStr, range) in candidates) {
            try {
                val root = JSONObject(jsonStr)
                val projObj = root.optJSONObject("project")
                val filesArr = root.optJSONArray("files") ?: JSONArray()

                val projectSpec = projObj?.let { p ->
                    val name = sanitizeAppName(p.optString("name", ""))
                    val pkg = sanitizePackageName(p.optString("packageName", ""), name)
                    val (tmpl, lang) = normalizeTemplateAndLanguage(
                        p.optString("templateType", ""),
                        p.optString("language", "")
                    )
                    AiProjectSpec(
                        create = p.optBoolean("create", true),
                        name = name,
                        packageName = pkg,
                        templateType = tmpl,
                        language = lang
                    )
                }

                val files = mutableListOf<AiFileWriteSpec>()
                for (i in 0 until filesArr.length()) {
                    val item = filesArr.optJSONObject(i) ?: continue
                    val path = item.optString("path", "").trim()
                    val content = item.optString("content", "")
                    if (path.isNotBlank()) {
                        files.add(AiFileWriteSpec(path = path, content = content))
                    }
                }

                if (projectSpec != null || files.isNotEmpty()) {
                    val summaryFromJson = root.optString("summary", "").trim()
                    val cleanTextOutside = (
                        aiResponse.substring(0, range.first) + "\n" + aiResponse.substring((range.last + 1).coerceAtMost(aiResponse.length))
                        ).trim()
                    val summary = listOf(cleanTextOutside, summaryFromJson)
                        .firstOrNull { it.isNotBlank() }
                        ?: "AI plan ready (${files.size} file(s))"
                    return AiAgentPlan(
                        project = projectSpec,
                        files = files,
                        summary = summary,
                        rawResponse = aiResponse
                    )
                }
            } catch (_: Exception) {
                // Try next candidate block if this JSON was malformed
            }
        }
        return null
    }

    /**
     * Produces a concise workspace tree + key file context string for the active project so AI can inspect/modify it accurately.
     */
    fun buildProjectContextSummary(project: ProjectEntity?, fileManager: ProjectFileManager): String {
        if (project == null) return "Active Project: None (ready to create a new project)."
        val rootDir = File(project.rootPath)
        if (!rootDir.exists()) return "Active Project: ${project.name} (root directory missing)."

        val tree = fileManager.getFileTree(rootDir)
        val relativePaths = mutableListOf<String>()
        fun collect(nodes: List<FileNode>) {
            for (node in nodes) {
                if (relativePaths.size >= 60) return
                if (node.name == ".git" || node.name == "build" || node.name == ".gradle") continue
                if (node.isDirectory) {
                    collect(node.children)
                } else {
                    val rel = File(node.path).relativeTo(rootDir).path.replace("\\", "/")
                    relativePaths.add(rel)
                }
            }
        }
        collect(tree)

        return buildString {
            appendLine("Active Project Name: ${project.name}")
            appendLine("Package Name: ${project.packageName}")
            appendLine("Template: ${project.templateType} | Language: ${project.language}")
            appendLine("Project Files:")
            relativePaths.forEach { appendLine("- $it") }
        }
    }

    /**
     * Safely resolves and writes a project-relative path inside rootDir, blocking any path traversal.
     */
    fun resolveSafeProjectFile(rootDir: File, rawRelativePath: String, packageName: String): File? {
        val packagePath = packageName.replace('.', '/')
        val normalized = rawRelativePath
            .replace("\\", "/")
            .replace("<package>", packagePath)
            .replace("\${package}", packagePath)
            .trim()
            .trimStart('/')

        if (normalized.isBlank()) return null
        val segments = normalized.split("/")
        if (segments.any { it == ".." || it == "." || it.isBlank() }) return null

        return try {
            val canonicalRoot = rootDir.canonicalFile
            val candidate = File(canonicalRoot, normalized).canonicalFile
            if (candidate.path.startsWith(canonicalRoot.path + File.separator)) {
                candidate
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun executePlan(
        plan: AiAgentPlan,
        currentActiveProject: ProjectEntity?,
        fileManager: ProjectFileManager,
        onInsertProject: suspend (ProjectEntity) -> Unit,
        onUpdateProject: suspend (ProjectEntity) -> Unit
    ): AiExecutionResult = withContext(Dispatchers.IO) {
        val shouldCreateNew = (plan.project?.create == true) || (currentActiveProject == null)

        val targetProject: ProjectEntity = if (shouldCreateNew) {
            val spec = plan.project
            val appName = sanitizeAppName(spec?.name ?: "NexaAiApp")
            val pkg = sanitizePackageName(spec?.packageName, appName)
            val (templateType, language) = normalizeTemplateAndLanguage(spec?.templateType, spec?.language)
            val created = fileManager.createProject(
                name = appName,
                packageName = pkg,
                templateType = templateType,
                language = language
            )
            onInsertProject(created)
            created
        } else {
            currentActiveProject!!
        }

        val rootDir = File(targetProject.rootPath)
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (fileSpec in plan.files) {
            val safeFile = resolveSafeProjectFile(rootDir, fileSpec.path, targetProject.packageName)
            if (safeFile == null) {
                skipped.add(fileSpec.path)
                continue
            }
            val ok = fileManager.writeFileContent(safeFile.absolutePath, fileSpec.content)
            if (ok) {
                val rel = safeFile.canonicalFile.relativeTo(rootDir.canonicalFile).path.replace("\\", "/")
                written.add(rel)
            } else {
                skipped.add(fileSpec.path)
            }
        }

        val refreshed = targetProject.copy(lastModifiedAt = System.currentTimeMillis())
        onUpdateProject(refreshed)

        AiExecutionResult(
            project = refreshed,
            createdNewProject = shouldCreateNew,
            writtenFiles = written,
            skippedFiles = skipped,
            summary = plan.summary
        )
    }
}

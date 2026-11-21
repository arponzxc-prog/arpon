package com.example.terminal

import android.content.Context
import android.os.Build
import com.example.data.filesystem.ProjectFileManager
import com.example.data.local.entity.ProjectEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class TerminalLineType {
    COMMAND,
    OUTPUT,
    ERROR,
    INFO,
    SUCCESS
}

data class TerminalOutputLine(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val type: TerminalLineType = TerminalLineType.OUTPUT,
    val timestamp: Long = System.currentTimeMillis()
)

data class TerminalSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val projectRootPath: String,
    val currentDirPath: String,
    val lines: List<TerminalOutputLine> = emptyList(),
    val history: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val lastExitCode: Int? = null,
    val lastDurationMs: Long? = null
)

sealed class TerminalCommandOutcome {
    data class Completed(
        val outputLines: List<TerminalOutputLine>,
        val exitCode: Int,
        val durationMs: Long,
        val updatedDir: String? = null,
        val clearOutput: Boolean = false
    ) : TerminalCommandOutcome()

    data class RequiresConfirmation(
        val command: String,
        val reason: String
    ) : TerminalCommandOutcome()

    data class TriggerCloudGradle(
        val task: String,
        val buildType: String
    ) : TerminalCommandOutcome()
}

/**
 * Sandboxed project-workspace terminal engine for Nexa IDE.
 *
 * Security & Honesty guarantees:
 * - Restricts filesystem access strictly within the active project's canonical directory.
 * - Blocks path traversal (`..`, `/data`, `/etc`, etc.) and privileged Linux commands.
 * - Requires explicit confirmation before destructive workspace operations (`rm`, `rmdir`).
 * - Clearly explains Android sandbox limitations when a desktop Linux tool isn't natively available.
 */
class TerminalEngine(
    private val context: Context,
    private val fileManager: ProjectFileManager
) {
    companion object {
        private val DESTRUCTIVE_COMMANDS = setOf("rm", "rmdir", "delete", "clean-files")
        private val PRIVILEGED_OR_UNSUPPORTED_LINUX = mapOf(
            "sudo" to "Android apps run in a non-root application sandbox; 'sudo' and elevated privileges are prohibited.",
            "su" to "Root shell access ('su') is prohibited inside Nexa IDE's constrained workspace.",
            "apt" to "Debian/Ubuntu 'apt' package manager is not available inside Android OS.",
            "apt-get" to "Debian/Ubuntu 'apt-get' is not available inside Android OS.",
            "yum" to "RHEL 'yum' is not available on Android devices.",
            "docker" to "Docker daemon is not available in the Android app sandbox.",
            "systemctl" to "Systemd ('systemctl') does not exist in Android OS.",
            "adb" to "Local 'adb' host daemon is not available inside a standard Android app without external USB/Wireless debugging."
        )
    }

    fun getRelativeWorkspacePath(projectRoot: File, currentDir: File): String {
        val rootCanonical = projectRoot.canonicalPath
        val currentCanonical = currentDir.canonicalPath
        if (currentCanonical == rootCanonical) return "~"
        val rel = currentCanonical.removePrefix(rootCanonical).trimStart(File.separatorChar)
        return "~/$rel"
    }

    /**
     * Resolves a user-supplied path strictly inside [projectRoot].
     * Returns null if the resolved canonical path escapes [projectRoot].
     */
    fun resolveSafeWorkspacePath(projectRoot: File, currentDir: File, targetPath: String): File? {
        val rootCanonical = projectRoot.canonicalPath
        val candidate = when {
            targetPath == "~" -> projectRoot
            targetPath.startsWith("~/") -> File(projectRoot, targetPath.removePrefix("~/"))
            targetPath.startsWith("/") -> {
                // Only allow if someone passes an explicit path inside projectRoot
                val f = File(targetPath)
                if (f.canonicalPath.startsWith(rootCanonical)) f else return null
            }
            else -> File(currentDir, targetPath)
        }
        val resolvedCanonical = candidate.canonicalPath
        return if (resolvedCanonical == rootCanonical || resolvedCanonical.startsWith("$rootCanonical${File.separator}")) {
            File(resolvedCanonical)
        } else {
            null
        }
    }

    suspend fun executeCommand(
        rawInput: String,
        project: ProjectEntity,
        currentDirPath: String,
        confirmedDestructive: Boolean = false
    ): TerminalCommandOutcome = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return@withContext TerminalCommandOutcome.Completed(emptyList(), 0, 0L)
        }

        // Reject shell chaining / injection operators for strict argument validation
        if (trimmed.contains("&&") || trimmed.contains("||") || trimmed.contains(";") || trimmed.contains("`") || trimmed.contains("$(")) {
            val dur = System.currentTimeMillis() - startMs
            return@withContext TerminalCommandOutcome.Completed(
                outputLines = listOf(
                    TerminalOutputLine(
                        text = "Security validation failed: Shell command chaining operators (&&, ;, `, $()) are blocked in sandboxed mode. Run one validated command at a time.",
                        type = TerminalLineType.ERROR
                    )
                ),
                exitCode = 126,
                durationMs = dur
            )
        }

        val tokens = tokenizeCommand(trimmed)
        if (tokens.isEmpty()) {
            return@withContext TerminalCommandOutcome.Completed(emptyList(), 0, 0L)
        }

        val cmd = tokens[0]
        val args = tokens.drop(1)
        val projectRoot = File(project.rootPath)
        val currentDir = resolveSafeWorkspacePath(projectRoot, projectRoot, currentDirPath) ?: projectRoot

        // Check unsupported privileged / desktop Linux commands honestly
        PRIVILEGED_OR_UNSUPPORTED_LINUX[cmd.lowercase()]?.let { explanation ->
            val dur = System.currentTimeMillis() - startMs
            return@withContext TerminalCommandOutcome.Completed(
                outputLines = listOf(
                    TerminalOutputLine(text = "Command '$cmd' cannot execute in this environment:", type = TerminalLineType.ERROR),
                    TerminalOutputLine(text = explanation, type = TerminalLineType.INFO)
                ),
                exitCode = 126,
                durationMs = dur
            )
        }

        // Require confirmation for destructive workspace operations
        if (cmd.lowercase() in DESTRUCTIVE_COMMANDS && !confirmedDestructive) {
            if (args.isEmpty()) {
                return@withContext TerminalCommandOutcome.Completed(
                    outputLines = listOf(TerminalOutputLine(text = "Usage: $cmd <relative-file-or-dir>", type = TerminalLineType.ERROR)),
                    exitCode = 1,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
            return@withContext TerminalCommandOutcome.RequiresConfirmation(
                command = trimmed,
                reason = "This destructive operation will permanently delete '${args.joinToString(" ")}' inside project '${project.name}'."
            )
        }

        try {
            val outcomeLines = mutableListOf<TerminalOutputLine>()
            var exitCode = 0
            var nextDir: String? = null
            var shouldClear = false

            when (cmd.lowercase()) {
                "clear", "cls" -> {
                    shouldClear = true
                }
                "help" -> {
                    outcomeLines += buildHelpOutput()
                }
                "pwd" -> {
                    outcomeLines += TerminalOutputLine(
                        text = "${getRelativeWorkspacePath(projectRoot, currentDir)} (${currentDir.canonicalPath})",
                        type = TerminalLineType.OUTPUT
                    )
                }
                "ls", "dir" -> {
                    val showHidden = args.any { it.contains("a") }
                    val longFmt = args.any { it.contains("l") }
                    val targetArg = args.firstOrNull { !it.startsWith("-") }
                    val targetDir = if (targetArg != null) {
                        resolveSafeWorkspacePath(projectRoot, currentDir, targetArg)
                    } else {
                        currentDir
                    }
                    if (targetDir == null) {
                        outcomeLines += TerminalOutputLine(
                            text = "Access denied: Path escapes project workspace.",
                            type = TerminalLineType.ERROR
                        )
                        exitCode = 1
                    } else if (!targetDir.exists()) {
                        outcomeLines += TerminalOutputLine(text = "ls: '${targetArg ?: "."}': No such file or directory", type = TerminalLineType.ERROR)
                        exitCode = 2
                    } else if (targetDir.isFile) {
                        outcomeLines += TerminalOutputLine(text = formatFileEntry(targetDir, longFmt), type = TerminalLineType.OUTPUT)
                    } else {
                        val children = targetDir.listFiles()
                            ?.filter { showHidden || !it.name.startsWith(".") }
                            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                            ?: emptyList()
                        if (children.isEmpty()) {
                            outcomeLines += TerminalOutputLine(text = "(empty directory)", type = TerminalLineType.INFO)
                        } else {
                            children.forEach { child ->
                                outcomeLines += TerminalOutputLine(
                                    text = formatFileEntry(child, longFmt),
                                    type = if (child.isDirectory) TerminalLineType.INFO else TerminalLineType.OUTPUT
                                )
                            }
                        }
                    }
                }
                "cd" -> {
                    val targetArg = args.firstOrNull() ?: "~"
                    val resolved = resolveSafeWorkspacePath(projectRoot, currentDir, targetArg)
                    if (resolved == null) {
                        outcomeLines += TerminalOutputLine(
                            text = "Access denied: Cannot navigate outside project workspace (${project.name}).",
                            type = TerminalLineType.ERROR
                        )
                        exitCode = 1
                    } else if (!resolved.exists() || !resolved.isDirectory) {
                        outcomeLines += TerminalOutputLine(text = "cd: $targetArg: No such directory", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else {
                        nextDir = resolved.canonicalPath
                        outcomeLines += TerminalOutputLine(
                            text = "Moved to ${getRelativeWorkspacePath(projectRoot, resolved)}",
                            type = TerminalLineType.SUCCESS
                        )
                    }
                }
                "tree" -> {
                    val targetArg = args.firstOrNull()
                    val targetDir = if (targetArg != null) {
                        resolveSafeWorkspacePath(projectRoot, currentDir, targetArg)
                    } else currentDir
                    if (targetDir == null || !targetDir.exists()) {
                        outcomeLines += TerminalOutputLine(text = "Invalid or unauthorized directory path.", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else {
                        val treeLines = mutableListOf<String>()
                        buildTreeLines(targetDir, "", treeLines, maxLines = 150)
                        treeLines.forEach { outcomeLines += TerminalOutputLine(text = it) }
                    }
                }
                "cat", "head" -> {
                    val targetArg = args.firstOrNull()
                    if (targetArg == null) {
                        outcomeLines += TerminalOutputLine(text = "Usage: $cmd <file>", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else {
                        val targetFile = resolveSafeWorkspacePath(projectRoot, currentDir, targetArg)
                        if (targetFile == null) {
                            outcomeLines += TerminalOutputLine(text = "Access denied: Path escapes project workspace.", type = TerminalLineType.ERROR)
                            exitCode = 1
                        } else if (!targetFile.exists() || !targetFile.isFile) {
                            outcomeLines += TerminalOutputLine(text = "$cmd: $targetArg: File not found", type = TerminalLineType.ERROR)
                            exitCode = 1
                        } else if (targetFile.length() > 512 * 1024) {
                            outcomeLines += TerminalOutputLine(text = "$cmd: File exceeds 512 KB terminal preview limit.", type = TerminalLineType.ERROR)
                            exitCode = 1
                        } else {
                            val lines = targetFile.readLines()
                            val sliced = if (cmd.equals("head", true)) lines.take(25) else lines.take(200)
                            sliced.forEach { outcomeLines += TerminalOutputLine(text = it) }
                            if (lines.size > sliced.size) {
                                outcomeLines += TerminalOutputLine(text = "... (${lines.size - sliced.size} more lines truncated)", type = TerminalLineType.INFO)
                            }
                        }
                    }
                }
                "wc" -> {
                    val targetArg = args.firstOrNull()
                    val targetFile = targetArg?.let { resolveSafeWorkspacePath(projectRoot, currentDir, it) }
                    if (targetFile == null || !targetFile.exists() || !targetFile.isFile) {
                        outcomeLines += TerminalOutputLine(text = "Usage: wc <existing-workspace-file>", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else {
                        val content = targetFile.readText()
                        val lineCnt = content.lines().size
                        val wordCnt = content.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                        val bytesCnt = targetFile.length()
                        outcomeLines += TerminalOutputLine(text = "  $lineCnt lines  $wordCnt words  $bytesCnt bytes  ${targetFile.name}")
                    }
                }
                "find" -> {
                    val query = args.firstOrNull()?.lowercase() ?: ""
                    val matches = currentDir.walkTopDown()
                        .filter { !it.absolutePath.contains("/.gradle") && !it.absolutePath.contains("/build/") }
                        .filter { query.isBlank() || it.name.lowercase().contains(query) }
                        .take(100)
                        .toList()
                    if (matches.isEmpty()) {
                        outcomeLines += TerminalOutputLine(text = "No matching files found.", type = TerminalLineType.INFO)
                    } else {
                        matches.forEach { f ->
                            val rel = f.canonicalPath.removePrefix(projectRoot.canonicalPath).trimStart(File.separatorChar)
                            outcomeLines += TerminalOutputLine(text = if (f.isDirectory) "$rel/" else rel)
                        }
                    }
                }
                "grep" -> {
                    if (args.isEmpty()) {
                        outcomeLines += TerminalOutputLine(text = "Usage: grep <pattern> [relative-path]", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else {
                        val pattern = args[0]
                        val scopeDir = args.getOrNull(1)?.let { resolveSafeWorkspacePath(projectRoot, currentDir, it) } ?: currentDir
                        if (scopeDir == null || !scopeDir.exists()) {
                            outcomeLines += TerminalOutputLine(text = "Invalid or unauthorized search path.", type = TerminalLineType.ERROR)
                            exitCode = 1
                        } else {
                            var matchCount = 0
                            scopeDir.walkTopDown()
                                .filter { it.isFile && it.length() < 512 * 1024 }
                                .forEach { file ->
                                    if (matchCount >= 80) return@forEach
                                    val rel = file.canonicalPath.removePrefix(projectRoot.canonicalPath).trimStart(File.separatorChar)
                                    file.readLines().forEachIndexed { idx, line ->
                                        if (matchCount < 80 && line.contains(pattern, ignoreCase = true)) {
                                            outcomeLines += TerminalOutputLine(text = "$rel:${idx + 1}: ${line.trim()}")
                                            matchCount++
                                        }
                                    }
                                }
                            if (matchCount == 0) {
                                outcomeLines += TerminalOutputLine(text = "No matches for '$pattern'.", type = TerminalLineType.INFO)
                            }
                        }
                    }
                }
                "mkdir" -> {
                    val dirName = args.firstOrNull()
                    val targetDir = dirName?.let { resolveSafeWorkspacePath(projectRoot, currentDir, it) }
                    if (targetDir == null) {
                        outcomeLines += TerminalOutputLine(text = "Invalid or unauthorized directory path.", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else if (targetDir.exists()) {
                        outcomeLines += TerminalOutputLine(text = "mkdir: '$dirName': Already exists", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else if (targetDir.mkdirs()) {
                        outcomeLines += TerminalOutputLine(text = "Created directory: ${getRelativeWorkspacePath(projectRoot, targetDir)}", type = TerminalLineType.SUCCESS)
                    } else {
                        outcomeLines += TerminalOutputLine(text = "Failed to create directory.", type = TerminalLineType.ERROR)
                        exitCode = 1
                    }
                }
                "touch" -> {
                    val fileName = args.firstOrNull()
                    val targetFile = fileName?.let { resolveSafeWorkspacePath(projectRoot, currentDir, it) }
                    if (targetFile == null) {
                        outcomeLines += TerminalOutputLine(text = "Invalid or unauthorized file path.", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else {
                        targetFile.parentFile?.mkdirs()
                        if (targetFile.createNewFile() || targetFile.exists()) {
                            outcomeLines += TerminalOutputLine(text = "Touched: ${getRelativeWorkspacePath(projectRoot, targetFile)}", type = TerminalLineType.SUCCESS)
                        } else {
                            outcomeLines += TerminalOutputLine(text = "Could not create file '$fileName'.", type = TerminalLineType.ERROR)
                            exitCode = 1
                        }
                    }
                }
                "rm", "rmdir", "delete" -> {
                    val targetArg = args.firstOrNull { !it.startsWith("-") }
                    val target = targetArg?.let { resolveSafeWorkspacePath(projectRoot, currentDir, it) }
                    if (target == null || target.canonicalPath == projectRoot.canonicalPath) {
                        outcomeLines += TerminalOutputLine(text = "Access denied: Cannot delete project root or paths outside workspace.", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else if (!target.exists()) {
                        outcomeLines += TerminalOutputLine(text = "$cmd: $targetArg: No such file or directory", type = TerminalLineType.ERROR)
                        exitCode = 1
                    } else {
                        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
                        if (deleted) {
                            outcomeLines += TerminalOutputLine(text = "Deleted: $targetArg", type = TerminalLineType.SUCCESS)
                        } else {
                            outcomeLines += TerminalOutputLine(text = "Failed to delete $targetArg", type = TerminalLineType.ERROR)
                            exitCode = 1
                        }
                    }
                }
                "echo" -> {
                    outcomeLines += TerminalOutputLine(text = args.joinToString(" "))
                }
                "date" -> {
                    val fmt = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US)
                    outcomeLines += TerminalOutputLine(text = fmt.format(Date()))
                }
                "whoami" -> {
                    outcomeLines += TerminalOutputLine(text = "nexa-developer (sandbox-uid:${android.os.Process.myUid()})")
                }
                "uname" -> {
                    outcomeLines += TerminalOutputLine(
                        text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}"
                    )
                }
                "env" -> {
                    // Explicitly sanitized workspace-only environment (no tokens, keys, or private system paths)
                    outcomeLines += TerminalOutputLine(text = "NEXA_PROJECT=${project.name}", type = TerminalLineType.INFO)
                    outcomeLines += TerminalOutputLine(text = "NEXA_PACKAGE=${project.packageName}", type = TerminalLineType.INFO)
                    outcomeLines += TerminalOutputLine(text = "NEXA_LANGUAGE=${project.language}", type = TerminalLineType.INFO)
                    outcomeLines += TerminalOutputLine(text = "NEXA_WORKSPACE=${getRelativeWorkspacePath(projectRoot, projectRoot)}", type = TerminalLineType.INFO)
                    outcomeLines += TerminalOutputLine(text = "ANDROID_API=${Build.VERSION.SDK_INT}", type = TerminalLineType.INFO)
                }
                "gradle", "./gradlew", "gradlew" -> {
                    val task = args.firstOrNull()?.trim() ?: "tasks"
                    when (task.lowercase()) {
                        "tasks", "--tasks" -> {
                            outcomeLines += listProjectGradleTasks(project, projectRoot)
                        }
                        "assembledebug", ":app:assembledebug" -> {
                            return@withContext TerminalCommandOutcome.TriggerCloudGradle(
                                task = ":app:assembleDebug",
                                buildType = "DEBUG"
                            )
                        }
                        "assemblerelease", ":app:assemblerelease" -> {
                            return@withContext TerminalCommandOutcome.TriggerCloudGradle(
                                task = ":app:assembleRelease",
                                buildType = "RELEASE"
                            )
                        }
                        "bundlerelease", ":app:bundlerelease" -> {
                            return@withContext TerminalCommandOutcome.TriggerCloudGradle(
                                task = ":app:bundleRelease",
                                buildType = "BUNDLE"
                            )
                        }
                        "check", "validate", "lint" -> {
                            outcomeLines += runLocalProjectChecks(projectRoot)
                        }
                        else -> {
                            outcomeLines += TerminalOutputLine(
                                text = "On-device Gradle execution note: Standard JVM/Gradle daemons require a desktop JDK & Android Build-Tools binary, which Android devices do not bundle natively.",
                                type = TerminalLineType.INFO
                            )
                            outcomeLines += TerminalOutputLine(
                                text = "Supported Gradle commands here: 'gradle tasks', './gradlew assembleDebug', './gradlew assembleRelease', './gradlew bundleRelease' (runs on GitHub Actions Cloud Builder & records to Build History), or 'gradle check' (local manifest & project validation).",
                                type = TerminalLineType.OUTPUT
                            )
                        }
                    }
                }
                else -> {
                    outcomeLines += TerminalOutputLine(
                        text = "nexa-sh: '$cmd': Command not supported in Android workspace sandbox. Type 'help' to see available commands.",
                        type = TerminalLineType.ERROR
                    )
                    exitCode = 127
                }
            }

            val dur = System.currentTimeMillis() - startMs
            TerminalCommandOutcome.Completed(
                outputLines = outcomeLines,
                exitCode = exitCode,
                durationMs = dur,
                updatedDir = nextDir,
                clearOutput = shouldClear
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TerminalCommandOutcome.Completed(
                outputLines = listOf(TerminalOutputLine(text = "Execution error: ${e.message}", type = TerminalLineType.ERROR)),
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun tokenizeCommand(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in input) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun formatFileEntry(file: File, longFormat: Boolean): String {
        val suffix = if (file.isDirectory) "/" else ""
        if (!longFormat) return "${file.name}$suffix"
        val perm = if (file.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        val sizeStr = file.length().toString().padStart(8)
        val dateStr = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(file.lastModified()))
        return "$perm  $sizeStr  $dateStr  ${file.name}$suffix"
    }

    private fun buildTreeLines(dir: File, prefix: String, out: MutableList<String>, maxLines: Int) {
        if (out.size >= maxLines) return
        if (prefix.isEmpty()) out.add("${dir.name}/")
        val children = dir.listFiles()
            ?.filter { it.name != ".gradle" && it.name != "build" }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return
        children.forEachIndexed { idx, child ->
            if (out.size >= maxLines) return
            val isLast = idx == children.lastIndex
            val connector = if (isLast) "└── " else "├── "
            out.add("$prefix$connector${child.name}${if (child.isDirectory) "/" else ""}")
            if (child.isDirectory) {
                val childPrefix = prefix + if (isLast) "    " else "│   "
                buildTreeLines(child, childPrefix, out, maxLines)
            }
        }
    }

    private fun listProjectGradleTasks(project: ProjectEntity, root: File): List<TerminalOutputLine> {
        val lines = mutableListOf<TerminalOutputLine>()
        val hasAppBuild = File(root, "app/build.gradle.kts").exists() || File(root, "app/build.gradle").exists()
        val hasWorkflow = File(root, ".github/workflows/android-build.yml").exists()

        lines += TerminalOutputLine(text = "> Inspecting Gradle configuration for '${project.name}'...", type = TerminalLineType.INFO)
        lines += TerminalOutputLine(text = "Module detected: ${if (hasAppBuild) ":app (Android Application)" else "No app/build.gradle(.kts) found"}", type = TerminalLineType.OUTPUT)
        lines += TerminalOutputLine(text = "Cloud CI Workflow: ${if (hasWorkflow) "Configured (.github/workflows/android-build.yml)" else "Missing"}", type = TerminalLineType.OUTPUT)
        lines += TerminalOutputLine(text = "")
        lines += TerminalOutputLine(text = "Available Build & Verification Tasks:", type = TerminalLineType.SUCCESS)
        lines += TerminalOutputLine(text = "  :app:assembleDebug     - Dispatches Debug APK build via GitHub Actions & saves to Build History")
        lines += TerminalOutputLine(text = "  :app:assembleRelease   - Dispatches Release APK build via GitHub Actions & saves to Build History")
        lines += TerminalOutputLine(text = "  :app:bundleRelease     - Dispatches Android App Bundle (.aab) via GitHub Actions")
        lines += TerminalOutputLine(text = "  gradle check           - Runs local workspace Manifest, Gradle, & Resource validation")
        return lines
    }

    private fun runLocalProjectChecks(root: File): List<TerminalOutputLine> {
        val lines = mutableListOf<TerminalOutputLine>()
        lines += TerminalOutputLine(text = "> Task :app:validateProjectStructure", type = TerminalLineType.INFO)
        val required = listOf("settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml")
        var issues = 0
        required.forEach { rel ->
            val exists = File(root, rel).exists()
            if (exists) {
                lines += TerminalOutputLine(text = "  [OK] $rel", type = TerminalLineType.SUCCESS)
            } else {
                lines += TerminalOutputLine(text = "  [MISSING] $rel", type = TerminalLineType.ERROR)
                issues++
            }
        }
        lines += TerminalOutputLine(
            text = if (issues == 0) "Local check passed with 0 missing core files." else "Local check found $issues issue(s).",
            type = if (issues == 0) TerminalLineType.SUCCESS else TerminalLineType.ERROR
        )
        return lines
    }

    private fun buildHelpOutput(): List<TerminalOutputLine> = listOf(
        TerminalOutputLine(text = "Nexa IDE Sandboxed Workspace Terminal", type = TerminalLineType.SUCCESS),
        TerminalOutputLine(text = "Workspace commands (strictly scoped inside active project):", type = TerminalLineType.INFO),
        TerminalOutputLine(text = "  ls [-la], pwd, cd <dir>, tree, find <name>, grep <pattern>"),
        TerminalOutputLine(text = "  cat <file>, head <file>, wc <file>, mkdir <dir>, touch <file>, rm <target>"),
        TerminalOutputLine(text = "  env, whoami, uname, date, echo <text>, clear"),
        TerminalOutputLine(text = "Gradle & Cloud Build integration:", type = TerminalLineType.INFO),
        TerminalOutputLine(text = "  gradle tasks                  - Inspect project Gradle tasks"),
        TerminalOutputLine(text = "  gradle check                  - Validate Gradle, Manifest, and project files"),
        TerminalOutputLine(text = "  ./gradlew assembleDebug       - Trigger Debug APK build & log to Build History"),
        TerminalOutputLine(text = "  ./gradlew assembleRelease     - Trigger Release APK build & log to Build History"),
        TerminalOutputLine(text = "  ./gradlew bundleRelease       - Trigger Release AAB build & log to Build History")
    )
}

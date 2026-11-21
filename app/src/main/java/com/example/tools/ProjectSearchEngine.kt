package com.example.tools

import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class SearchOptions(
    val query: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    /** Lower-case extensions without dot. Empty = all text files. */
    val extensions: Set<String> = emptySet()
)

data class SearchMatch(
    val file: File,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val preview: String
)

data class ReplaceReport(val filesChanged: Int, val replacements: Int, val failedFiles: List<String>)

/**
 * Project-wide search / replace. Pure java.io (JVM-testable). Files are streamed line-by-line for
 * search; replace reads at most [MAX_FILE_BYTES] per file and writes atomically (temp file + rename).
 */
object ProjectSearchEngine {
    val SKIP_DIRS = setOf("build", ".git", ".gradle", ".idea", "node_modules", ".cxx")
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val MAX_RESULTS = 500
    private val BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "apk", "aab", "jar", "zip", "so", "keystore", "jks",
        "ttf", "otf", "woff", "woff2", "mp3", "mp4", "pdf", "class", "dex"
    )

    fun buildPattern(o: SearchOptions): Pattern? {
        if (o.query.isEmpty()) return null
        return try {
            var src = if (o.regex) o.query else Pattern.quote(o.query)
            if (o.wholeWord) src = "\\b(?:$src)\\b"
            var flags = Pattern.UNICODE_CASE
            if (!o.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE
            Pattern.compile(src, flags)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    fun isSearchable(file: File, o: SearchOptions): Boolean {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return false
        val ext = file.extension.lowercase()
        if (ext in BINARY_EXT) return false
        if (o.extensions.isNotEmpty() && ext !in o.extensions) return false
        return !looksBinary(file)
    }

    private fun looksBinary(file: File): Boolean = try {
        file.inputStream().use { ins ->
            val buf = ByteArray(2048)
            val n = ins.read(buf)
            (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
        }
    } catch (_: Exception) { true }

    fun listFiles(root: File): Sequence<File> =
        root.walkTopDown().onEnter { it == root || it.name !in SKIP_DIRS }.filter { it.isFile }

    fun search(root: File, o: SearchOptions, maxResults: Int = MAX_RESULTS): List<SearchMatch> {
        val pattern = buildPattern(o) ?: return emptyList()
        val out = ArrayList<SearchMatch>()
        for (file in listFiles(root)) {
            if (out.size >= maxResults) break
            if (!isSearchable(file, o)) continue
            try {
                file.bufferedReader().useLines { lines ->
                    var n = 0
                    for (line in lines) {
                        n++
                        val m = pattern.matcher(line)
                        if (m.find()) {
                            out.add(SearchMatch(file, file.relativeTo(root).path.replace('\\', '/'), n, m.start() + 1, line.trim().take(160)))
                            if (out.size >= maxResults) break
                        }
                    }
                }
            } catch (_: Exception) { /* unreadable file: skip */ }
        }
        return out
    }

    /** Replaces in one file. Returns the number of replacements (0 = unchanged). */
    fun replaceInFile(file: File, o: SearchOptions, replacement: String): Int {
        val pattern = buildPattern(o) ?: return 0
        if (!isSearchable(file, o)) return 0
        val text = file.readText()
        val m = pattern.matcher(text)
        var count = 0
        val sb = StringBuffer()
        val safeReplacement = if (o.regex) replacement else java.util.regex.Matcher.quoteReplacement(replacement)
        while (m.find()) {
            count++
            m.appendReplacement(sb, safeReplacement)
        }
        if (count == 0) return 0
        m.appendTail(sb)
        val tmp = File(file.parentFile, file.name + ".nexa_tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(sb.toString())
            tmp.delete()
        }
        return count
    }

    /** Replaces in all matching files (or only [onlyPaths] relative paths when given). */
    fun replaceAll(root: File, o: SearchOptions, replacement: String, onlyPaths: Set<String>? = null): ReplaceReport {
        var files = 0
        var total = 0
        val failed = mutableListOf<String>()
        for (file in listFiles(root)) {
            val rel = file.relativeTo(root).path.replace('\\', '/')
            if (onlyPaths != null && rel !in onlyPaths) continue
            try {
                val c = replaceInFile(file, o, replacement)
                if (c > 0) { files++; total += c }
            } catch (_: Exception) {
                failed.add(rel)
            }
        }
        return ReplaceReport(files, total, failed)
    }
}

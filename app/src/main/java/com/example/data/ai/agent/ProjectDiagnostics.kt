package com.example.data.ai.agent

import org.json.JSONObject
import java.io.File

/**
 * Lightweight, dependency-free checks used by get_diagnostics and the post-write verification pass.
 * Step 4 extends this with HTML/JS parsers and cloud-build log parsing; the [Problem] shape is final.
 */
object ProjectDiagnostics {
    private val CODE_EXT = setOf("kt", "kts", "java", "gradle", "js", "css", "ts")
    private val MARKUP_EXT = setOf("xml", "html", "htm")
    private val VOID_TAGS = setOf("meta", "link", "br", "hr", "img", "input", "area", "base", "col", "source", "track", "wbr", "!doctype")

    fun check(relativePath: String, text: String): List<Problem> {
        val ext = relativePath.substringAfterLast('.', "").lowercase()
        val out = mutableListOf<Problem>()
        when {
            ext in CODE_EXT -> out += bracketProblems(relativePath, text, ext)
            ext == "json" -> out += jsonProblems(relativePath, text)
            ext in MARKUP_EXT -> out += tagProblems(relativePath, text, ext == "xml")
        }
        PLACEHOLDER.findAll(text).forEach { m ->
            val line = text.substring(0, m.range.first).count { it == '\n' } + 1
            out += Problem(relativePath, line, 1, "Placeholder text found ('${m.value.trim().take(40)}'); write the complete code.", Problem.Severity.ERROR, "nexa-lint")
        }
        return out
    }

    fun checkFile(root: File, file: File): List<Problem> {
        if (!file.isFile || file.length() > 2L * 1024 * 1024) return emptyList()
        val rel = file.relativeTo(root.canonicalFile).path.replace('\\', '/')
        val text = try { file.readText() } catch (_: Exception) { return emptyList() }
        return check(rel, text)
    }

    val PLACEHOLDER = Regex("(?i)(\\.\\.\\.\\s*existing\\s+code\\s*\\.{0,3}|//\\s*\\.\\.\\.\\s*(rest|remaining)\\s+of|/\\*\\s*\\.\\.\\.\\s*\\*/)")

    private fun jsonProblems(path: String, text: String): List<Problem> = try {
        if (text.isBlank()) emptyList() else {
            val t = text.trim()
            if (t.startsWith("[")) org.json.JSONArray(t) else JSONObject(t)
            emptyList()
        }
    } catch (e: Exception) {
        listOf(Problem(path, 1, 1, "Invalid JSON: ${e.message?.take(120)}", Problem.Severity.ERROR, "json"))
    }

    /** Brackets/quotes balance, ignoring comments and string contents. */
    private fun bracketProblems(path: String, text: String, ext: String): List<Problem> {
        val stack = ArrayDeque<Triple<Char, Int, Int>>()
        val problems = mutableListOf<Problem>()
        var line = 1
        var col = 0
        var i = 0
        val n = text.length
        val isKotlinLike = ext == "kt" || ext == "kts" || ext == "java" || ext == "gradle"
        fun pair(c: Char) = when (c) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> ' ' }
        while (i < n) {
            val c = text[i]
            col++
            if (c == '\n') { line++; col = 0; i++; continue }
            if (c == '/' && i + 1 < n && text[i + 1] == '/') { while (i < n && text[i] != '\n') i++; continue }
            if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                val end = text.indexOf("*/", i + 2)
                val stop = if (end < 0) n else end + 2
                for (k in i until stop) if (text[k] == '\n') { line++; col = 0 }
                i = stop; continue
            }
            if (isKotlinLike && c == '"' && text.startsWith("\"\"\"", i)) {
                val end = text.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) n else end + 3
                if (end < 0) problems += Problem(path, line, col, "Unterminated raw string \"\"\"")
                for (k in i until stop) if (text[k] == '\n') { line++; col = 0 }
                i = stop; continue
            }
            if (c == '"' || (c == '\'' ) || (c == '`' && !isKotlinLike)) {
                val q = c
                val startLine = line; val startCol = col
                var j = i + 1
                var closed = false
                while (j < n) {
                    val d = text[j]
                    if (d == '\\') { j += 2; continue }
                    if (d == '\n' && q != '`') break
                    if (d == '\n') line++
                    if (d == q) { closed = true; break }
                    j++
                }
                if (!closed) problems += Problem(path, startLine, startCol, "Unterminated string/char literal")
                col += (j - i)
                i = if (closed) j + 1 else j
                continue
            }
            when (c) {
                '(', '[', '{' -> stack.addLast(Triple(c, line, col))
                ')', ']', '}' -> {
                    val top = stack.lastOrNull()
                    if (top == null || top.first != pair(c)) {
                        problems += Problem(path, line, col, "Unexpected '$c'")
                    } else stack.removeLast()
                }
            }
            i++
        }
        stack.forEach { (ch, l, cl) -> problems += Problem(path, l, cl, "Unclosed '$ch'") }
        return problems.take(50)
    }

    private val TAG = Regex("<(/?)([A-Za-z][A-Za-z0-9:._-]*|!DOCTYPE)([^<>]*?)(/?)>", RegexOption.IGNORE_CASE)

    private fun tagProblems(path: String, text: String, strictXml: Boolean): List<Problem> {
        val cleaned = text
            .replace(Regex("<!--[\\s\\S]*?-->")) { " ".repeat(it.value.length) }
            .let { s ->
                if (strictXml) s else s
                    .replace(Regex("(?is)<script[^>]*>[\\s\\S]*?</script>")) { m -> m.value.filter { it == '\n' }.let { nl -> "<script></script>$nl" } }
                    .replace(Regex("(?is)<style[^>]*>[\\s\\S]*?</style>")) { m -> m.value.filter { it == '\n' }.let { nl -> "<style></style>$nl" } }
            }
        val stack = ArrayDeque<Triple<String, Int, Int>>()
        val problems = mutableListOf<Problem>()
        for (m in TAG.findAll(cleaned)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val lower = name.lowercase()
            val selfClosing = m.groupValues[4] == "/"
            if (name.startsWith("?")) continue
            val before = cleaned.substring(0, m.range.first)
            val line = before.count { it == '\n' } + 1
            val col = m.range.first - (before.lastIndexOf('\n') + 1) + 1
            if (!strictXml && lower in VOID_TAGS) continue
            if (selfClosing) continue
            if (!closing) { stack.addLast(Triple(lower, line, col)); continue }
            val idx = stack.indexOfLast { it.first == lower }
            if (idx < 0) {
                problems += Problem(path, line, col, "Closing tag </$name> has no matching opening tag")
            } else {
                while (stack.size - 1 > idx) {
                    val dropped = stack.removeLast()
                    if (strictXml || dropped.first !in setOf("p", "li", "td", "tr", "th", "option", "dd", "dt")) {
                        problems += Problem(path, dropped.second, dropped.third, "Tag <${dropped.first}> is not closed")
                    }
                }
                stack.removeLast()
            }
        }
        stack.forEach { (t, l, c) ->
            if (strictXml || t !in setOf("p", "li", "td", "tr", "th", "option", "dd", "dt")) {
                problems += Problem(path, l, c, "Tag <$t> is not closed")
            }
        }
        return problems.take(50)
    }
}

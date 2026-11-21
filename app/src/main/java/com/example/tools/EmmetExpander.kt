package com.example.tools

/**
 * Small Emmet subset (HTML + CSS), pure Kotlin.
 * HTML: tag, #id, .class, [attr=val], {text}, > child, + sibling, ^ climb, *N repeat, '$' numbering,
 *       "!" / "html:5" boilerplate, implicit tags (ul>li, table>tr>td, select>option, bare .class -> div).
 * Not supported: groups "(...)" - returns null so the caller can leave the text unchanged.
 */
object EmmetExpander {
    private const val INDENT = "    "
    private val VOID = setOf("img", "input", "br", "hr", "meta", "link")
    private val IMPLICIT_CHILD = mapOf(
        "ul" to "li", "ol" to "li", "table" to "tr", "tbody" to "tr", "thead" to "tr",
        "tr" to "td", "select" to "option", "p" to "span", "nav" to "a"
    )
    private val DEFAULT_ATTRS = mapOf(
        "a" to listOf("href" to ""), "img" to listOf("src" to "", "alt" to ""),
        "link" to listOf("rel" to "stylesheet", "href" to ""), "script" to emptyList()
    )

    private class Node(var tag: String, val parent: Node?) {
        var id: String? = null
        val classes = mutableListOf<String>()
        val attrs = mutableListOf<Pair<String, String>>()
        var text: String? = null
        var repeat = 1
        val children = mutableListOf<Node>()
    }

    const val HTML5 = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n" +
        "    <meta charset=\"UTF-8\">\n" +
        "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
        "    <title>Document</title>\n</head>\n<body>\n    \n</body>\n</html>"

    fun expandHtml(abbr: String): String? {
        val a = abbr.trim()
        if (a.isEmpty() || a.contains(' ') && !a.contains('{') && !a.contains('[')) return null
        if (a == "!" || a == "html:5") return HTML5
        if (a.contains('(') || a.contains(')')) return null
        val roots = parse(a) ?: return null
        if (roots.isEmpty()) return null
        val sb = StringBuilder()
        render(roots, 0, null, sb)
        return sb.toString().trimEnd('\n')
    }

    private fun parse(src: String): List<Node>? {
        val roots = mutableListOf<Node>()
        var ctx: Node? = null
        var last: Node? = null
        var i = 0
        fun container() = ctx?.children ?: roots
        while (i < src.length) {
            val start = i
            // tag name
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '-' || src[i] == ':')) i++
            var tag = src.substring(start, i)
            val node = Node("", ctx)
            if (tag.isEmpty()) {
                // implicit tag
                tag = ctx?.let { IMPLICIT_CHILD[it.tag] } ?: "div"
                if (i >= src.length || src[i] !in ".#[{") return null
            }
            node.tag = tag
            while (i < src.length && src[i] in ".#[{") {
                when (src[i]) {
                    '#' -> { i++; val s = i; while (i < src.length && (src[i].isLetterOrDigit() || src[i] in "-_$")) i++; node.id = src.substring(s, i) }
                    '.' -> { i++; val s = i; while (i < src.length && (src[i].isLetterOrDigit() || src[i] in "-_$")) i++; node.classes.add(src.substring(s, i)) }
                    '[' -> {
                        val end = src.indexOf(']', i); if (end < 0) return null
                        parseAttrs(src.substring(i + 1, end), node.attrs); i = end + 1
                    }
                    '{' -> {
                        val end = src.indexOf('}', i); if (end < 0) return null
                        node.text = src.substring(i + 1, end); i = end + 1
                    }
                }
            }
            if (i < src.length && src[i] == '*') {
                i++; val s = i
                while (i < src.length && src[i].isDigit()) i++
                node.repeat = src.substring(s, i).toIntOrNull()?.coerceIn(1, 100) ?: return null
            }
            container().add(node)
            last = node
            if (i >= src.length) break
            when (src[i]) {
                '>' -> { ctx = last; i++ }
                '+' -> i++
                '^' -> { while (i < src.length && src[i] == '^') { ctx = ctx?.parent; i++ } }
                else -> return null
            }
            if (i >= src.length) return null // dangling operator
        }
        return roots
    }

    private fun parseAttrs(body: String, out: MutableList<Pair<String, String>>) {
        Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:=(\"[^\"]*\"|'[^']*'|[^\\s\\]]+))?").findAll(body).forEach { m ->
            val v = m.groupValues[2].trim('"', '\'')
            out.add(m.groupValues[1] to v)
        }
    }

    private fun number(s: String, index: Int): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            if (s[i] == '$') {
                var j = i; while (j < s.length && s[j] == '$') j++
                sb.append(index.toString().padStart(j - i, '0')); i = j
            } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    private fun esc(v: String) = v.replace("\"", "&quot;")

    private fun render(nodes: List<Node>, depth: Int, parent: Node?, sb: StringBuilder) {
        for (n in nodes) for (idx in 1..n.repeat) renderOne(n, idx, depth, sb)
    }

    private fun renderOne(n: Node, idx: Int, depth: Int, sb: StringBuilder) {
        val pad = INDENT.repeat(depth)
        val attrs = StringBuilder()
        n.id?.let { attrs.append(" id=\"").append(esc(number(it, idx))).append('"') }
        if (n.classes.isNotEmpty()) attrs.append(" class=\"").append(esc(n.classes.joinToString(" ") { number(it, idx) })).append('"')
        val given = n.attrs.map { it.first }.toSet()
        (DEFAULT_ATTRS[n.tag].orEmpty().filter { it.first !in given } + n.attrs).forEach { (k, v) ->
            attrs.append(' ').append(k).append("=\"").append(esc(number(v, idx))).append('"')
        }
        if (n.tag in VOID) { sb.append(pad).append('<').append(n.tag).append(attrs).append(">\n"); return }
        val text = n.text?.let { number(it, idx) }
        if (n.children.isEmpty()) {
            sb.append(pad).append('<').append(n.tag).append(attrs).append('>').append(text.orEmpty())
                .append("</").append(n.tag).append(">\n")
        } else {
            sb.append(pad).append('<').append(n.tag).append(attrs).append(">\n")
            if (text != null) sb.append(pad).append(INDENT).append(text).append('\n')
            render(n.children, depth + 1, n, sb)
            sb.append(pad).append("</").append(n.tag).append(">\n")
        }
    }

    // ---------------- CSS ----------------
    private val CSS_PROPS = mapOf(
        "m" to "margin", "mt" to "margin-top", "mr" to "margin-right", "mb" to "margin-bottom", "ml" to "margin-left",
        "p" to "padding", "pt" to "padding-top", "pr" to "padding-right", "pb" to "padding-bottom", "pl" to "padding-left",
        "w" to "width", "h" to "height", "fz" to "font-size", "fw" to "font-weight", "lh" to "line-height",
        "bg" to "background", "c" to "color", "d" to "display", "pos" to "position", "br" to "border-radius",
        "ta" to "text-align", "op" to "opacity", "z" to "z-index", "jc" to "justify-content", "ai" to "align-items",
        "fd" to "flex-direction", "t" to "top", "r" to "right", "b" to "bottom", "l" to "left", "gap" to "gap"
    )
    private val UNITLESS = setOf("opacity", "z-index", "font-weight", "line-height")
    private val CSS_VALUES = mapOf(
        "display" to mapOf("f" to "flex", "n" to "none", "b" to "block", "g" to "grid", "ib" to "inline-block", "i" to "inline"),
        "position" to mapOf("a" to "absolute", "r" to "relative", "f" to "fixed", "s" to "sticky"),
        "text-align" to mapOf("c" to "center", "l" to "left", "r" to "right", "j" to "justify"),
        "justify-content" to mapOf("c" to "center", "sb" to "space-between", "sa" to "space-around", "fs" to "flex-start", "fe" to "flex-end"),
        "align-items" to mapOf("c" to "center", "s" to "stretch", "fs" to "flex-start", "fe" to "flex-end"),
        "flex-direction" to mapOf("c" to "column", "r" to "row")
    )

    fun expandCss(abbr: String): String? {
        val a = abbr.trim().removeSuffix(";")
        if (a.isEmpty()) return null
        val kv = Regex("^([a-z]+):([a-z]+)$").matchEntire(a)
        if (kv != null) {
            val prop = CSS_PROPS[kv.groupValues[1]] ?: return null
            val value = CSS_VALUES[prop]?.get(kv.groupValues[2]) ?: return null
            return "$prop: $value;"
        }
        val num = Regex("^([a-z]+?)(-?\\d+(?:\\.\\d+)?)(px|em|rem|vh|vw|p|%)?$").matchEntire(a) ?: return null
        val prop = CSS_PROPS[num.groupValues[1]] ?: return null
        val unit = when (val u = num.groupValues[3]) {
            "" -> if (prop in UNITLESS) "" else "px"
            "p" -> "%"
            else -> u
        }
        return "$prop: ${num.groupValues[2]}$unit;"
    }
}

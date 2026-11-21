package com.example

import com.example.tools.EmmetExpander
import com.example.tools.ProjectSearchEngine
import com.example.tools.SearchOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Step4ToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(): File {
        val root = tmp.newFolder("proj")
        File(root, "app/src").mkdirs()
        File(root, "build").mkdirs()
        File(root, "app/src/A.kt").writeText("val Hello = 1\nfun hello() = Hello\n")
        File(root, "app/src/index.html").writeText("<p>hello world</p>\n")
        File(root, "build/Gen.kt").writeText("hello generated\n")
        File(root, "app/src/logo.png").writeBytes(byteArrayOf(0, 1, 2, 3, 104, 101, 108, 108, 111))
        return root
    }

    @Test fun searchSkipsBuildAndBinaryAndIsCaseInsensitive() {
        val r = ProjectSearchEngine.search(project(), SearchOptions("hello"))
        assertEquals(3, r.size) // A.kt x2 lines, index.html x1
        assertTrue(r.none { it.relativePath.startsWith("build/") || it.relativePath.endsWith(".png") })
    }

    @Test fun caseSensitiveWholeWordAndExtensionFilter() {
        val root = project()
        assertEquals(1, ProjectSearchEngine.search(root, SearchOptions("Hello", caseSensitive = true, extensions = setOf("kt"))).count { it.line == 1 })
        assertEquals(0, ProjectSearchEngine.search(root, SearchOptions("hell", wholeWord = true)).size)
    }

    @Test fun invalidRegexReturnsNoResultsInsteadOfCrashing() {
        assertTrue(ProjectSearchEngine.search(project(), SearchOptions("(", regex = true)).isEmpty())
    }

    @Test fun replaceAllReportsAndIsLiteralByDefault() {
        val root = project()
        val rep = ProjectSearchEngine.replaceAll(root, SearchOptions("Hello", caseSensitive = true), "\$1 Hi")
        assertEquals(1, rep.filesChanged)
        assertEquals(2, rep.replacements)
        assertEquals("val \$1 Hi = 1\nfun hello() = \$1 Hi\n", File(root, "app/src/A.kt").readText())
        assertEquals("hello generated\n", File(root, "build/Gen.kt").readText())
    }

    @Test fun regexReplaceSupportsGroups() {
        val root = project()
        ProjectSearchEngine.replaceAll(root, SearchOptions("(hello) (world)", regex = true), "\$2 \$1")
        assertEquals("<p>world hello</p>\n", File(root, "app/src/index.html").readText())
    }

    @Test fun emmetHtmlBasics() {
        assertEquals("<div class=\"box\"></div>", EmmetExpander.expandHtml(".box"))
        assertEquals("<div id=\"main\" class=\"a b\"></div>", EmmetExpander.expandHtml("#main.a.b"))
        assertEquals("<ul>\n    <li>Item 1</li>\n    <li>Item 2</li>\n</ul>", EmmetExpander.expandHtml("ul>li{Item \$}*2"))
        assertEquals("<a href=\"\">go</a>", EmmetExpander.expandHtml("a{go}"))
        assertEquals("<img src=\"\" alt=\"\">", EmmetExpander.expandHtml("img"))
        assertEquals("<input type=\"email\">", EmmetExpander.expandHtml("input[type=email]"))
    }

    @Test fun emmetSiblingsAndClimb() {
        assertEquals("<div>\n    <h1></h1>\n    <p></p>\n</div>", EmmetExpander.expandHtml("div>h1+p"))
        assertEquals("<div>\n    <p></p>\n</div>\n<span></span>", EmmetExpander.expandHtml("div>p^span"))
        assertEquals("<ul>\n    <li></li>\n</ul>", EmmetExpander.expandHtml("ul>li"))
    }

    @Test fun emmetBoilerplateAndInvalid() {
        assertTrue(EmmetExpander.expandHtml("!")!!.startsWith("<!DOCTYPE html>"))
        assertNull(EmmetExpander.expandHtml("(a>b)*2"))
        assertNull(EmmetExpander.expandHtml("div>"))
        assertNull(EmmetExpander.expandHtml(""))
    }

    @Test fun emmetCss() {
        assertEquals("margin: 10px;", EmmetExpander.expandCss("m10"))
        assertEquals("width: 50%;", EmmetExpander.expandCss("w50p"))
        assertEquals("opacity: 0.5;", EmmetExpander.expandCss("op0.5"))
        assertEquals("display: flex;", EmmetExpander.expandCss("d:f"))
        assertEquals("justify-content: space-between;", EmmetExpander.expandCss("jc:sb"))
        assertNull(EmmetExpander.expandCss("zzz9"))
    }
}

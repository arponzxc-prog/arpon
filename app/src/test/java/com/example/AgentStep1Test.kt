package com.example

import com.example.data.ai.agent.*
import com.example.data.local.entity.ProjectEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentStep1Test {
    @get:Rule val tmp = TemporaryFolder()

    // ---------- helpers ----------
    private class FakeHost(var project: ProjectEntity?, val base: File) : AgentProjectHost {
        override fun currentProject() = project
        override suspend fun createProject(name: String, packageName: String, template: String, language: String): ProjectEntity {
            val dir = File(base, "created_$name").also { it.mkdirs() }
            val p = ProjectEntity("id-$name", name, packageName, template, dir.absolutePath, language)
            project = p
            return p
        }
    }

    private class Script(private val turns: List<ModelTurn>) : AgentTransport {
        var index = 0
        val seen = mutableListOf<List<AgentMessage>>()
        override suspend fun complete(system: String, messages: List<AgentMessage>, tools: List<ToolSpec>): ModelTurn {
            seen.add(messages.toList())
            if (index >= turns.size) return ModelTurn("done", emptyList(), StopReason.END)
            return turns[index++]
        }
    }

    private fun setup(): Triple<File, FakeHost, ProjectToolExecutor> {
        val root = tmp.newFolder("proj")
        File(root, "app/src/main/java/com/demo").mkdirs()
        File(root, "app/src/main/java/com/demo/Main.kt").writeText("package com.demo\n\nfun main() {\n    println(\"hi\")\n}\n")
        File(root, ".git").mkdirs(); File(root, ".git/config").writeText("secret=1")
        File(root, "local.properties").writeText("sdk.dir=/x\ntoken=abc")
        val p = ProjectEntity("p1", "Demo", "com.demo", "Kotlin Compose App", root.absolutePath, "Kotlin")
        val host = FakeHost(p, tmp.root)
        return Triple(root, host, ProjectToolExecutor(host))
    }

    private fun call(name: String, vararg kv: Pair<String, Any>, id: String = "c_${System.nanoTime()}"): ToolCall {
        val o = JSONObject(); kv.forEach { (k, v) -> o.put(k, v) }
        return ToolCall(id, name, o)
    }

    private fun exec(e: ProjectToolExecutor, c: ToolCall) = runBlocking { e.execute(c) }

    // ---------- guard ----------
    @Test fun `guard blocks traversal git and secrets`() {
        val (root, _, _) = setup()
        assertTrue(ProjectGuard.resolve(root, "../outside.txt", "com.demo", true) is ProjectGuard.Resolved.Denied)
        assertTrue(ProjectGuard.resolve(root, ".git/config", "com.demo", false) is ProjectGuard.Resolved.Denied)
        assertTrue(ProjectGuard.resolve(root, "local.properties", "com.demo", false) is ProjectGuard.Resolved.Denied)
        assertTrue(ProjectGuard.resolve(root, "keys/release.jks", "com.demo", false) is ProjectGuard.Resolved.Denied)
        assertTrue(ProjectGuard.resolve(root, "build/out.txt", "com.demo", true) is ProjectGuard.Resolved.Denied)
        assertTrue(ProjectGuard.resolve(root, "app/src/Foo.kt", "com.demo", true) is ProjectGuard.Resolved.Ok)
    }

    // ---------- executor ----------
    @Test fun `read_file returns numbered lines and supports ranges`() {
        val (_, _, e) = setup()
        val r = exec(e, call("read_file", "path" to "app/src/main/java/com/demo/Main.kt", "start_line" to 3, "end_line" to 4))
        assertFalse(r.isError)
        assertTrue(r.content.contains("3| fun main() {"))
        assertFalse(r.content.contains("1| package"))
    }

    @Test fun `read_file refuses secrets and git`() {
        val (_, _, e) = setup()
        assertTrue(exec(e, call("read_file", "path" to "local.properties")).isError)
        assertTrue(exec(e, call("read_file", "path" to ".git/config")).isError)
    }

    @Test fun `write_file creates new but refuses existing without overwrite`() {
        val (root, _, e) = setup()
        assertFalse(exec(e, call("write_file", "path" to "app/src/New.kt", "content" to "class New")).isError)
        assertEquals("class New", File(root, "app/src/New.kt").readText())
        val again = exec(e, call("write_file", "path" to "app/src/New.kt", "content" to "class Other"))
        assertTrue(again.isError)
        assertEquals("class New", File(root, "app/src/New.kt").readText())
        assertFalse(exec(e, call("write_file", "path" to "app/src/New.kt", "content" to "class Other", "overwrite" to true)).isError)
    }

    @Test fun `write_file rejects placeholders`() {
        val (_, _, e) = setup()
        val r = exec(e, call("write_file", "path" to "a.kt", "content" to "fun a() {\n // ... existing code ...\n}"))
        assertTrue(r.isError)
    }

    @Test fun `edit_file requires exactly one match`() {
        val (root, _, e) = setup()
        val f = "app/src/main/java/com/demo/Main.kt"
        assertFalse(exec(e, call("edit_file", "path" to f, "old_text" to "println(\"hi\")", "new_text" to "println(\"hello\")")).isError)
        assertTrue(File(root, f).readText().contains("hello"))
        assertTrue(exec(e, call("edit_file", "path" to f, "old_text" to "does not exist", "new_text" to "x")).isError)
        File(root, f).writeText("a\na\n")
        val multi = exec(e, call("edit_file", "path" to f, "old_text" to "a", "new_text" to "b"))
        assertTrue(multi.isError)
        assertTrue(multi.content.contains("2 places"))
        assertEquals("a\na\n", File(root, f).readText())
    }

    @Test fun `delete and rename work and respect guard`() {
        val (root, _, e) = setup()
        assertFalse(exec(e, call("rename_file", "path" to "app/src/main/java/com/demo/Main.kt", "new_path" to "app/src/main/java/com/demo/App.kt")).isError)
        assertTrue(File(root, "app/src/main/java/com/demo/App.kt").exists())
        assertFalse(exec(e, call("delete_file", "path" to "app/src/main/java/com/demo/App.kt")).isError)
        assertTrue(exec(e, call("delete_file", "path" to ".git/config")).isError)
        assertTrue(File(root, ".git/config").exists())
    }

    @Test fun `undo journal restores edits creations and deletions`() {
        val (root, _, e) = setup()
        val f = "app/src/main/java/com/demo/Main.kt"
        val original = File(root, f).readText()
        exec(e, call("edit_file", "path" to f, "old_text" to "hi", "new_text" to "yo"))
        exec(e, call("write_file", "path" to "app/src/Brand.kt", "content" to "class Brand"))
        File(root, "extra.txt").writeText("keep me")
        exec(e, call("delete_file", "path" to "extra.txt"))
        assertFalse(File(root, "extra.txt").exists())
        val restored = e.undoJournal()!!.restore()
        assertEquals(3, restored.size)
        assertEquals(original, File(root, f).readText())
        assertFalse(File(root, "app/src/Brand.kt").exists())
        assertEquals("keep me", File(root, "extra.txt").readText())
    }

    @Test fun `unknown tool and broken args return errors instead of throwing`() {
        val (_, _, e) = setup()
        assertTrue(exec(e, call("format_disk")).isError)
        val broken = ToolCall("b", "write_file", JSONObject(), argsError = "Unterminated string")
        val r = exec(e, broken)
        assertTrue(r.isError); assertTrue(r.content.contains("cut off"))
    }

    @Test fun `search and list tools`() {
        val (_, _, e) = setup()
        val s = exec(e, call("search_in_project", "query" to "println"))
        assertTrue(s.content.contains("Main.kt:4"))
        val l = exec(e, call("list_files", "path" to ""))
        assertTrue(l.content.contains("app/src/main/java/com/demo/Main.kt"))
        assertFalse(l.content.contains(".git"))
        assertFalse(l.content.contains("local.properties"))
    }

    @Test fun `create_project goes through host and normalises names`() {
        val (_, host, e) = setup()
        val r = exec(e, call("create_project", "name" to "Todo: App", "package_name" to "My.Todo", "template" to "Compose", "language" to "Kotlin"))
        assertFalse(r.isError)
        assertEquals("Todo App", host.project!!.name)
        assertEquals("my.todo", host.project!!.packageName)
        assertEquals("Kotlin Compose App", host.project!!.templateType)
    }

    // ---------- diagnostics ----------
    @Test fun `diagnostics detect brackets json and html`() {
        assertTrue(ProjectDiagnostics.check("A.kt", "fun a() {\n println(\"x\")\n").any { it.message.contains("Unclosed '{'") })
        assertTrue(ProjectDiagnostics.check("A.kt", "fun a() { val s = \"(\" }\n").isEmpty())
        assertTrue(ProjectDiagnostics.check("a.json", "{\"a\": }").isNotEmpty())
        assertTrue(ProjectDiagnostics.check("a.json", "{\"a\": 1}").isEmpty())
        assertTrue(ProjectDiagnostics.check("i.html", "<html><body><div></body></html>").isNotEmpty())
        assertTrue(ProjectDiagnostics.check("i.html", "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><p>x</p><br></body></html>").isEmpty())
    }

    // ---------- protocols ----------
    @Test fun `openai request and response with tool calls`() {
        val tools = AgentToolSpecs.all(false)
        val req = OpenAiProtocol.buildRequest("https://api.openai.com/v1", "gpt-4o", "KEY", "sys",
            listOf(AgentMessage.User("hi"),
                AgentMessage.Assistant("", listOf(ToolCall("c1", "read_file", JSONObject().put("path", "a")))),
                AgentMessage.ToolResults(listOf(ToolResult("c1", "read_file", "ok")))), tools, 8192)
        assertEquals("https://api.openai.com/v1/chat/completions", req.url)
        assertEquals("Bearer KEY", req.headers["Authorization"])
        val body = JSONObject(req.body)
        val msgs = body.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("tool", msgs.getJSONObject(3).getString("role"))
        assertEquals("c1", msgs.getJSONObject(3).getString("tool_call_id"))
        assertTrue(body.getJSONArray("tools").length() >= 10)

        val resp = """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"x1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.kt\"}"}}]}}]}"""
        val turn = OpenAiProtocol.parseResponse(resp)
        assertEquals(StopReason.TOOL_USE, turn.stopReason)
        assertEquals("a.kt", turn.toolCalls[0].args.getString("path"))
    }

    @Test fun `openai truncated arguments are flagged not dropped`() {
        val resp = """{"choices":[{"finish_reason":"length","message":{"content":"","tool_calls":[{"id":"x1","type":"function","function":{"name":"write_file","arguments":"{\"path\":\"a.kt\",\"content\":\"fun a() {"}}]}}]}"""
        val turn = OpenAiProtocol.parseResponse(resp)
        assertEquals(StopReason.LENGTH, turn.stopReason)
        assertNotNull(turn.toolCalls[0].argsError)
    }

    @Test fun `anthropic merges roles and parses tool_use`() {
        val req = AnthropicProtocol.buildRequest("https://api.anthropic.com/v1", "claude", "K", "sys",
            listOf(AgentMessage.User("go"),
                AgentMessage.Assistant("thinking", listOf(ToolCall("t1", "list_files", JSONObject()), ToolCall("t2", "get_project_info", JSONObject()))),
                AgentMessage.ToolResults(listOf(ToolResult("t1", "list_files", "a"), ToolResult("t2", "get_project_info", "b", true)))),
            AgentToolSpecs.all(false), 8192)
        assertEquals("K", req.headers["x-api-key"])
        val msgs = JSONObject(req.body).getJSONArray("messages")
        assertEquals(3, msgs.length())
        assertEquals(2, msgs.getJSONObject(2).getJSONArray("content").length())
        val resp = """{"stop_reason":"tool_use","content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"u1","name":"read_file","input":{"path":"x"}}]}"""
        val turn = AnthropicProtocol.parseResponse(resp)
        assertEquals(StopReason.TOOL_USE, turn.stopReason)
        assertEquals("u1", turn.toolCalls[0].id)
    }

    @Test fun `gemini keeps key out of url and echoes signature`() {
        val call = ToolCall("g1", "read_file", JSONObject().put("path", "a"), signature = "SIG")
        val req = GeminiProtocol.buildRequest("https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", "SECRETKEY", "sys",
            listOf(AgentMessage.User("x"), AgentMessage.Assistant("", listOf(call)), AgentMessage.ToolResults(listOf(ToolResult("g1", "read_file", "r")))),
            AgentToolSpecs.all(false), 8192)
        assertFalse(req.url.contains("SECRETKEY"))
        assertEquals("SECRETKEY", req.headers["x-goog-api-key"])
        assertTrue(req.body.contains("SIG"))
        val resp = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"z"}},"thoughtSignature":"S2"}]}}]}"""
        val turn = GeminiProtocol.parseResponse(resp)
        assertEquals(StopReason.TOOL_USE, turn.stopReason)
        assertEquals("S2", turn.toolCalls[0].signature)
    }

    // ---------- loop ----------
    private fun turnWith(vararg calls: ToolCall, text: String = "") =
        ModelTurn(text, calls.toList(), if (calls.isEmpty()) StopReason.END else StopReason.TOOL_USE)

    @Test fun `loop runs tools and returns summary`() = runBlocking {
        val (root, host, e) = setup()
        val script = Script(listOf(
            turnWith(call("read_file", "path" to "app/src/main/java/com/demo/Main.kt")),
            turnWith(call("edit_file", "path" to "app/src/main/java/com/demo/Main.kt", "old_text" to "hi", "new_text" to "dark")),
            ModelTurn("সম্পন্ন", emptyList(), StopReason.END)
        ))
        val result = AgentLoop(script, e).run(AgentSession(), "add dark", { "sys" }, false)
        assertEquals(AgentStopCause.COMPLETED, result.cause)
        assertEquals(listOf("app/src/main/java/com/demo/Main.kt"), result.filesEdited)
        assertTrue(File(root, "app/src/main/java/com/demo/Main.kt").readText().contains("dark"))
        assertEquals("সম্পন্ন", result.finalText)
        assertEquals(0, result.failedToolCalls)
    }

    @Test fun `failed tool result is sent back so model can correct itself`() = runBlocking {
        val (_, _, e) = setup()
        val script = Script(listOf(
            turnWith(call("edit_file", "path" to "app/src/main/java/com/demo/Main.kt", "old_text" to "nope", "new_text" to "x")),
            ModelTurn("gave up", emptyList(), StopReason.END)
        ))
        val r = AgentLoop(script, e).run(AgentSession(), "x", { "s" }, false)
        assertEquals(1, r.failedToolCalls)
        val lastSeen = script.seen.last().last() as AgentMessage.ToolResults
        assertTrue(lastSeen.results[0].isError)
    }

    @Test fun `identical repeated call stops the loop`() = runBlocking {
        val (_, _, e) = setup()
        val same = { call("read_file", "path" to "app/src/main/java/com/demo/Main.kt") }
        val script = Script(List(10) { turnWith(same()) })
        val r = AgentLoop(script, e, AgentConfig(repeatLimit = 3)).run(AgentSession(), "x", { "s" }, false)
        assertEquals(AgentStopCause.REPEATED_CALL, r.cause)
    }

    @Test fun `step limit stops the loop`() = runBlocking {
        val (_, _, e) = setup()
        var n = 0
        val script = Script(List(20) { turnWith(call("list_files", "path" to "", "depth" to (++n % 7 + 1))) })
        val r = AgentLoop(script, e, AgentConfig(maxSteps = 4, repeatLimit = 99)).run(AgentSession(), "x", { "s" }, false)
        assertEquals(AgentStopCause.MAX_STEPS, r.cause)
        assertEquals(4, r.steps)
    }

    @Test fun `length cut text is continued automatically and reported`() = runBlocking {
        val (_, _, e) = setup()
        val script = Script(listOf(
            ModelTurn("part one", emptyList(), StopReason.LENGTH),
            ModelTurn("part two", emptyList(), StopReason.END)
        ))
        val r = AgentLoop(script, e).run(AgentSession(), "x", { "s" }, false)
        assertEquals(AgentStopCause.COMPLETED, r.cause)
        assertTrue(r.notices.any { it.contains("cut off") })
        assertTrue(script.seen.last().any { it is AgentMessage.User && it.text.contains("Continue") })
    }

    @Test fun `truncated tool call gets explicit error and is retried`() = runBlocking {
        val (root, _, e) = setup()
        val broken = ToolCall("b1", "write_file", JSONObject(), argsError = "Unterminated string")
        val script = Script(listOf(
            ModelTurn("", listOf(broken), StopReason.LENGTH),
            turnWith(call("write_file", "path" to "app/src/Small.kt", "content" to "class Small")),
            ModelTurn("ok", emptyList(), StopReason.END)
        ))
        val r = AgentLoop(script, e).run(AgentSession(), "x", { "s" }, false)
        assertEquals(AgentStopCause.COMPLETED, r.cause)
        assertTrue(File(root, "app/src/Small.kt").exists())
        assertEquals(1, r.failedToolCalls)
        assertTrue(r.notices.isNotEmpty())
    }

    @Test fun `ask_user pauses and the next message answers the tool call`() = runBlocking {
        val (_, _, e) = setup()
        val ask = ToolCall("ask1", "ask_user", JSONObject("""{"questions":[{"id":"t","question":"App type?","options":["Compose","Web"]}]}"""))
        val script = Script(listOf(
            ModelTurn("", listOf(ask), StopReason.TOOL_USE),
            ModelTurn("plan ready", emptyList(), StopReason.END)
        ))
        val session = AgentSession()
        val loop1 = AgentLoop(script, e).run(session, "make a todo app", { "s" }, false)
        assertEquals(AgentStopCause.WAITING_FOR_USER, loop1.cause)
        assertEquals("App type?", loop1.pendingQuestion!!.questions[0].question)
        assertNotNull(session.pendingAsk)
        val loop2 = AgentLoop(script, ProjectToolExecutor(FakeHost(null, tmp.root))).run(session, "Compose", { "s" }, false)
        assertEquals(AgentStopCause.COMPLETED, loop2.cause)
        assertNull(session.pendingAsk)
        val tr = script.seen.last().filterIsInstance<AgentMessage.ToolResults>().last()
        assertEquals("ask1", tr.results[0].callId)
        assertTrue(tr.results[0].content.contains("Compose"))
    }

    @Test fun `cancel leaves transcript valid and journal undoable`() {
        val (root, _, e) = setup()
        val script = Script(listOf(
            turnWith(call("write_file", "path" to "app/src/A.kt", "content" to "class A")),
            turnWith(call("write_file", "path" to "app/src/B.kt", "content" to "class B"))
        ))
        val session = AgentSession()
        var started = 0
        val events = object : AgentEvents {
            override fun onToolStarted(call: ToolCall) { if (++started == 2) throw CancellationException("stop") }
        }
        try {
            runBlocking { AgentLoop(script, e, events = events).run(session, "x", { "s" }, false) }
            org.junit.Assert.fail("expected cancellation")
        } catch (_: CancellationException) { }
        val last = session.messages.last()
        assertTrue(last is AgentMessage.ToolResults)
        assertTrue((last as AgentMessage.ToolResults).results[0].isError)
        assertTrue(File(root, "app/src/A.kt").exists())
        e.undoJournal()!!.restore()
        assertFalse(File(root, "app/src/A.kt").exists())
    }

    @Test fun `verify pass sends problems back to the model max rounds`() = runBlocking {
        val (_, _, e) = setup()
        val bad = call("write_file", "path" to "app/src/Bad.kt", "content" to "fun a() {\n")
        val script = Script(listOf(
            turnWith(bad),
            ModelTurn("done?", emptyList(), StopReason.END),
            turnWith(call("edit_file", "path" to "app/src/Bad.kt", "old_text" to "fun a() {\n", "new_text" to "fun a() {\n}\n")),
            ModelTurn("fixed", emptyList(), StopReason.END)
        ))
        val r = AgentLoop(script, e).run(AgentSession(), "x", { "s" }, false)
        assertEquals(AgentStopCause.COMPLETED, r.cause)
        assertTrue(r.remainingProblems.isEmpty())
        assertTrue(script.seen[2].any { it is AgentMessage.User && it.text.contains("Automatic check") })
    }

    @Test fun `failover notice from transport is surfaced`() = runBlocking {
        val (_, _, e) = setup()
        val t = AgentTransport { _, _, _ -> ModelTurn("hi", emptyList(), StopReason.END, "Backup", "ℹ️ shifted") }
        val r = AgentLoop(t, e).run(AgentSession(), "x", { "s" }, false)
        assertTrue(r.notices.contains("ℹ️ shifted"))
    }

    // ---------- context ----------
    @Test fun `trim keeps recent turn drops oldest and starts with user`() {
        val big = "x".repeat(5000)
        val msgs = mutableListOf<AgentMessage>()
        repeat(6) { i ->
            msgs.add(AgentMessage.User("turn $i $big"))
            msgs.add(AgentMessage.Assistant("a$i", listOf(ToolCall("c$i", "read_file", JSONObject()))))
            msgs.add(AgentMessage.ToolResults(listOf(ToolResult("c$i", "read_file", big))))
        }
        val trimmed = AgentContext.trim(msgs, 12_000)
        assertTrue(trimmed.first() is AgentMessage.User)
        assertTrue((trimmed.first() as AgentMessage.User).text.startsWith("turn "))
        assertTrue(trimmed.size < msgs.size)
        val lastUser = trimmed.filterIsInstance<AgentMessage.User>().last().text
        assertTrue(lastUser.contains("turn 5"))
        // every tool call that remains has its result
        val ids = trimmed.filterIsInstance<AgentMessage.Assistant>().flatMap { it.toolCalls }.map { it.id }.toSet()
        val resIds = trimmed.filterIsInstance<AgentMessage.ToolResults>().flatMap { it.results }.map { it.callId }.toSet()
        assertEquals(ids, resIds)
    }

    @Test fun `project map has one line summaries`() {
        val (_, host, _) = setup()
        val map = AgentContext.buildProjectMap(host.project)
        assertTrue(map.contains("app/src/main/java/com/demo/Main.kt — fun main()"))
        assertFalse(map.contains("local.properties"))
    }

    @Test fun `runner answers identity locally and exposes undo`() = runBlocking {
        val (root, host, _) = setup()
        val script = Script(listOf(
            turnWith(call("write_file", "path" to "app/src/R.kt", "content" to "class R")),
            ModelTurn("ok", emptyList(), StopReason.END)
        ))
        val runner = NexaAgentRunner(script, host)
        val r = runner.send("add file")
        assertEquals(1, r.filesWritten.size)
        assertTrue(runner.canUndo())
        runner.undo()
        assertFalse(File(root, "app/src/R.kt").exists())
    }
}

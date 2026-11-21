package com.example.data.ai.agent

import org.json.JSONArray
import org.json.JSONObject

/** Tool definitions sent to every provider (neutral JSON-schema; adapters translate). */
object AgentToolSpecs {
    const val LIST_FILES = "list_files"
    const val READ_FILE = "read_file"
    const val SEARCH = "search_in_project"
    const val PROJECT_INFO = "get_project_info"
    const val CREATE_PROJECT = "create_project"
    const val WRITE_FILE = "write_file"
    const val EDIT_FILE = "edit_file"
    const val DELETE_FILE = "delete_file"
    const val RENAME_FILE = "rename_file"
    const val DIAGNOSTICS = "get_diagnostics"
    const val ASK_USER = "ask_user"
    const val TRIGGER_BUILD = "trigger_build"
    const val BUILD_LOG = "get_build_log"

    val WRITE_TOOLS = setOf(WRITE_FILE, EDIT_FILE, DELETE_FILE, RENAME_FILE)
    /** Allowed in "Fix mode" (Step 4). */
    val FIX_MODE_TOOLS = setOf(READ_FILE, SEARCH, EDIT_FILE, DIAGNOSTICS)

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

    private fun obj(props: Map<String, JSONObject>, required: List<String>): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
    }

    private fun spec(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()) =
        ToolSpec(name, desc, obj(props, required))

    fun all(buildToolsAvailable: Boolean): List<ToolSpec> {
        val base = listOf(
            spec(LIST_FILES, "List files/folders of the active project (paths are relative to the project root). Build output and .git are hidden.",
                mapOf("path" to str("Sub-folder, relative. Empty = project root."), "depth" to int("Max depth (default 3)."))),
            spec(READ_FILE, "Read a text file with line numbers. Use start_line/end_line for big files (max 400 lines per call).",
                mapOf("path" to str("Relative file path."), "start_line" to int("1-based first line."), "end_line" to int("1-based last line (inclusive).")),
                listOf("path")),
            spec(SEARCH, "Search text/regex across project files. Returns file:line:preview.",
                mapOf("query" to str("Text or regex."), "regex" to bool("Treat query as regex."), "case_sensitive" to bool("Default false."),
                    "extensions" to str("Comma separated extensions to limit, e.g. 'kt,xml'.")),
                listOf("query")),
            spec(PROJECT_INFO, "Project name, package, template, language and file count."),
            spec(CREATE_PROJECT, "Create a brand-new project and make it the active one. Call only after the user approved the plan.",
                mapOf("name" to str("App name (max 30 chars)."), "package_name" to str("e.g. com.nexa.todo"),
                    "template" to str("One of: Kotlin Compose App, Kotlin Android App, Java Android App, HTML Single File Project, HTML Multi-File Project, Android WebView App."),
                    "language" to str("Kotlin, Java or Webapp.")),
                listOf("name", "template")),
            spec(WRITE_FILE, "Create ONE new file with its COMPLETE content. Fails if the file exists unless overwrite=true (use only for deliberate full rewrites; prefer edit_file). One file per call.",
                mapOf("path" to str("Relative file path."), "content" to str("Complete file content."), "overwrite" to bool("Allow replacing an existing file. Default false.")),
                listOf("path", "content")),
            spec(EDIT_FILE, "Replace exact text in an existing file. old_text must match EXACTLY ONE place (include surrounding lines to make it unique). Fails otherwise.",
                mapOf("path" to str("Relative file path."), "old_text" to str("Exact existing text to find."), "new_text" to str("Replacement text (may be empty to delete).")),
                listOf("path", "old_text", "new_text")),
            spec(DELETE_FILE, "Delete one file.", mapOf("path" to str("Relative file path.")), listOf("path")),
            spec(RENAME_FILE, "Rename or move a file inside the project. Fails if the target exists.",
                mapOf("path" to str("Current relative path."), "new_path" to str("New relative path.")), listOf("path", "new_path")),
            spec(DIAGNOSTICS, "List current syntax problems of a file (or all recently changed files when path is empty).",
                mapOf("path" to str("Relative file path, or empty."))),
            spec(ASK_USER, "Ask the user 3-6 short questions BEFORE building a new project. Each question has 2-5 short options. Ask once; never repeat a question.",
                mapOf("questions" to JSONObject().put("type", "array").put("description", "Questions to show as tappable cards.")
                    .put("items", obj(mapOf(
                        "id" to str("Short id."), "question" to str("Question text."),
                        "options" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                        "multi_select" to bool("Allow several answers.")
                    ), listOf("id", "question", "options")))),
                listOf("questions"))
        )
        val build = if (buildToolsAvailable) listOf(
            spec(TRIGGER_BUILD, "Start a cloud build of the active project (needs GitHub connected)."),
            spec(BUILD_LOG, "Get the latest cloud build status and the extracted compile errors.")
        ) else emptyList()
        return base + build
    }
}

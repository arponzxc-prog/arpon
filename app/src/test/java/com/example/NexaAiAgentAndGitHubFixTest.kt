package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.NexaAiAgentEngine
import com.example.data.filesystem.ProjectFileManager
import com.example.data.github.GitHubAccountHelper
import com.example.data.github.GitHubService
import com.example.data.local.entity.ProjectEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.http.Query
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaAiAgentAndGitHubFixTest {

    @Test
    fun `getUserRepositories does not mix conflicting type and affiliation query params`() {
        val method = GitHubService::class.java.methods.first { it.name == "getUserRepositories" }
        val queryNames = method.parameterAnnotations
            .flatMap { annotations -> annotations.filterIsInstance<Query>() }
            .map { it.value }

        assertFalse(
            "GitHub /user/repos rejects requests containing both 'type' and 'affiliation' with HTTP 422",
            queryNames.contains("type") && queryNames.contains("affiliation")
        )
        assertTrue(queryNames.contains("affiliation"))
    }

    @Test
    fun `GitHubAccountHelper formats 422 and 409 JSON error bodies clearly`() {
        val err422 = GitHubAccountHelper.formatErrorBody(
            422,
            """{"message":"Validation Failed","errors":[{"message":"If you specify an affiliation, you cannot specify type"}]}"""
        )
        assertTrue(err422.contains("422"))
        assertTrue(err422.contains("Validation Failed"))

        val err409 = GitHubAccountHelper.formatErrorBody(
            409,
            """{"message":"Git Repository is empty."}"""
        )
        assertTrue(err409.contains("empty", ignoreCase = true))
    }

    @Test
    fun `parseAgentPlan returns null for normal chat and extracts valid project plan`() {
        val normalChat = "Sure! A Compose Text composable displays text on screen."
        assertNull(NexaAiAgentEngine.parseAgentPlan(normalChat))

        val aiResponse = """
            I created a new Jetpack Compose Calculator project with full dark Material 3 UI.

            ```nexa_actions
            {
              "project": {
                "create": true,
                "name": "SmartCalc",
                "packageName": "com.nexa.smartcalc",
                "templateType": "Kotlin Compose App",
                "language": "Kotlin"
              },
              "files": [
                {
                  "path": "app/src/main/java/com/nexa/smartcalc/MainActivity.kt",
                  "content": "package com.nexa.smartcalc\nclass MainActivity"
                },
                {
                  "path": "../evil_outside.txt",
                  "content": "should be blocked"
                }
              ],
              "summary": "Generated SmartCalc"
            }
            ```
        """.trimIndent()

        val plan = NexaAiAgentEngine.parseAgentPlan(aiResponse)
        assertNotNull(plan)
        assertEquals("SmartCalc", plan!!.project?.name)
        assertEquals("com.nexa.smartcalc", plan.project?.packageName)
        assertEquals("Kotlin Compose App", plan.project?.templateType)
        assertEquals(2, plan.files.size)
    }

    @Test
    fun `executePlan creates project on disk, writes valid files, and blocks path traversal`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileManager = ProjectFileManager(context)

        val aiResponse = """
            Built your Portfolio Webapp!
            ```nexa_actions
            {
              "project": {
                "create": true,
                "name": "My Portfolio",
                "packageName": "com.nexa.portfolio",
                "templateType": "HTML Multi-File Project",
                "language": "Webapp"
              },
              "files": [
                {
                  "path": "app/src/main/assets/index.html",
                  "content": "<h1>Hello Portfolio</h1>"
                },
                {
                  "path": "app/src/main/assets/style.css",
                  "content": "body { background: #0f172a; }"
                },
                {
                  "path": "../../outside.txt",
                  "content": "blocked"
                }
              ]
            }
            ```
        """.trimIndent()

        val plan = NexaAiAgentEngine.parseAgentPlan(aiResponse)!!
        var inserted: ProjectEntity? = null
        var updated: ProjectEntity? = null

        val result = NexaAiAgentEngine.executePlan(
            plan = plan,
            currentActiveProject = null,
            fileManager = fileManager,
            onInsertProject = { inserted = it },
            onUpdateProject = { updated = it }
        )

        assertTrue(result.createdNewProject)
        assertNotNull(inserted)
        assertNotNull(updated)
        assertEquals("My Portfolio", result.project.name)
        assertTrue(result.writtenFiles.contains("app/src/main/assets/index.html"))
        assertTrue(result.writtenFiles.contains("app/src/main/assets/style.css"))
        assertTrue(result.skippedFiles.contains("../../outside.txt"))

        val root = File(result.project.rootPath)
        assertEquals("<h1>Hello Portfolio</h1>", File(root, "app/src/main/assets/index.html").readText())
        assertEquals("body { background: #0f172a; }", File(root, "app/src/main/assets/style.css").readText())
        assertFalse(File(root.parentFile, "outside.txt").exists())

        // Cleanup test project directory
        root.deleteRecursively()
    }
}

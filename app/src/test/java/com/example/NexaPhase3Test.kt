package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.assets.AssetStudioProcessor
import com.example.assets.IconShapeOption
import com.example.assets.ImageExportFormat
import com.example.data.filesystem.ProjectFileManager
import com.example.data.github.GitHubAccountHelper
import com.example.data.github.GitHubUser
import com.example.data.github.TokenValidationStatus
import com.example.data.local.entity.ProjectEntity
import com.example.data.security.SecureTokenManager
import com.example.terminal.TerminalCommandOutcome
import com.example.terminal.TerminalEngine
import com.example.testing.TestResultStatus
import com.example.testing.TestingLabEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaPhase3Test {

    private lateinit var context: Context
    private lateinit var fileManager: ProjectFileManager
    private lateinit var tempWorkspace: File
    private lateinit var sampleProject: ProjectEntity

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileManager = ProjectFileManager(context)
        tempWorkspace = File(context.filesDir, "phase3_test_proj_${System.nanoTime()}").apply { mkdirs() }

        // Seed minimal valid Android project inside tempWorkspace
        File(tempWorkspace, "settings.gradle.kts").writeText("rootProject.name = \"TestApp\"\n")
        File(tempWorkspace, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }\n")
        val appDir = File(tempWorkspace, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText(
            """
            android {
                defaultConfig {
                    applicationId = "com.test.sample"
                    versionCode = 1
                    versionName = "1.0"
                }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
            }
            """.trimIndent()
        )
        val mainDir = File(appDir, "src/main").apply { mkdirs() }
        File(mainDir, "AndroidManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="TestApp">
                    <activity android:name=".MainActivity" android:exported="true" />
                </application>
            </manifest>
            """.trimIndent()
        )
        File(mainDir, "res/values").apply { mkdirs() }
        File(mainDir, "res/values/strings.xml").writeText("<resources><string name=\"app_name\">TestApp</string></resources>")
        File(mainDir, "java/com/test/sample").apply { mkdirs() }
        File(mainDir, "java/com/test/sample/MainActivity.kt").writeText("class MainActivity { fun ok() { val x = 1 } }")

        sampleProject = ProjectEntity(
            id = "proj-phase3-test",
            name = "TestApp",
            packageName = "com.test.sample",
            rootPath = tempWorkspace.absolutePath,
            templateType = "Kotlin Android Project",
            language = "Kotlin"
        )
    }

    // ==================== PART 7: INTEGRATED TERMINAL TESTS ====================

    @Test
    fun `terminal blocks path traversal and shell chaining`() = runBlocking {
        val engine = TerminalEngine(context, fileManager)

        val traversal = engine.executeCommand("cd ../../", sampleProject, sampleProject.rootPath)
        assertTrue(traversal is TerminalCommandOutcome.Completed)
        assertNotEquals(0, (traversal as TerminalCommandOutcome.Completed).exitCode)

        val outsideRead = engine.executeCommand("cat /etc/passwd", sampleProject, sampleProject.rootPath)
        assertTrue(outsideRead is TerminalCommandOutcome.Completed)
        assertNotEquals(0, (outsideRead as TerminalCommandOutcome.Completed).exitCode)

        val chaining = engine.executeCommand("ls && pwd", sampleProject, sampleProject.rootPath)
        assertTrue(chaining is TerminalCommandOutcome.Completed)
        assertEquals(126, (chaining as TerminalCommandOutcome.Completed).exitCode)
    }

    @Test
    fun `terminal enforces confirmation for destructive commands and executes safe workspace ops`() = runBlocking {
        val engine = TerminalEngine(context, fileManager)

        val mkdirOut = engine.executeCommand("mkdir notes", sampleProject, sampleProject.rootPath) as TerminalCommandOutcome.Completed
        assertEquals(0, mkdirOut.exitCode)
        assertTrue(File(tempWorkspace, "notes").isDirectory)

        val touchOut = engine.executeCommand("touch notes/todo.txt", sampleProject, sampleProject.rootPath) as TerminalCommandOutcome.Completed
        assertEquals(0, touchOut.exitCode)
        assertTrue(File(tempWorkspace, "notes/todo.txt").exists())

        val unconfirmedRm = engine.executeCommand("rm notes/todo.txt", sampleProject, sampleProject.rootPath)
        assertTrue(unconfirmedRm is TerminalCommandOutcome.RequiresConfirmation)
        assertTrue(File(tempWorkspace, "notes/todo.txt").exists())

        val confirmedRm = engine.executeCommand("rm notes/todo.txt", sampleProject, sampleProject.rootPath, confirmedDestructive = true) as TerminalCommandOutcome.Completed
        assertEquals(0, confirmedRm.exitCode)
        assertFalse(File(tempWorkspace, "notes/todo.txt").exists())

        val sudoRes = engine.executeCommand("sudo ls", sampleProject, sampleProject.rootPath) as TerminalCommandOutcome.Completed
        assertEquals(126, sudoRes.exitCode)
    }

    // ==================== PART 8: TESTING LAB TESTS ====================

    @Test
    fun `testing lab validates valid project and honestly skips unavailable runners`() = runBlocking {
        val lab = TestingLabEngine(context)
        lab.clearTestHistory()

        val report = lab.runProjectValidationSuite(
            sampleProject,
            setOf("Structure", "Manifest", "Resources", "Dependencies", "Gradle Build Validation", "Unit Tests", "Instrumented Tests")
        )

        assertTrue(report.passedCount >= 5)
        assertEquals(0, report.failedCount)
        assertEquals(2, report.skippedCount)
        assertTrue(report.results.any { it.category == "Unit Tests" && it.status == TestResultStatus.SKIPPED })
        assertTrue(report.results.any { it.category == "Instrumented Tests" && it.status == TestResultStatus.SKIPPED })
        assertEquals(1, lab.loadTestHistory().size)

        val formatted = lab.formatReadableReport(report)
        assertTrue(formatted.contains("# Nexa IDE Testing Lab Report"))
        assertTrue(formatted.contains("TestApp"))
    }

    @Test
    fun `testing lab detects broken manifest and parses gradle errors`() = runBlocking {
        val lab = TestingLabEngine(context)
        File(tempWorkspace, "app/src/main/AndroidManifest.xml").writeText("<manifest><application></manifest>")

        val report = lab.runProjectValidationSuite(sampleProject, setOf("Manifest"))
        assertEquals(1, report.failedCount)
        assertEquals(TestResultStatus.FAILED, report.results.first().status)

        val sampleGradleErr = """
            > Task :app:compileDebugKotlin FAILED
            e: file:///app/src/main/java/com/example/MainActivity.kt:42:5 Unresolved reference 'missingFn'.
            FAILURE: Build failed with an exception.
        """.trimIndent()
        val parsed = lab.parseGradleBuildErrors(sampleGradleErr)
        assertTrue(parsed.any { it.fileName == "MainActivity.kt" && it.lineNumber == 42 })
    }

    @Test
    fun `testing lab inspects real zip archive apk structure`() = runBlocking {
        val lab = TestingLabEngine(context)
        val fakeApk = File(tempWorkspace, "sample_test.apk")
        ZipOutputStream(FileOutputStream(fakeApk)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("binary".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libnative.so"))
            zos.write(byteArrayOf(4, 5))
            zos.closeEntry()
        }

        val analysis = lab.analyzeApkFile(fakeApk)
        assertTrue(analysis.isSuccess)
        val info = analysis.getOrThrow()
        assertTrue(info.hasAndroidManifest)
        assertEquals(1, info.dexFileCount)
        assertTrue(info.nativeAbis.contains("arm64-v8a"))

        val missingRes = lab.analyzeApkFile(File(tempWorkspace, "nonexistent.apk"))
        assertTrue(missingRes.isFailure)
    }

    // ==================== PART 9: ASSET STUDIO TESTS ====================

    @Test
    fun `asset studio crops resizes generates mipmaps and exports splash assets`() {
        val src = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.CYAN)

        val cropped = AssetStudioProcessor.cropCenterAspect(src, 1, 1)
        assertEquals(200, cropped.width)
        assertEquals(200, cropped.height)

        val resized = AssetStudioProcessor.resizeBitmap(cropped, 128, 128)
        assertEquals(128, resized.width)
        assertEquals(128, resized.height)

        val pngBytes = AssetStudioProcessor.encodeBitmap(resized, ImageExportFormat.PNG)
        assertTrue(pngBytes.isNotEmpty())

        val svgBmp = AssetStudioProcessor.renderBasicSvgToBitmap(
            """<svg viewBox="0 0 100 100"><rect x="10" y="10" width="80" height="80" fill="#00D2FF"/></svg>""",
            256,
            256
        )
        assertEquals(256, svgBmp.width)

        val iconFiles = AssetStudioProcessor.exportAndroidLauncherIcons(
            projectRoot = tempWorkspace,
            backgroundColorInt = Color.BLACK,
            foregroundColorInt = Color.CYAN,
            shape = IconShapeOption.SQUIRCLE,
            monogramText = "NX",
            foregroundBitmap = null
        )
        assertEquals(10, iconFiles.size) // 5 densities * (ic_launcher.png + ic_launcher_round.png)
        assertTrue(File(tempWorkspace, "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png").exists())

        val splashFiles = AssetStudioProcessor.exportSplashAssets(
            projectRoot = tempWorkspace,
            bgColorHex = "#0F172A",
            titleText = "Nexa Demo",
            textColorHex = "#00D2FF",
            logoBitmap = null
        )
        assertEquals(2, splashFiles.size)
        assertTrue(File(tempWorkspace, "app/src/main/res/drawable/splash_logo.png").exists())
        val colorsXml = File(tempWorkspace, "app/src/main/res/values/splash_colors.xml").readText()
        assertTrue(colorsXml.contains("Nexa Demo"))

        val listed = AssetStudioProcessor.listProjectVisualAssets(tempWorkspace)
        assertTrue(listed.size >= 11)
    }

    // ==================== PART 10: GITHUB ACCOUNT MANAGER TESTS ====================

    @Test
    fun `github helper classifies tokens evaluates scopes and keystore cleans up`() {
        assertEquals("Fine-grained PAT", GitHubAccountHelper.classifyTokenFormat("github_pat_123456"))
        assertEquals("Classic Personal Access Token", GitHubAccountHelper.classifyTokenFormat("ghp_123456"))
        assertEquals("OAuth Access Token", GitHubAccountHelper.classifyTokenFormat("gho_123456"))

        val fakeUser = GitHubUser(login = "nexadev", id = 42L, avatarUrl = null, name = "Nexa Dev", email = null, publicRepos = 5)

        val validClassic = GitHubAccountHelper.evaluateUserResponse(200, fakeUser, "repo, workflow", "4999", rawToken = "ghp_abc")
        assertEquals(TokenValidationStatus.VALID, validClassic.status)
        assertTrue(validClassic.scopes.contains("workflow"))

        val missingScope = GitHubAccountHelper.evaluateUserResponse(200, fakeUser, "repo", "4999", rawToken = "ghp_abc")
        assertEquals(TokenValidationStatus.INSUFFICIENT_SCOPE, missingScope.status)
        assertTrue(missingScope.missingRecommendedScopes.contains("workflow"))

        val expired = GitHubAccountHelper.evaluateUserResponse(401, null, null, "0", "Bad credentials", rawToken = "ghp_abc")
        assertEquals(TokenValidationStatus.INVALID_OR_EXPIRED, expired.status)

        val rateLimited = GitHubAccountHelper.evaluateUserResponse(403, null, null, "0", "API rate limit exceeded", rawToken = "ghp_abc")
        assertEquals(TokenValidationStatus.RATE_LIMITED, rateLimited.status)

        // Secure Keystore storage verification
        SecureTokenManager.saveGitHubToken(context, "ghp_TestSecretToken99999")
        assertEquals("ghp_TestSecretToken99999", SecureTokenManager.getGitHubToken(context))
        assertFalse(SecureTokenManager.maskToken("ghp_TestSecretToken99999").contains("TestSecret"))
        SecureTokenManager.clearGitHubToken(context)
        assertNull(SecureTokenManager.getGitHubToken(context))
    }
}

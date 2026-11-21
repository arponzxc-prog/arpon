package com.example

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.assets.AssetStudioProcessor
import com.example.data.filesystem.HtmlAppGenerator
import com.example.data.filesystem.HtmlBuildConfig
import com.example.data.filesystem.HtmlIconShape
import com.example.data.filesystem.HtmlMixedContentPolicy
import com.example.data.filesystem.HtmlOrientation
import com.example.data.filesystem.HtmlPermission
import com.example.data.filesystem.ProjectFileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Step1HtmlBuilderTest {

    private lateinit var context: Context
    private lateinit var fileManager: ProjectFileManager
    private val placeholderRegex = Regex("__[A-Z0-9_]+__")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileManager = ProjectFileManager(context)
    }

    @Test
    fun `package name and identity validators reject invalid inputs and keywords`() {
        assertNull(HtmlBuildConfig.validateAppName("Shop & Go's"))
        assertNotNull(HtmlBuildConfig.validateAppName("   "))
        assertNotNull(HtmlBuildConfig.validateAppName("A".repeat(41)))

        assertNull(HtmlBuildConfig.validatePackageName("com.nexa.shop_app1"))
        assertNotNull(HtmlBuildConfig.validatePackageName("singleword"))
        assertNotNull(HtmlBuildConfig.validatePackageName("com..broken"))
        assertNotNull(HtmlBuildConfig.validatePackageName("com.123bad.app"))
        assertNotNull(HtmlBuildConfig.validatePackageName("com.MyApp.test"))
        assertNotNull(HtmlBuildConfig.validatePackageName("com.class.app"))
        assertNotNull(HtmlBuildConfig.validatePackageName("com.return.app"))

        assertNull(HtmlBuildConfig.validateVersionName("1.2.3"))
        assertNotNull(HtmlBuildConfig.validateVersionName(""))
        assertNull(HtmlBuildConfig.validateVersionCode(5))
        assertNotNull(HtmlBuildConfig.validateVersionCode(0))

        assertNull(HtmlBuildConfig.validateRemoteUrl("https://example.org/app"))
        assertNotNull(HtmlBuildConfig.validateRemoteUrl("ftp://invalid.example"))
    }

    @Test
    fun `xml and kotlin string escaping handles special characters safely`() {
        val escapedXml = HtmlBuildConfig.escapeXmlText("""R&D <App> "Pro" '26""")
        assertEquals("""R&amp;D &lt;App&gt; &quot;Pro&quot; \'26""", escapedXml)

        val cfg = HtmlBuildConfig(
            appName = """Shop & "Go" <1>'s""",
            packageName = "com.example.shopgo",
            splashTitle = """Welcome & "Hi" <All>"""
        )
        val stringsXml = HtmlAppGenerator.generateStringsXml(cfg)
        assertTrue(stringsXml.contains("Shop &amp; &quot;Go&quot; &lt;1&gt;\\'s"))
        assertTrue(stringsXml.contains("Welcome &amp; &quot;Hi&quot; &lt;All&gt;"))

        val escapedKt = HtmlBuildConfig.escapeKotlinString("""App $1 "Quote" \Path""")
        assertTrue(escapedKt.contains("""\$1"""))
        assertTrue(escapedKt.contains("""\"Quote\""""))
        assertTrue(escapedKt.contains("""\\Path"""))
    }

    @Test
    fun `config json serialization roundtrip preserves all step 1 fields`() {
        val original = HtmlBuildConfig(
            appName = "My Portal",
            packageName = "com.portal.mobile",
            versionName = "2.4.1",
            versionCode = 14,
            autoIncrementVersionCode = true,
            orientation = HtmlOrientation.PORTRAIT,
            fullscreen = true,
            statusBarColorHex = "#0284C7",
            remoteUrlMode = true,
            remoteUrl = "https://portal.example.com",
            pullToRefresh = false,
            offlineFallbackEnabled = true,
            enableJs = true,
            enableDom = true,
            enableZoom = true,
            showSplash = false,
            splashTitle = "Portal Boot",
            splashBgColorHex = "#7C3AED",
            iconBgColorHex = "#059669",
            iconShape = HtmlIconShape.CIRCLE,
            enableAdaptiveIcon = true,
            enableMonochromeIcon = false,
            mixedContentPolicy = HtmlMixedContentPolicy.NEVER,
            allowCleartextTraffic = true,
            enableJsBridge = true,
            permissions = setOf(
                HtmlPermission.INTERNET,
                HtmlPermission.CAMERA,
                HtmlPermission.LOCATION,
                HtmlPermission.VIBRATE
            )
        )

        val json = original.toJsonString()
        val restored = HtmlBuildConfig.fromJsonString(json, "Fallback", "com.fallback.app")
        assertEquals(original, restored)
    }

    @Test
    fun `generator leaves zero unreplaced placeholders across permutations`() {
        val configs = listOf(
            HtmlBuildConfig(
                appName = "Local App",
                packageName = "com.local.app",
                remoteUrlMode = false,
                fullscreen = false,
                pullToRefresh = true,
                offlineFallbackEnabled = true,
                showSplash = true,
                enableJsBridge = true,
                enableMonochromeIcon = true
            ),
            HtmlBuildConfig(
                appName = "Remote Kiosk",
                packageName = "com.remote.kiosk",
                orientation = HtmlOrientation.LANDSCAPE,
                fullscreen = true,
                remoteUrlMode = true,
                remoteUrl = "https://kiosk.example.com",
                pullToRefresh = false,
                offlineFallbackEnabled = false,
                showSplash = false,
                enableJsBridge = false,
                enableMonochromeIcon = false,
                allowCleartextTraffic = true,
                mixedContentPolicy = HtmlMixedContentPolicy.ALWAYS,
                permissions = HtmlPermission.entries.toSet()
            )
        )

        configs.forEach { cfg ->
            val outputs = listOf(
                HtmlAppGenerator.generateStringsXml(cfg),
                HtmlAppGenerator.generateColorsXml(cfg),
                HtmlAppGenerator.generateThemesXml(cfg),
                HtmlAppGenerator.generateManifestXml(cfg),
                HtmlAppGenerator.generateIcLauncherBackgroundXml(cfg.iconBgColorHex),
                HtmlAppGenerator.generateAdaptiveIconXml(cfg.enableMonochromeIcon),
                HtmlAppGenerator.generateLayoutXml(cfg),
                HtmlAppGenerator.generateAppBuildGradle(cfg),
                HtmlAppGenerator.generateMainActivityKt(cfg)
            )
            outputs.forEach { content ->
                val leftover = placeholderRegex.find(content)?.value
                assertNull("Unreplaced placeholder found: $leftover", leftover)
            }
        }
    }

    @Test
    fun `manifest and main activity honor permissions, asset loader security, and lazy prompt deny`() {
        val cfg = HtmlBuildConfig(
            appName = "Cam & Geo",
            packageName = "com.camgeo.app",
            orientation = HtmlOrientation.PORTRAIT,
            fullscreen = true,
            remoteUrlMode = false,
            mixedContentPolicy = HtmlMixedContentPolicy.NEVER,
            allowCleartextTraffic = false,
            enableJsBridge = true,
            permissions = setOf(HtmlPermission.CAMERA, HtmlPermission.LOCATION, HtmlPermission.VIBRATE)
        )

        val manifest = HtmlAppGenerator.generateManifestXml(cfg)
        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("android.hardware.camera"))
        assertTrue(manifest.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(manifest.contains("android.permission.VIBRATE"))
        assertFalse(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:screenOrientation=\"portrait\""))
        assertTrue(manifest.contains("configChanges=\"orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize\""))

        val activityKt = HtmlAppGenerator.generateMainActivityKt(cfg)
        assertTrue(activityKt.contains("WebViewAssetLoader.Builder()"))
        assertTrue(activityKt.contains("https://appassets.androidplatform.net/assets/index.html"))
        assertTrue(activityKt.contains("settings.allowFileAccess = false"))
        assertTrue(activityKt.contains("settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW"))
        assertTrue(activityKt.contains("request.deny()"))
        assertTrue(activityKt.contains("callback.invoke(safeOrigin, false, false)"))
        assertTrue(activityKt.contains("addJavascriptInterface(NexaJsBridge(), \"NexaApp\")"))
    }

    @Test
    fun `all newly created project templates include generated default launcher icons`() {
        val templates = listOf(
            "Kotlin Compose App" to "Kotlin",
            "Kotlin Android App" to "Kotlin",
            "Java Android App" to "Java",
            "HTML Single File Project" to "Webapp",
            "HTML Multi-File Project" to "Webapp",
            "Android WebView App" to "Kotlin"
        )
        templates.forEachIndexed { idx, (template, lang) ->
            val project = fileManager.createProject(
                name = "Template $idx",
                packageName = "com.example.tpl$idx",
                templateType = template,
                language = lang
            )
            val root = File(project.rootPath)
            try {
                assertTrue(
                    "Missing xhdpi ic_launcher.png for $template",
                    File(root, "app/src/main/res/mipmap-xhdpi/ic_launcher.png").exists()
                )
                assertTrue(
                    "Missing adaptive ic_launcher.xml for $template",
                    File(root, "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").exists()
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `invalid image uri fails atomically without modifying existing project files`() {
        val project = fileManager.createProject(
            name = "Atomic Webapp",
            packageName = "com.atomic.webapp",
            templateType = "HTML Single File Project",
            language = "Webapp"
        )
        val root = File(project.rootPath)
        try {
            val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
            val stringsFile = File(root, "app/src/main/res/values/strings.xml")
            val originalManifest = manifestFile.readText()
            val originalStrings = stringsFile.readText()

            val updatedCfg = HtmlBuildConfig(
                appName = "Should Not Apply",
                packageName = "com.should.notapply",
                versionName = "9.9.9",
                versionCode = 99
            )
            val badUri = Uri.parse("file:///nonexistent_nexa_image_12345.png")
            val result = fileManager.updateHtmlProjectConfig(
                project = project,
                config = updatedCfg,
                logoUri = badUri,
                splashUri = null
            )

            assertTrue("Expected failure on broken image URI", result.isFailure)
            assertEquals("Manifest must remain untouched", originalManifest, manifestFile.readText())
            assertEquals("strings.xml must remain untouched", originalStrings, stringsFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `valid update writes html config, adaptive monochrome icons, and gradle version`() {
        val project = fileManager.createProject(
            name = "Starter Web",
            packageName = "com.starter.web",
            templateType = "HTML Multi-File Project",
            language = "Webapp"
        )
        val root = File(project.rootPath)
        try {
            val cfg = HtmlBuildConfig(
                appName = "Updated Web",
                packageName = "com.updated.web",
                versionName = "3.1.0",
                versionCode = 7,
                enableAdaptiveIcon = true,
                enableMonochromeIcon = true,
                pullToRefresh = true,
                showSplash = true,
                splashTitle = "Booting..."
            )
            val res = fileManager.updateHtmlProjectConfig(project, cfg, logoUri = null, splashUri = null)
            assertTrue(res.isSuccess)
            val updated = res.getOrThrow()
            assertEquals("Updated Web", updated.name)
            assertEquals("com.updated.web", updated.packageName)

            val loaded = fileManager.loadHtmlBuildConfig(updated)
            assertEquals(7, loaded.versionCode)
            assertEquals("3.1.0", loaded.versionName)

            val adaptiveXml = File(root, "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
            assertTrue(adaptiveXml.contains("<monochrome"))

            val gradleKts = File(root, "app/build.gradle.kts").readText()
            assertTrue(gradleKts.contains("versionCode = 7"))
            assertTrue(gradleKts.contains("versionName = \"3.1.0\""))
            assertTrue(gradleKts.contains("swiperefreshlayout"))

            assertTrue(File(root, "app/src/main/java/com/updated/web/MainActivity.kt").exists())
            assertFalse(File(root, "app/src/main/java/com/starter/web/MainActivity.kt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `svg rasterization and exif orientation transform work as expected`() {
        val svg = """
            <svg viewBox="0 0 100 100">
                <rect x="0" y="0" width="100" height="100" fill="#0284C7" />
                <circle cx="50" cy="50" r="30" fill="#FFFFFF" />
            </svg>
        """.trimIndent()
        val rendered = AssetStudioProcessor.renderBasicSvgToBitmap(svg, 128, 128)
        assertEquals(128, rendered.width)
        assertEquals(128, rendered.height)

        val rectBmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val rotated90 = fileManager.applyExifOrientation(rectBmp, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(20, rotated90.width)
        assertEquals(40, rotated90.height)

        val rotated180 = fileManager.applyExifOrientation(rectBmp, ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(40, rotated180.width)
        assertEquals(20, rotated180.height)
    }
}

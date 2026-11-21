package com.example.data.filesystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import com.example.assets.AssetStudioProcessor
import com.example.assets.IconShapeOption
import com.example.assets.ImageExportFormat
import com.example.data.local.entity.ProjectEntity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val extension: String = "",
    val children: List<FileNode> = emptyList()
)

class ProjectFileManager(private val context: Context) {

    private val projectsRoot: File
        get() {
            val dir = File(context.filesDir, "projects")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val downloadsDir: File
        get() {
            val dir = File(context.filesDir, "downloads")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getProjectDirectory(projectId: String): File {
        val dir = File(projectsRoot, projectId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createProject(
        name: String,
        packageName: String,
        templateType: String,
        language: String
    ): ProjectEntity {
        val projectId = UUID.randomUUID().toString()
        val projectDir = getProjectDirectory(projectId)

        // Generate files based on template
        generateProjectStructure(projectDir, name, packageName, templateType, language)

        return ProjectEntity(
            id = projectId,
            name = name,
            packageName = packageName,
            templateType = templateType,
            rootPath = projectDir.absolutePath,
            language = language,
            createdAt = System.currentTimeMillis(),
            lastModifiedAt = System.currentTimeMillis()
        )
    }

    private fun generateProjectStructure(
        root: File,
        appName: String,
        packageName: String,
        templateType: String,
        language: String
    ) {
        val packagePath = packageName.replace('.', '/')
        val isKotlin = language.equals("Kotlin", ignoreCase = true) || language.equals("Webapp", ignoreCase = true)
        val ext = if (isKotlin) "kt" else "java"

        // 1. root settings.gradle.kts
        File(root, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "$appName"
            include(":app")
            """.trimIndent()
        )

        // 2. root build.gradle.kts
        File(root, "build.gradle.kts").writeText(
            """
            // Top-level build file for $appName
            plugins {
                id("com.android.application") version "8.7.3" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
            }
            """.trimIndent()
        )

        // 3. gradle.properties
        File(root, "gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
            android.nonTransitiveRClass=true
            kotlin.code.style=official
            """.trimIndent()
        )

        // 4. README.md
        File(root, "README.md").writeText(
            """
            # $appName
            
            Built with Nexa IDE - Mobile Android IDE & Cloud APK Builder.
            
            - **Package**: `$packageName`
            - **Template**: `$templateType`
            - **Language**: `$language`
            
            ### Cloud Build with GitHub Actions
            Push this repository to GitHub or trigger the workflow under `.github/workflows/android-build.yml` to automatically compile a Debug or Release APK.
            """.trimIndent()
        )

        // 5. .github/workflows/android-build.yml (GitHub Actions workflow for building APK)
        val workflowDir = File(root, ".github/workflows")
        workflowDir.mkdirs()
        val workflowAssetContent = try {
            context.assets.open("workflows/android-build.yml").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            """
            name: Android CI Build
            on:
              workflow_dispatch:
                inputs:
                  build_type:
                    description: 'Build Type (debug, release, bundle)'
                    required: true
                    default: 'debug'
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - name: Set up Java JDK 17
                    uses: actions/setup-java@v4
                    with:
                      java-version: '17'
                      distribution: 'temurin'
                  - name: Build with Gradle
                    run: ./gradlew assembleDebug
            """.trimIndent()
        }
        File(workflowDir, "android-build.yml").writeText(workflowAssetContent)

        // 6. app module directories
        val appDir = File(root, "app")
        val srcMain = File(appDir, "src/main")
        val codeDir = File(srcMain, if (isKotlin) "java/$packagePath" else "java/$packagePath")
        val resDir = File(srcMain, "res")
        val valuesDir = File(resDir, "values")
        val layoutDir = File(resDir, "layout")
        val assetsDir = File(srcMain, "assets")

        codeDir.mkdirs()
        valuesDir.mkdirs()
        layoutDir.mkdirs()
        if (templateType.contains("HTML", ignoreCase = true)) {
            assetsDir.mkdirs()
            if (templateType == "HTML Single File Project") {
                File(assetsDir, "style.css").delete()
                File(assetsDir, "script.js").delete()
                File(assetsDir, "index.html").writeText(
                    """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>$appName</title>
                        <style>
                            body {
                                font-family: system-ui, -apple-system, sans-serif;
                                background: #0f172a;
                                color: #f8fafc;
                                display: flex;
                                flex-direction: column;
                                align-items: center;
                                justify-content: center;
                                height: 100vh;
                                margin: 0;
                                text-align: center;
                                padding: 20px;
                            }
                            .card {
                                background: #1e293b;
                                padding: 30px;
                                border-radius: 16px;
                                box-shadow: 0 10px 25px rgba(0,0,0,0.3);
                                max-width: 400px;
                                width: 100%;
                                border: 1px solid #334155;
                            }
                            h1 { color: #38bdf8; margin-bottom: 10px; font-size: 24px; }
                            p { color: #94a3b8; font-size: 15px; line-height: 1.5; }
                            button {
                                background: #38bdf8;
                                color: #0f172a;
                                border: none;
                                padding: 12px 24px;
                                font-size: 16px;
                                border-radius: 8px;
                                cursor: pointer;
                                margin-top: 15px;
                                font-weight: 700;
                            }
                            button:active { background: #0ea5e9; }
                            #msg { margin-top: 15px; font-weight: bold; color: #4ade80; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h1>$appName</h1>
                            <p>Single File HTML + CSS + JS App created with Nexa IDE!</p>
                            <button onclick="onBtnClick()">Tap Me</button>
                            <p id="msg"></p>
                        </div>

                        <script>
                            function onBtnClick() {
                                document.getElementById('msg').innerText = 'Hello from JavaScript! Single-file app working successfully.';
                            }
                        </script>
                    </body>
                    </html>
                    """.trimIndent()
                )
            } else if (templateType == "HTML Multi-File Project") {
                File(assetsDir, "index.html").writeText(
                    """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>$appName</title>
                        <link rel="stylesheet" href="style.css">
                    </head>
                    <body>
                        <div class="card">
                            <h1>$appName</h1>
                            <p>Multi-File HTML Project (index.html, style.css, script.js)</p>
                            <button id="actionBtn">Click Me</button>
                            <p id="outputMsg"></p>
                        </div>
                        <script src="script.js"></script>
                    </body>
                    </html>
                    """.trimIndent()
                )
                File(assetsDir, "style.css").writeText(
                    """
                    body {
                        font-family: system-ui, -apple-system, sans-serif;
                        background: #0f172a;
                        color: #f8fafc;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        height: 100vh;
                        margin: 0;
                        text-align: center;
                        padding: 20px;
                    }
                    .card {
                        background: #1e293b;
                        padding: 30px;
                        border-radius: 16px;
                        box-shadow: 0 10px 25px rgba(0,0,0,0.3);
                        max-width: 400px;
                        width: 100%;
                        border: 1px solid #334155;
                    }
                    h1 { color: #38bdf8; margin-bottom: 10px; font-size: 24px; }
                    p { color: #94a3b8; font-size: 15px; line-height: 1.5; }
                    button {
                        background: #38bdf8;
                        color: #0f172a;
                        border: none;
                        padding: 12px 24px;
                        font-size: 16px;
                        border-radius: 8px;
                        cursor: pointer;
                        margin-top: 15px;
                        font-weight: 700;
                    }
                    button:active { background: #0ea5e9; }
                    #outputMsg { margin-top: 15px; font-weight: bold; color: #4ade80; }
                    """.trimIndent()
                )
                File(assetsDir, "script.js").writeText(
                    """
                    document.addEventListener('DOMContentLoaded', () => {
                        const btn = document.getElementById('actionBtn');
                        const msg = document.getElementById('outputMsg');
                        if (btn) {
                            btn.addEventListener('click', () => {
                                msg.innerText = 'Success! script.js connected with style.css & index.html.';
                            });
                        }
                    });
                    """.trimIndent()
                )
            }
        }

        // 7. app/build.gradle.kts
        val isCompose = templateType.contains("Compose") || templateType.contains("Kotlin")
        val composePlugin = if (isCompose) "\n    id(\"org.jetbrains.kotlin.plugin.compose\")" else ""
        val composeBuildFeature = if (isCompose) "\n        buildFeatures {\n            compose = true\n        }" else ""

        File(appDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                ${if (isKotlin) "id(\"org.jetbrains.kotlin.android\")" else ""}$composePlugin
            }

            android {
                namespace = "$packageName"
                compileSdk = 35

                defaultConfig {
                    applicationId = "$packageName"
                    minSdk = 24
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }

                buildTypes {
                    release {
                        isMinifyEnabled = false
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }$composeBuildFeature
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("com.google.android.material:material:1.12.0")
                implementation("androidx.constraintlayout:constraintlayout:2.2.0")
                ${if (templateType.contains("WebView") || templateType.contains("HTML", ignoreCase = true)) "implementation(\"androidx.webkit:webkit:1.12.1\")" else ""}
                ${if (isCompose) """
                implementation(platform("androidx.compose:compose-bom:2024.10.01"))
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.ui:ui-graphics")
                implementation("androidx.compose.ui:ui-tooling-preview")
                implementation("androidx.compose.material3:material3")
                implementation("androidx.activity:activity-compose:1.9.3")
                """.trimIndent() else ""}
            }
            """.trimIndent()
        )

        // Empty proguard-rules.pro
        File(appDir, "proguard-rules.pro").writeText("# Proguard rules\n")

        // 8. AndroidManifest.xml
        File(srcMain, "AndroidManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                ${if (templateType.contains("WebView") || templateType.contains("HTML", ignoreCase = true)) "<uses-permission android:name=\"android.permission.INTERNET\" />" else ""}

                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:label="@string/app_name"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.App">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>

            </manifest>
            """.trimIndent()
        )

        // 9. res/values/strings.xml
        File(valuesDir, "strings.xml").writeText(
            """
            <resources>
                <string name="app_name">$appName</string>
                <string name="welcome_message">Welcome to $appName created with Nexa IDE!</string>
            </resources>
            """.trimIndent()
        )

        // 10. res/values/colors.xml
        File(valuesDir, "colors.xml").writeText(
            """
            <resources>
                <color name="purple_500">#FF6200EE</color>
                <color name="purple_700">#FF3700B3</color>
                <color name="teal_200">#FF03DAC5</color>
                <color name="teal_700">#FF018786</color>
                <color name="black">#FF000000</color>
                <color name="white">#FFFFFFFF</color>
            </resources>
            """.trimIndent()
        )

        // 11. res/values/themes.xml
        File(valuesDir, "themes.xml").writeText(
            """
            <resources>
                <style name="Theme.App" parent="Theme.MaterialComponents.DayNight.DarkActionBar">
                    <item name="colorPrimary">@color/purple_500</item>
                    <item name="colorPrimaryVariant">@color/purple_700</item>
                    <item name="colorOnPrimary">@color/white</item>
                </style>
            </resources>
            """.trimIndent()
        )

        // 12. MainActivity code
        val activityFile = File(codeDir, "MainActivity.$ext")
        when {
            templateType.contains("WebView") || templateType.contains("HTML", ignoreCase = true) -> {
                val loadUrl = if (templateType.contains("HTML", ignoreCase = true)) "file:///android_asset/index.html" else "https://developer.android.com"
                if (isKotlin) {
                    activityFile.writeText(
                        """
                        package $packageName

                        import android.annotation.SuppressLint
                        import android.os.Bundle
                        import android.webkit.WebView
                        import android.webkit.WebViewClient
                        import androidx.appcompat.app.AppCompatActivity

                        class MainActivity : AppCompatActivity() {
                            private lateinit var webView: WebView

                            @SuppressLint("SetJavaScriptEnabled")
                            override fun onCreate(savedInstanceState: Bundle?) {
                                super.onCreate(savedInstanceState)
                                setContentView(R.layout.activity_main)

                                webView = findViewById(R.id.webView)
                                webView.settings.javaScriptEnabled = true
                                webView.settings.domStorageEnabled = true
                                webView.webViewClient = WebViewClient()
                                webView.loadUrl("$loadUrl")
                            }

                            override fun onBackPressed() {
                                if (webView.canGoBack()) {
                                    webView.goBack()
                                } else {
                                    super.onBackPressed()
                                }
                            }
                        }
                        """.trimIndent()
                    )
                } else {
                    activityFile.writeText(
                        """
                        package $packageName;

                        import android.annotation.SuppressLint;
                        import android.os.Bundle;
                        import android.webkit.WebView;
                        import android.webkit.WebViewClient;
                        import androidx.appcompat.app.AppCompatActivity;

                        public class MainActivity extends AppCompatActivity {
                            private WebView webView;

                            @SuppressLint("SetJavaScriptEnabled")
                            @Override
                            protected void onCreate(Bundle savedInstanceState) {
                                super.onCreate(savedInstanceState);
                                setContentView(R.layout.activity_main);

                                webView = findViewById(R.id.webView);
                                webView.getSettings().setJavaScriptEnabled(true);
                                webView.getSettings().setDomStorageEnabled(true);
                                webView.setWebViewClient(new WebViewClient());
                                webView.loadUrl("$loadUrl");
                            }

                            @Override
                            public void onBackPressed() {
                                if (webView != null && webView.canGoBack()) {
                                    webView.goBack();
                                } else {
                                    super.onBackPressed();
                                }
                            }
                        }
                        """.trimIndent()
                    )
                }
                // layout for webview
                File(layoutDir, "activity_main.xml").writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        xmlns:app="http://schemas.android.com/apk/res-auto"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">

                        <WebView
                            android:id="@+id/webView"
                            android:layout_width="0dp"
                            android:layout_height="0dp"
                            app:layout_constraintBottom_toBottomOf="parent"
                            app:layout_constraintEnd_toEndOf="parent"
                            app:layout_constraintStart_toStartOf="parent"
                            app:layout_constraintTop_toTopOf="parent" />

                    </androidx.constraintlayout.widget.ConstraintLayout>
                    """.trimIndent()
                )
            }
            isKotlin -> {
                activityFile.writeText(
                    """
                    package $packageName

                    import android.os.Bundle
                    import android.widget.TextView
                    import androidx.appcompat.app.AppCompatActivity

                    class MainActivity : AppCompatActivity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContentView(R.layout.activity_main)

                            val tvWelcome = findViewById<TextView>(R.id.tvWelcome)
                            tvWelcome.text = getString(R.string.welcome_message)
                        }
                    }
                    """.trimIndent()
                )
                File(layoutDir, "activity_main.xml").writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        xmlns:app="http://schemas.android.com/apk/res-auto"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">

                        <TextView
                            android:id="@+id/tvWelcome"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/welcome_message"
                            android:textSize="18sp"
                            app:layout_constraintBottom_toBottomOf="parent"
                            app:layout_constraintEnd_toEndOf="parent"
                            app:layout_constraintStart_toStartOf="parent"
                            app:layout_constraintTop_toTopOf="parent" />

                    </androidx.constraintlayout.widget.ConstraintLayout>
                    """.trimIndent()
                )
            }
            else -> {
                activityFile.writeText(
                    """
                    package $packageName;

                    import android.os.Bundle;
                    import android.widget.TextView;
                    import androidx.appcompat.app.AppCompatActivity;

                    public class MainActivity extends AppCompatActivity {
                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            setContentView(R.layout.activity_main);

                            TextView tvWelcome = findViewById(R.id.tvWelcome);
                            tvWelcome.setText(R.string.welcome_message);
                        }
                    }
                    """.trimIndent()
                )
                File(layoutDir, "activity_main.xml").writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        xmlns:app="http://schemas.android.com/apk/res-auto"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">

                        <TextView
                            android:id="@+id/tvWelcome"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/welcome_message"
                            android:textSize="18sp"
                            app:layout_constraintBottom_toBottomOf="parent"
                            app:layout_constraintEnd_toEndOf="parent"
                            app:layout_constraintStart_toStartOf="parent"
                            app:layout_constraintTop_toTopOf="parent" />

                    </androidx.constraintlayout.widget.ConstraintLayout>
                    """.trimIndent()
                )
            }
        }

        // 13. Always generate default launcher & adaptive icons so AAPT builds never fail on missing @mipmap/ic_launcher
        generateDefaultLauncherIcons(root, appName)
    }

    private fun generateDefaultLauncherIcons(
        projectRoot: File,
        appName: String,
        bgColorHex: String = "#0F172A",
        shape: IconShapeOption = IconShapeOption.SQUIRCLE,
        enableAdaptiveIcon: Boolean = true,
        enableMonochromeIcon: Boolean = true,
        foregroundBitmap: Bitmap? = null
    ) {
        val bgColorInt = try { Color.parseColor(bgColorHex) } catch (_: Exception) { Color.parseColor("#0F172A") }
        val fgColorInt = Color.parseColor("#38BDF8")
        val monogram = appName.trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .take(2)
            .ifBlank { "NX" }

        AssetStudioProcessor.exportAndroidLauncherIcons(
            projectRoot = projectRoot,
            backgroundColorInt = bgColorInt,
            foregroundColorInt = fgColorInt,
            shape = shape,
            monogramText = monogram,
            foregroundBitmap = foregroundBitmap
        )

        val anydpiDir = File(projectRoot, "app/src/main/res/mipmap-anydpi-v26")
        val drawableDir = File(projectRoot, "app/src/main/res/drawable").apply { mkdirs() }

        if (enableAdaptiveIcon) {
            anydpiDir.mkdirs()

            // Adaptive icon foreground (432x432 transparent canvas with safe-zone inset)
            val fgCanvasBmp = AssetStudioProcessor.generateLauncherIconBitmap(
                sizePx = 432,
                backgroundColorInt = Color.TRANSPARENT,
                foregroundColorInt = fgColorInt,
                shape = IconShapeOption.SQUARE,
                monogramText = monogram,
                foregroundBitmap = foregroundBitmap,
                paddingRatio = 0.22f
            )
            FileOutputStream(File(drawableDir, "ic_launcher_foreground.png")).use {
                it.write(AssetStudioProcessor.encodeBitmap(fgCanvasBmp, ImageExportFormat.PNG))
            }

            File(drawableDir, "ic_launcher_background.xml").writeText(
                HtmlAppGenerator.generateIcLauncherBackgroundXml(bgColorHex)
            )

            if (enableMonochromeIcon) {
                val monoBmp = createMonochromeBitmap(fgCanvasBmp)
                FileOutputStream(File(drawableDir, "ic_launcher_monochrome.png")).use {
                    it.write(AssetStudioProcessor.encodeBitmap(monoBmp, ImageExportFormat.PNG))
                }
            }

            val adaptiveXml = HtmlAppGenerator.generateAdaptiveIconXml(enableMonochromeIcon)
            File(anydpiDir, "ic_launcher.xml").writeText(adaptiveXml)
            File(anydpiDir, "ic_launcher_round.xml").writeText(adaptiveXml)
        } else {
            File(anydpiDir, "ic_launcher.xml").delete()
            File(anydpiDir, "ic_launcher_round.xml").delete()
        }
    }

    private fun createMonochromeBitmap(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    fun getWebappDirectory(rootDir: File): File {
        val assetsDir = File(rootDir, "app/src/main/assets")
        if (!assetsDir.exists()) assetsDir.mkdirs()
        return assetsDir
    }

    fun getWebappFileTree(rootDir: File): List<FileNode> {
        val webDir = getWebappDirectory(rootDir)
        return getFileTree(webDir)
    }

    fun getFileTree(directory: File): List<FileNode> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        val files = directory.listFiles() ?: return emptyList()

        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).map { file ->
            if (file.isDirectory) {
                FileNode(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = true,
                    sizeBytes = getFolderSize(file),
                    children = getFileTree(file)
                )
            } else {
                FileNode(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = false,
                    sizeBytes = file.length(),
                    extension = file.extension.lowercase()
                )
            }
        }
    }

    fun readFileContent(filePath: String): String {
        val file = File(filePath)
        return if (file.exists() && file.isFile) file.readText() else ""
    }

    fun writeFileContent(filePath: String, content: String): Boolean {
        return try {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun createFile(parentPath: String, fileName: String): File? {
        return try {
            val file = File(parentPath, fileName)
            file.parentFile?.mkdirs()
            if (file.createNewFile()) file else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun createDirectory(parentPath: String, dirName: String): File? {
        return try {
            val dir = File(parentPath, dirName)
            if (dir.mkdirs()) dir else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun renameFile(path: String, newName: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        val dest = File(file.parentFile, newName)
        return file.renameTo(dest)
    }

    fun deleteFile(path: String): Boolean {
        val file = File(path)
        return file.deleteRecursively()
    }

    fun duplicateProject(sourceProject: ProjectEntity, newName: String): ProjectEntity? {
        return try {
            val newId = UUID.randomUUID().toString()
            val newDir = getProjectDirectory(newId)
            val sourceDir = File(sourceProject.rootPath)
            if (sourceDir.exists()) {
                sourceDir.copyRecursively(newDir, overwrite = true)
            }
            ProjectEntity(
                id = newId,
                name = newName,
                packageName = sourceProject.packageName,
                templateType = sourceProject.templateType,
                rootPath = newDir.absolutePath,
                language = sourceProject.language,
                createdAt = System.currentTimeMillis(),
                lastModifiedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun exportProjectToZip(project: ProjectEntity): File? {
        return try {
            val sourceDir = File(project.rootPath)
            if (!sourceDir.exists()) return null

            val zipFile = File(downloadsDir, "${project.name.replace(' ', '_')}_source.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                sourceDir.walkTopDown().forEach { file ->
                    val relativePath = file.relativeTo(sourceDir).path
                    if (relativePath.isNotEmpty()) {
                        if (file.isDirectory) {
                            zos.putNextEntry(ZipEntry("$relativePath/"))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(relativePath))
                            FileInputStream(file).use { fis ->
                                fis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }
            zipFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importProjectFromZip(inputStream: InputStream, projectName: String): ProjectEntity? {
        val projectId = UUID.randomUUID().toString()
        val projectDir = getProjectDirectory(projectId)
        return try {
            val canonicalRootDir = projectDir.canonicalFile
            val maxFiles = 500
            val maxTotalSize = 50 * 1024 * 1024L // 50 MB
            val maxEntrySize = 15 * 1024 * 1024L // 15 MB
            var totalExtractedSize = 0L
            var fileCount = 0

            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    fileCount++
                    if (fileCount > maxFiles) {
                        throw SecurityException("ZIP import error: Exceeded maximum file count ($maxFiles)")
                    }

                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                        throw SecurityException("ZIP Slip path traversal detected: $name")
                    }

                    val file = File(projectDir, name)
                    val canonicalFile = file.canonicalFile
                    if (!canonicalFile.path.startsWith(canonicalRootDir.path + File.separator) && canonicalFile != canonicalRootDir) {
                        throw SecurityException("ZIP Slip vulnerability detected: $name")
                    }

                    val relativePath = canonicalFile.path.substring(canonicalRootDir.path.length)
                    val depth = relativePath.split(File.separator).size
                    if (depth > 12) {
                        throw SecurityException("ZIP import error: Exceeded maximum directory depth")
                    }

                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
                            var bytesRead = 0L
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                bytesRead += read
                                totalExtractedSize += read
                                if (bytesRead > maxEntrySize || totalExtractedSize > maxTotalSize) {
                                    throw SecurityException("ZIP import error: Exceeded maximum file or total size limit")
                                }
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Auto-flatten single top-level folder if all files were nested inside one root directory
            val subFiles = projectDir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            if (subFiles.size == 1 && subFiles[0].isDirectory) {
                val singleSubDir = subFiles[0]
                val innerFiles = singleSubDir.listFiles() ?: emptyArray()
                for (inner in innerFiles) {
                    val dest = File(projectDir, inner.name)
                    inner.renameTo(dest)
                }
                singleSubDir.delete()
            }

            ProjectEntity(
                id = projectId,
                name = projectName,
                packageName = "com.imported.${projectName.lowercase().replace(" ", "").replace(Regex("[^a-z0-9]"), "")}",
                templateType = "Imported Project",
                rootPath = projectDir.absolutePath,
                language = "Kotlin",
                createdAt = System.currentTimeMillis(),
                lastModifiedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            projectDir.deleteRecursively()
            null
        }
    }

    fun loadHtmlBuildConfig(project: ProjectEntity): HtmlBuildConfig {
        val configFile = File(project.rootPath, HtmlBuildConfig.CONFIG_FILE_NAME)
        return if (configFile.exists()) {
            HtmlBuildConfig.fromJsonString(
                raw = configFile.readText(),
                fallbackAppName = project.name,
                fallbackPackage = project.packageName
            )
        } else {
            HtmlBuildConfig(
                appName = project.name,
                packageName = project.packageName
            )
        }
    }

    fun saveHtmlBuildConfig(project: ProjectEntity, config: HtmlBuildConfig) {
        val root = File(project.rootPath)
        if (!root.exists()) root.mkdirs()
        File(root, HtmlBuildConfig.CONFIG_FILE_NAME).writeText(config.toJsonString())
    }

    fun decodeBitmapWithExifAndSvg(uri: Uri): Result<Bitmap> {
        return try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalStateException("Could not open selected image."))
            if (rawBytes.size > AssetStudioProcessor.MAX_IMPORT_BYTES) {
                return Result.failure(IllegalArgumentException("Image exceeds 15 MB limit."))
            }
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            val headText = rawBytes.take(256).toByteArray().decodeToString()
            if (mimeType.contains("svg", ignoreCase = true) ||
                uri.toString().endsWith(".svg", ignoreCase = true) ||
                headText.contains("<svg", ignoreCase = true)
            ) {
                val rendered = AssetStudioProcessor.renderBasicSvgToBitmap(rawBytes.decodeToString(), 512, 512)
                return Result.success(rendered)
            }

            val orientation = try {
                ByteArrayInputStream(rawBytes).use { bis ->
                    ExifInterface(bis).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return Result.failure(IllegalArgumentException("Unsupported or corrupted image file."))

            Result.success(applyExifOrientation(decoded, orientation))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    /**
     * Validates config and decodes URIs BEFORE mutating any project files on disk (atomic failure protection).
     */
    fun updateHtmlProjectConfig(
        project: ProjectEntity,
        config: HtmlBuildConfig,
        logoUri: Uri? = null,
        splashUri: Uri? = null
    ): Result<ProjectEntity> {
        HtmlBuildConfig.validateAppName(config.appName)?.let { return Result.failure(IllegalArgumentException(it)) }
        HtmlBuildConfig.validatePackageName(config.packageName)?.let { return Result.failure(IllegalArgumentException(it)) }
        HtmlBuildConfig.validateVersionName(config.versionName)?.let { return Result.failure(IllegalArgumentException(it)) }
        HtmlBuildConfig.validateVersionCode(config.versionCode)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (config.remoteUrlMode) {
            HtmlBuildConfig.validateRemoteUrl(config.remoteUrl)?.let { return Result.failure(IllegalArgumentException(it)) }
        }

        val decodedLogo: Bitmap? = if (logoUri != null) {
            val res = decodeBitmapWithExifAndSvg(logoUri)
            if (res.isFailure) return Result.failure(res.exceptionOrNull()!!)
            res.getOrNull()
        } else null

        val decodedSplash: Bitmap? = if (splashUri != null) {
            val res = decodeBitmapWithExifAndSvg(splashUri)
            if (res.isFailure) return Result.failure(res.exceptionOrNull()!!)
            res.getOrNull()
        } else null

        return try {
            val updated = updateHtmlProjectConfig(project, config, decodedLogo, decodedSplash)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun updateHtmlProjectConfig(
        project: ProjectEntity,
        config: HtmlBuildConfig,
        logoBitmap: Bitmap? = null,
        splashBitmap: Bitmap? = null
    ): ProjectEntity {
        val root = File(project.rootPath)
        val packageName = config.packageName.trim()
        val appName = config.appName.trim()
        val packagePath = packageName.replace('.', '/')
        val appDir = File(root, "app")
        val srcMain = File(appDir, "src/main")
        val codeDir = File(srcMain, "java/$packagePath")
        val layoutDir = File(srcMain, "res/layout")
        val valuesDir = File(srcMain, "res/values")

        val oldCodeDir = File(srcMain, "java/${project.packageName.replace('.', '/')}")
        if (oldCodeDir.exists() && oldCodeDir != codeDir) {
            oldCodeDir.deleteRecursively()
        }

        codeDir.mkdirs()
        layoutDir.mkdirs()
        valuesDir.mkdirs()

        // 1. settings.gradle.kts
        File(root, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "${HtmlBuildConfig.escapeKotlinString(appName)}"
            include(":app")
            """.trimIndent()
        )

        // 2. AndroidManifest.xml
        File(srcMain, "AndroidManifest.xml").writeText(HtmlAppGenerator.generateManifestXml(config))

        // 3. Resources (strings, colors, themes, layout)
        File(valuesDir, "strings.xml").writeText(HtmlAppGenerator.generateStringsXml(config))
        File(valuesDir, "colors.xml").writeText(HtmlAppGenerator.generateColorsXml(config))
        File(valuesDir, "themes.xml").writeText(HtmlAppGenerator.generateThemesXml(config))
        File(layoutDir, "activity_main.xml").writeText(HtmlAppGenerator.generateLayoutXml(config))

        // 4. app/build.gradle.kts
        File(appDir, "build.gradle.kts").writeText(HtmlAppGenerator.generateAppBuildGradle(config))

        // 5. MainActivity.kt
        File(codeDir, "MainActivity.kt").writeText(HtmlAppGenerator.generateMainActivityKt(config))

        // 6. Launcher & Adaptive/Monochrome Icons
        val iconShape = when (config.iconShape) {
            HtmlIconShape.CIRCLE -> IconShapeOption.CIRCLE
            HtmlIconShape.SQUIRCLE -> IconShapeOption.SQUIRCLE
        }
        generateDefaultLauncherIcons(
            projectRoot = root,
            appName = appName,
            bgColorHex = config.iconBgColorHex,
            shape = iconShape,
            enableAdaptiveIcon = config.enableAdaptiveIcon,
            enableMonochromeIcon = config.enableMonochromeIcon,
            foregroundBitmap = logoBitmap
        )

        // 7. Splash Image
        AssetStudioProcessor.exportSplashAssets(
            projectRoot = root,
            bgColorHex = config.splashBgColorHex,
            titleText = config.splashTitle.ifBlank { appName },
            textColorHex = "#F8FAFC",
            logoBitmap = splashBitmap ?: logoBitmap
        )

        // 8. Persist user configuration for future builds
        val updatedEntity = project.copy(
            name = appName,
            packageName = packageName,
            lastModifiedAt = System.currentTimeMillis()
        )
        saveHtmlBuildConfig(updatedEntity, config)
        return updatedEntity
    }

    fun updateHtmlProjectConfig(
        project: ProjectEntity,
        appName: String,
        packageName: String,
        enableJs: Boolean,
        enableDom: Boolean,
        enableZoom: Boolean,
        showSplash: Boolean,
        splashTitle: String
    ): ProjectEntity {
        val base = loadHtmlBuildConfig(project)
        val merged = base.copy(
            appName = appName.trim(),
            packageName = packageName.trim(),
            enableJs = enableJs,
            enableDom = enableDom,
            enableZoom = enableZoom,
            showSplash = showSplash,
            splashTitle = splashTitle.trim().ifBlank { appName.trim() }
        )
        return updateHtmlProjectConfig(project, merged, logoBitmap = null, splashBitmap = null)
    }

    fun getTotalStorageUsageBytes(): Long {
        return getFolderSize(projectsRoot) + getFolderSize(downloadsDir)
    }

    fun getFolderSize(folder: File): Long {
        if (!folder.exists()) return 0L
        return folder.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
}

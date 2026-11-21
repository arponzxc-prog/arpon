package com.example.data.filesystem

/**
 * Pure Kotlin generator for HTML/WebView Android projects.
 * Uses __PLACEHOLDER__ substitution (never '$' interpolation inside code templates)
 * so this class can be thoroughly tested on the JVM without Android runtime dependencies.
 */
object HtmlAppGenerator {

    fun generateStringsXml(config: HtmlBuildConfig): String {
        val escapedName = HtmlBuildConfig.escapeXmlText(config.appName.trim())
        val splashText = HtmlBuildConfig.escapeXmlText(config.splashTitle.trim().ifBlank { config.appName.trim() })
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<resources>",
            "    <string name=\"app_name\">$escapedName</string>",
            "    <string name=\"splash_title\">$splashText</string>",
            "    <string name=\"welcome_message\">Welcome to $escapedName</string>",
            "    <string name=\"offline_title\">No Internet Connection</string>",
            "    <string name=\"offline_subtitle\">Please check your network connection and try again.</string>",
            "    <string name=\"offline_retry\">Retry</string>",
            "</resources>"
        ).joinToString("\n")
    }

    fun generateColorsXml(config: HtmlBuildConfig): String {
        val statusColor = HtmlBuildConfig.normalizeHexColor(config.statusBarColorHex)
        val splashBg = HtmlBuildConfig.normalizeHexColor(config.splashBgColorHex)
        val iconBg = HtmlBuildConfig.normalizeHexColor(config.iconBgColorHex)
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<resources>",
            "    <color name=\"status_bar_color\">$statusColor</color>",
            "    <color name=\"splash_background\">$splashBg</color>",
            "    <color name=\"icon_background\">$iconBg</color>",
            "    <color name=\"purple_500\">#FF6200EE</color>",
            "    <color name=\"purple_700\">#FF3700B3</color>",
            "    <color name=\"teal_200\">#FF03DAC5</color>",
            "    <color name=\"white\">#FFFFFFFF</color>",
            "    <color name=\"black\">#FF000000</color>",
            "</resources>"
        ).joinToString("\n")
    }

    fun generateThemesXml(config: HtmlBuildConfig): String {
        val fullscreenLine = if (config.fullscreen) {
            "        <item name=\"android:windowFullscreen\">true</item>\n"
        } else {
            ""
        }
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<resources>",
            "    <style name=\"Theme.App\" parent=\"Theme.MaterialComponents.DayNight.NoActionBar\">",
            "        <item name=\"colorPrimary\">@color/status_bar_color</item>",
            "        <item name=\"colorPrimaryDark\">@color/status_bar_color</item>",
            "        <item name=\"android:statusBarColor\">@color/status_bar_color</item>",
            fullscreenLine.trimEnd(),
            "    </style>",
            "</resources>"
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun generateManifestXml(config: HtmlBuildConfig): String {
        val allPerms = linkedSetOf<String>()
        if (config.remoteUrlMode || config.permissions.contains(HtmlPermission.INTERNET)) {
            allPerms.addAll(HtmlPermission.INTERNET.manifestPermissions)
        }
        config.permissions.forEach { perm ->
            allPerms.addAll(perm.manifestPermissions)
        }

        val permTags = allPerms.joinToString("\n") { permName ->
            val featureTag = when (permName) {
                "android.permission.CAMERA" -> "\n    <uses-feature android:name=\"android.hardware.camera\" android:required=\"false\" />"
                else -> ""
            }
            "    <uses-permission android:name=\"$permName\" />$featureTag"
        }

        val cleartextAttr = if (config.allowCleartextTraffic) "true" else "false"
        val orientationAttr = config.orientation.manifestValue

        val template = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">

            __PERMISSIONS__

                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:label="@string/app_name"
                    android:supportsRtl="true"
                    android:usesCleartextTraffic="__CLEARTEXT__"
                    android:theme="@style/Theme.App">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:screenOrientation="__ORIENTATION__"
                        android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>

            </manifest>
        """.trimIndent()

        return template
            .replace("__PERMISSIONS__", permTags)
            .replace("__CLEARTEXT__", cleartextAttr)
            .replace("__ORIENTATION__", orientationAttr)
    }

    fun generateIcLauncherBackgroundXml(hexColor: String): String {
        val color = HtmlBuildConfig.normalizeHexColor(hexColor)
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">",
            "    <solid android:color=\"$color\" />",
            "</shape>"
        ).joinToString("\n")
    }

    fun generateAdaptiveIconXml(includeMonochrome: Boolean): String {
        val monochromeTag = if (includeMonochrome) {
            "\n    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />"
        } else {
            ""
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@drawable/ic_launcher_background" />
                <foreground android:drawable="@drawable/ic_launcher_foreground" />__MONOCHROME__
            </adaptive-icon>
        """.trimIndent().replace("__MONOCHROME__", monochromeTag)
    }

    fun generateLayoutXml(config: HtmlBuildConfig): String {
        val webViewTag = if (config.pullToRefresh) {
            """
                <androidx.swiperefreshlayout.widget.SwipeRefreshLayout
                    android:id="@+id/swipeRefresh"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent">

                    <WebView
                        android:id="@+id/webView"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent" />

                </androidx.swiperefreshlayout.widget.SwipeRefreshLayout>
            """.trimIndent()
        } else {
            """
                <WebView
                    android:id="@+id/webView"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />
            """.trimIndent()
        }

        val offlineBlock = if (config.offlineFallbackEnabled) {
            """
                <LinearLayout
                    android:id="@+id/offlineView"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:background="#0F172A"
                    android:gravity="center"
                    android:orientation="vertical"
                    android:padding="28dp"
                    android:visibility="gone">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/offline_title"
                        android:textColor="#38BDF8"
                        android:textSize="22sp"
                        android:textStyle="bold"
                        android:gravity="center" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/offline_subtitle"
                        android:textColor="#CBD5E1"
                        android:textSize="14sp"
                        android:gravity="center"
                        android:layout_marginTop="10dp" />

                    <Button
                        android:id="@+id/btnRetry"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/offline_retry"
                        android:layout_marginTop="20dp" />
                </LinearLayout>
            """.trimIndent()
        } else ""

        val splashBlock = if (config.showSplash) {
            val splashBg = HtmlBuildConfig.normalizeHexColor(config.splashBgColorHex)
            """
                <LinearLayout
                    android:id="@+id/splashView"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:background="$splashBg"
                    android:gravity="center"
                    android:orientation="vertical"
                    android:padding="24dp">

                    <ImageView
                        android:id="@+id/splashLogo"
                        android:layout_width="112dp"
                        android:layout_height="112dp"
                        android:contentDescription="@string/app_name"
                        android:scaleType="fitCenter"
                        android:src="@drawable/splash_logo" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:gravity="center"
                        android:text="@string/splash_title"
                        android:textColor="#F8FAFC"
                        android:textSize="24sp"
                        android:textStyle="bold" />
                </LinearLayout>
            """.trimIndent()
        } else ""

        return """
            <?xml version="1.0" encoding="utf-8"?>
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">

            __WEBVIEW_BLOCK__

            __OFFLINE_BLOCK__

            __SPLASH_BLOCK__

            </FrameLayout>
        """.trimIndent()
            .replace("__WEBVIEW_BLOCK__", webViewTag)
            .replace("__OFFLINE_BLOCK__", offlineBlock)
            .replace("__SPLASH_BLOCK__", splashBlock)
    }

    fun generateAppBuildGradle(config: HtmlBuildConfig): String {
        val pkg = config.packageName.trim()
        val vCode = config.versionCode.coerceAtLeast(1)
        val vName = HtmlBuildConfig.escapeKotlinString(config.versionName.trim().ifBlank { "1.0.0" })
        val swipeDep = if (config.pullToRefresh) {
            "\n    implementation(\"androidx.swiperefreshlayout:swiperefreshlayout:1.1.0\")"
        } else ""

        val template = """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "__PACKAGE__"
                compileSdk = 35

                defaultConfig {
                    applicationId = "__PACKAGE__"
                    minSdk = 24
                    targetSdk = 35
                    versionCode = __VERSION_CODE__
                    versionName = "__VERSION_NAME__"
                }

                signingConfigs {
                    create("release") {
                        val ksPath = System.getenv("KEYSTORE_PATH") ?: "${'$'}{rootDir}/my-upload-key.jks"
                        val ksFile = file(ksPath)
                        if (ksFile.exists()) {
                            storeFile = ksFile
                            storePassword = System.getenv("STORE_PASSWORD") ?: "android"
                            keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
                            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
                        }
                    }
                }

                buildTypes {
                    release {
                        val enableMinify = project.findProperty("nexaMinify")?.toString()?.toBoolean() ?: false
                        isMinifyEnabled = enableMinify
                        isShrinkResources = enableMinify
                        signingConfig = signingConfigs.getByName("release")
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("com.google.android.material:material:1.12.0")
                implementation("androidx.constraintlayout:constraintlayout:2.2.0")
                implementation("androidx.activity:activity-ktx:1.10.0")
                implementation("androidx.webkit:webkit:1.12.1")__SWIPE_DEP__
            }
        """.trimIndent()

        return template
            .replace("__PACKAGE__", pkg)
            .replace("__VERSION_CODE__", vCode.toString())
            .replace("__VERSION_NAME__", vName)
            .replace("__SWIPE_DEP__", swipeDep)
    }

    fun generateMainActivityKt(config: HtmlBuildConfig): String {
        val pkg = config.packageName.trim()
        val targetUrl = if (config.remoteUrlMode) {
            HtmlBuildConfig.escapeKotlinString(config.remoteUrl.trim())
        } else {
            "https://appassets.androidplatform.net/assets/index.html"
        }
        val appNameEscaped = HtmlBuildConfig.escapeKotlinString(config.appName.trim())
        val versionNameEscaped = HtmlBuildConfig.escapeKotlinString(config.versionName.trim())
        val statusColorHex = HtmlBuildConfig.normalizeHexColor(config.statusBarColorHex)

        val allowCamera = config.permissions.contains(HtmlPermission.CAMERA)
        val allowMic = config.permissions.contains(HtmlPermission.MICROPHONE)
        val allowLocation = config.permissions.contains(HtmlPermission.LOCATION)
        val allowFileUpload = config.permissions.contains(HtmlPermission.FILE_UPLOAD)
        val allowVibrate = config.permissions.contains(HtmlPermission.VIBRATE)

        val swipeImport = if (config.pullToRefresh) {
            "import androidx.swiperefreshlayout.widget.SwipeRefreshLayout"
        } else ""

        val swipeSetup = if (config.pullToRefresh) {
            """
            swipeRefresh = findViewById(R.id.swipeRefresh)
            swipeRefresh?.setOnRefreshListener {
                webView.reload()
            }
            """.trimIndent()
        } else ""

        val swipeStop = if (config.pullToRefresh) {
            "swipeRefresh?.isRefreshing = false"
        } else ""

        val swipeField = if (config.pullToRefresh) {
            "private var swipeRefresh: SwipeRefreshLayout? = null"
        } else ""

        val offlineField = if (config.offlineFallbackEnabled) {
            "private var offlineView: View? = null\n    private var hasMainFrameError: Boolean = false"
        } else ""

        val offlineSetup = if (config.offlineFallbackEnabled) {
            """
            offlineView = findViewById(R.id.offlineView)
            findViewById<Button>(R.id.btnRetry)?.setOnClickListener {
                hasMainFrameError = false
                offlineView?.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.reload()
            }
            """.trimIndent()
        } else ""

        val offlineErrorHandler = if (config.offlineFallbackEnabled) {
            """
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    hasMainFrameError = true
                    offlineView?.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                    __SWIPE_STOP__
                }
            }
            """.trimIndent().replace("__SWIPE_STOP__", swipeStop)
        } else ""

        val offlineFinishReset = if (config.offlineFallbackEnabled) {
            """
            if (!hasMainFrameError) {
                offlineView?.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }
            """.trimIndent()
        } else ""

        val splashSetup = if (config.showSplash) {
            """
            val splashView = findViewById<View>(R.id.splashView)
            if (splashView != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    splashView.animate().alpha(0f).setDuration(350).withEndAction {
                        splashView.visibility = View.GONE
                    }.start()
                }, 1000)
            }
            """.trimIndent()
        } else ""

        val fullscreenSetup = if (config.fullscreen) {
            """
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            """.trimIndent()
        } else {
            """
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.parseColor("__STATUS_COLOR__")
            """.trimIndent().replace("__STATUS_COLOR__", statusColorHex)
        }

        val jsBridgeClass = if (config.enableJsBridge) {
            """
            inner class NexaJsBridge {
                @JavascriptInterface
                fun toast(message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }

                @JavascriptInterface
                fun share(text: String) {
                    runOnUiThread {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        startActivity(Intent.createChooser(intent, "Share"))
                    }
                }

                @JavascriptInterface
                fun vibrate(durationMs: Long): String {
                    if (!__ALLOW_VIBRATE__) {
                        return "denied: VIBRATE permission is disabled"
                    }
                    val safeMs = durationMs.coerceIn(10L, 2000L)
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val mgr = getSystemService(VibratorManager::class.java)
                        mgr?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(VIBRATOR_SERVICE) as? Vibrator
                    }
                    if (vibrator != null && vibrator.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(safeMs, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(safeMs)
                        }
                        return "ok"
                    }
                    return "unavailable"
                }

                @JavascriptInterface
                fun openExternal(url: String) {
                    val trimmed = url.trim()
                    if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                        runOnUiThread {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)))
                            } catch (_: Exception) { }
                        }
                    }
                }

                @JavascriptInterface
                fun getDeviceInfo(): String {
                    val json = JSONObject()
                    json.put("brand", Build.BRAND)
                    json.put("model", Build.MODEL)
                    json.put("sdkInt", Build.VERSION.SDK_INT)
                    json.put("appName", "__APP_NAME__")
                    json.put("versionName", "__VERSION_NAME__")
                    return json.toString()
                }
            }
            """.trimIndent()
                .replace("__ALLOW_VIBRATE__", allowVibrate.toString())
                .replace("__APP_NAME__", appNameEscaped)
                .replace("__VERSION_NAME__", versionNameEscaped)
        } else ""

        val jsBridgeAttach = if (config.enableJsBridge && config.enableJs) {
            "webView.addJavascriptInterface(NexaJsBridge(), \"NexaApp\")"
        } else ""

        val template = """
            package __PACKAGE__

            import android.Manifest
            import android.annotation.SuppressLint
            import android.app.Activity
            import android.content.Intent
            import android.content.pm.PackageManager
            import android.graphics.Color
            import android.net.Uri
            import android.os.Build
            import android.os.Bundle
            import android.os.Handler
            import android.os.Looper
            import android.os.VibrationEffect
            import android.os.Vibrator
            import android.os.VibratorManager
            import android.view.View
            import android.webkit.GeolocationPermissions
            import android.webkit.JavascriptInterface
            import android.webkit.PermissionRequest
            import android.webkit.ValueCallback
            import android.webkit.WebChromeClient
            import android.webkit.WebResourceError
            import android.webkit.WebResourceRequest
            import android.webkit.WebResourceResponse
            import android.webkit.WebSettings
            import android.webkit.WebView
            import android.webkit.WebViewClient
            import android.widget.Button
            import android.widget.Toast
            import androidx.activity.OnBackPressedCallback
            import androidx.activity.result.contract.ActivityResultContracts
            import androidx.appcompat.app.AppCompatActivity
            import androidx.core.content.ContextCompat
            import androidx.core.view.WindowCompat
            import androidx.core.view.WindowInsetsCompat
            import androidx.core.view.WindowInsetsControllerCompat
            import androidx.webkit.WebViewAssetLoader
            __SWIPE_IMPORT__
            import org.json.JSONObject

            class MainActivity : AppCompatActivity() {

                private lateinit var webView: WebView
                __SWIPE_FIELD__
                __OFFLINE_FIELD__

                private var pendingWebPermissionRequest: PermissionRequest? = null
                private var pendingGeoOrigin: String? = null
                private var pendingGeoCallback: GeolocationPermissions.Callback? = null
                private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

                private val permissionLauncher = registerForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    pendingWebPermissionRequest?.let { req ->
                        val allGranted = results.values.isNotEmpty() && results.values.all { it }
                        if (allGranted) {
                            req.grant(req.resources)
                        } else {
                            req.deny()
                        }
                        pendingWebPermissionRequest = null
                    }
                    pendingGeoCallback?.let { cb ->
                        val origin = pendingGeoOrigin.orEmpty()
                        val locGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                        cb.invoke(origin, locGranted, false)
                        pendingGeoCallback = null
                        pendingGeoOrigin = null
                    }
                }

                private val fileChooserLauncher = registerForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val callback = pendingFileCallback
                    pendingFileCallback = null
                    if (callback != null) {
                        val uris = if (result.resultCode == Activity.RESULT_OK) {
                            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                        } else {
                            null
                        }
                        callback.onReceiveValue(uris)
                    }
                }

                @SuppressLint("SetJavaScriptEnabled")
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)

                    __FULLSCREEN_SETUP__

                    webView = findViewById(R.id.webView)
                    __SWIPE_SETUP__
                    __OFFLINE_SETUP__

                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                        .build()

                    val settings = webView.settings
                    settings.javaScriptEnabled = __ENABLE_JS__
                    settings.domStorageEnabled = __ENABLE_DOM__
                    settings.builtInZoomControls = __ENABLE_ZOOM__
                    settings.displayZoomControls = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = __MIXED_CONTENT__

                    __JS_BRIDGE_ATTACH__

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url ?: return null
                            if ("appassets.androidplatform.net" == url.host) {
                                return assetLoader.shouldInterceptRequest(url)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            __SWIPE_STOP__
                            __OFFLINE_FINISH_RESET__
                        }

                        __OFFLINE_ERROR_HANDLER__
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            if (request == null) return
                            runOnUiThread {
                                val needed = mutableListOf<String>()
                                for (res in request.resources) {
                                    when (res) {
                                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                            if (!__ALLOW_CAMERA__) {
                                                request.deny()
                                                return@runOnUiThread
                                            }
                                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                                needed.add(Manifest.permission.CAMERA)
                                            }
                                        }
                                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                            if (!__ALLOW_MIC__) {
                                                request.deny()
                                                return@runOnUiThread
                                            }
                                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                                needed.add(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }
                                }
                                if (needed.isEmpty()) {
                                    request.grant(request.resources)
                                } else {
                                    pendingWebPermissionRequest?.deny()
                                    pendingWebPermissionRequest = request
                                    permissionLauncher.launch(needed.toTypedArray())
                                }
                            }
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: GeolocationPermissions.Callback?
                        ) {
                            if (callback == null) return
                            val safeOrigin = origin.orEmpty()
                            if (!__ALLOW_LOCATION__) {
                                callback.invoke(safeOrigin, false, false)
                                return
                            }
                            val hasFine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val hasCoarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (hasFine || hasCoarse) {
                                callback.invoke(safeOrigin, true, false)
                            } else {
                                pendingGeoOrigin = safeOrigin
                                pendingGeoCallback = callback
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            if (!__ALLOW_FILE_UPLOAD__) {
                                filePathCallback?.onReceiveValue(null)
                                return false
                            }
                            pendingFileCallback?.onReceiveValue(null)
                            pendingFileCallback = filePathCallback
                            return try {
                                val intent = fileChooserParams?.createIntent()
                                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                    }
                                fileChooserLauncher.launch(intent)
                                true
                            } catch (_: Exception) {
                                pendingFileCallback = null
                                false
                            }
                        }
                    }

                    webView.loadUrl("__TARGET_URL__")

                    __SPLASH_SETUP__

                    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            if (webView.canGoBack()) {
                                webView.goBack()
                            } else {
                                isEnabled = false
                                onBackPressedDispatcher.onBackPressed()
                            }
                        }
                    })
                }

                __JS_BRIDGE_CLASS__
            }
        """.trimIndent()

        return template
            .replace("__PACKAGE__", pkg)
            .replace("__SWIPE_IMPORT__", swipeImport)
            .replace("__SWIPE_FIELD__", swipeField)
            .replace("__OFFLINE_FIELD__", offlineField)
            .replace("__FULLSCREEN_SETUP__", fullscreenSetup)
            .replace("__SWIPE_SETUP__", swipeSetup)
            .replace("__OFFLINE_SETUP__", offlineSetup)
            .replace("__ENABLE_JS__", config.enableJs.toString())
            .replace("__ENABLE_DOM__", config.enableDom.toString())
            .replace("__ENABLE_ZOOM__", config.enableZoom.toString())
            .replace("__MIXED_CONTENT__", config.mixedContentPolicy.webSettingsConstant)
            .replace("__JS_BRIDGE_ATTACH__", jsBridgeAttach)
            .replace("__SWIPE_STOP__", swipeStop)
            .replace("__OFFLINE_FINISH_RESET__", offlineFinishReset)
            .replace("__OFFLINE_ERROR_HANDLER__", offlineErrorHandler)
            .replace("__ALLOW_CAMERA__", allowCamera.toString())
            .replace("__ALLOW_MIC__", allowMic.toString())
            .replace("__ALLOW_LOCATION__", allowLocation.toString())
            .replace("__ALLOW_FILE_UPLOAD__", allowFileUpload.toString())
            .replace("__TARGET_URL__", targetUrl)
            .replace("__SPLASH_SETUP__", splashSetup)
            .replace("__JS_BRIDGE_CLASS__", jsBridgeClass)
    }
}

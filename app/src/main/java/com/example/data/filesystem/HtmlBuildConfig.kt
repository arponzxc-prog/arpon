package com.example.data.filesystem

import org.json.JSONArray
import org.json.JSONObject

enum class HtmlPermission(
    val id: String,
    val labelEn: String,
    val labelBn: String,
    val descriptionEn: String,
    val manifestPermissions: List<String>,
    val runtimePermissions: List<String>,
    val isDangerous: Boolean
) {
    INTERNET(
        id = "INTERNET",
        labelEn = "Internet Access",
        labelBn = "ইন্টারনেট অ্যাক্সেস",
        descriptionEn = "Required for remote URLs, APIs, CDNs, and online content.",
        manifestPermissions = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"),
        runtimePermissions = emptyList(),
        isDangerous = false
    ),
    CAMERA(
        id = "CAMERA",
        labelEn = "Camera",
        labelBn = "ক্যামেরা",
        descriptionEn = "Allow web pages to access device camera (lazy runtime prompt).",
        manifestPermissions = listOf("android.permission.CAMERA"),
        runtimePermissions = listOf("android.permission.CAMERA"),
        isDangerous = true
    ),
    MICROPHONE(
        id = "MICROPHONE",
        labelEn = "Microphone / Audio",
        labelBn = "মাইক্রোফোন / অডিও",
        descriptionEn = "Allow web pages to record audio via microphone (lazy runtime prompt).",
        manifestPermissions = listOf("android.permission.RECORD_AUDIO", "android.permission.MODIFY_AUDIO_SETTINGS"),
        runtimePermissions = listOf("android.permission.RECORD_AUDIO"),
        isDangerous = true
    ),
    LOCATION(
        id = "LOCATION",
        labelEn = "Location (GPS)",
        labelBn = "লোকেশন (GPS)",
        descriptionEn = "Allow Geolocation API requests from web pages (lazy runtime prompt).",
        manifestPermissions = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
        runtimePermissions = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
        isDangerous = true
    ),
    FILE_UPLOAD(
        id = "FILE_UPLOAD",
        labelEn = "File & Photo Picker",
        labelBn = "ফাইল ও ছবি আপলোড",
        descriptionEn = "Enable <input type=\"file\"> chooser using system Android picker.",
        manifestPermissions = emptyList(),
        runtimePermissions = emptyList(),
        isDangerous = false
    ),
    VIBRATE(
        id = "VIBRATE",
        labelEn = "Vibration / Haptics",
        labelBn = "ভাইব্রেশন",
        descriptionEn = "Allow JS Bridge window.NexaApp.vibrate() and haptic feedback.",
        manifestPermissions = listOf("android.permission.VIBRATE"),
        runtimePermissions = emptyList(),
        isDangerous = false
    ),
    NOTIFICATIONS(
        id = "NOTIFICATIONS",
        labelEn = "Notifications (Android 13+)",
        labelBn = "নোটিফিকেশন",
        descriptionEn = "Declare POST_NOTIFICATIONS permission on Android 13+.",
        manifestPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
        runtimePermissions = listOf("android.permission.POST_NOTIFICATIONS"),
        isDangerous = true
    )
}

enum class HtmlOrientation(val id: String, val manifestValue: String, val labelEn: String, val labelBn: String) {
    AUTO("AUTO", "unspecified", "Auto", "স্বয়ংক্রিয়"),
    PORTRAIT("PORTRAIT", "portrait", "Portrait", "পোর্ট্রেট"),
    LANDSCAPE("LANDSCAPE", "landscape", "Landscape", "ল্যান্ডস্কেপ")
}

enum class HtmlIconShape(val id: String, val labelEn: String) {
    SQUIRCLE("SQUIRCLE", "Squircle"),
    CIRCLE("CIRCLE", "Circle")
}

enum class HtmlMixedContentPolicy(val id: String, val webSettingsConstant: String, val labelEn: String) {
    NEVER("NEVER", "WebSettings.MIXED_CONTENT_NEVER_ALLOW", "Strict HTTPS Only"),
    COMPATIBILITY("COMPATIBILITY", "WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE", "Compatibility"),
    ALWAYS("ALWAYS", "WebSettings.MIXED_CONTENT_ALWAYS_ALLOW", "Allow All")
}

data class HtmlBuildConfig(
    val appName: String,
    val packageName: String,
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val autoIncrementVersionCode: Boolean = false,
    val orientation: HtmlOrientation = HtmlOrientation.AUTO,
    val fullscreen: Boolean = false,
    val statusBarColorHex: String = "#0F172A",
    val remoteUrlMode: Boolean = false,
    val remoteUrl: String = "https://example.com",
    val pullToRefresh: Boolean = true,
    val offlineFallbackEnabled: Boolean = true,
    val enableJs: Boolean = true,
    val enableDom: Boolean = true,
    val enableZoom: Boolean = false,
    val showSplash: Boolean = true,
    val splashTitle: String = appName,
    val splashBgColorHex: String = "#0F172A",
    val iconBgColorHex: String = "#0F172A",
    val iconShape: HtmlIconShape = HtmlIconShape.SQUIRCLE,
    val enableAdaptiveIcon: Boolean = true,
    val enableMonochromeIcon: Boolean = true,
    val mixedContentPolicy: HtmlMixedContentPolicy = HtmlMixedContentPolicy.COMPATIBILITY,
    val allowCleartextTraffic: Boolean = false,
    val enableJsBridge: Boolean = true,
    val permissions: Set<HtmlPermission> = setOf(HtmlPermission.INTERNET, HtmlPermission.FILE_UPLOAD)
) {
    fun toJsonString(): String {
        val obj = JSONObject()
        obj.put("appName", appName)
        obj.put("packageName", packageName)
        obj.put("versionName", versionName)
        obj.put("versionCode", versionCode)
        obj.put("autoIncrementVersionCode", autoIncrementVersionCode)
        obj.put("orientation", orientation.id)
        obj.put("fullscreen", fullscreen)
        obj.put("statusBarColorHex", statusBarColorHex)
        obj.put("remoteUrlMode", remoteUrlMode)
        obj.put("remoteUrl", remoteUrl)
        obj.put("pullToRefresh", pullToRefresh)
        obj.put("offlineFallbackEnabled", offlineFallbackEnabled)
        obj.put("enableJs", enableJs)
        obj.put("enableDom", enableDom)
        obj.put("enableZoom", enableZoom)
        obj.put("showSplash", showSplash)
        obj.put("splashTitle", splashTitle)
        obj.put("splashBgColorHex", splashBgColorHex)
        obj.put("iconBgColorHex", iconBgColorHex)
        obj.put("iconShape", iconShape.id)
        obj.put("enableAdaptiveIcon", enableAdaptiveIcon)
        obj.put("enableMonochromeIcon", enableMonochromeIcon)
        obj.put("mixedContentPolicy", mixedContentPolicy.id)
        obj.put("allowCleartextTraffic", allowCleartextTraffic)
        obj.put("enableJsBridge", enableJsBridge)
        val permsArr = JSONArray()
        permissions.forEach { permsArr.put(it.id) }
        obj.put("permissions", permsArr)
        return obj.toString(2)
    }

    companion object {
        const val CONFIG_FILE_NAME = "nexa_html_config.json"

        private val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null"
        )

        private val SEGMENT_REGEX = Regex("^[a-z][a-z0-9_]*$")
        private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

        fun fromJsonString(raw: String, fallbackAppName: String, fallbackPackage: String): HtmlBuildConfig {
            return try {
                val obj = JSONObject(raw)
                val permsArr = obj.optJSONArray("permissions")
                val perms = mutableSetOf<HtmlPermission>()
                if (permsArr != null) {
                    for (i in 0 until permsArr.length()) {
                        val id = permsArr.optString(i)
                        HtmlPermission.entries.find { it.id == id }?.let { perms.add(it) }
                    }
                } else {
                    perms.add(HtmlPermission.INTERNET)
                    perms.add(HtmlPermission.FILE_UPLOAD)
                }

                val orientationId = obj.optString("orientation", HtmlOrientation.AUTO.id)
                val iconShapeId = obj.optString("iconShape", HtmlIconShape.SQUIRCLE.id)
                val mixedId = obj.optString("mixedContentPolicy", HtmlMixedContentPolicy.COMPATIBILITY.id)

                HtmlBuildConfig(
                    appName = obj.optString("appName", fallbackAppName).ifBlank { fallbackAppName },
                    packageName = obj.optString("packageName", fallbackPackage).ifBlank { fallbackPackage },
                    versionName = obj.optString("versionName", "1.0.0").ifBlank { "1.0.0" },
                    versionCode = obj.optInt("versionCode", 1).coerceAtLeast(1),
                    autoIncrementVersionCode = obj.optBoolean("autoIncrementVersionCode", false),
                    orientation = HtmlOrientation.entries.find { it.id == orientationId } ?: HtmlOrientation.AUTO,
                    fullscreen = obj.optBoolean("fullscreen", false),
                    statusBarColorHex = normalizeHexColor(obj.optString("statusBarColorHex", "#0F172A")),
                    remoteUrlMode = obj.optBoolean("remoteUrlMode", false),
                    remoteUrl = obj.optString("remoteUrl", "https://example.com"),
                    pullToRefresh = obj.optBoolean("pullToRefresh", true),
                    offlineFallbackEnabled = obj.optBoolean("offlineFallbackEnabled", true),
                    enableJs = obj.optBoolean("enableJs", true),
                    enableDom = obj.optBoolean("enableDom", true),
                    enableZoom = obj.optBoolean("enableZoom", false),
                    showSplash = obj.optBoolean("showSplash", true),
                    splashTitle = obj.optString("splashTitle", fallbackAppName),
                    splashBgColorHex = normalizeHexColor(obj.optString("splashBgColorHex", "#0F172A")),
                    iconBgColorHex = normalizeHexColor(obj.optString("iconBgColorHex", "#0F172A")),
                    iconShape = HtmlIconShape.entries.find { it.id == iconShapeId } ?: HtmlIconShape.SQUIRCLE,
                    enableAdaptiveIcon = obj.optBoolean("enableAdaptiveIcon", true),
                    enableMonochromeIcon = obj.optBoolean("enableMonochromeIcon", true),
                    mixedContentPolicy = HtmlMixedContentPolicy.entries.find { it.id == mixedId } ?: HtmlMixedContentPolicy.COMPATIBILITY,
                    allowCleartextTraffic = obj.optBoolean("allowCleartextTraffic", false),
                    enableJsBridge = obj.optBoolean("enableJsBridge", true),
                    permissions = perms
                )
            } catch (_: Exception) {
                HtmlBuildConfig(appName = fallbackAppName, packageName = fallbackPackage)
            }
        }

        fun normalizeHexColor(raw: String?, defaultHex: String = "#0F172A"): String {
            val cleaned = raw.orEmpty().trim().uppercase()
            return if (HEX_COLOR_REGEX.matches(cleaned)) cleaned else defaultHex
        }

        fun validateAppName(name: String): String? {
            val trimmed = name.trim()
            return when {
                trimmed.isBlank() -> "App name cannot be empty."
                trimmed.length > 40 -> "App name must be 40 characters or fewer."
                else -> null
            }
        }

        fun validatePackageName(pkg: String): String? {
            val trimmed = pkg.trim()
            if (trimmed.isBlank()) return "Package name cannot be empty."
            if (trimmed.contains("..")) return "Package name cannot contain consecutive dots."
            val segments = trimmed.split(".")
            if (segments.size < 2) return "Package name must have at least 2 segments (e.g. com.example.app)."
            for (seg in segments) {
                if (seg.isEmpty()) return "Package segments cannot be empty."
                if (!SEGMENT_REGEX.matches(seg)) {
                    return "Invalid segment '$seg': use lowercase letters, digits, or '_', starting with a letter."
                }
                if (seg in JAVA_KEYWORDS) {
                    return "Segment '$seg' is a reserved Java/Kotlin keyword."
                }
            }
            return null
        }

        fun validateVersionName(version: String): String? {
            val trimmed = version.trim()
            if (trimmed.isBlank()) return "Version name cannot be empty."
            if (trimmed.length > 24) return "Version name is too long."
            return null
        }

        fun validateVersionCode(code: Int): String? {
            return if (code < 1) "Version code must be 1 or greater." else null
        }

        fun validateRemoteUrl(url: String): String? {
            val trimmed = url.trim()
            return when {
                trimmed.isBlank() -> "Remote URL cannot be empty."
                !trimmed.startsWith("https://", ignoreCase = true) &&
                    !trimmed.startsWith("http://", ignoreCase = true) ->
                    "URL must start with https:// or http://"
                else -> null
            }
        }

        fun escapeXmlText(raw: String): String {
            return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "\\'")
        }

        fun escapeKotlinString(raw: String): String {
            return raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("\n", "\\n")
                .replace("\r", "")
        }
    }
}

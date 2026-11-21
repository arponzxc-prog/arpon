package com.example.builder

import com.example.data.filesystem.HtmlBuildConfig
import java.io.File

/**
 * Pure Kotlin idempotent injector that updates `app/build.gradle.kts` with:
 * - versionCode & versionName
 * - optional applicationId
 * - release signingConfig backed by environment variables / CI keystore (`my-upload-key.jks`)
 * - Gradle property-controlled R8 minify (`isMinifyEnabled`) & resource shrinking (`isShrinkResources`)
 */
object GradleBuildConfigInjector {

    fun injectIntoAppBuildGradle(
        gradleText: String,
        versionCode: Int?,
        versionName: String?,
        packageName: String? = null,
        minifyEnabled: Boolean = false
    ): String {
        var updated = gradleText

        if (versionCode != null && versionCode >= 1) {
            val vcRegex = Regex("""versionCode\s*=\s*\d+""")
            if (vcRegex.containsMatchIn(updated)) {
                updated = vcRegex.replaceFirst(updated, "versionCode = $versionCode")
            }
        }

        if (!versionName.isNullOrBlank()) {
            val cleanVName = HtmlBuildConfig.escapeKotlinString(versionName.trim())
            val vnRegex = Regex("""versionName\s*=\s*"[^"]*"""")
            if (vnRegex.containsMatchIn(updated)) {
                updated = vnRegex.replaceFirst(updated, Regex.escapeReplacement("versionName = \"$cleanVName\""))
            }
        }

        if (!packageName.isNullOrBlank()) {
            val cleanPkg = packageName.trim()
            val appIdRegex = Regex("""applicationId\s*=\s*"[^"]*"""")
            if (appIdRegex.containsMatchIn(updated)) {
                updated = appIdRegex.replaceFirst(updated, Regex.escapeReplacement("applicationId = \"$cleanPkg\""))
            }
        }

        // Inject R8 Minify & Shrink control (or set directly)
        val minifyVal = if (minifyEnabled) {
            "(project.findProperty(\"nexaMinify\")?.toString()?.toBoolean() ?: true)"
        } else {
            "(project.findProperty(\"nexaMinify\")?.toString()?.toBoolean() ?: false)"
        }

        val minifyRegex = Regex("""isMinifyEnabled\s*=\s*[^\n]+""")
        if (minifyRegex.containsMatchIn(updated)) {
            updated = minifyRegex.replaceFirst(updated, Regex.escapeReplacement("isMinifyEnabled = $minifyVal"))
        }

        val shrinkRegex = Regex("""isShrinkResources\s*=\s*[^\n]+""")
        if (shrinkRegex.containsMatchIn(updated)) {
            updated = shrinkRegex.replaceFirst(updated, Regex.escapeReplacement("isShrinkResources = $minifyVal"))
        } else if (minifyEnabled) {
            val targetReleaseRegex = Regex("""isMinifyEnabled\s*=\s*[^\n]+""")
            val match = targetReleaseRegex.find(updated)
            if (match != null) {
                updated = updated.replaceRange(
                    match.range,
                    "${match.value}\n            isShrinkResources = $minifyVal"
                )
            }
        }

        // Inject release signingConfigs block if missing (supports CI secrets + fallback keystore)
        if (!updated.contains("signingConfigs") && updated.contains("buildTypes")) {
            val signingBlock = """
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
            """.trimIndent()
            updated = updated.replaceFirst(Regex("""buildTypes\s*\{"""), Regex.escapeReplacement(signingBlock))
        }

        // Attach signingConfig = signingConfigs.getByName("release") inside release { ... } if not already set
        if (updated.contains("signingConfigs") && !updated.contains("signingConfig = signingConfigs.getByName")) {
            val releaseStartRegex = Regex("""buildTypes\s*\{\s*release\s*\{""")
            val releaseMatch = releaseStartRegex.find(updated)
            if (releaseMatch != null) {
                updated = updated.replaceRange(
                    releaseMatch.range,
                    "${releaseMatch.value}\n            signingConfig = signingConfigs.getByName(\"release\")"
                )
            }
        }

        return updated
    }

    fun prepareProjectGradleFiles(
        projectRoot: File,
        versionCode: Int?,
        versionName: String?,
        packageName: String?,
        minifyEnabled: Boolean
    ): Boolean {
        val appGradle = File(projectRoot, "app/build.gradle.kts")
        if (!appGradle.exists()) return false
        val original = appGradle.readText()
        val updated = injectIntoAppBuildGradle(
            gradleText = original,
            versionCode = versionCode,
            versionName = versionName,
            packageName = packageName,
            minifyEnabled = minifyEnabled
        )
        if (updated != original) {
            appGradle.writeText(updated)
            return true
        }
        return false
    }
}

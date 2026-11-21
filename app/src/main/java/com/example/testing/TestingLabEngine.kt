package com.example.testing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.local.entity.ProjectEntity
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

enum class TestResultStatus {
    PASSED,
    FAILED,
    SKIPPED
}

@JsonClass(generateAdapter = true)
data class TestCaseResult(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String,
    val status: TestResultStatus,
    val durationMs: Long,
    val details: String,
    val fileName: String? = null,
    val lineNumber: Int? = null
)

@JsonClass(generateAdapter = true)
data class TestSuiteReport(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val projectName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val totalDurationMs: Long,
    val passedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val results: List<TestCaseResult>
)

data class ApkAnalysisResult(
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int?,
    val isDebuggable: Boolean,
    val permissions: List<String>,
    val signingSha256Fingerprints: List<String>,
    val dexFileCount: Int,
    val nativeAbis: List<String>,
    val resourceFileCount: Int,
    val hasAndroidManifest: Boolean,
    val warnings: List<String>
)

data class ParsedBuildError(
    val fileName: String?,
    val lineNumber: Int?,
    val rawMessage: String,
    val explanation: String
)

data class DeviceEnvironmentInfo(
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val abis: String,
    val canInstallPackages: Boolean,
    val hasExternalAdbOrEmulator: Boolean,
    val statusNote: String
)

class TestingLabEngine(private val context: Context) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val reportListType = Types.newParameterizedType(List::class.java, TestSuiteReport::class.java)
    private val reportAdapter = moshi.adapter<List<TestSuiteReport>>(reportListType)

    private val historyFile: File
        get() = File(context.filesDir, "testing_lab_history.json")

    fun loadTestHistory(): List<TestSuiteReport> {
        if (!historyFile.exists()) return emptyList()
        return try {
            reportAdapter.fromJson(historyFile.readText()) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTestReport(report: TestSuiteReport) {
        val existing = loadTestHistory().toMutableList()
        existing.add(0, report)
        val trimmed = existing.take(30)
        historyFile.writeText(reportAdapter.toJson(trimmed))
    }

    fun clearTestHistory() {
        if (historyFile.exists()) historyFile.delete()
    }

    fun getDeviceEnvironmentInfo(): DeviceEnvironmentInfo {
        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
        return DeviceEnvironmentInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            canInstallPackages = canInstall,
            hasExternalAdbOrEmulator = false,
            statusNote = "Running directly on current Android device. No external ADB bridge or nested emulator is attached; local APK installation & launch testing uses Android PackageInstaller when permitted."
        )
    }

    /**
     * Runs authentic static, manifest, resource, dependency, and syntax validation checks against [project].
     * Honestly marks JVM unit/instrumented tests as SKIPPED with reason when executed in an on-device Android sandbox.
     */
    suspend fun runProjectValidationSuite(
        project: ProjectEntity,
        selectedTypes: Set<String>
    ): TestSuiteReport = withContext(Dispatchers.IO) {
        val suiteStart = System.currentTimeMillis()
        val root = File(project.rootPath)
        val results = mutableListOf<TestCaseResult>()

        // 1. Project Structure Validation
        if ("Structure" in selectedTypes) {
            val t0 = System.currentTimeMillis()
            val expectedFiles = listOf(
                "settings.gradle.kts",
                "build.gradle.kts",
                "app/build.gradle.kts",
                "app/src/main/AndroidManifest.xml"
            )
            val missing = expectedFiles.filter { !File(root, it).exists() }
            results += if (missing.isEmpty()) {
                TestCaseResult(
                    name = "Core Android Project Structure",
                    category = "Structure",
                    status = TestResultStatus.PASSED,
                    durationMs = System.currentTimeMillis() - t0,
                    details = "All core Gradle and Manifest files exist."
                )
            } else {
                TestCaseResult(
                    name = "Core Android Project Structure",
                    category = "Structure",
                    status = TestResultStatus.FAILED,
                    durationMs = System.currentTimeMillis() - t0,
                    details = "Missing core project files: ${missing.joinToString(", ")}",
                    fileName = missing.first()
                )
            }
        }

        // 2. AndroidManifest.xml Validation
        if ("Manifest" in selectedTypes) {
            val t0 = System.currentTimeMillis()
            val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
            if (!manifestFile.exists()) {
                results += TestCaseResult(
                    name = "AndroidManifest.xml Inspection",
                    category = "Manifest",
                    status = TestResultStatus.FAILED,
                    durationMs = System.currentTimeMillis() - t0,
                    details = "app/src/main/AndroidManifest.xml does not exist.",
                    fileName = "AndroidManifest.xml"
                )
            } else {
                results += validateManifestXml(manifestFile, t0)
            }
        }

        // 3. XML Resource Validation
        if ("Resources" in selectedTypes) {
            val t0 = System.currentTimeMillis()
            val resDir = File(root, "app/src/main/res")
            if (!resDir.exists()) {
                results += TestCaseResult(
                    name = "Android Resource XML Validation",
                    category = "Resources",
                    status = TestResultStatus.FAILED,
                    durationMs = System.currentTimeMillis() - t0,
                    details = "res/ directory is missing."
                )
            } else {
                val xmlFiles = resDir.walkTopDown().filter { it.isFile && it.extension.equals("xml", true) }.toList()
                var brokenFile: File? = null
                var brokenErr: String? = null
                var brokenLine: Int? = null

                for (xml in xmlFiles) {
                    val parseErr = validateXmlSyntax(xml.readText())
                    if (parseErr != null) {
                        brokenFile = xml
                        brokenErr = parseErr.first
                        brokenLine = parseErr.second
                        break
                    }
                }

                results += if (brokenFile == null) {
                    TestCaseResult(
                        name = "Android Resource XML Validation",
                        category = "Resources",
                        status = TestResultStatus.PASSED,
                        durationMs = System.currentTimeMillis() - t0,
                        details = "Parsed ${xmlFiles.size} resource XML file(s) with 0 syntax errors."
                    )
                } else {
                    TestCaseResult(
                        name = "Android Resource XML Validation",
                        category = "Resources",
                        status = TestResultStatus.FAILED,
                        durationMs = System.currentTimeMillis() - t0,
                        details = "Malformed XML in ${brokenFile.name}: $brokenErr",
                        fileName = brokenFile.name,
                        lineNumber = brokenLine
                    )
                }
            }
        }

        // 4. Gradle & Dependency Checks
        if ("Dependencies" in selectedTypes) {
            val t0 = System.currentTimeMillis()
            val appGradle = File(root, "app/build.gradle.kts").takeIf { it.exists() }
                ?: File(root, "app/build.gradle").takeIf { it.exists() }
            if (appGradle == null) {
                results += TestCaseResult(
                    name = "Gradle Build & Dependency Audit",
                    category = "Dependencies",
                    status = TestResultStatus.FAILED,
                    durationMs = System.currentTimeMillis() - t0,
                    details = "Missing app/build.gradle.kts or app/build.gradle."
                )
            } else {
                val content = appGradle.readText()
                val issues = mutableListOf<String>()
                if (content.contains("http://") && !content.contains("127.0.0.1")) {
                    issues.add("Insecure plain HTTP repository/URL detected")
                }
                if (Regex("AIza[0-9A-Za-z\\-_]{20,}|ghp_[0-9A-Za-z]{20,}").containsMatchIn(content)) {
                    issues.add("Hardcoded credential/API key detected in Gradle file")
                }
                val depLines = content.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("implementation(") || it.startsWith("implementation ") }
                val duplicates = depLines.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                if (duplicates.isNotEmpty()) {
                    issues.add("Duplicate dependency entries: ${duplicates.joinToString()}")
                }
                results += if (issues.isEmpty()) {
                    TestCaseResult(
                        name = "Gradle Build & Dependency Audit",
                        category = "Dependencies",
                        status = TestResultStatus.PASSED,
                        durationMs = System.currentTimeMillis() - t0,
                        details = "Checked ${depLines.size} dependency declaration(s) in ${appGradle.name}; no duplicates or plaintext secrets found.",
                        fileName = appGradle.name
                    )
                } else {
                    TestCaseResult(
                        name = "Gradle Build & Dependency Audit",
                        category = "Dependencies",
                        status = TestResultStatus.FAILED,
                        durationMs = System.currentTimeMillis() - t0,
                        details = issues.joinToString("; "),
                        fileName = appGradle.name
                    )
                }
            }
        }

        // 5. Source Code Syntax & Bracket Balance Validation
        if ("Gradle Build Validation" in selectedTypes || "Unit Tests" in selectedTypes) {
            val t0 = System.currentTimeMillis()
            val srcFiles = root.walkTopDown()
                .filter { !it.absolutePath.contains("/.gradle") && !it.absolutePath.contains("/build/") && !it.absolutePath.contains("/.git") }
                .filter { it.isFile && it.extension.lowercase() in listOf("kt", "java", "js", "css", "json") }
                .toList()

            var failedFile: File? = null
            var failedReason: String? = null
            var failedLine: Int? = null

            for (f in srcFiles) {
                val issue = checkCodeBracketBalance(f.readText())
                if (issue != null) {
                    failedFile = f
                    failedReason = issue.first
                    failedLine = issue.second
                    break
                }
            }

            results += if (failedFile == null) {
                TestCaseResult(
                    name = "Source Code Syntax & Bracket Check",
                    category = "Static Check",
                    status = TestResultStatus.PASSED,
                    durationMs = System.currentTimeMillis() - t0,
                    details = "Validated ${srcFiles.size} source file(s) (.kt/.java/.js/.css/.json) with balanced scopes."
                )
            } else {
                TestCaseResult(
                    name = "Source Code Syntax & Bracket Check",
                    category = "Static Check",
                    status = TestResultStatus.FAILED,
                    durationMs = System.currentTimeMillis() - t0,
                    details = "${failedFile.name}: $failedReason",
                    fileName = failedFile.name,
                    lineNumber = failedLine
                )
            }
        }

        // 6. On-Device Unit & Instrumented Test Honesty Entry
        if ("Unit Tests" in selectedTypes) {
            results += TestCaseResult(
                name = "JVM Unit Tests (:app:testDebugUnitTest)",
                category = "Unit Tests",
                status = TestResultStatus.SKIPPED,
                durationMs = 0L,
                details = "Skipped on device: Android runtime does not ship a local JDK/Gradle test runner. Trigger GitHub Actions CI or run locally on desktop via 'gradle :app:testDebugUnitTest'."
            )
        }
        if ("Instrumented Tests" in selectedTypes) {
            results += TestCaseResult(
                name = "Android Instrumented Tests (androidTest)",
                category = "Instrumented Tests",
                status = TestResultStatus.SKIPPED,
                durationMs = 0L,
                details = "Skipped: No external ADB test harness or Android Emulator instrumentation service is attached to this device."
            )
        }

        val report = TestSuiteReport(
            projectId = project.id,
            projectName = project.name,
            totalDurationMs = System.currentTimeMillis() - suiteStart,
            passedCount = results.count { it.status == TestResultStatus.PASSED },
            failedCount = results.count { it.status == TestResultStatus.FAILED },
            skippedCount = results.count { it.status == TestResultStatus.SKIPPED },
            results = results
        )
        saveTestReport(report)
        report
    }

    private fun validateManifestXml(manifestFile: File, startMs: Long): TestCaseResult {
        val xml = manifestFile.readText()
        val syntaxErr = validateXmlSyntax(xml)
        if (syntaxErr != null) {
            return TestCaseResult(
                name = "AndroidManifest.xml Inspection",
                category = "Manifest",
                status = TestResultStatus.FAILED,
                durationMs = System.currentTimeMillis() - startMs,
                details = "XML syntax error: ${syntaxErr.first}",
                fileName = manifestFile.name,
                lineNumber = syntaxErr.second
            )
        }
        val missingAppTag = !xml.contains("<application")
        val missingActivity = !xml.contains("<activity")
        return if (missingAppTag || missingActivity) {
            TestCaseResult(
                name = "AndroidManifest.xml Inspection",
                category = "Manifest",
                status = TestResultStatus.FAILED,
                durationMs = System.currentTimeMillis() - startMs,
                details = "Manifest is valid XML but missing required <application> or <activity> tag.",
                fileName = manifestFile.name
            )
        } else {
            val warnings = mutableListOf<String>()
            if (xml.contains("usesCleartextTraffic=\"true\"")) warnings.add("cleartextTraffic=true")
            val warnNote = if (warnings.isNotEmpty()) " (Warnings: ${warnings.joinToString()})" else ""
            TestCaseResult(
                name = "AndroidManifest.xml Inspection",
                category = "Manifest",
                status = TestResultStatus.PASSED,
                durationMs = System.currentTimeMillis() - startMs,
                details = "Manifest XML well-formed with <application> and <activity> declarations$warnNote.",
                fileName = manifestFile.name
            )
        }
    }

    private fun validateXmlSyntax(xml: String): Pair<String, Int?>? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                event = parser.next()
            }
            null
        } catch (e: Exception) {
            val line = (e as? org.xmlpull.v1.XmlPullParserException)?.lineNumber
            (e.message ?: "Invalid XML") to line
        }
    }

    private fun checkCodeBracketBalance(code: String): Pair<String, Int>? {
        var braceBalance = 0
        var parenBalance = 0
        var lastOpenBraceLine = 1
        val lines = code.lines()
        lines.forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
            var inString = false
            var quoteChar = '"'
            var escaped = false
            for (ch in line) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    continue
                }
                if (inString) {
                    if (ch == quoteChar) inString = false
                    continue
                }
                if (ch == '"' || ch == '\'') {
                    inString = true
                    quoteChar = ch
                    continue
                }
                when (ch) {
                    '{' -> {
                        if (braceBalance == 0) lastOpenBraceLine = idx + 1
                        braceBalance++
                    }
                    '}' -> braceBalance--
                    '(' -> parenBalance++
                    ')' -> parenBalance--
                }
                if (braceBalance < 0) return "Unexpected closing '}'" to (idx + 1)
                if (parenBalance < 0) return "Unexpected closing ')'" to (idx + 1)
            }
        }
        if (braceBalance > 0) return "Unclosed '{' ($braceBalance remaining)" to lastOpenBraceLine
        if (parenBalance > 0) return "Unclosed '(' ($parenBalance remaining)" to lines.size
        return null
    }

    /**
     * Parses real Gradle/Kotlin/Java compiler logs for actionable file names, line numbers, and readable hints.
     */
    fun parseGradleBuildErrors(buildLog: String): List<ParsedBuildError> {
        if (buildLog.isBlank()) return emptyList()
        val parsed = mutableListOf<ParsedBuildError>()
        val seen = mutableSetOf<String>()

        val kotlinRegex = Regex("""e:\s*(?:file://)?([^\s:]+\.(?:kt|java|kts|xml)):(\d+)(?::\d+)?\s*(.*)""")
        val javaRegex = Regex("""([^\s:]+\.java):(\d+):\s*error:\s*(.*)""")
        val taskFailRegex = Regex(""">\s*Task\s+(:[^\s]+)\s+FAILED""")

        buildLog.lines().forEach { line ->
            val clean = line.trim()
            if (clean.isEmpty() || !seen.add(clean)) return@forEach

            kotlinRegex.find(clean)?.let { m ->
                val path = m.groupValues[1]
                val lineNum = m.groupValues[2].toIntOrNull()
                val msg = m.groupValues[3]
                parsed += ParsedBuildError(
                    fileName = File(path).name,
                    lineNumber = lineNum,
                    rawMessage = clean,
                    explanation = explainErrorMessage(msg)
                )
                return@forEach
            }

            javaRegex.find(clean)?.let { m ->
                val path = m.groupValues[1]
                val lineNum = m.groupValues[2].toIntOrNull()
                val msg = m.groupValues[3]
                parsed += ParsedBuildError(
                    fileName = File(path).name,
                    lineNumber = lineNum,
                    rawMessage = clean,
                    explanation = explainErrorMessage(msg)
                )
                return@forEach
            }

            taskFailRegex.find(clean)?.let { m ->
                val task = m.groupValues[1]
                parsed += ParsedBuildError(
                    fileName = null,
                    lineNumber = null,
                    rawMessage = clean,
                    explanation = "Gradle task '$task' failed during compilation or resource processing. Check the error lines immediately above this task failure."
                )
                return@forEach
            }

            if (clean.startsWith("FAILURE:") || clean.contains("Execution failed for task")) {
                parsed += ParsedBuildError(
                    fileName = null,
                    lineNumber = null,
                    rawMessage = clean,
                    explanation = explainErrorMessage(clean)
                )
            }
        }
        return parsed
    }

    private fun explainErrorMessage(msg: String): String {
        return when {
            msg.contains("Unresolved reference", true) ->
                "Missing import, misspelled symbol, or undeclared dependency in build.gradle.kts."
            msg.contains("Type mismatch", true) ->
                "The expression produces a different type than the variable or parameter expects."
            msg.contains("Cannot infer type", true) ->
                "Kotlin could not deduce the generic type automatically; specify the type explicitly."
            msg.contains("Duplicate class", true) ->
                "Two dependencies include the same class; exclude the conflicting transitive module in Gradle."
            msg.contains("Manifest merger failed", true) ->
                "AndroidManifest.xml attributes conflict with a library manifest or contain an invalid value."
            msg.contains("resource", true) && msg.contains("not found", true) ->
                "A referenced drawable, string, or layout ID does not exist under app/src/main/res/."
            else -> msg.ifBlank { "Compiler reported a syntax or configuration error." }
        }
    }

    /**
     * Inspects a real APK file using Android PackageManager + ZipFile without running it.
     */
    @Suppress("DEPRECATION")
    suspend fun analyzeApkFile(apkFile: File): Result<ApkAnalysisResult> = withContext(Dispatchers.IO) {
        try {
            if (!apkFile.exists() || !apkFile.isFile) {
                return@withContext Result.failure(IllegalArgumentException("APK file does not exist."))
            }

            val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES
            val pkgInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }

            var dexCount = 0
            var resCount = 0
            var hasManifest = false
            val abis = mutableSetOf<String>()

            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    when {
                        name == "AndroidManifest.xml" -> hasManifest = true
                        name.startsWith("classes") && name.endsWith(".dex") -> dexCount++
                        name.startsWith("res/") -> resCount++
                        name.startsWith("lib/") -> {
                            val parts = name.split("/")
                            if (parts.size >= 3) abis.add(parts[1])
                        }
                    }
                }
            }

            if (!hasManifest && pkgInfo == null) {
                return@withContext Result.failure(IllegalStateException("Selected file is not a valid Android APK (missing AndroidManifest.xml)."))
            }

            val appInfo = pkgInfo?.applicationInfo
            val isDebuggable = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo?.minSdkVersion else null
            val targetSdk = appInfo?.targetSdkVersion
            val versionCode = if (pkgInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()
            } else 0L

            val perms = pkgInfo?.requestedPermissions?.toList() ?: emptyList()
            val sigFingerprints = pkgInfo?.signatures?.mapNotNull { sig ->
                try {
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(sig.toByteArray())
                    digest.joinToString(":") { "%02X".format(it) }
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()

            val warnings = mutableListOf<String>()
            if (isDebuggable) warnings += "APK has android:debuggable=true (not suitable for Play Store production release)."
            if (sigFingerprints.isEmpty()) warnings += "No v1 signing certificate extracted via PackageArchiveInfo (may be unsigned or v2/v3-only)."
            if (perms.size > 15) warnings += "High permission count (${perms.size} permissions requested)."

            Result.success(
                ApkAnalysisResult(
                    fileName = apkFile.name,
                    filePath = apkFile.absolutePath,
                    fileSizeBytes = apkFile.length(),
                    packageName = pkgInfo?.packageName ?: "Unknown (unparsed manifest)",
                    versionName = pkgInfo?.versionName ?: "Unknown",
                    versionCode = versionCode,
                    minSdk = minSdk,
                    targetSdk = targetSdk,
                    isDebuggable = isDebuggable,
                    permissions = perms,
                    signingSha256Fingerprints = sigFingerprints,
                    dexFileCount = dexCount,
                    nativeAbis = abis.sorted(),
                    resourceFileCount = resCount,
                    hasAndroidManifest = hasManifest,
                    warnings = warnings
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatReadableReport(report: TestSuiteReport): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(report.timestamp))
        return buildString {
            appendLine("# Nexa IDE Testing Lab Report")
            appendLine("Project: ${report.projectName}")
            appendLine("Date: $dateStr")
            appendLine("Duration: ${report.totalDurationMs} ms")
            appendLine("Summary: ${report.passedCount} Passed | ${report.failedCount} Failed | ${report.skippedCount} Skipped")
            appendLine()
            report.results.forEach { tc ->
                appendLine("[${tc.status.name}] (${tc.category}) ${tc.name} (${tc.durationMs}ms)")
                appendLine("  -> ${tc.details}")
                if (!tc.fileName.isNullOrBlank()) {
                    appendLine("  -> File: ${tc.fileName}${tc.lineNumber?.let { ":$it" } ?: ""}")
                }
            }
        }
    }
}

package com.example.builder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.filesystem.ProjectFileManager
import com.example.data.github.*
import com.example.data.local.NexaDatabase
import com.example.data.local.entity.BuildArtifactEntity
import com.example.data.local.entity.BuildEntity
import com.example.data.local.entity.NotificationEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.security.ReleaseKeystoreManager
import com.example.data.security.SecureTokenManager
import com.example.testing.TestResultStatus
import com.example.testing.TestingLabEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

sealed class BuildProgressState {
    object Idle : BuildProgressState()
    data class Active(
        val projectName: String,
        val currentStep: String,
        val progressPercent: Float,
        val liveLogs: String,
        val status: String,
        val runHtmlUrl: String? = null
    ) : BuildProgressState()
}

class BuildEngine(
    private val context: Context,
    private val database: NexaDatabase,
    private val fileManager: ProjectFileManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var currentBuildJob: Job? = null
    private var currentActiveRunInfo: Triple<String, String, Long>? = null

    private val _buildState = MutableStateFlow<BuildProgressState>(BuildProgressState.Idle)
    val buildState: StateFlow<BuildProgressState> = _buildState.asStateFlow()

    private val gitHubService by lazy { GitHubService.create() }

    init {
        // Keep the process alive (foreground service) for as long as a build is running, even when
        // the UI is gone. Observed here, not in the Activity, so it works after the Activity is destroyed.
        scope.launch {
            var serviceRunning = false
            _buildState.collect { st ->
                val appCtx = context.applicationContext
                if (BuildNotificationText.needsService(st)) {
                    BuildKeepAliveService.startOrUpdate(appCtx, st as BuildProgressState.Active)
                    serviceRunning = true
                } else if (serviceRunning) {
                    BuildKeepAliveService.stop(appCtx)
                    serviceRunning = false
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: BuildEngine? = null

        /** Process-wide engine: survives Activity recreation/destroy so a running build is never orphaned. */
        fun getInstance(context: Context, database: NexaDatabase, fileManager: ProjectFileManager): BuildEngine =
            instance ?: synchronized(this) {
                instance ?: BuildEngine(context.applicationContext, database, fileManager).also { instance = it }
            }
    }

    fun triggerBuild(
        project: ProjectEntity,
        buildType: String = "DEBUG",
        jdk: String = "17",
        gradle: String = "8.10.2",
        compileSdk: String = "35",
        clean: Boolean = true,
        versionCode: Int? = null,
        versionName: String? = null,
        enableMinify: Boolean = false,
        moduleName: String = ":app",
        skipPreValidation: Boolean = false
    ) {
        currentBuildJob?.cancel()
        currentBuildJob = scope.launch {
            val buildId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()
            val logBuilder = StringBuilder()
            var runId: Long? = null
            var extractedErrorMessage: String? = null
            val runtimeSecrets = mutableListOf<String>()

            fun appendLog(msg: String) {
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val safeMsg = SecureTokenManager.redactSecrets(msg, runtimeSecrets)
                logBuilder.append("[$time] $safeMsg\n")
            }

            fun updateProgress(step: String, percent: Float, status: String, runUrl: String? = null) {
                _buildState.value = BuildProgressState.Active(
                    projectName = project.name,
                    currentStep = step,
                    progressPercent = percent,
                    liveLogs = SecureTokenManager.redactSecrets(logBuilder.toString(), runtimeSecrets),
                    status = status,
                    runHtmlUrl = runUrl
                )
            }

            try {
                appendLog("=== Nexa IDE Cloud APK Builder ===")
                appendLog("Target Project: ${project.name} (${project.packageName})")
                appendLog("Build Variant: $buildType | Module: $moduleName | R8 Minify: $enableMinify")
                if (versionName != null || versionCode != null) {
                    appendLog("Version Override: v${versionName ?: "default"} (code ${versionCode ?: "default"})")
                }
                appendLog("JDK: OpenJDK $jdk | Gradle Engine: $gradle | Compile SDK: API $compileSdk")

                // Step 0: Pre-Build Validation Check
                if (!skipPreValidation) {
                    updateProgress("Running pre-build validation", 0.03f, "VALIDATING")
                    val validatorReport = TestingLabEngine(context).runProjectValidationSuite(
                        project = project,
                        selectedTypes = setOf("Structure", "Manifest", "Resources")
                    )
                    val failedChecks = validatorReport.results.filter { it.status == TestResultStatus.FAILED }
                    if (failedChecks.isNotEmpty()) {
                        val firstFail = failedChecks.first()
                        val detailMsg = failedChecks.joinToString("\n") { "• ${it.name}: ${it.details}" }
                        appendLog("Pre-Build Validation Failed:\n$detailMsg")
                        throw IllegalStateException("Pre-Build Validation Failed (${firstFail.fileName ?: firstFail.category}): ${firstFail.details}")
                    }
                    appendLog("Pre-Build Validator: ${validatorReport.passedCount} check(s) passed.")
                }

                // Step 0.5: Inject versionCode, versionName, minify & release signingConfig cleanly
                val resolvedVersionCode = versionCode ?: 1
                val resolvedVersionName = versionName?.ifBlank { "1.0.0" } ?: "1.0.0"
                GradleBuildConfigInjector.prepareProjectGradleFiles(
                    projectRoot = File(project.rootPath),
                    versionCode = versionCode,
                    versionName = versionName,
                    packageName = project.packageName,
                    minifyEnabled = enableMinify
                )

                updateProgress("Verifying GitHub credentials", 0.06f, "INITIALIZING")

                // Step 1: Verify GitHub connection with Keystore-backed SecureTokenManager
                val connection = database.gitHubConnectionDao().getConnection()
                val token = SecureTokenManager.getGitHubToken(context)?.takeIf { it.isNotBlank() }
                    ?: connection?.token?.takeIf { it.isNotBlank() && !it.startsWith("SECURE_") && !it.contains("••••") }

                if (!token.isNullOrBlank()) runtimeSecrets.add(token)

                if (token.isNullOrBlank()) {
                    throw IllegalStateException(
                        "No GitHub Token found. Connect your GitHub Personal Access Token (PAT) with 'repo' and 'workflow' scopes in GitHub screen before building."
                    )
                }

                val targetRepo = connection?.activeRepo?.trim()
                if (targetRepo.isNullOrBlank() || !targetRepo.contains("/")) {
                    throw IllegalStateException(
                        "No target repository selected. Please open GitHub screen and select or create an active repository (e.g. ${connection?.username ?: "username"}/my-app)."
                    )
                }

                val repoParts = targetRepo.split("/")
                val repoOwner = repoParts[0]
                val repoName = repoParts[1]
                val authHeader = "Bearer ${token.trim()}"

                appendLog("Target GitHub Repository: $targetRepo")
                updateProgress("Inspecting repository metadata", 0.10f, "PREPARING")

                val repoDetails = try {
                    gitHubService.getRepository(authHeader, repoOwner, repoName)
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Could not access repository '$targetRepo' (${e.message}). Ensure your GitHub token has 'repo' scope and write permissions."
                    )
                }

                val targetBranch = repoDetails.defaultBranch.ifBlank { "main" }
                appendLog("Repository default branch: $targetBranch")

                // Step 1.5: If building RELEASE/BUNDLE and encrypted keystore is configured, ensure secrets are synced
                val signingMeta = SecureTokenManager.getReleaseSigningMetadata(context)
                val signingSecrets = SecureTokenManager.getReleaseSigningSecrets(context)
                if (signingSecrets != null) {
                    runtimeSecrets.add(signingSecrets.first)
                    runtimeSecrets.add(signingSecrets.second)
                    runtimeSecrets.add(signingSecrets.third)
                }
                if ((buildType == "RELEASE" || buildType == "BUNDLE") && signingMeta != null && signingSecrets != null) {
                    if (signingMeta.uploadedRepo != targetRepo) {
                        updateProgress("Encrypting & uploading release keystore secrets", 0.14f, "SIGNING_SETUP")
                        appendLog("Syncing production keystore (alias '${signingMeta.alias}') to GitHub Actions secrets...")
                        val secretRes = ReleaseKeystoreManager.uploadSigningSecretsToGitHub(
                            service = gitHubService,
                            githubToken = token,
                            owner = repoOwner,
                            repo = repoName,
                            keystoreBase64 = signingSecrets.first,
                            storePassword = signingSecrets.second,
                            keyAlias = signingMeta.alias,
                            keyPassword = signingSecrets.third
                        )
                        if (secretRes.isSuccess) {
                            SecureTokenManager.markKeystoreUploadedToRepo(context, targetRepo)
                            appendLog("Release signing secrets uploaded securely via sealed-box encryption.")
                        } else {
                            appendLog("Warning: Could not upload signing secrets (${secretRes.exceptionOrNull()?.message}). Runner will use existing repo secrets or fallback key.")
                        }
                    } else {
                        appendLog("Using verified production release keystore (alias '${signingMeta.alias}').")
                    }
                }

                // Step 2: Incremental sync project files and workflow directly via Git Data API
                updateProgress("Incremental sync to GitHub ($targetBranch)", 0.22f, "SYNCING")
                appendLog("Comparing ${project.name} file hashes with branch '$targetBranch'...")
                syncProjectFiles(project, authHeader, repoOwner, repoName, targetBranch, logBuilder)

                // Step 3: Record known run IDs before dispatching
                val initialRuns = try {
                    gitHubService.listWorkflowRuns(authHeader, repoOwner, repoName, perPage = 30)
                } catch (e: Exception) {
                    null
                }
                val knownIds = initialRuns?.workflowRuns?.map { it.id }?.toSet() ?: emptySet()

                // Step 4: Dispatch workflow with retry for 404
                appendLog("Dispatching workflow dispatch event to '.github/workflows/android-build.yml' on branch '$targetBranch'...")
                updateProgress("Triggering GitHub Actions runner", 0.40f, "TRIGGERING")

                val dispatchInputs = buildMap {
                    put("build_type", buildType.lowercase())
                    put("jdk_version", jdk)
                    put("clean_build", clean.toString())
                    put("enable_minify", enableMinify.toString())
                    put("module_name", moduleName.ifBlank { ":app" })
                    if (versionCode != null) put("version_code", versionCode.toString())
                    if (!versionName.isNullOrBlank()) put("version_name", versionName)
                }

                var dispatchResp: retrofit2.Response<Unit>? = null
                var lastDispatchException: Exception? = null
                for (attempt in 1..5) {
                    try {
                        val resp = gitHubService.triggerWorkflowDispatch(
                            auth = authHeader,
                            owner = repoOwner,
                            repo = repoName,
                            workflowId = "android-build.yml",
                            payload = WorkflowDispatchPayload(
                                ref = targetBranch,
                                inputs = dispatchInputs
                            )
                        )
                        dispatchResp = resp
                        if (resp.isSuccessful) break
                        if (resp.code() == 404 && attempt < 5) {
                            appendLog("Workflow not registered yet (404), waiting 4s before retry ($attempt/5)...")
                            delay(4000L)
                            continue
                        }
                        break
                    } catch (e: Exception) {
                        lastDispatchException = e
                        if (attempt < 5) {
                            delay(4000L)
                        }
                    }
                }

                if (dispatchResp == null || !dispatchResp.isSuccessful) {
                    val code = dispatchResp?.code() ?: 0
                    val errorBody = dispatchResp?.errorBody()?.string() ?: lastDispatchException?.message ?: ""
                    appendLog("GitHub API Error HTTP $code: $errorBody")
                    if (code == 404) {
                        throw IllegalStateException(
                            "Workflow or Repository Not Found (404). Ensure '$targetRepo' exists and contains '.github/workflows/android-build.yml' on branch '$targetBranch'."
                        )
                    } else if (code == 403) {
                        throw IllegalStateException(
                            "GitHub API Error 403: 'workflow' scope is missing from your Personal Access Token. Please generate a new PAT with BOTH 'repo' and 'workflow' checkboxes enabled."
                        )
                    } else {
                        throw IllegalStateException("GitHub dispatch failed (HTTP $code): $errorBody")
                    }
                }

                appendLog("Workflow dispatch accepted by GitHub.")
                updateProgress("Waiting for GitHub runner to pick up job...", 0.45f, "QUEUED")

                // Step 5: Find the exact newly created workflow run
                var runHtmlUrl: String? = null

                for (attempt in 1..30) {
                    delay(3000)
                    try {
                        val runsResponse = gitHubService.listWorkflowRuns(authHeader, repoOwner, repoName, perPage = 30)
                        val targetRun = runsResponse.workflowRuns.firstOrNull { run ->
                            run.id !in knownIds &&
                                run.headBranch == targetBranch &&
                                run.event == "workflow_dispatch"
                        } ?: runsResponse.workflowRuns.firstOrNull { run ->
                            run.id !in knownIds && run.headBranch == targetBranch
                        }

                        if (targetRun != null) {
                            runId = targetRun.id
                            runHtmlUrl = targetRun.htmlUrl
                            currentActiveRunInfo = Triple(repoOwner, repoName, targetRun.id)
                            appendLog("Found GitHub Actions Run #${targetRun.id} (Status: ${targetRun.status})")
                            appendLog("View Live: ${targetRun.htmlUrl}")
                            updateProgress("GitHub Actions running #${targetRun.id}", 0.50f, targetRun.status.uppercase(), runHtmlUrl)
                            break
                        }
                    } catch (e: Exception) {
                        appendLog("Run discovery warning: ${e.message}")
                    }
                }

                if (runId == null) {
                    throw IllegalStateException("Dispatched workflow run did not appear in GitHub Actions within 90 seconds.")
                }

                // Step 6: Monitor run progress and jobs until completed
                var runConclusion: String? = null
                val maxWaitMs = 15 * 60 * 1000L
                val monitorStart = System.currentTimeMillis()

                while (System.currentTimeMillis() - monitorStart < maxWaitMs) {
                    delay(5000)
                    try {
                        val runs = gitHubService.listWorkflowRuns(authHeader, repoOwner, repoName, perPage = 10)
                        val currentRun = runs.workflowRuns.find { it.id == runId }
                        if (currentRun != null) {
                            val status = currentRun.status
                            val conclusion = currentRun.conclusion

                            // Fetch jobs for granular progress
                            var stepName = "Running GitHub Actions..."
                            var computedProgress = when (status) {
                                "queued" -> 0.52f
                                "in_progress" -> 0.65f
                                "completed" -> 0.88f
                                else -> 0.60f
                            }

                            try {
                                val jobsResp = gitHubService.listRunJobs(authHeader, repoOwner, repoName, runId)
                                val activeJob = jobsResp.jobs.firstOrNull()
                                if (activeJob != null) {
                                    val steps = activeJob.steps ?: emptyList()
                                    if (steps.isNotEmpty()) {
                                        val completedCount = steps.count { it.status == "completed" }
                                        val totalCount = steps.size
                                        computedProgress = 0.50f + 0.38f * (completedCount.toFloat() / totalCount.toFloat())
                                        val currentStepObj = steps.find { it.status == "in_progress" } ?: steps.find { it.status == "queued" }
                                        if (currentStepObj != null) {
                                            stepName = currentStepObj.name
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore job query error
                            }

                            appendLog("Run #$runId status: $status | step: $stepName | conclusion: ${conclusion ?: "running"}")
                            updateProgress(
                                step = stepName,
                                percent = computedProgress,
                                status = status.uppercase(),
                                runUrl = runHtmlUrl
                            )

                            if (status == "completed") {
                                runConclusion = conclusion
                                break
                            }
                        }
                    } catch (e: Exception) {
                        appendLog("Status check notice: ${e.message}")
                    }
                }

                if (runConclusion != "success") {
                    var failDetail = "GitHub Actions run ended with conclusion: '${runConclusion ?: "failure"}'."
                    var logSnippet = ""

                    if (runId != null) {
                        try {
                            val jobsResp = gitHubService.listRunJobs(authHeader, repoOwner, repoName, runId)
                            val failedJob = jobsResp.jobs.find { it.conclusion == "failure" } ?: jobsResp.jobs.firstOrNull()
                            if (failedJob != null) {
                                val failedStep = failedJob.steps?.find { it.conclusion == "failure" }
                                if (failedStep != null) {
                                    failDetail = "Failed at step '${failedStep.name}' in job '${failedJob.name}'."
                                    appendLog("Step Failure: ${failedStep.name} (Step #${failedStep.number})")
                                }

                                // Download job logs to extract real Gradle error
                                try {
                                    val logsBody = gitHubService.downloadJobLogs(authHeader, repoOwner, repoName, failedJob.id)
                                    val rawLogText = logsBody.string()

                                    // Clean ANSI escape codes and leading timestamps
                                    val ansiCleaned = rawLogText.replace(Regex("\u001B\\[[;\\d]*m"), "")
                                    val lines = ansiCleaned.lines()
                                    val extractedLines = mutableListOf<String>()

                                    var capturingWhatWentWrong = false
                                    var whatWentWrongCount = 0
                                    val kotlinErrors = mutableListOf<String>()

                                    for (line in lines) {
                                        // strip timestamp e.g. "2026-11-11T21:30:00.0000000Z "
                                        val cleanLine = line.replace(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z\\s+"), "")

                                        if (cleanLine.contains("What went wrong:")) {
                                            capturingWhatWentWrong = true
                                            extractedLines.add(cleanLine)
                                            whatWentWrongCount = 0
                                            continue
                                        }

                                        if (capturingWhatWentWrong) {
                                            extractedLines.add(cleanLine)
                                            whatWentWrongCount++
                                            if (whatWentWrongCount > 8) capturingWhatWentWrong = false
                                        }

                                        if ((cleanLine.contains("error:") || cleanLine.startsWith("e: ")) && kotlinErrors.size < 20) {
                                            kotlinErrors.add(cleanLine)
                                        }

                                        if (cleanLine.contains("Execution failed for task") && !extractedLines.contains(cleanLine)) {
                                            extractedLines.add(cleanLine)
                                        }

                                        if (cleanLine.contains("Minimum supported Gradle version")) {
                                            if (!extractedLines.contains(cleanLine)) extractedLines.add(cleanLine)
                                        }
                                    }

                                    if (kotlinErrors.isNotEmpty()) {
                                        extractedLines.add("--- Kotlin Errors ---")
                                        extractedLines.addAll(kotlinErrors)
                                    }

                                    logSnippet = extractedLines.take(60).joinToString("\n")
                                    if (logSnippet.isNotBlank()) {
                                        appendLog("\n--- Extracted Gradle Error Summary ---\n$logSnippet\n")
                                    }
                                } catch (e: Exception) {
                                    appendLog("Could not download job logs: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            appendLog("Could not query job steps: ${e.message}")
                        }
                    }

                    val finalError = if (logSnippet.isNotBlank()) {
                        "$failDetail\n$logSnippet"
                    } else {
                        failDetail
                    }
                    extractedErrorMessage = finalError

                    val failMsg = "$failDetail Check full compilation logs on GitHub:\n$runHtmlUrl"
                    appendLog("BUILD FAILED: $failMsg")
                    throw IllegalStateException(finalError)
                }

                // Step 7: Download real compiled APK artifact from GitHub
                appendLog("Build succeeded on GitHub Actions! Fetching generated APK artifact...")
                updateProgress("Downloading compiled APK from GitHub", 0.90f, "DOWNLOADING", runHtmlUrl)

                val artifactsResp = gitHubService.listRunArtifacts(authHeader, repoOwner, repoName, runId!!)
                val apkArtifact = artifactsResp.artifacts.firstOrNull {
                    it.name.contains("APK", ignoreCase = true) || it.name.contains("AAB", ignoreCase = true) || it.name.contains("Android", ignoreCase = true)
                } ?: artifactsResp.artifacts.firstOrNull()

                if (apkArtifact == null) {
                    throw IllegalStateException("GitHub Actions build succeeded, but no APK artifact was uploaded by the runner.")
                }

                appendLog("Found artifact: ${apkArtifact.name} (${apkArtifact.sizeInBytes / 1024} KB)")

                val artifactZipBody = gitHubService.downloadArtifactZip(authHeader, repoOwner, repoName, apkArtifact.id)
                val targetExt = if (buildType == "BUNDLE") "aab" else "apk"
                val outputApkFile = File(fileManager.downloadsDir, "${project.name.replace(' ', '_')}-${buildType.lowercase()}.$targetExt")

                ZipInputStream(artifactZipBody.byteStream()).use { zis ->
                    var entry = zis.nextEntry
                    var found = false
                    while (entry != null) {
                        if (!entry.isDirectory && (entry.name.endsWith(".apk") || entry.name.endsWith(".aab"))) {
                            FileOutputStream(outputApkFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            found = true
                            break
                        }
                        entry = zis.nextEntry
                    }
                    if (!found) {
                        throw IllegalStateException("Artifact archive did not contain a valid .apk or .aab file.")
                    }
                }

                // Verify downloaded artifact integrity
                if (!outputApkFile.exists() || outputApkFile.length() < 1024) {
                    throw IllegalStateException("Downloaded APK is empty or corrupted (size: ${outputApkFile.length()} bytes)")
                }
                val magicHeader = ByteArray(4)
                java.io.FileInputStream(outputApkFile).use { it.read(magicHeader) }
                if (magicHeader[0] != 0x50.toByte() || magicHeader[1] != 0x4B.toByte()) {
                    throw IllegalStateException("Downloaded file failed validation: invalid ZIP/APK magic header.")
                }

                appendLog("APK successfully saved to: ${outputApkFile.absolutePath}")
                appendLog("File Size: ${String.format(Locale.US, "%.2f MB", outputApkFile.length() / (1024.0 * 1024.0))}")
                appendLog("=== BUILD SUCCESSFUL ===")

                val cleanLogs = SecureTokenManager.redactSecrets(logBuilder.toString(), runtimeSecrets)
                val endTime = System.currentTimeMillis()
                val durationMs = endTime - startTime
                val maxNum = database.buildDao().getMaxBuildNumber() ?: 0
                val nextBuildNum = maxNum + 1

                val buildEntity = BuildEntity(
                    id = buildId,
                    buildNumber = nextBuildNum,
                    projectId = project.id,
                    projectName = project.name,
                    buildType = buildType,
                    status = "SUCCESSFUL",
                    logs = cleanLogs,
                    durationSeconds = durationMs / 1000L,
                    startedAt = startTime,
                    completedAt = endTime,
                    artifactName = outputApkFile.name,
                    artifactSize = outputApkFile.length(),
                    localArtifactPath = outputApkFile.absolutePath,
                    errorMessage = null,
                    githubRunId = runId
                )
                database.buildDao().insertBuild(buildEntity)

                val artifactEntity = BuildArtifactEntity(
                    id = UUID.randomUUID().toString(),
                    buildId = buildId,
                    projectId = project.id,
                    projectName = project.name,
                    fileName = outputApkFile.name,
                    fileType = targetExt.uppercase(),
                    localFilePath = outputApkFile.absolutePath,
                    fileSizeBytes = outputApkFile.length(),
                    packageName = project.packageName,
                    versionName = resolvedVersionName,
                    versionCode = resolvedVersionCode,
                    downloadedAt = endTime,
                    isVerified = true
                )
                database.buildArtifactDao().insertArtifact(artifactEntity)

                database.notificationDao().insertNotification(
                    NotificationEntity(
                        id = UUID.randomUUID().toString(),
                        title = "Build Succeeded: ${project.name}",
                        message = "Your $buildType ${targetExt.uppercase()} is ready (${outputApkFile.name}) in ${durationMs / 1000L}s.",
                        type = "BUILD_SUCCESS"
                    )
                )

                _buildState.value = BuildProgressState.Idle
                BuildKeepAliveService.notifyResult(
                    context.applicationContext,
                    "Build Succeeded: ${project.name}",
                    "Your $buildType ${targetExt.uppercase()} is ready (${outputApkFile.name})."
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Build finished: ${outputApkFile.name}", Toast.LENGTH_LONG).show()
                }

            } catch (ce: CancellationException) {
                appendLog("Build cancelled by user.")
                _buildState.value = BuildProgressState.Idle
            } catch (e: Exception) {
                val rawErr = extractedErrorMessage ?: e.message ?: "GitHub Actions compilation error occurred."
                val safeError = SecureTokenManager.redactSecrets(rawErr, runtimeSecrets)
                appendLog("ERROR: $safeError")
                val safeLogs = SecureTokenManager.redactSecrets(logBuilder.toString(), runtimeSecrets)
                val endTime = System.currentTimeMillis()
                val durationMs = endTime - startTime
                val maxNum = database.buildDao().getMaxBuildNumber() ?: 0
                val nextBuildNum = maxNum + 1

                database.buildDao().insertBuild(
                    BuildEntity(
                        id = buildId,
                        buildNumber = nextBuildNum,
                        projectId = project.id,
                        projectName = project.name,
                        buildType = buildType,
                        status = "FAILED",
                        logs = safeLogs,
                        durationSeconds = durationMs / 1000L,
                        startedAt = startTime,
                        completedAt = endTime,
                        artifactName = null,
                        artifactSize = null,
                        localArtifactPath = null,
                        errorMessage = safeError,
                        githubRunId = runId
                    )
                )

                database.notificationDao().insertNotification(
                    NotificationEntity(
                        id = UUID.randomUUID().toString(),
                        title = "Build Failed: ${project.name}",
                        message = safeError.take(1500),
                        type = "BUILD_FAILED"
                    )
                )

                _buildState.value = BuildProgressState.Idle
                BuildKeepAliveService.notifyResult(
                    context.applicationContext,
                    "Build Failed: ${project.name}",
                    safeError.take(200)
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${project.name} build failed: ${safeError.take(120)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun syncProjectFiles(
        project: ProjectEntity,
        authHeader: String,
        owner: String,
        repo: String,
        branch: String,
        logBuilder: StringBuilder
    ) {
        val rootDir = File(project.rootPath)
        if (!rootDir.exists()) return

        val rootHasBuildOrSettings = File(rootDir, "settings.gradle").exists() ||
                File(rootDir, "settings.gradle.kts").exists() ||
                File(rootDir, "build.gradle").exists() ||
                File(rootDir, "build.gradle.kts").exists()

        val subDirs = rootDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
        val effectiveDir = if (rootHasBuildOrSettings) {
            rootDir
        } else if (subDirs.size == 1 && (
                    File(subDirs[0], "settings.gradle").exists() ||
                    File(subDirs[0], "settings.gradle.kts").exists()
                )) {
            subDirs[0]
        } else {
            rootDir
        }

        val hasGradle = File(effectiveDir, "build.gradle").exists() ||
                File(effectiveDir, "build.gradle.kts").exists() ||
                effectiveDir.walkTopDown().any { it.name == "build.gradle" || it.name == "build.gradle.kts" }
        val hasSettings = File(effectiveDir, "settings.gradle").exists() ||
                File(effectiveDir, "settings.gradle.kts").exists() ||
                effectiveDir.walkTopDown().any { it.name == "settings.gradle" || it.name == "settings.gradle.kts" }

        if (!hasGradle || !hasSettings) {
            throw IllegalStateException("Project is missing settings.gradle / build.gradle, cannot build")
        }

        val excludedDirs = setOf(".gradle", "build", ".git", ".idea", ".kotlin", "captures", ".cxx", "apk")
        val allFiles = effectiveDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val rel = file.relativeTo(effectiveDir).invariantSeparatorsPath
                val dirs = rel.split('/').dropLast(1)
                dirs.none { it in excludedDirs } &&
                    !rel.endsWith(".apk") && !rel.endsWith(".aab") && !rel.endsWith(".zip") &&
                    !rel.endsWith(".jks") && !rel.endsWith(".keystore") && !rel.endsWith(".p12") &&
                    !rel.startsWith(".github/") &&
                    file.length() < 50L * 1024 * 1024 &&
                    rel != "local.properties" && rel != ".env"
            }.toList()

        val candidates = mutableListOf<IncrementalGitSync.SyncCandidate>()
        for (file in allFiles) {
            val relPath = file.relativeTo(effectiveDir).invariantSeparatorsPath
            val mode = if (relPath == "gradlew" || relPath.endsWith("/gradlew")) "100755" else "100644"
            candidates.add(IncrementalGitSync.SyncCandidate(relPath, file.readBytes(), mode))
        }

        val workflowAssetContent = try {
            context.assets.open("workflows/android-build.yml").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        if (workflowAssetContent.isNotBlank()) {
            candidates.add(
                IncrementalGitSync.SyncCandidate(
                    relativePath = ".github/workflows/android-build.yml",
                    bytes = workflowAssetContent.toByteArray(Charsets.UTF_8),
                    mode = "100644"
                )
            )
        }

        // 1. Get head commit & base tree SHA
        val refResponse = gitHubService.getGitRef(authHeader, owner, repo, branch)
        val headSha = refResponse.obj.sha
        val baseTreeSha = try {
            gitHubService.getCommit(authHeader, owner, repo, headSha).tree.sha
        } catch (_: Exception) {
            null
        }

        // 2. Fetch existing remote tree hashes for incremental diffing
        val remoteBlobMap = if (baseTreeSha != null) {
            try {
                val recResp = gitHubService.getGitTreeRecursive(authHeader, owner, repo, baseTreeSha, 1)
                if (recResp.isSuccessful && recResp.body()?.truncated == false) {
                    recResp.body()!!.tree
                        .filter { it.type == "blob" }
                        .associate { it.path to it.sha }
                } else emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        } else emptyMap()

        val syncPlan = IncrementalGitSync.computeDiffPlan(candidates, remoteBlobMap)
        logBuilder.append("[Sync] Incremental diff: ${syncPlan.toUpload.size} modified/new file(s), ${syncPlan.skippedUnchangedPaths.size} unchanged skipped.\n")

        if (syncPlan.toUpload.isEmpty()) {
            logBuilder.append("[Sync] Remote branch '$branch' is already up to date (0 blobs uploaded).\n")
            return
        }

        // 3. Bounded concurrent blob uploads with exponential backoff
        val uploadSemaphore = Semaphore(4)
        val treeEntries = coroutineScope {
            syncPlan.toUpload.map { candidate ->
                async(Dispatchers.IO) {
                    uploadSemaphore.withPermit {
                        val base64Content = Base64.encodeToString(candidate.bytes, Base64.NO_WRAP)
                        var blobSha: String? = null
                        var lastException: Exception? = null
                        for (attempt in 1..4) {
                            try {
                                val blobResp = gitHubService.createBlob(
                                    authHeader, owner, repo,
                                    CreateBlobRequest(content = base64Content, encoding = "base64")
                                )
                                blobSha = blobResp.sha
                                break
                            } catch (e: Exception) {
                                lastException = e
                                if (attempt < 4) delay(2000L * (1 shl (attempt - 1)))
                            }
                        }
                        if (blobSha == null) {
                            throw IllegalStateException("Failed to create blob for ${candidate.relativePath} after 4 attempts: ${lastException?.message}")
                        }
                        TreeEntry(
                            path = candidate.relativePath,
                            mode = candidate.mode,
                            type = "blob",
                            sha = blobSha
                        )
                    }
                }
            }.awaitAll()
        }

        // 4. Create Tree with base_tree to preserve unchanged files
        val treeResp = gitHubService.createTree(
            authHeader, owner, repo,
            CreateTreeRequest(tree = treeEntries, baseTree = baseTreeSha)
        )
        val newTreeSha = treeResp.sha

        // 5. Create Commit
        val commitResp = gitHubService.createCommit(
            authHeader, owner, repo,
            CreateCommitRequest(
                message = "Nexa IDE incremental sync (${treeEntries.size} file(s)): ${project.name}",
                tree = newTreeSha,
                parents = listOf(headSha)
            )
        )
        val newCommitSha = commitResp.sha

        // 6. Update Ref
        val updateRefResp = gitHubService.updateRef(
            authHeader, owner, repo,
            branch = branch,
            body = UpdateRefRequest(sha = newCommitSha, force = false)
        )

        if (!updateRefResp.isSuccessful) {
            val code = updateRefResp.code()
            if (code == 403 || code == 404) {
                throw IllegalStateException("GitHub API Error HTTP $code: Your token probably lacks the 'workflow' scope or write permissions to update reference.")
            } else {
                throw IllegalStateException("Failed to update branch reference (HTTP $code)")
            }
        }

        logBuilder.append("[Sync] Committed ${treeEntries.size} file(s). Commit SHA: $newCommitSha\n")
    }

    fun cancelCurrentBuild() {
        val runInfo = currentActiveRunInfo
        currentBuildJob?.cancel()
        if (runInfo != null) {
            scope.launch {
                try {
                    val token = SecureTokenManager.getGitHubToken(context)
                    if (!token.isNullOrBlank()) {
                        gitHubService.cancelWorkflowRun("Bearer ${token.trim()}", runInfo.first, runInfo.second, runInfo.third)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        _buildState.value = BuildProgressState.Idle
    }

    fun installApk(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "APK file not found: $filePath", Toast.LENGTH_SHORT).show()
                return
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "Allow Nexa IDE to install apps, then tap Install again", Toast.LENGTH_LONG).show()
                    val permissionIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:" + context.packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                clipData = android.content.ClipData.newRawUri("", apkUri)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareApk(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "File does not exist: $filePath", Toast.LENGTH_SHORT).show()
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share APK via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "Sharing failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.builder.BuildEngine
import com.example.data.filesystem.ProjectFileManager
import com.example.data.local.entity.BuildArtifactEntity
import com.example.data.local.entity.BuildEntity
import com.example.data.local.entity.ProjectEntity
import com.example.testing.ApkAnalysisResult
import com.example.testing.ParsedBuildError
import com.example.testing.TestResultStatus
import com.example.testing.TestSuiteReport
import com.example.testing.TestingLabEngine
import com.example.ui.theme.AmberYellow
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TestingLabScreen(
    project: ProjectEntity?,
    builds: List<BuildEntity>,
    artifacts: List<BuildArtifactEntity>,
    fileManager: ProjectFileManager,
    buildEngine: BuildEngine,
    onAskAiError: (fileName: String?, errorContext: String) -> Unit,
    onOpenEditor: (ProjectEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { TestingLabEngine(context) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Tests", "APK Analyzer", "Error Analysis", "Device Test")

    val allCheckTypes = listOf(
        "Structure",
        "Manifest",
        "Resources",
        "Dependencies",
        "Gradle Build Validation",
        "Unit Tests",
        "Instrumented Tests"
    )
    var selectedTypes by remember {
        mutableStateOf(setOf("Structure", "Manifest", "Resources", "Dependencies", "Gradle Build Validation", "Unit Tests"))
    }

    var isRunningTests by remember { mutableStateOf(false) }
    var testHistory by remember { mutableStateOf(engine.loadTestHistory()) }
    var activeReport by remember { mutableStateOf(testHistory.firstOrNull()) }

    // APK Analyzer state
    var isAnalyzingApk by remember { mutableStateOf(false) }
    var apkAnalysis by remember { mutableStateOf<ApkAnalysisResult?>(null) }
    var apkError by remember { mutableStateOf<String?>(null) }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { pickedUri ->
            isAnalyzingApk = true
            apkError = null
            coroutineScope.launch {
                val tempApk = withContext(Dispatchers.IO) {
                    val outFile = File(context.cacheDir, "inspect_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(pickedUri)?.use { inp ->
                        FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                    }
                    outFile
                }
                val res = engine.analyzeApkFile(tempApk)
                isAnalyzingApk = false
                res.fold(
                    onSuccess = { apkAnalysis = it },
                    onFailure = { apkError = it.message ?: "Failed to analyze APK" }
                )
            }
        }
    }

    // Error Analysis state
    val latestFailedBuild = remember(builds) {
        builds.firstOrNull { it.status == "FAILED" } ?: builds.firstOrNull()
    }
    var customErrorLogInput by remember(latestFailedBuild?.id) {
        mutableStateOf(latestFailedBuild?.logs ?: latestFailedBuild?.errorMessage ?: "")
    }
    var parsedErrors by remember(customErrorLogInput) {
        mutableStateOf(engine.parseGradleBuildErrors(customErrorLogInput))
    }

    val deviceInfo = remember { engine.getDeviceEnvironmentInfo() }

    fun exportReport(report: TestSuiteReport) {
        coroutineScope.launch(Dispatchers.IO) {
            val content = engine.formatReadableReport(report)
            val file = File(fileManager.downloadsDir, "test_report_${report.projectName.replace(" ", "_")}_${report.timestamp}.md")
            file.writeText(content)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved report: ${file.name}", Toast.LENGTH_SHORT).show()
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, content)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Test Report"))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .navigationBarsPadding()
            .testTag("testing_lab_screen")
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("Testing Lab", fontWeight = FontWeight.Bold)
                    Text(
                        text = project?.name ?: "No active project",
                        fontSize = 11.sp,
                        color = NeonCyan
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900, titleContentColor = Color.White)
        )

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Slate900,
            contentColor = NeonCyan,
            edgePadding = 12.dp
        ) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> {
                // Tab 0: Tests Dashboard & Execution
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Check Selection & Run Card
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Validation Suites", fontWeight = FontWeight.Bold, color = Color.White)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    allCheckTypes.forEach { check ->
                                        val selected = check in selectedTypes
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                selectedTypes = if (selected && selectedTypes.size > 1) {
                                                    selectedTypes - check
                                                } else {
                                                    selectedTypes + check
                                                }
                                            },
                                            label = { Text(check, fontSize = 11.sp) }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (project == null) {
                                                Toast.makeText(context, "Select a project first", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            isRunningTests = true
                                            coroutineScope.launch {
                                                val report = engine.runProjectValidationSuite(project, selectedTypes)
                                                activeReport = report
                                                testHistory = engine.loadTestHistory()
                                                isRunningTests = false
                                            }
                                        },
                                        enabled = !isRunningTests && project != null,
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("run_testing_lab_button")
                                    ) {
                                        if (isRunningTests) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Running...")
                                        } else {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Run Validation Suite", fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    if (testHistory.isNotEmpty()) {
                                        IconButton(onClick = {
                                            engine.clearTestHistory()
                                            testHistory = emptyList()
                                            activeReport = null
                                            Toast.makeText(context, "Test reports cleared", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Reports", tint = CoralRed)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Active Report Summary
                    activeReport?.let { report ->
                        item {
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate900),
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Latest Report: ${report.projectName}", fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("Duration: ${report.totalDurationMs} ms", fontSize = 11.sp, color = Color.LightGray)
                                        }
                                        OutlinedButton(onClick = { exportReport(report) }) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Export", fontSize = 12.sp)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TestMetricPill("Passed", report.passedCount, EmeraldGreen, Modifier.weight(1f))
                                        TestMetricPill("Failed", report.failedCount, CoralRed, Modifier.weight(1f))
                                        TestMetricPill("Skipped", report.skippedCount, AmberYellow, Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        items(report.results, key = { it.id }) { tc ->
                            val tint = when (tc.status) {
                                TestResultStatus.PASSED -> EmeraldGreen
                                TestResultStatus.FAILED -> CoralRed
                                TestResultStatus.SKIPPED -> AmberYellow
                            }
                            val icon = when (tc.status) {
                                TestResultStatus.PASSED -> Icons.Default.CheckCircle
                                TestResultStatus.FAILED -> Icons.Default.Error
                                TestResultStatus.SKIPPED -> Icons.Default.SkipNext
                            }
                            Surface(
                                color = Slate900,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(icon, contentDescription = tc.status.name, tint = tint, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(tc.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                            Text("${tc.durationMs}ms", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(tc.details, fontSize = 12.sp, color = Color.LightGray, lineHeight = 16.sp)
                                        if (!tc.fileName.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "File: ${tc.fileName}${tc.lineNumber?.let { ":$it" } ?: ""}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = NeonCyan,
                                                modifier = Modifier.clickable {
                                                    if (project != null) onOpenEditor(project)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Previous Test History
                    if (testHistory.size > 1) {
                        item {
                            Text("Test Run History", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 6.dp))
                        }
                        items(testHistory.drop(1), key = { it.id }) { old ->
                            val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(old.timestamp))
                            Surface(
                                color = Slate900,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Slate800),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { activeReport = old }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(old.projectName, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                                        Text("$dateStr • ${old.totalDurationMs}ms", fontSize = 11.sp, color = Color.Gray)
                                    }
                                    Text(
                                        text = "${old.passedCount}P / ${old.failedCount}F / ${old.skippedCount}S",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (old.failedCount == 0) EmeraldGreen else CoralRed
                                    )
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // Tab 1: Real APK Analyzer
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Real APK Inspector", fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    text = "Inspects AndroidManifest metadata, SDK targets, permissions, DEX count, native ABIs, and SHA-256 signing certificates directly from an APK file.",
                                    fontSize = 12.sp,
                                    color = Color.LightGray
                                )
                                Button(
                                    onClick = { apkPickerLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*")) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Select APK File from Device", fontWeight = FontWeight.Bold)
                                }

                                val apkArtifacts = artifacts.filter { it.fileName.endsWith(".apk", true) && File(it.localFilePath).exists() }
                                if (apkArtifacts.isNotEmpty()) {
                                    Text("Or inspect a Nexa built artifact:", fontSize = 12.sp, color = NeonCyan, fontWeight = FontWeight.SemiBold)
                                    apkArtifacts.take(3).forEach { art ->
                                        OutlinedButton(
                                            onClick = {
                                                isAnalyzingApk = true
                                                apkError = null
                                                coroutineScope.launch {
                                                    val res = engine.analyzeApkFile(File(art.localFilePath))
                                                    isAnalyzingApk = false
                                                    res.fold(
                                                        onSuccess = { apkAnalysis = it },
                                                        onFailure = { apkError = it.message }
                                                    )
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("${art.fileName} (${String.format(Locale.US, "%.2f MB", art.fileSizeBytes / (1024.0 * 1024.0))})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isAnalyzingApk) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = NeonCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Inspecting APK archive...", color = NeonCyan, fontSize = 12.sp)
                            }
                        }
                    }

                    apkError?.let { err ->
                        item {
                            Surface(
                                color = CoralRed.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(err, color = CoralRed, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }

                    apkAnalysis?.let { info ->
                        item {
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate900),
                                border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(info.fileName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                                    ApkInfoRow("Package Name", info.packageName)
                                    ApkInfoRow("Version", "${info.versionName} (code ${info.versionCode})")
                                    ApkInfoRow("Min / Target SDK", "${info.minSdk ?: "?"} / ${info.targetSdk ?: "?"}")
                                    ApkInfoRow("File Size", String.format(Locale.US, "%.2f MB (%d bytes)", info.fileSizeBytes / (1024.0 * 1024.0), info.fileSizeBytes))
                                    ApkInfoRow("Debuggable", if (info.isDebuggable) "Yes (Debug)" else "No (Release)")
                                    ApkInfoRow("DEX / Resources", "${info.dexFileCount} DEX file(s) • ${info.resourceFileCount} res entries")
                                    ApkInfoRow("Native ABIs", if (info.nativeAbis.isEmpty()) "None (Pure JVM/DEX)" else info.nativeAbis.joinToString(", "))

                                    if (info.signingSha256Fingerprints.isNotEmpty()) {
                                        Text("Signing SHA-256:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                                        info.signingSha256Fingerprints.forEach { fp ->
                                            Text(fp, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.LightGray)
                                        }
                                    }

                                    if (info.warnings.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        info.warnings.forEach { w ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Warning, contentDescription = null, tint = AmberYellow, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(w, fontSize = 11.sp, color = AmberYellow)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Declared Permissions (${info.permissions.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    if (info.permissions.isEmpty()) {
                                        Text("No permissions declared.", fontSize = 11.sp, color = Color.Gray)
                                    } else {
                                        info.permissions.forEach { perm ->
                                            Text("• $perm", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.LightGray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                // Tab 2: Gradle Error Analysis + AI Explanation
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Gradle Build Error Analyzer", fontWeight = FontWeight.Bold, color = Color.White)
                                OutlinedTextField(
                                    value = customErrorLogInput,
                                    onValueChange = { customErrorLogInput = it },
                                    label = { Text("Build Log / Compiler Error Output") },
                                    placeholder = { Text("Paste real Gradle/Kotlin/Java build logs or inspect your latest build log...") },
                                    minLines = 4,
                                    maxLines = 8,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = { parsedErrors = engine.parseGradleBuildErrors(customErrorLogInput) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Parse Compiler Errors")
                                }
                            }
                        }
                    }

                    if (parsedErrors.isEmpty()) {
                        item {
                            Surface(
                                color = Slate900,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Slate800),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (customErrorLogInput.isBlank()) "No build log loaded. Run a build or paste a Gradle log above to inspect errors." else "No standard Kotlin/Java/Gradle compiler error lines detected in the provided log.",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    } else {
                        items(parsedErrors) { err ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate900),
                                border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!err.fileName.isNullOrBlank()) {
                                        Text(
                                            text = "${err.fileName}${err.lineNumber?.let { " : Line $it" } ?: ""}",
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = NeonCyan,
                                            fontSize = 12.sp,
                                            modifier = Modifier.clickable {
                                                if (project != null) onOpenEditor(project)
                                            }
                                        )
                                    }
                                    Text(err.explanation, fontSize = 13.sp, color = Color.White)
                                    Text(err.rawMessage, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CoralRed)
                                    OutlinedButton(
                                        onClick = { onAskAiError(err.fileName, err.rawMessage) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Explain & Fix with Nexa AI", color = NeonPurple, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                // Tab 3: Device Testing & Honest Environment Report
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = NeonCyan)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Device & Harness Availability", fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                ApkInfoRow("Device Model", deviceInfo.deviceModel)
                                ApkInfoRow("Android OS", "Android ${deviceInfo.androidVersion} (API ${deviceInfo.sdkInt})")
                                ApkInfoRow("Supported ABIs", deviceInfo.abis)
                                ApkInfoRow("Install Permission", if (deviceInfo.canInstallPackages) "Granted" else "Requires User Approval")
                                ApkInfoRow("External ADB / Emulator", "Not connected")
                                Text(deviceInfo.statusNote, fontSize = 12.sp, color = Color.LightGray, lineHeight = 16.sp)
                            }
                        }
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("On-Device APK Install Testing", fontWeight = FontWeight.Bold, color = Color.White)
                                val localApks = artifacts.filter { it.fileName.endsWith(".apk", true) && File(it.localFilePath).exists() }
                                if (localApks.isEmpty()) {
                                    Text(
                                        text = "No locally downloaded APK artifacts found. Build an APK first to test installation on this device.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                } else {
                                    localApks.forEach { art ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(art.fileName, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                                                Text("Verified local artifact", fontSize = 11.sp, color = EmeraldGreen)
                                            }
                                            Button(
                                                onClick = { buildEngine.installApk(art.localFilePath) },
                                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                                            ) {
                                                Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Install & Test", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestMetricPill(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$count", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
            Text(label, fontSize = 11.sp, color = Color.LightGray)
        }
    }
}

@Composable
private fun ApkInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(0.42f))
        Text(value, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.58f))
    }
}

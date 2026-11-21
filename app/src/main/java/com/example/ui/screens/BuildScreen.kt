package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.builder.BengaliBuildErrorSummary
import com.example.builder.BuildErrorAnalyzer
import com.example.builder.BuildProgressState
import com.example.data.local.entity.BuildArtifactEntity
import com.example.data.local.entity.BuildEntity
import com.example.data.local.entity.GitHubConnectionEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.security.SecureTokenManager
import com.example.ui.components.ReleaseSigningDialog
import com.example.ui.components.StatusChip
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(
    projects: List<ProjectEntity>,
    selectedProject: ProjectEntity?,
    onSelectProject: (ProjectEntity) -> Unit,
    buildState: BuildProgressState,
    latestBuild: BuildEntity?,
    latestArtifact: BuildArtifactEntity?,
    githubConnection: GitHubConnectionEntity?,
    onStartBuild: (
        project: ProjectEntity,
        buildType: String,
        jdk: String,
        gradle: String,
        compileSdk: String,
        clean: Boolean,
        versionCode: Int,
        versionName: String,
        enableMinify: Boolean,
        moduleName: String
    ) -> Unit,
    onCancelBuild: () -> Unit,
    onInstallApk: (String) -> Unit,
    onShareApk: (String) -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToGitHub: () -> Unit,
    onCustomizeWebapp: ((ProjectEntity) -> Unit)? = null,
    onAiFixAndRebuild: ((project: ProjectEntity, errorSummary: BengaliBuildErrorSummary, buildType: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Build Configuration States
    var selectedBuildType by remember { mutableStateOf("DEBUG") } // DEBUG, RELEASE, BUNDLE
    var selectedModule by remember { mutableStateOf(":app") }
    var selectedJdk by remember { mutableStateOf("17") }
    var selectedGradle by remember { mutableStateOf("8.10.2") }
    var selectedCompileSdk by remember { mutableStateOf("35") }
    var cleanBuild by remember { mutableStateOf(true) }
    var enableMinify by remember { mutableStateOf(false) }

    val savedHtmlConfig = remember(selectedProject?.id, selectedProject?.lastModifiedAt) {
        val proj = selectedProject
        if (proj != null) {
            val f = java.io.File(proj.rootPath, com.example.data.filesystem.HtmlBuildConfig.CONFIG_FILE_NAME)
            if (f.exists()) {
                com.example.data.filesystem.HtmlBuildConfig.fromJsonString(f.readText(), proj.name, proj.packageName)
            } else null
        } else null
    }
    var customPackageName by remember(selectedProject?.id, selectedProject?.lastModifiedAt) {
        mutableStateOf(savedHtmlConfig?.packageName ?: selectedProject?.packageName ?: "com.example.app")
    }
    var customVersionName by remember(selectedProject?.id, selectedProject?.lastModifiedAt) {
        mutableStateOf(savedHtmlConfig?.versionName ?: "1.0.0")
    }
    var customVersionCode by remember(selectedProject?.id, selectedProject?.lastModifiedAt) {
        mutableStateOf((savedHtmlConfig?.versionCode ?: 1).toString())
    }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showSigningSecretDialog by remember { mutableStateOf(false) }
    var signingRefreshCount by remember { mutableIntStateOf(0) }
    val savedSigningMeta = remember(signingRefreshCount, githubConnection?.activeRepo) {
        SecureTokenManager.getReleaseSigningMetadata(context)
    }

    // Log Filter
    var logFilter by remember { mutableStateOf("ALL") } // ALL, ERRORS, TASKS
    var logSearchQuery by remember { mutableStateOf("") }

    var projectDropdownExpanded by remember { mutableStateOf(false) }
    var showMissingGitHubDialog by remember { mutableStateOf(false) }

    val logListState = rememberLazyListState()

    val hasGitHubConnected = githubConnection != null &&
            githubConnection.token.isNotBlank() &&
            !githubConnection.activeRepo.isNullOrBlank()

    val activeState = buildState as? BuildProgressState.Active
    val isBuilding = activeState != null

    // Auto-scroll logs when updated
    LaunchedEffect(activeState?.liveLogs) {
        if (activeState?.liveLogs?.isNotEmpty() == true) {
            logListState.animateScrollToItem((logListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .testTag("build_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Build Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Cloud APK/AAB Builder",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Production CI/CD powered by GitHub Actions",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonCyan
                            )
                        }

                        IconButton(onClick = onNavigateToHistory) {
                            Icon(Icons.Default.History, contentDescription = "Build History", tint = NeonCyan)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Project Selector Dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { projectDropdownExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("select_project_dropdown"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedProject?.let { "${it.name} (${it.language})" } ?: "Select Project to Build",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = projectDropdownExpanded,
                            onDismissRequest = { projectDropdownExpanded = false }
                        ) {
                            projects.forEach { proj ->
                                DropdownMenuItem(
                                    text = { Text("${proj.name} • ${proj.packageName}") },
                                    onClick = {
                                        onSelectProject(proj)
                                        customPackageName = proj.packageName
                                        projectDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // GitHub Connection Requirement Banner (if not configured)
        if (!hasGitHubConnected) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AmberYellow.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmberYellow.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AmberYellow, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GitHub Token & Repository Required",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = AmberYellow
                            )
                        }

                        Text(
                            text = "Nexa IDE compiles real Android APKs using GitHub Actions runners. Without connecting your GitHub Personal Access Token (PAT) and selecting an active repository, APK builds cannot be created.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            lineHeight = 18.sp
                        )

                        Button(
                            onClick = onNavigateToGitHub,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AmberYellow, contentColor = Color.Black),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect GitHub Account", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Build Configuration Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isWebappSelected = selectedProject != null && (
                        selectedProject.language.equals("Webapp", ignoreCase = true) ||
                            selectedProject.templateType.contains("HTML", ignoreCase = true)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Build Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        TextButton(onClick = {
                            if (isWebappSelected && selectedProject != null && onCustomizeWebapp != null) {
                                onCustomizeWebapp(selectedProject)
                            } else {
                                showConfigDialog = true
                            }
                        }) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isWebappSelected) "Webapp Studio" else "Customize", fontSize = 12.sp, color = NeonCyan)
                        }
                    }

                    if (isWebappSelected && selectedProject != null && onCustomizeWebapp != null) {
                        OutlinedButton(
                            onClick = { onCustomizeWebapp(selectedProject) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = androidx.compose.ui.res.stringResource(com.example.R.string.webapp_customize_banner),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeonCyan
                            )
                        }
                    }

                    // Build Variant selection chips
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedBuildType == "DEBUG",
                            onClick = { selectedBuildType = "DEBUG" },
                            label = { Text("Debug APK") }
                        )
                        FilterChip(
                            selected = selectedBuildType == "RELEASE",
                            onClick = { selectedBuildType = "RELEASE" },
                            label = { Text("Release APK") }
                        )
                        FilterChip(
                            selected = selectedBuildType == "BUNDLE",
                            onClick = { selectedBuildType = "BUNDLE" },
                            label = { Text("App Bundle (AAB)") }
                        )
                    }

                    // Package metadata summary pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Slate800,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("Module", fontSize = 10.sp, color = Color.Gray)
                                Text(selectedModule, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Surface(
                            color = Slate800,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("Package ID", fontSize = 10.sp, color = Color.Gray)
                                Text(customPackageName.takeLast(16), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NeonCyan, maxLines = 1)
                            }
                        }
                        Surface(
                            color = Slate800,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("Version", fontSize = 10.sp, color = Color.Gray)
                                Text("v$customVersionName ($customVersionCode)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                            }
                        }
                    }

                    // SDK & Environment Info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("JDK Runtime", fontSize = 11.sp, color = Color.Gray)
                            Text("OpenJDK $selectedJdk", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White)
                        }
                        Column {
                            Text("Gradle Engine", fontSize = 11.sp, color = Color.Gray)
                            Text("v$selectedGradle", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White)
                        }
                        Column {
                            Text("Compile SDK", fontSize = 11.sp, color = Color.Gray)
                            Text("API $selectedCompileSdk", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White)
                        }
                    }

                    // Signing status & interactive Keystore config
                    val activeRepoName = githubConnection?.activeRepo
                    val hasSyncedKeystore = savedSigningMeta != null && !activeRepoName.isNullOrBlank() && savedSigningMeta.uploadedRepo == activeRepoName
                    Surface(
                        color = Slate950,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSigningSecretDialog = true }
                            .testTag("release_signing_card")
                            .padding(vertical = 4.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when {
                                selectedBuildType == "DEBUG" -> Slate800
                                hasSyncedKeystore -> EmeraldGreen.copy(alpha = 0.6f)
                                savedSigningMeta != null -> NeonCyan.copy(alpha = 0.6f)
                                else -> AmberYellow.copy(alpha = 0.5f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when {
                                    selectedBuildType == "DEBUG" || hasSyncedKeystore -> Icons.Default.VerifiedUser
                                    savedSigningMeta != null -> Icons.Default.Shield
                                    else -> Icons.Default.Key
                                },
                                contentDescription = null,
                                tint = when {
                                    selectedBuildType == "DEBUG" || hasSyncedKeystore -> EmeraldGreen
                                    savedSigningMeta != null -> NeonCyan
                                    else -> AmberYellow
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        savedSigningMeta != null -> "Release Keystore: ${savedSigningMeta.alias} (${savedSigningMeta.sourceType})"
                                        selectedBuildType == "DEBUG" -> "Debug Signing Active (Tap to setup Release Keystore)"
                                        else -> "Release Signing: Fallback Test Key (Tap to setup JKS/P12)"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = when {
                                        hasSyncedKeystore -> "Encrypted secrets synced to ${savedSigningMeta?.uploadedRepo}"
                                        savedSigningMeta != null -> "Saved locally — tap to upload sealed secrets to GitHub"
                                        else -> "Generate or import .jks/.p12 keystore for Play Store signing"
                                    },
                                    fontSize = 10.sp,
                                    color = if (hasSyncedKeystore) EmeraldGreen else NeonCyan
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }

                    // Clean Project Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Clean project before build (Recommended)",
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                        Switch(
                            checked = cleanBuild,
                            onCheckedChange = { cleanBuild = it }
                        )
                    }

                    // R8 Minify & Resource Shrink Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "R8 Minify & Shrink Resources",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.LightGray
                            )
                            Text(
                                text = "Shrinks & obfuscates Release APK / AAB bytecode",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = enableMinify,
                            onCheckedChange = { enableMinify = it },
                            modifier = Modifier.testTag("minify_switch")
                        )
                    }

                    // Build Trigger Action
                    Button(
                        onClick = {
                            if (!hasGitHubConnected) {
                                showMissingGitHubDialog = true
                            } else {
                                selectedProject?.let { proj ->
                                    val resolvedCode = customVersionCode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                    val resolvedName = customVersionName.ifBlank { "1.0.0" }
                                    onStartBuild(
                                        proj,
                                        selectedBuildType,
                                        selectedJdk,
                                        selectedGradle,
                                        selectedCompileSdk,
                                        cleanBuild,
                                        resolvedCode,
                                        resolvedName,
                                        enableMinify,
                                        selectedModule.ifBlank { ":app" }
                                    )
                                }
                            }
                        },
                        enabled = selectedProject != null && !isBuilding,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("start_build_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasGitHubConnected) NeonPurple else AmberYellow,
                            contentColor = if (hasGitHubConnected) Color.White else Color.Black
                        )
                    ) {
                        if (isBuilding) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Building on GitHub Actions...")
                        } else if (!hasGitHubConnected) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect GitHub Account to Build", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("START $selectedBuildType BUILD", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Active Build Progress Card (Real-time Timeline & Actions)
        activeState?.let { active ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Building: ${active.projectName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Status: ${active.status}",
                                    fontSize = 11.sp,
                                    color = NeonCyan
                                )
                            }

                            Row {
                                active.runHtmlUrl?.let { url ->
                                    IconButton(onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(intent)
                                    }) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = "View Run", tint = NeonCyan)
                                    }
                                }
                                IconButton(onClick = onCancelBuild) {
                                    Icon(Icons.Default.Cancel, contentDescription = "Cancel Build", tint = CoralRed)
                                }
                            }
                        }

                        // Granular Step Indicator
                        Text(
                            text = "Current Step: ${active.currentStep}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        LinearProgressIndicator(
                            progress = { active.progressPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = NeonCyan,
                            trackColor = Slate800
                        )

                        Text(
                            text = "${(active.progressPercent * 100).toInt()}% completed",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        }

        // Bengali Root-Cause Error Card (when latest build failed)
        if (!isBuilding && latestBuild?.status == "FAILED") {
            item {
                val errSummary = remember(latestBuild.id, latestBuild.errorMessage, latestBuild.logs) {
                    BuildErrorAnalyzer.analyzeError(
                        rawError = latestBuild.errorMessage.orEmpty(),
                        rawLogs = latestBuild.logs
                    )
                }
                val targetProj = projects.find { it.id == latestBuild.projectId } ?: selectedProject
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("build_error_summary_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CoralRed.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CoralRed, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errSummary.titleBn,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CoralRed
                                )
                            }
                            if (latestBuild.durationSeconds > 0) {
                                Surface(
                                    color = Slate800,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = formatBuildDuration(latestBuild.durationSeconds),
                                        fontSize = 10.sp,
                                        color = Color.LightGray,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = errSummary.causeBn,
                            fontSize = 12.sp,
                            color = Color.White,
                            lineHeight = 18.sp
                        )

                        Surface(
                            color = Slate950,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "সমাধানের পরামর্শ: ${errSummary.fixSuggestionBn}",
                                    fontSize = 11.sp,
                                    color = NeonCyan,
                                    lineHeight = 16.sp
                                )
                                errSummary.offendingFileHint?.let { fileHint ->
                                    Text(
                                        text = "ফাইল: $fileHint${errSummary.lineNumber?.let { " • লাইন $it" } ?: ""}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (targetProj != null && onAiFixAndRebuild != null) {
                                Button(
                                    onClick = { onAiFixAndRebuild(targetProj, errSummary, latestBuild.buildType) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("ai_fix_rebuild_button"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Color.Black)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("AI দিয়ে ঠিক করে আবার বিল্ড", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (targetProj != null && hasGitHubConnected) {
                                OutlinedButton(
                                    onClick = {
                                        val resolvedCode = customVersionCode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                        val resolvedName = customVersionName.ifBlank { "1.0.0" }
                                        onStartBuild(
                                            targetProj,
                                            latestBuild.buildType,
                                            selectedJdk,
                                            selectedGradle,
                                            selectedCompileSdk,
                                            cleanBuild,
                                            resolvedCode,
                                            resolvedName,
                                            enableMinify,
                                            selectedModule.ifBlank { ":app" }
                                        )
                                    },
                                    modifier = Modifier.testTag("retry_build_button"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Retry", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Compiler Logs Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Compiler Logs", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = {
                                val logsToCopy = activeState?.liveLogs ?: latestBuild?.logs ?: ""
                                clipboardManager.setText(AnnotatedString(logsToCopy))
                                Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val rawLogs = activeState?.liveLogs ?: latestBuild?.logs ?: "No recent build logs available."
                    val filteredLogs = remember(rawLogs, logFilter) {
                        val lines = rawLogs.lines()
                        when (logFilter) {
                            "ERRORS" -> lines.filter { it.contains("error", ignoreCase = true) || it.contains("FAILED") || it.contains("e: ") }.joinToString("\n")
                            "TASKS" -> lines.filter { it.contains("> Task") || it.contains("BUILD") }.joinToString("\n")
                            else -> rawLogs
                        }
                    }

                    // Filter chips
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = logFilter == "ALL",
                            onClick = { logFilter = "ALL" },
                            label = { Text("All", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = logFilter == "ERRORS",
                            onClick = { logFilter = "ERRORS" },
                            label = { Text("Errors Only", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = logFilter == "TASKS",
                            onClick = { logFilter = "TASKS" },
                            label = { Text("Gradle Tasks", fontSize = 11.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = Color(0xFF0d1117),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        val horizScroll = rememberScrollState()
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(horizScroll)
                                .padding(10.dp)
                        ) {
                            item {
                                Text(
                                    text = filteredLogs.ifBlank { "No logs match the current filter." },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFc9d1d9),
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Latest Verified Artifact Card (with Integrity Check & Signing Status)
        latestArtifact?.let { artifact ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldGreen)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Verified Artifact Ready", fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(
                                        text = "${artifact.fileName} (${String.format(Locale.US, "%.2f MB", artifact.fileSizeBytes / (1024.0 * 1024.0))})",
                                        fontSize = 11.sp,
                                        color = EmeraldGreen
                                    )
                                }
                            }
                            Surface(
                                color = EmeraldGreen.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "ZIP VERIFIED",
                                    color = EmeraldGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = buildString {
                                append("Package: ${artifact.packageName} • v${artifact.versionName}")
                                if (latestBuild != null && latestBuild.status == "SUCCESS" && latestBuild.durationSeconds > 0) {
                                    append(" • Build time: ${formatBuildDuration(latestBuild.durationSeconds)}")
                                }
                            },
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )

                        val isAab = artifact.fileName.endsWith(".aab", ignoreCase = true)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!isAab) {
                                Button(
                                    onClick = { onInstallApk(artifact.localFilePath) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Color.Black)
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Install APK", fontWeight = FontWeight.Bold)
                                }
                            }

                            Button(
                                onClick = { onShareApk(artifact.localFilePath) },
                                modifier = Modifier.weight(1f),
                                colors = if (isAab) {
                                    ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                                } else {
                                    ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = Color.White)
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isAab) "Share .AAB (Play Console)" else "Share", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Dialog: Customize Build Config (Module, Package Name, Version Name, Code)
    if (showConfigDialog) {
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("Customize Build Properties", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = selectedModule,
                        onValueChange = { selectedModule = it.trim().ifBlank { ":app" } },
                        label = { Text("Gradle Module (e.g. :app)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customPackageName,
                        onValueChange = { customPackageName = it.trim() },
                        label = { Text("Package ID / Application ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customVersionName,
                        onValueChange = { customVersionName = it.trim() },
                        label = { Text("Version Name (e.g. 1.0.0)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customVersionCode,
                        onValueChange = { customVersionCode = it.filter { c -> c.isDigit() } },
                        label = { Text("Version Code (e.g. 1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showConfigDialog = false }) { Text("Done") }
            }
        )
    }

    // Modal Dialog: Release Signing Keystore & Encrypted GitHub Secret Upload
    if (showSigningSecretDialog) {
        ReleaseSigningDialog(
            activeRepo = githubConnection?.activeRepo,
            hasGitHubToken = !githubConnection?.token.isNullOrBlank() || SecureTokenManager.hasGitHubToken(context),
            onDismiss = { showSigningSecretDialog = false },
            onSigningUpdated = { signingRefreshCount++ }
        )
    }

    if (showMissingGitHubDialog) {
        AlertDialog(
            onDismissRequest = { showMissingGitHubDialog = false },
            title = { Text("GitHub Account Required", fontWeight = FontWeight.Bold) },
            text = { Text("Connect your GitHub PAT (with 'repo' & 'workflow' scopes) and select an active repository to run cloud builds.") },
            confirmButton = {
                Button(onClick = {
                    showMissingGitHubDialog = false
                    onNavigateToGitHub()
                }) { Text("Connect GitHub") }
            },
            dismissButton = {
                TextButton(onClick = { showMissingGitHubDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatBuildDuration(totalSeconds: Long): String {
    if (totalSeconds < 60L) return "${totalSeconds}s"
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return "${mins}m ${secs}s"
}

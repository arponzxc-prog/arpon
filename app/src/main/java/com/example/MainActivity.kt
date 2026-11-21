package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.example.ui.components.HtmlBuildCustomizeDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.builder.BengaliBuildErrorSummary
import com.example.builder.BuildEngine
import com.example.builder.BuildProgressState
import com.example.data.ai.AiCodeProposal
import com.example.data.ai.AiModelConfig
import com.example.data.ai.GeminiAiService
import com.example.data.filesystem.ProjectFileManager
import com.example.data.local.NexaDatabase
import com.example.data.local.entity.BuildArtifactEntity
import com.example.data.local.entity.BuildEntity
import com.example.data.local.entity.GitHubConnectionEntity
import com.example.data.local.entity.NotificationEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.preferences.NexaPreferences
import com.example.data.preferences.NexaPreferencesRepository
import com.example.data.security.SecureTokenManager
import com.example.ui.components.AiDiffDialog
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.AddNewModelScreen
import com.example.ui.screens.AiAssistantScreen
import com.example.ui.screens.ApiSettingsScreen
import com.example.ui.screens.AssetStudioScreen
import com.example.ui.screens.BuildHistoryScreen
import com.example.ui.screens.BuildScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.GitHubAccountManagerScreen
import com.example.ui.screens.GitHubScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NotificationsScreen
import com.example.ui.screens.PreviewScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.screens.TestingLabScreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NexaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen {
    HOME,
    PROJECTS,
    BUILD,
    EDITOR,
    BUILD_HISTORY,
    GITHUB,
    GITHUB_ACCOUNT,
    DOWNLOADS,
    NOTIFICATIONS,
    SETTINGS,
    API_SETTINGS,
    ADD_AI_MODEL,
    ABOUT,
    AI_ASSISTANT,
    PREVIEW,
    TERMINAL,
    TESTING_LAB,
    ASSET_STUDIO
}

class MainActivity : ComponentActivity() {

    private lateinit var database: NexaDatabase
    private lateinit var preferencesRepo: NexaPreferencesRepository
    private lateinit var fileManager: ProjectFileManager
    private lateinit var buildEngine: BuildEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = NexaDatabase.getInstance(this)
        preferencesRepo = NexaPreferencesRepository(this)
        fileManager = ProjectFileManager(this)
        buildEngine = BuildEngine.getInstance(this, database, fileManager)

        // Security: Disable WebView debugging in release builds, enable only in debug builds
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }

        setContent {
            val preferences by preferencesRepo.preferencesFlow.collectAsState(initial = NexaPreferences())
            val isDark = when (preferences.themeMode) {
                "LIGHT" -> false
                "DARK" -> true
                else -> true
            }

            NexaTheme(darkTheme = isDark) {
                NexaMainApp(
                    database = database,
                    preferencesRepo = preferencesRepo,
                    fileManager = fileManager,
                    buildEngine = buildEngine,
                    preferences = preferences
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexaMainApp(
    database: NexaDatabase,
    preferencesRepo: NexaPreferencesRepository,
    fileManager: ProjectFileManager,
    buildEngine: BuildEngine,
    preferences: NexaPreferences
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var activeProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var editingAiModel by remember { mutableStateOf<AiModelConfig?>(null) }
    var apiRefreshSignal by remember { mutableStateOf(0) }
    var returnScreenFromAddModel by remember { mutableStateOf(Screen.API_SETTINGS) }
    var aiErrorFileName by remember { mutableStateOf<String?>(null) }
    var aiInitialPrompt by remember { mutableStateOf<String?>(null) }

    // Live Database flows
    val projects by database.projectDao().getAllProjects().collectAsState(initial = emptyList())
    val builds by database.buildDao().getAllBuilds().collectAsState(initial = emptyList())
    val artifacts by database.buildArtifactDao().getAllArtifacts().collectAsState(initial = emptyList())
    val githubConnection by database.gitHubConnectionDao().getConnectionFlow().collectAsState(initial = null)
    val notifications by database.notificationDao().getAllNotifications().collectAsState(initial = emptyList())
    val unreadNotificationsCount by database.notificationDao().getUnreadCount().collectAsState(initial = 0)
    val successfulBuildsCount by database.buildDao().getSuccessfulBuildsCount().collectAsState(initial = 0)
    val failedBuildsCount by database.buildDao().getFailedBuildsCount().collectAsState(initial = 0)

    val buildState by buildEngine.buildState.collectAsState(initial = BuildProgressState.Idle)

    // Storage usage
    var storageUsageBytes by remember { mutableStateOf(0L) }
    fun refreshStorage() {
        storageUsageBytes = fileManager.getTotalStorageUsageBytes()
    }

    // Auto-seed initial project on first launch and migrate any legacy plain tokens
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            refreshStorage()
            // Keystore Token Security Migration: remove plaintext token from Room
            val conn = database.gitHubConnectionDao().getConnection()
            if (conn != null && conn.token.isNotBlank() && !conn.token.startsWith("SECURE_") && !conn.token.contains("••••")) {
                com.example.data.security.SecureTokenManager.migrateLegacyTokenIfNeeded(context, conn.token)
                database.gitHubConnectionDao().saveConnection(conn.copy(token = "SECURE_KEYSTORE_ENCRYPTED"))
            }
        }
    }

    LaunchedEffect(projects) {
        if (projects.isEmpty()) {
            withContext(Dispatchers.IO) {
                val starterProject = fileManager.createProject(
                    name = "QuickStart App",
                    packageName = "com.example.quickstart",
                    templateType = "Kotlin Android Project",
                    language = "Kotlin"
                )
                database.projectDao().insertProject(starterProject)
                database.notificationDao().insertNotification(
                    NotificationEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        title = "Welcome to Nexa IDE!",
                        message = "Created initial QuickStart App template for you. Open it in the editor to start coding.",
                        type = "SYSTEM"
                    )
                )
            }
        }
        if (activeProject == null && projects.isNotEmpty()) {
            activeProject = projects.first()
        }
    }

    // ZIP Import File Picker
    val importZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                        val imported = fileManager.importProjectFromZip(inputStream, "ImportedApp_${System.currentTimeMillis() % 1000}")
                        if (imported != null) {
                            database.projectDao().insertProject(imported)
                            withContext(Dispatchers.Main) {
                                activeProject = imported
                                Toast.makeText(context, "Project imported successfully!", Toast.LENGTH_SHORT).show()
                                currentScreen = Screen.PROJECTS
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to import ZIP: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Proper Android Back-Button Handling
    BackHandler(enabled = drawerState.isOpen || currentScreen != Screen.HOME) {
        if (drawerState.isOpen) {
            coroutineScope.launch { drawerState.close() }
        } else {
            currentScreen = when (currentScreen) {
                Screen.ADD_AI_MODEL -> {
                    editingAiModel = null
                    returnScreenFromAddModel
                }
                Screen.API_SETTINGS -> Screen.SETTINGS
                Screen.GITHUB_ACCOUNT -> Screen.GITHUB
                else -> Screen.HOME
            }
        }
    }

    var htmlProjectToConfigure by remember { mutableStateOf<ProjectEntity?>(null) }
    val aiService = remember { GeminiAiService(context) }
    var isGeneratingAiBuildFix by remember { mutableStateOf(false) }
    var pendingAiBuildFixProposal by remember { mutableStateOf<Triple<ProjectEntity, File, AiCodeProposal>?>(null) }
    var pendingAiFixBuildType by remember { mutableStateOf("DEBUG") }

    fun resolveTargetFileForBuildFix(project: ProjectEntity, fileHint: String?): File? {
        val root = File(project.rootPath)
        if (!root.exists()) return null
        val allSourceFiles = root.walkTopDown()
            .onEnter { dir -> dir.name !in setOf(".git", "build", ".gradle", ".nexa") }
            .filter { it.isFile }
            .toList()
        if (!fileHint.isNullOrBlank()) {
            val exactRel = File(root, fileHint)
            if (exactRel.exists() && exactRel.isFile) return exactRel
            val hintName = File(fileHint).name
            allSourceFiles.firstOrNull { it.name.equals(hintName, ignoreCase = true) }?.let { return it }
        }
        return allSourceFiles.firstOrNull { it.name == "MainActivity.kt" || it.name == "MainActivity.java" }
            ?: allSourceFiles.firstOrNull { it.name == "index.html" }
            ?: allSourceFiles.firstOrNull { it.absolutePath.endsWith("app/build.gradle.kts") || it.absolutePath.endsWith("app/build.gradle") }
            ?: allSourceFiles.firstOrNull()
    }

    fun triggerAiBuildFix(proj: ProjectEntity, summary: BengaliBuildErrorSummary, buildType: String) {
        if (aiService.getEffectiveApiKey().isNullOrBlank()) {
            Toast.makeText(context, "Configure an AI Model / API Key in Settings -> API first.", Toast.LENGTH_LONG).show()
            currentScreen = Screen.API_SETTINGS
            return
        }
        isGeneratingAiBuildFix = true
        pendingAiFixBuildType = buildType
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val targetFile = resolveTargetFileForBuildFix(proj, summary.offendingFileHint)
                if (targetFile == null) {
                    withContext(Dispatchers.Main) {
                        isGeneratingAiBuildFix = false
                        Toast.makeText(context, "No editable source file found in ${proj.name}.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val originalCode = targetFile.readText()
                val relPath = targetFile.relativeTo(File(proj.rootPath)).path
                val proposal = aiService.fixErrors(
                    code = originalCode,
                    errorMessage = summary.aiPrompt,
                    fileName = relPath
                )
                withContext(Dispatchers.Main) {
                    isGeneratingAiBuildFix = false
                    pendingAiBuildFixProposal = Triple(proj, targetFile, proposal)
                }
            } catch (e: Exception) {
                val safeMsg = SecureTokenManager.redactSecrets(e.message ?: "AI analysis failed")
                withContext(Dispatchers.Main) {
                    isGeneratingAiBuildFix = false
                    Toast.makeText(context, "AI Fix error: $safeMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun isWebappProject(proj: ProjectEntity?): Boolean {
        return proj != null && (
            proj.language.equals("Webapp", ignoreCase = true) ||
                proj.templateType.contains("HTML", ignoreCase = true)
            )
    }
    val handleQuickBuild = { proj: ProjectEntity ->
        if (isWebappProject(proj)) {
            htmlProjectToConfigure = proj
        } else {
            activeProject = proj
            currentScreen = Screen.BUILD
        }
    }

    // Build-related dialogs: must be composed on EVERY screen (incl. full-screen Editor,
    // which returns early), otherwise the Webapp customize dialog never appears from the Editor.
    val buildDialogs: @Composable () -> Unit = {
    htmlProjectToConfigure?.let { proj ->
        val projectBuildCount = builds.count { it.projectId == proj.id }
        val savedCfg = remember(proj.id, proj.lastModifiedAt) { fileManager.loadHtmlBuildConfig(proj) }
        val suggestedVersionCode = maxOf(
            savedCfg.versionCode + if (projectBuildCount > 0) 1 else 0,
            projectBuildCount + 1
        )
        HtmlBuildCustomizeDialog(
            project = proj,
            fileManager = fileManager,
            suggestedVersionCode = suggestedVersionCode,
            onDismiss = { htmlProjectToConfigure = null },
            onBuildWithConfig = { config, logoUri, splashUri, startBuildImmediately ->
                coroutineScope.launch(Dispatchers.IO) {
                    val result = fileManager.updateHtmlProjectConfig(
                        project = proj,
                        config = config,
                        logoUri = logoUri,
                        splashUri = splashUri
                    )
                    if (result.isFailure) {
                        val errMsg = result.exceptionOrNull()?.message ?: "Failed to update Webapp configuration."
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    val updatedProj = result.getOrThrow()
                    database.projectDao().updateProject(updatedProj)
                    refreshStorage()
                    val hasGitHub = githubConnection != null &&
                        githubConnection?.token?.isNotBlank() == true &&
                        !githubConnection?.activeRepo.isNullOrBlank()
                    withContext(Dispatchers.Main) {
                        activeProject = updatedProj
                        htmlProjectToConfigure = null
                        currentScreen = Screen.BUILD
                        if (startBuildImmediately) {
                            if (hasGitHub) {
                                coroutineScope.launch {
                                    buildEngine.triggerBuild(updatedProj)
                                    refreshStorage()
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    "Webapp config saved. Connect GitHub repository to start cloud build.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(context, "Webapp configuration saved.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    if (isGeneratingAiBuildFix) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Nexa AI Error Solver", fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = NeonCyan, strokeWidth = 2.5.dp)
                    Text("বিল্ড লগ বিশ্লেষণ করে ফিক্স তৈরি করা হচ্ছে...")
                }
            },
            confirmButton = {}
        )
    }

    pendingAiBuildFixProposal?.let { (targetProj, targetFile, proposal) ->
        AiDiffDialog(
            proposal = proposal,
            aiService = aiService,
            onAccept = { proposedCode ->
                coroutineScope.launch(Dispatchers.IO) {
                    fileManager.writeFileContent(targetFile.absolutePath, proposedCode)
                    val updatedProj = targetProj.copy(lastModifiedAt = System.currentTimeMillis())
                    database.projectDao().updateProject(updatedProj)
                    refreshStorage()
                    withContext(Dispatchers.Main) {
                        activeProject = updatedProj
                        pendingAiBuildFixProposal = null
                        currentScreen = Screen.BUILD
                        Toast.makeText(context, "Fix applied! Starting rebuild...", Toast.LENGTH_SHORT).show()
                        buildEngine.triggerBuild(updatedProj, pendingAiFixBuildType)
                    }
                }
            },
            onDismiss = { pendingAiBuildFixProposal = null }
        )
    }
    }

    // If on full-screen Editor, show dedicated editor without bottom bar or drawer
    if (currentScreen == Screen.EDITOR && activeProject != null) {
        EditorScreen(
            project = activeProject!!,
            fileManager = fileManager,
            onBack = { currentScreen = Screen.PROJECTS },
            onQuickBuild = { proj -> handleQuickBuild(proj) },
            onCreateProjectRecord = { newProj ->
                database.projectDao().insertProject(newProj)
                refreshStorage()
            },
            onProjectUpdated = { updatedProj ->
                database.projectDao().updateProject(updatedProj)
                refreshStorage()
            },
            onSwitchProject = { targetProj ->
                activeProject = targetProj
                currentScreen = Screen.EDITOR
            },
            onOpenApiSettings = { currentScreen = Screen.API_SETTINGS }
        )
        buildDialogs()
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                ) {
                    // Drawer Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(20.dp)
                    ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(NeonCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = NeonCyan)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Nexa IDE",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Mobile IDE & APK Builder",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Navigation Drawer Items
                DrawerMenuItem(
                    label = "My Projects",
                    icon = Icons.Default.Folder,
                    selected = currentScreen == Screen.PROJECTS,
                    onClick = {
                        currentScreen = Screen.PROJECTS
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Code Editor",
                    icon = Icons.Default.Code,
                    selected = currentScreen == Screen.EDITOR,
                    onClick = {
                        if (activeProject != null) {
                            currentScreen = Screen.EDITOR
                        } else if (projects.isNotEmpty()) {
                            activeProject = projects.first()
                            currentScreen = Screen.EDITOR
                        } else {
                            Toast.makeText(context, "Please create a project first", Toast.LENGTH_SHORT).show()
                        }
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Build History",
                    icon = Icons.Default.History,
                    selected = currentScreen == Screen.BUILD_HISTORY,
                    onClick = {
                        currentScreen = Screen.BUILD_HISTORY
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "GitHub Studio",
                    icon = Icons.Default.CloudDone,
                    selected = currentScreen == Screen.GITHUB,
                    onClick = {
                        currentScreen = Screen.GITHUB
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "GitHub Account",
                    icon = Icons.Default.ManageAccounts,
                    selected = currentScreen == Screen.GITHUB_ACCOUNT,
                    onClick = {
                        currentScreen = Screen.GITHUB_ACCOUNT
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Integrated Terminal",
                    icon = Icons.Default.Terminal,
                    selected = currentScreen == Screen.TERMINAL,
                    onClick = {
                        currentScreen = Screen.TERMINAL
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Testing Lab",
                    icon = Icons.Default.Science,
                    selected = currentScreen == Screen.TESTING_LAB,
                    onClick = {
                        currentScreen = Screen.TESTING_LAB
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Asset Studio",
                    icon = Icons.Default.Image,
                    selected = currentScreen == Screen.ASSET_STUDIO,
                    onClick = {
                        currentScreen = Screen.ASSET_STUDIO
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Downloads",
                    icon = Icons.Default.Storage,
                    selected = currentScreen == Screen.DOWNLOADS,
                    onClick = {
                        currentScreen = Screen.DOWNLOADS
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Notifications",
                    icon = Icons.Default.Notifications,
                    badgeCount = unreadNotificationsCount,
                    selected = currentScreen == Screen.NOTIFICATIONS,
                    onClick = {
                        currentScreen = Screen.NOTIFICATIONS
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Settings",
                    icon = Icons.Default.Settings,
                    selected = currentScreen == Screen.SETTINGS,
                    onClick = {
                        currentScreen = Screen.SETTINGS
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Live Preview Studio",
                    icon = Icons.Default.Preview,
                    selected = currentScreen == Screen.PREVIEW,
                    onClick = {
                        currentScreen = Screen.PREVIEW
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = "Nexa AI Assistant",
                    icon = Icons.Default.AutoAwesome,
                    selected = currentScreen == Screen.AI_ASSISTANT,
                    onClick = {
                        aiErrorFileName = null
                        aiInitialPrompt = null
                        currentScreen = Screen.AI_ASSISTANT
                        coroutineScope.launch { drawerState.close() }
                    }
                )

                DrawerMenuItem(
                    label = stringResource(R.string.drawer_about),
                    icon = Icons.Default.Info,
                    selected = currentScreen == Screen.ABOUT,
                    onClick = {
                        currentScreen = Screen.ABOUT
                        coroutineScope.launch { drawerState.close() }
                    }
                )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (currentScreen in listOf(Screen.HOME, Screen.PROJECTS, Screen.BUILD)) {
                    TopAppBar(
                        title = {
                            Text(
                                text = when (currentScreen) {
                                    Screen.HOME -> stringResource(R.string.app_name)
                                    Screen.PROJECTS -> stringResource(R.string.nav_projects)
                                    Screen.BUILD -> stringResource(R.string.nav_build_title)
                                    else -> stringResource(R.string.app_name)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            IconButton(onClick = { currentScreen = Screen.NOTIFICATIONS }) {
                                BadgedBox(
                                    badge = {
                                        if (unreadNotificationsCount > 0) {
                                            Badge { Text("$unreadNotificationsCount") }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Notifications, contentDescription = "Notifications")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            },
            bottomBar = {
                if (currentScreen in listOf(Screen.HOME, Screen.PROJECTS, Screen.BUILD, Screen.SETTINGS)) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        NavigationBarItem(
                            selected = currentScreen == Screen.HOME,
                            onClick = { currentScreen = Screen.HOME },
                            icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) },
                            label = { Text(stringResource(R.string.nav_home)) },
                            modifier = Modifier.testTag("nav_home")
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.PROJECTS,
                            onClick = { currentScreen = Screen.PROJECTS },
                            icon = { Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.nav_projects)) },
                            label = { Text(stringResource(R.string.nav_projects)) },
                            modifier = Modifier.testTag("nav_projects")
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.BUILD,
                            onClick = {
                                val target = activeProject ?: projects.firstOrNull()
                                if (isWebappProject(target) && buildState !is BuildProgressState.Active) {
                                    htmlProjectToConfigure = target
                                } else {
                                    currentScreen = Screen.BUILD
                                }
                            },
                            icon = { Icon(Icons.Default.Build, contentDescription = stringResource(R.string.nav_build)) },
                            label = { Text(stringResource(R.string.nav_build)) },
                            modifier = Modifier.testTag("nav_build")
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.SETTINGS,
                            onClick = { currentScreen = Screen.SETTINGS },
                            icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
                            label = { Text(stringResource(R.string.nav_settings)) },
                            modifier = Modifier.testTag("nav_settings")
                        )
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (currentScreen) {
                    Screen.HOME -> HomeScreen(
                        projects = projects,
                        builds = builds,
                        successfulBuildsCount = successfulBuildsCount,
                        failedBuildsCount = failedBuildsCount,
                        storageUsageBytes = storageUsageBytes,
                        githubConnection = githubConnection,
                        onOpenProject = { proj ->
                            activeProject = proj
                            currentScreen = Screen.EDITOR
                        },
                        onNewProjectClick = { currentScreen = Screen.PROJECTS },
                        onImportProjectClick = { importZipLauncher.launch(arrayOf("application/zip")) },
                        onQuickBuildClick = { proj -> handleQuickBuild(proj) },
                        onNavigateToProjects = { currentScreen = Screen.PROJECTS },
                        onNavigateToBuilds = { currentScreen = Screen.BUILD_HISTORY },
                        onNavigateToGitHub = { currentScreen = Screen.GITHUB },
                        onNavigateToTerminal = { currentScreen = Screen.TERMINAL },
                        onNavigateToTestingLab = { currentScreen = Screen.TESTING_LAB },
                        onNavigateToAssetStudio = { currentScreen = Screen.ASSET_STUDIO },
                        onNavigateToGitHubAccount = { currentScreen = Screen.GITHUB_ACCOUNT }
                    )

                    Screen.PROJECTS -> ProjectsScreen(
                        projects = projects,
                        onOpenProject = { proj ->
                            activeProject = proj
                            currentScreen = Screen.EDITOR
                        },
                        onCreateProject = { name, pkg, template, lang ->
                            coroutineScope.launch(Dispatchers.IO) {
                                val newProj = fileManager.createProject(name, pkg, template, lang)
                                database.projectDao().insertProject(newProj)
                                refreshStorage()
                                withContext(Dispatchers.Main) {
                                    activeProject = newProj
                                    Toast.makeText(context, "Created ${newProj.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDuplicateProject = { proj, newName ->
                            coroutineScope.launch(Dispatchers.IO) {
                                val dup = fileManager.duplicateProject(proj, newName)
                                if (dup != null) {
                                    database.projectDao().insertProject(dup)
                                    refreshStorage()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Duplicated to $newName", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onRenameProject = { proj, newName ->
                            coroutineScope.launch(Dispatchers.IO) {
                                database.projectDao().updateProject(proj.copy(name = newName))
                            }
                        },
                        onDeleteProject = { proj ->
                            coroutineScope.launch(Dispatchers.IO) {
                                fileManager.deleteFile(proj.rootPath)
                                database.projectDao().deleteProject(proj)
                                refreshStorage()
                                if (activeProject?.id == proj.id) {
                                    activeProject = projects.firstOrNull { it.id != proj.id }
                                }
                            }
                        },
                        onExportProject = { proj ->
                            coroutineScope.launch(Dispatchers.IO) {
                                val zip = fileManager.exportProjectToZip(proj)
                                withContext(Dispatchers.Main) {
                                    if (zip != null) {
                                        Toast.makeText(context, "Exported: ${zip.name}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        onImportProjectClick = { importZipLauncher.launch(arrayOf("application/zip")) },
                        onQuickBuild = { proj -> handleQuickBuild(proj) }
                    )

                    Screen.BUILD -> BuildScreen(
                        projects = projects,
                        selectedProject = activeProject,
                        onSelectProject = { activeProject = it },
                        buildState = buildState,
                        latestBuild = builds.firstOrNull(),
                        latestArtifact = artifacts.firstOrNull(),
                        githubConnection = githubConnection,
                        onStartBuild = { proj, bType, jdk, gradle, compileSdk, clean, versionCode, versionName, enableMinify, moduleName ->
                            coroutineScope.launch {
                                buildEngine.triggerBuild(
                                    project = proj,
                                    buildType = bType,
                                    jdk = jdk,
                                    gradle = gradle,
                                    compileSdk = compileSdk,
                                    clean = clean,
                                    versionCode = versionCode,
                                    versionName = versionName,
                                    enableMinify = enableMinify,
                                    moduleName = moduleName
                                )
                                refreshStorage()
                            }
                        },
                        onCancelBuild = {
                            coroutineScope.launch { buildEngine.cancelCurrentBuild() }
                        },
                        onInstallApk = { buildEngine.installApk(it) },
                        onShareApk = { buildEngine.shareApk(it) },
                        onNavigateToHistory = { currentScreen = Screen.BUILD_HISTORY },
                        onNavigateToGitHub = { currentScreen = Screen.GITHUB },
                        onCustomizeWebapp = { proj -> htmlProjectToConfigure = proj },
                        onAiFixAndRebuild = { proj, summary, bType ->
                            triggerAiBuildFix(proj, summary, bType)
                        }
                    )

                    Screen.BUILD_HISTORY -> BuildHistoryScreen(
                        builds = builds,
                        onBack = { currentScreen = Screen.HOME },
                        onDeleteBuild = { id ->
                            coroutineScope.launch(Dispatchers.IO) {
                                database.buildDao().deleteBuildById(id)
                            }
                        },
                        onClearAllBuilds = {
                            coroutineScope.launch(Dispatchers.IO) {
                                database.buildDao().clearAllBuilds()
                            }
                        },
                        onInstallApk = { buildEngine.installApk(it) },
                        onShareApk = { buildEngine.shareApk(it) },
                        onRebuild = { build ->
                            val proj = projects.find { it.id == build.projectId } ?: activeProject
                            if (proj != null) {
                                if (isWebappProject(proj)) {
                                    htmlProjectToConfigure = proj
                                } else {
                                    activeProject = proj
                                    currentScreen = Screen.BUILD
                                    coroutineScope.launch {
                                        buildEngine.triggerBuild(proj, build.buildType)
                                        refreshStorage()
                                    }
                                }
                            }
                        },
                        onAiFixBuild = { build, summary ->
                            val proj = projects.find { it.id == build.projectId } ?: activeProject
                            if (proj != null) {
                                triggerAiBuildFix(proj, summary, build.buildType)
                            }
                        }
                    )

                    Screen.GITHUB -> GitHubScreen(
                        connection = githubConnection,
                        currentProject = activeProject,
                        onSaveConnection = { username, token, activeRepo ->
                            // Securely store token in Keystore and store non-sensitive metadata in Room
                            com.example.data.security.SecureTokenManager.saveGitHubToken(context, token)
                            coroutineScope.launch(Dispatchers.IO) {
                                database.gitHubConnectionDao().saveConnection(
                                    GitHubConnectionEntity(
                                        username = username,
                                        token = "SECURE_KEYSTORE_ENCRYPTED",
                                        activeRepo = activeRepo
                                    )
                                )
                            }
                        },
                        onDisconnect = {
                            com.example.data.security.SecureTokenManager.clearGitHubToken(context)
                            coroutineScope.launch(Dispatchers.IO) {
                                database.gitHubConnectionDao().clearConnection()
                            }
                        },
                        onOpenAccountManager = { currentScreen = Screen.GITHUB_ACCOUNT },
                        onBack = { currentScreen = Screen.HOME }
                    )

                    Screen.GITHUB_ACCOUNT -> GitHubAccountManagerScreen(
                        connection = githubConnection,
                        onSaveConnection = { username, token, activeRepo ->
                            com.example.data.security.SecureTokenManager.saveGitHubToken(context, token)
                            coroutineScope.launch(Dispatchers.IO) {
                                database.gitHubConnectionDao().saveConnection(
                                    GitHubConnectionEntity(
                                        username = username,
                                        token = "SECURE_KEYSTORE_ENCRYPTED",
                                        activeRepo = activeRepo
                                    )
                                )
                            }
                        },
                        onDisconnect = {
                            com.example.data.security.SecureTokenManager.clearGitHubToken(context)
                            coroutineScope.launch(Dispatchers.IO) {
                                database.gitHubConnectionDao().clearConnection()
                            }
                        },
                        onOpenGitHubAdvanced = { currentScreen = Screen.GITHUB },
                        onBack = { currentScreen = Screen.HOME }
                    )

                    Screen.DOWNLOADS -> DownloadsScreen(
                        artifacts = artifacts,
                        onBack = { currentScreen = Screen.HOME },
                        onInstallApk = { buildEngine.installApk(it) },
                        onShareApk = { buildEngine.shareApk(it) },
                        onDeleteArtifact = { art ->
                            coroutineScope.launch(Dispatchers.IO) {
                                File(art.localFilePath).delete()
                                database.buildArtifactDao().deleteArtifact(art)
                                refreshStorage()
                            }
                        }
                    )

                    Screen.NOTIFICATIONS -> NotificationsScreen(
                        notifications = notifications,
                        onBack = { currentScreen = Screen.HOME },
                        onMarkAsRead = { id ->
                            coroutineScope.launch(Dispatchers.IO) {
                                database.notificationDao().markAsRead(id)
                            }
                        },
                        onMarkAllAsRead = {
                            coroutineScope.launch(Dispatchers.IO) {
                                database.notificationDao().markAllAsRead()
                            }
                        },
                        onDeleteNotification = { id ->
                            coroutineScope.launch(Dispatchers.IO) {
                                database.notificationDao().deleteNotificationById(id)
                            }
                        },
                        onClearAll = {
                            coroutineScope.launch(Dispatchers.IO) {
                                database.notificationDao().clearAllNotifications()
                            }
                        }
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        preferences = preferences,
                        storageUsageBytes = storageUsageBytes,
                        onSetThemeMode = { mode ->
                            coroutineScope.launch { preferencesRepo.setThemeMode(mode) }
                        },
                        onSetFontSize = { size ->
                            coroutineScope.launch { preferencesRepo.setEditorFontSize(size) }
                        },
                        onSetTabSize = { size ->
                            coroutineScope.launch { preferencesRepo.setEditorTabSize(size) }
                        },
                        onSetAutoSave = { autoSave ->
                            coroutineScope.launch { preferencesRepo.setEditorAutoSave(autoSave) }
                        },
                        onSetEditorTheme = { theme ->
                            coroutineScope.launch { preferencesRepo.setEditorTheme(theme) }
                        },
                        onClearCache = {
                            coroutineScope.launch(Dispatchers.IO) {
                                context.cacheDir.deleteRecursively()
                                refreshStorage()
                            }
                        },
                        onOpenApiSettings = { currentScreen = Screen.API_SETTINGS },
                        onAddNewModel = {
                            editingAiModel = null
                            returnScreenFromAddModel = Screen.SETTINGS
                            currentScreen = Screen.ADD_AI_MODEL
                        },
                        onBack = { currentScreen = Screen.HOME }
                    )

                    Screen.API_SETTINGS -> ApiSettingsScreen(
                        refreshSignal = apiRefreshSignal,
                        onAddModelClick = {
                            editingAiModel = null
                            returnScreenFromAddModel = Screen.API_SETTINGS
                            currentScreen = Screen.ADD_AI_MODEL
                        },
                        onEditModelClick = { model ->
                            editingAiModel = model
                            returnScreenFromAddModel = Screen.API_SETTINGS
                            currentScreen = Screen.ADD_AI_MODEL
                        },
                        onBack = { currentScreen = Screen.SETTINGS }
                    )

                    Screen.ADD_AI_MODEL -> AddNewModelScreen(
                        editingModel = editingAiModel,
                        onSaved = {
                            apiRefreshSignal++
                            editingAiModel = null
                            currentScreen = Screen.API_SETTINGS
                        },
                        onBack = {
                            editingAiModel = null
                            currentScreen = returnScreenFromAddModel
                        }
                    )

                    Screen.ABOUT -> AboutScreen(
                        onBack = { currentScreen = Screen.HOME }
                    )

                    Screen.AI_ASSISTANT -> AiAssistantScreen(
                        currentFileName = aiErrorFileName ?: activeProject?.name,
                        currentFileContent = null,
                        initialPrompt = aiInitialPrompt,
                        activeProject = activeProject,
                        fileManager = fileManager,
                        onCreateProjectRecord = { newProj ->
                            database.projectDao().insertProject(newProj)
                            activeProject = newProj
                            refreshStorage()
                        },
                        onProjectUpdated = { updatedProj ->
                            database.projectDao().updateProject(updatedProj)
                            activeProject = updatedProj
                            refreshStorage()
                        },
                        onOpenProjectInEditor = { proj ->
                            activeProject = proj
                            currentScreen = Screen.EDITOR
                        },
                        onPreviewProject = { proj ->
                            activeProject = proj
                            currentScreen = Screen.PREVIEW
                        },
                        onBuildProject = { proj -> handleQuickBuild(proj) },
                        onOpenApiSettings = { currentScreen = Screen.API_SETTINGS },
                        onBack = {
                            aiErrorFileName = null
                            aiInitialPrompt = null
                            currentScreen = Screen.HOME
                        }
                    )

                    Screen.PREVIEW -> {
                        val proj = activeProject ?: projects.firstOrNull()
                        if (proj != null) {
                            PreviewScreen(
                                project = proj,
                                fileManager = fileManager,
                                onBack = { currentScreen = Screen.HOME },
                                onNavigateToBuild = { targetProj -> handleQuickBuild(targetProj) }
                            )
                        } else {
                            Toast.makeText(context, "No project available to preview", Toast.LENGTH_SHORT).show()
                            currentScreen = Screen.HOME
                        }
                    }

                    Screen.TERMINAL -> TerminalScreen(
                        project = activeProject ?: projects.firstOrNull(),
                        fileManager = fileManager,
                        buildEngine = buildEngine,
                        buildState = buildState,
                        onNavigateToBuildHistory = { currentScreen = Screen.BUILD_HISTORY },
                        onBack = { currentScreen = Screen.HOME }
                    )

                    Screen.TESTING_LAB -> TestingLabScreen(
                        project = activeProject ?: projects.firstOrNull(),
                        builds = builds,
                        artifacts = artifacts,
                        fileManager = fileManager,
                        buildEngine = buildEngine,
                        onAskAiError = { fileName, errorContext ->
                            aiErrorFileName = fileName ?: activeProject?.name
                            aiInitialPrompt = errorContext
                            currentScreen = Screen.AI_ASSISTANT
                        },
                        onOpenEditor = { proj ->
                            activeProject = proj
                            currentScreen = Screen.EDITOR
                        },
                        onBack = { currentScreen = Screen.HOME }
                    )

                    Screen.ASSET_STUDIO -> AssetStudioScreen(
                        project = activeProject ?: projects.firstOrNull(),
                        onBack = { currentScreen = Screen.HOME }
                    )

                    Screen.EDITOR -> { /* Handled in dedicated condition above */ }
                }
            }
        }
    }

    buildDialogs()
}

@Composable
private fun DrawerMenuItem(
    label: String,
    icon: ImageVector,
    badgeCount: Int = 0,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                if (badgeCount > 0) {
                    Spacer(modifier = Modifier.weight(1f))
                    Badge { Text("$badgeCount") }
                }
            }
        },
        icon = { Icon(icon, contentDescription = label) },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

package com.example.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.github.*
import com.example.data.local.entity.GitHubConnectionEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.security.SecureTokenManager
import com.example.ui.components.FileBadge
import com.example.ui.components.GitConflictDialog
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

enum class GitHubTab(val title: String) {
    REPOSITORIES("Repos"),
    FILES("Files"),
    COMMITS("Commits"),
    BRANCHES("Branches"),
    PULL_REQUESTS("PRs"),
    ACTIONS("CI/CD")
}

private enum class RepoFilter(val label: String) {
    ALL("All"),
    PUBLIC("Public"),
    PRIVATE("Private")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GitHubScreen(
    connection: GitHubConnectionEntity?,
    currentProject: ProjectEntity?,
    onSaveConnection: (username: String, token: String, activeRepo: String?) -> Unit,
    onDisconnect: () -> Unit,
    onOpenAccountManager: (() -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val secureToken = remember(connection) {
        SecureTokenManager.getGitHubToken(context)
            ?: connection?.token?.takeIf { !it.startsWith("SECURE_") && !it.contains("••••") }
    }
    val effectiveToken = (secureToken ?: "").trim()
    val isConnected = (connection?.isConnected == true) && effectiveToken.isNotBlank()
    val authHeader = if (effectiveToken.isNotBlank()) "Bearer $effectiveToken" else ""

    var selectedTab by remember { mutableStateOf(GitHubTab.REPOSITORIES) }
    var activeRepoName by remember(connection?.activeRepo) { mutableStateOf(connection?.activeRepo ?: "") }
    var bannerError by remember { mutableStateOf<String?>(null) }
    var bannerInfo by remember { mutableStateOf<String?>(null) }

    // Live token health & avatar state
    var tokenHealth by remember { mutableStateOf<TokenValidationResult?>(null) }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Inline modern connect / update key state
    var showTokenModal by remember { mutableStateOf(false) }
    var patInput by remember { mutableStateOf("") }
    var showPatText by remember { mutableStateOf(false) }
    var isVerifyingPat by remember { mutableStateOf(false) }
    var patError by remember { mutableStateOf<String?>(null) }

    // OAuth Device Flow state
    var showOAuthModal by remember { mutableStateOf(false) }
    var oauthClientId by remember { mutableStateOf("") }
    var deviceCode by remember { mutableStateOf<DeviceCodeResponse?>(null) }
    var oauthStatusText by remember { mutableStateOf<String?>(null) }
    var isOAuthPolling by remember { mutableStateOf(false) }
    var oauthJob by remember { mutableStateOf<Job?>(null) }

    // Disconnect confirm
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // 1. Repositories state
    var reposList by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var isLoadingRepos by remember { mutableStateOf(false) }
    var repoSearchQuery by remember { mutableStateOf("") }
    var repoFilter by remember { mutableStateOf(RepoFilter.ALL) }
    var showCreateRepoDialog by remember { mutableStateOf(false) }
    var newRepoName by remember { mutableStateOf("") }
    var newRepoDesc by remember { mutableStateOf("Android Project built with Nexa IDE") }
    var isNewRepoPrivate by remember { mutableStateOf(false) }
    var autoInitNewRepo by remember { mutableStateOf(true) }
    var isCreatingRepo by remember { mutableStateOf(false) }

    // 2. Files state
    var currentDirPath by remember { mutableStateOf("") }
    var dirContents by remember { mutableStateOf<List<GitHubContentItem>>(emptyList()) }
    var isFilesRepoEmpty by remember { mutableStateOf(false) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var viewingFileContent by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fileToDelete by remember { mutableStateOf<GitHubContentItem?>(null) }

    // 3. Commits state
    var commitsList by remember { mutableStateOf<List<GitHubCommitItem>>(emptyList()) }
    var isCommitsRepoEmpty by remember { mutableStateOf(false) }
    var isLoadingCommits by remember { mutableStateOf(false) }
    var commitDetail by remember { mutableStateOf<GitHubCommitDetail?>(null) }

    // 4. Branches state
    var branchesList by remember { mutableStateOf<List<GitHubBranch>>(emptyList()) }
    var isLoadingBranches by remember { mutableStateOf(false) }
    var currentBranch by remember { mutableStateOf(currentProject?.gitBranch ?: "main") }
    var showCreateBranchDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var showMergeBranchDialog by remember { mutableStateOf(false) }
    var branchToMerge by remember { mutableStateOf("") }

    // 5. Pull Requests state
    var pullRequestsList by remember { mutableStateOf<List<GitHubPullRequest>>(emptyList()) }
    var isLoadingPRs by remember { mutableStateOf(false) }
    var showCreatePrDialog by remember { mutableStateOf(false) }
    var prTitle by remember { mutableStateOf("") }
    var prBody by remember { mutableStateOf("") }
    var prHeadBranch by remember { mutableStateOf("") }
    var prBaseBranch by remember { mutableStateOf("main") }

    // 6. Actions state
    var workflowsList by remember { mutableStateOf<List<GitHubWorkflow>>(emptyList()) }
    var runsList by remember { mutableStateOf<List<GitHubWorkflowRun>>(emptyList()) }
    var isLoadingActions by remember { mutableStateOf(false) }
    var dispatchingWorkflowId by remember { mutableStateOf<Long?>(null) }

    // Push & Pull state
    var showCommitDialog by remember { mutableStateOf(false) }
    var commitMessage by remember { mutableStateOf("Sync project from Nexa IDE") }
    var isPushing by remember { mutableStateOf(false) }
    var isPulling by remember { mutableStateOf(false) }
    var conflictData by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    fun openExternalUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun getRepoParts(): Pair<String, String>? {
        val repo = activeRepoName.trim()
        if (repo.isBlank() || !repo.contains("/")) return null
        val parts = repo.split("/")
        return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            Pair(parts[0], parts[1])
        } else null
    }

    fun maskToken(token: String): String {
        val t = token.trim()
        if (t.length <= 10) return "••••"
        return "${t.take(4)}••••${t.takeLast(4)}"
    }

    fun verifyAndSaveToken(rawToken: String, onSuccess: () -> Unit) {
        val trimmed = rawToken.trim()
        if (trimmed.isBlank()) {
            patError = "Please paste a valid GitHub Personal Access Token."
            return
        }
        coroutineScope.launch {
            isVerifyingPat = true
            patError = null
            val service = GitHubService.create()
            val result = withContext(Dispatchers.IO) {
                GitHubAccountHelper.verifyTokenLive(service, trimmed)
            }
            isVerifyingPat = false
            when (result.status) {
                TokenValidationStatus.VALID, TokenValidationStatus.INSUFFICIENT_SCOPE -> {
                    val user = result.user!!
                    tokenHealth = result
                    onSaveConnection(user.login, trimmed, activeRepoName.takeIf { it.isNotBlank() })
                    bannerError = null
                    bannerInfo = result.message
                    onSuccess()
                }
                else -> {
                    patError = result.message
                }
            }
        }
    }

    fun fetchAccountHealth() {
        if (!isConnected) return
        coroutineScope.launch {
            val service = GitHubService.create()
            val result = withContext(Dispatchers.IO) {
                GitHubAccountHelper.verifyTokenLive(service, effectiveToken)
            }
            tokenHealth = result
            val url = result.user?.avatarUrl
            if (!url.isNullOrBlank()) {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bmp != null) avatarBitmap = bmp
            }
        }
    }

    fun fetchRepositories() {
        if (!isConnected) return
        coroutineScope.launch {
            isLoadingRepos = true
            bannerError = null
            try {
                val service = GitHubService.create()
                val repos = withContext(Dispatchers.IO) {
                    service.getUserRepositories(authHeader)
                }
                reposList = repos
                if (activeRepoName.isBlank() && repos.isNotEmpty()) {
                    val first = repos.first()
                    activeRepoName = first.fullName
                    currentBranch = first.defaultBranch.ifBlank { "main" }
                    connection?.let {
                        onSaveConnection(it.username, effectiveToken, first.fullName)
                    }
                }
            } catch (e: Exception) {
                bannerError = GitHubAccountHelper.formatThrowable(e)
            } finally {
                isLoadingRepos = false
            }
        }
    }

    fun fetchFiles(path: String = currentDirPath) {
        val parts = getRepoParts() ?: return
        if (!isConnected) return
        coroutineScope.launch {
            isLoadingFiles = true
            bannerError = null
            isFilesRepoEmpty = false
            try {
                val service = GitHubService.create()
                val resp = withContext(Dispatchers.IO) {
                    service.getDirectoryContentsResponse(authHeader, parts.first, parts.second, path, currentBranch)
                }
                if (resp.isSuccessful) {
                    currentDirPath = path
                    val list = resp.body().orEmpty()
                    dirContents = list.sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
                } else if (resp.code() == 404) {
                    dirContents = emptyList()
                    isFilesRepoEmpty = path.isBlank()
                } else {
                    bannerError = GitHubAccountHelper.formatErrorBody(resp.code(), resp.errorBody()?.string())
                }
            } catch (e: Exception) {
                bannerError = GitHubAccountHelper.formatThrowable(e)
            } finally {
                isLoadingFiles = false
            }
        }
    }

    fun fetchCommits() {
        val parts = getRepoParts() ?: return
        if (!isConnected) return
        coroutineScope.launch {
            isLoadingCommits = true
            bannerError = null
            isCommitsRepoEmpty = false
            try {
                val service = GitHubService.create()
                val resp = withContext(Dispatchers.IO) {
                    service.listCommitsResponse(authHeader, parts.first, parts.second, currentBranch, 30)
                }
                if (resp.isSuccessful) {
                    commitsList = resp.body().orEmpty()
                } else if (resp.code() == 409 || resp.code() == 404) {
                    commitsList = emptyList()
                    isCommitsRepoEmpty = true
                } else {
                    bannerError = GitHubAccountHelper.formatErrorBody(resp.code(), resp.errorBody()?.string())
                }
            } catch (e: Exception) {
                bannerError = GitHubAccountHelper.formatThrowable(e)
            } finally {
                isLoadingCommits = false
            }
        }
    }

    fun fetchBranches() {
        val parts = getRepoParts() ?: return
        if (!isConnected) return
        coroutineScope.launch {
            isLoadingBranches = true
            bannerError = null
            try {
                val service = GitHubService.create()
                val branches = withContext(Dispatchers.IO) {
                    service.listBranches(authHeader, parts.first, parts.second)
                }
                branchesList = branches
                if (branches.isNotEmpty() && branches.none { it.name == currentBranch }) {
                    currentBranch = branches.first().name
                }
            } catch (e: Exception) {
                bannerError = GitHubAccountHelper.formatThrowable(e)
            } finally {
                isLoadingBranches = false
            }
        }
    }

    fun fetchPullRequests() {
        val parts = getRepoParts() ?: return
        if (!isConnected) return
        coroutineScope.launch {
            isLoadingPRs = true
            bannerError = null
            try {
                val service = GitHubService.create()
                pullRequestsList = withContext(Dispatchers.IO) {
                    service.listPullRequests(authHeader, parts.first, parts.second)
                }
            } catch (e: Exception) {
                bannerError = GitHubAccountHelper.formatThrowable(e)
            } finally {
                isLoadingPRs = false
            }
        }
    }

    fun fetchActions() {
        val parts = getRepoParts() ?: return
        if (!isConnected) return
        coroutineScope.launch {
            isLoadingActions = true
            bannerError = null
            try {
                val service = GitHubService.create()
                val workflowsResp = withContext(Dispatchers.IO) {
                    service.listWorkflows(authHeader, parts.first, parts.second)
                }
                val runsResp = withContext(Dispatchers.IO) {
                    service.listWorkflowRuns(authHeader, parts.first, parts.second)
                }
                workflowsList = workflowsResp.workflows
                runsList = runsResp.workflowRuns
            } catch (e: Exception) {
                bannerError = GitHubAccountHelper.formatThrowable(e)
            } finally {
                isLoadingActions = false
            }
        }
    }

    fun refreshCurrentTab() {
        when (selectedTab) {
            GitHubTab.REPOSITORIES -> fetchRepositories()
            GitHubTab.FILES -> fetchFiles(currentDirPath)
            GitHubTab.COMMITS -> fetchCommits()
            GitHubTab.BRANCHES -> fetchBranches()
            GitHubTab.PULL_REQUESTS -> fetchPullRequests()
            GitHubTab.ACTIONS -> fetchActions()
        }
    }

    fun pushProjectToRepo() {
        val parts = getRepoParts()
        val project = currentProject
        if (parts == null) {
            bannerError = "Select a target repository in the Repos tab first."
            return
        }
        if (project == null) {
            bannerError = "Open a project in Nexa IDE before pushing files."
            return
        }
        coroutineScope.launch {
            isPushing = true
            bannerError = null
            try {
                val service = GitHubService.create()
                val resultCount = withContext(Dispatchers.IO) {
                    val rootDir = File(project.rootPath)
                    val allFiles = rootDir.walkTopDown().filter { file ->
                        file.isFile &&
                            !file.path.contains("${File.separator}.git${File.separator}") &&
                            !file.path.contains("${File.separator}build${File.separator}") &&
                            !file.path.contains("${File.separator}.gradle${File.separator}")
                    }.toList()

                    var count = 0
                    for (file in allFiles) {
                        val relPath = file.relativeTo(rootDir).path.replace("\\", "/")
                        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        val existingSha = try {
                            val existing = service.getFileContent(authHeader, parts.first, parts.second, relPath, currentBranch)
                            if (existing.isSuccessful) existing.body()?.sha else null
                        } catch (_: Exception) { null }

                        val resp = service.createOrUpdateFile(
                            auth = authHeader,
                            owner = parts.first,
                            repo = parts.second,
                            path = relPath,
                            body = CreateOrUpdateFilePayload(
                                message = commitMessage.ifBlank { "Sync from Nexa IDE" },
                                content = encoded,
                                branch = currentBranch,
                                sha = existingSha
                            )
                        )
                        if (resp.isSuccessful) count++
                    }
                    count
                }
                showCommitDialog = false
                bannerInfo = "Pushed $resultCount project file(s) to $activeRepoName ($currentBranch)."
                fetchFiles("")
                fetchCommits()
            } catch (e: Exception) {
                bannerError = GitHubAccountHelper.formatThrowable(e)
            } finally {
                isPushing = false
            }
        }
    }

    fun pullRemoteFiles() {
        val parts = getRepoParts()
        val project = currentProject
        if (parts == null || project == null) {
            bannerError = "Both an active repository and an open local project are required to pull."
            return
        }
        coroutineScope.launch {
            isPulling = true
            bannerError = null
            try {
                val service = GitHubService.create()
                val rootDir = File(project.rootPath)
                var updated = 0
                val resp = withContext(Dispatchers.IO) {
                    service.getDirectoryContentsResponse(authHeader, parts.first, parts.second, "", currentBranch)
                }
                if (!resp.isSuccessful) {
                    bannerError = GitHubAccountHelper.formatErrorBody(resp.code(), resp.errorBody()?.string())
                } else {
                    val items = resp.body().orEmpty()
                    for (item in items) {
                        if (item.type == "file") {
                            val fileResp = withContext(Dispatchers.IO) {
                                service.getFileContent(authHeader, parts.first, parts.second, item.path, currentBranch)
                            }
                            if (fileResp.isSuccessful) {
                                val remoteB64 = fileResp.body()?.content?.replace("\n", "") ?: ""
                                val remoteText = String(Base64.decode(remoteB64, Base64.DEFAULT))
                                val localFile = File(rootDir, item.path)
                                if (localFile.exists() && localFile.readText() != remoteText) {
                                    conflictData = Triple(item.path, localFile.readText(), remoteText)
                                    break
                                } else {
                                    localFile.parentFile?.mkdirs()
                                    localFile.writeText(remoteText)
                                    updated++
                                }
                            }
                        }
                    }
                    if (conflictData == null) {
                        bannerInfo = "Pulled $updated file(s) from $activeRepoName ($currentBranch)."
                    }
                }
            } catch (e: Exception) {
                bannerError = GitHubAccountHelper.formatThrowable(e)
            } finally {
                isPulling = false
            }
        }
    }

    LaunchedEffect(isConnected, effectiveToken) {
        if (isConnected) {
            fetchAccountHealth()
            fetchRepositories()
        } else {
            reposList = emptyList()
            tokenHealth = null
            avatarBitmap = null
        }
    }

    LaunchedEffect(selectedTab, activeRepoName, currentBranch, isConnected) {
        if (isConnected && getRepoParts() != null) {
            when (selectedTab) {
                GitHubTab.REPOSITORIES -> if (reposList.isEmpty()) fetchRepositories()
                GitHubTab.FILES -> fetchFiles(currentDirPath)
                GitHubTab.COMMITS -> fetchCommits()
                GitHubTab.BRANCHES -> fetchBranches()
                GitHubTab.PULL_REQUESTS -> fetchPullRequests()
                GitHubTab.ACTIONS -> fetchActions()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .navigationBarsPadding()
            .testTag("github_screen")
    ) {
        // Modern App Bar
        TopAppBar(
            title = {
                Column {
                    Text(
                        "GitHub Studio",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isConnected && activeRepoName.isNotBlank()) {
                            "$activeRepoName • $currentBranch"
                        } else if (isConnected) {
                            "@${connection?.username ?: "connected"} • Select repository"
                        } else {
                            "Encrypted Keystore Sync & Cloud CI"
                        },
                        fontSize = 11.sp,
                        color = if (isConnected) NeonCyan else Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (onOpenAccountManager != null) {
                    IconButton(
                        onClick = onOpenAccountManager,
                        modifier = Modifier.testTag("open_account_manager_button")
                    ) {
                        Icon(Icons.Default.ManageAccounts, contentDescription = "Account Manager", tint = Color.White)
                    }
                }
                if (isConnected) {
                    IconButton(onClick = { refreshCurrentTab() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = NeonCyan)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900, titleContentColor = Color.White)
        )

        // Dismissible Error / Status Banner (replaces ugly HTTP Toasts)
        AnimatedVisibility(visible = bannerError != null || bannerInfo != null) {
            val isErr = bannerError != null
            val msg = bannerError ?: bannerInfo.orEmpty()
            Surface(
                color = if (isErr) CoralRed.copy(alpha = 0.14f) else EmeraldGreen.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (isErr) CoralRed.copy(alpha = 0.5f) else EmeraldGreen.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isErr) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isErr) CoralRed else EmeraldGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    if (isErr && isConnected) {
                        TextButton(
                            onClick = { refreshCurrentTab() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Retry", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(
                        onClick = {
                            bannerError = null
                            bannerInfo = null
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.Gray, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        if (!isConnected) {
            // MODERN EMBEDDED CONNECTION EXPERIENCE
            ModernGitHubOnboardingView(
                patInput = patInput,
                onPatInputChange = { patInput = it; patError = null },
                showPatText = showPatText,
                onToggleShowPat = { showPatText = !showPatText },
                isVerifying = isVerifyingPat,
                patError = patError,
                onVerifyAndConnect = { verifyAndSaveToken(patInput) { patInput = "" } },
                onStartOAuthDeviceFlow = {
                    deviceCode = null
                    oauthStatusText = null
                    showOAuthModal = true
                },
                onOpenCreateClassicPat = { openExternalUrl(GitHubAccountHelper.URL_CREATE_CLASSIC_PAT) },
                onOpenCreateFinePat = { openExternalUrl(GitHubAccountHelper.URL_CREATE_FINE_GRAINED_PAT) },
                onOpenSignUp = { openExternalUrl(GitHubAccountHelper.URL_GITHUB_SIGNUP) }
            )
        } else {
            // Connected Profile Header Card
            Surface(
                color = Slate900,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Slate800),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap!!,
                            contentDescription = "GitHub Avatar",
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, EmeraldGreen.copy(alpha = 0.6f), CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.35f), NeonPurple.copy(alpha = 0.35f)))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (connection?.username?.firstOrNull() ?: 'G').uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "@${connection?.username ?: "user"}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val healthColor = when (tokenHealth?.status) {
                                TokenValidationStatus.VALID -> EmeraldGreen
                                TokenValidationStatus.INSUFFICIENT_SCOPE -> AmberYellow
                                TokenValidationStatus.INVALID_OR_EXPIRED -> CoralRed
                                else -> NeonCyan
                            }
                            Surface(
                                color = healthColor.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = when (tokenHealth?.status) {
                                        TokenValidationStatus.VALID -> "Verified"
                                        TokenValidationStatus.INSUFFICIENT_SCOPE -> "Missing Scope"
                                        TokenValidationStatus.INVALID_OR_EXPIRED -> "Expired"
                                        else -> "Keystore"
                                    },
                                    color = healthColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "${maskToken(effectiveToken)} • ${GitHubAccountHelper.classifyTokenFormat(effectiveToken)}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TextButton(
                        onClick = {
                            patInput = ""
                            patError = null
                            showTokenModal = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Switch Key", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(
                        onClick = { showDisconnectDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = "Disconnect", tint = CoralRed, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Modern Tab Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GitHubTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Surface(
                        onClick = { selectedTab = tab },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) NeonCyan.copy(alpha = 0.18f) else Slate900,
                        border = BorderStroke(1.dp, if (selected) NeonCyan else Slate800)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = when (tab) {
                                    GitHubTab.REPOSITORIES -> Icons.Default.FolderShared
                                    GitHubTab.FILES -> Icons.Default.Code
                                    GitHubTab.COMMITS -> Icons.Default.History
                                    GitHubTab.BRANCHES -> Icons.Default.CallSplit
                                    GitHubTab.PULL_REQUESTS -> Icons.AutoMirrored.Filled.MergeType
                                    GitHubTab.ACTIONS -> Icons.Default.PlayCircle
                                },
                                contentDescription = null,
                                tint = if (selected) NeonCyan else Color(0xFF94A3B8),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = tab.title,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) NeonCyan else Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }

            // Tab Body
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    GitHubTab.REPOSITORIES -> {
                        val filteredRepos = remember(reposList, repoSearchQuery, repoFilter) {
                            reposList.filter { repo ->
                                val matchesSearch = repoSearchQuery.isBlank() ||
                                    repo.name.contains(repoSearchQuery, ignoreCase = true) ||
                                    (repo.description?.contains(repoSearchQuery, ignoreCase = true) == true)
                                val matchesFilter = when (repoFilter) {
                                    RepoFilter.ALL -> true
                                    RepoFilter.PUBLIC -> !repo.private
                                    RepoFilter.PRIVATE -> repo.private
                                }
                                matchesSearch && matchesFilter
                            }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = repoSearchQuery,
                                    onValueChange = { repoSearchQuery = it },
                                    placeholder = { Text("Search repositories...", fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        newRepoName = currentProject?.name?.replace(" ", "-")?.lowercase().orEmpty()
                                        showCreateRepoDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("New", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RepoFilter.entries.forEach { f ->
                                    FilterChip(
                                        selected = repoFilter == f,
                                        onClick = { repoFilter = f },
                                        label = { Text(f.label, fontSize = 11.sp) }
                                    )
                                }
                            }

                            if (isLoadingRepos) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = NeonCyan)
                                }
                            } else if (filteredRepos.isEmpty()) {
                                EmptyStudioState(
                                    icon = Icons.Default.FolderOff,
                                    title = "No repositories match",
                                    subtitle = "Create a new repository with + New or adjust your search/filter.",
                                    actionLabel = "Create Repository",
                                    onAction = { showCreateRepoDialog = true }
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(filteredRepos, key = { it.id }) { repo ->
                                        val isActive = repo.fullName.equals(activeRepoName, ignoreCase = true)
                                        Card(
                                            onClick = {
                                                activeRepoName = repo.fullName
                                                currentBranch = repo.defaultBranch.ifBlank { "main" }
                                                currentDirPath = ""
                                                connection?.let {
                                                    onSaveConnection(it.username, effectiveToken, repo.fullName)
                                                }
                                                bannerInfo = "Active repository switched to ${repo.fullName}"
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isActive) NeonCyan.copy(alpha = 0.1f) else Slate900
                                            ),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isActive) NeonCyan else Slate800
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = if (repo.private) Icons.Default.Lock else Icons.Default.Public,
                                                        contentDescription = null,
                                                        tint = if (repo.private) AmberYellow else EmeraldGreen,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = repo.fullName,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = Color.White,
                                                        modifier = Modifier.weight(1f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (isActive) {
                                                        Surface(
                                                            color = EmeraldGreen.copy(alpha = 0.2f),
                                                            shape = RoundedCornerShape(6.dp)
                                                        ) {
                                                            Text(
                                                                "ACTIVE",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = EmeraldGreen,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                if (!repo.description.isNullOrBlank()) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = repo.description,
                                                        fontSize = 12.sp,
                                                        color = Color(0xFF94A3B8),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                Spacer(modifier = Modifier.height(10.dp))
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    if (!repo.language.isNullOrBlank()) {
                                                        MiniMetaPill(repo.language, NeonPurple)
                                                    }
                                                    MiniMetaPill("★ ${repo.stargazersCount}", AmberYellow)
                                                    MiniMetaPill("⑂ ${repo.forksCount}", NeonCyan)
                                                    Text(
                                                        text = "branch: ${repo.defaultBranch}",
                                                        fontSize = 11.sp,
                                                        color = Color.Gray
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    GitHubTab.FILES -> {
                        if (getRepoParts() == null) {
                            EmptyStudioState(
                                icon = Icons.Default.FolderOpen,
                                title = "No Repository Selected",
                                subtitle = "Pick a repository from the Repos tab to browse files, push, or pull code.",
                                actionLabel = "Browse Repos",
                                onAction = { selectedTab = GitHubTab.REPOSITORIES }
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Sync Toolbar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { showCommitDialog = true },
                                        enabled = currentProject != null && !isPushing,
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = Slate950),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (isPushing) "Pushing..." else "Push Project", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { pullRemoteFiles() },
                                        enabled = currentProject != null && !isPulling,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonCyan)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (isPulling) "Pulling..." else "Pull Root", fontSize = 12.sp, color = NeonCyan)
                                    }
                                }

                                // Breadcrumb
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Slate900)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (currentDirPath.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                val parent = currentDirPath.substringBeforeLast("/", "")
                                                fetchFiles(parent)
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up", tint = NeonCyan, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = "/${currentDirPath}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = Color.LightGray,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = currentBranch,
                                        fontSize = 11.sp,
                                        color = NeonCyan,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (isLoadingFiles) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = NeonCyan)
                                    }
                                } else if (isFilesRepoEmpty || dirContents.isEmpty()) {
                                    EmptyStudioState(
                                        icon = Icons.Default.CloudUpload,
                                        title = if (isFilesRepoEmpty) "Repository is Empty" else "Empty Directory",
                                        subtitle = if (currentProject != null) {
                                            "Push '${currentProject.name}' to create your initial commit on '$currentBranch'."
                                        } else {
                                            "Open a project in Nexa IDE to push files into this repository."
                                        },
                                        actionLabel = if (currentProject != null) "Push Project Files" else null,
                                        onAction = if (currentProject != null) { { showCommitDialog = true } } else null
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(dirContents, key = { it.path }) { item ->
                                            Surface(
                                                onClick = {
                                                    if (item.type == "dir") {
                                                        fetchFiles(item.path)
                                                    } else {
                                                        val parts = getRepoParts() ?: return@Surface
                                                        coroutineScope.launch {
                                                            try {
                                                                val resp = withContext(Dispatchers.IO) {
                                                                    GitHubService.create().getFileContent(authHeader, parts.first, parts.second, item.path, currentBranch)
                                                                }
                                                                if (resp.isSuccessful) {
                                                                    val b64 = resp.body()?.content?.replace("\n", "") ?: ""
                                                                    val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                                                                    viewingFileContent = item.name to decoded
                                                                } else {
                                                                    bannerError = GitHubAccountHelper.formatErrorBody(resp.code(), resp.errorBody()?.string())
                                                                }
                                                            } catch (e: Exception) {
                                                                bannerError = GitHubAccountHelper.formatThrowable(e)
                                                            }
                                                        }
                                                    }
                                                },
                                                color = Slate900,
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(1.dp, Slate800)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    if (item.type == "dir") {
                                                        Icon(Icons.Default.Folder, contentDescription = null, tint = AmberYellow, modifier = Modifier.size(20.dp))
                                                    } else {
                                                        FileBadge(extension = item.name.substringAfterLast('.', ""))
                                                    }
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(item.name, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                                        if (item.type != "dir") {
                                                            Text("${item.size} bytes", fontSize = 10.sp, color = Color.Gray)
                                                        }
                                                    }
                                                    if (item.type == "file") {
                                                        IconButton(
                                                            onClick = { fileToDelete = item },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = CoralRed, modifier = Modifier.size(16.dp))
                                                        }
                                                    } else {
                                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    GitHubTab.COMMITS -> {
                        if (getRepoParts() == null) {
                            EmptyStudioState(
                                icon = Icons.Default.History,
                                title = "No Repository Selected",
                                subtitle = "Select a repository first to inspect commit history.",
                                actionLabel = "Select Repo",
                                onAction = { selectedTab = GitHubTab.REPOSITORIES }
                            )
                        } else if (isLoadingCommits) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = NeonCyan)
                            }
                        } else if (isCommitsRepoEmpty || commitsList.isEmpty()) {
                            EmptyStudioState(
                                icon = Icons.Default.Commit,
                                title = "No Commits Yet",
                                subtitle = "This branch has no commits yet. Push your project files to create the initial commit.",
                                actionLabel = if (currentProject != null) "Push Project" else null,
                                onAction = if (currentProject != null) { { showCommitDialog = true } } else null
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(commitsList, key = { it.sha }) { commit ->
                                    Card(
                                        onClick = {
                                            val parts = getRepoParts() ?: return@Card
                                            coroutineScope.launch {
                                                try {
                                                    commitDetail = withContext(Dispatchers.IO) {
                                                        GitHubService.create().getCommitDetail(authHeader, parts.first, parts.second, commit.sha)
                                                    }
                                                } catch (e: Exception) {
                                                    bannerError = GitHubAccountHelper.formatThrowable(e)
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Slate900),
                                        border = BorderStroke(1.dp, Slate800)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(NeonPurple.copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.Commit, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(18.dp))
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = commit.commit.message.lines().firstOrNull().orEmpty(),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${commit.commit.author?.name ?: "Author"} • ${commit.commit.author?.date?.take(10) ?: ""}",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF94A3B8)
                                                )
                                            }
                                            Surface(color = Slate800, shape = RoundedCornerShape(6.dp)) {
                                                Text(
                                                    commit.sha.take(7),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = NeonCyan,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    GitHubTab.BRANCHES -> {
                        if (getRepoParts() == null) {
                            EmptyStudioState(
                                icon = Icons.Default.CallSplit,
                                title = "No Repository Selected",
                                subtitle = "Select a repository to manage branches.",
                                actionLabel = "Browse Repos",
                                onAction = { selectedTab = GitHubTab.REPOSITORIES }
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showCreateBranchDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("New Branch", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { showMergeBranchDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonPurple)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Merge", fontSize = 12.sp, color = NeonPurple)
                                    }
                                }

                                if (isLoadingBranches) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = NeonCyan)
                                    }
                                } else if (branchesList.isEmpty()) {
                                    EmptyStudioState(
                                        icon = Icons.Default.CallSplit,
                                        title = "No Branches Found",
                                        subtitle = "Empty repositories get their default branch after the first commit."
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(branchesList, key = { it.name }) { branch ->
                                            val isSelected = branch.name == currentBranch
                                            Card(
                                                onClick = {
                                                    currentBranch = branch.name
                                                    currentDirPath = ""
                                                    bannerInfo = "Switched active branch to ${branch.name}"
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isSelected) NeonCyan.copy(alpha = 0.12f) else Slate900
                                                ),
                                                border = BorderStroke(1.dp, if (isSelected) NeonCyan else Slate800)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(14.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.CallSplit, contentDescription = null, tint = if (isSelected) NeonCyan else Color.Gray)
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(branch.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                                        Text(branch.commit?.sha?.take(8) ?: "", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                                                    }
                                                    if (branch.protected) {
                                                        MiniMetaPill("Protected", AmberYellow)
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                    }
                                                    if (isSelected) {
                                                        Icon(Icons.Default.CheckCircle, contentDescription = "Current", tint = EmeraldGreen)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    GitHubTab.PULL_REQUESTS -> {
                        if (getRepoParts() == null) {
                            EmptyStudioState(
                                icon = Icons.AutoMirrored.Filled.MergeType,
                                title = "No Repository Selected",
                                subtitle = "Select a repository to view or create Pull Requests."
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
                                        onClick = {
                                            prHeadBranch = currentBranch
                                            showCreatePrDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Create PR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                                if (isLoadingPRs) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = NeonCyan)
                                    }
                                } else if (pullRequestsList.isEmpty()) {
                                    EmptyStudioState(
                                        icon = Icons.AutoMirrored.Filled.MergeType,
                                        title = "No Pull Requests",
                                        subtitle = "Create a pull request to propose and merge changes across branches."
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(pullRequestsList, key = { it.id }) { pr ->
                                            Card(
                                                onClick = { openExternalUrl(pr.htmlUrl) },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = CardDefaults.cardColors(containerColor = Slate900),
                                                border = BorderStroke(1.dp, Slate800)
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        MiniMetaPill(
                                                            pr.state.uppercase(),
                                                            if (pr.state == "open") EmeraldGreen else NeonPurple
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text("#${pr.number} ${pr.title}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                                    }
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = "${pr.head?.ref ?: "head"} → ${pr.base?.ref ?: "base"}",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF94A3B8)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    GitHubTab.ACTIONS -> {
                        if (getRepoParts() == null) {
                            EmptyStudioState(
                                icon = Icons.Default.PlayCircle,
                                title = "No Repository Selected",
                                subtitle = "Select a repository to trigger and monitor GitHub Actions workflows."
                            )
                        } else if (isLoadingActions) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = NeonCyan)
                            }
                        } else if (workflowsList.isEmpty() && runsList.isEmpty()) {
                            EmptyStudioState(
                                icon = Icons.Default.PlayCircleOutline,
                                title = "No GitHub Actions Found",
                                subtitle = "Push your Nexa IDE project to upload '.github/workflows/android-build.yml' for cloud APK builds."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (workflowsList.isNotEmpty()) {
                                    item {
                                        Text("WORKFLOWS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                    }
                                    items(workflowsList, key = { it.id }) { wf ->
                                        Card(
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(containerColor = Slate900),
                                            border = BorderStroke(1.dp, Slate800)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(wf.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                                    Text(wf.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                                                }
                                                Button(
                                                    onClick = {
                                                        val parts = getRepoParts() ?: return@Button
                                                        coroutineScope.launch {
                                                            dispatchingWorkflowId = wf.id
                                                            try {
                                                                val wfFile = wf.path.substringAfterLast("/")
                                                                val resp = withContext(Dispatchers.IO) {
                                                                    GitHubService.create().triggerWorkflowDispatch(
                                                                        auth = authHeader,
                                                                        owner = parts.first,
                                                                        repo = parts.second,
                                                                        workflowId = wfFile,
                                                                        payload = WorkflowDispatchPayload(ref = currentBranch)
                                                                    )
                                                                }
                                                                if (resp.isSuccessful) {
                                                                    bannerInfo = "Dispatched '${wf.name}' on branch '$currentBranch'."
                                                                    delay(1500)
                                                                    fetchActions()
                                                                } else {
                                                                    bannerError = GitHubAccountHelper.formatErrorBody(resp.code(), resp.errorBody()?.string())
                                                                }
                                                            } catch (e: Exception) {
                                                                bannerError = GitHubAccountHelper.formatThrowable(e)
                                                            } finally {
                                                                dispatchingWorkflowId = null
                                                            }
                                                        }
                                                    },
                                                    enabled = dispatchingWorkflowId != wf.id,
                                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    Text(if (dispatchingWorkflowId == wf.id) "Running..." else "Run", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }

                                if (runsList.isNotEmpty()) {
                                    item {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("RECENT RUNS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                    }
                                    items(runsList, key = { it.id }) { run ->
                                        val pillColor = when {
                                            run.conclusion == "success" -> EmeraldGreen
                                            run.conclusion == "failure" -> CoralRed
                                            run.status == "in_progress" || run.status == "queued" -> AmberYellow
                                            else -> Color.Gray
                                        }
                                        Card(
                                            onClick = { openExternalUrl(run.htmlUrl) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(containerColor = Slate900),
                                            border = BorderStroke(1.dp, Slate800)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(run.name ?: "Workflow Run #${run.id}", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                                                    Text(
                                                        "branch: ${run.headBranch ?: "-"} • ${run.createdAt.take(10)}",
                                                        fontSize = 11.sp,
                                                        color = Color.Gray
                                                    )
                                                }
                                                MiniMetaPill((run.conclusion ?: run.status).uppercase(), pillColor)
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

    // Update / Switch Token Dialog
    if (showTokenModal) {
        AlertDialog(
            onDismissRequest = { if (!isVerifyingPat) showTokenModal = false },
            title = { Text("Update GitHub Token", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter a GitHub PAT (ghp_... or github_pat_...) with 'repo' and 'workflow' permissions. Stored via Android Keystore.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = patInput,
                        onValueChange = { patInput = it; patError = null },
                        label = { Text("GitHub Personal Access Token") },
                        singleLine = true,
                        visualTransformation = if (showPatText) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPatText = !showPatText }) {
                                Icon(if (showPatText) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle visibility")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (patError != null) {
                        Text(patError!!, color = CoralRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { verifyAndSaveToken(patInput) { showTokenModal = false; patInput = "" } },
                    enabled = !isVerifyingPat
                ) {
                    Text(if (isVerifyingPat) "Verifying..." else "Verify & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTokenModal = false }) { Text("Cancel") }
            }
        )
    }

    // Disconnect Confirmation
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect GitHub?", fontWeight = FontWeight.Bold) },
            text = { Text("This will wipe the encrypted GitHub token from Android Keystore on this device.", fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectDialog = false
                        bannerError = null
                        bannerInfo = null
                        onDisconnect()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Create Repository Dialog
    if (showCreateRepoDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCreatingRepo) showCreateRepoDialog = false },
            title = { Text("Create GitHub Repository", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it.replace(" ", "-") },
                        label = { Text("Repository Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRepoDesc,
                        onValueChange = { newRepoDesc = it },
                        label = { Text("Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = isNewRepoPrivate, onCheckedChange = { isNewRepoPrivate = it })
                        Text("Private Repository", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = autoInitNewRepo, onCheckedChange = { autoInitNewRepo = it })
                        Text("Initialize with README.md", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newRepoName.isBlank()) return@Button
                        coroutineScope.launch {
                            isCreatingRepo = true
                            try {
                                val resp = withContext(Dispatchers.IO) {
                                    GitHubService.create().createRepository(
                                        auth = authHeader,
                                        request = CreateRepoRequest(
                                            name = newRepoName.trim(),
                                            description = newRepoDesc.trim().ifBlank { null },
                                            private = isNewRepoPrivate,
                                            autoInit = autoInitNewRepo
                                        )
                                    )
                                }
                                if (resp.isSuccessful && resp.body() != null) {
                                    val created = resp.body()!!
                                    activeRepoName = created.fullName
                                    currentBranch = created.defaultBranch.ifBlank { "main" }
                                    connection?.let {
                                        onSaveConnection(it.username, effectiveToken, created.fullName)
                                    }
                                    showCreateRepoDialog = false
                                    bannerInfo = "Created repository ${created.fullName}"
                                    fetchRepositories()
                                } else {
                                    bannerError = GitHubAccountHelper.formatErrorBody(resp.code(), resp.errorBody()?.string())
                                }
                            } catch (e: Exception) {
                                bannerError = GitHubAccountHelper.formatThrowable(e)
                            } finally {
                                isCreatingRepo = false
                            }
                        }
                    },
                    enabled = !isCreatingRepo && newRepoName.isNotBlank()
                ) { Text(if (isCreatingRepo) "Creating..." else "Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRepoDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Push / Commit Dialog
    if (showCommitDialog) {
        AlertDialog(
            onDismissRequest = { if (!isPushing) showCommitDialog = false },
            title = { Text("Push Project to $activeRepoName", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target branch: $currentBranch", fontSize = 12.sp, color = NeonCyan)
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Commit Message") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { pushProjectToRepo() },
                    enabled = !isPushing
                ) { Text(if (isPushing) "Pushing..." else "Push Now") }
            },
            dismissButton = {
                TextButton(onClick = { showCommitDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Create Branch Dialog
    if (showCreateBranchDialog) {
        AlertDialog(
            onDismissRequest = { showCreateBranchDialog = false },
            title = { Text("Create New Branch", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it.replace(" ", "-") },
                    label = { Text("Branch Name (from $currentBranch)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parts = getRepoParts() ?: return@Button
                        val branch = newBranchName.trim()
                        if (branch.isBlank()) return@Button
                        coroutineScope.launch {
                            try {
                                val service = GitHubService.create()
                                val refResp = withContext(Dispatchers.IO) {
                                    service.getGitRef(authHeader, parts.first, parts.second, currentBranch)
                                }
                                val createResp = withContext(Dispatchers.IO) {
                                    service.createGitRef(
                                        authHeader,
                                        parts.first,
                                        parts.second,
                                        CreateGitRefRequest(ref = "refs/heads/$branch", sha = refResp.obj.sha)
                                    )
                                }
                                if (createResp.isSuccessful) {
                                    currentBranch = branch
                                    newBranchName = ""
                                    showCreateBranchDialog = false
                                    bannerInfo = "Created branch '$branch'."
                                    fetchBranches()
                                } else {
                                    bannerError = GitHubAccountHelper.formatErrorBody(createResp.code(), createResp.errorBody()?.string())
                                }
                            } catch (e: Exception) {
                                bannerError = GitHubAccountHelper.formatThrowable(e)
                            }
                        }
                    }
                ) { Text("Create Branch") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateBranchDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Merge Branch Dialog
    if (showMergeBranchDialog) {
        AlertDialog(
            onDismissRequest = { showMergeBranchDialog = false },
            title = { Text("Merge Into $currentBranch", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = branchToMerge,
                    onValueChange = { branchToMerge = it },
                    label = { Text("Head branch to merge") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parts = getRepoParts() ?: return@Button
                        if (branchToMerge.isBlank()) return@Button
                        coroutineScope.launch {
                            try {
                                val resp = withContext(Dispatchers.IO) {
                                    GitHubService.create().mergeBranch(
                                        authHeader,
                                        parts.first,
                                        parts.second,
                                        MergeBranchRequest(base = currentBranch, head = branchToMerge.trim())
                                    )
                                }
                                if (resp.isSuccessful) {
                                    showMergeBranchDialog = false
                                    bannerInfo = "Merged '${branchToMerge.trim()}' into '$currentBranch'."
                                    branchToMerge = ""
                                    fetchCommits()
                                } else {
                                    bannerError = GitHubAccountHelper.formatErrorBody(resp.code(), resp.errorBody()?.string())
                                }
                            } catch (e: Exception) {
                                bannerError = GitHubAccountHelper.formatThrowable(e)
                            }
                        }
                    }
                ) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = { showMergeBranchDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Create Pull Request Dialog
    if (showCreatePrDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePrDialog = false },
            title = { Text("New Pull Request", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = prTitle, onValueChange = { prTitle = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = prHeadBranch, onValueChange = { prHeadBranch = it }, label = { Text("Head Branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = prBaseBranch, onValueChange = { prBaseBranch = it }, label = { Text("Base Branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = prBody, onValueChange = { prBody = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parts = getRepoParts() ?: return@Button
                        if (prTitle.isBlank() || prHeadBranch.isBlank() || prBaseBranch.isBlank()) return@Button
                        coroutineScope.launch {
                            try {
                                val pr = withContext(Dispatchers.IO) {
                                    GitHubService.create().createPullRequest(
                                        authHeader,
                                        parts.first,
                                        parts.second,
                                        CreatePullRequestPayload(prTitle.trim(), prHeadBranch.trim(), prBaseBranch.trim(), prBody.ifBlank { null })
                                    )
                                }
                                showCreatePrDialog = false
                                prTitle = ""; prBody = ""
                                bannerInfo = "Opened Pull Request #${pr.number}."
                                fetchPullRequests()
                            } catch (e: Exception) {
                                bannerError = GitHubAccountHelper.formatThrowable(e)
                            }
                        }
                    }
                ) { Text("Open PR") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePrDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Remote File Viewer Dialog
    viewingFileContent?.let { (name, content) ->
        AlertDialog(
            onDismissRequest = { viewingFileContent = null },
            title = { Text(name, fontWeight = FontWeight.Bold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .background(Slate950, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(content, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFE2E8F0))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingFileContent = null }) { Text("Close") }
            }
        )
    }

    // Delete File Confirmation
    fileToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete Remote File?", fontWeight = FontWeight.Bold) },
            text = { Text("Delete '${item.path}' on branch '$currentBranch'?", fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        val parts = getRepoParts() ?: return@Button
                        coroutineScope.launch {
                            try {
                                val resp = withContext(Dispatchers.IO) {
                                    GitHubService.create().deleteFile(
                                        authHeader,
                                        parts.first,
                                        parts.second,
                                        item.path,
                                        DeleteFilePayload("Delete ${item.name} via Nexa IDE", item.sha, currentBranch)
                                    )
                                }
                                fileToDelete = null
                                if (resp.isSuccessful) {
                                    bannerInfo = "Deleted ${item.name}."
                                    fetchFiles(currentDirPath)
                                } else {
                                    bannerError = GitHubAccountHelper.formatErrorBody(resp.code(), resp.errorBody()?.string())
                                }
                            } catch (e: Exception) {
                                fileToDelete = null
                                bannerError = GitHubAccountHelper.formatThrowable(e)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Commit Detail Dialog
    commitDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { commitDetail = null },
            title = { Text("Commit ${detail.sha.take(7)}", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(detail.commit.message, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    detail.files.orEmpty().forEach { f ->
                        Surface(color = Slate950, shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Text("${f.status.uppercase()}: ${f.filename}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = NeonCyan)
                                Text("+${f.additions} / -${f.deletions}", fontSize = 10.sp, color = EmeraldGreen)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { commitDetail = null }) { Text("Close") } }
        )
    }

    // Conflict Dialog
    conflictData?.let { (filename, local, remote) ->
        GitConflictDialog(
            fileName = filename,
            localContent = local,
            remoteContent = remote,
            onKeepLocal = {
                conflictData = null
                bannerInfo = "Kept local copy of $filename."
            },
            onAcceptRemote = {
                val proj = currentProject
                if (proj != null) {
                    File(proj.rootPath, filename).apply {
                        parentFile?.mkdirs()
                        writeText(remote)
                    }
                }
                conflictData = null
                bannerInfo = "Updated $filename from remote repository."
            },
            onCancel = { conflictData = null }
        )
    }

    // OAuth Device Flow Dialog
    if (showOAuthModal) {
        AlertDialog(
            onDismissRequest = {
                oauthJob?.cancel()
                isOAuthPolling = false
                showOAuthModal = false
            },
            title = { Text("GitHub OAuth Device Flow", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Connect via official GitHub OAuth 2.0 Device Flow (RFC 8628) using your GitHub OAuth App Client ID.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = oauthClientId,
                        onValueChange = { oauthClientId = it },
                        label = { Text("GitHub OAuth App Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    deviceCode?.let { dc ->
                        Surface(
                            color = Slate950,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Verification Code", fontSize = 11.sp, color = Color.Gray)
                                Text(dc.userCode, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = NeonCyan)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        clipboardManager.setText(AnnotatedString(dc.userCode))
                                        Toast.makeText(context, "Copied code", Toast.LENGTH_SHORT).show()
                                    }) { Text("Copy Code") }
                                    TextButton(onClick = { openExternalUrl(dc.verificationUri) }) { Text("Open GitHub") }
                                }
                            }
                        }
                    }
                    oauthStatusText?.let {
                        Text(it, fontSize = 12.sp, color = NeonCyan)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        oauthJob?.cancel()
                        oauthJob = coroutineScope.launch {
                            isOAuthPolling = true
                            oauthStatusText = "Requesting device code..."
                            val reqRes = withContext(Dispatchers.IO) {
                                GitHubAccountHelper.requestOAuthDeviceCode(oauthClientId)
                            }
                            if (reqRes.isFailure) {
                                oauthStatusText = reqRes.exceptionOrNull()?.message ?: "Device code failed"
                                isOAuthPolling = false
                                return@launch
                            }
                            val dc = reqRes.getOrThrow()
                            deviceCode = dc
                            oauthStatusText = "Enter code on github.com/login/device..."
                            var intervalMs = (dc.intervalSeconds.coerceAtLeast(5)) * 1000L
                            while (isActive) {
                                delay(intervalMs)
                                val poll = withContext(Dispatchers.IO) {
                                    GitHubAccountHelper.pollOAuthDeviceToken(oauthClientId, dc.deviceCode)
                                }
                                when (poll) {
                                    is DeviceFlowPollResult.Authorized -> {
                                        val verified = withContext(Dispatchers.IO) {
                                            GitHubAccountHelper.verifyTokenLive(GitHubService.create(), poll.accessToken)
                                        }
                                        val username = verified.user?.login ?: "github-user"
                                        onSaveConnection(username, poll.accessToken, activeRepoName.takeIf { it.isNotBlank() })
                                        isOAuthPolling = false
                                        showOAuthModal = false
                                        bannerInfo = "Signed in as @$username via GitHub OAuth."
                                        break
                                    }
                                    is DeviceFlowPollResult.Pending -> oauthStatusText = poll.message
                                    is DeviceFlowPollResult.SlowDown -> intervalMs = (poll.nextIntervalSeconds.coerceAtLeast(5)) * 1000L
                                    is DeviceFlowPollResult.Error -> {
                                        oauthStatusText = poll.message
                                        isOAuthPolling = false
                                        break
                                    }
                                }
                            }
                        }
                    },
                    enabled = oauthClientId.isNotBlank() && !isOAuthPolling
                ) { Text(if (isOAuthPolling) "Waiting..." else "Start OAuth") }
            },
            dismissButton = {
                TextButton(onClick = {
                    oauthJob?.cancel()
                    isOAuthPolling = false
                    showOAuthModal = false
                }) { Text("Close") }
            }
        )
    }
}

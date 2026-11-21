package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import java.net.URL
import com.example.data.github.DeviceCodeResponse
import com.example.data.github.DeviceFlowPollResult
import com.example.data.github.GitHubAccountHelper
import com.example.data.github.GitHubRepo
import com.example.data.github.GitHubService
import com.example.data.github.TokenValidationResult
import com.example.data.github.TokenValidationStatus
import com.example.data.local.entity.GitHubConnectionEntity
import com.example.data.security.SecureTokenManager
import com.example.ui.theme.AmberYellow
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GitHubAccountManagerScreen(
    connection: GitHubConnectionEntity?,
    onSaveConnection: (username: String, token: String, activeRepo: String?) -> Unit,
    onDisconnect: () -> Unit,
    onOpenGitHubAdvanced: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val storedToken = remember(connection) {
        SecureTokenManager.getGitHubToken(context)
            ?: connection?.token?.takeIf { !it.startsWith("SECURE_") && !it.contains("••••") }
            ?: ""
    }
    val hasConnection = (connection?.isConnected == true) && storedToken.isNotBlank()

    var validationResult by remember { mutableStateOf<TokenValidationResult?>(null) }
    var isValidating by remember { mutableStateOf(false) }
    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var isLoadingRepos by remember { mutableStateOf(false) }

    // Token Connect / Replace Dialog
    var showPatDialog by remember { mutableStateOf(false) }
    var patInput by remember { mutableStateOf("") }
    var showPatText by remember { mutableStateOf(false) }
    var isSavingPat by remember { mutableStateOf(false) }
    var patErrorMessage by remember { mutableStateOf<String?>(null) }

    // Official OAuth Device Flow State (user-supplied GitHub OAuth App Client ID)
    var showOAuthDialog by remember { mutableStateOf(false) }
    var oauthClientId by remember { mutableStateOf("") }
    var deviceCode by remember { mutableStateOf<DeviceCodeResponse?>(null) }
    var oauthStatusText by remember { mutableStateOf<String?>(null) }
    var isOAuthPolling by remember { mutableStateOf(false) }
    var oauthJob by remember { mutableStateOf<Job?>(null) }

    // Disconnect Confirmation
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    fun openExternalUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun runLiveValidation() {
        if (storedToken.isBlank()) {
            validationResult = null
            repos = emptyList()
            return
        }
        coroutineScope.launch {
            isValidating = true
            isLoadingRepos = true
            val service = GitHubService.create()
            val result = withContext(Dispatchers.IO) {
                GitHubAccountHelper.verifyTokenLive(service, storedToken)
            }
            validationResult = result
            isValidating = false

            if (result.status == TokenValidationStatus.VALID || result.status == TokenValidationStatus.INSUFFICIENT_SCOPE) {
                try {
                    val fetchedRepos = withContext(Dispatchers.IO) {
                        service.getUserRepositories("Bearer ${storedToken.trim()}")
                    }
                    repos = fetchedRepos
                } catch (_: Exception) {
                    repos = emptyList()
                }
            } else {
                repos = emptyList()
            }
            isLoadingRepos = false
        }
    }

    LaunchedEffect(hasConnection, storedToken) {
        if (hasConnection) {
            runLiveValidation()
        } else {
            validationResult = null
            repos = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .navigationBarsPadding()
            .testTag("github_account_manager_screen")
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("GitHub Account Manager", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Keystore-Backed Authentication & Scopes", fontSize = 11.sp, color = NeonCyan)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (hasConnection) {
                    IconButton(onClick = { runLiveValidation() }, enabled = !isValidating) {
                        Icon(Icons.Default.Refresh, contentDescription = "Verify Token", tint = NeonCyan)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900, titleContentColor = Color.White)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Account Status Dashboard
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    border = BorderStroke(
                        1.dp,
                        when (validationResult?.status) {
                            TokenValidationStatus.VALID -> EmeraldGreen.copy(alpha = 0.5f)
                            TokenValidationStatus.INVALID_OR_EXPIRED, TokenValidationStatus.INSUFFICIENT_SCOPE -> CoralRed.copy(alpha = 0.5f)
                            TokenValidationStatus.RATE_LIMITED, TokenValidationStatus.NETWORK_ERROR -> AmberYellow.copy(alpha = 0.5f)
                            else -> if (hasConnection) NeonCyan.copy(alpha = 0.4f) else Slate800
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            val avatarUrl = validationResult?.user?.avatarUrl
                            var avatarBitmap by remember(avatarUrl) { mutableStateOf<ImageBitmap?>(null) }
                            LaunchedEffect(avatarUrl) {
                                avatarBitmap = if (!avatarUrl.isNullOrBlank()) {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            URL(avatarUrl).openStream().use { stream ->
                                                BitmapFactory.decodeStream(stream)?.asImageBitmap()
                                            }
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                } else null
                            }
                            if (avatarBitmap != null) {
                                Image(
                                    bitmap = avatarBitmap!!,
                                    contentDescription = "GitHub Avatar",
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Slate800)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(if (hasConnection) EmeraldGreen.copy(alpha = 0.15f) else CoralRed.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (hasConnection) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                        contentDescription = null,
                                        tint = if (hasConnection) EmeraldGreen else CoralRed
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (hasConnection) {
                                        validationResult?.user?.name?.takeIf { it.isNotBlank() }
                                            ?: "@${connection?.username}"
                                    } else {
                                        "No GitHub Account Connected"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = if (hasConnection) {
                                        "@${validationResult?.user?.login ?: connection?.username} • ${GitHubAccountHelper.classifyTokenFormat(storedToken)}"
                                    } else {
                                        "Connect via Personal Access Token or GitHub OAuth Device Flow"
                                    },
                                    color = if (hasConnection) NeonCyan else Color.Gray,
                                    fontSize = 12.sp
                                )
                            }

                            if (isValidating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NeonCyan, strokeWidth = 2.dp)
                            }
                        }

                        if (hasConnection) {
                            // Masked Token + Active Repo Info
                            Surface(
                                color = Slate950,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Slate800),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Stored Key (AES-256-GCM)", color = Color.Gray, fontSize = 11.sp)
                                        Text(
                                            SecureTokenManager.maskToken(storedToken),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Active Build Repository", color = Color.Gray, fontSize = 11.sp)
                                        Text(
                                            connection?.activeRepo ?: "Not selected yet",
                                            color = if (connection?.activeRepo != null) EmeraldGreen else AmberYellow,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            // Token Validation Banner
                            validationResult?.let { res ->
                                val statusColor = when (res.status) {
                                    TokenValidationStatus.VALID -> EmeraldGreen
                                    TokenValidationStatus.INVALID_OR_EXPIRED, TokenValidationStatus.INSUFFICIENT_SCOPE -> CoralRed
                                    else -> AmberYellow
                                }
                                val statusIcon = when (res.status) {
                                    TokenValidationStatus.VALID -> Icons.Default.CheckCircle
                                    TokenValidationStatus.INVALID_OR_EXPIRED, TokenValidationStatus.INSUFFICIENT_SCOPE -> Icons.Default.Error
                                    else -> Icons.Default.Warning
                                }
                                Surface(
                                    color = statusColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Status: ${res.status.label}", color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Text(res.message, color = Color.LightGray, fontSize = 11.sp, lineHeight = 16.sp)
                                        if (res.scopes.isNotEmpty()) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                res.scopes.forEach { scope ->
                                                    Surface(
                                                        color = Slate900,
                                                        shape = RoundedCornerShape(6.dp),
                                                        border = BorderStroke(1.dp, Slate800)
                                                    ) {
                                                        Text(
                                                            scope,
                                                            color = NeonCyan,
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Account Actions Row
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onOpenGitHubAdvanced,
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Open GitHub Studio", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        patInput = ""
                                        patErrorMessage = null
                                        showPatDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Replace Token", fontSize = 12.sp, color = Color.White)
                                }
                                OutlinedButton(
                                    onClick = { showDisconnectConfirm = true },
                                    border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.6f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Disconnect", fontSize = 12.sp, color = CoralRed)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Onboarding & Authentication Options (Always available for initial setup or switching)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    border = BorderStroke(1.dp, Slate800)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Connect or Create GitHub Account",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            "Nexa IDE never asks for or stores your GitHub password. Connect cleanly via an encrypted Personal Access Token or official OAuth Device Flow.",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        Button(
                            onClick = {
                                patInput = ""
                                patErrorMessage = null
                                showPatDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_pat_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enter / Replace Personal Access Token", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { openExternalUrl(GitHubAccountHelper.URL_CREATE_FINE_GRAINED_PAT) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("create_pat_browser_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Create PAT", color = EmeraldGreen, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { openExternalUrl(GitHubAccountHelper.URL_GITHUB_SIGNUP) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("create_github_account_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sign Up", color = NeonCyan, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                deviceCode = null
                                oauthStatusText = null
                                showOAuthDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("oauth_device_flow_button"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect via GitHub OAuth (Device Flow)", color = NeonPurple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // 3. Repository Access Selection (when connected)
            if (hasConnection) {
                item {
                    Text(
                        "Repository Access (${repos.size})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                if (isLoadingRepos) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (repos.isEmpty()) {
                    item {
                        Surface(
                            color = Slate900,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "No accessible repositories returned for this token.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                } else {
                    items(repos.take(15)) { repo ->
                        val isActive = repo.fullName.equals(connection?.activeRepo, ignoreCase = true)
                        Surface(
                            color = if (isActive) Slate800 else Slate900,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isActive) NeonCyan else Slate800),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val login = validationResult?.user?.login ?: connection?.username ?: ""
                                    onSaveConnection(login, storedToken, repo.fullName)
                                    Toast.makeText(context, "Active build repo set to ${repo.fullName}", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (repo.private) Icons.Default.Lock else Icons.Default.Public,
                                    contentDescription = null,
                                    tint = if (repo.private) AmberYellow else NeonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(repo.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Default branch: ${repo.defaultBranch}", color = Color.Gray, fontSize = 11.sp)
                                }
                                if (isActive) {
                                    Text("ACTIVE", color = EmeraldGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 4. Step-by-Step Comparison Guide
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    border = BorderStroke(1.dp, Slate800)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Step-by-Step Guide: Account vs OAuth vs PAT", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        GuideRow(
                            title = "1. On github.com (Browser)",
                            body = "Sign up for your GitHub account, manage billing/2FA, and generate Personal Access Tokens or approve OAuth Device Codes."
                        )
                        GuideRow(
                            title = "2. Personal Access Token (PAT)",
                            body = "Recommended for Cloud APK Builds. Use a Classic PAT with 'repo' + 'workflow' scopes, or a Fine-grained PAT with Contents (R/W), Actions (R/W), and Workflows (R/W)."
                        )
                        GuideRow(
                            title = "3. OAuth vs PAT Inside Nexa IDE",
                            body = "OAuth Device Flow requires your GitHub OAuth App Client ID (github.com/settings/developers). PAT works immediately without registering an app. Both are stored AES-256-GCM encrypted in Android Keystore."
                        )
                        GuideRow(
                            title = "4. Inside Nexa IDE",
                            body = "Push code changes, manage branches/PRs, inspect commits, trigger GitHub Actions Gradle builds, and download signed/debug APK & AAB artifacts."
                        )
                    }
                }
            }
        }
    }

    // Dialog: Personal Access Token Entry + Validation
    if (showPatDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingPat) showPatDialog = false },
            title = { Text("Connect Personal Access Token", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Create a token on github.com with 'repo' and 'workflow' scopes (or Fine-grained: Contents, Workflows, Actions R/W). Paste it below to verify live.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { openExternalUrl(GitHubAccountHelper.URL_CREATE_CLASSIC_PAT) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open GitHub Classic PAT Generator", fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = patInput,
                        onValueChange = {
                            patInput = it
                            patErrorMessage = null
                        },
                        label = { Text("Token (ghp_... or github_pat_...)") },
                        visualTransformation = if (showPatText) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPatText = !showPatText }) {
                                Icon(if (showPatText) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle visibility")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pat_token_input")
                    )
                    patErrorMessage?.let { err ->
                        Text(err, color = CoralRed, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isSavingPat && patInput.isNotBlank(),
                    onClick = {
                        isSavingPat = true
                        patErrorMessage = null
                        coroutineScope.launch {
                            val service = GitHubService.create()
                            val res = withContext(Dispatchers.IO) {
                                GitHubAccountHelper.verifyTokenLive(service, patInput)
                            }
                            isSavingPat = false
                            when (res.status) {
                                TokenValidationStatus.VALID, TokenValidationStatus.INSUFFICIENT_SCOPE -> {
                                    val login = res.user?.login ?: "github-user"
                                    onSaveConnection(login, patInput.trim(), connection?.activeRepo)
                                    validationResult = res
                                    showPatDialog = false
                                    Toast.makeText(context, "Verified & saved for @$login", Toast.LENGTH_SHORT).show()
                                }
                                else -> {
                                    patErrorMessage = res.message
                                }
                            }
                        }
                    }
                ) {
                    if (isSavingPat) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Verify & Save")
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !isSavingPat, onClick = { showPatDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Dialog: Official GitHub OAuth Device Flow (Requires real GitHub OAuth App Client ID)
    if (showOAuthDialog) {
        AlertDialog(
            onDismissRequest = {
                oauthJob?.cancel()
                isOAuthPolling = false
                showOAuthDialog = false
            },
            title = { Text("GitHub OAuth Device Flow", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This open-source build does not ship a pre-provisioned OAuth Client ID. Enter your GitHub OAuth App Client ID (with Device Flow enabled under github.com/settings/developers) to authorize safely via browser.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                    OutlinedTextField(
                        value = oauthClientId,
                        onValueChange = { oauthClientId = it },
                        label = { Text("GitHub OAuth App Client ID (Ov23... / Iv1...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    deviceCode?.let { dc ->
                        Surface(
                            color = Slate950,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("One-Time Device Code", color = Color.Gray, fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        dc.userCode,
                                        color = NeonCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    IconButton(onClick = {
                                        clipboardManager.setText(AnnotatedString(dc.userCode))
                                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                                Button(
                                    onClick = { openExternalUrl(dc.verificationUri) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Open ${dc.verificationUri}", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    oauthStatusText?.let { status ->
                        Text(status, color = AmberYellow, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = oauthClientId.isNotBlank() && !isOAuthPolling,
                    onClick = {
                        oauthJob?.cancel()
                        oauthJob = coroutineScope.launch {
                            isOAuthPolling = true
                            oauthStatusText = "Requesting device authorization code from github.com..."
                            val initRes = withContext(Dispatchers.IO) {
                                GitHubAccountHelper.requestOAuthDeviceCode(oauthClientId)
                            }
                            if (initRes.isFailure) {
                                oauthStatusText = "Error: ${initRes.exceptionOrNull()?.message}"
                                isOAuthPolling = false
                                return@launch
                            }
                            val dc = initRes.getOrThrow()
                            deviceCode = dc
                            clipboardManager.setText(AnnotatedString(dc.userCode))
                            oauthStatusText = "Code ${dc.userCode} copied. Open browser above and approve access..."

                            var intervalSec = dc.intervalSeconds.coerceAtLeast(5)
                            val deadlineMs = System.currentTimeMillis() + dc.expiresInSeconds * 1000L
                            while (isActive && System.currentTimeMillis() < deadlineMs) {
                                delay(intervalSec * 1000L)
                                val poll = withContext(Dispatchers.IO) {
                                    GitHubAccountHelper.pollOAuthDeviceToken(oauthClientId, dc.deviceCode)
                                }
                                when (poll) {
                                    is DeviceFlowPollResult.Authorized -> {
                                        val userRes = withContext(Dispatchers.IO) {
                                            GitHubAccountHelper.verifyTokenLive(GitHubService.create(), poll.accessToken)
                                        }
                                        val login = userRes.user?.login ?: "github-user"
                                        onSaveConnection(login, poll.accessToken, connection?.activeRepo)
                                        validationResult = userRes
                                        isOAuthPolling = false
                                        showOAuthDialog = false
                                        Toast.makeText(context, "OAuth connected as @$login", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    is DeviceFlowPollResult.Pending -> {
                                        oauthStatusText = poll.message
                                    }
                                    is DeviceFlowPollResult.SlowDown -> {
                                        intervalSec = poll.nextIntervalSeconds.coerceAtLeast(intervalSec + 5)
                                    }
                                    is DeviceFlowPollResult.Error -> {
                                        oauthStatusText = "OAuth stopped: ${poll.message}"
                                        isOAuthPolling = false
                                        return@launch
                                    }
                                }
                            }
                            oauthStatusText = "Device code expired. Please try again."
                            isOAuthPolling = false
                        }
                    }
                ) {
                    Text(if (isOAuthPolling) "Waiting Approval..." else "Start Device Flow")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    oauthJob?.cancel()
                    isOAuthPolling = false
                    showOAuthDialog = false
                }) { Text("Close") }
            }
        )
    }

    // Disconnect Confirmation
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect GitHub Account?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This clears your encrypted GitHub token from Android Keystore and disconnects cloud APK build syncing until re-authenticated.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDisconnect()
                        validationResult = null
                        repos = emptyList()
                        showDisconnectConfirm = false
                        Toast.makeText(context, "GitHub account disconnected", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun GuideRow(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(body, color = Color.Gray, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

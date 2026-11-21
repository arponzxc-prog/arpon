package com.example.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.github.GitHubService
import com.example.data.security.ReleaseKeystoreManager
import com.example.data.security.SecureTokenManager
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReleaseSigningDialog(
    activeRepo: String?,
    hasGitHubToken: Boolean,
    onDismiss: () -> Unit,
    onSigningUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var refreshTrigger by remember { mutableIntStateOf(0) }
    val savedMeta = remember(refreshTrigger) {
        SecureTokenManager.getReleaseSigningMetadata(context)
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Generate, 1 = Import
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Generate Keystore fields
    var genAlias by remember { mutableStateOf("upload") }
    var genCommonName by remember { mutableStateOf("Nexa Developer") }
    var genOrganization by remember { mutableStateOf("Nexa IDE") }
    var genStorePass by remember { mutableStateOf("") }
    var genKeyPass by remember { mutableStateOf("") }
    var sameKeyPass by remember { mutableStateOf(true) }
    var showPasswords by remember { mutableStateOf(false) }

    // Import Keystore fields
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var impAlias by remember { mutableStateOf("upload") }
    var impStorePass by remember { mutableStateOf("") }
    var impKeyPass by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            val resolvedName = try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            } ?: uri.lastPathSegment ?: "keystore.jks"
            pickedFileName = resolvedName
            statusMessage = null
        }
    }

    val repoReady = hasGitHubToken && !activeRepo.isNullOrBlank() && activeRepo.contains("/")

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(18.dp),
            color = Slate900,
            border = BorderStroke(1.dp, Slate800)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Release Signing Keystore",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "AES-256 Keystore + Libsodium Sealed GitHub Secrets",
                                fontSize = 11.sp,
                                color = NeonCyan
                            )
                        }
                    }
                    IconButton(
                        onClick = { if (!isBusy) onDismiss() },
                        modifier = Modifier.testTag("close_signing_dialog_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Active Saved Keystore Card
                    if (savedMeta != null) {
                        val isUploadedToCurrentRepo = repoReady && savedMeta.uploadedRepo == activeRepo
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate950),
                            border = BorderStroke(
                                1.dp,
                                if (isUploadedToCurrentRepo) EmeraldGreen.copy(alpha = 0.6f) else AmberYellow.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isUploadedToCurrentRepo) Icons.Default.VerifiedUser else Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = if (isUploadedToCurrentRepo) EmeraldGreen else AmberYellow,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Saved Keystore (${savedMeta.sourceType})",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color.White
                                        )
                                    }
                                    TextButton(
                                        enabled = !isBusy,
                                        onClick = {
                                            SecureTokenManager.clearReleaseKeystore(context)
                                            refreshTrigger++
                                            onSigningUpdated()
                                            statusMessage = true to "Saved keystore cleared from device Keystore."
                                        }
                                    ) {
                                        Text("Remove", color = CoralRed, fontSize = 11.sp)
                                    }
                                }

                                Text(
                                    text = "Alias: ${savedMeta.alias} • CN: ${savedMeta.subjectCn}",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "SHA-256: ${savedMeta.sha256Fingerprint}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = NeonCyan,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Text(
                                    text = when {
                                        isUploadedToCurrentRepo -> "Synced encrypted secrets to ${savedMeta.uploadedRepo}"
                                        !savedMeta.uploadedRepo.isNullOrBlank() -> "Previously synced to ${savedMeta.uploadedRepo}. Upload again for $activeRepo."
                                        repoReady -> "Ready to encrypt & upload secrets to $activeRepo"
                                        else -> "Connect GitHub & select a repo to upload Actions secrets."
                                    },
                                    fontSize = 11.sp,
                                    color = if (isUploadedToCurrentRepo) EmeraldGreen else AmberYellow
                                )

                                Button(
                                    onClick = {
                                        val token = SecureTokenManager.getGitHubToken(context)
                                        val secrets = SecureTokenManager.getReleaseSigningSecrets(context)
                                        if (token.isNullOrBlank() || activeRepo.isNullOrBlank() || !activeRepo.contains("/") || secrets == null) {
                                            statusMessage = false to "Connect GitHub token and select an active repository first."
                                            return@Button
                                        }
                                        val parts = activeRepo.split("/", limit = 2)
                                        isBusy = true
                                        statusMessage = null
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val uploadRes = ReleaseKeystoreManager.uploadSigningSecretsToGitHub(
                                                service = GitHubService.create(),
                                                githubToken = token,
                                                owner = parts[0],
                                                repo = parts[1],
                                                keystoreBase64 = secrets.first,
                                                storePassword = secrets.second,
                                                keyAlias = savedMeta.alias,
                                                keyPassword = secrets.third
                                            )
                                            withContext(Dispatchers.Main) {
                                                isBusy = false
                                                if (uploadRes.isSuccess) {
                                                    SecureTokenManager.markKeystoreUploadedToRepo(context, activeRepo)
                                                    refreshTrigger++
                                                    onSigningUpdated()
                                                    statusMessage = true to "Uploaded 4 encrypted secrets (RELEASE_KEYSTORE_BASE64, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) to $activeRepo."
                                                    Toast.makeText(context, "Signing secrets uploaded to GitHub!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val rawErr = uploadRes.exceptionOrNull()?.message ?: "Upload failed"
                                                    statusMessage = false to SecureTokenManager.redactSecrets(rawErr)
                                                }
                                            }
                                        }
                                    },
                                    enabled = repoReady && !isBusy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("upload_signing_secrets_btn"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isUploadedToCurrentRepo) Slate800 else EmeraldGreen,
                                        contentColor = if (isUploadedToCurrentRepo) Color.White else Color.Black
                                    )
                                ) {
                                    if (isBusy) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Encrypting & Uploading...")
                                    } else {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isUploadedToCurrentRepo) "Re-Sync Secrets to $activeRepo" else "Upload Secrets to GitHub Repo",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Inline status alert
                    statusMessage?.let { (isOk, msg) ->
                        Surface(
                            color = (if (isOk) EmeraldGreen else CoralRed).copy(alpha = 0.14f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, (if (isOk) EmeraldGreen else CoralRed).copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                color = if (isOk) EmeraldGreen else CoralRed,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Mode Tabs: Generate New vs Import Existing
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Slate950,
                        contentColor = NeonCyan
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Generate New", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Import .jks / .p12", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }

                    if (selectedTab == 0) {
                        // Generate Keystore Form
                        OutlinedTextField(
                            value = genAlias,
                            onValueChange = { genAlias = it },
                            label = { Text("Key Alias") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = genCommonName,
                                onValueChange = { genCommonName = it },
                                label = { Text("Developer / CN") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = genOrganization,
                                onValueChange = { genOrganization = it },
                                label = { Text("Organization") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        val visualTrans = if (showPasswords) VisualTransformation.None else PasswordVisualTransformation()
                        OutlinedTextField(
                            value = genStorePass,
                            onValueChange = {
                                genStorePass = it
                                if (sameKeyPass) genKeyPass = it
                            },
                            label = { Text("Keystore Password (min 6 chars)") },
                            singleLine = true,
                            visualTransformation = visualTrans,
                            trailingIcon = {
                                IconButton(onClick = { showPasswords = !showPasswords }) {
                                    Icon(
                                        imageVector = if (showPasswords) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle password visibility"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = sameKeyPass,
                                onCheckedChange = {
                                    sameKeyPass = it
                                    if (it) genKeyPass = genStorePass
                                }
                            )
                            Text("Use same password for Key Password", fontSize = 12.sp, color = Color.LightGray)
                        }
                        if (!sameKeyPass) {
                            OutlinedTextField(
                                value = genKeyPass,
                                onValueChange = { genKeyPass = it },
                                label = { Text("Key Password (min 6 chars)") },
                                singleLine = true,
                                visualTransformation = visualTrans,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                val resolvedKeyPass = if (sameKeyPass) genStorePass else genKeyPass
                                isBusy = true
                                statusMessage = null
                                coroutineScope.launch(Dispatchers.Default) {
                                    val res = ReleaseKeystoreManager.generateReleaseKeystore(
                                        alias = genAlias,
                                        storePassword = genStorePass,
                                        keyPassword = resolvedKeyPass,
                                        commonName = genCommonName,
                                        organization = genOrganization
                                    )
                                    withContext(Dispatchers.Main) {
                                        isBusy = false
                                        if (res.isSuccess) {
                                            val data = res.getOrThrow()
                                            SecureTokenManager.saveReleaseKeystore(
                                                context = context,
                                                metadata = data.metadata,
                                                keystoreBase64 = data.keystoreBase64,
                                                storePassword = genStorePass,
                                                keyPassword = resolvedKeyPass
                                            )
                                            genStorePass = ""
                                            genKeyPass = ""
                                            refreshTrigger++
                                            onSigningUpdated()
                                            statusMessage = true to "RSA-2048 Release Keystore generated & encrypted cleanly. Tap 'Upload Secrets to GitHub Repo' above."
                                        } else {
                                            statusMessage = false to (res.exceptionOrNull()?.message ?: "Keystore generation failed.")
                                        }
                                    }
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("generate_keystore_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                        ) {
                            Icon(Icons.Default.EnhancedEncryption, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generate & Save Encrypted Keystore", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // Import Existing JKS / PKCS12 Form
                        OutlinedButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("pick_keystore_file_btn"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = NeonCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = pickedFileName ?: "Select .jks or .p12 Keystore File",
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedTextField(
                            value = impAlias,
                            onValueChange = { impAlias = it },
                            label = { Text("Key Alias") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val visualTrans = if (showPasswords) VisualTransformation.None else PasswordVisualTransformation()
                        OutlinedTextField(
                            value = impStorePass,
                            onValueChange = { impStorePass = it },
                            label = { Text("Store Password") },
                            singleLine = true,
                            visualTransformation = visualTrans,
                            trailingIcon = {
                                IconButton(onClick = { showPasswords = !showPasswords }) {
                                    Icon(
                                        imageVector = if (showPasswords) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle password visibility"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = impKeyPass,
                            onValueChange = { impKeyPass = it },
                            label = { Text("Key Password") },
                            singleLine = true,
                            visualTransformation = visualTrans,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                val targetUri = pickedUri
                                if (targetUri == null) {
                                    statusMessage = false to "Please select a .jks or .p12 file first."
                                    return@Button
                                }
                                isBusy = true
                                statusMessage = null
                                coroutineScope.launch(Dispatchers.IO) {
                                    val res = ReleaseKeystoreManager.importKeystoreFromUri(
                                        context = context,
                                        uri = targetUri,
                                        alias = impAlias,
                                        storePassword = impStorePass,
                                        keyPassword = impKeyPass
                                    )
                                    withContext(Dispatchers.Main) {
                                        isBusy = false
                                        if (res.isSuccess) {
                                            val data = res.getOrThrow()
                                            SecureTokenManager.saveReleaseKeystore(
                                                context = context,
                                                metadata = data.metadata,
                                                keystoreBase64 = data.keystoreBase64,
                                                storePassword = impStorePass,
                                                keyPassword = impKeyPass
                                            )
                                            impStorePass = ""
                                            impKeyPass = ""
                                            refreshTrigger++
                                            onSigningUpdated()
                                            statusMessage = true to "Keystore verified & saved in Android Keystore. Tap 'Upload Secrets to GitHub Repo' above."
                                        } else {
                                            statusMessage = false to (res.exceptionOrNull()?.message ?: "Keystore verification failed.")
                                        }
                                    }
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("import_keystore_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Verify & Save Keystore", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

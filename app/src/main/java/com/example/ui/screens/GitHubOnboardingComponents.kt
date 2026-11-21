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

@Composable
internal fun ModernGitHubOnboardingView(
    patInput: String,
    onPatInputChange: (String) -> Unit,
    showPatText: Boolean,
    onToggleShowPat: () -> Unit,
    isVerifying: Boolean,
    patError: String?,
    onVerifyAndConnect: () -> Unit,
    onStartOAuthDeviceFlow: () -> Unit,
    onOpenCreateClassicPat: () -> Unit,
    onOpenCreateFinePat: () -> Unit,
    onOpenSignUp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.25f), NeonPurple.copy(alpha = 0.35f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Hub, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Connect Your GitHub Account", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                        Text("Zero-error API sync • Cloud APK CI/CD • Keystore Encrypted", fontSize = 11.sp, color = EmeraldGreen)
                    }
                }

                OutlinedTextField(
                    value = patInput,
                    onValueChange = onPatInputChange,
                    label = { Text("GitHub Personal Access Token") },
                    placeholder = { Text("ghp_... or github_pat_...", fontSize = 13.sp) },
                    singleLine = true,
                    visualTransformation = if (showPatText) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleShowPat) {
                            Icon(if (showPatText) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle visibility")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (patInput.isNotBlank()) {
                    Text(
                        text = "Detected: ${GitHubAccountHelper.classifyTokenFormat(patInput)}",
                        fontSize = 11.sp,
                        color = NeonCyan
                    )
                }

                if (patError != null) {
                    Surface(
                        color = CoralRed.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.4f))
                    ) {
                        Text(patError, color = CoralRed, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                    }
                }

                Button(
                    onClick = onVerifyAndConnect,
                    enabled = patInput.isNotBlank() && !isVerifying,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Slate950)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verifying Token...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verify & Connect GitHub", fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onStartOAuthDeviceFlow,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect via GitHub OAuth Device Flow", color = NeonPurple, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Quick Help Card
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = BorderStroke(1.dp, Slate800)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Need a Token?", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                Text(
                    "Recommended scopes: 'repo' and 'workflow' so Nexa IDE can list repositories, push code, and run Cloud APK builds cleanly.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onOpenCreateClassicPat,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Classic PAT", fontSize = 11.sp, color = NeonCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp), tint = NeonCyan)
                    }
                    OutlinedButton(
                        onClick = onOpenCreateFinePat,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Fine-grained", fontSize = 11.sp, color = EmeraldGreen)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp), tint = EmeraldGreen)
                    }
                }
                TextButton(onClick = onOpenSignUp, modifier = Modifier.align(Alignment.End)) {
                    Text("Create a free GitHub account", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

@Composable
internal fun EmptyStudioState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Slate900)
                .border(1.dp, Slate800, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(30.dp))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = Color(0xFF94A3B8),
            lineHeight = 18.sp
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(actionLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun MiniMetaPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.NexaPreferences
import com.example.data.security.SecureTokenManager
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: NexaPreferences,
    storageUsageBytes: Long,
    onSetThemeMode: (String) -> Unit,
    onSetFontSize: (Float) -> Unit,
    onSetTabSize: (Int) -> Unit,
    onSetAutoSave: (Boolean) -> Unit,
    onSetEditorTheme: (String) -> Unit,
    onClearCache: () -> Unit,
    onOpenApiSettings: () -> Unit = {},
    onAddNewModel: () -> Unit = onOpenApiSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storageMb = String.format(Locale.US, "%.1f MB", storageUsageBytes / (1024.0 * 1024.0))
    val savedModels = remember { SecureTokenManager.getAiModelConfigs(context) }
    val activeModel = remember(savedModels) { SecureTokenManager.getActiveAiModel(context) }
    val hasLegacyGemini = remember { SecureTokenManager.hasGeminiApiKey(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
    ) {
        TopAppBar(
            title = { Text("Settings", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API & AI Providers Section
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenApiSettings() }
                        .testTag("settings_api_section_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Api, contentDescription = null, tint = NeonPurple)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "Add API Keys & AI Model Providers",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Open API Settings",
                                tint = NeonCyan
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                if (activeModel != null) {
                                    val (cfg, key) = activeModel
                                    Text(
                                        text = "Active: ${cfg.displayName} (${cfg.modelName})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EmeraldGreen
                                    )
                                    Text(
                                        text = "Key: ${if (cfg.requiresApiKey) SecureTokenManager.maskToken(key) else "Local Endpoint"} • ${savedModels.size} saved",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (hasLegacyGemini) {
                                    Text(
                                        text = "Active: Google Gemini (Configured)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EmeraldGreen
                                    )
                                    Text(
                                        text = "Tap Manage API to add OpenRouter, Ollama, or custom providers",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "No API Model Configured",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NeonCyan
                                    )
                                    Text(
                                        text = "OpenRouter, APIQIK, WusRouter, Xingchi, Ollama, LM Studio & Custom",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenApiSettings,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_open_api_button")
                            ) {
                                Text("Manage API")
                            }
                            Button(
                                onClick = onAddNewModel,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_add_new_model_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Model")
                            }
                        }
                    }
                }
            }

            // Theme Mode
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = NeonCyan)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Application Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("DARK", "LIGHT", "SYSTEM").forEach { mode ->
                                FilterChip(
                                    selected = preferences.themeMode == mode,
                                    onClick = { onSetThemeMode(mode) },
                                    label = { Text(mode) }
                                )
                            }
                        }
                    }
                }
            }

            // Editor Preferences
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TextFields, contentDescription = null, tint = NeonCyan)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("CodeMirror 6 Editor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        // Font size slider
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Font Size", fontSize = 13.sp)
                                Text("${preferences.editorFontSize.toInt()} sp", fontWeight = FontWeight.Bold, color = NeonCyan)
                            }
                            Slider(
                                value = preferences.editorFontSize,
                                onValueChange = onSetFontSize,
                                valueRange = 10f..22f,
                                steps = 11
                            )
                        }

                        // Tab size
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Tab Indent Size", fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = preferences.editorTabSize == 2,
                                    onClick = { onSetTabSize(2) },
                                    label = { Text("2 Spaces") }
                                )
                                FilterChip(
                                    selected = preferences.editorTabSize == 4,
                                    onClick = { onSetTabSize(4) },
                                    label = { Text("4 Spaces") }
                                )
                            }
                        }

                        // Editor Theme
                        Column {
                            Text("Editor Color Scheme", fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    Pair("one-dark", "One Dark"),
                                    Pair("dracula", "Dracula"),
                                    Pair("material-light", "Light")
                                ).forEach { (id, label) ->
                                    FilterChip(
                                        selected = preferences.editorTheme == id,
                                        onClick = { onSetEditorTheme(id) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }

                        // Auto Save toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Auto Save", fontWeight = FontWeight.SemiBold)
                                Text("Automatically save source files while typing", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = preferences.editorAutoSave,
                                onCheckedChange = onSetAutoSave
                            )
                        }
                    }
                }
            }

            // Storage Management
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = NeonCyan)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Local Storage Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Current Storage Used:", fontSize = 13.sp)
                            Text(storageMb, fontWeight = FontWeight.Bold, color = NeonCyan)
                        }

                        OutlinedButton(
                            onClick = {
                                onClearCache()
                                Toast.makeText(context, "Temporary build files cleared", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Clean Temporary Build Cache")
                        }
                    }
                }
            }
        }
    }
}

package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.AiModelConfig
import com.example.data.ai.AiProviderType
import com.example.data.ai.GeminiAiService
import com.example.data.ai.PopularAiProvider
import com.example.data.ai.PopularProvidersCatalog
import com.example.data.security.SecureTokenManager
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.Slate800
import kotlinx.coroutines.launch
import java.util.UUID

private val ModelBgDark = Color(0xFF121218)
private val ModelCardDark = Color(0xFF1C1E26)
private val ModelTabContainerDark = Color(0xFF1F2028)
private val ModelPurpleAccent = Color(0xFFA78BFA)
private val ModelPurpleSwitch = Color(0xFF8B5CF6)
private val ModelBlueButton = Color(0xFF0277BD)
private val ModelMutedText = Color(0xFF9CA3AF)
private val ModelBorder = Color(0xFF3A3D4A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNewModelScreen(
    editingModel: AiModelConfig? = null,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val aiService = remember { GeminiAiService(context) }

    val existingKey = remember(editingModel?.id) {
        editingModel?.let { SecureTokenManager.getAiModelApiKey(context, it.id).orEmpty() } ?: ""
    }

    // 0 = Popular Providers (Quick), 1 = Custom Provider
    var selectedTab by remember(editingModel?.id) {
        mutableIntStateOf(if (editingModel != null) 1 else 0)
    }

    var displayName by remember(editingModel?.id) { mutableStateOf(editingModel?.displayName ?: "") }
    var providerType by remember(editingModel?.id) {
        mutableStateOf(editingModel?.providerType ?: AiProviderType.OPENAI_COMPATIBLE.label)
    }
    var baseUrl by remember(editingModel?.id) { mutableStateOf(editingModel?.baseUrl ?: "") }
    var apiKey by remember(editingModel?.id) { mutableStateOf(existingKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var modelName by remember(editingModel?.id) { mutableStateOf(editingModel?.modelName ?: "") }

    var requiresApiKey by remember(editingModel?.id) {
        mutableStateOf(editingModel?.requiresApiKey ?: true)
    }
    var supportsTools by remember(editingModel?.id) {
        mutableStateOf(editingModel?.supportsTools ?: true)
    }
    var supportsVision by remember(editingModel?.id) {
        mutableStateOf(editingModel?.supportsVision ?: false)
    }
    var streamingResponses by remember(editingModel?.id) {
        mutableStateOf(editingModel?.streamingResponses ?: true)
    }

    var providerMenuExpanded by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResultMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun applyPopularPreset(provider: PopularAiProvider) {
        displayName = provider.name
        providerType = provider.providerType
        baseUrl = provider.defaultBaseUrl
        modelName = provider.defaultModelName
        requiresApiKey = provider.requiresApiKey
        supportsTools = provider.defaultSupportsTools
        supportsVision = provider.defaultSupportsVision
        streamingResponses = provider.defaultStreaming
        validationError = null
        testResultMessage = null
        selectedTab = 1
    }

    fun buildCandidateConfig(): AiModelConfig {
        return AiModelConfig(
            id = editingModel?.id ?: UUID.randomUUID().toString(),
            displayName = displayName.trim(),
            providerType = providerType,
            baseUrl = baseUrl.trim(),
            modelName = modelName.trim(),
            supportsTools = supportsTools,
            supportsVision = supportsVision,
            streamingResponses = streamingResponses,
            requiresApiKey = requiresApiKey,
            createdAt = editingModel?.createdAt ?: System.currentTimeMillis()
        )
    }

    fun validateInputs(): String? {
        if (displayName.isBlank()) return "Please enter a Display Name"
        val cleanUrl = baseUrl.trim()
        if (cleanUrl.isBlank() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
            return "Base URL must start with https:// or http://"
        }
        val isLocal = providerType == AiProviderType.OLLAMA.label ||
            cleanUrl.contains("127.0.0.1") ||
            cleanUrl.contains("localhost")
        if (requiresApiKey && !isLocal && apiKey.isBlank()) {
            return "Please enter your API Key"
        }
        if (modelName.isBlank()) return "Please enter a Model Name"
        return null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ModelBgDark)
            .imePadding()
            .navigationBarsPadding()
            .testTag("add_new_model_screen")
    ) {
        TopAppBar(
            title = {
                Text(
                    text = if (editingModel != null) "Edit Model" else "Add New Model",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("add_model_back_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = ModelBgDark,
                titleContentColor = Color.White
            )
        )

        // Segmented Tabs Bar ("Popular Providers (Quick)" | "Custom Provider")
        Surface(
            color = ModelTabContainerDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                SegmentedModelTab(
                    label = "Popular Providers (Quick)",
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_popular_providers")
                )
                SegmentedModelTab(
                    label = "Custom Provider",
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_custom_provider")
                )
            }
        }

        if (selectedTab == 0) {
            // Popular Providers (Quick) List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = "Select Popular Provider",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }

                items(PopularProvidersCatalog.providers, key = { it.id }) { provider ->
                    PopularProviderCard(
                        provider = provider,
                        onConfigure = { applyPopularPreset(provider) }
                    )
                }
            }
        } else {
            // Custom Provider / Configure Form
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = ModelPurpleAccent,
                    unfocusedBorderColor = ModelBorder,
                    focusedLabelColor = ModelPurpleAccent,
                    unfocusedLabelColor = ModelMutedText,
                    focusedPlaceholderColor = ModelMutedText,
                    unfocusedPlaceholderColor = ModelMutedText,
                    cursorColor = ModelPurpleAccent
                )

                // 1. Display Name
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        validationError = null
                    },
                    label = { Text("Display Name (e.g. My OpenAI)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_display_name")
                )

                // 2. Provider Type Dropdown
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = { providerMenuExpanded = !providerMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = providerType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider Type") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .testTag("dropdown_provider_type")
                    )

                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false },
                        containerColor = ModelCardDark
                    ) {
                        AiProviderType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label, color = Color.White) },
                                onClick = {
                                    providerType = type.label
                                    requiresApiKey = type != AiProviderType.OLLAMA
                                    if (type == AiProviderType.GOOGLE_GEMINI && baseUrl.isBlank()) {
                                        baseUrl = "https://generativelanguage.googleapis.com/v1beta"
                                        if (modelName.isBlank()) modelName = "gemini-2.5-flash"
                                    } else if (type == AiProviderType.ANTHROPIC && baseUrl.isBlank()) {
                                        baseUrl = "https://api.anthropic.com/v1"
                                        if (modelName.isBlank()) modelName = "claude-3-5-sonnet-20241022"
                                    } else if (type == AiProviderType.OLLAMA && baseUrl.isBlank()) {
                                        baseUrl = "http://127.0.0.1:11434/v1"
                                        if (modelName.isBlank()) modelName = "llama3.2"
                                    }
                                    providerMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // 3. Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        validationError = null
                    },
                    label = { Text("Base URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_base_url")
                )

                // 4. API Key with Eye Icon Visibility Toggle
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        validationError = null
                    },
                    label = {
                        Text(if (requiresApiKey) "API Key" else "API Key (Optional for Local)")
                    },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKey) "Hide API Key" else "Show API Key",
                                tint = ModelMutedText
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_api_key")
                )

                // 5. Model Name
                OutlinedTextField(
                    value = modelName,
                    onValueChange = {
                        modelName = it
                        validationError = null
                    },
                    label = { Text("Model Name (e.g. gpt-4o, claude-3-5-sonnet)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_model_name")
                )

                // Capability Switch Cards
                ModelCapabilityToggleCard(
                    title = "Supports Tools (Function Calling)",
                    subtitle = "Keep enabled to allow AI to perform phone actions",
                    checked = supportsTools,
                    onCheckedChange = { supportsTools = it }
                )

                ModelCapabilityToggleCard(
                    title = "Supports Vision / Images (Screenshot AI)",
                    subtitle = "Enable if this model can analyze images (e.g. GPT-4o, Gemini, Claude)",
                    checked = supportsVision,
                    onCheckedChange = { supportsVision = it }
                )

                ModelCapabilityToggleCard(
                    title = "Streaming Responses",
                    subtitle = "Display tokens live as they arrive instead of waiting (ON by default)",
                    checked = streamingResponses,
                    onCheckedChange = { streamingResponses = it }
                )

                // Validation error banner
                AnimatedVisibility(visible = validationError != null) {
                    Surface(
                        color = CoralRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CoralRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(validationError.orEmpty(), color = CoralRed, fontSize = 12.sp)
                        }
                    }
                }

                // Test Connection result banner
                AnimatedVisibility(visible = testResultMessage != null) {
                    val (success, msg) = testResultMessage ?: (false to "")
                    val tint = if (success) EmeraldGreen else CoralRed
                    Surface(
                        color = tint.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(msg, color = tint, fontSize = 12.sp)
                        }
                    }
                }

                // Action Buttons Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val err = validateInputs()
                            if (err != null) {
                                validationError = err
                                return@OutlinedButton
                            }
                            isTesting = true
                            testResultMessage = null
                            val candidate = buildCandidateConfig()
                            coroutineScope.launch {
                                val res = aiService.testProviderConnection(candidate, apiKey)
                                isTesting = false
                                testResultMessage = res.fold(
                                    onSuccess = { true to it },
                                    onFailure = { false to (it.message ?: "Connection failed") }
                                )
                            }
                        },
                        enabled = !isTesting,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ModelBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("test_connection_button")
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ModelPurpleAccent
                            )
                        } else {
                            Text("Test Connection", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }

                    Button(
                        onClick = {
                            val err = validateInputs()
                            if (err != null) {
                                validationError = err
                                return@Button
                            }
                            val cleanUrl = baseUrl.trim()
                            val isLocal = providerType == AiProviderType.OLLAMA.label ||
                                cleanUrl.contains("127.0.0.1") ||
                                cleanUrl.contains("localhost")
                            val config = buildCandidateConfig().copy(requiresApiKey = !isLocal && requiresApiKey)
                            SecureTokenManager.saveAiModelConfig(
                                context = context,
                                config = config,
                                apiKey = apiKey,
                                setAsActive = true
                            )
                            Toast.makeText(context, "${config.displayName} saved securely", Toast.LENGTH_SHORT).show()
                            onSaved()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ModelPurpleSwitch,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("save_model_button")
                    ) {
                        Text("Save Model", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedModelTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) ModelPurpleAccent else ModelMutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(
            thickness = if (selected) 2.5.dp else 0.dp,
            color = if (selected) ModelPurpleAccent else Color.Transparent
        )
    }
}

@Composable
private fun PopularProviderCard(
    provider: PopularAiProvider,
    onConfigure: () -> Unit
) {
    Surface(
        color = ModelCardDark,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Slate800.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = provider.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = provider.description,
                    fontSize = 12.sp,
                    color = ModelMutedText,
                    lineHeight = 16.sp
                )
            }

            Button(
                onClick = onConfigure,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ModelBlueButton,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                modifier = Modifier.testTag("configure_${provider.id}")
            ) {
                Text(
                    text = "Configure",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ModelCapabilityToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = ModelCardDark,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = ModelMutedText,
                    lineHeight = 16.sp
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ModelPurpleSwitch,
                    uncheckedThumbColor = Color(0xFF9CA3AF),
                    uncheckedTrackColor = Color(0xFF2D2F3A),
                    uncheckedBorderColor = Color(0xFF4B5563)
                )
            )
        }
    }
}

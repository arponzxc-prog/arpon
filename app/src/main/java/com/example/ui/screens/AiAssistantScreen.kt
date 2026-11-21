package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.*
import com.example.data.ai.agent.*
import com.example.data.filesystem.ProjectFileManager
import com.example.data.local.entity.ProjectEntity
import com.example.data.security.SecureTokenManager
import com.example.ui.components.AiDiffDialog
import com.example.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    currentFileName: String? = null,
    currentFileContent: String? = null,
    initialPrompt: String? = null,
    activeProject: ProjectEntity? = null,
    fileManager: ProjectFileManager? = null,
    onCreateProjectRecord: (suspend (ProjectEntity) -> Unit)? = null,
    onProjectUpdated: (suspend (ProjectEntity) -> Unit)? = null,
    onOpenProjectInEditor: ((ProjectEntity) -> Unit)? = null,
    onPreviewProject: ((ProjectEntity) -> Unit)? = null,
    onBuildProject: ((ProjectEntity) -> Unit)? = null,
    onApplyCodeToEditor: ((newCode: String) -> Unit)? = null,
    onOpenApiSettings: (() -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val aiService = remember { GeminiAiService(context) }

    var apiKeyDialog by remember { mutableStateOf(false) }
    var inputApiKey by remember { mutableStateOf("") }
    var hasValidKey by remember { mutableStateOf(!aiService.getEffectiveApiKey().isNullOrBlank()) }
    var providerSummary by remember { mutableStateOf(aiService.getActiveProviderSummary()) }

    val canExecuteAgent = fileManager != null && onCreateProjectRecord != null
    var agentModeEnabled by remember(canExecuteAgent) { mutableStateOf(canExecuteAgent) }
    var currentTargetProject by remember(activeProject) { mutableStateOf(activeProject) }
    val projectRef = remember { AtomicReference<ProjectEntity?>(activeProject) }
    LaunchedEffect(currentTargetProject) { projectRef.set(currentTargetProject) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var undoCount by remember { mutableStateOf(0) }

    val agentRunner = remember(canExecuteAgent) {
        if (fileManager != null && onCreateProjectRecord != null) {
            NexaAgentRunner(
                transport = AgentModelClient(context),
                host = object : AgentProjectHost {
                    override fun currentProject(): ProjectEntity? = projectRef.get()
                    override suspend fun createProject(name: String, packageName: String, template: String, language: String): ProjectEntity {
                        val created = withContext(Dispatchers.IO) { fileManager.createProject(name, packageName, template, language) }
                        onCreateProjectRecord(created)
                        projectRef.set(created)
                        withContext(Dispatchers.Main) { currentTargetProject = created }
                        return created
                    }
                }
            )
        } else null
    }

    var messages by remember {
        mutableStateOf(
            listOf(
                AiChatMessage(
                    isUser = false,
                    text = if (canExecuteAgent) {
                        "Hello! I am Nexa AI Autonomous Builder. Tell me to create a brand-new Android (Kotlin/Compose/Java) or Web (HTML/CSS/JS) project, or ask me to write & edit code files directly in your project!"
                    } else {
                        "Hello! I am Nexa AI, your coding assistant. I can explain code, fix errors, refactor, or help you with your Android & Web projects. How can I help you today?"
                    }
                )
            )
        )
    }

    var inputText by remember(initialPrompt) { mutableStateOf(initialPrompt.orEmpty()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusLabel by remember { mutableStateOf("Nexa AI is thinking...") }
    var activeProposalForDiff by remember { mutableStateOf<AiCodeProposal?>(null) }
    val listState = rememberLazyListState()

    fun sendPrompt(prompt: String, isCodeAction: Boolean = false, codeActionType: String = "") {
        if (prompt.isBlank()) return
        if (!hasValidKey) {
            apiKeyDialog = true
            return
        }

        val userMessage = AiChatMessage(isUser = true, text = prompt)
        messages = messages + userMessage
        inputText = ""
        isLoading = true
        statusLabel = if (agentModeEnabled && !isCodeAction) {
            "Nexa AI is designing & coding..."
        } else {
            "Nexa AI is thinking..."
        }

        runJob = coroutineScope.launch {
            try {
                if (isCodeAction && !currentFileContent.isNullOrBlank()) {
                    val fileName = currentFileName ?: "ActiveFile"
                    when (codeActionType) {
                        "FIX" -> {
                            val proposal = aiService.fixErrors(currentFileContent, prompt, fileName)
                            messages = messages + AiChatMessage(
                                isUser = false,
                                text = "Here is the proposed fix for '$fileName':\n\n${proposal.summary}",
                                proposal = proposal
                            )
                        }
                        "REFACTOR" -> {
                            val proposal = aiService.refactorCode(currentFileContent, prompt, fileName)
                            messages = messages + AiChatMessage(
                                isUser = false,
                                text = "Here is the refactored code for '$fileName':\n\n${proposal.summary}",
                                proposal = proposal
                            )
                        }
                        "EXPLAIN" -> {
                            val explanation = aiService.explainCode(currentFileContent, fileName)
                            messages = messages + AiChatMessage(isUser = false, text = explanation)
                        }
                        "REVIEW" -> {
                            val review = aiService.codeReview(currentFileContent, fileName)
                            messages = messages + AiChatMessage(isUser = false, text = review)
                        }
                        else -> {
                            val response = aiService.sendMessage(
                                prompt = prompt,
                                history = messages,
                                currentFileName = currentFileName,
                                currentFileContent = currentFileContent
                            )
                            messages = messages + AiChatMessage(isUser = false, text = response)
                        }
                    }
                } else if (agentModeEnabled && agentRunner != null) {
                    val projectIdBefore = projectRef.get()?.id
                    val result = withContext(Dispatchers.IO) {
                        agentRunner.send(
                            prompt = prompt,
                            events = object : AgentEvents {
                                override fun onModelThinking(step: Int) { statusLabel = "Nexa AI কাজ করছে / working… ($step)" }
                                override fun onToolStarted(call: ToolCall) { statusLabel = AgentStatusText.label(call) }
                                override fun onNotice(message: String) { statusLabel = message }
                            },
                            currentFile = currentFileName
                        )
                    }
                    val proj = projectRef.get()
                    val changed = (result.filesWritten + result.filesEdited).distinct()
                    val exec = if (proj != null && result.changedFileCount > 0) {
                        AiExecutionResult(
                            project = proj,
                            createdNewProject = proj.id != projectIdBefore,
                            writtenFiles = changed,
                            skippedFiles = emptyList(),
                            summary = result.finalText
                        )
                    } else null
                    if (proj != null && result.changedFileCount > 0) onProjectUpdated?.invoke(proj.copy(lastModifiedAt = System.currentTimeMillis()))
                    undoCount = if (agentRunner.canUndo()) result.changedFileCount else 0
                    val text = buildString {
                        val title = AgentStatusText.summaryTitle(result)
                        if (title.isNotBlank()) appendLine(title)
                        if (result.finalText.isNotBlank()) appendLine(result.finalText)
                        result.pendingQuestion?.let { appendLine(); appendLine(it.toDisplayText()) }
                        result.notices.distinct().forEach { appendLine(it) }
                        if (result.remainingProblems.isNotEmpty()) {
                            appendLine()
                            appendLine("এখনও ${result.remainingProblems.size}টি সমস্যা বাকি / ${result.remainingProblems.size} problem(s) remain:")
                            result.remainingProblems.take(5).forEach { appendLine("• ${it.file}:${it.line} ${it.message}") }
                        }
                        if (result.failedToolCalls > 0) appendLine("(${result.failedToolCalls} টুল কল ব্যর্থ হয়েছিল ও মডেল শুধরে নিয়েছে / tool call(s) failed and were retried)")
                    }.trim().ifBlank { "✅ Done" }
                    messages = messages + AiChatMessage(isUser = false, text = text, executionResult = exec)
                } else {
                    val projectCtx = if (agentModeEnabled && fileManager != null) {
                        NexaAiAgentEngine.buildProjectContextSummary(currentTargetProject, fileManager)
                    } else null

                    val response = aiService.sendAgentMessage(
                        prompt = prompt,
                        history = messages,
                        agentModeEnabled = agentModeEnabled && canExecuteAgent,
                        projectContextSummary = projectCtx,
                        currentFileName = currentFileName,
                        currentFileContent = currentFileContent
                    )

                    val plan = if (agentModeEnabled && fileManager != null && onCreateProjectRecord != null) {
                        NexaAiAgentEngine.parseAgentPlan(response)
                    } else null

                    if (plan != null && fileManager != null && onCreateProjectRecord != null) {
                        statusLabel = "Creating project & writing ${plan.files.size} file(s)..."
                        val execResult = NexaAiAgentEngine.executePlan(
                            plan = plan,
                            currentActiveProject = currentTargetProject,
                            fileManager = fileManager,
                            onInsertProject = { newProj -> onCreateProjectRecord(newProj) },
                            onUpdateProject = { updatedProj -> onProjectUpdated?.invoke(updatedProj) }
                        )
                        currentTargetProject = execResult.project
                        messages = messages + AiChatMessage(
                            isUser = false,
                            text = plan.summary,
                            executionResult = execResult
                        )
                    } else {
                        messages = messages + AiChatMessage(isUser = false, text = response)
                    }
                }

                listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
            } catch (e: CancellationException) {
                undoCount = if (agentRunner?.canUndo() == true) undoCount.coerceAtLeast(1) else 0
                messages = messages + AiChatMessage(isUser = false, text = "⏹ থামানো হয়েছে / Stopped.")
            } catch (e: Exception) {
                messages = messages + AiChatMessage(
                    isUser = false,
                    text = "⚠️ Nexa AI Error: ${e.message}"
                )
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .navigationBarsPadding()
            .imePadding()
            .testTag("ai_assistant_screen")
    ) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(NeonPurple.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Nexa AI Studio", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = providerSummary,
                            fontSize = 11.sp,
                            color = if (hasValidKey) EmeraldGreen else AmberYellow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = {
                    if (onOpenApiSettings != null) {
                        onOpenApiSettings()
                    } else {
                        apiKeyDialog = true
                    }
                }) {
                    Icon(Icons.Default.Key, contentDescription = "Configure API Provider", tint = if (hasValidKey) EmeraldGreen else AmberYellow)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900, titleContentColor = Color.White)
        )

        // Agent Mode Control Bar
        if (canExecuteAgent) {
            Surface(
                color = Slate900,
                modifier = Modifier.fillMaxWidth().border(1.dp, Slate800)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PrecisionManufacturing,
                        contentDescription = null,
                        tint = if (agentModeEnabled) EmeraldGreen else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (agentModeEnabled) "Autonomous Project & Code Agent: ON" else "Chat Only Mode",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (agentModeEnabled) EmeraldGreen else Color.LightGray
                        )
                        Text(
                            text = currentTargetProject?.let { "Target: ${it.name} (${it.language})" }
                                ?: "Ready to scaffold new projects & write multi-file code",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = agentModeEnabled,
                        onCheckedChange = { agentModeEnabled = it },
                        modifier = Modifier.testTag("ai_agent_mode_switch")
                    )
                }
            }
        }

        // API Key missing banner
        if (!hasValidKey) {
            Surface(
                color = AmberYellow.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (onOpenApiSettings != null) onOpenApiSettings() else apiKeyDialog = true
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AmberYellow.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = AmberYellow, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Configure an AI API key (OpenRouter, Gemini, Ollama, Custom) in Settings → API to start.",
                        fontSize = 12.sp,
                        color = AmberYellow,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Active File Context Indicator
        if (!currentFileName.isNullOrBlank()) {
            Surface(
                color = Slate900,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Slate800)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open File: $currentFileName",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Quick Action Chips (either Single File Actions or Autonomous Project Starter Prompts)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Slate900)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!currentFileContent.isNullOrBlank()) {
                ActionChip("Explain Code", Icons.Default.Info) {
                    sendPrompt("Explain this code", isCodeAction = true, codeActionType = "EXPLAIN")
                }
                ActionChip("Fix Errors", Icons.Default.BugReport) {
                    sendPrompt("Find and fix any errors in this file", isCodeAction = true, codeActionType = "FIX")
                }
                ActionChip("Refactor & Clean", Icons.Default.AutoFixHigh) {
                    sendPrompt("Refactor this code for readability and performance", isCodeAction = true, codeActionType = "REFACTOR")
                }
                ActionChip("Code Review", Icons.Default.RateReview) {
                    sendPrompt("Perform a comprehensive code review", isCodeAction = true, codeActionType = "REVIEW")
                }
            }
            if (canExecuteAgent && agentModeEnabled) {
                ActionChip("New Compose Calculator", Icons.Default.Calculate) {
                    sendPrompt("Create a complete new Jetpack Compose Calculator Android project with Material 3 dark UI and working math operations.")
                }
                ActionChip("New Todo App", Icons.Default.CheckBox) {
                    sendPrompt("Create a brand-new Kotlin Jetpack Compose Todo & Task Manager project with add, complete, and delete functionality.")
                }
                ActionChip("New Portfolio Webapp", Icons.Default.Language) {
                    sendPrompt("Create a new modern responsive HTML Multi-File developer portfolio project (index.html, style.css, script.js) with dark animations.")
                }
            }
        }

        // Message List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(
                    message = msg,
                    onCopy = { text ->
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onOpenDiff = { proposal -> activeProposalForDiff = proposal },
                    onOpenProjectInEditor = onOpenProjectInEditor,
                    onPreviewProject = onPreviewProject,
                    onBuildProject = onBuildProject
                )
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = NeonCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(statusLabel, fontSize = 12.sp, color = NeonCyan)
                    }
                }
            }
        }

        if (undoCount > 0 && agentRunner != null) {
            Surface(color = Slate900, modifier = Modifier.fillMaxWidth().border(1.dp, Slate800)) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$undoCount ফাইল বদলেছে / files changed",
                        fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val restored = agentRunner.undo()
                            undoCount = 0
                            projectRef.get()?.let { p -> coroutineScope.launch { onProjectUpdated?.invoke(p.copy(lastModifiedAt = System.currentTimeMillis())) } }
                            messages = messages + AiChatMessage(isUser = false, text = "↩ ${restored.size}টি ফাইল আগের অবস্থায় ফেরানো হয়েছে / files restored.")
                        },
                        modifier = Modifier.testTag("ai_undo_button")
                    ) { Text("Undo", color = NeonCyan) }
                }
            }
        }

        // Input Bar
        Surface(
            color = Slate900,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Slate800)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            if (agentModeEnabled && canExecuteAgent) {
                                "Describe an app to build or code to write..."
                            } else {
                                "Ask Nexa AI a question..."
                            },
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .testTag("ai_prompt_input"),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Slate700
                    )
                )

                IconButton(
                    onClick = {
                        if (isLoading) {
                            runJob?.cancel()
                        } else if (inputText.isNotBlank()) {
                            sendPrompt(inputText)
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isLoading) AmberYellow else if (inputText.isNotBlank()) NeonCyan else Slate800)
                        .testTag("ai_send_button")
                ) {
                    Icon(
                        imageVector = if (isLoading) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isLoading) "Stop / থামান" else "Send / পাঠান",
                        tint = if (isLoading || inputText.isNotBlank()) Slate950 else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // Safe Diff Dialog
    activeProposalForDiff?.let { proposal ->
        AiDiffDialog(
            proposal = proposal,
            aiService = aiService,
            onAccept = { newCode ->
                onApplyCodeToEditor?.invoke(newCode)
                Toast.makeText(context, "Applied changes to editor!", Toast.LENGTH_SHORT).show()
                activeProposalForDiff = null
            },
            onDismiss = { activeProposalForDiff = null }
        )
    }

    // Legacy Gemini API Key Config Dialog
    if (apiKeyDialog) {
        AlertDialog(
            onDismissRequest = { apiKeyDialog = false },
            title = { Text("Google Gemini API Key", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter your Google AI Studio Gemini API key, or use Settings → API for OpenRouter/Anthropic/Ollama/Custom providers.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = inputApiKey,
                        onValueChange = { inputApiKey = it },
                        label = { Text("Gemini API Key (AIzaSy...)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputApiKey.isNotBlank()) {
                            SecureTokenManager.saveGeminiApiKey(context, inputApiKey)
                            hasValidKey = true
                            providerSummary = aiService.getActiveProviderSummary()
                            apiKeyDialog = false
                            Toast.makeText(context, "Gemini API Key securely saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Save Key") }
            },
            dismissButton = {
                TextButton(onClick = { apiKeyDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ActionChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Slate800,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Slate700)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text, fontSize = 11.sp, color = Color.White)
        }
    }
}

@Composable
private fun MessageBubble(
    message: AiChatMessage,
    onCopy: (String) -> Unit,
    onOpenDiff: (AiCodeProposal) -> Unit,
    onOpenProjectInEditor: ((ProjectEntity) -> Unit)? = null,
    onPreviewProject: ((ProjectEntity) -> Unit)? = null,
    onBuildProject: ((ProjectEntity) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 330.dp),
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (message.isUser) 14.dp else 2.dp,
                bottomEnd = if (message.isUser) 2.dp else 14.dp
            ),
            color = if (message.isUser) NeonCyan.copy(alpha = 0.2f) else Slate900,
            border = BorderStroke(
                1.dp,
                if (message.isUser) NeonCyan.copy(alpha = 0.4f) else Slate800
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    fontSize = 13.sp,
                    color = Color.White,
                    lineHeight = 18.sp
                )

                // Autonomous Project & File Execution Card
                message.executionResult?.let { exec ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Slate950,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (exec.createdNewProject) {
                                        "Created Project: ${exec.project.name}"
                                    } else {
                                        "Updated Project: ${exec.project.name}"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = EmeraldGreen
                                )
                            }
                            Text(
                                text = "${exec.project.packageName} • ${exec.project.templateType}",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                            if (exec.writtenFiles.isNotEmpty()) {
                                exec.writtenFiles.take(8).forEach { rel ->
                                    Text(
                                        text = "✓ $rel",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = NeonCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (exec.writtenFiles.size > 8) {
                                    Text(
                                        text = "+${exec.writtenFiles.size - 8} more file(s) written",
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                            if (exec.skippedFiles.isNotEmpty()) {
                                Text(
                                    text = "Skipped unsafe paths: ${exec.skippedFiles.joinToString(", ")}",
                                    fontSize = 10.sp,
                                    color = CoralRed
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (onOpenProjectInEditor != null) {
                                    Button(
                                        onClick = { onOpenProjectInEditor(exec.project) },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Open Code", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (onPreviewProject != null &&
                                    (exec.project.language.equals("Webapp", ignoreCase = true) ||
                                        exec.project.templateType.contains("HTML", ignoreCase = true))
                                ) {
                                    OutlinedButton(
                                        onClick = { onPreviewProject(exec.project) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonPurple)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Preview", fontSize = 11.sp, color = NeonPurple)
                                    }
                                }
                                if (onBuildProject != null) {
                                    OutlinedButton(
                                        onClick = { onBuildProject(exec.project) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(14.dp), tint = EmeraldGreen)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("APK", fontSize = 11.sp, color = EmeraldGreen)
                                    }
                                }
                            }
                        }
                    }
                }

                // Single-file proposal diff card
                message.proposal?.let { proposal ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onOpenDiff(proposal) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compare Diff & Apply", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = { onCopy(message.proposal?.proposedCode ?: message.text) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Gray, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

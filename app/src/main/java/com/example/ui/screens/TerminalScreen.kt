package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.builder.BuildEngine
import com.example.builder.BuildProgressState
import com.example.data.filesystem.ProjectFileManager
import com.example.data.local.entity.ProjectEntity
import com.example.terminal.TerminalCommandOutcome
import com.example.terminal.TerminalEngine
import com.example.terminal.TerminalLineType
import com.example.terminal.TerminalOutputLine
import com.example.terminal.TerminalSession
import com.example.ui.theme.AmberYellow
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    project: ProjectEntity?,
    fileManager: ProjectFileManager,
    buildEngine: BuildEngine,
    buildState: BuildProgressState,
    onNavigateToBuildHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember(fileManager) { TerminalEngine(context, fileManager) }

    if (project == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Slate950),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(48.dp))
                Text("Select or create a project first to open the Terminal workspace.", color = Color.White)
                Button(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    fun createWelcomeLines(proj: ProjectEntity): List<TerminalOutputLine> = listOf(
        TerminalOutputLine(
            text = "Nexa IDE Integrated Terminal • Workspace: ${proj.name}",
            type = TerminalLineType.SUCCESS
        ),
        TerminalOutputLine(
            text = "Sandboxed Mode: Navigation restricted to project root. Type 'help' or tap a shortcut below.",
            type = TerminalLineType.INFO
        )
    )

    val sessions = remember(project.id) {
        mutableStateListOf(
            TerminalSession(
                title = "Session 1",
                projectRootPath = project.rootPath,
                currentDirPath = project.rootPath,
                lines = createWelcomeLines(project)
            )
        )
    }
    var activeSessionIndex by remember(project.id) { mutableIntStateOf(0) }
    val activeSession = sessions.getOrElse(activeSessionIndex) { sessions.first() }

    var commandInput by remember { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    var pendingConfirmation by remember { mutableStateOf<TerminalCommandOutcome.RequiresConfirmation?>(null) }
    var isStreamingBuildLogs by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Stream real BuildEngine output into active terminal when triggered from terminal
    LaunchedEffect(buildState, isStreamingBuildLogs) {
        if (isStreamingBuildLogs && buildState is BuildProgressState.Active) {
            val idx = activeSessionIndex.coerceIn(0, sessions.lastIndex)
            val s = sessions[idx]
            val logLines = buildState.liveLogs.lines().filter { it.isNotBlank() }.takeLast(40).map { line ->
                TerminalOutputLine(
                    text = line,
                    type = when {
                        line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> TerminalLineType.ERROR
                        line.contains("success", ignoreCase = true) -> TerminalLineType.SUCCESS
                        else -> TerminalLineType.INFO
                    }
                )
            }
            val base = s.lines.filterNot { it.id.startsWith("build_stream_") }
            val tagged = logLines.mapIndexed { i, l -> l.copy(id = "build_stream_$i") }
            sessions[idx] = s.copy(
                lines = base + tagged,
                isRunning = buildState.status !in listOf("SUCCESSFUL", "FAILED", "CANCELLED")
            )
            if (buildState.status in listOf("SUCCESSFUL", "FAILED", "CANCELLED")) {
                isStreamingBuildLogs = false
                sessions[idx] = sessions[idx].copy(
                    lines = sessions[idx].lines + TerminalOutputLine(
                        text = "Build finished with status: ${buildState.status}. Recorded in Build History.",
                        type = if (buildState.status == "SUCCESSFUL") TerminalLineType.SUCCESS else TerminalLineType.ERROR
                    ),
                    lastExitCode = if (buildState.status == "SUCCESSFUL") 0 else 1
                )
            }
        }
    }

    LaunchedEffect(activeSession.lines.size) {
        if (activeSession.lines.isNotEmpty()) {
            listState.animateScrollToItem(activeSession.lines.lastIndex)
        }
    }

    fun updateSession(index: Int, transform: (TerminalSession) -> TerminalSession) {
        if (index in sessions.indices) {
            sessions[index] = transform(sessions[index])
        }
    }

    fun runCommand(rawCmd: String, confirmedDestructive: Boolean = false) {
        val trimmed = rawCmd.trim()
        if (trimmed.isEmpty()) return
        val sessionIdx = activeSessionIndex.coerceIn(0, sessions.lastIndex)
        val session = sessions[sessionIdx]
        val relDir = engine.getRelativeWorkspacePath(File(project.rootPath), File(session.currentDirPath))

        commandInput = ""
        historyCursor = -1

        updateSession(sessionIdx) { s ->
            s.copy(
                lines = s.lines + TerminalOutputLine(
                    text = "$relDir $ $trimmed",
                    type = TerminalLineType.COMMAND
                ),
                history = (s.history + trimmed).takeLast(50),
                isRunning = true
            )
        }

        runningJob?.cancel()
        runningJob = coroutineScope.launch {
            try {
                when (val outcome = engine.executeCommand(trimmed, project, session.currentDirPath, confirmedDestructive)) {
                    is TerminalCommandOutcome.RequiresConfirmation -> {
                        updateSession(sessionIdx) { it.copy(isRunning = false) }
                        pendingConfirmation = outcome
                    }
                    is TerminalCommandOutcome.TriggerCloudGradle -> {
                        isStreamingBuildLogs = true
                        updateSession(sessionIdx) { s ->
                            s.copy(
                                lines = s.lines + TerminalOutputLine(
                                    text = "Dispatching ${outcome.task} (${outcome.buildType}) to Cloud Build Engine...",
                                    type = TerminalLineType.INFO
                                )
                            )
                        }
                        buildEngine.triggerBuild(project = project, buildType = outcome.buildType)
                    }
                    is TerminalCommandOutcome.Completed -> {
                        updateSession(sessionIdx) { s ->
                            val newLines = if (outcome.clearOutput) {
                                createWelcomeLines(project)
                            } else {
                                s.lines + outcome.outputLines + TerminalOutputLine(
                                    text = "[exit ${outcome.exitCode} • ${outcome.durationMs}ms]",
                                    type = if (outcome.exitCode == 0) TerminalLineType.INFO else TerminalLineType.ERROR
                                )
                            }
                            s.copy(
                                currentDirPath = outcome.updatedDir ?: s.currentDirPath,
                                lines = newLines,
                                isRunning = false,
                                lastExitCode = outcome.exitCode,
                                lastDurationMs = outcome.durationMs
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                updateSession(sessionIdx) { s ->
                    s.copy(
                        lines = s.lines + TerminalOutputLine(text = "^C Command cancelled", type = TerminalLineType.ERROR),
                        isRunning = false,
                        lastExitCode = 130
                    )
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .imePadding()
            .navigationBarsPadding()
            .testTag("terminal_screen")
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("Integrated Terminal", fontWeight = FontWeight.Bold)
                    Text(
                        text = "${project.name} • ${engine.getRelativeWorkspacePath(File(project.rootPath), File(activeSession.currentDirPath))}",
                        fontSize = 11.sp,
                        color = NeonCyan,
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
                if (activeSession.isRunning) {
                    IconButton(onClick = {
                        runningJob?.cancel()
                        if (isStreamingBuildLogs) {
                            buildEngine.cancelCurrentBuild()
                            isStreamingBuildLogs = false
                        }
                    }) {
                        Icon(Icons.Default.Stop, contentDescription = "Cancel Command", tint = CoralRed)
                    }
                }
                IconButton(onClick = {
                    val allText = activeSession.lines.joinToString("\n") { it.text }
                    clipboardManager.setText(AnnotatedString(allText))
                    Toast.makeText(context, "Terminal output copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Output")
                }
                IconButton(onClick = {
                    updateSession(activeSessionIndex) {
                        it.copy(lines = createWelcomeLines(project), lastExitCode = null, lastDurationMs = null)
                    }
                }) {
                    Icon(Icons.Default.ClearAll, contentDescription = "Clear Terminal")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900, titleContentColor = Color.White)
        )

        // Sandbox Notice + Build History link
        Surface(
            color = Slate900,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Slate800)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Workspace-sandboxed shell • Non-root Android environment",
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Build History",
                    fontSize = 11.sp,
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onNavigateToBuildHistory() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Multi-Session Tabs Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Slate900)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sessions.forEachIndexed { idx, session ->
                val selected = idx == activeSessionIndex
                Surface(
                    color = if (selected) Slate800 else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (selected) NeonCyan.copy(alpha = 0.6f) else Slate700),
                    modifier = Modifier.clickable { activeSessionIndex = idx }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = session.title,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else Color.Gray
                        )
                        if (sessions.size > 1) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Session",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        sessions.removeAt(idx)
                                        if (activeSessionIndex >= sessions.size) {
                                            activeSessionIndex = (sessions.size - 1).coerceAtLeast(0)
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            Surface(
                color = NeonPurple.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.5f)),
                modifier = Modifier.clickable {
                    val nextNum = sessions.size + 1
                    sessions.add(
                        TerminalSession(
                            title = "Session $nextNum",
                            projectRootPath = project.rootPath,
                            currentDirPath = project.rootPath,
                            lines = createWelcomeLines(project)
                        )
                    )
                    activeSessionIndex = sessions.lastIndex
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Session", tint = NeonPurple, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New", fontSize = 12.sp, color = NeonPurple, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Terminal Output Area
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF06090F))
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(activeSession.lines, key = { it.id }) { line ->
                val lineColor = when (line.type) {
                    TerminalLineType.COMMAND -> NeonCyan
                    TerminalLineType.ERROR -> CoralRed
                    TerminalLineType.SUCCESS -> EmeraldGreen
                    TerminalLineType.INFO -> Color(0xFF94A3B8)
                    TerminalLineType.OUTPUT -> Color(0xFFE2E8F0)
                }
                Text(
                    text = line.text,
                    color = lineColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (line.type == TerminalLineType.COMMAND) FontWeight.Bold else FontWeight.Normal,
                    lineHeight = 17.sp
                )
            }
            if (activeSession.isRunning) {
                item {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NeonCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Executing command...", fontSize = 11.sp, color = NeonCyan, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Quick Android Dev Shortcuts Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Slate900)
                .border(1.dp, Slate800)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "ls -la",
                "pwd",
                "tree",
                "gradle tasks",
                "gradle check",
                "./gradlew assembleDebug",
                "help",
                "clear"
            ).forEach { shortcut ->
                Surface(
                    color = Slate800,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Slate700),
                    modifier = Modifier.clickable { runCommand(shortcut) }
                ) {
                    Text(
                        text = shortcut,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = NeonCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // Command Input Area with History Navigation
        Surface(
            color = Slate900,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Command history Up / Down
                IconButton(
                    onClick = {
                        val hist = activeSession.history
                        if (hist.isNotEmpty()) {
                            val nextCursor = if (historyCursor < 0) hist.lastIndex else (historyCursor - 1).coerceAtLeast(0)
                            historyCursor = nextCursor
                            commandInput = hist[nextCursor]
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Previous Command", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = {
                        val hist = activeSession.history
                        if (hist.isNotEmpty() && historyCursor >= 0) {
                            if (historyCursor >= hist.lastIndex) {
                                historyCursor = -1
                                commandInput = ""
                            } else {
                                historyCursor++
                                commandInput = hist[historyCursor]
                            }
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Next Command", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                }

                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    placeholder = { Text("Enter workspace command (e.g. ls, gradle tasks)...", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color.White
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (commandInput.isNotBlank() && !activeSession.isRunning) {
                            runCommand(commandInput)
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Slate700,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .testTag("terminal_command_input")
                )

                IconButton(
                    onClick = {
                        if (commandInput.isNotBlank() && !activeSession.isRunning) {
                            runCommand(commandInput)
                        }
                    },
                    enabled = commandInput.isNotBlank() && !activeSession.isRunning,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (commandInput.isNotBlank() && !activeSession.isRunning) NeonCyan else Slate800)
                        .testTag("terminal_run_button")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Execute Command",
                        tint = if (commandInput.isNotBlank()) Slate950 else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    pendingConfirmation?.let { req ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text("Confirm Destructive Operation", fontWeight = FontWeight.Bold, color = CoralRed) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(req.reason)
                    Text("Command: ${req.command}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AmberYellow)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cmdToRun = req.command
                        pendingConfirmation = null
                        runCommand(cmdToRun, confirmedDestructive = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                ) { Text("Proceed") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) { Text("Cancel") }
            }
        )
    }
}

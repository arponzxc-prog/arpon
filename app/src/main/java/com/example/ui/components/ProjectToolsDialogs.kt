package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.tools.EmmetExpander
import com.example.tools.ProjectSearchEngine
import com.example.tools.SearchMatch
import com.example.tools.SearchOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Project-wide search & replace (Step 4.1). Callers must save the active file BEFORE showing this. */
@Composable
fun ProjectSearchDialog(
    rootDir: File,
    onOpenFile: (File) -> Unit,
    onReplaced: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchMatch>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var confirmReplace by remember { mutableStateOf(false) }

    val options = SearchOptions(query, useRegex, caseSensitive, wholeWord)
    val invalidRegexMsg = stringResource(R.string.search_invalid_regex)
    val noResultsMsg = stringResource(R.string.search_no_results)

    fun runSearch() {
        if (ProjectSearchEngine.buildPattern(options) == null) {
            status = if (query.isEmpty()) "" else invalidRegexMsg
            results = emptyList()
            return
        }
        scope.launch {
            val found = withContext(Dispatchers.IO) { ProjectSearchEngine.search(rootDir, options) }
            results = found
            status = if (found.isEmpty()) noResultsMsg else "${found.size}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_project_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search_query)) }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement, onValueChange = { replacement = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search_replace_with)) }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useRegex, { useRegex = it }); Text(stringResource(R.string.search_regex), fontSize = 11.sp)
                    Checkbox(caseSensitive, { caseSensitive = it }); Text(stringResource(R.string.search_case), fontSize = 11.sp)
                    Checkbox(wholeWord, { wholeWord = it }); Text(stringResource(R.string.search_word), fontSize = 11.sp)
                }
                if (status.isNotEmpty()) Text(status, fontSize = 11.sp)
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(results) { m ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFile(m.file); onDismiss() }
                                .padding(vertical = 4.dp)
                        ) {
                            Text("${m.relativePath}:${m.line}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(m.preview, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { runSearch() }) { Text(stringResource(R.string.search_action)) }
                TextButton(onClick = { confirmReplace = true }, enabled = results.isNotEmpty()) {
                    Text(stringResource(R.string.search_replace_all))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )

    if (confirmReplace) {
        val files = results.map { it.relativePath }.toSet()
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.search_replace_all)) },
            text = { Text(stringResource(R.string.search_replace_confirm, files.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    scope.launch {
                        val report = withContext(Dispatchers.IO) {
                            ProjectSearchEngine.replaceAll(rootDir, options, replacement, files)
                        }
                        status = "${report.replacements} / ${report.filesChanged}"
                        results = emptyList()
                        onReplaced()
                    }
                }) { Text(stringResource(R.string.search_replace_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

/** Emmet abbreviation -> code, with live preview (Step 4.1). */
@Composable
fun EmmetDialog(onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    var abbr by remember { mutableStateOf("") }
    var css by remember { mutableStateOf(false) }
    val expanded = if (abbr.isBlank()) null else if (css) EmmetExpander.expandCss(abbr) else EmmetExpander.expandHtml(abbr)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emmet_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = abbr, onValueChange = { abbr = it }, singleLine = true,
                    label = { Text(stringResource(R.string.emmet_hint)) }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(css, { css = it }); Text(stringResource(R.string.emmet_css_mode), fontSize = 12.sp)
                }
                Text(
                    expanded ?: if (abbr.isBlank()) "" else stringResource(R.string.emmet_invalid),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { expanded?.let { onInsert(it) }; onDismiss() }, enabled = expanded != null) {
                Text(stringResource(R.string.emmet_insert))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

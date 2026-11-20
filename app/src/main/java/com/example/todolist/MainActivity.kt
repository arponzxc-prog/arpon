package com.example.todolist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TodoListTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TodoScreen()
                }
            }
        }
    }
}

@Composable
fun TodoScreen() {
    var taskText by remember { mutableStateOf(TextFieldValue("")) }
    var tasks by remember { mutableStateOf(listOf<Task>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Todo List",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = taskText,
                onValueChange = { taskText = it },
                placeholder = { Text("New task") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (taskText.text.isNotBlank()) {
                        tasks = tasks + Task(taskText.text)
                        taskText = TextFieldValue("")
                    }
                },
                modifier = Modifier.height(56.dp)
            ) {
                Text("Add")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(tasks) { index, task ->
                TodoItem(
                    task = task,
                    onToggle = {
                        tasks = tasks.toMutableList().apply { set(index, task.copy(completed = !task.completed)) }
                    },
                    onDelete = {
                        tasks = tasks.toMutableList().apply { removeAt(index) }
                    }
                )
            }
        }
    }
}

@Composable
fun TodoItem(task: Task, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.completed,
                onCheckedChange = { onToggle() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = task.text,
                modifier = Modifier.weight(1f),
                style = if (task.completed) MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                ) else MaterialTheme.typography.bodyLarge
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

data class Task(val text: String, val completed: Boolean = false)

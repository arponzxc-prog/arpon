package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ai.AiFailoverSettings
import com.example.data.ai.AiModelConfig
import com.example.data.ai.AiRuntime
import com.example.data.ai.AiUsageStore
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.Slate900

/** Auto-failover toggle + backup order + content-free usage dashboard (Step 3.1 / 3.4). */
@Composable
fun AiFailoverUsageCard(models: List<AiModelConfig>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AiFailoverSettings.isEnabled(context)) }
    var order by remember(models) {
        val saved = AiFailoverSettings.getBackupOrder(context).filter { id -> models.any { it.id == id } }
        mutableStateOf(saved + models.map { it.id }.filterNot { it in saved })
    }
    var usage by remember {
        AiUsageStore.load(context)
        mutableStateOf(AiRuntime.usage.snapshot())
    }

    Surface(
        color = Slate900,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_failover_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(stringResource(R.string.ai_failover_desc), fontSize = 11.sp, color = NeonCyan)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    AiFailoverSettings.setEnabled(context, it)
                })
            }

            if (enabled && order.size > 1) {
                Text(stringResource(R.string.ai_failover_order), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                order.forEach { id ->
                    val name = models.firstOrNull { it.id == id }?.displayName ?: id
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("${order.indexOf(id) + 1}. $name", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            order = AiFailoverSettings.move(order, id, up = true)
                            AiFailoverSettings.setBackupOrder(context, order)
                        }) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.ai_move_up), modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = {
                            order = AiFailoverSettings.move(order, id, up = false)
                            AiFailoverSettings.setBackupOrder(context, order)
                        }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.ai_move_down), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_usage_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { usage = AiRuntime.usage.snapshot() }) { Text(stringResource(R.string.ai_usage_refresh)) }
                TextButton(onClick = { AiUsageStore.reset(context); usage = emptyList() }) { Text(stringResource(R.string.ai_usage_reset)) }
            }
            if (usage.isEmpty()) {
                Text(stringResource(R.string.ai_usage_empty), fontSize = 11.sp)
            } else {
                usage.forEach { u ->
                    val name = models.firstOrNull { it.id == u.modelId }?.displayName ?: u.modelId.take(8)
                    Text(
                        stringResource(R.string.ai_usage_row, name, u.requests, u.failures, u.failoversFrom, u.estimatedTokens),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

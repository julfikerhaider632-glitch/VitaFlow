package app.vitaflow.com.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.ui.components.ConfirmDialog
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.launch

/** What Max remembers about you. Everything is on the phone and you can delete any of it. */
@Composable
fun MemoryScreen(account: AccountEntity, container: AppContainer) {
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    val on by container.settingsStore.memoryEnabled.collectAsStateWithLifecycle(initialValue = true)
    val facts by container.memory.facts(account.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val chats by container.chatRepo.conversations(account.id).collectAsStateWithLifecycle(initialValue = emptyList())
    var newFact by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Max আপনার chat থেকে শিখে রাখে: প্রতিটা chat-এর সারাংশ আর আপনার সম্পর্কে স্থায়ী কিছু তথ্য। এসব শুধু আপনার ফোনে থাকে।",
            color = g.textSecondary, style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(
                checked = on, onCheckedChange = { scope.launch { container.settingsStore.setMemoryEnabled(it) } },
                colors = SwitchDefaults.colors(checkedTrackColor = g.accent)
            )
            Text("Max আমার সম্পর্কে মনে রাখবে", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        }

        Text("যা মনে আছে", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
        if (facts.isEmpty()) {
            Text("এখনো কিছু জমা হয়নি। কথা বলতে বলতে Max নিজে শিখবে, অথবা আপনি নিজে নিচে লিখে দিতে পারেন।", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        facts.forEach { f ->
            GlassSurface(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.text, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { container.memory.deleteFact(f.id) } }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "মুছুন", tint = g.danger)
                    }
                }
            }
        }
        GlassTextField(newFact, { newFact = it }, "Max-কে নিজে থেকে মনে রাখতে বলুন (যেমন: আমি ডিম খাই না)")
        GlassButton("যোগ করুন", {
            val t = newFact
            newFact = ""
            scope.launch { container.memory.addFact(account.id, t) }
        }, Modifier.fillMaxWidth(), enabled = newFact.isNotBlank())

        if (chats.any { it.digest.isNotBlank() }) {
            Text("আগের chat-এর সারাংশ", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
            chats.filter { it.digest.isNotBlank() }.forEach { c ->
                Text("• ${c.digest}", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (facts.isNotEmpty()) {
            GlassButton("সব মনে-রাখা তথ্য মুছুন", { confirmClear = true }, Modifier.fillMaxWidth(), danger = true)
        }
    }
    if (confirmClear) {
        ConfirmDialog("Max-এর মনে-রাখা সব তথ্য মুছে ফেলবেন? (আপনার chat মুছবে না)", onConfirm = { scope.launch { container.memory.clearFacts(account.id) } }, onDismiss = { confirmClear = false })
    }
}

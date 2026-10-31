package app.vitaflow.com.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vitaflow.com.data.TaskRepository
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.data.db.TaskEntity
import app.vitaflow.com.ui.components.ConfirmDialog
import app.vitaflow.com.ui.components.asciiDigits
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassChip
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.launch
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.input.KeyboardType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dueFormat = DateTimeFormatter.ofPattern("d/M/yyyy, HH:mm")

@Composable
fun TasksScreen(account: AccountEntity, repo: TaskRepository) {
    val g = LocalGlass.current
    val tasks by repo.observe(account.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<TaskEntity?>(null) }
    var dayChoice by remember { mutableStateOf("NONE") }
    var timeText by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("টাস্ক", style = MaterialTheme.typography.headlineMedium, color = g.textPrimary)
        GlassTextField(title, { title = it }, "নতুন টাস্ক")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("সময় ছাড়া", dayChoice == "NONE", { dayChoice = "NONE" })
            GlassChip("আজ", dayChoice == "TODAY", { dayChoice = "TODAY" })
            GlassChip("কাল", dayChoice == "TOMORROW", { dayChoice = "TOMORROW" })
        }
        if (dayChoice != "NONE" || daily) {
            GlassTextField(timeText, { timeText = it }, "সময় (যেমন 07:30, ২৪ ঘণ্টা)", keyboardType = KeyboardType.Number)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = daily, onCheckedChange = { daily = it }, colors = SwitchDefaults.colors(checkedTrackColor = g.accent))
            Text("প্রতিদিন", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        }
        formError?.let { Text(it, color = g.danger, style = MaterialTheme.typography.bodyMedium) }
        GlassButton(
            text = "যোগ করুন",
            onClick = {
                var due: Long? = null
                if (dayChoice != "NONE" || daily) {
                    val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(timeText.asciiDigits().trim())
                    val h = m?.groupValues?.get(1)?.toIntOrNull()
                    val min = m?.groupValues?.get(2)?.toIntOrNull()
                    if (h == null || min == null || h !in 0..23 || min !in 0..59) {
                        formError = "সময় ঠিকমতো লিখুন, যেমন 07:30"
                        return@GlassButton
                    }
                    var dt = (if (dayChoice == "TOMORROW") LocalDate.now().plusDays(1) else LocalDate.now()).atTime(LocalTime.of(h, min))
                    if (!dt.isAfter(java.time.LocalDateTime.now())) dt = dt.plusDays(1)
                    due = dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                formError = null
                val t = title
                title = ""
                scope.launch { repo.create(account.id, t, dueAt = due, repeatRule = if (daily) "DAILY" else null) }
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )
        if (tasks.isEmpty()) {
            Text("এখনো কোনো টাস্ক নেই। উপরে লিখে প্রথমটা যোগ করুন, অথবা Max-কে বলুন।", color = g.textSecondary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tasks, key = { it.id }) { task ->
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = task.done,
                            onCheckedChange = { checked -> scope.launch { repo.setDone(task, checked) } },
                            colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.textSecondary)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.title,
                                color = if (task.done) g.textSecondary else g.textPrimary,
                                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            task.dueAt?.let {
                                Text(
                                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dueFormat) +
                                        if (task.repeatRule == "DAILY") " · প্রতিদিন" else "",
                                    color = g.textSecondary, style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        IconButton(onClick = { toDelete = task }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "মুছুন", tint = g.danger)
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { t ->
        ConfirmDialog("\"${t.title}\" টাস্কটা মুছে ফেলবেন?", onConfirm = { scope.launch { repo.delete(t) } }, onDismiss = { toDelete = null })
    }
}

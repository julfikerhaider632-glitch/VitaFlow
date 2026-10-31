package app.vitaflow.com.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vitaflow.com.AppContainer
import app.vitaflow.com.ai.ModelManager
import app.vitaflow.com.data.ThemeMode
import app.vitaflow.com.data.auth.GoogleResult
import app.vitaflow.com.data.auth.requestGoogleIdentity
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.ui.components.ConfirmDialog
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassChip
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val modelRows = listOf(
    "bn-stt" to "বাংলা শোনা (ASR)",
    "en-stt" to "English শোনা (ASR)",
    "bn-tts" to "বাংলা বলা (TTS)",
    "en-tts" to "English বলা (TTS)",
    ModelManager.LLM to "Max-এর brain (Gemma)"
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalGlass.current.textPrimary, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun SettingsScreen(account: AccountEntity, container: AppContainer, onLogout: () -> Unit, onClearChat: () -> Unit) {
    val g = LocalGlass.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeMode by container.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val lang by container.settingsStore.voiceLang.collectAsStateWithLifecycle(initialValue = "bn")
    val speak by container.settingsStore.speakReplies.collectAsStateWithLifecycle(initialValue = false)
    val wake by container.settingsStore.wakeStandby.collectAsStateWithLifecycle(initialValue = false)
    val understand by container.settingsStore.understandMode.collectAsStateWithLifecycle(initialValue = "SMART")
    val reduce by container.settingsStore.reduceEffects.collectAsStateWithLifecycle(initialValue = false)
    val style by container.settingsStore.replyStyle.collectAsStateWithLifecycle(initialValue = "SHORT")
    val lastExport by container.settingsStore.lastExportAt.collectAsStateWithLifecycle(initialValue = 0L)
    val webOn by container.settingsStore.webSearchEnabled.collectAsStateWithLifecycle(initialValue = true)
    val hasKey by container.secrets.hasTavilyKey.collectAsStateWithLifecycle(initialValue = false)
    val installed by container.models.installed.collectAsStateWithLifecycle()

    var message by remember { mutableStateOf<String?>(null) }
    var llmProgress by remember { mutableStateOf<Int?>(null) }
    var keyText by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            uris.forEach { uri ->
                message = "model যোগ হচ্ছে, একটু অপেক্ষা করুন..."
                container.models.importPack(uri)
                    .onSuccess { message = "$it যোগ হয়েছে ✓" }
                    .onFailure { message = it.message ?: "model যোগ করা যায়নি" }
            }
        }
    }
    val llmPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "Gemma কপি হচ্ছে (প্রায় ২.৬ GB), কিছুক্ষণ লাগবে..."
            llmProgress = 0
            container.models.importLlm(uri) { done, total ->
                if (total > 0) llmProgress = (done * 100 / total).toInt()
            }.onSuccess { message = "Gemma যোগ হয়েছে ✓" }
                .onFailure { message = it.message ?: "Gemma যোগ করা যায়নি" }
            llmProgress = null
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val bytes = container.backup.export(passphrase)
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            if (ok) container.settingsStore.markExported()
            message = if (ok) "backup ফাইল সংরক্ষিত হয়েছে ✓" else "ফাইল লেখা যায়নি"
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                message = "ফাইল পড়া যায়নি"
            } else {
                val err = container.backup.import(bytes, passphrase)
                message = err ?: "সব data ফিরে এসেছে ✓ আবার প্রবেশ করুন।"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        message?.let { Text(it, color = g.accent, style = MaterialTheme.typography.bodyLarge) }
        llmProgress?.let { Text("কপি: $it%", color = g.accent) }

        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.name, color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(account.email ?: account.phone ?: "", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionTitle("থিম")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChip("ফোনের মতো", themeMode == ThemeMode.SYSTEM, { scope.launch { container.settingsStore.setThemeMode(ThemeMode.SYSTEM) } })
            GlassChip("ডার্ক", themeMode == ThemeMode.DARK, { scope.launch { container.settingsStore.setThemeMode(ThemeMode.DARK) } })
            GlassChip("লাইট", themeMode == ThemeMode.LIGHT, { scope.launch { container.settingsStore.setThemeMode(ThemeMode.LIGHT) } })
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(
                checked = reduce, onCheckedChange = { scope.launch { container.settingsStore.setReduceEffects(it) } },
                colors = SwitchDefaults.colors(checkedTrackColor = g.accent)
            )
            Text("ঝাপসা effect কমান (ফোন ধীর লাগলে)", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        }

        SectionTitle("Max কীভাবে বুঝবে")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChip("স্মার্ট", understand == "SMART", { scope.launch { container.settingsStore.setUnderstandMode("SMART") } })
            GlassChip("দ্রুত", understand == "QUICK", { scope.launch { container.settingsStore.setUnderstandMode("QUICK") } })
        }
        Text(
            if (understand == "SMART") "স্মার্ট: আপনি নিজের মতো করে বললেই AI অর্থ বোঝে (একসাথে একাধিক কথাও)। উত্তর আসতে কিছুটা সময় লাগে।"
            else "দ্রুত: ছোট সহজ command নিয়মে সঙ্গে সঙ্গে হয়, বাকি সব AI বোঝে।",
            color = g.textSecondary, style = MaterialTheme.typography.bodyMedium
        )

        SectionTitle("Max-এর উত্তর")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChip("ছোট", style == "SHORT", { scope.launch { container.settingsStore.setReplyStyle("SHORT") } })
            GlassChip("বিস্তারিত", style == "DETAILED", { scope.launch { container.settingsStore.setReplyStyle("DETAILED") } })
        }

        SectionTitle("ভয়েস")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChip("বাংলা শুনুক", lang == "bn", { scope.launch { container.settingsStore.setVoiceLang("bn") } })
            GlassChip("English শুনুক", lang == "en", { scope.launch { container.settingsStore.setVoiceLang("en") } })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(
                checked = speak, onCheckedChange = { scope.launch { container.settingsStore.setSpeakReplies(it) } },
                colors = SwitchDefaults.colors(checkedTrackColor = g.accent)
            )
            Text("Max উত্তর পড়ে শোনাবে", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(
                checked = wake, onCheckedChange = { scope.launch { container.settingsStore.setWakeStandby(it) } },
                colors = SwitchDefaults.colors(checkedTrackColor = g.accent)
            )
            Text("Live চালু হলে আগে \"Hey Max\" বলার অপেক্ষা করবে (app খোলা থাকলে)", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        }

        SectionTitle("Model")
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modelRows.forEach { (key, label) ->
                    val has = key in installed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (has) "আছে ✓" else "নেই",
                            color = if (has) g.success else g.textSecondary,
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (has) {
                            Text(
                                "মুছুন", color = g.danger, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable { confirmDelete = key }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                            )
                        }
                    }
                }
            }
        }
        GlassButton("Voice model যোগ করুন (.itpack)", { packPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
        Text("শুধু .litertlm ফাইল চলে (যেমন gemma-4-E2B-it.litertlm)। .gguf ফাইল এই app-এ চলবে না।", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        GlassButton("Gemma যোগ করুন (.litertlm)", { llmPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = llmProgress == null)

        SectionTitle("ওয়েব সার্চ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(
                checked = webOn, onCheckedChange = { scope.launch { container.settingsStore.setWebSearchEnabled(it) } },
                colors = SwitchDefaults.colors(checkedTrackColor = g.accent)
            )
            Text("Max ওয়েবে খুঁজতে পারবে", color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "শুধু search-এর ছোট বাক্যটা ইন্টারনেটে যায়। আপনার profile, diet, workout বা voice যায় না। দিনে সর্বোচ্চ ৩০টা search।",
            color = g.textSecondary, style = MaterialTheme.typography.bodyMedium
        )
        if (hasKey) Text("Tavily key সংরক্ষিত আছে ✓", color = g.success, style = MaterialTheme.typography.bodyMedium)
        GlassTextField(keyText, { keyText = it }, "Tavily API key (tvly-...)", password = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("key সংরক্ষণ", {
                scope.launch {
                    container.secrets.saveTavilyKey(keyText)
                    keyText = ""
                    message = "key সংরক্ষিত হয়েছে ✓"
                }
            }, Modifier.weight(1f), enabled = keyText.isNotBlank())
            if (hasKey) {
                GlassButton("key মুছুন", {
                    scope.launch { container.secrets.saveTavilyKey(""); message = "key মুছে ফেলা হয়েছে" }
                }, Modifier.weight(1f), danger = true)
            }
        }

        SectionTitle("Data backup")
        val days = if (lastExport == 0L) null else (System.currentTimeMillis() - lastExport) / (24L * 60 * 60 * 1000)
        Text(
            if (days == null) "এখনো কোনো backup নেওয়া হয়নি।" else "শেষ backup: $days দিন আগে",
            color = if (days == null || days > 7) g.danger else g.success, style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "App মুছে ফেলার আগে export করে রাখুন। আবার install করে import করলে সব data ফিরে আসবে। পাসওয়ার্ড দিলে ফাইল encrypt হবে (import-এও একই পাসওয়ার্ড লাগবে)।",
            color = g.textSecondary, style = MaterialTheme.typography.bodyMedium
        )
        GlassTextField(passphrase, { passphrase = it }, "ফাইলের পাসওয়ার্ড (ইচ্ছা হলে)", password = true)
        GlassButton("Export করুন", { exportPicker.launch("vitaflow-backup-${LocalDate.now()}.vfbak") }, Modifier.fillMaxWidth())
        GlassButton("Import করুন", { importPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())

        SectionTitle("Google account")
        if (account.googleSub != null) {
            Text("Google account যুক্ত আছে ✓", color = g.success, style = MaterialTheme.typography.bodyLarge)
        } else {
            GlassButton("Google account যুক্ত করুন", {
                scope.launch {
                    when (val r = requestGoogleIdentity(context)) {
                        is GoogleResult.Ok -> message = container.authRepo.linkGoogle(account.id, r.identity) ?: "Google account যুক্ত হয়েছে ✓"
                        is GoogleResult.Err -> message = r.message
                    }
                }
            }, Modifier.fillMaxWidth())
        }

        SectionTitle("গোপনীয়তা")
        GlassButton("Chat-এর ইতিহাস মুছুন", { confirmClear = true }, Modifier.fillMaxWidth(), danger = true)
        GlassButton("লগ আউট", onLogout, Modifier.fillMaxWidth(), danger = true)
    }

    confirmDelete?.let { key ->
        ConfirmDialog("এই model মুছে ফেলবেন? আবার যোগ করতে হবে।", onConfirm = { container.models.delete(key) }, onDismiss = { confirmDelete = null })
    }
    if (confirmClear) {
        ConfirmDialog("Max-এর সাথে সব chat মুছে ফেলবেন? (আপনার task, workout, diet মুছবে না)", "মুছুন", onConfirm = onClearChat, onDismiss = { confirmClear = false })
    }
}

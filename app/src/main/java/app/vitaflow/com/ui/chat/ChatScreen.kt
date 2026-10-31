package app.vitaflow.com.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vitaflow.com.AppContainer
import app.vitaflow.com.ai.Attachment
import app.vitaflow.com.ai.AttachmentJson
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.data.db.ChangeRecordEntity
import app.vitaflow.com.data.db.ChatMessageEntity
import app.vitaflow.com.data.diffJson
import app.vitaflow.com.ui.components.ConfirmDialog
import app.vitaflow.com.ui.components.GlassDialog
import app.vitaflow.com.ui.components.LocalImage
import app.vitaflow.com.ui.home.HomeTab
import app.vitaflow.com.ui.home.tabForEntity
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassChip
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayFormat = DateTimeFormatter.ofPattern("d/M/yyyy")

@Composable
fun ChatScreen(account: AccountEntity, container: AppContainer, onNavigate: (HomeTab) -> Unit) {
    val g = LocalGlass.current
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(
        key = "chat-${account.id}",
        factory = viewModelFactory { initializer { ChatViewModel(container, account) } }
    )
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val currentId by vm.conversationId.collectAsStateWithLifecycle()
    val lang by vm.voiceLang.collectAsStateWithLifecycle()
    val speakOn by vm.speakReplies.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var openIds by remember { mutableStateOf<List<Long>?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var deleteChatId by remember { mutableStateOf<Long?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterGrant?.invoke() else vm.updateStatus("মাইক্রোফোনের অনুমতি ছাড়া voice চলবে না")
        afterGrant = null
    }
    fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterGrant = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.attach(uri) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.attach(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { vm.attach(it) }
    }

    // Keep the screen on while Live mode listens, and stop listening when the app goes to the background.
    val view = LocalView.current
    val live = vm.liveState != LiveState.OFF
    DisposableEffect(live) {
        view.keepScreenOn = live
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.stopLive()
        vm.onLeaveChat()
    }
    DisposableEffect(Unit) { onDispose { vm.stopLive() } }

    LaunchedEffect(messages.size, vm.streaming) {
        val extra = if (vm.streaming != null) 1 else 0
        val last = messages.size + extra - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Max", style = MaterialTheme.typography.headlineMedium, color = g.textPrimary, modifier = Modifier.weight(1f))
            GlassChip(if (live) "Live বন্ধ" else "Live", live, { if (live) vm.stopLive() else withMic { vm.startLive() } })
            IconButton(onClick = { vm.setSpeakReplies(!speakOn) }) {
                Icon(
                    if (speakOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                    contentDescription = "উত্তর পড়ে শোনানো",
                    tint = if (speakOn) g.accent else g.textSecondary
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("＋ নতুন chat", false, { vm.newChat() })
            GlassChip("ইতিহাস", false, { showHistory = true })
            GlassChip("বাংলা", lang == "bn", { vm.setLang("bn") })
            GlassChip("EN", lang == "en", { vm.setLang("en") })
        }
        conversations.firstOrNull { it.id == currentId }?.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "+২৫০ মিলি পানি" to "২৫০ মিলি পানি খেয়েছি",
                "আজকের টাস্ক" to "আজকের task দেখাও",
                "Workout শুরু" to "workout শুরু করো",
                "আজকের হিসাব" to "আজকের ক্যালরি কত",
                "Progress" to "progress দেখাও"
            ).forEach { (label, command) -> GlassChip(label, false, { vm.send(command, fast = true) }) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty() && vm.streaming == null) {
                item {
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Text(
                            "হ্যালো ${account.name}! লিখে বা মাইক চেপে বলুন। ছবি বা ফাইলও পাঠাতে পারেন (📎)। আমি এই chat-এর সবকিছু মনে রাখব, আর আগের chat-গুলোর সারাংশও জানি।",
                            color = g.textSecondary, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(18.dp)
                        )
                    }
                }
            }
            items(messages, key = { it.id }) { m ->
                Bubble(m, onOpen = { openIds = it }, onSpeak = { vm.speak(it) })
            }
            vm.streaming?.let { partial ->
                item { Bubble(ChatMessageEntity(id = -1, accountId = account.id, role = "MAX", text = partial), {}, {}) }
            }
            if (vm.busy && vm.streaming == null) {
                item { Text("Max ভাবছে...", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        vm.status?.let { Text(it, color = g.accent, style = MaterialTheme.typography.bodyMedium) }
        if (live) {
            Text(
                when (vm.liveState) {
                    LiveState.STANDBY -> "Live: \"Hey Max\"-এর অপেক্ষায়"
                    LiveState.LISTENING -> "Live: শুনছি"
                    LiveState.THINKING -> "Live: কাজ করছি"
                    LiveState.SPEAKING -> "Live: বলছি"
                    LiveState.OFF -> ""
                },
                color = g.success, style = MaterialTheme.typography.labelLarge
            )
        }

        if (vm.attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.attachments.toList().forEach { a ->
                    Box {
                        if (a.kind == "IMAGE") {
                            LocalImage(a.path, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                        } else {
                            GlassSurface(Modifier.size(width = 120.dp, height = 72.dp), shape = RoundedCornerShape(14.dp)) {
                                Text(a.name, color = g.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.padding(8.dp))
                            }
                        }
                        IconButton(onClick = { vm.removeAttachment(a) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "সরান", tint = g.danger)
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                IconButton(onClick = { attachMenu = true }, enabled = !vm.importing) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "ছবি বা ফাইল", tint = g.accent)
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(text = { Text("গ্যালারি থেকে ছবি") }, onClick = {
                        attachMenu = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("ক্যামেরায় ছবি তুলুন") }, onClick = {
                        attachMenu = false
                        val f = File(context.cacheDir, "camera/IMG_${System.currentTimeMillis()}.jpg")
                        f.parentFile?.mkdirs()
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                        cameraUri = uri
                        camera.launch(uri)
                    })
                    DropdownMenuItem(text = { Text("ফাইল (PDF, লেখা)") }, onClick = {
                        attachMenu = false
                        filePicker.launch(arrayOf("application/pdf", "text/*", "application/json"))
                    })
                }
            }
            GlassTextField(vm.input, { vm.input = it }, "Max-কে লিখুন", modifier = Modifier.weight(1f))
            IconButton(enabled = !live, onClick = {
                if (vm.speaking) {
                    vm.stopSpeaking()
                } else if (vm.recording) {
                    vm.toggleMic()
                } else {
                    withMic { vm.toggleMic() }
                }
            }) {
                Icon(
                    if (vm.recording || vm.speaking) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = "মাইক",
                    tint = if (vm.recording) g.danger else g.accent,
                    modifier = Modifier.size(28.dp)
                )
            }
            val canSend = !vm.busy && !vm.importing && (vm.input.isNotBlank() || vm.attachments.isNotEmpty())
            IconButton(onClick = { vm.send(vm.input) }, enabled = canSend) {
                Icon(
                    Icons.Rounded.Send, contentDescription = "পাঠান",
                    tint = if (canSend) g.accent else g.textSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }

    openIds?.let { ids ->
        ChangeDialog(ids, container, onDismiss = { openIds = null }, onGo = { openIds = null; onNavigate(it) })
    }

    if (showHistory) {
        GlassDialog("আপনার chat-গুলো", { showHistory = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (conversations.isEmpty()) Text("এখনো কোনো chat নেই।", color = g.textSecondary)
                conversations.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable { vm.openChat(c.id); showHistory = false }.padding(vertical = 6.dp)
                        ) {
                            Text(
                                c.title.ifBlank { "নতুন chat" } + if (c.id == currentId) "  ✓" else "",
                                color = g.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1
                            )
                            Text(
                                Instant.ofEpochMilli(c.updatedAt).atZone(ZoneId.systemDefault()).format(dayFormat) +
                                    if (c.digest.isNotBlank()) " · ${c.digest}" else "",
                                color = g.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2
                            )
                        }
                        IconButton(onClick = { deleteChatId = c.id }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "chat মুছুন", tint = g.danger)
                        }
                    }
                }
            }
            GlassButton("বন্ধ করুন", { showHistory = false }, Modifier.fillMaxWidth())
        }
    }
    deleteChatId?.let { id ->
        ConfirmDialog("এই chat আর এর ছবি-ফাইল মুছে ফেলবেন?", onConfirm = { vm.deleteChat(id) }, onDismiss = { deleteChatId = null })
    }
}

@Composable
private fun Bubble(m: ChatMessageEntity, onOpen: (List<Long>) -> Unit, onSpeak: (String) -> Unit) {
    val g = LocalGlass.current
    val mine = m.role == "USER"
    val ids = m.changeIds.split(',').mapNotNull { it.trim().toLongOrNull() }
    val files: List<Attachment> = remember(m.attachments) { AttachmentJson.decode(m.attachments) }
    val sources = remember(m.sourcesJson) {
        runCatching {
            val arr = JSONArray(m.sourcesJson ?: "[]")
            (0 until arr.length()).map { arr.getJSONObject(it).optString("title") to arr.getJSONObject(it).optString("url") }
        }.getOrDefault(emptyList())
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        GlassSurface(Modifier.fillMaxWidth(0.88f), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { a ->
                    if (a.kind == "IMAGE" && File(a.path).exists()) {
                        LocalImage(a.path, Modifier.fillMaxWidth().size(width = 200.dp, height = 160.dp).clip(RoundedCornerShape(14.dp)))
                    } else {
                        Text("📎 ${a.name}", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (m.text.isNotBlank()) Text(m.text, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                sources.forEachIndexed { i, (title, url) ->
                    Text("[${i + 1}] $title\n$url", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                if (!mine && m.id > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ids.isNotEmpty()) GlassButton("খুলুন", { onOpen(ids) })
                        Text(
                            "শুনুন",
                            color = g.accent,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable { onSpeak(m.text) }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Shows what Max changed: the summary of every change and, for edits, the old and new values. */
@Composable
private fun ChangeDialog(ids: List<Long>, container: AppContainer, onDismiss: () -> Unit, onGo: (HomeTab) -> Unit) {
    val g = LocalGlass.current
    var records by remember { mutableStateOf<List<ChangeRecordEntity>>(emptyList()) }
    var undoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ids) { records = container.db.changeDao().getByIds(ids) }

    GlassDialog("যা যা বদলেছে", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (records.isEmpty()) Text("লোড হচ্ছে...", color = g.textSecondary)
            records.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.summary, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    if (r.action == "UPDATE") {
                        diffJson(r.beforeJson, r.afterJson).forEach {
                            Text(it, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (r.undone) {
                        Text("ফিরিয়ে নেওয়া হয়েছে", color = g.success, style = MaterialTheme.typography.bodyMedium)
                    } else if (r.entity != "SESSION") {
                        Text(
                            "ফিরিয়ে নিন",
                            color = g.danger, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    undoMessage = container.undo.undo(r)
                                    records = container.db.changeDao().getByIds(ids)
                                }
                            }.padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
        undoMessage?.let { Text(it, color = g.accent, style = MaterialTheme.typography.bodyMedium) }
        records.firstOrNull()?.let { first ->
            GlassButton("সংশ্লিষ্ট পাতায় যান", { onGo(tabForEntity(first.entity)) }, Modifier.fillMaxWidth())
        }
        GlassButton("বন্ধ করুন", onDismiss, Modifier.fillMaxWidth())
    }
}

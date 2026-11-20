package com.deepseek.client.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.deepseek.client.android.R
import com.deepseek.client.android.data.attachmentInputs
import com.deepseek.client.domain.*
import com.deepseek.client.attachments.AttachmentInput
import com.deepseek.client.mvi.*
import com.deepseek.client.runtime.ChatController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepSeekApp(controller: ChatController) {
    val state by controller.state.collectAsState()
    var drawer by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var configureKey by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Session?>(null) }
    var rename by remember { mutableStateOf<Session?>(null) }
    var sources by remember { mutableStateOf<List<Source>?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    BackHandler(drawer) { drawer = false }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try {
                controller.stageAttachments(context.contentResolver.attachmentInputs(uris))
            } catch (error: Exception) {
                snack.showSnackbar(error.message ?: "无法读取附件")
            }
        }
    }
    LaunchedEffect(state.transient.message) {
        state.transient.message?.let { snack.showSnackbar(it); controller.submit(ChatIntent.DismissMessage) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        val drawerWidth = maxWidth * .82f
        val displacement by animateFloatAsState(if (drawer) 1f else 0f, label = "drawer")
        Column(Modifier.width(drawerWidth).fillMaxHeight().padding(12.dp)) {
            OutlinedTextField(filter, { filter = it }, placeholder = { Text("搜索对话内容...") }, singleLine = true,
                shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), leadingIcon = { DSIcon(R.drawable.ic_ds_search_outline_16, "搜索") })
            if (state.sessions.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("暂无对话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("你与 DeepSeek 的对话\n将展示在这里", color = colors.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                    Button({ controller.submit(ChatIntent.NewSession); drawer = false }) { Text("开启新对话") }
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(top = 20.dp)) {
                    item { Text("对话", color = colors.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                    val sessions = state.sessions.sortedWith(compareByDescending<Session> { it.pinned }.thenByDescending { it.updatedAt }).filter {
                        filter.isBlank() || it.title.contains(filter, true) || it.messages.any { msg -> msg.blocks.any { block -> block is ContentBlock.Text && block.text.contains(filter, true) } }
                    }
                    items(sessions, key = { it.id.value }) { session ->
                        var menu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(if (state.selectedSessionId == session.id) colors.primaryContainer else Color.Transparent)
                            .clickable { controller.submit(ChatIntent.SelectSession(session.id)); drawer = false; keyboard?.hide() }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (session.pinned) "置顶 · " else "") + session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box {
                                IconButton({ menu = true }) { Text("•••", modifier = Modifier.semantics { contentDescription = "对话菜单" }) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("重命名") }, { menu = false; rename = session })
                                    DropdownMenuItem({ Text(if (session.pinned) "取消置顶" else "置顶") }, { menu = false; controller.submit(ChatIntent.PinSession(session.id)) })
                                    DropdownMenuItem({ Text("删除", color = colors.error) }, { menu = false; delete = session })
                                }
                            }
                        }
                    }
                }
            }
            TextButton({ settings = true }, Modifier.fillMaxWidth().height(56.dp)) { Text("设置", color = colors.onSurface, modifier = Modifier.weight(1f)); Text("•••") }
        }
        Surface(Modifier.fillMaxSize().offset(x = drawerWidth * displacement)
            .clip(RoundedCornerShape(if (drawer) 28.dp else 0.dp))
            .pointerInput(drawer) {
                var total = 0f
                detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                    if (total > 100) { drawer = true; keyboard?.hide() }
                    else if (total < -100) drawer = false
                }) { _, delta -> total += delta }
            }, shadowElevation = if (drawer) 12.dp else 0.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton({ drawer = !drawer; keyboard?.hide() }, Modifier.size(46.dp)) { DSIcon(R.drawable.ic_sidebar_outline, "对话列表") }
                    Text(state.selectedSession?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedIconButton({ controller.submit(ChatIntent.NewSession) }, Modifier.size(46.dp)) { DSIcon(R.drawable.ic_new_chat_outline_24, "新对话") }
                }
                state.storageError?.let { message -> Text(message, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
                if (!state.ready) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else MessageList(state, { controller.submit(it) }, { sources = it }, Modifier.weight(1f))
                if (!state.apiKeyConfigured && state.ready) TextButton({ configureKey = true }, Modifier.align(Alignment.CenterHorizontally)) { Text("填写 API Key 开始对话") }
                Composer(state, controller, { configureKey = true }, { attachmentPicker.launch(arrayOf("*/*")) })
            }
        }
        if (drawer) Box(Modifier.fillMaxSize().offset(x = drawerWidth).clickable { drawer = false })
        SnackbarHost(snack, Modifier.align(Alignment.Center).padding(24.dp))
    }
    if (configureKey) KeySheet(state, controller, { configureKey = false })
    if (settings) ModalBottomSheet({ settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("API Key", color = colors.onSurfaceVariant)
            ListItem(headlineContent = { Text(if (state.apiKeyConfigured) "已配置 ${state.apiKeyMasked}" else "未配置") }, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { configureKey = true })
            if (state.apiKeyConfigured) TextButton({ controller.clearKey() }) { Text("清除 API Key", color = colors.error) }
            Text("外观", color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, text) ->
                    FilterChip(state.preferences.theme == id, { controller.submit(ChatIntent.SetTheme(id)) }, { Text(text) })
                }
            }
            Text("字体大小 · ${(state.preferences.fontScale * 100).roundToInt()}%")
            Text("你可以拖动滑块来调整字体大小。", fontSize = (17 * state.preferences.fontScale).sp)
            Slider(state.preferences.fontScale, { controller.submit(ChatIntent.SetFontScale(it)) }, valueRange = .8f..1.4f, steps = 5)
            Text("历史记录仅保存在当前设备；删除不可恢复。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            var confirmAll by remember { mutableStateOf(false) }
            TextButton({ confirmAll = true }) { Text("删除所有历史对话", color = colors.error) }
            if (confirmAll) AlertDialog({ confirmAll = false }, title = { Text("删除所有历史对话？") }, text = { Text("本机记录和草稿会永久删除，API Key 和设置保留。") },
                confirmButton = { TextButton({ controller.submit(ChatIntent.DeleteAll); confirmAll = false }) { Text("删除", color = colors.error) } }, dismissButton = { TextButton({ confirmAll = false }) { Text("取消") } })
            Text("开发版 · 文本、思考与搜索接入\n附件、录音与朗读尚未接入", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
    delete?.let { session -> AlertDialog({ delete = null }, title = { Text("删除后，该对话将不可恢复") },
        text = { Text("将删除本机保存的“${session.title}”。") },
        confirmButton = { TextButton({ controller.submit(ChatIntent.DeleteSession(session.id)); delete = null }) { Text("删除该对话", color = colors.error) } },
        dismissButton = { TextButton({ delete = null }) { Text("取消") } }) }
    rename?.let { session ->
        var value by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog({ rename = null }, title = { Text("重命名对话") }, text = { OutlinedTextField(value, { value = it }, singleLine = true) },
            confirmButton = { TextButton({ controller.submit(ChatIntent.RenameSession(session.id, value)); rename = null }, enabled = value.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton({ rename = null }) { Text("取消") } })
    }
    sources?.let { values -> ModalBottomSheet({ sources = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val uri = LocalUriHandler.current
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            item { Text("搜索结果", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 20.dp)) }
            items(values, key = { it.url }) { source ->
                Column(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(source.url) } }.padding(vertical = 16.dp)) {
                    Text("${source.index} · ${source.siteName}  ${source.publishedAt.orEmpty()}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(source.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                    source.snippet?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant) }
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                }
            }
        }
    } }
}

@Composable
private fun MessageList(state: AppState, dispatch: (ChatIntent) -> Unit, onSources: (List<Source>) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val messages = state.selectedSession?.messages.orEmpty()
    val list = rememberLazyListState()
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(state.selectedSessionId, messages.size) { if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex) }
    LaunchedEffect(messages.lastOrNull()?.blocks) { if (atBottom && messages.isNotEmpty()) list.scrollToItem(messages.lastIndex) }
    if (messages.isEmpty()) Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.chat_welcome_logo), null, tint = colors.primary, modifier = Modifier.size(58.dp))
            Text("你好，有什么我能帮你的吗？", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
        }
    } else LazyColumn(modifier.fillMaxWidth().padding(horizontal = 22.dp), state = list, verticalArrangement = Arrangement.spacedBy(26.dp), contentPadding = PaddingValues(vertical = 26.dp)) {
        items(messages, key = { it.id.value }) { msg ->
            Column(Modifier.fillMaxWidth()) {
                if (msg.role == MessageRole.User) {
                    Column(Modifier.align(Alignment.End).widthIn(max = 310.dp).clip(RoundedCornerShape(24.dp, 24.dp, 5.dp, 24.dp)).background(colors.primaryContainer).padding(16.dp)) {
                        if (msg.attachments.isNotEmpty()) Text(msg.attachments.joinToString("\n") { "📎 ${it.name}" }, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        SelectionContainerText(msg.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }, Modifier, state.preferences.fontScale)
                    }
                }
                else {
                    msg.blocks.forEach { block -> when (block) {
                        is ContentBlock.Thinking -> {
                            val collapsed = block.id in state.collapsedThinkingBlocks
                            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                            LaunchedEffect(block.endedAt) { while (block.endedAt == null) { delay(1000); now = System.currentTimeMillis() } }
                            val seconds = (((block.endedAt ?: now) - (block.startedAt ?: now)) / 1000).coerceAtLeast(1)
                            Text((if (block.endedAt == null) "思考中…" else "已思考（用时 $seconds 秒）") + if (collapsed) "  ›" else "  ⌄", color = colors.onSurfaceVariant,
                                modifier = Modifier.clickable { dispatch(ChatIntent.ToggleThinkingBlock(block.id)) }.padding(vertical = 10.dp))
                            if (!collapsed) Text(block.text, color = colors.onSurfaceVariant, fontSize = (15 * state.preferences.fontScale).sp, lineHeight = (25 * state.preferences.fontScale).sp,
                                modifier = Modifier.padding(start = 10.dp, bottom = 20.dp))
                        }
                        is ContentBlock.Text -> {
                            SelectionContainerText(block.text, Modifier.padding(vertical = 8.dp), state.preferences.fontScale)
                            if (block.citations.isNotEmpty()) TextButton({ onSources(msg.sources) }) { Text(block.citations.mapNotNull { c -> msg.sources.find { it.url == c.url }?.index }.joinToString("  ") { "[$it]" }) }
                        }
                        is ContentBlock.Search -> Text(when (block.status) { ToolStatus.Wip -> "正在搜索 ${block.queries.joinToString()}"; ToolStatus.Done -> "搜索到 ${block.results.size} 个网页"; ToolStatus.Failed -> "搜索已停止或未完成" }, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        is ContentBlock.ReadLink -> Text(if (block.status == ToolStatus.Wip) "正在阅读链接…" else if (block.status == ToolStatus.Done) "已阅读链接" else "链接未能读取", color = colors.onSurfaceVariant)
                    } }
                    if (msg.blocks.isEmpty() && msg.status == MessageStatus.Streaming) Text("正在准备回复…", color = colors.onSurfaceVariant)
                    msg.error?.let { Text(it, color = colors.error, modifier = Modifier.padding(vertical = 8.dp)) }
                    if (msg.sources.isNotEmpty()) OutlinedButton({ onSources(msg.sources) }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("${msg.sources.size} 个网页") }
                    val clipboard = LocalClipboardManager.current
                    Row {
                        IconButton({ clipboard.setText(AnnotatedString(msg.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.text })) }) { DSIcon(R.drawable.ic_copy_outline, "复制回答") }
                        if (msg.status == MessageStatus.Failed || msg.status == MessageStatus.Cancelled) TextButton({ dispatch(ChatIntent.EditForResend(msg.id)) }) { Text("编辑后重发") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionContainerText(text: String, modifier: Modifier = Modifier, scale: Float) {
    androidx.compose.foundation.text.selection.SelectionContainer(modifier) {
        Text(text, fontSize = (17 * scale).sp, lineHeight = (28 * scale).sp)
    }
}

@Composable
private fun Composer(state: AppState, controller: ChatController, openKey: () -> Unit, attach: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 12.dp), shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (state.attachments.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.attachments.forEach { file ->
                        AssistChip({ controller.submit(ChatIntent.RemoveAttachment(file.id)) }, label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            androidx.compose.foundation.text.BasicTextField(state.draft, { controller.submit(ChatIntent.InputChanged(it)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp, max = 150.dp).padding(6.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontSize = (17 * state.preferences.fontScale).sp),
                decorationBox = { field -> Box { if (state.draft.isEmpty()) Text("发消息", color = colors.onSurfaceVariant); field() } })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(state.thinkingEnabled, { controller.submit(ChatIntent.ToggleThinking) }, { Text("深度思考", fontSize = 12.sp) }, leadingIcon = { DSIcon(R.drawable.ic_think_outline_16, null, 16.dp) })
                FilterChip(state.searchEnabled, { controller.submit(ChatIntent.ToggleSearch) }, { Text("智能搜索", fontSize = 12.sp) }, leadingIcon = { DSIcon(R.drawable.ic_globe_language_outline_16, null, 16.dp) })
                Spacer(Modifier.weight(1f))
                IconButton(attach, enabled = state.activeTurnId == null) { DSIcon(R.drawable.ic_photo_outline_20, "添加附件") }
                FilledIconButton({
                    if (state.activeTurnId != null) controller.stop()
                    else if (!state.apiKeyConfigured) openKey()
                    else { keyboard?.hide(); controller.send() }
                }, enabled = state.ready && (state.activeTurnId != null || state.draft.isNotBlank() || state.attachments.isNotEmpty() || !state.apiKeyConfigured)) {
                    if (state.activeTurnId != null) Text("■", modifier = Modifier.semantics { contentDescription = "停止生成" }) else DSIcon(R.drawable.ic_send_fill_20, "发送")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(state: AppState, controller: ChatController, dismiss: () -> Unit) {
    var input by remember { mutableStateOf("") } // Deliberately not rememberSaveable.
    var submitted by remember { mutableStateOf(false) }
    var sawChecking by remember { mutableStateOf(false) }
    val focus = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.keyChecking, state.keyError, submitted) {
        if (state.keyChecking && submitted) sawChecking = true
        if (submitted && sawChecking && !state.keyChecking && state.keyError == null && state.apiKeyConfigured) { input = ""; dismiss() }
    }
    ModalBottomSheet(onDismissRequest = { if (!state.keyChecking) { input = ""; dismiss() } }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("API Key", style = MaterialTheme.typography.titleLarge)
            Text("校验通过后加密保存在本机 Android Keystore 保护的存储中。对话直接发送到 DeepSeek。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                enabled = !state.keyChecking, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), label = { Text("API Key") })
            state.keyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button({ submitted = true; sawChecking = false; focus?.hide(); controller.validateAndSaveKey(input) }, enabled = input.isNotBlank() && !state.keyChecking,
                modifier = Modifier.fillMaxWidth().height(52.dp)) { if (state.keyChecking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("校验并保存") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DSIcon(resource: Int, description: String?, size: Dp = 23.dp) {
    Icon(painterResource(resource), description, modifier = Modifier.size(size))
}

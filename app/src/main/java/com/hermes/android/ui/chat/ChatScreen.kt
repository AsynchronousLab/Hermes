package com.hermes.android.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.core.model.ReasoningLevel
import com.hermes.android.core.net.RpcConnectionState
import com.hermes.android.ui.markdown.MarkdownText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Chat surface styled after Feishu / QQ.
 *
 * Window insets are handled explicitly: the transcript scrolls under the status
 * bar, and the composer lifts above the keyboard via [imePadding], so the input
 * is never hidden behind the IME.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Boolean,
    onPickModel: () -> Unit,
    onPickReasoning: () -> Unit,
    onForkFrom: (ChatMessage) -> Unit,
    onNewSession: () -> Unit,
    onDismiss: () -> Unit,
    onAddAttachments: (List<PendingAttachment>) -> Unit,
    onRemoveAttachment: (Long) -> Unit,
    onAttachmentId: () -> Long,
    onSpeakToggle: (Boolean) -> Unit,
    onSpeak: (String) -> Unit,
    onRequestResponse: (ServerRequest, String) -> Unit,
    onReadError: (String) -> Unit,
    onConsumeRestoredDraft: () -> Unit,
) {
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    // A failed send (upload or submit) hands the text back: retry is one tap
    // instead of a retype.
    LaunchedEffect(state.restoreDraft) {
        state.restoreDraft?.let {
            input = it
            onConsumeRestoredDraft()
        }
    }

    // What the transcript actually shows: empty assistant rows dropped and
    // consecutive assistant rows folded into one bubble. Everything below
    // that touches the message list goes through this, never `state.messages`.
    val transcript = remember(state.messages) { visibleTranscript(state.messages) }

    // Follow the stream as it grows. Keyed on the last message's *text*, not
    // the message count: deltas grow an existing message without adding one,
    // so a count-keyed effect never re-runs while a reply streams.
    //
    // `autoFollow` turns off as soon as the user scrolls away and back on when
    // they return to the bottom, so reading history is not interrupted by every
    // incoming delta.
    val lastMessage = transcript.lastOrNull()
    val scrollKey = "${transcript.size}:${lastMessage?.key}:${lastMessage?.text?.length}"
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(scrollKey) {
        if (transcript.isNotEmpty() && autoFollow) {
            listState.animateScrollToEnd()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            // Bottom-edge test, not index alone: a last bubble taller than the
            // viewport keeps its index visible while the user reads halfway up
            // it, and an index-only check then yanked them back down on every
            // delta. Same predicate the room transcript uses.
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (
                lastVisible != null &&
                    lastVisible.index >= info.totalItemsCount - 1 &&
                    remainingScrollToEnd(
                        lastVisible.offset, lastVisible.size,
                        info.viewportEndOffset, info.afterContentPadding,
                    ) <= 0.5f
                )
        }.distinctUntilChanged().collect { atBottom -> autoFollow = atBottom }
    }

    // Read-aloud: keyed on the newest *assistant* message identity, so it never
    // speaks the user's own message and fires when the reply is final.
    val speak = onSpeak
    var lastSpokenKey by remember { mutableStateOf(0L) }
    val newestAssistant = transcript.lastOrNull {
        it.role == ChatMessage.Role.Assistant && it.text.isNotBlank()
    }
    LaunchedEffect(state.speakReplies, newestAssistant?.key, state.streaming) {
        if (!state.speakReplies || state.streaming) return@LaunchedEffect
        val last = newestAssistant ?: return@LaunchedEffect
        if (last.key != lastSpokenKey) {
            lastSpokenKey = last.key
            speak(last.text)
        }
    }

    // File picker → read on IO, stopping as soon as the caps are hit. Reading
    // every pick first and filtering afterwards could hold dozens of base64
    // copies in memory at once.
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        scope.launch {
            val ok = mutableListOf<PendingAttachment>()
            val rejected = mutableListOf<String>()
            val alreadyStaged = state.pending
            var total = alreadyStaged.sumOf { it.size }
            val room = MAX_ATTACHMENT_COUNT - alreadyStaged.size

            for (uri in uris) {
                if (ok.size >= room) {
                    rejected += "最多 ${MAX_ATTACHMENT_COUNT} 个附件，其余已跳过"
                    break
                }
                if (total >= MAX_TOTAL_ATTACHMENT_BYTES) {
                    rejected += "附件总大小超过 ${MAX_TOTAL_ATTACHMENT_BYTES / 1024 / 1024} MB"
                    break
                }
                // Remaining headroom for this one file: never read more than the
                // total cap allows, so a big selection cannot pile up in memory.
                val budget = minOf(MAX_ATTACHMENT_BYTES, MAX_TOTAL_ATTACHMENT_BYTES - total)
                when (val r = context.readAttachment(uri, onAttachmentId(), limit = budget)) {
                    is AttachmentReadResult.Ok -> {
                        ok.add(r.attachment)
                        total += r.attachment.size
                    }

                    is AttachmentReadResult.Rejected -> rejected.add("${r.name}：${r.reason}")
                }
            }
            if (ok.isNotEmpty()) onAddAttachments(ok)
            if (rejected.isNotEmpty()) onReadError(rejected.joinToString("\n"))
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = if (input.isBlank()) spoken else "$input $spoken"
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatTopBar(
            state = state,
            onPickModel = onPickModel,
            onPickReasoning = onPickReasoning,
            onNewSession = onNewSession,
            onSpeakToggle = onSpeakToggle,
        )

        ConnectionBanner(state.connection)
        state.notice?.let { NoticeStrip(it, onDismiss) }
        state.error?.let { NoticeStrip(it, onDismiss, isError = true) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                transcript.isEmpty() -> EmptyTranscript(state.title)

                else -> {
                    // ── 定位按钮 ──────────────────────────────────────────
                    // 上箭头：视口往回找最近的一条用户消息；下箭头：往前找。
                    // 锚点是当前视口首项，不是固定游标——固定游标会让两个
                    // 键都从最旧那条开始递进，行为完全一样。
                    // 双击：上=回到开头，下=回到结尾。
                    val userIndices = remember(transcript) {
                        transcript.indices.filter {
                            transcript[it].role == ChatMessage.Role.User
                        }
                    }
                    var lastUpTap by remember { mutableLongStateOf(0L) }
                    var lastDownTap by remember { mutableLongStateOf(0L) }
                    val atBottom by remember {
                        // True only when the last item's *bottom edge* has passed
                        // the viewport's end. Comparing indices hid the arrow too
                        // early: a tall final bubble whose top was just visible
                        // reported "at bottom" while the reader could still
                        // scroll a long way.
                        derivedStateOf {
                            val info = listState.layoutInfo
                            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
                            lastVisible.index >= info.totalItemsCount - 1 &&
                                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 1
                        }
                    }
                    val atTop by remember {
                        derivedStateOf { listState.firstVisibleItemIndex <= 0 }
                    }

                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(transcript, key = { it.key }) { msg ->
                                MessageBubble(
                                    msg = msg,
                                    onSpeak = onSpeak,
                                    onFork = if (msg.role == ChatMessage.Role.Assistant &&
                                        msg.text.isNotBlank() && !msg.streaming
                                    ) {
                                        { onForkFrom(msg) }
                                    } else null,
                                )
                            }
                            // A streaming assistant row with nothing in it yet is
                            // dropped by `visibleTranscript`, so this is what covers
                            // the gap between the tool boundary and the first delta.
                            if (state.streaming && transcript.none { it.streaming }) {
                                item(key = "typing") { TypingIndicator() }
                            }
                        }

                        // 双击阈值：两次点击间隔小于 300ms 视为双击。
                        Column(
                            Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!atTop) {
                                ScrollJumpButton(
                                    icon = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "上一条我的消息",
                                    onClick = {
                                        val now = System.currentTimeMillis()
                                        val target = if (now - lastUpTap < DOUBLE_TAP_MS) {
                                            0
                                        } else {
                                            val here = listState.firstVisibleItemIndex
                                            // No earlier user message left: go to the
                                            // very top rather than doing nothing.
                                            userIndices.lastOrNull { it < here } ?: 0
                                        }
                                        lastUpTap = now
                                        scope.launch { listState.animateScrollToItem(target) }
                                    },
                                )
                            }
                            if (!atBottom) {
                                ScrollJumpButton(
                                    icon = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "下一条我的消息",
                                    onClick = {
                                        val now = System.currentTimeMillis()
                                        val last = listState.layoutInfo.totalItemsCount - 1
                                        val target = if (now - lastDownTap < DOUBLE_TAP_MS) {
                                            last
                                        } else {
                                            val here = listState.firstVisibleItemIndex
                                            // In a two-party chat the tail after the
                                            // last user message is the reply, so with
                                            // nothing further the useful destination
                                            // is the end — not "no movement".
                                            userIndices.firstOrNull { it > here } ?: last
                                        }
                                        lastDownTap = now
                                        scope.launch {
                                            if (target == last) listState.animateScrollToEnd()
                                            else listState.animateScrollToItem(target)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        state.pendingRequest?.let { req ->
            RequestCard(req, onRespond = { choice -> onRequestResponse(req, choice) })
        }

        state.usage?.let { UsageStrip(it) }

        Composer(
            value = input,
            streaming = state.streaming,
            uploading = state.uploading,
            sessionReady = !state.loading && state.sessionId != null,
            pending = state.pending,
            onValueChange = { input = it },
            onSend = {
                // Only clear the draft once the ViewModel accepted it, so a
                // not-yet-ready session does not silently swallow the message.
                val draft = input
                if (onSend(draft)) input = ""
            },
            onAttach = { filePicker.launch(arrayOf("*/*")) },
            onRemoveAttachment = onRemoveAttachment,
            onVoice = {
                val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .apply {
                        putExtra(
                            android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(
                            android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                            // BCP 47 tag, not the Locale object: the extra is a
                            // String, so a Locale rides the Serializable
                            // overload and the recognizer ignores it.
                            Locale.getDefault().toLanguageTag(),
                        )
                    }
                // A ROM with no recognizer installed makes `launch` throw
                // ActivityNotFoundException. The bare `runCatching` swallowed it,
                // so the button looked simply dead — say what went wrong instead.
                // The pre-check gives the clean "nothing installed" message; the
                // catch still covers engines that die on the way up.
                val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.queryIntentActivities(
                        i, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
                }
                if (handlers.isNullOrEmpty()) {
                    Toast.makeText(context, "设备未安装语音识别服务", Toast.LENGTH_SHORT).show()
                } else {
                    runCatching { voiceLauncher.launch(i) }.onFailure {
                        Toast.makeText(context, "语音识别启动失败", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    state: ChatUiState,
    onPickModel: () -> Unit,
    onPickReasoning: () -> Unit,
    onNewSession: () -> Unit,
    onSpeakToggle: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            // The host Scaffold already applies statusBarsPadding.
            title = {
                Column {
                    Text(
                        state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        state.model ?: "未选择模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            },
            navigationIcon = {
                Box(
                    Modifier
                        .padding(start = 12.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Text("☤", fontSize = 12.sp) }
            },
            actions = {
                AssistChip(
                    onClick = onPickModel,
                    label = {
                        Text(
                            state.model?.substringAfterLast('/')?.take(10) ?: "模型",
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.padding(end = 2.dp),
                )
                AssistChip(
                    onClick = onPickReasoning,
                    label = { Text(state.reasoning.label, fontSize = 11.sp) },
                    modifier = Modifier.padding(end = 6.dp),
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        // 分叉已移到每条 AI 回复上的「从此处继续」，
                        // 这里只保留会话级操作。
                        DropdownMenuItem(
                            text = { Text("新建会话") },
                            onClick = { menu = false; onNewSession() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.speakReplies) "关闭自动朗读" else "自动朗读回复") },
                            onClick = { menu = false; onSpeakToggle(!state.speakReplies) },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ConnectionBanner(state: RpcConnectionState) {
    if (state == RpcConnectionState.Connected || state == RpcConnectionState.Disconnected) return
    val text = when (state) {
        RpcConnectionState.Connecting -> "正在连接后端…"
        RpcConnectionState.Reconnecting -> "连接断开，正在重连…"
        RpcConnectionState.Failed -> "连接失败，请检查设置中的后端地址"
        else -> ""
    }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun NoticeStrip(text: String, onDismiss: () -> Unit, isError: Boolean = false) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onDismiss).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, color = fg, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.Close, "关闭", Modifier.size(14.dp))
    }
}

@Composable
private fun EmptyTranscript(title: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("☤", fontSize = 44.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "支持 Markdown、代码块；可上传文件、语音输入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Two taps closer together than this count as a double tap. */
private const val DOUBLE_TAP_MS = 300L

@Composable
private fun ScrollJumpButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Collapses the raw assistant rows into the bubbles the transcript should show.
 *
 * One turn is *not* one message: every tool boundary closes the open segment,
 * so post-tool text lands in a **new** assistant row, and a reasoning-only row
 * carries no body at all. Drawn one-row-per-bubble that produced stacks of
 * empty slivers and lone "思考" strips, which pushed the real paragraphs off
 * screen — in the field report half the viewport was blank between two
 * sentences.
 *
 * Two passes:
 *  - drop rows that would render nothing (no body, no reasoning, no tools, no
 *    attachments, not failed). A streaming-but-empty row is dropped too: the
 *    typing indicator is the right thing to show for it;
 *  - fold each run of consecutive assistant rows into a single bubble.
 *
 * The merged row carries the *last* row's key, so `forkFrom` — which cuts the
 * history prefix at `indexOfFirst { it.key == message.key }` — branches after
 * the whole visible bubble instead of halfway through it. It only changes when
 * the run grows a new segment, which is exactly when the content changed too.
 */
internal fun visibleTranscript(rows: List<ChatMessage>): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    for (row in rows) {
        // User and System rows are always their own bubble; only assistant rows
        // can be empty, because only they are split into several rows.
        val rendersSomething = row.role != ChatMessage.Role.Assistant ||
            row.text.isNotBlank() || row.hasThinking || row.tools.isNotEmpty() ||
            row.attachments.isNotEmpty() || row.failed
        if (!rendersSomething) continue

        val prev = out.lastOrNull()
        // Slash-command output is a UI-only row; merging it into a real reply
        // would make `forkFrom` treat the whole merged bubble as droppable.
        val mergeable = prev != null &&
            row.role == ChatMessage.Role.Assistant &&
            prev.role == ChatMessage.Role.Assistant &&
            !prev.isSlashOutput && !row.isSlashOutput
        if (mergeable) {
            out[out.lastIndex] = mergeAssistant(prev, row)
        } else {
            out += row
        }
    }
    return out
}

private fun mergeAssistant(a: ChatMessage, b: ChatMessage) = a.copy(
    key = b.key,
    text = joinBlocks(a.text, b.text),
    reasoning = joinBlocks(a.reasoning, b.reasoning),
    tools = a.tools + b.tools,
    attachments = a.attachments + b.attachments,
    streaming = a.streaming || b.streaming,
    failed = a.failed || b.failed,
    usage = a.usage ?: b.usage,
    timestamp = a.timestamp,
)

/** Blank halves contribute no separator, so empty rows leave no blank line. */
private fun joinBlocks(x: String, y: String): String = when {
    x.isBlank() -> y
    y.isBlank() -> x
    else -> "$x\n\n$y"
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    onSpeak: (String) -> Unit,
    /** Non-null only on finished assistant replies: "continue from here". */
    onFork: (() -> Unit)? = null,
) {
    val isUser = msg.role == ChatMessage.Role.User
    val clipboard = LocalClipboardManager.current

    // Anything that would render *below* the execution strip. Gates the
    // divider so a strip-only bubble (thinking arrived, body did not) does not
    // reserve blank space for content that never came. The undelivered marker
    // is deliberately absent: it only ever shows on a user bubble, and the
    // strip never renders there, so including it changed nothing.
    val hasBody = msg.text.isNotBlank() || msg.attachments.isNotEmpty() || msg.failed

    if (msg.role == ChatMessage.Role.System) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
            Text(
                msg.text, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            Avatar()
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp, topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 14.dp,
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            // Reasoning and tool calls collapse into a single strip so the
            // bubble stays readable; both remain one tap away.
            if (!isUser && (msg.hasThinking || msg.tools.isNotEmpty())) {
                ExecutionStrip(msg)
                if (hasBody) {
                    Spacer(Modifier.height(7.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Spacer(Modifier.height(7.dp))
                }
            }

            if (msg.attachments.isNotEmpty()) {
                msg.attachments.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (a.kind) {
                                AttachmentRef.Kind.IMAGE -> Icons.Default.Image
                                AttachmentRef.Kind.PDF -> Icons.Default.PictureAsPdf
                                AttachmentRef.Kind.FILE -> Icons.Default.InsertDriveFile
                            },
                            null, Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            a.name, fontSize = 11.sp,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
            }

            // A blank body still occupied a full line of `Text`, so reasoning-only rows
            // stayed tall even after the divider stopped being drawn.
            if (msg.text.isNotBlank()) {
                MarkdownText(
                    // Was `msg.text.ifEmpty { if (msg.streaming) "" else "" }` — both
                    // branches returned "", so the whole expression was a no-op.
                    text = msg.text,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (msg.failed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "本轮出错",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // The bubble is rendered before the upload/submit round trip, so an
            // unsuccessful send would otherwise be indistinguishable from a
            // delivered one.
            if (isUser && !msg.delivered) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "未发送",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!isUser && !msg.streaming && msg.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row {
                    Box(Modifier.clickable { clipboard.setText(AnnotatedString(msg.text)) }.padding(2.dp)) {
                        Icon(
                            Icons.Default.ContentCopy, "复制",
                            Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.clickable { onSpeak(msg.text) }.padding(2.dp)) {
                        Icon(
                            Icons.Default.VolumeUp, "朗读",
                            Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 分叉入口挂在每条 AI 回复上：从此处继续，而不是分叉整个会话。
                    if (onFork != null) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.clickable { onFork() }.padding(2.dp)) {
                            Icon(
                                Icons.Default.CallSplit, "从此处继续",
                                Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (isUser) Spacer(Modifier.width(8.dp))
    }
}

/** Collapsed-by-default summary of reasoning + tool activity for one turn. */
@Composable
private fun ExecutionStrip(msg: ChatMessage) {
    var open by remember(msg.key) { mutableStateOf(false) }
    val summary = buildString {
        if (msg.hasThinking) append("思考 ")
        msg.toolSummary?.let { append(it) }
    }.trim()

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable { open = !open }.padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                summary.ifBlank { "执行过程" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp)) {
                if (msg.hasThinking) {
                    Text(
                        "思考过程",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        msg.reasoning.takeLast(1500),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (msg.tools.isNotEmpty()) Spacer(Modifier.height(8.dp))
                }
                msg.tools.forEach { tool ->
                    ToolRow(tool)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolCall) {
    val icon = when (tool.status) {
        ToolCall.Status.RUNNING -> "⏳"
        ToolCall.Status.DONE -> "✓"
        ToolCall.Status.FAILED -> "✗"
    }
    val tint = when (tool.status) {
        ToolCall.Status.FAILED -> MaterialTheme.colorScheme.error
        ToolCall.Status.DONE -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 11.sp, color = tint)
            Spacer(Modifier.width(5.dp))
            Text(
                tool.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            tool.context.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.width(6.dp))
                Text(
                    it.take(40),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            tool.durationMs?.let {
                Spacer(Modifier.width(6.dp))
                Text("${it / 1000}s", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (!tool.output.isNullOrBlank()) {
            Text(
                tool.output.take(400),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, top = 1.dp),
            )
        }
    }
}

@Composable
private fun RequestCard(req: ServerRequest, onRespond: (String) -> Unit) {
    var answer by remember { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (req.kind == ServerRequest.Kind.APPROVAL) "需要你的授权" else "Hermes 想确认一下",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                req.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            if (req.kind == ServerRequest.Kind.CLARIFY) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入你的回答", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onRespond(answer.ifBlank { "skip" }) },
                        enabled = answer.isNotBlank(),
                    ) { Text("发送") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRespond("once") }) { Text("仅允许这一次") }
                    OutlinedButton(onClick = { onRespond("deny") }) { Text("拒绝") }
                }
            }
        }
    }
}

@Composable
private fun Avatar() {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text("☤", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 15.sp)
    }
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar()
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
        ) {
            Text(
                "正在思考…",
                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageStrip(usage: com.hermes.android.core.model.Usage) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "token ${usage.total ?: 0}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "上下文 ${usage.contextPercent ?: 0}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if ((usage.avgTps ?: 0.0) > 0) {
            Text(
                // Locale.US so the decimal point stays a point; the default
                // locale rendered "1,5 t/s" in comma-decimal languages.
                String.format(java.util.Locale.US, "%.1f t/s", usage.avgTps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    streaming: Boolean,
    uploading: Boolean,
    sessionReady: Boolean,
    pending: List<PendingAttachment>,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Long) -> Unit,
    onVoice: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                // Lifts the whole composer above the soft keyboard.
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            if (pending.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pending.forEach { a ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onRemoveAttachment(a.id) }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                when (a.kind) {
                                    AttachmentRef.Kind.IMAGE -> Icons.Default.Image
                                    AttachmentRef.Kind.PDF -> Icons.Default.PictureAsPdf
                                    AttachmentRef.Kind.FILE -> Icons.Default.InsertDriveFile
                                },
                                null, Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                a.name.take(18),
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Close, "移除", Modifier.size(12.dp))
                        }
                    }
                }
            }

            // Fixed-height row so the attach/mic/send controls sit on the same baseline
            // as the single-line input instead of being stretched to the
            // OutlinedTextField's minimum height.
            val controlSize = 40.dp
            Row(
                Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onAttach,
                    enabled = !uploading,
                    modifier = Modifier.size(controlSize),
                ) {
                    if (uploading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.AddCircleOutline, "上传文件", Modifier.size(21.dp))
                    }
                }
                IconButton(onClick = onVoice, modifier = Modifier.size(controlSize)) {
                    Icon(Icons.Default.Mic, "语音输入", Modifier.size(21.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息，或输入 / 命令", fontSize = 15.sp) },
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = (value.isNotBlank() || pending.isNotEmpty()) &&
                        !streaming && !uploading && sessionReady,
                    modifier = Modifier.size(controlSize),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

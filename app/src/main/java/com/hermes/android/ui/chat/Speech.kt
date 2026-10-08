package com.hermes.android.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Wraps the platform text-to-speech engine for read-aloud replies.
 *
 * The engine is created lazily and shut down with the composable, and any
 * failure degrades to "no audio" rather than crashing the chat.
 */
class HermesSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.0f)
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " 代码块 ")
            .replace(Regex("[*_`#>]"), "")
            .trim()
        if (clean.isBlank()) return
        // QUEUE_FLUSH keeps a rapid sequence of replies from stacking up.
        tts?.speak(clean.take(4000), TextToSpeech.QUEUE_FLUSH, null, "hermes-reply")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}

/**
 * Hard ceiling for a single attachment.
 *
 * Two independent limits apply:
 *  - the gateway rejects anything above 20 MiB;
 *  - OkHttp 4.12 caps the WebSocket send queue at 16 MiB, and base64 inflates
 *    the payload by ~4/3, so anything above ~11 MiB of raw bytes would close
 *    the connection instead of being sent.
 *
 * 10 MiB leaves headroom for the JSON envelope and queue occupancy.
 */
const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

/** Ceiling on the total of all staged attachments in one message. */
const val MAX_TOTAL_ATTACHMENT_BYTES = 16L * 1024 * 1024

/** Maximum number of staged attachments per message. */
const val MAX_ATTACHMENT_COUNT = 5

/** Events held while a session binding is still in flight. */
const val PRE_BIND_EVENT_LIMIT = 64

sealed interface AttachmentReadResult {
    data class Ok(val attachment: PendingAttachment) : AttachmentReadResult
    data class Rejected(val name: String, val reason: String) : AttachmentReadResult
}

/**
 * Reads one picked URI into a staged attachment.
 *
 * The stream is read with a hard cap of [limit] + 1 bytes so an oversized file
 * is rejected without first allocating all of it, and the whole read runs on
 * [io] — the picker callback used to block the main thread.
 */
suspend fun Context.readAttachment(
    uri: Uri,
    id: Long,
    io: CoroutineDispatcher = Dispatchers.IO,
    limit: Long = MAX_ATTACHMENT_BYTES,
): AttachmentReadResult = withContext(io) {
    val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
    val name = runCatching {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: "attachment"

    val bytes = runCatching {
        contentResolver.openInputStream(uri)?.use { stream ->
            // Cap the read at limit+1 so oversize is detected without
            // materialising the whole file.
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(chunk)
                if (n <= 0) break
                total += n
                if (total > limit) return@use null
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        }
    }.getOrNull()

    when {
        bytes == null -> AttachmentReadResult.Rejected(
            name, "文件超过 ${limit / 1024 / 1024} MB 上限"
        )

        else -> AttachmentReadResult.Ok(
            PendingAttachment(
                id = id,
                name = name,
                kind = when {
                    mime.startsWith("image/") -> AttachmentRef.Kind.IMAGE
                    mime == "application/pdf" -> AttachmentRef.Kind.PDF
                    else -> AttachmentRef.Kind.FILE
                },
                mime = mime.ifEmpty { "application/octet-stream" },
                size = bytes.size.toLong(),
                base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            )
        )
    }
}
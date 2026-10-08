package com.hermes.android.ui.chat

/** One tool invocation inside a turn. */
data class ToolCall(
    val id: String?,
    val name: String,
    val context: String = "",
    val args: String? = null,
    val output: String? = null,
    val status: Status = Status.RUNNING,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
) {
    enum class Status { RUNNING, DONE, FAILED }

    val durationMs: Long? get() = endedAt?.let { it - startedAt }
}

/** One rendered row in the conversation. */
data class ChatMessage(
    val key: Long,
    val role: Role,
    val text: String,
    val reasoning: String = "",
    val tools: List<ToolCall> = emptyList(),
    val streaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val usage: com.hermes.android.core.model.Usage? = null,
    val isSlashOutput: Boolean = false,
    /** The `/command` line itself, which is UI chrome rather than history. */
    val isSlashInput: Boolean = false,
    val attachments: List<AttachmentRef> = emptyList(),
    val failed: Boolean = false,
    /**
     * False while a user bubble's submission is still in flight, or when it
     * failed outright. The bubble is rendered before the upload/submit round
     * trip so the text is on screen immediately, which meant a failed send used
     * to look identical to a delivered one.
     */
    val delivered: Boolean = true,
) {
    enum class Role { User, Assistant, System }

    /** One-line summary shown in the collapsed execution strip. */
    val toolSummary: String?
        get() = tools.takeIf { it.isNotEmpty() }?.let { list ->
            val failed = list.count { it.status == ToolCall.Status.FAILED }
            val totalMs = list.mapNotNull { it.durationMs }.sum()
            buildString {
                append("工具调用 ×${list.size}")
                if (failed > 0) append(" · $failed 失败")
                if (totalMs > 0) append(" · ${totalMs / 1000}s")
            }
        }

    val hasThinking: Boolean get() = reasoning.isNotBlank()
}

data class AttachmentRef(
    val name: String,
    val kind: Kind,
    val localUri: String? = null,
    val uploaded: Boolean = false,
) {
    enum class Kind { IMAGE, PDF, FILE }
}

data class PendingAttachment(
    val id: Long,
    val name: String,
    val kind: AttachmentRef.Kind,
    val mime: String,
    val size: Long,
    val base64: String,
    val error: String? = null,
    /**
     * Bytes already accepted by the gateway in an earlier attempt of this same
     * send task. The staged task stays whole until the prompt itself is
     * submitted, so a partial-upload retry only re-sends what failed.
     */
    val uploaded: Boolean = false,
)

data class ChatUiState(
    val sessionId: String? = null,
    val storedSessionId: String? = null,
    val title: String = "新会话",
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val connection: com.hermes.android.core.net.RpcConnectionState =
        com.hermes.android.core.net.RpcConnectionState.Disconnected,
    val model: String? = null,
    val provider: String? = null,
    val reasoning: com.hermes.android.core.model.ReasoningLevel =
        com.hermes.android.core.model.ReasoningLevel.MEDIUM,
    val usage: com.hermes.android.core.model.Usage? = null,
    val models: List<com.hermes.android.core.model.ModelProvider> = emptyList(),
    val commands: List<com.hermes.android.core.CommandEntry> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
    val loading: Boolean = true,
    val pending: List<PendingAttachment> = emptyList(),
    val uploading: Boolean = false,
    val listening: Boolean = false,
    val speakReplies: Boolean = false,
    val pendingRequest: ServerRequest? = null,
    /**
     * Text handed back to the composer after a failed send (upload or submit),
     * so a retry is one tap instead of a retype. Cleared once applied.
     */
    val restoreDraft: String? = null,
)

/** An interactive request the agent is blocked on (approval / clarify). */
data class ServerRequest(
    val id: String,
    val kind: Kind,
    val text: String,
) {
    enum class Kind { APPROVAL, CLARIFY, OTHER }
}
package com.hermes.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire contracts for the Hermes gateway (Agent v0.21.5, probed live).
 *
 * The server answers `session.list` with a trimmed projection while the REST
 * endpoint `/api/sessions` returns the full record, so both shapes are modelled
 * and the richer one wins when both are available.
 */

@Serializable
data class HermesHealth(
    val ok: Boolean = false,
    val version: String? = null,
    @SerialName("displayVersion") val displayVersion: String? = null,
    @SerialName("auth_required") val authRequired: Boolean? = null,
)

@Serializable
data class HermesMe(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    @SerialName("org_id") val orgId: String? = null,
    val provider: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

@Serializable
data class WsTicket(val ticket: String, @SerialName("ttl_seconds") val ttlSeconds: Int = 30)

/** A conversation entry as returned by `session.list`. */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    val source: String? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "未命名会话"
}

/** Full session record from `GET /api/sessions/{id}`. */
@Serializable
data class SessionDetail(
    val id: String,
    val source: String? = null,
    val model: String? = null,
    @SerialName("model_config") val modelConfig: String? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    val cwd: String? = null,
    @SerialName("git_branch") val gitBranch: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("end_reason") val endReason: String? = null,
)

/** Result of `session.create`. */
@Serializable
data class SessionCreated(
    @SerialName("session_id") val sessionId: String,
    @SerialName("stored_session_id") val storedSessionId: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    val info: JsonObject? = null,
)

@Serializable
data class SessionInfo(
    val model: String? = null,
    val tools: JsonElement? = null,
    val skills: JsonElement? = null,
    val cwd: String? = null,
    val branch: String? = null,
    val project: String? = null,
    val lazy: Boolean? = null,
    @SerialName("profile_name") val profileName: String? = null,
)

@Serializable
data class SessionListResult(val sessions: List<SessionSummary> = emptyList())

@Serializable
data class SessionMessagesResult(
    val count: Int = 0,
    val messages: List<SessionMessage> = emptyList(),
)

/**
 * One transcript row.
 *
 * `session.history` renders `{role, text, timestamp, row_id}` while
 * `GET /api/sessions/{id}/messages` renders `{role, content, timestamp, id}` —
 * both shapes are decoded into this one type.
 */
@Serializable
data class SessionMessage(
    val id: Long? = null,
    @SerialName("row_id") val rowId: Long? = null,
    val role: String = "assistant",
    val content: String = "",
    val text: String = "",
    val timestamp: Double? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val name: String? = null,
    val model: String? = null,
    val kind: String? = null,
    val reasoning: String? = null,
) {
    /** Whichever of the two content fields the server actually sent. */
    val body: String get() = if (text.isNotEmpty()) text else content

    val isUser: Boolean get() = role == "user"
    val isSystem: Boolean get() = role == "system"
}

/** `session.info` event payload — authoritative live model / reasoning state. */
@Serializable
data class SessionInfoPayload(
    val model: String? = null,
    val provider: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("reasoning_effort_wire") val reasoningEffortWire: String? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    val fast: Boolean? = null,
    val yolo: Boolean? = null,
    @SerialName("approval_mode") val approvalMode: String? = null,
    val tools: JsonObject? = null,
)

@Serializable
data class Usage(
    val model: String? = null,
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val prompt: Long? = null,
    val completion: Long? = null,
    val total: Long? = null,
    val calls: Int? = null,
    @SerialName("context_used") val contextUsed: Long? = null,
    @SerialName("context_max") val contextMax: Long? = null,
    @SerialName("context_percent") val contextPercent: Int? = null,
    @SerialName("cache_hit_pct") val cacheHitPct: Int? = null,
    @SerialName("avg_latency_s") val avgLatencyS: Double? = null,
    @SerialName("avg_tps") val avgTps: Double? = null,
)

@Serializable
data class PromptSubmitResult(
    val status: String? = null,
    @SerialName("user_row_id") val userRowId: Long? = null,
)

@Serializable
data class SlashExecResult(val output: String = "")

@Serializable
data class CommandsCatalogResult(val pairs: List<List<String>> = emptyList())

@Serializable
data class ModelProvider(
    val slug: String = "",
    val name: String = "",
    @SerialName("is_current") val isCurrent: Boolean = false,
    @SerialName("is_user_defined") val isUserDefined: Boolean = false,
    val models: List<String> = emptyList(),
    @SerialName("total_models") val totalModels: Int = 0,
)

@Serializable
data class ModelOptionsResult(val providers: List<ModelProvider> = emptyList())

@Serializable
data class ModelInfo(
    val model: String? = null,
    val provider: String? = null,
    @SerialName("auto_context_length") val autoContextLength: Long? = null,
    @SerialName("effective_context_length") val effectiveContextLength: Long? = null,
    val capabilities: JsonElement? = null,
)

@Serializable
data class Toolset(
    val name: String = "",
    val description: String? = null,
    @SerialName("tool_count") val toolCount: Int = 0,
    val enabled: Boolean = false,
    val tools: List<String> = emptyList(),
)

@Serializable
data class ToolsListResult(val toolsets: List<Toolset> = emptyList())

@Serializable
data class AgentProcess(
    @SerialName("session_id") val sessionId: String? = null,
    val command: String? = null,
    val status: String? = null,
    val uptime: Double? = null,
)

@Serializable
data class AgentsListResult(val processes: List<AgentProcess> = emptyList())

@Serializable
data class Profile(
    val name: String = "",
    val path: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("skill_count") val skillCount: Int? = null,
    @SerialName("gateway_running") val gatewayRunning: Boolean? = null,
    val description: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("bot_title") val botTitle: String? = null,
    @SerialName("has_alias") val hasAlias: Boolean? = null,
    val role: String? = null,
) {
    val title: String get() = displayName?.takeIf { it.isNotBlank() }
        ?: botTitle?.takeIf { it.isNotBlank() }
        ?: name
}

@Serializable
data class ProfilesResult(val profiles: List<Profile> = emptyList())

@Serializable
data class Skill(
    val name: String = "",
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean = false,
    val usage: Int? = null,
    val provenance: String? = null,
)

@Serializable
data class FileEntry(
    val name: String = "",
    val path: String = "",
    @SerialName("is_directory") val isDirectory: Boolean = false,
    val size: Long? = null,
    val mtime: Double? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

@Serializable
data class FileListing(
    val path: String = "/",
    val parent: String? = null,
    val entries: List<FileEntry> = emptyList(),
)

/**
 * Schedule spec. The gateway returns an object
 * (`{"kind":"cron","expr":"5 0 * * *","display":"5 0 * * *"}`), not a bare string —
 * decoding it as `String` is what produced the "Unexpected JSON token … $[0].schedule"
 * crash on the 定时 tab.
 */
@Serializable
data class CronSchedule(
    val kind: String = "cron",
    val expr: String = "",
    val display: String = "",
) {
    val label: String get() = display.ifBlank { expr.ifBlank { kind } }
}

@Serializable
data class CronJob(
    val id: String = "",
    val name: String? = null,
    val prompt: String? = null,
    val schedule: CronSchedule? = null,
    @SerialName("schedule_display") val scheduleDisplay: String? = null,
    val enabled: Boolean? = null,
    val state: String? = null,
    val profile: String? = null,
    @SerialName("profile_name") val profileName: String? = null,
    val model: String? = null,
    val skills: List<String> = emptyList(),
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: "未命名任务"

    /** Human-readable cadence, tolerant of both the object and the flat field. */
    val scheduleLabel: String
        get() = schedule?.label?.takeIf { it.isNotBlank() }
            ?: scheduleDisplay?.takeIf { it.isNotBlank() }
            ?: "—"

    /** True when the job exists but is paused/stopped server-side. */
    val paused: Boolean
        get() = enabled == false || state?.lowercase() in setOf("paused", "stopped", "disabled")
}

@Serializable
data class MessagingPlatform(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("configured") val configured: Boolean? = null,
    @SerialName("docs_url") val docsUrl: String? = null,
)

@Serializable
data class MessagingPlatformsResult(
    @SerialName("env_path") val envPath: String? = null,
    @SerialName("gateway_start_command") val gatewayStartCommand: String? = null,
    val platforms: List<MessagingPlatform> = emptyList(),
)

@Serializable
data class PairingEntry(
    val platform: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String? = null,
    @SerialName("approved_at") val approvedAt: Double? = null,
)

@Serializable
data class PairingResult(
    val pending: List<PairingEntry> = emptyList(),
    val approved: List<PairingEntry> = emptyList(),
)

@Serializable
data class OpenRequest(
    val id: String = "",
    val method: String = "",
    val session_id: String? = null,
    val payload: JsonElement? = null,
)

@Serializable
data class SessionEventsResult(
    val events: List<JsonObject> = emptyList(),
    @SerialName("latest_seq") val latestSeq: Long = 0,
    val truncated: Boolean = false,
    val count: Int = 0,
    val epoch: String? = null,
    @SerialName("open_requests") val openRequests: List<OpenRequest> = emptyList(),
)

@Serializable
data class CapabilitiesResult(@SerialName("server_requests") val serverRequests: List<String> = emptyList())

/** Reasoning levels exactly as the dashboard exposes them. */
enum class ReasoningLevel(val wire: String, val label: String) {
    NONE("none", "关闭思考"),
    MINIMAL("minimal", "极简"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    XHIGH("xhigh", "超高"),
    MAX("max", "最大"),
    ULTRA("ultra", "极致");

    companion object {
        val wireOrder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
        fun from(wire: String?): ReasoningLevel =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() } ?: MEDIUM
    }
}
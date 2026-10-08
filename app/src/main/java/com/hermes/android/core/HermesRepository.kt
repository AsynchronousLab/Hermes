package com.hermes.android.core

import com.hermes.android.core.model.*
import com.hermes.android.core.net.HermesAuth
import com.hermes.android.core.net.HermesConfig
import com.hermes.android.core.net.HermesHttp
import com.hermes.android.core.net.HermesRest
import com.hermes.android.core.net.JsonRpcClient
import com.hermes.android.core.net.SessionCookieJar
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Single facade over the Hermes gateway.
 *
 * Transport split (verified live against Agent 0.21.5):
 *  - JSON-RPC over `ws://host/api/ws?ticket=…` → sessions, chat, streaming, models
 *  - REST under `/api/…` → skills, cron, files, messaging, pairing
 */
class HermesRepository {
    private val jar = SessionCookieJar()
    private val client: OkHttpClient = HermesHttp.client(jar, debug = false)
    private val auth = HermesAuth(jar, client)

    @Volatile var config: HermesConfig = HermesConfig()
        private set

    val rpc = JsonRpcClient(client, auth)
    val rest = HermesRest(client) { config }

    val events: SharedFlow<com.hermes.android.core.net.RpcEvent> get() = rpc.events
    val connection: StateFlow<com.hermes.android.core.net.RpcConnectionState> get() = rpc.state

    /**
     * True when the shared WebSocket is live.
     *
     * Secondary screens check this before asking to connect: the repository
     * (and therefore the socket) is shared with the chat screen, so an
     * unconditional connect would drop the connection it is streaming on.
     */
    val isConnected: Boolean get() = rpc.isConnected

    /**
     * Bumped by every `disconnect()` / `updateConfig()` / `connect()`.
     *
     * A password login is a network round-trip, so an account switch that lands
     * while one is in flight used to be undone by that login completing
     * afterwards. Each login claims the epoch it started under and commits its
     * cookies only if it still owns it.
     */
    @Volatile private var authEpoch = 0L

    /**
 * Swaps in a new backend.
 *
 * Changing the address must invalidate the live socket too: REST would start
 * using the new host while RPC kept publishing to the old one, so a message
 * sent after a switch could still land on the previous backend.
 */
suspend fun updateConfig(cfg: HermesConfig) {
        val changed = cfg.normalizedBaseUrl != config.normalizedBaseUrl ||
            cfg.username != config.username
        config = cfg
        if (changed) {
            // Same lock the login commit takes, so a switch cannot land
            // between a login's epoch check and its absorb.
            synchronized(authLock) {
                cancelEpoch++
                authEpoch++
            }
            jar.clear()
            rpc.disconnect()
        }
    }

    init {
        // The reconnect loop lives in [JsonRpcClient] and only knows how to fetch
        // a ws-ticket; it cannot log in by itself. Hand it the one piece it is
        // missing, so an expired session cookie recovers instead of 401ing
        // forever at the backoff cap.
        rpc.reauthenticate = { loginFresh() }
    }

    /**
     * Serialises the login section.
     *
     * Two screens asking to connect at the same time used to both run a login.
     * Only one won the epoch and the loser reported "配置已变更" for what was
     * plain concurrency. The second now waits and finds the session the first
     * produced.
     */
    private val authMutex = kotlinx.coroutines.sync.Mutex()

    /** Guards [authEpoch] plus the cookie commit, which must not be separable. */
    private val authLock = Any()

    /** Bumped by disconnect/updateConfig; a login must not undo a user cancel. */
    @Volatile private var cancelEpoch = 0L

    /** Outcome of the login section, kept distinct so callers can react. */
    private sealed interface LoginOutcome {
        data class Ok(val reused: Boolean) : LoginOutcome
        data class Failed(val message: String) : LoginOutcome

        /** Superseded by an account switch or an explicit disconnect. */
        data object Stale : LoginOutcome
    }

    /**
     * Runs one login for [cfg], committing its cookies atomically with the
     * checks that authorise the commit.
     *
     * The network call stays outside [authLock]; only the epoch check and
     * [SessionCookieJar.absorb] are inside it. Even splitting those two was
     * enough to lose a race — a pause between the check and the commit let an
     * account switch land in the gap, and the stale account's cookie won.
     */
    private suspend fun performLogin(cfg: HermesConfig, force: Boolean): LoginOutcome =
        authMutex.withLock {
            if (force) jar.clear()
            if (jar.hasSession()) return@withLock LoginOutcome.Ok(reused = true)

            var epoch = 0L
            synchronized(authLock) { epoch = ++authEpoch }
            val (result, scratch) = auth.loginScratch(cfg)
            if (result is HermesAuth.LoginResult.Failed) {
                return@withLock LoginOutcome.Failed(result.message)
            }
            val committed = synchronized(authLock) {
                val stillCurrent = epoch == authEpoch &&
                    config.normalizedBaseUrl == cfg.normalizedBaseUrl &&
                    config.username == cfg.username
                if (stillCurrent) jar.absorb(scratch)
                stillCurrent
            }
            if (committed) LoginOutcome.Ok(reused = false) else LoginOutcome.Stale
        }

    /**
     * Re-logs in after the socket reported a dead cookie.
     *
     * Goes through the same serialised path as [connect] so a re-login racing
     * an account switch cannot overwrite the new identity's cookie.
     */
    private suspend fun loginFresh(): Boolean {
        val cfg = config
        if (!cfg.isComplete) return false
        jar.clear()
        return performLogin(cfg, force = false) is LoginOutcome.Ok
    }

    // ---------------------------------------------------------------- auth ---

    suspend fun health(): Result<HermesHealth> = runCatching {
        val body = rest.getText("/api/health")
        HermesHttp.json.decodeFromString(HermesHealth.serializer(), body)
    }

    suspend fun me(): Result<HermesMe> = runCatching {
        HermesHttp.json.decodeFromString(
            HermesMe.serializer(),
            rest.getText("/api/auth/me"),
        )
    }

    /** Logs in if needed, then opens the WebSocket. Returns the failure if any. */
    suspend fun connect(force: Boolean = false): Result<Unit> {
        val cfg = config
        if (!cfg.isComplete) {
            return Result.failure(IllegalStateException("请先在设置中填写后端地址和账号"))
        }
        if (force) jar.clear()

        // A user disconnect is a separate decision from "another login won the
        // race". Reusing a same-account session is fine, but it must never
        // resurrect a connection the user just cancelled.
        val myCancel = cancelEpoch

        when (val outcome = performLogin(cfg, force = force)) {
            is LoginOutcome.Failed ->
                return Result.failure(IllegalStateException(outcome.message))
            LoginOutcome.Stale ->
                return Result.failure(IllegalStateException("登录期间配置已变更，已取消连接"))
            is LoginOutcome.Ok -> {
                if (myCancel != cancelEpoch) {
                    return Result.failure(IllegalStateException("已取消连接"))
                }
            }
        }

        // The RPC layer claims its own operation token, so a connect that
        // reaches here can still be superseded there — that resolves silently.
        return runCatching { rpc.connect(cfg, force = force) }
    }

    suspend fun disconnect() {
        synchronized(authLock) {
            cancelEpoch++
            authEpoch++
        }
        rpc.disconnect()
    }

    /** Settings screen "test connection": login → ticket → ws → ping. */
    suspend fun testConnection(): ConnectionTestResult {
        if (!config.isComplete) return ConnectionTestResult.Failed("请填写后端地址、用户名和密码")
        val started = System.currentTimeMillis()

        val health = health().getOrElse {
            return ConnectionTestResult.Failed(HermesAuth.describeNetworkError(it))
        }
        val httpMs = System.currentTimeMillis() - started

        if (jar.hasSession()) jar.clear()
        // The scratch jar stops a stale *response* from writing cookies, but it
        // does not cancel the test: the run continued and reconnected using
        // whatever session existed by then, reporting success for the wrong
        // backend and undoing a disconnect. Abort the whole test instead.
        val cfg = config
        val myCancel = cancelEpoch
        val outcome = performLogin(cfg, force = true)
        if (outcome is LoginOutcome.Failed) return ConnectionTestResult.Failed(outcome.message)
        if (outcome is LoginOutcome.Stale || myCancel != cancelEpoch) {
            return ConnectionTestResult.Failed("测试期间配置已变更或已取消连接，请重试")
        }

        val ticket = auth.wsTicket(config).getOrElse {
            return ConnectionTestResult.Failed("获取 WS 票据失败: ${HermesAuth.describeNetworkError(it)}")
        }

        val ping = runCatching {
            rpc.connect(config, force = true)
            var waited = 0
            while (!rpc.isConnected && waited < 8_000) {
                kotlinx.coroutines.delay(150)
                waited += 150
            }
            if (!rpc.isConnected) error("WebSocket 握手超时")
            rpc.rpcRaw("gateway.ping", timeoutMs = 10_000)
        }.getOrElse {
            rpc.disconnect()
            return ConnectionTestResult.Failed("WebSocket 失败: ${HermesAuth.describeNetworkError(it)}")
        }

        val me = me().getOrNull()
        return ConnectionTestResult.Success(
            httpMs = httpMs,
            version = health.displayVersion ?: health.version ?: "unknown",
            username = me?.userId ?: config.username,
            displayName = me?.displayName,
            wsPath = "${config.wsDisplayUrl}/api/ws",
            ticketHint = "ticket ${ticket.take(6)}… (${ticket.length} chars)",
            pingOk = ping["ok"] != null,
        )
    }

    // ------------------------------------------------------------ sessions ---

    suspend fun listSessions(): List<SessionSummary> =
        rpc.rpc("session.list", JsonObject(emptyMap()), SessionListResult.serializer()).sessions

    suspend fun createSession(): SessionCreated =
        rpc.rpc("session.create", JsonObject(emptyMap()), SessionCreated.serializer())

    /**
     * Opens a session bound to a named profile.
     *
     * Verified live: `session.create` accepts a `profile` field and the returned
     * session runs on that profile's model (`writer` → qwen3.8-27b-soyaa,
     * `ocr` → deepseek-v4.1-flash). This is what the contacts list uses — each
     * Hermes profile is a distinct persona.
     */
    suspend fun createSession(profile: String?): SessionCreated =
        rpc.rpc(
            "session.create",
            buildJsonObject { if (!profile.isNullOrBlank()) put("profile", profile) },
            SessionCreated.serializer(),
        )

    /** `session.resume` wants the durable (stored) id, not the short runtime one. */
    suspend fun resumeSession(storedSessionId: String): SessionCreated =
        rpc.rpc(
            "session.resume",
            buildJsonObject { put("session_id", storedSessionId) },
            SessionCreated.serializer(),
        )

    /**
     * Branches the conversation from a point by seeding a new session.
     *
     * `session.branch` takes only `session_id` — there is no message-level fork
     * on the gateway; every plausible cursor parameter is rejected as an extra
     * input. `session.create` however accepts `messages` plus
     * `parent_session_id`, which is enough to reproduce a history prefix.
     *
     * Verified live, and both edge cases matter: the text field must be
     * `content` (`body`/`text` are accepted and then silently dropped), and an
     * unrecognised `role` is dropped without any error. The gateway also
     * reports how many rows it actually stored, so a short seed is detectable
     * rather than silent.
     */
    suspend fun forkSessionFrom(
        sessionId: String,
        messages: List<Pair<String, String>>,
        profile: String? = null,
    ): SessionCreated {
        val seeded = rpc.rpc(
            "session.create",
            buildJsonObject {
                profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
                put("parent_session_id", sessionId)
                put(
                    "messages",
                    buildJsonArray {
                        messages.forEach { (role, content) ->
                            add(buildJsonObject {
                                put("role", role)
                                put("content", content)
                            })
                        }
                    },
                )
            },
            SessionCreated.serializer(),
        )
        // The gateway reports how many rows it actually stored. A *short* count
        // is the failure we care about — it means rows were silently dropped —
        // so the check has to be "not fewer than we sent", not "not more".
        val stored = seeded.messageCount
        require(stored == null || stored >= messages.size) {
            "分支播种丢失消息：发送 ${messages.size} 条，网关只存了 ${stored} 条"
        }
        return seeded
    }

    suspend fun history(sessionId: String): SessionMessagesResult =
        rpc.rpc(
            "session.history",
            buildJsonObject { put("session_id", sessionId) },
            SessionMessagesResult.serializer(),
        )

    suspend fun deleteSession(sessionId: String): JsonObject =
        rpc.rpcRaw("session.delete", buildJsonObject { put("session_id", sessionId) })

    suspend fun submitPrompt(sessionId: String, text: String): PromptSubmitResult =
        rpc.rpc(
            "prompt.submit",
            buildJsonObject {
                put("session_id", sessionId)
                put("text", text)
            },
            PromptSubmitResult.serializer(),
        )

    /** Slash commands (`/model`, `/reasoning`, `/cron`, `/skills`, …). */
    suspend fun slash(sessionId: String, command: String): SlashExecResult =
        rpc.rpc(
            "slash.exec",
            buildJsonObject {
                put("session_id", sessionId)
                put("command", command)
            },
            SlashExecResult.serializer(),
            timeoutMs = 60_000,
        )

    suspend fun commands(): List<CommandEntry> =
        rpc.rpc("commands.catalog", JsonObject(emptyMap()), CommandsCatalogResult.serializer())
            .pairs
            .mapNotNull { p ->
                if (p.size < 2) null
                else CommandEntry(p[0], p[1])
            }

    // ------------------------------------------------------------ attachments ---

    /**
     * Upload an attachment.
     *
     * Mirrors the `hermes-lan-chat` server, which forwards to the gateway RPC
     * `image.attach_bytes` / `pdf.attach` / `file.attach`. Bytes travel base64
     * encoded, so callers must respect the gateway's size cap.
     */
    suspend fun attach(
        sessionId: String,
        name: String,
        kind: AttachmentKind,
        mime: String,
        base64: String,
    ): JsonObject {
        val method = when (kind) {
            AttachmentKind.IMAGE -> "image.attach_bytes"
            AttachmentKind.PDF -> "pdf.attach"
            AttachmentKind.FILE -> "file.attach"
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("filename", name)
            if (kind == AttachmentKind.FILE) {
                put("name", name)
                put("data_url", "data:$mime;base64,$base64")
            } else {
                put("content_base64", base64)
            }
        }
        return rpc.rpcRaw(method, params, timeoutMs = 120_000)
    }

    /** Answer an `approval.request` / `clarify.request` the agent is blocked on. */
    suspend fun serverResponse(requestId: String, choice: String): JsonObject =
        rpc.rpcRaw(
            "approval.received",
            buildJsonObject {
                put("request_id", requestId)
                put("response", choice)
            },
        )

    suspend fun sessionEvents(): SessionEventsResult =
        rpc.rpc("session.events.since", JsonObject(emptyMap()), SessionEventsResult.serializer())

    suspend fun capabilities(): CapabilitiesResult =
        rpc.rpc("client.capabilities", JsonObject(emptyMap()), CapabilitiesResult.serializer())

    // -------------------------------------------------------------- models ---

    suspend fun modelOptions(): List<ModelProvider> =
        rpc.rpc("model.options", JsonObject(emptyMap()), ModelOptionsResult.serializer()).providers

    suspend fun modelInfo(): ModelInfo = rest.getJson("/api/model/info")
        .let { HermesHttp.json.decodeFromJsonElement(ModelInfo.serializer(), it) }

    /**
     * Switch model for this session.
     *
     * The gateway exposes model control as a slash command rather than a
     * dedicated RPC method: `/model [model] [--provider name]`.
     */
    suspend fun switchModel(sessionId: String, model: String, provider: String?): SlashExecResult {
        val cmd = buildString {
            append("/model ").append(model)
            if (!provider.isNullOrBlank()) append(" --provider ").append(provider)
        }
        return slash(sessionId, cmd)
    }

    /** `/reasoning <level>` — none | minimal | low | medium | high | xhigh | max | ultra. */
    suspend fun setReasoning(sessionId: String, level: ReasoningLevel, global: Boolean = false): SlashExecResult {
        val cmd = if (global) "/reasoning ${level.wire} --global" else "/reasoning ${level.wire}"
        return slash(sessionId, cmd)
    }

    suspend fun reasoningState(sessionId: String): SlashExecResult = slash(sessionId, "/reasoning show")

    // --------------------------------------------------------- skills/tools ---

    suspend fun toolsets(): List<Toolset> =
        rpc.rpc("toolsets.list", JsonObject(emptyMap()), ToolsListResult.serializer()).toolsets

    suspend fun toolsDetailed(): List<Toolset> =
        rpc.rpc("tools.list", JsonObject(emptyMap()), ToolsListResult.serializer()).toolsets

    suspend fun skills(): List<Skill> =
        HermesHttp.json.decodeFromString(
            ListSerializer(Skill.serializer()),
            rest.getText("/api/skills"),
        )

    suspend fun setSkillEnabled(name: String, enabled: Boolean) {
        rest.postJsonObject(
            "/api/skills/toggle",
            buildJsonObject {
                put("name", name)
                put("enabled", enabled)
            },
        )
    }

    suspend fun skillContent(name: String): String =
        rest.getText("/api/skills/content", mapOf("name" to name))

    // ------------------------------------------------------------ artifacts ---

    suspend fun listFiles(path: String): FileListing =
        HermesHttp.json.decodeFromString(
            FileListing.serializer(),
            rest.getText("/api/files", mapOf("path" to path)),
        )

    // ----------------------------------------------------------------- cron ---

    suspend fun cronJobs(): List<CronJob> =
        HermesHttp.json.decodeFromString(
            ListSerializer(CronJob.serializer()),
            rest.getText("/api/cron/jobs"),
        )

    // ---------------------------------------------------------------- groups ---

    suspend fun listGroups(limit: Int = 50, offset: Int = 0): GroupListResult =
        rpc.rpc(
            "groups.list",
            buildJsonObject {
                put("limit", limit)
                put("offset", offset)
            },
            GroupListResult.serializer(),
        )

    /**
     * Creates a group from the chosen profiles.
     *
     * The gateway requires a caller-supplied `room_id`, a name, and 2–6 members
     * each carrying `member_id` / `profile` / `handle`.
     */
    suspend fun createGroup(
        roomId: String,
        name: String,
        profiles: List<String>,
    ): GroupCreated {
        require(profiles.size in GroupLimits.MIN_MEMBERS..GroupLimits.MAX_MEMBERS) {
            "群组需要 ${GroupLimits.MIN_MEMBERS}-${GroupLimits.MAX_MEMBERS} 个成员"
        }
        val members = buildJsonArray {
            profiles.forEach { p ->
                add(
                    buildJsonObject {
                        put("member_id", p)
                        put("profile", p)
                        put("handle", p)
                    }
                )
            }
        }
        return rpc.rpc(
            "groups.create",
            buildJsonObject {
                put("room_id", roomId)
                put("name", name)
                put("members", members)
            },
            GroupCreated.serializer(),
        )
    }

    /**
     * Renames a room.
     *
     * `event_id` is mandatory: rename is an event-emitting command, so the
     * server rejects the call with `event_id must be a string` without it.
     */
    suspend fun renameGroup(roomId: String, name: String): JsonObject =
        rpc.rpcRaw(
            "groups.rename",
            buildJsonObject {
                put("room_id", roomId)
                put("name", name)
                put("event_id", "rename-${System.currentTimeMillis().toString(36)}")
            },
        )

    // ------------------------------------------------------------- rooms ---

    /**
     * What the gateway supports for group chat.
     *
     * Probed live: protocol_version 2, `driver: true`, 18 methods and
     * `max_log_limit: 500`. Worth calling before showing a room UI — an older
     * backend answers `absent`, and the feature has to degrade, not crash.
     */
    suspend fun groupCapabilities(): GroupCapabilities =
        rpc.rpc("groups.capabilities", JsonObject(emptyMap()), GroupCapabilities.serializer())

    /** Room record plus who is driving it. */
    suspend fun groupState(roomId: String): GroupState =
        rpc.rpc("groups.state", buildJsonObject { put("room_id", roomId) }, GroupState.serializer())

    /**
     * The room log, oldest first.
     *
     * **No incremental fetch exists.** Probed every plausible cursor name
     * (`after`, `since`, `after_seq`, `from`, `cursor`, `offset`, `start`) and
     * the gateway rejects every one with `Extra inputs are not permitted` — it
     * accepts only `room_id` and `limit`. Callers therefore refetch the page and
     * diff locally by `seq`; that is also how the Desktop catches up after a
     * reconnect.
     *
     * `max_log_limit` is 500, so a room longer than that is truncated to its
     * first page. A larger `limit` is refused rather than clamped.
     */
    suspend fun groupLog(roomId: String, limit: Int = 500): RoomLog = rpc.rpc(
        "groups.log",
        buildJsonObject {
            put("room_id", roomId)
            put("limit", limit.coerceIn(1, 500))
        },
        RoomLog.serializer(),
    )

    /**
     * Mints the `event_id` that keys one logical post.
     *
     * One post keeps one id across every attempt of its life: the gateway
     * advertises `idempotent_send` and collapses a re-send of a known id onto
     * the original delivery, so a retry that reuses the id can never
     * double-post while a retry that mints fresh always can.
     */
    fun newSendEventId(text: String): String =
        "send-${System.currentTimeMillis().toString(36)}-${text.hashCode()}"

    /**
     * Posts into a room.
     *
     * Verified shape — `event_id` is the idempotency key and `payload` needs
     * **both** `text` and a non-empty `thread_id`; an empty string is rejected
     * with 5112. `thread_id = "main"` is the room's primary thread.
     *
     * [eventId] pins the idempotency key to the caller: null mints a fresh
     * post via [newSendEventId]; the SAME id retries an attempt whose outcome
     * is unknown (timeout, dropped socket) — the gateway either returns the
     * original confirmation or takes the post now, never both.
     */
    suspend fun sendToGroup(
        roomId: String,
        profile: String,
        text: String,
        threadId: String = MAIN_THREAD_ID,
        eventId: String? = null,
    ): GroupSendResult = rpc.rpc(
        "groups.send",
        buildJsonObject {
            put("event_id", eventId ?: newSendEventId(text))
            put("room_id", roomId)
            put("profile", profile)
            put(
                "payload",
                buildJsonObject {
                    put("text", text)
                    put("thread_id", threadId)
                },
            )
        },
        GroupSendResult.serializer(),
    )

    /** Holds the room: its members stop taking new turns until resumed. */
    suspend fun stopGroup(roomId: String): JsonObject =
        rpc.rpcRaw("groups.stop", buildJsonObject { put("room_id", roomId) })

    /** Clears a pending room approval. Takes `room_id` and nothing else. */
    suspend fun approveGroup(roomId: String): JsonObject =
        rpc.rpcRaw("groups.approve", buildJsonObject { put("room_id", roomId) })

    suspend fun messagingPlatforms(): MessagingPlatformsResult =
        HermesHttp.json.decodeFromString(
            MessagingPlatformsResult.serializer(),
            rest.getText("/api/messaging/platforms"),
        )

    suspend fun pairing(): PairingResult =
        HermesHttp.json.decodeFromString(
            PairingResult.serializer(),
            rest.getText("/api/pairing"),
        )

    // --------------------------------------------------------------- extras ---

    suspend fun agents(): List<AgentProcess> =
        rpc.rpc("agents.list", JsonObject(emptyMap()), AgentsListResult.serializer()).processes

    suspend fun profiles(): List<Profile> =
        rpc.rpc("profiles.list", JsonObject(emptyMap()), ProfilesResult.serializer()).profiles

    suspend fun sessionDetail(storedSessionId: String): SessionDetail =
        HermesHttp.json.decodeFromString(
            SessionDetail.serializer(),
            rest.getText("/api/sessions/$storedSessionId"),
        )
}

data class CommandEntry(val name: String, val description: String)

enum class AttachmentKind { IMAGE, PDF, FILE }

sealed interface ConnectionTestResult {
    data class Success(
        val httpMs: Long,
        val version: String,
        val username: String,
        val displayName: String?,
        val wsPath: String,
        val ticketHint: String,
        val pingOk: Boolean,
    ) : ConnectionTestResult

    data class Failed(val message: String) : ConnectionTestResult
}
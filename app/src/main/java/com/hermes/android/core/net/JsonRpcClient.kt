package com.hermes.android.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.pow

enum class RpcConnectionState { Disconnected, Connecting, Connected, Reconnecting, Failed }

sealed class RpcFailure(message: String) : Exception(message) {
    data class Rpc(val code: Int, override val message: String) : RpcFailure(message)

    data class Timeout(val method: String) :
        RpcFailure("请求超时: $method")

    data class NotConnected(val method: String) :
        RpcFailure("尚未连接到后端: $method")
}

/**
 * Identity token for one handshake attempt.
 *
 * Comparing [JsonRpcClient.generation] alone was not enough: two concurrent
 * handshakes shared it, so a superseded socket's late `onFailure` cleared the
 * replacement. Each attempt therefore gets its own token, and callbacks that
 * carry a token this client no longer owns are ignored.
 *
 * [socket] is a holder because OkHttp may call `onOpen` on its reader thread
 * *before* [JsonRpcClient.connect] has the WebSocket back from `newWebSocket`.
 */
private class Handshake(val id: Long) {
    val socket = AtomicReference<WebSocket?>(null)
}

/** One server-push frame: `{"method":"event","params":{...}}`. */
data class RpcEvent(
    val type: String,
    val sessionId: String,
    val payload: JsonObject,
    val raw: JsonObject,
    /** Server sequence number, used to drop duplicates and detect gaps. */
    val seq: Long = 0,
)

/**
 * JSON-RPC 2.0 client over the gateway's `/api/ws` WebSocket.
 *
 * Handshake (probed live against Agent 0.21.5):
 * ```
 * POST /auth/password-login  -> session cookies
 * POST /api/auth/ws-ticket   -> { ticket, ttl_seconds: 30 }
 * GET  ws://host/api/ws?ticket=...
 * ```
 *
 * The server greets with a `gateway.ready` event carrying the skin, the brand
 * and a `replay_epoch`; `session.events.since` can then replay anything missed
 * while disconnected.
 */
class JsonRpcClient(
    private val client: OkHttpClient,
    private val auth: HermesAuth,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val idSeq = AtomicLong(0)
    private val mutex = Mutex()

    /**
     * Events are queued by the socket callback and drained by a single
     * consumer, so subscribers observe them in wire order.
     *
     * Publishing each frame with its own `scope.launch` did not preserve order —
     * a delta could be handled before the `start` that opened its segment.
     */
    private val eventQueue = Channel<RpcEvent>(capacity = 1024)
    private val consumerJob: Job

    /**
     * Bumped on every connect/disconnect. Callbacks compare their generation so
     * a late frame from a replaced socket cannot clobber current state.
     */
    @Volatile private var generation = 0L

    /**
     * The handshake this client currently owns, or null.
     *
     * Identity token, not the socket itself: two sockets can briefly coexist
     * (a new one opening while the old one is being cancelled) and the
     * callbacks have to tell which is which.
     */
    @Volatile private var handshake: Handshake? = null

    @Volatile private var reconnectJob: Job? = null

    /**
     * Connection-state mutations, serialised through one consumer.
     *
     * OkHttp delivers `onOpen`/`onClosed`/`onFailure` on its own threads, and
     * checking the ownership token then mutating shared state left a window:
     * a superseded close could pass the check, stall inside `failAllPending`,
     * and resume after a disconnect+reconnect to clear the *new* connection's
     * socket and mark the state Reconnecting with no reconnect job alive — a
     * permanently wedged client.
     *
     * Callbacks now only enqueue. One consumer drains the queue and runs each
     * command with the ownership check and the state change in the same
     * `mutex.withLock`, so check-then-act cannot be interleaved. A queue (rather
     * than `scope.launch` per callback) is what keeps the original order.
     */
    private val connCommands = Channel<ConnCommand>(Channel.UNLIMITED)
    private val connJob: Job

    private sealed interface ConnCommand {
        class Opened(val mine: Handshake, val ws: WebSocket) : ConnCommand
        class Closed(
            val mine: Handshake,
            val myGen: Long,
            val code: Int,
            val error: Throwable?,
        ) : ConnCommand
    }

    /**
     * Identity of the in-flight connect() calls.
     *
     * [generation] is not enough across the ws-ticket round-trip: two concurrent
     * connects shared it, so an older ticket failure could downgrade a newer,
     * already-connected client to Failed. Each attempt claims an operation
     * number and only the current one may change state or commit a config.
     */
    private val opSeq = AtomicLong(0)

    @Volatile private var activeOp = 0L

    private val _events = MutableSharedFlow<RpcEvent>(
        replay = 256,
        extraBufferCapacity = 1024,
    )
    val events: SharedFlow<RpcEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(RpcConnectionState.Disconnected)
    val state: StateFlow<RpcConnectionState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var config: HermesConfig? = null
    @Volatile private var reconnectAttempt = 0
    @Volatile private var closedByUser = false

    /** `gateway.ready` payload skin/branding, kept for the chat header. */
    @Volatile var readyPayload: JsonObject? = null
        private set

    @Volatile var replayEpoch: String? = null
        private set

    /**
     * Re-runs the username/password login and refreshes the session cookie.
     *
     * Wired by `HermesRepository`. Needed because the cookie dies on its own:
     * once it does, `ws-ticket` answers 401 forever, so a reconnect loop that
     * only fetches tickets would spin at its backoff cap and never recover.
     * Returns true when a fresh session was obtained.
     */
    @Volatile var reauthenticate: (suspend () -> Boolean)? = null

    val isConnected: Boolean get() = socket != null && _state.value == RpcConnectionState.Connected

    /** Same predicate, for use inside an existing `mutex.withLock` block. */
    private fun isConnectedLocked(): Boolean =
        socket != null && _state.value == RpcConnectionState.Connected

    init {
        // Single consumer: preserves receive order for every subscriber.
        consumerJob = scope.launch {
            for (ev in eventQueue) _events.emit(ev)
        }
        // Single consumer for connection state: ownership check and mutation in
        // one critical section, in the order OkHttp delivered the callbacks.
        connJob = scope.launch {
            for (cmd in connCommands) {
                // Anything that needs the lock again (reconnect scheduling,
                // re-login) is returned and run *after* this block releases it.
                var deferred: (suspend () -> Unit)? = null
                mutex.withLock { deferred = applyLocked(cmd) }
                deferred?.invoke()
            }
        }
    }

    /**
     * Applies one connection callback under the lock.
     *
     * Returns work that must happen with the lock released — the reconnect
     * loop and the re-login both take [mutex] themselves, and it is not
     * reentrant.
     */
    private fun applyLocked(cmd: ConnCommand): (suspend () -> Unit)? {
        when (cmd) {
            is ConnCommand.Opened -> {
                if (cmd.mine !== handshake) {
                    runCatching { cmd.ws.close(1000, "superseded") }
                    return null
                }
                reconnectAttempt = 0
                socket = cmd.ws
                cmd.mine.socket.set(cmd.ws)
                handshake = null
                _state.value = RpcConnectionState.Connected
                return null
            }

            is ConnCommand.Closed -> {
                val mine = cmd.mine
                // Late callback from a superseded generation, or from a handshake
                // we no longer own. Both are checked in the same section that
                // clears state below, so nothing can slip in between.
                if (cmd.myGen != generation) return null
                if (socket !== mine.socket.get() && handshake !== mine) return null

                socket = null
                if (handshake === mine) handshake = null
                failAllPending(cmd.error ?: RpcFailure.NotConnected("*"))

                if (closedByUser) {
                    _state.value = RpcConnectionState.Disconnected
                    return null
                }
                // 4401/4403 mean the cookie the socket was opened with is already
                // dead; retrying with it can never succeed.
                val authIssue = cmd.code == 4401 || cmd.code == 4403
                _state.value =
                    if (authIssue) RpcConnectionState.Failed else RpcConnectionState.Reconnecting
                val cfg = config
                val reauth = reauthenticate
                return {
                    if (authIssue && reauth != null) reauth()
                    // If no reconnect job is already running, start one. Deciding
                    // this *inside* the critical section is what stops a close
                    // that lands during the previous loop's success path from
                    // being dropped — that loop saw the connection die only
                    // after it had already decided it was finished.
                    if (cfg != null && reconnectJob?.isActive != true) {
                        scheduleReconnect(cfg, cmd.myGen)
                    }
                }
            }
        }
    }

    /**
     * Logs in, then opens the WebSocket.
     *
     * Throws when the connection could not be established, so callers cannot
     * mistake a failed attempt for a successful one.
     */
    suspend fun connect(cfg: HermesConfig, force: Boolean = false) {
        // Already connected to this exact backend: nothing to do. Reconnecting
        // anyway tore down a live socket that the chat screen was streaming on
        // every time a second screen asked to connect.
        if (!force && isConnected && config == cfg) return

        // Take the ticket OUTSIDE the lock: it is a network round-trip, and
        // holding the mutex across it made save/disconnect block for as long
        // as the request took (measured 1505 ms for a slow response).
        if (force || isConnected) {
            mutex.withLock { teardownLocked() }
        }

        // Claim an operation number *before* the round-trip. Concurrent
        // connects used to share `generation` here, so an older ticket failure
        // could downgrade a newer, already-connected client to Failed and leave
        // it stuck: the socket was live but nothing would retry it.
        // Claim and publish in one critical section — incrementing outside it
        // let a slower older connect steal `activeOp` back from a newer one and
        // silently discard its successful result.
        val myGen: Long
        val op: Long
        mutex.withLock {
            op = opSeq.incrementAndGet()
            activeOp = op
            myGen = generation
        }

        val ticket = ticketWithRecovery(cfg, op)
        if (ticket == null) {
            // Either superseded by a newer connect, or the user disconnected
            // while we waited. Neither is a failure: a disconnect bumps
            // `generation`, so checking only `activeOp` would let a late
            // failure overwrite Disconnected with Failed and report "连接失败"
            // for something the user asked for. The check and the state write share one
            // critical section: splitting them left the last unsynchronised
            // state transition in this file, where a disconnect or a new connect
            // landing in between still got overwritten with Failed.
            val superseded = mutex.withLock {
                if (activeOp == op && myGen == generation) {
                    _state.value = RpcConnectionState.Failed
                    false
                } else {
                    true
                }
            }
            if (superseded) return
            throw RpcFailure.Rpc(-1, "无法获取 ws-ticket：后端未就绪或凭据已失效")
        }

        mutex.withLock {
            if (activeOp != op || myGen != generation) return@withLock
            closedByUser = false
            config = cfg
            if (socket != null) return@withLock
            startSocketLocked(cfg, ticket, isReconnect = false)
        }
    }

    /**
     * Fetches a ticket, logging in once if the cookie turns out to be dead.
     *
     * A single 401 is tolerated; if it persists the cookie is gone and no
     * amount of retrying will help, so [reauthenticate] runs and the ticket is
     * fetched again. Returns null when the attempt was superseded or recovery
     * failed — the caller decides which by re-checking [activeOp].
     */
    private suspend fun ticketWithRecovery(cfg: HermesConfig, op: Long): String? {
        var reauthenticated = false
        while (true) {
            auth.wsTicket(cfg).getOrNull()?.let { return it }
            if (activeOp != op) return null
            val reauth = reauthenticate ?: return null
            if (reauthenticated || !reauth()) return null
            reauthenticated = true
        }
    }

    suspend fun disconnect() {
        mutex.withLock {
            closedByUser = true
            teardownLocked()
            _state.value = RpcConnectionState.Disconnected
        }
    }

    /** Cancels the socket, any handshake in flight and the reconnect timer. */
    private fun teardownLocked() {
        reconnectJob?.cancel()
        reconnectJob = null
        generation++
        handshake?.let { h -> runCatching { h.socket.get()?.cancel() } }
        handshake = null
        socket?.close(1000, "client closing")
        socket = null
        failAllPending(RpcFailure.NotConnected("*"))
    }

    /**
     * Starts the WebSocket handshake with a ticket already fetched.
     *
     * Returns as soon as the handshake is *started*. It must NOT wait for the
     * handshake to complete: this runs inside `mutex.withLock`, and suspending
     * here would hold the mutex for seconds and serialise every
     * connect/disconnect behind it — `disconnect()` and the reconnect loop
     * would then appear frozen to the UI.
     *
     * Never blocks: it only issues `newWebSocket`, so the mutex is held for
     * microseconds. Callers that need the outcome poll [isConnected] via
     * [awaitHandshake] outside the lock.
     */
    private fun startSocketLocked(cfg: HermesConfig, ticket: String, isReconnect: Boolean) {
        _state.value = if (isReconnect) RpcConnectionState.Reconnecting else RpcConnectionState.Connecting
        val myGen = generation
        // Registered *before* newWebSocket: the listener may fire before it returns.
        val mine = Handshake(idSeq.incrementAndGet())
        handshake = mine
        // OkHttp needs an http/https URL here; it does the ws upgrade itself.
        val url = cfg.socketBaseUrl + "/api/ws?ticket=" + ticket
        val req = Request.Builder().url(url).build()
        val ws = client.newWebSocket(req, makeListener(myGen, mine))
        mine.socket.set(ws)
        // A teardown may have landed while the socket was being created.
        if (myGen != generation) {
            runCatching { ws.cancel() }
            if (handshake === mine) handshake = null
        }
    }

    /** Waits for the current handshake, outside any lock. */
    private suspend fun awaitHandshake(myGen: Long, timeoutMs: Long = 10_000): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (closedByUser || myGen != generation) return false
            if (socket != null && _state.value == RpcConnectionState.Connected) return true
            delay(100)
            waited += 100
        }
        return false
    }

    private fun makeListener(myGen: Long, mine: Handshake) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connCommands.trySend(ConnCommand.Opened(mine, webSocket))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (myGen != generation) return
            // Cheap pre-filter so a dead socket's frames never reach the parser.
            // The authoritative ownership check happens in applyLocked; this
            // path only appends to the event channel and refreshes two
            // display-only caches, so it is not part of the state machine.
            if (socket !== webSocket && handshake?.socket?.get() !== webSocket) return
            handleFrame(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connCommands.trySend(ConnCommand.Closed(mine, myGen, code, null))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connCommands.trySend(ConnCommand.Closed(mine, myGen, response?.code ?: -1, t))
        }
    }


    /**
     * Runs the reconnect loop until it succeeds or is cancelled.
     *
     * A previous version scheduled one attempt per close event. When the ws-ticket
     * fetch failed there was no socket and therefore no further close event, so
     * the loop stopped for good. This keeps retrying with backoff instead, and
     * exits only on success, an explicit disconnect, or a generation change.
     */
    private fun scheduleReconnect(cfg: HermesConfig, myGen: Long) {
        if (closedByUser) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            // Captured so the loop can recognise itself and hand the slot over
            // without clobbering a newer loop that already took over.
            val self = coroutineContext[Job]
            var attempt = reconnectAttempt
            var ticketFailures = 0
            while (!closedByUser && myGen == generation) {
                val backoff = min(30_000.0, 1000.0 * 2.0.pow(attempt)).toLong()
                attempt++
                // Publish the attempt count so the *next* reconnect loop resumes
                // where this one gave up. Without this the field was never
                // written with a non-zero value, every loop restarted at 1s,
                // and a gateway that stayed down was hammered once a second.
                reconnectAttempt = attempt
                delay(backoff)
                if (closedByUser || myGen != generation) return@launch
                if (isConnected && retireIfHealthy(myGen, self)) return@launch

                // Ticket fetch outside the lock — it is a network round-trip and
                // must never block save/disconnect.
                val ticket = auth.wsTicket(cfg).getOrNull()
                if (closedByUser || myGen != generation) return@launch
                if (ticket == null) {
                    // A dead session cookie makes every ticket fetch 401, so this
                    // used to spin at the 30s backoff cap forever. Re-login after
                    // a few consecutive failures rather than giving up on the
                    // connection for the rest of the process lifetime.
                    if (++ticketFailures >= TICKET_FAILURES_BEFORE_REAUTH) {
                        ticketFailures = 0
                        reauthenticate?.invoke()
                    }
                    continue
                }
                ticketFailures = 0

                val started = mutex.withLock {
                    if (closedByUser || myGen != generation || isConnected) {
                        false
                    } else {
                        startSocketLocked(cfg, ticket, isReconnect = true)
                        true
                    }
                }
                if (!started) continue

                if (awaitHandshake(myGen)) {
                    // Two orderings have to both recover, and neither may rely
                    // on the other's timing:
                    //  - close lands before this check -> the connection is
                    //    down again, so *this* loop keeps retrying
                    //  - close lands after it -> the slot is already released
                    //    here, so the close's deferred work schedules a new loop
                    if (stillNeedsRetry(myGen, self)) {
                        // A success happened, so the accumulated backoff is no
                        // longer earned. Only the field was being reset before;
                        // the loop's local counter kept climbing and repeated
                        // reconnects crept up to the 30s cap.
                        attempt = 0
                        reconnectAttempt = 0
                        continue
                    }
                    return@launch
                }
                // Ticket or handshake failed: loop again with a longer backoff.
            }
        }
    }

    /**
 * Decides whether the reconnect loop should keep going, releasing the slot
 * when it retires.
 *
 * Every exit from the loop has to go through here. One of them used to `return`
 * on its own, and a close landing in the gap saw `reconnectJob.isActive` and
 * skipped scheduling — then the loop retired on the success path and nothing
 * was left to retry, stranding the state at Reconnecting.
 *
 * The check and the slot handover share one critical section so a disconnect or
 * a newer loop cannot slip between them. Returns true when this loop should
 * keep retrying.
 */
private suspend fun stillNeedsRetry(myGen: Long, self: Job?): Boolean =
    mutex.withLock {
        val alive = !closedByUser && myGen == generation && !isConnectedLocked()
        // Release only when retiring: while this loop still owns the slot, a
        // close can schedule a replacement instead.
        if (!alive && reconnectJob === self) reconnectJob = null
        alive
    }

/**
 * The other exit: the connection came up while this loop was backing off.
 *
 * Same reasoning as [stillNeedsRetry] — a close that lands right after this
 * check has to find the slot free so it can schedule a replacement.
 */
private suspend fun retireIfHealthy(myGen: Long, self: Job?): Boolean =
    mutex.withLock {
        val healthy = !closedByUser && myGen == generation && isConnectedLocked()
        if (healthy && reconnectJob === self) {
            reconnectJob = null
            reconnectAttempt = 0
        }
        healthy
    }

private fun handleFrame(text: String) {
        val obj = runCatching { HermesHttp.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNullSafe()
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNullSafe()

        if (id != null) {
            pending.remove(id)?.takeIf { !it.isCompleted }?.complete(obj)
            return
        }
        if (method != "event") return

        val params = obj["params"] as? JsonObject ?: return
        val type = (params["type"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty()
        val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val seq = (params["seq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

        if (type == "gateway.ready") {
            readyPayload = payload
            replayEpoch = (params["replayEpoch"] as? JsonPrimitive)?.contentOrNullSafe()
                ?: (payload["replay_epoch"] as? JsonPrimitive)?.contentOrNullSafe()
        }

        val ev = RpcEvent(
            type = type,
            sessionId = (params["session_id"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty(),
            payload = payload,
            raw = params,
            seq = seq,
        )
        // Bounded, non-suspending enqueue: under extreme pressure the oldest
        // frame is evicted rather than the newest (see offerLatest).
        offerLatest(eventQueue, ev)
    }

    private fun failAllPending(t: Throwable) {
        pending.keys.toList().forEach { k -> pending.remove(k)?.completeExceptionally(t) }
    }

    /** Issue one JSON-RPC call and await its reply. */
    suspend fun <T> rpc(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        deserializer: KSerializer<T>,
        timeoutMs: Long = 30_000,
    ): T = withContext(Dispatchers.IO) {
        val ws = socket ?: throw RpcFailure.NotConnected(method)
        val id = "r${idSeq.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        if (!ws.send(payload.toString())) {
            pending.remove(id)
            throw RpcFailure.Rpc(-1, "WebSocket 发送失败: $method")
        }

        val reply = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            pending.remove(id)
            throw e
        }
        pending.remove(id)
        if (reply == null) throw RpcFailure.Timeout(method)

        reply["error"]?.let { err ->
            val obj = err as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
            val msg = (obj?.get("message") as? JsonPrimitive)?.contentOrNullSafe().orEmpty()
            throw RpcFailure.Rpc(code, msg)
        }

        val result = reply["result"] as? JsonObject ?: JsonObject(emptyMap())
        HermesHttp.json.decodeFromJsonElement(deserializer, result)
    }

    /** Raw call, returns the `result` object untouched. */
    suspend fun rpcRaw(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 30_000,
    ): JsonObject = rpc(method, params, JsonObject.serializer(), timeoutMs)

    /** Fire-and-forget, for notifications the server does not answer. */
    fun notify(method: String, params: JsonObject = JsonObject(emptyMap())) {
        val ws = socket ?: return
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        ws.send(payload.toString())
    }

    companion object {
        /** Consecutive failed ticket fetches before assuming the cookie died. */
        private const val TICKET_FAILURES_BEFORE_REAUTH = 3

        private val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        )
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

/**
 * Enqueues one event, evicting the oldest queued frame if the queue is full.
 *
 * The socket callback thread must not suspend, so a full queue means a drop —
 * and dropping the *newest* frame is the worst trade: a completion lost in
 * favour of an ancient start leaves the UI stuck on "streaming" with no
 * follow-up frame to repair it. Evict from the front instead, so the freshest
 * state always wins; a dropped `start` is self-healing because the next delta
 * re-opens the segment.
 */
internal fun <T> offerLatest(queue: Channel<T>, ev: T): Boolean {
    if (queue.trySend(ev).isSuccess) return true
    queue.tryReceive()
    return queue.trySend(ev).isSuccess
}

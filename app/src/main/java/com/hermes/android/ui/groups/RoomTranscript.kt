package com.hermes.android.ui.groups

import com.hermes.android.core.net.RpcFailure

/**
 * Delivery state of one local echo.
 *
 * The transcript's rows otherwise come straight from `groups.log`, which is
 * authoritative by definition — only a locally composed post can be anywhere
 * but [Settled].
 */
enum class RoomSendState {
    Settled,

    /** A `groups.send` for this post is on the wire right now. */
    Sending,

    /**
     * The attempt failed *ambiguously* — timeout or a socket that died
     * mid-flight — so the post may already be in the room's log. The only safe
     * follow-up is a retry that reuses the original `event_id`.
     */
    Unknown,
}

/** One rendered row in a room transcript. */
data class RoomMessage(
    val key: String,
    val seq: Long,
    val kind: String,
    val actorId: String?,
    val text: String,
    val mine: Boolean,
    val system: Boolean,
    /** Local-echo state; rows decoded from the log are always [RoomSendState.Settled]. */
    val sendState: RoomSendState = RoomSendState.Settled,
    /**
     * The `event_id` this echo was posted under. The gateway collapses a
     * re-send onto the original delivery by this key, so an unknown-outcome
     * retry must carry THIS id — minting a fresh one is what double-posts.
     */
    val eventId: String? = null,
)

/**
 * Confirmed events stay in sequence order; pending echoes remain at the tail.
 *
 * Pairing is by KEY ONLY, on purpose. An unknown-outcome echo is never settled
 * by matching its text against incoming rows: the log does not echo the client
 * `event_id`, so text is the only join key available and it is not sound — a
 * *later* post with identical wording would consume the echo and silently
 * discard its retry entry. The echo settles through its own id-reusing retry
 * (or stays honestly marked "unknown"), never through a guess.
 */
internal fun mergeRoomMessages(
    existing: List<RoomMessage>,
    incoming: List<RoomMessage>,
): List<RoomMessage> = (existing + incoming)
    .distinctBy { it.key }
    .sortedBy { it.seq }

/**
 * Whether a failed `groups.send` leaves delivery undecided.
 *
 * [RpcFailure.Rpc] covers both shapes of a definitive "no": the gateway
 * answered with an error, or `WebSocket.send` refused the frame outright — in
 * either case nothing reached the room. [RpcFailure.NotConnected] means no
 * socket existed when the call was made, so the request never left the phone.
 * [RpcFailure.ConnectionClosed] means a submitted request lost its reply.
 * Everything else — a [RpcFailure.Timeout] with the request possibly slow in
 * flight, the raw OkHttp throwable a dropped socket fails pending calls with,
 * a cancelled coroutine — may already be in the log, and only a retry that
 * reuses the same `event_id` is safe.
 */
internal fun isUnknownOutcome(t: Throwable): Boolean =
    t !is RpcFailure || t is RpcFailure.Timeout || t is RpcFailure.ConnectionClosed

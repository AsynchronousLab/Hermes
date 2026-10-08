package com.hermes.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Group ("room") contracts.
 *
 * Shape captured live against the gateway:
 * ```
 * groups.create { room_id, name, members: [ { member_id, profile, handle } ] }
 *   -> { room: { room_id, name, members[], revision, created_at, … } }
 * groups.list   { limit, offset }  -> { rooms: [ …, latest_seq ], next_offset }
 * groups.rename { room_id, name }
 * ```
 *
 * The server validates members one field at a time and accepts **2 to 6**
 * entries, each requiring `handle`, `member_id` and `profile`.
 */
object GroupLimits {
    const val MIN_MEMBERS = 2
    const val MAX_MEMBERS = 6
}

/**
 * The room's primary thread, used as `payload.thread_id` for ordinary posts.
 *
 * Probed live: `groups.send` rejects an empty `thread_id` with 5112, so there
 * is no "no thread" sentinel to omit the field with.
 */
const val MAIN_THREAD_ID = "main"

@Serializable
data class GroupMember(
    @SerialName("member_id") val memberId: String = "",
    val profile: String = "",
    val handle: String = "",
    val target: JsonObject? = null,
) {
    /** Display name — the profile's own title when known. */
    fun displayName(profileTitle: (String) -> String?): String =
        profileTitle(profile)?.takeIf { it.isNotBlank() } ?: handle.ifBlank { memberId }
}

@Serializable
data class Group(
    @SerialName("room_id") val roomId: String = "",
    val name: String = "",
    val members: List<GroupMember> = emptyList(),
    val revision: Int = 0,
    @SerialName("authority_epoch") val authorityEpoch: Int? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("latest_seq") val latestSeq: Long? = null,
    val idempotent: Boolean? = null,
) {
    val memberCount: Int get() = members.size
}

@Serializable
data class GroupListResult(
    val rooms: List<Group> = emptyList(),
    @SerialName("next_offset") val nextOffset: String? = null,
)

@Serializable
data class GroupCreated(val room: Group = Group())

/**
 * `groups.send` response.
 *
 * Captured live — the shape is `{event, client_event_id, accepted,
 * driver_started}`, not `{ok, seq, detail}` as an earlier draft guessed. That
 * guess decoded to an empty object on every send without raising anything.
 *
 * [event] is the authoritative server-side entry: it already carries the `seq`
 * and the `event_id` the *log* will later show, which is the only reliable way
 * to pair an optimistic echo with its confirmation.
 */
@Serializable
data class GroupSendResult(
    val event: RoomEvent? = null,
    @SerialName("client_event_id") val clientEventId: String? = null,
    val accepted: Boolean? = null,
    @SerialName("driver_started") val driverStarted: Boolean? = null,
)

/**
 * `groups.capabilities` — the gateway's own statement of what it supports.
 *
 * Captured live: `protocol_version: 2`, `driver: true`, and an 18-entry
 * `methods` array (`groups.list/create/state/send/rename/log/disband/…`).
 * Probe the app against this before promising the user a room feature.
 */
@Serializable
data class GroupCapabilities(
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    val driver: Boolean? = null,
    @SerialName("persistent_process") val persistentProcess: Boolean? = null,
    @SerialName("authority_gateway_id") val authorityGatewayId: String? = null,
    val methods: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    @SerialName("max_log_limit") val maxLogLimit: Int? = null,
)

/** `groups.state.driver_status` — who is currently driving the room. */
@Serializable
data class RoomDriverStatus(
    val running: Boolean? = null,
    val working: Boolean? = null,
    val blocked: Boolean? = null,
    @SerialName("pending_actions") val pendingActions: List<JsonElement> = emptyList(),
)

/** `groups.state` — one room plus its driver status. */
@Serializable
data class GroupState(
    val room: Group = Group(),
    @SerialName("driver_status") val driverStatus: RoomDriverStatus? = null,
)

/** Who produced a room event: `room-control`/system, or a member profile. */
@Serializable
data class RoomActor(
    val id: String? = null,
    val kind: String? = null,
)

/**
 * One entry of `groups.log`.
 *
 * `kind` is a typed event name (`room.renamed` observed live; the gateway
 * advertises `typed_events` as a feature). `payload` varies per kind, so it is
 * kept as raw JSON and rendered generically rather than decoded into a guess.
 */
@Serializable
data class RoomEvent(
    @SerialName("room_id") val roomId: String? = null,
    val seq: Long? = null,
    @SerialName("event_id") val eventId: String? = null,
    val kind: String? = null,
    val actor: RoomActor? = null,
    @SerialName("authority_epoch") val authorityEpoch: Long? = null,
    val payload: JsonObject? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    val idempotent: Boolean? = null,
) {
    /** Best-effort body text; falls back to the raw payload when not textual. */
    fun displayText(): String? {
        val p = payload ?: return null
        p["text"]?.let { return it.toString().trim('"').takeIf(String::isNotBlank) }
        p["message"]?.let { return it.toString().trim('"').takeIf(String::isNotBlank) }
        p["name"]?.let { return it.toString().trim('"').takeIf(String::isNotBlank) }
        return null
    }
}

/**
 * `groups.log` page.
 *
 * When nothing new matches `after`, both [cursor] and [latestSeq] come back
 * `null` — never overwrite a locally held cursor with those.
 */
@Serializable
data class RoomLog(
    val events: List<RoomEvent> = emptyList(),
    val cursor: Long? = null,
    @SerialName("latest_seq") val latestSeq: Long? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
)
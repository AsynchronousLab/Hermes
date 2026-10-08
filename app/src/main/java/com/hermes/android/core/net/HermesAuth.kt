package com.hermes.android.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Everything the user can type into Settings. */
data class HermesConfig(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
) {
    /** Normalised `http://host:port` with no trailing slash. */
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')

    /** Non-empty *and* parseable as an http/https URL. */
    val isComplete: Boolean
        get() = hostUrl != null &&
            username.isNotBlank() &&
            password.isNotEmpty()

    val hostUrl: HttpUrl? get() = normalizedBaseUrl.toHttpUrlOrNull()

    /**
     * Base URL for OkHttp's `newWebSocket`.
     *
     * Must stay http/https: OkHttp performs the `Upgrade: websocket` handshake
     * itself and rejects `ws://` / `wss://` with "unexpected scheme: ws".
     * wss is still reachable — just use the https base and OkHttp picks TLS.
     */
    val socketBaseUrl: String get() = normalizedBaseUrl

    /** Human-readable ws:// form, for display only. */
    val wsDisplayUrl: String
        get() = normalizedBaseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
}

/** Cookie-name shape that identifies an access cookie. */
private val SESSION_COOKIE_HINT = Regex("session", RegexOption.IGNORE_CASE)

/** In-memory cookie store: the gateway authenticates with session cookies. */
class SessionCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) put(c)
    }

    @Synchronized
    private fun put(cookie: Cookie) {
        val list = store.getOrPut(cookie.name) { mutableListOf() }
        list.removeAll { it.domain == cookie.domain && it.path == cookie.path }
        if (cookie.expiresAt > System.currentTimeMillis()) list.add(cookie)
    }

    /**
     * Copies every cookie [source] holds into this jar.
     *
     * Used to commit a scratch login only once the attempt that made it is
     * known to still be the current one.
     */
    @Synchronized
    fun absorb(source: SessionCookieJar) {
        source.snapshot().values.flatten().forEach { put(it) }
    }

    @Synchronized
    internal fun snapshot(): Map<String, List<Cookie>> = store.mapValues { it.value.toList() }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.values.flatten().filter { it.matches(url) }

    @Synchronized
    fun clear() = store.clear()

    /**
     * True when we hold a live access cookie — avoids a needless re-login.
     *
     * The gateway's cookie was observed as `hermes_session_at`. Matching that
     * one literal name meant a server-side rename silently degraded every
     * single connect into a full re-login. Match any live cookie whose name
     * looks like a session cookie instead, so `hermes_session`, `session_at`
     * and friends keep working.
     *
     * Failing open here is the safe direction: a false negative only costs one
     * extra login round trip, whereas a false positive would send an
     * unauthenticated request.
     */
    @Synchronized
    fun hasSession(): Boolean {
        val now = System.currentTimeMillis()
        return store.values.flatten().any { c ->
            c.expiresAt > now && SESSION_COOKIE_HINT.containsMatchIn(c.name)
        }
    }
}

object HermesHttp {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun client(jar: SessionCookieJar, debug: Boolean = false): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Overall budget for a single call. Without it a slow-but-steady
            // response can keep a call open far past the read timeout.
            .callTimeout(45, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (debug) {
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().apply {
                            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                        }
                    )
                }
            }
            .build()
}

/** Password login + ws-ticket acquisition. */
class HermesAuth(
    private val jar: SessionCookieJar,
    private val client: OkHttpClient,
) {
    sealed interface LoginResult {
        data object Ok : LoginResult
        data class Failed(val status: Int, val message: String) : LoginResult
    }

    /**
     * Logs in against a throwaway cookie jar and hands it back.
     *
     * The caller decides whether to commit: if an account switch or a newer
     * connect landed while this request was in flight, its cookies must be
     * dropped rather than merged into the live jar.
     */
    suspend fun loginScratch(config: HermesConfig): Pair<LoginResult, SessionCookieJar> =
        withContext(Dispatchers.IO) {
            val scratch = SessionCookieJar()
            val badUrl = LoginResult.Failed(0, "地址无效：${config.baseUrl}")
            val base = config.hostUrl
            if (base == null) {
                return@withContext LoginResult.Failed(
                    0, "后端地址无效，请填写完整地址，例如 http://192.168.1.10:9119",
                ) to scratch
            }
            val url = base.newBuilder()
                .encodedPath(base.encodedPath.trimEnd('/') + "/auth/password-login")
                .build()
            val body = buildJsonObject {
                put("provider", "basic")
                put("username", config.username)
                put("password", config.password)
                put("next", "/")
            }
            val req = runCatching {
                Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(HermesHttp.jsonMedia))
                    .header("Accept", "application/json")
                    .build()
            }.getOrElse {
                return@withContext badUrl to scratch
            }
            // Share the parent's connection pool and thread pool instead of
            // building a whole new OkHttpClient per login.
            val scratchClient = client.newBuilder().cookieJar(scratch).build()
            val result = runCatching {
                scratchClient.newCall(req).execute().use { resp ->
                    val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    if (resp.isSuccessful && scratch.hasSession()) {
                        LoginResult.Ok
                    } else {
                        LoginResult.Failed(
                            resp.code,
                            if (resp.code == 401) "用户名或密码错误"
                            else "登录失败 (HTTP ${resp.code}): ${text.take(200)}",
                        )
                    }
                }
            }.getOrElse { LoginResult.Failed(0, describeNetworkError(it)) }
            result to scratch
        }

    /** Fresh short-lived ticket for the WebSocket handshake. */
    suspend fun wsTicket(config: HermesConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(config.normalizedBaseUrl + "/api/auth/ws-ticket")
                .post("{}".toRequestBody(HermesHttp.jsonMedia))
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("ws-ticket HTTP ${resp.code}")
                val json = HermesHttp.json.parseToJsonElement(resp.body?.string().orEmpty()) as JsonObject
                json["ticket"]?.toString()?.trim('"') ?: error("ws-ticket missing ticket")
            }
        }
    }

    fun logout() = jar.clear()

    companion object {
        fun describeNetworkError(t: Throwable): String {
            val m = t.message.orEmpty()
            return when {
                m.contains("timeout", true) -> "连接超时，请检查地址和网络"
                m.contains("refused", true) -> "连接被拒绝，请确认后端已启动、端口正确"
                m.contains("unable to resolve", true) || m.contains("failed to connect", true) ->
                    "无法连接到该地址，请检查后端地址是否正确"
                else -> m.ifBlank { "网络错误" }.take(300)
            }
        }
    }
}
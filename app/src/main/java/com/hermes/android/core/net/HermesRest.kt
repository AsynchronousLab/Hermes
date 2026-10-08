package com.hermes.android.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * REST access to the endpoints the JSON-RPC surface does not expose
 * (skills, cron jobs, file browser, messaging platforms, pairing …).
 *
 * The base URL travels with each call rather than living in a global, so a
 * settings change takes effect on the next request without re-wiring anything.
 */
class HermesRest(
    private val client: OkHttpClient,
    private val configProvider: () -> HermesConfig,
) {
    private fun url(path: String, query: Map<String, String> = emptyMap()): String {
        val base = configProvider().normalizedBaseUrl
        val qs = if (query.isEmpty()) "" else query.entries.joinToString(
            prefix = "?", separator = "&"
        ) { (k, v) -> "${enc(k)}=${enc(v)}" }
        return base + path + qs
    }

    /** GET returning the raw body, or throwing [RestException] on a non-2xx. */
    suspend fun getText(path: String, query: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val target = url(path, query)
            val req = Request.Builder().url(target).get()
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw RestException(r.code, path, body)
                body
            }
        }

    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) {
            val body = getText(path, query)
            HermesHttp.json.parseToJsonElement(body) as? JsonObject
                ?: throw RestException(0, path, "响应不是 JSON 对象")
        }

    suspend fun postJson(path: String, body: JsonObject): String = withContext(Dispatchers.IO) {
        val target = url(path)
        val req = Request.Builder()
            .url(target)
            .post(body.toString().toRequestBody(HermesHttp.jsonMedia))
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RestException(r.code, path, text)
            text
        }
    }

    suspend fun postJsonObject(path: String, body: JsonObject): JsonObject =
        HermesHttp.json.parseToJsonElement(postJson(path, body)) as? JsonObject
            ?: throw RestException(0, path, "响应不是 JSON 对象")

    suspend fun postForm(path: String, form: Map<String, String>): String = withContext(Dispatchers.IO) {
        val target = url(path)
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val req = Request.Builder()
            .url(target)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RestException(r.code, path, text)
            text
        }
    }

    /** Raw bytes, for artifact/file downloads. */
    suspend fun getBytes(path: String, query: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(path, query)).get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw RestException(r.code, path, "")
                r.body?.bytes() ?: ByteArray(0)
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

class RestException(val code: Int, val path: String, val body: String) : Exception(
    when (code) {
        401 -> "认证已失效，请重新登录 (HTTP 401)"
        403 -> "没有权限 (HTTP 403)"
        404 -> "接口不存在: $path"
        else -> "请求失败 HTTP $code: ${body.take(160)}"
    }
)
package com.wing.folderplayer.data.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AiConfig(val baseUrl: String, val apiKey: String, val model: String) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    /** Endpoint as configured by the user; never switched automatically. */
    val endpoint: String get() = baseUrl.trim().removeSuffix("/") + "/chat/completions"
}

class AiException(val httpCode: Int, message: String) : IOException(message)

/** Minimal OpenAI-compatible chat completion client. Cancelling the coroutine cancels the HTTP call. */
class AiClient(private val http: OkHttpClient = defaultClient) {

    suspend fun chat(config: AiConfig, system: String?, user: String, temperature: Double = 0.2): String {
        if (!config.isComplete) throw AiException(0, "AI endpoint, key or model not configured")
        val messages = JSONArray()
        if (system != null) messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", config.model).put("messages", messages).put("temperature", temperature)
        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val (code, text) = http.newCall(request).awaitBody()
        if (code !in 200..299) throw AiException(code, "HTTP $code")
        return try {
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            throw AiException(code, "unexpected response format")
        }
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Status code and full body; the body is read on OkHttp's thread so cancelling the coroutine aborts a slow body too. */
suspend fun Call.awaitBody(maxChars: Int = 2_000_000): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use { r -> r.code to (r.body?.string()?.take(maxChars) ?: "") }
                cont.resume(body)
            } catch (e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

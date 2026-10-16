package com.vision.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Result of one notification turn. reply is empty when Star should stay silent. */
data class Decision(
    val reply: String,
    val endConversation: Boolean,
    val relay: Boolean,
    val relaySummary: String
)

/** Text chat with friendly handling of Gemini API failures. */
object ChatEngine {
    private const val MODEL = "gemini-flash-latest" // change here if you want a specific model
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Returns null when the API call fails, so the caller can retry later.
     * Otherwise returns what Star should do for this incoming message.
     */
    suspend fun notificationDecision(
        platform: String,
        sender: String,
        incoming: String,
        recentContext: String,
        ended: Boolean,
        exchangesSoFar: Int,
        maxMessages: Int
    ): Decision? = withContext(Dispatchers.IO) {
        if (Prefs.apiKey.isBlank()) return@withContext null

        val limitReached = maxMessages > 0 && exchangesSoFar >= maxMessages
        val stateRule = when {
            ended -> "This conversation was already closed by you earlier. Do not continue it. " +
                "Set reply to an empty string and end_conversation to false, unless the relay rule above applies. " +
                "If relay is true, reply only with the short acknowledgement."
            limitReached -> "This conversation has reached its message limit. " +
                "Write a brief, polite closing in reply and set end_conversation to true. Set relay to false unless the relay rule applies."
            else -> "Reply to the incoming message. If the conversation has reached a natural end " +
                "(for example the sender says goodbye, or the topic is finished and there is nothing useful left to say), " +
                "write a brief closing reply and set end_conversation to true. Otherwise set end_conversation to false."
        }

        val prompt = """
You are Star, Raj's AI assistant. You are replying to an incoming $platform message from $sender on Raj's behalf.

Rules:
- Always be transparent that you are an AI assistant, not Raj. If asked directly whether you are an AI or a human, say honestly that you are an AI assistant.
- Speak in the sender's language (Hindi, Hinglish or English), briefly, politely and naturally. Do not use a fixed script.
- Never claim to be Raj, never invent facts about Raj or his life, and never make promises for Raj.
- Never share or ask for passwords, OTPs, API keys, bank details, addresses, live location, photos or other private information.
- Do not start unrelated topics.
- If the message contains something important that Raj must know (urgent, time-sensitive, an emergency, a request that needs his decision, or a serious problem), set relay to true, write a one-line relay_summary in Hindi for Raj, and in reply give a brief acknowledgement that you will let Raj know.

Conversation state: $stateRule

Recent context (for continuity only):
$recentContext

Output only a JSON object with exactly these keys:
{"reply": string, "end_conversation": boolean, "relay": boolean, "relay_summary": string}
Use an empty string for reply when you should not reply. Do not wrap the JSON in markdown.
        """.trimIndent()

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", "Incoming message: $incoming")))
                )
            )
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", Prefs.apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) return@withContext null
            val parts = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            val text = (0 until (parts?.length() ?: 0))
                .joinToString("") { parts!!.optJSONObject(it)?.optString("text").orEmpty() }
            val obj = runCatching { JSONObject(text.trim()) }.getOrNull()
                ?: return@withContext Decision("", false, false, "")
            Decision(
                reply = obj.optString("reply").trim(),
                endConversation = obj.optBoolean("end_conversation", false),
                relay = obj.optBoolean("relay", false),
                relaySummary = obj.optString("relay_summary").trim()
            )
        }
    }

    suspend fun reply(history: List<Pair<Boolean, String>>): String = withContext(Dispatchers.IO) {
        require(Prefs.apiKey.isNotBlank()) { "Add your Gemini API key in Settings first." }
        val contents = JSONArray()
        history.forEach { (user, text) ->
            contents.put(JSONObject()
                .put("role", if (user) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Persona.PROMPT + Persona.TEXT_NOTE))))
            .put("contents", contents)
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", Prefs.apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) when (r.code) {
                400, 401, 403 -> "Gemini rejected the request or API key. Check the key and API access in Settings."
                404 -> "The selected Gemini model isn't available for this API. Please check the model configuration."
                429 -> "Gemini is rate-limiting requests or the API quota is exhausted. Wait a little and try again."
                500, 502, 503, 504 -> "Gemini is busy or temporarily unavailable. Please try again in a moment."
                else -> "Gemini request failed (HTTP ${r.code}). Please try again."
            }
            else runCatching {
                val candidates = JSONObject(s).optJSONArray("candidates")
                    ?: throw IllegalStateException("Gemini returned no answer. Please try again.")
                if (candidates.length() == 0) throw IllegalStateException("Gemini returned no answer. Please try again.")
                val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini returned an empty answer. Please try again.")
                (0 until parts.length()).mapNotNull { i -> parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() } }.joinToString("\n")
            }.getOrElse { "Gemini returned an empty answer. Please try again." }
        }
    }
}

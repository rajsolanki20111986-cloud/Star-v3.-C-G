package com.vision.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class LiveState { Disconnected, Connecting, Listening, Speaking }

/** Gemini Live API session for two-way audio conversation. */
class LiveSession(
    private val ctx: Context,
    private val onState: (LiveState) -> Unit,
    private val onCaption: (text: String, fromUser: Boolean) -> Unit,
) {
    companion object { const val MODEL = "gemini-3.1-flash-live-preview" }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ws: WebSocket? = null
    private val audio = AudioStreamer(ctx) { ws?.send(audioMsg(it)) }
    @Volatile private var closed = false

    fun connect(apiKey: String, voice: String) {
        onState(LiveState.Connecting)
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup(voice)) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (reason.isNotBlank()) onCaption("Closed ($code): $reason", false)
                webSocket.close(1000, null)
                shutdown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onCaption("Connection error: ${t.message}", false)
                shutdown()
            }
        })
    }

    private fun setup(voice: String): String = JSONObject().put("setup", JSONObject()
        .put("model", "models/$MODEL")
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Persona.PROMPT))))
        .put("inputAudioTranscription", JSONObject())
        .put("outputAudioTranscription", JSONObject())
    ).toString()

    private fun audioMsg(pcm: ByteArray): String = JSONObject().put("realtimeInput", JSONObject()
        .put("audio", JSONObject()
            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=16000"))).toString()

    private fun handle(raw: String) {
        runCatching {
            val m = JSONObject(raw)
            if (m.has("setupComplete")) { audio.start(); onState(LiveState.Listening) }

            m.optJSONObject("serverContent")?.let { sc ->
                if (sc.optBoolean("interrupted")) { audio.clear(); onState(LiveState.Listening) }
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                        audio.play(Base64.decode(data, Base64.DEFAULT))
                        onState(LiveState.Speaking)
                    }
                }
                sc.optJSONObject("inputTranscription")?.optString("text")?.let { onCaption(it, true) }
                sc.optJSONObject("outputTranscription")?.optString("text")?.let { onCaption(it, false) }
                if (sc.optBoolean("turnComplete")) onState(LiveState.Listening)
            }

        }
    }

    fun stop() { ws?.close(1000, "bye"); shutdown() }

    private fun shutdown() {
        if (closed) return
        closed = true
        audio.stop()
        scope.cancel()
        onState(LiveState.Disconnected)
    }
}

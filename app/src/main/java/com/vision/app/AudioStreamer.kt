package com.vision.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Mic in: PCM16 16 kHz with echo cancellation, so Star still hears you over music.
 * Speaker out: PCM16 24 kHz. Other apps' audio is ducked only while Vision talks.
 */
class AudioStreamer(ctx: Context, private val onMic: (ByteArray) -> Unit) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var focus: AudioFocusRequest? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var ducked = false

    private fun voiceAttrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        val minIn = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 3200) * 2
        )
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
        r.startRecording()
        rec = r
        thread(name = "star-mic") {
            val buf = ByteArray(1280) // 40 ms
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) onMic(buf.copyOf(n))
            }
        }

        val minOut = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(voiceAttrs())
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(24000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minOut, 4800) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        thread(name = "star-play") {
            while (running) {
                val b = queue.poll(300, TimeUnit.MILLISECONDS)
                if (b == null) { setDuck(false); continue }
                setDuck(true)
                t.write(b, 0, b.size)
            }
        }
    }

    fun play(pcm: ByteArray) { queue.put(pcm) }

    /** Called when the user interrupts: drop everything Vision was about to say. */
    fun clear() {
        queue.clear()
        track?.let { it.pause(); it.flush(); it.play() }
    }

    private fun setDuck(on: Boolean) {
        if (on == ducked) return
        ducked = on
        if (on) {
            val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(voiceAttrs())
                .setOnAudioFocusChangeListener { }
                .build()
            focus = f
            am.requestAudioFocus(f)
        } else {
            focus?.let { am.abandonAudioFocusRequest(it) }
        }
    }

    fun stop() {
        running = false
        setDuck(false)
        runCatching { rec?.stop() }
        rec?.release(); rec = null
        aec?.release(); ns?.release()
        track?.release(); track = null
        queue.clear()
        am.mode = AudioManager.MODE_NORMAL
    }
}

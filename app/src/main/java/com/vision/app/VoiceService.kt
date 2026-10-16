package com.vision.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared live state read by the UI. */
object StarCore {
    val state = MutableStateFlow(LiveState.Disconnected)
    val caption = MutableStateFlow("")
}

/**
 * Foreground (microphone) service that owns the live session, so Star keeps
 * listening while it operates other apps like YouTube.
 */
class VoiceService : Service() {
    private var session: LiveSession? = null
    private var lastUser: Boolean? = null
    private var buf = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        val n = notification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        session?.stop()
        buf = ""
        lastUser = null
        session = LiveSession(
            this,
            onState = { s ->
                StarCore.state.value = s
                if (s == LiveState.Disconnected) stopSelf()
            },
            onCaption = { text, user ->
                buf = if (lastUser == user) buf + text else text
                lastUser = user
                StarCore.caption.value = (if (user) "You: " else "Star: ") + buf
            }
        ).also { it.connect(Prefs.apiKey, Prefs.voice) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        StarCore.state.value = LiveState.Disconnected
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("star", "Star", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "star")
            .setContentTitle("Star is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

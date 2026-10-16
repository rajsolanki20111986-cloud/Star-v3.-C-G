package com.vision.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Important messages the assistant decided Raj must see.
 * Stored only on this device, and never sent anywhere else.
 */
object RelayInbox {
    private const val CHANNEL_ID = "star_relay"
    private const val MAX_ENTRIES = 200
    private val lock = Any()

    fun add(context: Context, platform: String, sender: String, summary: String, original: String) {
        val entry = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("platform", platform)
            .put("sender", sender)
            .put("summary", summary)
            .put("original", original.take(500))
        synchronized(lock) {
            val current = readAll()
            val out = JSONArray()
            val start = (current.length() - (MAX_ENTRIES - 1)).coerceAtLeast(0)
            for (i in start until current.length()) out.put(current.getJSONObject(i))
            out.put(entry)
            Prefs.relayInboxJson = out.toString()
        }
        notifyOwner(context, platform, sender, summary)
    }

    fun list(): List<String> {
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        val arr = synchronized(lock) { readAll() }
        return (arr.length() - 1 downTo 0).map { i ->
            val o = arr.getJSONObject(i)
            val time = fmt.format(Date(o.optLong("time")))
            "$time · ${o.optString("platform")} · ${o.optString("sender")}\n${o.optString("summary")}"
        }
    }

    fun clear() {
        synchronized(lock) { Prefs.relayInboxJson = "[]" }
    }

    private fun readAll(): JSONArray =
        runCatching { JSONArray(Prefs.relayInboxJson) }.getOrElse { JSONArray() }

    private fun notifyOwner(context: Context, platform: String, sender: String, summary: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Star relay", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("$platform: $sender")
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }
}

package com.vision.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted settings for API key, voice and notification assistant state. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (::sp.isInitialized) return
        val app = c.applicationContext
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        sp = EncryptedSharedPreferences.create(
            app, "star_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var apiKey: String
        get() = sp.getString("api", "") ?: ""
        set(v) = sp.edit().putString("api", v.trim()).apply()

    var voice: String
        get() = sp.getString("voice", "Charon") ?: "Charon"
        set(v) = sp.edit().putString("voice", v.trim()).apply()

    var notificationAssistantEnabled: Boolean
        get() = sp.getBoolean("notification_assistant_enabled", false)
        set(v) = sp.edit().putBoolean("notification_assistant_enabled", v).apply()

    var instagramReplyNames: String
        get() = sp.getString("instagram_reply_names", "") ?: ""
        set(v) = sp.edit().putString("instagram_reply_names", v).apply()

    var whatsappReplyNames: String
        get() = sp.getString("whatsapp_reply_names", "") ?: ""
        set(v) = sp.edit().putString("whatsapp_reply_names", v).apply()

    /** Messages exchanged per conversation before the assistant closes it. 0 means no limit. */
    var maxMessagesPerConversation: Int
        get() = sp.getInt("max_messages_per_conversation", 50)
        set(v) = sp.edit().putInt("max_messages_per_conversation", v.coerceAtLeast(0)).apply()

    /** JSON array of important messages kept for the owner. */
    var relayInboxJson: String
        get() = sp.getString("relay_inbox", "[]") ?: "[]"
        set(v) = sp.edit().putString("relay_inbox", v).apply()

    fun namesFor(platform: String): List<String> {
        val raw = if (platform.equals("Instagram", true)) instagramReplyNames else whatsappReplyNames
        return raw.lines().map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotBlank() }.distinct()
    }

    // Conversations the assistant has closed. Stored so they stay closed after restarts.
    fun isConversationEnded(threadKey: String): Boolean = endedConversations().contains(threadKey)

    fun markConversationEnded(threadKey: String) {
        val updated = endedConversations() + threadKey
        sp.edit().putStringSet("ended_conversations", updated).apply()
    }

    fun clearEndedConversations() {
        sp.edit().remove("ended_conversations").apply()
    }

    private fun endedConversations(): Set<String> =
        sp.getStringSet("ended_conversations", emptySet())?.toSet() ?: emptySet()
}

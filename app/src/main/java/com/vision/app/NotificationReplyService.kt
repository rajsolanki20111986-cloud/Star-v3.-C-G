package com.vision.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens for Instagram and WhatsApp notifications from allowlisted senders.
 * Replies only through a direct-reply action that Android exposes on the notification.
 * Conversations end when Star decides, or at the message limit. Closed conversations
 * stay closed, and only important messages get through to the owner's relay inbox.
 */
class NotificationReplyService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = ConcurrentHashMap<String, MutableList<String>>()
    private val exchanges = ConcurrentHashMap<String, Int>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val handled = ConcurrentHashMap.newKeySet<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Prefs.init(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.notificationAssistantEnabled) return
        if (sbn.packageName == packageName) return
        val platform = when (sbn.packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.instagram.android" -> "Instagram"
            else -> return
        }
        val extras = sbn.notification.extras ?: return
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (sender.isBlank() || message.isBlank()) return
        val senderKey = sender.removePrefix("@").lowercase()
        if (Prefs.namesFor(platform).none { it == senderKey }) return

        val notificationKey = "${sbn.key}:${sbn.postTime}:${message.hashCode()}"
        if (!inFlight.add(notificationKey)) return
        if (!handled.add(notificationKey)) {
            inFlight.remove(notificationKey)
            return
        }

        val action = findReplyAction(sbn.notification)
        val threadKey = "$platform:$senderKey"
        scope.launch {
            var retryable = false
            try {
                retryable = handleMessage(platform, sender, threadKey, message, action)
            } catch (_: Exception) {
                // Fail closed: if anything goes wrong, do not send a guessed reply.
            } finally {
                if (retryable) handled.remove(notificationKey)
                inFlight.remove(notificationKey)
            }
        }
    }

    /** Returns true when the API failed and the same notification may be retried later. */
    private suspend fun handleMessage(
        platform: String,
        sender: String,
        threadKey: String,
        message: String,
        action: Notification.Action?
    ): Boolean {
        val ended = Prefs.isConversationEnded(threadKey)

        if (SENSITIVE.containsMatchIn(message)) {
            // Keep the attempt visible to the owner, but never store the sensitive text itself.
            RelayInbox.add(this, platform, sender, "Asked for private information. Declined.", "")
            if (!ended && action != null) sendReply(action, REFUSAL)
            return false
        }

        val context = synchronized(recent) { recent[threadKey]?.takeLast(12)?.joinToString("\n").orEmpty() }
        val exchangesSoFar = exchanges[threadKey] ?: 0
        val maxMessages = Prefs.maxMessagesPerConversation

        val decision = ChatEngine.notificationDecision(
            platform, sender, message, context, ended, exchangesSoFar, maxMessages
        ) ?: return true

        if (decision.relay && decision.relaySummary.isNotBlank()) {
            RelayInbox.add(this, platform, sender, decision.relaySummary, message)
        }

        // Closed conversations only speak again to pass on something important.
        if (ended && !decision.relay) return false

        val reply = decision.reply
        if (reply.isNotBlank() && reply.length <= 700 && action != null) {
            sendReply(action, reply)
            synchronized(recent) {
                val thread = recent.getOrPut(threadKey) { mutableListOf() }
                thread.add("Them: $message")
                thread.add("Star: $reply")
                while (thread.size > 24) thread.removeAt(0)
            }
            exchanges[threadKey] = exchangesSoFar + 1
        }

        val limitReached = maxMessages > 0 && (exchanges[threadKey] ?: 0) >= maxMessages
        if (!ended && (decision.endConversation || limitReached)) {
            Prefs.markConversationEnded(threadKey)
        }
        return false
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        // Do not fall back to an arbitrary RemoteInput action (which may do something else).
        return notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true &&
                action.title?.toString()?.let {
                    it.contains("reply", true) || it.contains("respond", true) ||
                        it.contains("जवाब") || it.contains("उत्तर")
                } == true
        }
    }

    private fun sendReply(action: Notification.Action, message: String) {
        val inputs = action.remoteInputs ?: return
        val textInput = inputs.firstOrNull { it.allowFreeFormInput } ?: return
        val intent = Intent()
        val results = Bundle().apply { putCharSequence(textInput.resultKey, message) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(this, 0, intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REFUSAL = "मैं निजी जानकारी, पासवर्ड या निजी फ़ोटो साझा नहीं कर सकता। आप राज को सीधे संदेश भेज सकते हैं।"
        private val SENSITIVE = Regex(
            "(?i)(password|passcode|otp|one.?time.?password|api.?key|secret.?key|private.?key|verification code|bank details|address|live location|send (me )?(a )?(photo|picture|selfie|private)|nude|intimate|पासवर्ड|ओटीपी|गुप्त कुंजी|प्राइवेट फोटो|निजी फोटो|लोकेशन|बैंक डिटेल)"
        )
    }
}

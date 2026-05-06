package com.lily.ai

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

class LilyNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "LilyNotifService"
        private const val MAX_STORED = 20

        // Thread-safe queue of recent notifications
        private val recentNotifs = ConcurrentLinkedDeque<String>()

        fun getRecentNotifications(): String {
            if (recentNotifs.isEmpty()) return "No recent notifications."
            return recentNotifs.take(10).joinToString(" | ")
        }

        fun clearNotifications() {
            recentNotifs.clear()
        }
    }

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        try {
            val pkg = sbn.packageName ?: return
            val extras = sbn.notification?.extras ?: return

            val title = extras.getCharSequence("android.title")?.toString() ?: return
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val time = timeFormat.format(Date(sbn.postTime))

            // Skip system / Lily's own notifications
            if (pkg == packageName) return
            if (title.isBlank() && text.isBlank()) return

            val appName = getAppName(pkg)
            val entry = "$appName — $title: $text [$time]"

            Log.d(TAG, "Notification: $entry")

            // Add to front, keep max size
            recentNotifs.addFirst(entry)
            while (recentNotifs.size > MAX_STORED) recentNotifs.removeLast()

            // If Lily service is running, optionally announce urgent messages
            if (LilyForegroundService.isRunning) {
                val isMessage = isMessagingApp(pkg)
                if (isMessage && text.isNotBlank()) {
                    // Don't auto-announce — user can ask "Hey Lily, what did X message me"
                    Log.d(TAG, "Message stored for query: $entry")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Notification processing error: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: clean up
    }

    private fun getAppName(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            when {
                pkg.contains("whatsapp") -> "WhatsApp"
                pkg.contains("telegram") -> "Telegram"
                pkg.contains("instagram") -> "Instagram"
                pkg.contains("gmail") -> "Gmail"
                pkg.contains("message") -> "Messages"
                else -> pkg.substringAfterLast(".")
            }
        }
    }

    private fun isMessagingApp(pkg: String): Boolean {
        return pkg.contains("whatsapp") ||
                pkg.contains("telegram") ||
                pkg.contains("signal") ||
                pkg.contains("messenger") ||
                pkg.contains("message") ||
                pkg.contains("sms")
    }
}

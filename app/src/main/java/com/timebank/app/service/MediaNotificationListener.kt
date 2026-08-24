package com.timebank.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.timebank.app.data.Economy

/**
 * Two jobs, both of which need notification access:
 *
 *  1. Existing and enabled, it grants us permission to call
 *     `MediaSessionManager.getActiveSessions()` — see [MediaMonitor].
 *  2. It watches for the ongoing "close your private tabs" notification that
 *     browsers post while an incognito / InPrivate session is alive, and publishes
 *     the owning packages to [Economy.privatePackages].
 *
 * Note what (2) can and cannot see: the notification means private tabs *exist*,
 * not that one is on screen right now. Chrome and Edge keep it posted until the
 * last private tab is closed, so the surcharge keeps applying while a private tab
 * sits parked in the background. Distinguishing "currently viewing" would require
 * an AccessibilityService reading window contents.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() = refresh()

    override fun onListenerDisconnected() {
        // We can no longer see notifications; don't keep charging on stale state.
        Economy.privatePackages.value = emptySet()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = try {
            activeNotifications
        } catch (_: Exception) {
            // Thrown when the listener isn't connected yet; leave the last value alone.
            return
        } ?: return

        Economy.privatePackages.value = active
            .filter { isPrivateSession(it) }
            .map { it.packageName }
            .toSet()
    }

    private fun isPrivateSession(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val extras = n.extras
        val haystack = buildString {
            append(n.channelId ?: "").append(' ')
            append(sbn.tag ?: "").append(' ')
            append(extras?.getCharSequence(Notification.EXTRA_TITLE) ?: "").append(' ')
            append(extras?.getCharSequence(Notification.EXTRA_TEXT) ?: "")
        }.lowercase()

        return PRIVATE_MARKERS.any { it in haystack }
    }

    private companion object {
        /**
         * Matches Chrome ("Close all Incognito tabs", channel "incognito"), Edge and
         * other Chromium browsers ("InPrivate"), and Firefox ("private browsing session").
         * Kept as substrings rather than a package allow-list so any browser works.
         */
        val PRIVATE_MARKERS = listOf("incognito", "inprivate", "private tab", "private browsing")
    }
}

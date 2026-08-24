package com.timebank.app.service

import android.service.notification.NotificationListenerService

/**
 * Empty listener. Its only job is to exist and be enabled by the user, which
 * grants us permission to call MediaSessionManager.getActiveSessions().
 */
class MediaNotificationListener : NotificationListenerService()

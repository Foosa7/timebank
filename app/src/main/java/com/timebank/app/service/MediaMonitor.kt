package com.timebank.app.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/** True when any media session (music, YouTube, podcasts...) is actively playing. */
object MediaMonitor {

    fun isMediaPlaying(context: Context): Boolean {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as MediaSessionManager
            val listener = ComponentName(context, MediaNotificationListener::class.java)
            val controllers = msm.getActiveSessions(listener)
            controllers.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        } catch (_: SecurityException) {
            false // notification access not granted yet
        } catch (_: Exception) {
            false
        }
    }
}

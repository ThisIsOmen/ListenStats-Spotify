package com.listenstats

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

class ListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val trackers = HashMap<MediaSession.Token, PlayTracker>()
    private var msm: MediaSessionManager? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sync(it) }

    private val periodic = object : Runnable {
        override fun run() {
            Genres.kick(applicationContext)
            handler.postDelayed(this, 10 * 60 * 1000L)
        }
    }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val cn = ComponentName(this, ListenerService::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            sync(m.getActiveSessions(cn))
        } catch (_: SecurityException) {
        }
        handler.removeCallbacks(periodic)
        handler.post(periodic)
    }

    private fun sync(list: List<MediaController>?) {
        val l = list ?: emptyList()
        val tokens = l.map { it.sessionToken }.toSet()
        trackers.keys.filter { it !in tokens }.forEach { trackers.remove(it)?.release() }
        l.forEach { c ->
            if (c.sessionToken !in trackers) {
                trackers[c.sessionToken] = PlayTracker(applicationContext, c, handler)
            }
        }
    }

    override fun onListenerDisconnected() {
        cleanup()
        try {
            requestRebind(ComponentName(this, ListenerService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacks(periodic)
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        trackers.values.forEach { it.release() }
        trackers.clear()
    }
}

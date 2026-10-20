package com.listenstats

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock

/** Следит за одной медиа-сессией. Прослушивание засчитывается после 30 секунд воспроизведения. */
class PlayTracker(
    private val ctx: Context,
    private val c: MediaController,
    private val h: Handler
) {
    private var key: String? = null
    private var artist = ""
    private var title = ""
    private var acc = 0L
    private var resumedAt = 0L
    private var playId = -1L
    private var mine: Now? = null

    private val countRun = Runnable { record() }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onMeta(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = onState(state)
        override fun onSessionDestroyed() = finish()
    }

    init {
        c.registerCallback(cb, h)
        onMeta(c.metadata)
        onState(c.playbackState)
    }

    private fun onMeta(m: MediaMetadata?) {
        val a = clean(
            m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        )
        val t = cleanTitle(m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
        val k = if (a.isEmpty() || t.isEmpty() || isAd(a, t) || !Prefs.allowed(ctx, c.packageName)) {
            null
        } else "$a|$t"
        if (k == key) return
        finish()
        key = k; artist = a; title = t
        acc = 0; playId = -1; resumedAt = 0
        publish()
        onState(c.playbackState)
    }

    private fun onState(s: PlaybackState?) {
        if (key == null) return
        val playing = s?.state == PlaybackState.STATE_PLAYING
        if (playing) {
            // повтор того же трека: позиция вернулась в начало после полноценного прослушивания
            if (playId != -1L && s != null && s.position in 0..3000 && acc > 20_000) {
                finish()
                acc = 0; playId = -1; resumedAt = 0
            }
            if (resumedAt == 0L) startClock()
        } else {
            stopClock()
        }
        publish()
    }

    private fun startClock() {
        resumedAt = SystemClock.elapsedRealtime()
        h.removeCallbacks(countRun)
        if (playId == -1L) h.postDelayed(countRun, (30_000 - acc).coerceAtLeast(0))
    }

    private fun stopClock() {
        if (resumedAt != 0L) {
            acc += SystemClock.elapsedRealtime() - resumedAt
            resumedAt = 0
        }
        h.removeCallbacks(countRun)
        if (playId != -1L) Db.get(ctx).updateListened(playId, acc)
    }

    private fun record() {
        if (key == null || playId != -1L) return
        val db = Db.get(ctx)
        playId = db.addPlay(artist, title, System.currentTimeMillis())
        val cur = acc + if (resumedAt != 0L) SystemClock.elapsedRealtime() - resumedAt else 0L
        db.updateListened(playId, cur)
        Bus.bump()
        Genres.kick(ctx)
    }

    private fun finish() {
        stopClock()
    }

    private fun publish() {
        if (key != null) {
            val n = Now(artist, title, c.playbackState?.state == PlaybackState.STATE_PLAYING)
            mine = n
            Bus.now.value = n
        } else {
            if (mine != null && Bus.now.value == mine) Bus.now.value = null
            mine = null
        }
    }

    fun release() {
        finish()
        try { c.unregisterCallback(cb) } catch (_: Exception) {}
        if (mine != null && Bus.now.value == mine) Bus.now.value = null
        mine = null
    }
}

package com.os4.rear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * What is playing, for the rear card, fed by the card itself. SystemUI
 * hands the card the top session's `media_data` (its token, song, artist and artwork), so a
 * MediaController on that token gives position and controls with no listener of our own.
 * Main thread only.
 */
object RearMedia {
    var art: Bitmap? = null; private set
    var running = false; private set
    /** Strictly PLAYING: HMC-E's cover dips through a skip's buffering on purpose (CardSpring). */
    var playingNow = false; private set
    private var playing = false
    var title = ""; private set
    var artist = ""; private set
    /** Identifies the track: a new key is a new song, the same key a refreshed picture of it. */
    var key = ""; private set
    var duration = 0L; private set
    /** The last skip pressed here and when, so a track change can tell which way it went. */
    var lastSkip = 1; private set
    var lastSkipAt = 0L; private set

    /** Something is playing (or paused mid-track) and there is a picture of it. */
    val shown get() = playing && art != null

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var playback: PlaybackState? = null
    private var cardArt: Bitmap? = null

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    /** The card's `media_data`, each time the card is built or handed new data. */
    fun update(context: Context, media: Bundle?) {
        if (Looper.myLooper() != main.looper) { main.post { update(context, media) }; return }
        val token = media?.getParcelable("token", MediaSession.Token::class.java)
        cardArt = runCatching {
            media?.getParcelable("artwork", Icon::class.java)?.loadDrawable(context)?.let { d ->
                (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: Bitmap.createBitmap(512, 512,
                    Bitmap.Config.ARGB_8888).also { b ->
                    d.setBounds(0, 0, 512, 512); d.draw(android.graphics.Canvas(b))
                }
            }
        }.getOrNull()
        if (token != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = token?.let { MediaController(context, it) }
            controller?.registerCallback(callback, main)
        }
        refresh()
    }

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (running) t.pause() else t.play()
    }
    fun next() { lastSkip = 1; lastSkipAt = SystemClock.uptimeMillis(); controller?.transportControls?.skipToNext() }
    fun previous() { lastSkip = -1; lastSkipAt = SystemClock.uptimeMillis(); controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }

    /** Players report a position only on a change; the rest is extrapolated from its time and speed. */
    fun positionNow(): Long {
        val s = playback ?: return 0L
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        return if (duration > 0) p.coerceIn(0L, duration) else p.coerceAtLeast(0L)
    }

    private val recheck = Runnable { refresh() }

    private fun refresh() {
        val c = controller
        val state = c?.playbackState?.state
        // Between tracks a player passes through buffering or skipping: still playing, for the
        // card, or the cover shrank as if paused and grew back on every track change.
        val run = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING || state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
            state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
        val md = c?.metadata
        playback = c?.playbackState
        duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val a = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: cardArt
        val k = "${c?.packageName}|$t|$a"
        val active = run || state == PlaybackState.STATE_PAUSED
        val now = state == PlaybackState.STATE_PLAYING
        // Nothing promises the PLAYING that ends a skip reaches us: ask again while passing (HMC-E).
        main.removeCallbacks(recheck)
        if (run && !now) main.postDelayed(recheck, 400L)
        if (bmp === art && active == playing && run == running && now == playingNow && k == key) return
        art = bmp; playing = active; running = run; playingNow = now; title = t; artist = a; key = k
        listeners.toList().forEach { it() }
    }
}

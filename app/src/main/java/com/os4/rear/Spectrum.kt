package com.os4.rear

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock

/**
 * What HyperMusicCover-Enhanced hands its extensions, for the rear card: five audio-reactive
 * levels, the lock screen's backdrop ([HmcBackdrop]) and the lyric ([HmcLyrics]).
 *
 * HMC-E already captures playback before volume in SystemUI and turns it into the five bars
 * its island and players show. Asked over its probe channel (`op extspectrum`), it pushes
 * each frame to [sink] as a oneway call: code 1, five floats. The request is repeated every
 * [REPEAT_MS] while the card is up - HMC-E drops a listener that stops repeating. It is sent
 * from the rear launcher's process, where the card runs; HMC-E serves that sender when
 * HyperRear is signed like it.
 *
 * Nothing here captures audio; without HMC-E (or with a build that has no such op) the bars
 * simply stay down, and [available] stays false so the title keeps its full width.
 */
object Spectrum {
    private const val ACTION = "com.os4.musiccover.PROBE"
    private const val REPEAT_MS = 1_000L
    /** No frame for this long: not available (HMC-E absent, or nothing playing). */
    private const val STALE_MS = 1_500L

    val levels = FloatArray(5)
    private val target = FloatArray(5)
    @Volatile private var lastFrameAt = 0L
    private var lastStep = 0L
    private var context: Context? = null
    private val main = Handler(Looper.getMainLooper())

    val available get() = SystemClock.uptimeMillis() - lastFrameAt < STALE_MS

    /** Bar colours top/bottom as HMC-E's Bar color setting draws them. */
    @Volatile var top = 0xFFFFFFFF.toInt()
        private set
    @Volatile var bottom = 0xFFFFFFFF.toInt()
        private set
    /** HMC-E's Audio visualization switch. */
    @Volatile private var enabled = true

    /** HMC-E has answered since the card opened: the bars have a place, even while paused. */
    @Volatile var connected = false
        private set

    private val sink = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == FIRST_CALL_TRANSACTION + 1) {
                // The lock screen's backdrop for this track, composed by HMC-E.
                val key = data.readString().orEmpty()
                val bmp = runCatching { android.graphics.Bitmap.CREATOR.createFromParcel(data) }
                    .getOrNull()
                if (bmp != null) main.post { HmcBackdrop.set(key, bmp) }
                return true
            }
            if (code == FIRST_CALL_TRANSACTION + 2) {
                // The lyric HMC-E settled for this track and how its settings draw it - see
                // HMC-E's ExtensionSpectrum for the layout.
                data.readInt()
                val pkg = data.readString().orEmpty()
                val title = data.readString().orEmpty()
                val sizeSp = data.readFloat()
                val weight = data.readInt()
                val align = data.readInt()
                val depthBlur = data.readInt()
                val aliveFx = data.readInt()
                val n = data.readInt()
                val lines = ArrayList<HmcLyrics.Line>(n.coerceIn(0, 2000))
                repeat(n) {
                    val text = data.readString().orEmpty()
                    val start = data.readInt()
                    val end = data.readInt()
                    val opposite = data.readInt() != 0
                    lines += HmcLyrics.Line(text, start, end, opposite,
                        data.createIntArray(), data.createIntArray(), data.createIntArray(),
                        data.readString(), data.readString())
                }
                // Gentle lyric flow comes after the lines; an HMC-E from before it sends none.
                val groups = data.dataAvail() >= 4 && data.readInt() != 0
                val look = HmcLyrics.Look(sizeSp, weight, align, depthBlur, aliveFx, groups)
                main.post { HmcLyrics.set(lines, look, pkg, title) }
                return true
            }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            for (i in 0 until 5) target[i] = data.readFloat().coerceIn(0f, 1f)
            if (data.dataAvail() >= 12) {
                top = data.readInt()
                bottom = data.readInt()
                enabled = data.readInt() and 1 != 0
            }
            lastFrameAt = SystemClock.uptimeMillis()
            connected = enabled
            return true
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            send("start")
            main.postDelayed(this, REPEAT_MS)
        }
    }

    fun start(c: Context) {
        context = c.applicationContext
        main.removeCallbacks(repeat)
        repeat.run()
    }

    fun stop() {
        main.removeCallbacks(repeat)
        send("stop")
        context = null
        lastFrameAt = 0L
        connected = false
        HmcLyrics.clear()
        levels.fill(0f)
        target.fill(0f)
    }

    private fun send(what: String) {
        val c = context ?: return
        runCatching {
            // HMC-E answers only senders it can identify: share ours with the broadcast.
            c.sendBroadcast(Intent(ACTION).setPackage("com.android.systemui")
                .putExtra("op", "extspectrum").putExtra("do", what)
                .putExtras(Bundle().apply { putBinder("sink", sink) }), null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }
    }

    /**
     * Moves the shown levels toward the latest frame; call once per drawn frame. HMC-E's own
     * smoothing (AudioWaveDrawable.step): 0.6 of the way up and 0.28 down per 30fps frame,
     * scaled to the time actually passed.
     */
    fun step() {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastStep == 0L) 0f else ((now - lastStep) / 1000f).coerceAtMost(0.1f)
        lastStep = now
        val frames = dt * 30f
        val attack = 1f - Math.pow(0.4, frames.toDouble()).toFloat()
        val release = 1f - Math.pow(0.72, frames.toDouble()).toFloat()
        val live = available
        for (b in 0 until 5) {
            val t = if (live) target[b] else 0f
            levels[b] += (t - levels[b]) * if (t > levels[b]) attack else release
        }
    }
}

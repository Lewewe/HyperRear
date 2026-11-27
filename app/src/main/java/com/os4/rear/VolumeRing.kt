package com.os4.rear

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.media.AudioManager
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The music volume as a ring round the top camera, shown on a volume change: it comes out of the
 * lens (growing from under its edge, fading in and sharpening from a blur), then goes back in.
 */
class VolumeRing(private val view: View) {
    private val audio = view.context.getSystemService(AudioManager::class.java)
    private val ring = LensRing(view.resources.displayMetrics.density)

    private var shown = 0f      // 0 hidden in the lens .. 1 out
    private var target = 0f
    private var frac = Float.NaN
    private var reveal: ValueAnimator? = null
    private var slide: ValueAnimator? = null

    private val hide = Runnable { animateShown(0f, HIDE_MS) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra(EXTRA_STREAM, -1) == AudioManager.STREAM_MUSIC) changed()
        }
    }

    fun attach() {
        view.context.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED), Context.RECEIVER_EXPORTED)
    }

    fun detach() {
        runCatching { view.context.unregisterReceiver(receiver) }
        view.removeCallbacks(hide)
        reveal?.cancel(); slide?.cancel()
        shown = 0f; target = 0f
    }

    private fun current(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max <= 0) 0f else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun changed() {
        val to = current()
        if (frac.isNaN() || shown == 0f) frac = to
        else {
            slide?.cancel()
            slide = ValueAnimator.ofFloat(frac, to).apply {
                duration = SLIDE_MS
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { frac = it.animatedValue as Float; view.invalidate() }
                start()
            }
        }
        if (target != 1f) animateShown(1f, SHOW_MS)
        view.removeCallbacks(hide)
        view.postDelayed(hide, HOLD_MS)
    }

    private fun animateShown(to: Float, ms: Long) {
        target = to
        reveal?.cancel()
        reveal = ValueAnimator.ofFloat(shown, to).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { shown = it.animatedValue as Float; view.invalidate() }
            start()
        }
    }

    /** [w] x [h] is the whole rear screen. */
    fun draw(c: Canvas, w: Float, h: Float) {
        if (!frac.isNaN()) ring.draw(c, w, h, LensRing.TOP_Y, shown, frac)
    }

    companion object {
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val SHOW_MS = 320L
        private const val HIDE_MS = 420L
        private const val HOLD_MS = 1600L
        private const val SLIDE_MS = 180L
    }
}

/**
 * A level as a ring round one of the rear cameras: [shown] brings it out of the lens (growing
 * from under its edge, fading in and sharpening from a blur) and back in.
 */
class LensRing(private val dens: Float) {
    private val node = RenderNode("lensRing")
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(70, 255, 255, 255) }
    private val level = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }

    /** [w] x [h] is the whole rear screen; [lensY] the lens centre's share of its height. */
    fun draw(c: Canvas, w: Float, h: Float, lensY: Float, shown: Float, frac: Float) {
        if (shown <= 0f || !c.isHardwareAccelerated) return
        val cx = w * LENS_X
        val cy = h * lensY
        val lens = h * LENS_R
        val stroke = STROKE_DP * dens
        // Out of the lens: from under its edge to a gap beside it.
        val r = lens - stroke + (GAP_DP * dens + stroke * 1.5f) * shown
        track.strokeWidth = stroke
        level.strokeWidth = stroke
        val blur = BLUR_DP * dens * (1f - shown)
        node.setPosition(0, 0, w.toInt(), h.toInt())
        node.setRenderEffect(if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) else null)
        node.alpha = shown
        val rc = node.beginRecording()
        try {
            rc.drawCircle(cx, cy, r, track)
            if (frac > 0f) rc.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * frac, false, level)
        } finally { node.endRecording() }
        c.drawRenderNode(node)
    }

    companion object {
        // The top lens on the rear screen, measured off the rear wallpaper picker's preview
        // (the card 1043 px wide there): centre (147.8, 146.5), radius 128.7 of 904 x 572.
        const val LENS_X = 147.8f / 904f
        const val TOP_Y = 146.5f / 572f
        // shortcut: the lower lens taken as the top one mirrored about the middle; measure it
        // off the same preview if the ring sits off it.
        const val BOTTOM_Y = 1f - TOP_Y
        const val LENS_R = 128.7f / 572f
        const val BLUR_DP = 10f
        private const val STROKE_DP = 3f
        private const val GAP_DP = 0f
    }
}

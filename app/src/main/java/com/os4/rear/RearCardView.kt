package com.os4.rear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * The rear music card: a backdrop (the artwork blurred, saturated and dimmed,
 * crossfaded on a new track) under [RearPlayer], which draws right of the camera island.
 */
class RearCardView(context: Context) : View(context) {
    private val dens = resources.displayMetrics.density
    private val player = RearPlayer(context, dens) { invalidate() }
    private val onMedia: () -> Unit = { player.sync(); backdrop(); invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RearMedia.addListener(onMedia)
        player.snap()
        backdrop()
    }

    override fun onDetachedFromWindow() {
        RearMedia.removeListener(onMedia)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ backdrop

    private val node = RenderNode("backdrop").apply {
        val blur = RenderEffect.createBlurEffect(BLUR_DP * dens, BLUR_DP * dens, Shader.TileMode.CLAMP)
        setRenderEffect(RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.35f) }), blur))
    }
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private var current: Bitmap? = null
    private var outgoing: Bitmap? = null
    private var currentKey = ""
    private var mix = 1f
    private var fade: ValueAnimator? = null

    /** Same song, the picture is swapped in place; a new one comes in over the old (no dip). */
    private fun backdrop() {
        val next = RearMedia.art ?: return
        if (current != null && RearMedia.key == currentKey) { current = next; return }
        currentKey = RearMedia.key
        outgoing = current
        current = next
        if (outgoing == null) { mix = 1f; return }
        fade?.cancel()
        mix = 0f
        // HMC-E's track fade (WallpaperProbe sTrackFadeMs, cubic ease-out): turns with the cover.
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RearPlayer.TRACK_FADE_MS
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { mix = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (fade === a) { outgoing = null; invalidate() } }
            })
            start()
        }
    }

    private fun drawArt(c: Canvas, b: Bitmap, alpha: Float, w: Float, h: Float) {
        if (alpha <= 0f) return
        val s = max(w / b.width, h / b.height)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
        artPaint.alpha = (255 * alpha).toInt()
        c.drawBitmap(b, m, artPaint)
    }

    // ------------------------------------------------------------------ draw

    /** The camera island's width on the left: the display's cutout, or q200's 296 of 904. */
    private fun cameraLeft(): Float {
        val inset = rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
        return if (inset > 0) inset.toFloat() else width * 296f / 904f
    }

    private val tick = Runnable { invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(Color.BLACK)
        val cur = current
        if (cur != null && c.isHardwareAccelerated) {
            node.setPosition(0, 0, width, height)
            val rc = node.beginRecording()
            try {
                outgoing?.let { drawArt(rc, it, 1f, w, h) }
                drawArt(rc, cur, mix, w, h)
            } finally { node.endRecording() }
            c.drawRenderNode(node)
            c.drawColor(Color.argb(DIM_ALPHA, 0, 0, 0))
        }
        val left = cameraLeft()
        c.save()
        c.translate(left, 0f)
        player.draw(c, w - left, h)
        c.restore()
        // The bar and its times move on their own while playing.
        removeCallbacks(tick)
        if (RearMedia.running) postDelayed(tick, TICK_MS)
    }

    // ------------------------------------------------------------------ touch

    private var down = -1
    private var scrubbing = false

    /** Only the bar and buttons take a touch; anywhere else is left to the panel's own gestures. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x - cameraLeft()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = player.buttonAt(x, e.y)
                if (down >= 0) { player.press(down); parent?.requestDisallowInterceptTouchEvent(true); return true }
                scrubbing = player.scrubStart(x, e.y)
                if (scrubbing) parent?.requestDisallowInterceptTouchEvent(true)
                return scrubbing
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) player.scrubMove(x)
                else if (down >= 0 && player.buttonAt(x, e.y) != down) { player.release(down, false); down = -1 }
            MotionEvent.ACTION_UP -> {
                if (scrubbing) player.scrubEnd(true) else if (down >= 0) player.release(down, true)
                scrubbing = false; down = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) player.scrubEnd(false) else if (down >= 0) player.release(down, false)
                scrubbing = false; down = -1
            }
        }
        return true
    }

    companion object {
        private const val BLUR_DP = 60f
        private const val DIM_ALPHA = 90
        private const val TICK_MS = 250L
    }
}

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
class RearCardView(
    context: Context,
    /** Tells the panel this touch is the card's, so a double tap on a control does not sleep the screen. */
    private val interceptDoubleClick: () -> Unit = {},
) : View(context) {
    private val dens = resources.displayMetrics.density
    private val player = RearPlayer(context, dens) { invalidate() }
    private val onMedia: () -> Unit = { player.sync(); backdrop(); invalidate() }
    private val volume = VolumeRing(this)
    private val onHmc: () -> Unit = { backdrop(); invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RearMedia.addListener(onMedia)
        volume.attach()
        HmcBackdrop.addListener(onHmc)
        Spectrum.start(context)
        post { takeClock() }
        player.snap()
        backdrop()
    }

    override fun onDetachedFromWindow() {
        RearMedia.removeListener(onMedia)
        volume.detach()
        HmcBackdrop.removeListener(onHmc)
        Spectrum.stop()
        giveClockBack()
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ backdrop

    private val ownLook = RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.35f) }),
        RenderEffect.createBlurEffect(BLUR_DP * dens, BLUR_DP * dens, Shader.TileMode.CLAMP))
    /** HMC-E's backdrop is already a blur of colour: only softened. */
    private val hmcLook = RenderEffect.createBlurEffect(6f * dens, 6f * dens, Shader.TileMode.CLAMP)
    private var fromHmc = false
    private val node = RenderNode("backdrop").apply { setRenderEffect(ownLook) }
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private var current: Bitmap? = null
    private var outgoing: Bitmap? = null
    private var currentKey = ""
    private var mix = 1f
    private var fade: ValueAnimator? = null

    /**
     * HMC-E's lock screen backdrop when it has sent one, the cover blurred here otherwise. Same
     * song, the picture is swapped in place; a new one comes in over the old (no dip).
     */
    private fun backdrop() {
        val hmc = HmcBackdrop.bitmap
        val useHmc = hmc != null
        if (useHmc != fromHmc) {
            fromHmc = useHmc
            node.setRenderEffect(if (useHmc) hmcLook else ownLook)
            currentKey = ""
        }
        val next = (if (useHmc) hmc else RearMedia.art) ?: return
        val key = if (useHmc) HmcBackdrop.key else RearMedia.key
        if (current != null && key == currentKey) { current = next; return }
        currentKey = key
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

    // ------------------------------------------------------------------ the panel's clock

    /**
     * The rear panel's own clock is a pill at the top right (SmartAssistantPanel's `time_parent`,
     * a MIUI blur backdrop with the `time` TimeView in it). While this card is up it is clipped
     * away and the time is drawn in its place, without the pill, in its size and face. shortcut: the clock is the panel's, so it stays hidden if another card shows while
     * this one is still attached; give it back per page if that turns out to happen.
     */
    private var clockBox: View? = null
    private var clockClip: android.graphics.Rect? = null
    private val minute = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    private fun takeClock() {
        if (!isAttachedToWindow || clockBox != null) return
        fun find(name: String): View? {
            val id = resources.getIdentifier(name, "id", context.packageName)
            return if (id != 0) rootView.findViewById(id) else null
        }
        val box = find("time_parent") ?: return
        (find("time") as? android.widget.TextView)?.let { player.clockLook(it.paint) }
        clockBox = box
        clockClip = box.clipBounds
        box.clipBounds = android.graphics.Rect()
        minute.run()
    }

    private val boxAt = IntArray(2)
    private val selfAt = IntArray(2)

    /** In the pill's place and with its fade, the panel still moving it as it would. */
    private fun drawClock(c: Canvas) {
        val box = clockBox ?: return
        box.getLocationOnScreen(boxAt)
        getLocationOnScreen(selfAt)
        player.drawClock(c, boxAt[0] - selfAt[0] + box.width / 2f, boxAt[1] - selfAt[1] + box.height / 2f,
            box.alpha * ((box.parent as? View)?.alpha ?: 1f))
    }

    private fun giveClockBack() {
        removeCallbacks(minute)
        val box = clockBox ?: return
        box.clipBounds = clockClip
        clockBox = null
        clockClip = null
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
        player.draw(c, w - left, h, w)
        c.restore()
        drawClock(c)
        volume.draw(c, w, h)
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
                if (down >= 0) { player.press(down); interceptDoubleClick(); parent?.requestDisallowInterceptTouchEvent(true); return true }
                scrubbing = player.scrubStart(x, e.y)
                if (scrubbing) { interceptDoubleClick(); parent?.requestDisallowInterceptTouchEvent(true) }
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

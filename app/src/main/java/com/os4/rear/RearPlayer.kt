package com.os4.rear

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.animation.PathInterpolator
import kotlin.math.max

/**
 * The rear card's player (cover motion, flip, HyperOS icons and seek bar, text). Text beside a
 * cover 70% of the card tall leaves ~30 px at the rear's 1.58:1 past its camera, so the cover and
 * its title share the top row and the bar and controls take the full width below - HyperOS's own
 * card order.
 *
 * With HMC-E, five visualizer bars sit at the title's right end and a lyrics button under them
 * opens HMC-E's lyric over the cover and titles (LyricsFlow), the bar and
 * controls staying put.
 */
class RearPlayer(private val context: android.content.Context, private val dens: Float,
                 private val invalidate: () -> Unit) {

    /** HyperOS's own transport icons and seek bar, read out of SystemUI. */
    private val sysui = runCatching { context.createPackageContext("com.android.systemui", 0) }.getOrNull()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val iconCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) = invalidate()
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { main.postAtTime(what, who, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { main.removeCallbacks(what, who) }
    }

    private fun hyperIcon(name: String): Drawable? = runCatching {
        val ctx = sysui ?: return null
        val id = ctx.resources.getIdentifier(name, "drawable", "com.android.systemui")
        if (id == 0) null else ctx.getDrawable(id)?.mutate()?.apply { setTint(Color.WHITE); callback = iconCallback }
    }.getOrNull()

    private val prevIcon = hyperIcon("ic_media_prev")
    private val nextIcon = hyperIcon("ic_media_next")
    private var playIcon: Drawable? = null
    private var playIconFor: Boolean? = null

    private fun start(d: Drawable?) { (d as? Animatable)?.let { it.stop(); it.start() } }

    private fun playIconFor(running: Boolean): Drawable? {
        if (playIconFor != running) {
            playIconFor = running
            playIcon = hyperIcon(if (running) "ic_media_pause_animation" else "ic_media_play_animation")
            start(playIcon)
        }
        return playIcon
    }

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255); typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val rect = RectF()
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPath = android.graphics.Path()
    /** Buttons as last drawn: prev, play/pause, next, lyrics. */
    private val buttons = Array(4) { RectF() }
    private var pressed = -1
    private val m = Matrix()

    private class Cover(var bitmap: Bitmap, val key: String, val title: String, val artist: String) {
        var shader: BitmapShader? = null
        var tint = Color.BLACK
        var madeFor: Bitmap? = null
        /** Records the art for HMC-E's skip blur, kept inside the artwork. */
        val node = android.graphics.RenderNode("cover")
        /** HMC-E's CoverCardStyle.aspect: the art's own width over height, held to 1/2..2. */
        val aspect get() = if (bitmap.width > 0 && bitmap.height > 0)
            (bitmap.width.toFloat() / bitmap.height).coerceIn(1f / MAX_ASPECT, MAX_ASPECT) else 1f
    }

    /**
     * HMC-E's shownAspect: the card takes the current art's shape, eased from the previous one's
     * over the change (the flip's hold, or the crossfade), so a song then a video's cover changes
     * shape as it turns over rather than jumping.
     */
    private fun shownAspect(): Float {
        val to = current?.aspect ?: 1f
        val from = outgoing?.aspect ?: return to
        val t = ((SystemClock.uptimeMillis() - changedAt) / changeFadeMs.toFloat()).coerceIn(0f, 1f)
        if (t < 1f) invalidate()
        return from + (to - from) * (1f - (1f - t) * (1f - t) * (1f - t))
    }
    private var changeFadeMs = TRACK_FADE_MS

    private var current: Cover? = null
    private var outgoing: Cover? = null
    private var swap = 1f
    private var swapAnim: ValueAnimator? = null
    private val clear = Runnable { if (!RearMedia.shown && current != null) changeTo(null) }
    private var trackChangedAt = 0L
    private val resync = Runnable { sync() }

    /** A player can report a pause for a moment as it starts the next track (YouTube Music does). */
    private fun looksRunning() = RearMedia.running || SystemClock.uptimeMillis() - trackChangedAt < TRACK_SETTLE_MS

    fun sync() {
        val art = RearMedia.art
        val key = RearMedia.key
        val cur = current
        if (!RearMedia.shown || art == null) {
            // Players pass through a state-less moment on play/pause; only a lasting one clears.
            if (cur != null) { main.removeCallbacks(clear); main.postDelayed(clear, 1500L) }
        } else {
            main.removeCallbacks(clear)
            if (cur == null || cur.key != key) {
                if (cur != null) trackChangedAt = SystemClock.uptimeMillis()
                changeTo(Cover(art, key, RearMedia.title, RearMedia.artist))
            } else cur.bitmap = art
        }
        main.removeCallbacks(resync)
        if (!RearMedia.running && looksRunning()) main.postDelayed(resync, TRACK_SETTLE_MS)
        invalidate()
    }

    // ------------------------------------------------------------------ HMC-E's scale and skip treatment

    /**
     * HMC-E's CardSpring: full size strictly while PLAYING, [CARD_PAUSED] otherwise, on a lightly
     * underdamped spring - so a skip's buffering dips the cover and it rebounds with the new song.
     */
    private var scale = 1f
    private var scaleVelocity = 0f
    private var lastFrameNs = 0L

    private fun stepScale(target: Float, dt: Float) {
        if (dt <= 0f) return
        val frequency = 2.0 * Math.PI / CARD_RESPONSE
        val damped = frequency * kotlin.math.sqrt(1.0 - CARD_DAMPING * CARD_DAMPING)
        val x = (scale - target).toDouble()
        val b = (scaleVelocity + CARD_DAMPING * frequency * x) / damped
        val cos = kotlin.math.cos(damped * dt)
        val sin = kotlin.math.sin(damped * dt)
        val decay = kotlin.math.exp(-CARD_DAMPING * frequency * dt)
        val wave = x * cos + b * sin
        scale = (target + decay * wave).toFloat()
        scaleVelocity = (decay * (-CARD_DAMPING * frequency * wave + damped * (-x * sin + b * cos))).toFloat()
        if (kotlin.math.abs(scale - target) < 0.0005f && kotlin.math.abs(scaleVelocity) < 0.005f) {
            scale = target; scaleVelocity = 0f
        }
    }

    /**
     * HMC-E's skip wait (CoverCardLayer.beginSkipWait): from a skip pressed here until the cover's
     * first return to full size, the art blurs (2dp) and darkens (20%) by how far it has shrunk.
     */
    private var skipActive = false
    private var skipWaiting = false
    private var skipContracted = false
    private var treatment = 0f
    private val skipTimeout = Runnable { skipActive = false; skipWaiting = false; invalidate() }

    private fun beginSkip() {
        if (current == null) return
        skipActive = true; skipWaiting = true
        skipContracted = scale < 1f - 0.001f
        main.removeCallbacks(skipTimeout)
        main.postDelayed(skipTimeout, 4000L)
        invalidate()
    }

    /** @return whether the scale or the treatment still moves */
    private fun stepMotion(): Boolean {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceIn(0f, 1f / 30f)
        lastFrameNs = now
        val target = if (RearMedia.playingNow) 1f else CARD_PAUSED
        stepScale(target, dt)
        if (skipActive && scale < 1f - 0.001f) skipContracted = true
        // Finish at the first return to full size, so the spring's later bounce cannot reblur.
        if (skipActive && (!skipWaiting || skipContracted) && RearMedia.playingNow && scale >= 1f - 0.001f) {
            skipActive = false; skipWaiting = false; main.removeCallbacks(skipTimeout)
        }
        if (skipActive) treatment = ((1f - scale) / (1f - CARD_PAUSED)).coerceIn(0f, 1f)
        else {
            // Eased out, not cut, when the wait ends early.
            treatment *= kotlin.math.exp(-dt / 0.03f)
            if (treatment < 0.01f) treatment = 0f
        }
        val moving = scale != target || scaleVelocity != 0f || treatment > 0f
        if (!moving) lastFrameNs = 0L
        return moving
    }

    /** First look: no animation, the card simply has what is playing. */
    fun snap() {
        sync()
        swapAnim?.cancel(); swap = 1f; outgoing = null
        scale = if (RearMedia.playingNow) 1f else CARD_PAUSED; scaleVelocity = 0f
    }

    // ------------------------------------------------------------------ flip (HyperOS's track change)

    private var flipAngle = 0f
    private var flipTarget = 0f
    private var flipVelocity = 0f
    private var flipLastNs = 0L
    private var flipFrom: Cover? = null
    private var flipFromAngle = 0f
    private var flipStartedAt = 0L
    private var changedAt = 0L
    private val camera = android.graphics.Camera()
    private val flipMatrix = Matrix()

    private fun startFlip(from: Cover, dir: Int) {
        flipTarget = (kotlin.math.round(flipTarget / 180f) + dir) * 180f
        flipFrom = from
        flipFromAngle = flipAngle
        flipStartedAt = SystemClock.uptimeMillis()
        flipLastNs = 0L
        invalidate()
    }

    /** One step of the Folme spring (damping 0.72, response 0.5s), on the frame clock. */
    private fun stepFlip() {
        if (flipAngle == flipTarget && flipVelocity == 0f) return
        val now = System.nanoTime()
        val dt = if (flipLastNs == 0L) 1f / 120f else ((now - flipLastNs) / 1e9f).coerceIn(0f, 1f / 30f)
        flipLastNs = now
        val k = (2f * Math.PI.toFloat() / FLIP_RESPONSE).let { it * it }
        val damp = 4f * Math.PI.toFloat() * FLIP_DAMPING / FLIP_RESPONSE
        var t = 0f
        while (t < dt) {
            val h = minOf(1f / 480f, dt - t)
            flipVelocity += (-k * (flipAngle - flipTarget) - damp * flipVelocity) * h
            flipAngle += flipVelocity * h
            t += h
        }
        if (kotlin.math.abs(flipAngle - flipTarget) < 0.05f && kotlin.math.abs(flipVelocity) < 1f) {
            flipAngle = flipTarget; flipVelocity = 0f
        }
        if (flipFrom != null && kotlin.math.abs(flipAngle - flipFromAngle) >= 90f) flipFrom = null
        invalidate()
    }

    private fun flipMatrix(cx: Float, cy: Float): Matrix? {
        val a = flipAngle % 360f
        if (a == 0f) return null
        camera.save()
        camera.setLocation(0f, 0f, -1280f * dens / 72f)
        camera.rotateY(-a)
        camera.getMatrix(flipMatrix)
        camera.restore()
        val norm = ((a % 360f) + 360f) % 360f
        if (norm > 90f && norm < 270f) flipMatrix.preScale(-1f, 1f)
        flipMatrix.preTranslate(-cx, -cy)
        flipMatrix.postTranslate(cx, cy)
        return flipMatrix
    }

    private fun changeTo(next: Cover?) {
        val prev = current
        if (prev != null && next != null) {
            val recent = SystemClock.uptimeMillis() - RearMedia.lastSkipAt < 3000
            startFlip(prev, if (recent) RearMedia.lastSkip else 1)
        }
        changeFadeMs = if (prev != null && next != null) FLIP_HOLD_MS else TRACK_FADE_MS
        outgoing = current
        current = next
        changedAt = SystemClock.uptimeMillis()
        // The new art ends the wait; the treatment follows the rest of the rebound.
        if (next != null && prev != null) skipWaiting = false
        swapAnim?.cancel()
        swap = 0f
        swapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener { swap = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (swapAnim === a) outgoing = null }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ layout

    /** Draws into the free area right of the camera, [w] x [h]; [full] is the card's whole width. */
    fun draw(c: Canvas, w: Float, h: Float, full: Float) {
        val margin = h * MARGIN
        // The native card's own cover size: 66 of its 334-wide design (179 px on q200's 904).
        val side = full * 66f / 334f
        val cx = margin + side / 2f
        val cy = h * COVER_TOP + side / 2f
        // A touch under the native 12.4/66 so wide and tall covers keep their corners.
        val radius = side * 0.15f
        stepFlip()
        val out = outgoing
        val cur = current
        val inT = if (out == null) swap else ((swap - 0.15f) / 0.85f).coerceIn(0f, 1f)
        if (stepMotion()) invalidate()
        stepLyrics()
        val lt = lyricsT
        val box = h * VIS_W
        val layer = if (lt > 0f) c.saveLayerAlpha(0f, 0f, w, h * BAR_Y - h * 0.04f, (255 * (1f - lt)).toInt()) else -1
        if (lt < 1f) {
            // Opening the lyric, the cover row sinks into the middle of the panel as it fades.
            c.save()
            val k = 1f - LYRICS_SHRINK * lt
            c.scale(k, k, w / 2f, cy)
            drawCover(c, side, radius, scale, out, cur, inT, cx, cy)
            val textLeft = margin + side + h * 0.055f
            val textW = w - textLeft - margin - (if (Spectrum.connected || lyricsBtn > 0f) box * 1.3f else 0f)
            val titleBase = cy + h * 0.02f
            val outT = (swap / 0.5f).coerceIn(0f, 1f)
            drawText(c, out, textLeft, textW, titleBase, h, 1f - outT, -outT)
            drawText(c, cur, textLeft, textW, titleBase, h, inT, 1f - inT)
            c.restore()
        }
        if (layer >= 0) c.restoreToCount(layer)
        // The bars and the lyrics button keep their spot at the title row's right end.
        val sideX = w - margin - box / 2f
        drawVisualizer(c, sideX, cy - h * 0.012f, box, if (cur != null) 1f else 0f)
        drawLyricsButton(c, sideX, cy + h * 0.065f, box * LYRICS_SIDE)
        if (lt > 0f && lyricsWanted) {
            flow.sizeScale = LYRICS_SCALE
            // It grows out of the middle as the cover row leaves, and sinks back as it returns.
            c.save()
            val k = 1f - LYRICS_SHRINK * (1f - lt)
            c.scale(k, k, w / 2f, h * BAR_Y / 2f)
            // From the panel's top edge, so the rows under the sung line stay above the bar.
            if (flow.draw(c, margin, 0f, w - margin - box * 1.4f, h * BAR_Y - h * 0.05f, lt)) invalidate()
            c.restore()
        }

        val barLeft = margin
        val barW = w - 2f * margin
        drawProgress(c, barLeft, barW, h * BAR_Y, h, if (cur != null || out != null) 1f else 0f)
        drawControls(c, barLeft, barW, h * CONTROLS_Y, h)
    }

    /**
     * HMC-E's big cover on a track change (CoverCardLayer): it turns over, one face at a time -
     * the old art until edge-on (held [FLIP_HOLD_MS] at most), the new one after. Anything else
     * that brings a cover with an old one up (none here yet) crossfades over [TRACK_FADE_MS]
     * with a cubic ease-out, the wallpaper's track fade.
     */
    private fun drawCover(cc: Canvas, side: Float, radius: Float, base: Float, out: Cover?, cur: Cover?,
                          inT: Float, cx: Float, cy: Float) {
        if (flipFrom != null && SystemClock.uptimeMillis() - flipStartedAt > FLIP_HOLD_MS) flipFrom = null
        val fm = flipMatrix(cx, cy)
        if (fm != null) { cc.save(); cc.concat(fm) }
        val face = flipFrom ?: cur
        val aspect = shownAspect()
        if (flipFrom == null && out != null && cur != null && !flipping()) {
            val t = ((SystemClock.uptimeMillis() - changedAt) / TRACK_FADE_MS.toFloat()).coerceIn(0f, 1f)
            cover(cc, out, cx, cy, side, radius, base, 1f, aspect)
            cover(cc, cur, cx, cy, side, radius, base, 1f - (1f - t) * (1f - t) * (1f - t), aspect)
            if (t < 1f) invalidate()
        } else if (face != null) cover(cc, face, cx, cy, side, radius, base,
            if (out == null && flipFrom == null) inT else 1f, aspect)
        if (fm != null) cc.restore()
    }

    private fun flipping() = flipFrom != null || flipAngle % 360f != 0f

    /** The artwork's mean colour at a third of its brightness, for its shadow. */
    private fun shadowTint(b: Bitmap): Int = runCatching {
        val one = Bitmap.createScaledBitmap(b, 1, 1, true)
        val px = one.getPixel(0, 0)
        if (one !== b) one.recycle()
        val hsv = FloatArray(3)
        Color.colorToHSV(px, hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f)
        hsv[2] = hsv[2] * 0.35f
        Color.HSVToColor(hsv)
    }.getOrDefault(Color.BLACK)

    private fun cover(c: Canvas, cv: Cover, cx: Float, cy: Float, side: Float, radius: Float, scale: Float,
                      alpha: Float, aspect: Float) {
        if (alpha <= 0f) return
        val b = cv.bitmap
        if (cv.madeFor !== b) {
            cv.madeFor = b
            cv.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            cv.tint = shadowTint(b)
        }
        val s = side * scale
        // HMC-E's cardBox: the art's shape fitted inside the square, landscape full width, portrait full height.
        val w = if (aspect >= 1f) s else s * aspect
        val h = if (aspect >= 1f) s / aspect else s
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        // The shadow sinks with the cover and takes the artwork's own colour, deepened.
        val depth = ((scale - CARD_PAUSED) / (1f - CARD_PAUSED)).coerceIn(0f, 1.2f)
        val tint = cv.tint
        shadow.color = Color.argb((255 * alpha).toInt(), 0, 0, 0)
        shadow.setShadowLayer(dens * (24f + 12f * depth), 0f, dens * (1.5f + 1.5f * depth),
            Color.argb((120 * alpha).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint)))
        val inset = 2f * dens
        c.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            radius * scale, radius * scale, shadow)
        val shader = cv.shader!!
        val k = max(w / b.width, h / b.height)
        m.reset()
        m.postScale(k, k)
        m.postTranslate(rect.left + (w - b.width * k) / 2f, rect.top + (h - b.height * k) / 2f)
        shader.setLocalMatrix(m)
        artPaint.shader = shader
        artPaint.alpha = (255 * alpha).toInt()
        val r = radius * scale
        if (treatment > 0f && c.isHardwareAccelerated) {
            // HMC-E's drawWaitingArt: the art blurred by up to 2dp and darkened by up to 20%.
            val l = kotlin.math.floor(rect.left).toInt(); val t = kotlin.math.floor(rect.top).toInt()
            val node = cv.node
            node.setPosition(l, t, kotlin.math.ceil(rect.right).toInt(), kotlin.math.ceil(rect.bottom).toInt())
            val blur = treatment * 2f * dens
            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
            val rc = node.beginRecording()
            try {
                rc.translate(-l.toFloat(), -t.toFloat())
                rc.drawRoundRect(rect, r, r, artPaint)
                dim.alpha = (51f * alpha * treatment).toInt()
                rc.drawRoundRect(rect, r, r, dim)
            } finally { node.endRecording() }
            c.drawRenderNode(node)
        } else c.drawRoundRect(rect, r, r, artPaint)
        artPaint.shader = null
        // HMC-E's 1dp light rim, so a dark cover keeps its edge on a dark backdrop.
        rim.strokeWidth = dens
        rim.alpha = (64 * alpha).toInt()
        c.drawRoundRect(rect, radius * scale, radius * scale, rim)
    }

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    private fun drawText(c: Canvas, cover: Cover?, left: Float, width: Float, base: Float, h: Float,
                         alpha: Float, rise: Float) {
        if (cover == null || alpha <= 0f || width <= 0f) return
        val dy = rise * h * 0.04f
        titlePaint.textSize = h * 0.075f
        artistPaint.textSize = h * 0.055f
        titlePaint.alpha = (255 * alpha).toInt()
        artistPaint.alpha = (170 * alpha).toInt()
        c.drawText(TextUtils.ellipsize(cover.title, titlePaint, width, TextUtils.TruncateAt.END).toString(),
            left, base + dy, titlePaint)
        if (cover.artist.isNotEmpty()) c.drawText(
            TextUtils.ellipsize(cover.artist, artistPaint, width, TextUtils.TruncateAt.END).toString(),
            left, base + dy + artistPaint.textSize * 1.4f, artistPaint)
    }

    // ------------------------------------------------------------------ HMC-E's visualizer and lyric

    private val visPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var visShader: android.graphics.LinearGradient? = null
    private var visShaderKey = 0L

    /** Lifted towards white: HMC-E's colours keep the artwork's shades, lost on its own backdrop. */
    private fun brighter(c: Int): Int {
        fun ch(v: Int) = (v + (255 - v) * BAR_LIFT).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    /**
     * Five bars, drawn as HMC-E's AudioWaveDrawable draws them in a square slot: a
     * twelfth of the slot wide, spread over 7/8 of it, each 9% + 85% * level tall and never
     * shorter than wide, in the Bar color setting's gradient.
     */
    private fun drawVisualizer(c: Canvas, cx: Float, cy: Float, box: Float, alpha: Float) {
        if (!Spectrum.connected || alpha <= 0f) return
        Spectrum.step()
        if (Spectrum.available) invalidate()
        val width = box / 12f
        val pitch = (box * 0.875f - width) / 4f
        val x0 = cx - 2f * pitch - width / 2f
        val key = (Spectrum.top.toLong() shl 32) xor (Spectrum.bottom.toLong() and 0xffffffffL) xor
            java.lang.Float.floatToIntBits(cy).toLong()
        if (visShader == null || key != visShaderKey) {
            visShaderKey = key
            visShader = android.graphics.LinearGradient(0f, cy - box / 2f, 0f, cy + box / 2f,
                brighter(Spectrum.top), brighter(Spectrum.bottom), android.graphics.Shader.TileMode.CLAMP)
        }
        visPaint.shader = visShader
        visPaint.alpha = (255 * alpha).toInt()
        for (i in 0 until 5) {
            val bh = (box * (0.09f + 0.85f * Spectrum.levels[i])).coerceAtLeast(width)
            val x = x0 + i * pitch
            c.drawRoundRect(x, cy - bh / 2f, x + width, cy + bh / 2f, width / 2f, width / 2f, visPaint)
        }
        visPaint.shader = null
    }

    private val flow = LyricsFlow(dens)
    private val lyricsIcon = LyricsIcon()
    private val lyricsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var lyricsWanted = false
    private var lyricsOn = false
    private var lyricsT = 0f
    private var lyricsAnim: ValueAnimator? = null
    private var lyricsBtn = 0f
    private var lyricsBtnAnim: ValueAnimator? = null

    /**
     * The button shows while HMC-E has a lyric for this track; open, the lyric replaces the cover
     * and titles. shortcut: a song with none closes the lyric; saying "no lyrics" would need strings,
     * which the rear launcher's process does not have.
     */
    private fun stepLyrics() {
        val has = HmcLyrics.matches(RearMedia.key.substringBefore('|'), RearMedia.title)
        if (has == lyricsWanted) return
        lyricsWanted = has
        if (!has && lyricsOn) toggleLyrics()
        lyricsBtnAnim?.cancel()
        lyricsBtnAnim = ValueAnimator.ofFloat(lyricsBtn, if (has) 1f else 0f).apply {
            duration = if (has) 260L else 200L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { lyricsBtn = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun toggleLyrics() {
        if (!lyricsOn && !lyricsWanted) return
        lyricsOn = !lyricsOn
        lyricsAnim?.cancel()
        lyricsAnim = ValueAnimator.ofFloat(lyricsT, if (lyricsOn) 1f else 0f).apply {
            duration = 380L
            interpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener { lyricsT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun drawLyricsButton(c: Canvas, cx: Float, cy: Float, side: Float) {
        if (lyricsBtn <= 0f) { buttons[3].setEmpty(); return }
        val k = (APPEAR_SCALE + (1f - APPEAR_SCALE) * lyricsBtn) * (if (pressed == 3) 0.85f else 1f)
        lyricsPaint.alpha = (255 * lyricsBtn).toInt()
        lyricsIcon.draw(c, cx, cy, side * k, lyricsOn, lyricsPaint)
        buttons[3].set(cx - side * 0.9f, cy - side * 0.9f, cx + side * 0.9f, cy + side * 0.9f)
    }

    // ------------------------------------------------------------------ clock

    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
    private var clockOn = false

    /** The panel's clock is drawn here instead: its text size and face, in white. */
    fun clockLook(native: Paint) {
        clockPaint.textSize = native.textSize
        clockPaint.typeface = native.typeface
        clockOn = true
    }

    /** The time as the panel's TimeView writes it, centred on [cx], [cy]. */
    fun drawClock(c: Canvas, cx: Float, cy: Float, alpha: Float) {
        if (!clockOn || alpha <= 0f) return
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        clockPaint.textAlign = Paint.Align.CENTER
        clockPaint.alpha = (255 * alpha).toInt()
        val fm = clockPaint.fontMetrics
        c.drawText(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern(pattern)),
            cx, cy - (fm.ascent + fm.descent) / 2f, clockPaint)
    }

    // ------------------------------------------------------------------ progress

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, 500, false) }
    private val bar = RectF()
    private var grip = 0f
    private var gripAnim: ValueAnimator? = null
    private var scrub = Float.NaN
    private var seekHold = Float.NaN
    private var seekHoldUntil = 0L
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    /** HyperOS 4's seek bar: SystemUI's music_player_tracker shader with its glowing head. */
    private val trackShader: android.graphics.RuntimeShader? by lazy {
        runCatching {
            val res = sysui!!.resources
            val raw = res.getIdentifier("music_player_tracker", "raw", "com.android.systemui")
            val img = res.getIdentifier("miuix_hyper_progressbar_light_head", "drawable", "com.android.systemui")
            val src = res.openRawResource(raw).bufferedReader().use { it.readText() }
            val head = android.graphics.BitmapFactory.decodeResource(res, img)
            android.graphics.RuntimeShader(src).apply {
                setInputShader("uTex", BitmapShader(head, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                setFloatUniform("uHeadSize", head.width.toFloat(), head.height.toFloat())
                setFloatUniform("uHeadGlowAlpha", GLOW / BAR_GAIN)
                setFloatUniform("uTime", 0f)
                setIntUniform("uIsRtl", 0)
            }
        }.getOrNull()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barGain = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(
        floatArrayOf(BAR_GAIN, 0f, 0f, 0f, 0f, 0f, BAR_GAIN, 0f, 0f, 0f, 0f, 0f, BAR_GAIN, 0f, 0f,
            0f, 0f, 0f, BAR_GAIN, 0f)))
    private var barF = Float.NaN
    private var glideFrom = 0f
    private var glideAt = 0L
    private val glideEase = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var lastDuration = 0L
    private var lastDurationAt = 0L

    private fun drawProgress(c: Canvas, left: Float, width: Float, y: Float, h: Float, alpha: Float) {
        val now = SystemClock.uptimeMillis()
        // A new track that has not reported its length yet keeps the old one for a moment.
        var d = RearMedia.duration
        if (d > 0L) { lastDuration = d; lastDurationAt = now } else if (now - lastDurationAt < 2000L) d = lastDuration
        if (alpha <= 0f || d <= 0L || width <= 0f) { bar.setEmpty(); return }
        val barRight = left + width
        bar.set(left, y - h * 0.04f, barRight, y + h * 0.04f)
        val live = RearMedia.positionNow().toFloat() / d
        if (!seekHold.isNaN() && (now > seekHoldUntil || kotlin.math.abs(live - seekHold) < 0.01f)) seekHold = Float.NaN
        val target = (if (!scrub.isNaN()) scrub else if (!seekHold.isNaN()) seekHold else live).coerceIn(0f, 1f)
        // A jump the bar did not make itself glides there, as HyperOS's bar runs back to 0; a finger's never.
        if (scrub.isNaN() && !barF.isNaN() && kotlin.math.abs(target - barF) > GLIDE_JUMP && now - glideAt >= GLIDE_MS) {
            glideFrom = barF; glideAt = now
        }
        val g = ((now - glideAt) / GLIDE_MS.toFloat()).coerceIn(0f, 1f)
        val f = if (scrub.isNaN() && g < 1f) glideFrom + (target - glideFrom) * glideEase.getInterpolation(g) else target
        if (g < 1f) invalidate()
        barF = f

        val thick = dens * (6f + 6f * grip)
        val rs = trackShader
        if (rs != null) {
            val cw = barRight - left
            val ch = thick * 38f / 6f
            rs.setFloatUniform("uResolution", cw, ch)
            rs.setFloatUniform("uTrackCanvasSize", cw, ch)
            rs.setFloatUniform("uTrackPosition", 0f, ch / 2f)
            rs.setFloatUniform("uTrackSize", cw, thick)
            rs.setFloatUniform("uTrackProgress", f)
            trackPaint.shader = rs
            trackPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            trackPaint.colorFilter = barGain
            c.save()
            c.translate(left, y - ch / 2f)
            c.drawRect(0f, 0f, cw, ch, trackPaint)
            c.restore()
        } else drawOwnTrack(c, left, barRight, y, thick, thick / 2f, f, alpha)

        timePaint.textSize = h * 0.034f
        timePaint.color = Color.argb(((170 + 70 * grip) * alpha).toInt(), 255, 255, 255)
        val ty = y + thick / 2f + h * 0.05f
        timePaint.textAlign = Paint.Align.LEFT
        c.drawText(clock((f * d).toLong()), left, ty, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        c.drawText(clock(d), barRight, ty, timePaint)
    }

    /** The fallback when SystemUI's shader cannot be had. */
    private fun drawOwnTrack(c: Canvas, barLeft: Float, barRight: Float, y: Float, thick: Float, r: Float, f: Float, alpha: Float) {
        track.strokeWidth = thick
        track.shader = null
        track.color = Color.argb((38 * alpha).toInt(), 255, 255, 255)
        c.drawLine(barLeft + r, y, barRight - r, y, track)
        val head = barLeft + r + (barRight - barLeft - thick) * f
        if (f <= 0f) return
        val a = (128 + (230 - 128) * grip) / 255f * alpha
        track.shader = android.graphics.LinearGradient(barLeft, y, head + r, y,
            Color.argb((255 * a * 0.45f).toInt(), 255, 255, 255), Color.argb((255 * a).toInt(), 255, 255, 255),
            Shader.TileMode.CLAMP)
        c.drawLine(barLeft + r, y, head, y, track)
        track.shader = null
        glow.color = Color.argb((255 * alpha).toInt(), 255, 255, 255)
        glow.setShadowLayer(dens * 6f, 0f, 0f, Color.argb((200 * alpha).toInt(), 255, 255, 255))
        c.drawCircle(head, y, r, glow)
    }

    private fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
    }

    private fun setGrip(on: Boolean) {
        gripAnim?.cancel()
        gripAnim = ValueAnimator.ofFloat(grip, if (on) 1f else 0f).apply {
            duration = 220
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener { grip = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** A finger down on the bar (with its own height of slack around it) starts a scrub. */
    fun scrubStart(x: Float, y: Float): Boolean {
        if (bar.isEmpty) return false
        val slack = bar.height()
        if (y < bar.top - slack || y > bar.bottom + slack || x < bar.left - slack || x > bar.right + slack) return false
        setGrip(true)
        scrubMove(x)
        return true
    }

    fun scrubMove(x: Float) { scrub = ((x - bar.left) / bar.width()).coerceIn(0f, 1f); invalidate() }

    fun scrubEnd(commit: Boolean) {
        val f = scrub
        scrub = Float.NaN
        setGrip(false)
        val d = RearMedia.duration
        if (!commit || f.isNaN() || d <= 0L) return
        seekHold = f
        seekHoldUntil = SystemClock.uptimeMillis() + 1500
        RearMedia.seekTo((f * d).toLong())
    }

    // ------------------------------------------------------------------ controls

    /** Play/pause in the middle, previous/next [SKIP_SPREAD] of the bar's width to either side. */
    private fun drawControls(c: Canvas, left: Float, width: Float, y: Float, h: Float) {
        val size = h * 0.15f
        for (i in 0..2) {
            val cx = left + width * (0.5f + (i - 1) * SKIP_SPREAD)
            buttons[i].set(cx - size, y - size, cx + size, y + size)
            val k = if (pressed == i) 0.85f else 1f
            c.save()
            c.scale(k, k, cx, y)
            val d = when (i) { 0 -> prevIcon; 1 -> playIconFor(looksRunning()); else -> nextIcon }
            if (d != null) {
                val half = size * 0.6f
                d.setBounds((cx - half).toInt(), (y - half).toInt(), (cx + half).toInt(), (y + half).toInt())
                d.draw(c)
            } else when (i) {
                0 -> { triangle(c, cx + size * 0.05f, y, size * 0.45f, -1); triangle(c, cx - size * 0.4f, y, size * 0.45f, -1) }
                1 -> if (RearMedia.running) {
                    val bw = size * 0.22f
                    c.drawRoundRect(cx - size * 0.38f, y - size * 0.45f, cx - size * 0.38f + bw, y + size * 0.45f, bw / 3, bw / 3, icon)
                    c.drawRoundRect(cx + size * 0.38f - bw, y - size * 0.45f, cx + size * 0.38f, y + size * 0.45f, bw / 3, bw / 3, icon)
                } else triangle(c, cx - size * 0.25f, y, size * 0.6f, 1)
                else -> { triangle(c, cx - size * 0.05f, y, size * 0.45f, 1); triangle(c, cx + size * 0.4f, y, size * 0.45f, 1) }
            }
            c.restore()
        }
    }

    private fun triangle(c: Canvas, x: Float, y: Float, len: Float, dir: Int) {
        iconPath.reset()
        iconPath.moveTo(x, y - len * 0.6f)
        iconPath.lineTo(x + dir * len, y)
        iconPath.lineTo(x, y + len * 0.6f)
        iconPath.close()
        c.drawPath(iconPath, icon)
    }

    fun buttonAt(x: Float, y: Float) = buttons.indexOfFirst { it.contains(x, y) }
    fun press(i: Int) { pressed = i; invalidate() }

    fun release(i: Int, fire: Boolean) {
        pressed = -1
        invalidate()
        if (!fire) return
        when (i) {
            0 -> { start(prevIcon); beginSkip(); RearMedia.previous() }
            1 -> RearMedia.playPause()
            2 -> { start(nextIcon); beginSkip(); RearMedia.next() }
            3 -> toggleLyrics()
        }
    }

    companion object {
        /** HMC-E's CardSpring: paused size, damping, and its default response (EASE_COVER, s). */
        const val CARD_PAUSED = 0.90f
        const val CARD_DAMPING = 0.56
        const val CARD_RESPONSE = 0.38
        /** HMC-E's CoverCardLayer: the old art held this long at most, and the track crossfade. */
        const val FLIP_HOLD_MS = 600L
        const val TRACK_FADE_MS = 180L
        /** HMC-E's CoverCardStyle.MAX_ASPECT: longer art is cropped to 2:1 (or 1:2). */
        const val MAX_ASPECT = 2f
        const val FLIP_DAMPING = 0.72f
        const val FLIP_RESPONSE = 0.5f
        const val BAR_GAIN = 1.45f
        const val GLOW = 0.5f
        const val GLIDE_JUMP = 0.02f
        const val GLIDE_MS = 450L
        const val TRACK_SETTLE_MS = 2_000L
        const val SKIP_SPREAD = 0.36f
        /** The rear layout, as shares of the card's height. */
        const val MARGIN = 0.075f
        // The cover and titles sit as far above the bar as the controls sit below it (73 px on q200).
        const val COVER_TOP = 0.208f
        const val BAR_Y = 0.66f
        const val CONTROLS_Y = 0.85f
        /** The visualizer slot (share of the height), and the lyrics button against it. */
        const val VIS_W = 0.1f
        const val LYRICS_SIDE = 0.8f
        const val APPEAR_SCALE = 0.6f
        const val BAR_LIFT = 0.45f
        /** HMC-E's lyric size scaled down for the rear's small panel. */
        const val LYRICS_SCALE = 0.45f
        /** Opening the lyric: how far the cover row sinks into the middle and the lyric grows out of it. */
        const val LYRICS_SHRINK = 0.08f
    }
}

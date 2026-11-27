package com.os4.rear

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The lyric, moving the way HMC-E's LyricView moves it (its constants, below), cut down to what
 * the rear card needs, and drawn with the user's HMC-E lyric settings ([HmcLyrics.Look]): size,
 * weight, alignment, depth blur, alive effects, and the romanisation/translation rows those
 * settings show. Every value belongs to a line:
 * - Position: each line springs to its place once the focus moves, the ones further down setting
 *   off [RIPPLE_MS] after the one above, so the stack flows up; stiffer the closer two lines are.
 * - Emphasis: the line in focus (up to a second before it is sung) brightens quickly and grows from [INACTIVE_SCALE]; it drops at once.
 * - Depth: lines away from the sung one blur by their distance; they run on to the screen's edge.
 * - Words: with word timing the sung part fills in through a soft edge, each syllable rising a
 *   little as it is sung and a held note glowing (LyricView.drawOriginalSyllables).
 * - Gentle lyric flow (RapidLyricGroups): up to [GROUP_MAX] lines in quick succession stand still
 *   as one block, centred, and stay lit while only the highlight moves through them.
 * shortcut: no alive word effects beyond breathing, HDR, interlude dots or duet staging; add
 * them from LyricView.
 */
class LyricsFlow(private val dens: Float) {
    /**
     * Idle at its slowest refresh: no scrolling or fading between lines, and a
     * word-timed line lit whole rather than filled word by word.
     */
    var idle = false
    /** The rear's panel is small: HMC-E's lyric size times this. */
    var sizeScale = 1f
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, TRANS_WEIGHT, false)
    }
    private var layouts: List<StaticLayout> = emptyList()
    /** The romanisation and translation rows under each line, as HMC-E's settings show them. */
    private var subs: List<List<StaticLayout>> = emptyList()
    /** Each line's whole height, rows under it included. */
    private var heights = FloatArray(0)
    private var builtFor = -1
    private var builtW = 0
    private var builtSize = 0f
    /** Per line: offset from its place, speed, emphasis, scale, blur, and when it may move. */
    private var y = FloatArray(0)
    private var v = FloatArray(0)
    private var emph = FloatArray(0)
    private var lit = FloatArray(0)
    private var scale = FloatArray(0)
    private var blur = FloatArray(0)
    private var go = LongArray(0)
    private var k = K_MAX
    private var damp = DAMPING_MULT * sqrt(K_MAX)
    private var focus = -1
    private var lastFrame = 0L
    /** Each line's top in the column, relative to the first. */
    private var tops = FloatArray(0)
    /** Where the column is scrolled to: a line's top minus this is where it is drawn at rest. */
    private var scrolled = 0f
    /** Each line's quick group, first and last line (itself when none); see [grouped]. */
    private var heads = IntArray(0)
    private var tails = IntArray(0)
    /** The part of a group on screen together, while the focus is in one. */
    private var readFirst = -1
    private var readLast = -1

    /** Draws into [left, right] x [top, bottom]; true while something still moves. */
    fun draw(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Float): Boolean {
        val lines = HmcLyrics.lines
        if (lines.isEmpty()) return false
        val w = (right - left).toInt()
        val look = HmcLyrics.look
        val size = look.sizeSp * dens * sizeScale
        if (builtFor != HmcLyrics.version || builtW != w || builtSize != size) build(lines, w, size)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val pos = RearMedia.positionNow()
        // Idle at a frame a second, nothing eases: lines go straight to their place and look.
        val step = if (idle) 1_000f else dt
        val sung = HmcLyrics.indexAt(pos)
        // The stack moves to a line a little before its first word, as Apple's does, and the
        // emphasis with it (LyricView.switchAt): never before the line ahead of it has ended.
        val next = if (sung + 1 < lines.size && pos >= switchAt(lines, sung + 1)) sung + 1
            else sung.coerceAtLeast(0)
        // Gentle lyric flow: a group stands still as a block, centred, while it is sung.
        var rf = next; var rl = next
        if (grouped(next)) {
            var first = heads[next]
            val end = tails[next]
            while (first <= end) {
                var last = first
                while (last < end && tops[last + 1] - tops[first] + heights[last + 1] <= bottom - top) last++
                if (next <= last) { rf = first; rl = last; break }
                first = last + 1
            }
        }
        if (next != focus || rf != readFirst || rl != readLast) {
            readFirst = rf
            readLast = rl
            val blockH = tops[rl] - tops[rf] + heights[rl]
            val to = if (rl > rf) tops[rf] - (top + (bottom - top - blockH) / 2f)
                else tops[next] - (top + (bottom - top) * ANCHOR)
            refocus(next, to, lines, now)
        }

        val gap = GAP_DP * dens
        var moving = false
        c.save()
        c.clipRect(left - gap, top, right + gap, bottom)
        for (i in lines.indices) {
            // Springs: offsets run down to 0 once each line's turn comes.
            if (idle) { y[i] = 0f; v[i] = 0f }
            else if (now >= go[i] && (y[i] != 0f || v[i] != 0f)) {
                v[i] += (-k * y[i] - damp * v[i]) * dt
                y[i] += v[i] * dt
                if (abs(y[i]) < 0.3f && abs(v[i]) < 3f) { y[i] = 0f; v[i] = 0f } else moving = true
            } else if (y[i] != 0f) moving = true
            val e = if (i == focus || (readLast > readFirst && i in readFirst until focus)) 1f else 0f
            emph[i] = approach(emph[i], e, if (e > emph[i]) TAU_EMPH_IN else TAU_EMPH_OUT, step)
            // A line-timed line lights when it is sung, not when the stack arrives early.
            val litTo = if (e == 1f && (lines[i].timed || pos >= lines[i].start)) 1f else 0f
            lit[i] = approach(lit[i], litTo, if (litTo > lit[i]) TAU_EMPH_IN else TAU_EMPH_OUT, step)
            scale[i] = approach(scale[i], INACTIVE_SCALE + (1f - INACTIVE_SCALE) * e, TAU_SCALE, step)
            val d = abs(i - focus)
            val wantBlur = look.depthBlur / 100f * (if (d == 0 || emph[i] > 0.01f) 0f
                else if (d == 1) BLUR_NEXT_DP else BLUR_NEXT_DP + BLUR_DP_PER_ROW * (min(d, BLUR_MAX_ROWS) - 1))
            blur[i] = approach(blur[i], wantBlur, if (wantBlur > blur[i]) TAU_BLUR_OUT else TAU_BLUR_IN, step)
            if (abs(emph[i] - e) > 0.002f || abs(lit[i] - litTo) > 0.002f || abs(blur[i] - wantBlur) > 0.02f) moving = true

            val l = layouts[i]
            val lh = heights[i]
            val ly = tops[i] - scrolled + y[i]
            if (ly > bottom || ly + lh < top) continue
            val above = if (i < focus && emph[i] < 0.01f) ABOVE_ALPHA else 1f
            val bright = INACTIVE + (1f - INACTIVE) * lit[i]
            val a = alpha * above * (1f - BLUR_ALPHA_PER_DP * blur[i]).coerceAtLeast(0.2f)
            if (a <= 0.004f) continue
            // Out of focus as a layer: a mask filter on hardware-drawn text flickered frame to frame.
            val bpx = blur[i] * dens
            val layered = bpx >= 0.5f && c.isHardwareAccelerated
            val pad = 3f * bpx
            val node = if (layered) nodeFor(i) else null
            val lc = if (node != null) {
                node.setPosition(0, 0, (right - left + 2 * pad).toInt(), (lh + 2 * pad).toInt())
                node.beginRecording().also { it.translate(pad, pad) }
            } else c.also { it.save(); it.translate(left, ly) }
            val fill = if (!idle && lines[i].timed && (e == 1f || emph[i] > 0.01f)) fillAt(lines[i], pos) else Float.NaN
            val breathe = breathing(look.aliveFx, i == focus, lines[i], pos, if (fill.isNaN()) 0f else fill)
            if (breathe != 1f) moving = true
            val px = if (look.align == 1) l.width / 2f
                else if ((look.align == 2) != lines[i].opposite) l.width.toFloat() else 0f
            lc.scale(scale[i] * breathe, scale[i] * breathe, px, lh / 2f)
            if (fill.isNaN()) {
                paint.shader = null
                paint.alpha = (255 * a * bright).toInt()
                l.draw(lc)
            } else {
                drawFilled(lc, l, lines[i], pos, fill, a, bright, emph[i], size)
                moving = true
            }
            // The rows under it: dimmer, never filled.
            var sy = l.height.toFloat()
            for (sub in subs[i]) {
                sy += TRANS_GAP_DP * dens
                lc.save()
                lc.translate(0f, sy)
                subPaint.alpha = (255 * a * bright * TRANS_ALPHA).toInt()
                sub.draw(lc)
                lc.restore()
                sy += sub.height
            }
            if (node != null) {
                node.endRecording()
                node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(bpx, bpx, Shader.TileMode.DECAL))
                c.save()
                c.translate(left - pad, ly - pad)
                c.drawRenderNode(node)
                c.restore()
            } else c.restore()
        }
        paint.shader = null
        c.restore()
        return moving
    }

    private fun build(lines: List<HmcLyrics.Line>, w: Int, size: Float) {
        builtFor = HmcLyrics.version
        builtW = w
        builtSize = size
        val look = HmcLyrics.look
        paint.textSize = size
        paint.typeface = Typeface.create(Typeface.DEFAULT, look.weight, false)
        subPaint.textSize = size * TRANS_SP / TEXT_SP
        fun lay(text: String, tp: TextPaint, opposite: Boolean): StaticLayout {
            val right = look.align == 2 != opposite
            val align = if (look.align == 1) android.text.Layout.Alignment.ALIGN_CENTER
                else if (right) android.text.Layout.Alignment.ALIGN_OPPOSITE else android.text.Layout.Alignment.ALIGN_NORMAL
            return StaticLayout.Builder.obtain(text, 0, text.length, tp, w.coerceAtLeast(1))
                .setAlignment(align).setIncludePad(false)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED).build()
        }
        layouts = lines.map { lay(it.text, paint, it.opposite) }
        subs = lines.map { l -> listOfNotNull(l.roma, l.translation).map { lay(it, subPaint, l.opposite) } }
        heights = FloatArray(lines.size) { i ->
            layouts[i].height + subs[i].sumOf { (it.height + TRANS_GAP_DP * dens).toDouble() }.toFloat()
        }
        val old = tops
        tops = FloatArray(lines.size)
        var t = 0f
        for (i in lines.indices) { tops[i] = t; t += heights[i] + GAP_DP * dens }
        heads = IntArray(lines.size)
        tails = IntArray(lines.size)
        var first = 0
        while (first < lines.size) {
            var last = first
            while (last + 1 < lines.size && last - first + 1 < GROUP_MAX && quick(lines[last], lines[last + 1])) last++
            heads.fill(first, first, last + 1)
            tails.fill(last, first, last + 1)
            first = last + 1
        }
        nodes.clear()
        // The same lyric sent again (a translation arriving, a setting changed): every line
        // keeps its motion and emphasis, and stays where it is drawn as the heights change.
        if (old.size == lines.size && focus >= 0) {
            val f = focus.coerceAtMost(lines.size - 1)
            for (i in lines.indices) y[i] += (old[i] - old[f]) - (tops[i] - tops[f])
            scrolled += tops[f] - old[f]
            return
        }
        y = FloatArray(lines.size); v = FloatArray(lines.size)
        emph = FloatArray(lines.size); lit = FloatArray(lines.size); blur = FloatArray(lines.size)
        scale = FloatArray(lines.size) { INACTIVE_SCALE }
        go = LongArray(lines.size)
        focus = -1
        readFirst = -1
        readLast = -1
        lastFrame = 0L
    }

    /** RapidLyricGroups.quick: two lines sung in quick succession, neither a duet's nor a break. */
    private fun quick(prev: HmcLyrics.Line, next: HmcLyrics.Line): Boolean {
        val iv = next.start.toLong() - prev.start
        return iv in 1..GROUP_QUICK_MS && !prev.opposite && !next.opposite &&
            prev.end <= next.start.toLong() + 150 && next.start.toLong() - prev.end <= 600 &&
            prev.text.isNotBlank() && next.text.isNotBlank()
    }

    private fun grouped(i: Int) = HmcLyrics.look.groups && i in heads.indices && heads[i] != tails[i]

    /** When the focus may move to line [i]: a second early, once the line before has ended. */
    private fun switchAt(lines: List<HmcLyrics.Line>, i: Int): Long {
        // In a quick group only the highlight advances, exactly when the next line is sung.
        if (grouped(i) || grouped(i - 1)) return lines[i].start.toLong()
        val early = lines[i].start - LEAD_MS
        return if (i == 0) early else minOf(lines[i].start.toLong(), maxOf(early, lines[i - 1].end.toLong()))
    }

    /**
     * The focus moves to [next] and the column to [to] ([scrolled]): each line keeps where it is
     * drawn and springs from there.
     */
    private fun refocus(next: Int, to: Float, lines: List<HmcLyrics.Line>, now: Long) {
        val old = focus
        val shift = to - scrolled
        focus = next
        scrolled = to
        if (old < 0) return
        val seek = next < old || next - old > 2
        if (seek) { k = K_SEEK; damp = C_SEEK } else {
            val iv = (lines[next].start - lines[old].start).coerceIn(IV_MIN, IV_MAX)
            k = K_MAX - (K_MAX - K_MIN) * (iv - IV_MIN) / (IV_MAX - IV_MIN).toFloat()
            damp = DAMPING_MULT * sqrt(k)
        }
        var delay = 0f
        var step = RIPPLE_MS
        val first = (old - 3).coerceAtLeast(0)
        for (i in lines.indices) {
            y[i] += shift
            if (seek || i < first) { go[i] = now; continue }
            go[i] = now + delay.toLong()
            delay += step
            if (i >= next) step *= RIPPLE_DECAY
        }
    }

    /** How far through [line] the singing is, in characters (fractional), or NaN before it. */
    private fun fillAt(line: HmcLyrics.Line, pos: Long): Float {
        val st = line.sylStart!!; val en = line.sylEnd!!; val ce = line.charEnd!!
        var chars = 0f
        for (s in st.indices) {
            val from = if (s == 0) 0 else ce[s - 1]
            if (pos < st[s]) break
            val f = if (en[s] <= st[s]) 1f else ((pos - st[s]).toFloat() / (en[s] - st[s])).coerceIn(0f, 1f)
            chars = from + (ce[s] - from) * f
        }
        return chars
    }

    /**
     * Each row bright up to the fill and at the inactive level after it, through a soft edge;
     * syllable by syllable, each risen by how far it has been sung and a held one glowing, both
     * as much as the line is emphasised ([e]).
     */
    private fun drawFilled(c: Canvas, l: StaticLayout, line: HmcLyrics.Line, pos: Long, chars: Float,
                           a: Float, bright: Float, e: Float, size: Float) {
        val feather = FEATHER_EM * size
        val lit = Color.argb((255 * a * bright).toInt(), 255, 255, 255)
        val dim = Color.argb((255 * a * INACTIVE).toInt(), 255, 255, 255)
        val ci = chars.toInt().coerceIn(0, l.text.length)
        val frac = chars - ci
        val fillRow = l.getLineForOffset(ci)
        paint.alpha = 255
        for (r in 0 until l.lineCount) {
            paint.shader = null
            paint.color = when {
                r < fillRow -> lit
                r > fillRow -> dim
                else -> Color.WHITE
            }
            if (r == fillRow) {
                val x0 = l.getPrimaryHorizontal(ci)
                // The row's last character fills toward the row's own end: the next offset is
                // already on the row below, and its x would pull the fill back across this one.
                val x1 = if (ci + 1 < l.getLineEnd(r)) l.getPrimaryHorizontal(ci + 1) else l.getLineRight(r)
                val x = x0 + (x1 - x0) * frac
                paint.shader = LinearGradient(x - feather, 0f, x + feather, 0f, lit, dim, Shader.TileMode.CLAMP)
            }
            drawSyllables(c, l, line, r, pos, e, a)
        }
        paint.shader = null
        paint.color = Color.WHITE
    }

    /** One row's syllables, as LyricView.drawOriginalSyllables draws them. */
    private fun drawSyllables(c: Canvas, l: StaticLayout, line: HmcLyrics.Line, r: Int, pos: Long, e: Float, a: Float) {
        val st = line.sylStart!!; val en = line.sylEnd!!; val ce = line.charEnd!!
        val rs = l.getLineStart(r); val re = l.getLineEnd(r)
        val baseline = l.getLineBaseline(r).toFloat()
        val text = l.text
        var drawn = rs
        for (k in st.indices) {
            val cs = maxOf(if (k == 0) 0 else ce[k - 1], rs).coerceAtMost(text.length)
            val cEnd = minOf(ce[k], re, text.length)
            if (cEnd <= cs) continue
            val s = st[k]; val end = en[k]; val dur = end - s
            val rise = if (pos <= s) 0f else ((pos - s).toFloat() / maxOf(dur, LIFT_MIN_MS)).coerceIn(0f, 1f)
            val lift = LIFT_DP * dens * e * (1f - (1f - rise) * (1f - rise))
            var glow = 0f
            if (dur >= GLOW_MIN_MS && pos > s) {
                glow = if (pos < end) ((pos - s) / (dur * 0.3f)).coerceIn(0f, 1f)
                    else 1f - ((pos - end).toFloat() / GLOW_TAIL_MS).coerceIn(0f, 1f)
                glow *= e
            }
            val x0 = l.getPrimaryHorizontal(cs)
            if (glow > 0.01f) {
                val x1 = if (cEnd < re) l.getPrimaryHorizontal(cEnd) else l.getLineRight(r)
                c.save()
                val swell = 1f + GLOW_SWELL * glow
                c.scale(swell, swell, (x0 + x1) / 2f, baseline)
                paint.setShadowLayer(GLOW_DP * dens * glow, 0f, 0f,
                    Color.argb((255 * GLOW_ALPHA * glow * a).toInt(), 255, 255, 255))
                c.drawTextRun(text, cs, cEnd, rs, re, x0, baseline - lift, false, paint)
                paint.clearShadowLayer()
                c.restore()
            } else c.drawTextRun(text, cs, cEnd, rs, re, x0, baseline - lift, false, paint)
            drawn = cEnd
        }
        // Text past the last timed syllable, if the timing stops short of the row's end.
        if (drawn < re) c.drawTextRun(text, drawn, re, rs, re, l.getPrimaryHorizontal(drawn), baseline, false, paint)
    }

    /**
     * Alive effects (HMC-E's AliveLyricsEffects.breathingScale): with them on, the line being
     * sung swells and shrinks a little as it goes, more in the dramatic modes.
     */
    private fun breathing(mode: Int, active: Boolean, l: HmcLyrics.Line, ms: Long, sungChars: Float): Float {
        if (mode <= 0 || !active || !RearMedia.running) return 1f
        val span = maxOf(1f, (l.end - l.start).toFloat())
        val progress = ((ms - l.start) / span).coerceIn(0f, 1f)
        val cycles = if (mode >= 2) 1.9f else 1.35f
        val phase = (Math.PI * 2 * (progress * cycles + sungChars * 0.015f)).toFloat()
        val pulse = 0.5f + 0.5f * kotlin.math.sin(phase)
        return 1f + (if (mode >= 2) 0.018f else 0.012f) * pulse
    }

    private val nodes = HashMap<Int, android.graphics.RenderNode>()
    private fun nodeFor(i: Int) = nodes.getOrPut(i) { android.graphics.RenderNode("lyric$i") }

    /** Eases [from] toward [to], landing on it once close, so a settled lyric asks for no frames. */
    private fun approach(from: Float, to: Float, tau: Float, dt: Float): Float {
        if (dt <= 0f) return from
        val next = to + (from - to) * exp(-dt / tau)
        return if (abs(next - to) < 0.002f) to else next
    }

    companion object {
        // LyricView's values.
        private const val TEXT_SP = 25f
        private const val TRANS_SP = 15f
        private const val TRANS_WEIGHT = 500
        private const val TRANS_GAP_DP = 5f
        private const val TRANS_ALPHA = 0.62f
        private const val GAP_DP = 22f
        private const val INACTIVE = 0.40f
        private const val ABOVE_ALPHA = 0.6f
        private const val INACTIVE_SCALE = 0.97f
        private const val FEATHER_EM = 0.45f
        private const val ANCHOR = 0.40f
        private const val BLUR_NEXT_DP = 0.9f
        private const val BLUR_DP_PER_ROW = 1.6f
        private const val BLUR_MAX_ROWS = 4
        private const val TAU_BLUR_IN = 0.05f
        private const val TAU_BLUR_OUT = 0.07f
        private const val BLUR_ALPHA_PER_DP = 0.12f
        private const val K_MIN = 170f
        private const val K_MAX = 220f
        private const val IV_MIN = 100
        private const val IV_MAX = 800
        private const val DAMPING_MULT = 2.2f
        private const val K_SEEK = 120f
        private const val C_SEEK = 24f
        private const val RIPPLE_MS = 50f
        private const val RIPPLE_DECAY = 1f / 1.05f
        private const val LEAD_MS = 1000L
        private const val TAU_EMPH_IN = 0.08f
        private const val TAU_EMPH_OUT = 0.016f
        private const val TAU_SCALE = 0.15f
        private const val LIFT_DP = 1.8f
        private const val LIFT_MIN_MS = 320
        private const val GLOW_MIN_MS = 1000
        private const val GLOW_TAIL_MS = 380
        private const val GLOW_DP = 9f
        private const val GLOW_ALPHA = 0.55f
        private const val GLOW_SWELL = 0.045f
        // RapidLyricGroups' values.
        private const val GROUP_QUICK_MS = 1200L
        private const val GROUP_MAX = 4
    }
}

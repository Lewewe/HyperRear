package com.os4.rear

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The current track's lyric, as HyperMusicCover-Enhanced settled it (`op extspectrum`, code 3 -
 * see [Spectrum]). HMC-E does the looking up; the rear card only draws what it is handed. Empty when
 * the track has none, HMC-E is absent, or its lyrics are off.
 */
object HmcLyrics {
    /**
     * Word timing as HMC-E's LyricLine has it, or null for a line-timed file: syllable k covers
     * the characters [charEnd[k-1], charEnd[k]) and is sung from sylStart[k] to sylEnd[k].
     */
    class Line(val text: String, val start: Int, val end: Int, val opposite: Boolean,
               val sylStart: IntArray?, val sylEnd: IntArray?, val charEnd: IntArray?,
               /** The rows under the line as HMC-E's settings show them; null when off. */
               val roma: String?, val translation: String?) {
        val timed get() = sylStart != null && sylEnd != null && charEnd != null &&
            sylStart.isNotEmpty() && sylStart.size == sylEnd.size && sylStart.size == charEnd.size
    }

    /**
     * HMC-E's lyric settings: text size (sp) and weight, alignment (0 left, 1 centre, 2 right),
     * depth blur in % of its default, the alive effects mode (0 off .. 3 eye candy), and Gentle
     * lyric flow.
     */
    class Look(val sizeSp: Float, val weight: Int, val align: Int, val depthBlur: Int, val aliveFx: Int,
               val groups: Boolean = false)

    @Volatile var lines: List<Line> = emptyList()
        private set
    @Volatile var look = Look(25f, 500, 0, 100, 0)
        private set
    /** The player and title HMC-E found this lyric for. */
    @Volatile private var pkg = ""
    @Volatile private var title = ""
    /** Bumped with each new set, so a page can tell it has a different lyric or look. */
    @Volatile var version = 0
        private set

    fun set(l: List<Line>, k: Look = look, p: String = pkg, t: String = title) {
        lines = l
        look = k
        pkg = p
        title = t
        version++
    }

    /**
     * The lyric is for what the rear card shows: HMC-E follows the lock screen's media card, the rear card
     * the first playing session, and with two players those can differ.
     */
    fun matches(playerPkg: String, playerTitle: String): Boolean =
        lines.isNotEmpty() && title.trim().equals(playerTitle.trim(), ignoreCase = true) &&
            (pkg.isEmpty() || playerPkg.isEmpty() || pkg == playerPkg)

    fun clear() { if (lines.isNotEmpty()) set(emptyList()) }

    /** The line being sung at [ms], or the last one started; -1 before the first. */
    fun indexAt(ms: Long): Int {
        val l = lines
        var lo = 0
        var hi = l.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (l[mid].start <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

/**
 * HMC-E's lyrics button icon (LyricsSheetDrawable, Lewewe's own drawing with Material Icons'
 * round "music_note", Apache-2.0): a sheet of text lines with the note at its lower right, the
 * sheet cut back round it. Outlined while the lyrics are off, filled while they show.
 */
class LyricsIcon {
    private val outline = Path()
    private val filled = Path()
    private val frame = RectF()

    init {
        val note = parse(NOTE)
        note.transform(Matrix().apply { setScale(NOTE_SCALE, NOTE_SCALE); postTranslate(NOTE_X, NOTE_Y) })
        val sheet = Path().apply { addRoundRect(L, T, R, B, CORNER, CORNER, Path.Direction.CW) }
        val r = CORNER - RING
        val inner = Path().apply { addRoundRect(L + RING, T + RING, R - RING, B - RING, r, r, Path.Direction.CW) }
        val lines = Path()
        for (l in LINES) {
            lines.op(Path().apply {
                addRoundRect(l[0], l[2] - LINE_H / 2f, l[1], l[2] + LINE_H / 2f, LINE_H / 2f, LINE_H / 2f,
                    Path.Direction.CW)
            }, Path.Op.UNION)
        }
        outline.set(sheet)
        outline.op(inner, Path.Op.DIFFERENCE)
        outline.op(lines, Path.Op.UNION)
        outline.op(cut(note, OUTLINE_GAP), Path.Op.DIFFERENCE)
        outline.op(note, Path.Op.UNION)
        filled.set(sheet)
        filled.op(lines, Path.Op.DIFFERENCE)
        filled.op(cut(note, GAP), Path.Op.DIFFERENCE)
        filled.op(note, Path.Op.UNION)
        val other = RectF()
        outline.computeBounds(frame, true)
        filled.computeBounds(other, true)
        frame.union(other)
    }

    fun draw(c: Canvas, cx: Float, cy: Float, side: Float, on: Boolean, paint: Paint) {
        val k = scale(side)
        c.save()
        c.translate(cx, cy)
        c.scale(k, k)
        c.translate(-frame.centerX(), -frame.centerY())
        c.drawPath(if (on) filled else outline, paint)
        c.restore()
    }

    /** How much the icon's path is scaled up at [side]: a paint's mask blur grows with it. */
    fun scale(side: Float) = side * FILL / maxOf(frame.width(), frame.height())

    private fun cut(note: Path, gap: Float): Path {
        val stroke = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * gap
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        return Path().also { stroke.getFillPath(note, it); it.op(note, Path.Op.UNION) }
    }

    companion object {
        private const val L = 2.8f; private const val T = 1.8f; private const val R = 17.8f
        private const val B = 21.2f; private const val CORNER = 2.6f; private const val RING = 1.3f
        private const val LINE_H = 1.5f
        private val LINES = arrayOf(floatArrayOf(6.25f, 14.35f, 6f), floatArrayOf(6.25f, 14.35f, 9.67f),
            floatArrayOf(6.25f, 12.6f, 13.33f), floatArrayOf(6.25f, 10.4f, 17f))
        private const val NOTE = "M12 5v8.55c-.94-.54-2.1-.75-3.33-.32-1.34.48-2.37 1.67-2.61 " +
            "3.07-.46 2.74 1.86 5.08 4.59 4.65 1.96-.31 3.35-2.11 3.35-4.1V7h2c1.1 0 2-.9 2-2s-.9-2-2-2h-2" +
            "c-1.1 0-2 .9-2 2z"
        private const val NOTE_SCALE = 0.58f; private const val NOTE_X = 10.1f; private const val NOTE_Y = 9.02f
        private const val GAP = 1.0f; private const val OUTLINE_GAP = 1.4f
        private const val FILL = 0.92f

        /**
         * SVG path data, the commands [NOTE] uses (M V v H h C c S s Z, implicit repeats).
         * shortcut: no arcs, quadratics or lineto; add them if another icon needs them.
         */
        fun parse(d: String): Path {
            val p = Path()
            val nums = Regex("[A-Za-z]|-?(?:\\d+\\.?\\d*|\\.\\d+)").findAll(d).map { it.value }.toList()
            var i = 0
            var cmd = 'M'
            var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
            var cx2 = 0f; var cy2 = 0f
            var lastCurve = false
            fun n() = nums[i++].toFloat()
            while (i < nums.size) {
                if (nums[i][0].isLetter()) cmd = nums[i++][0]
                val rel = cmd.isLowerCase()
                val ox = if (rel) x else 0f
                val oy = if (rel) y else 0f
                var curve = false
                when (cmd.uppercaseChar()) {
                    'M' -> { x = ox + n(); y = oy + n(); p.moveTo(x, y); sx = x; sy = y; cmd = if (rel) 'l' else 'L' }
                    'L' -> { x = ox + n(); y = oy + n(); p.lineTo(x, y) }
                    'V' -> { y = oy + n(); p.lineTo(x, y) }
                    'H' -> { x = ox + n(); p.lineTo(x, y) }
                    'C' -> {
                        val x1 = ox + n(); val y1 = oy + n()
                        cx2 = ox + n(); cy2 = oy + n()
                        x = ox + n(); y = oy + n()
                        p.cubicTo(x1, y1, cx2, cy2, x, y); curve = true
                    }
                    'S' -> {
                        val x1 = if (lastCurve) 2 * x - cx2 else x
                        val y1 = if (lastCurve) 2 * y - cy2 else y
                        cx2 = ox + n(); cy2 = oy + n()
                        x = ox + n(); y = oy + n()
                        p.cubicTo(x1, y1, cx2, cy2, x, y); curve = true
                    }
                    'Z' -> { p.close(); x = sx; y = sy }
                    else -> error("path command $cmd")
                }
                lastCurve = curve
            }
            return p
        }
    }
}

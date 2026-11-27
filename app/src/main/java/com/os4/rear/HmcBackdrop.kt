package com.os4.rear

import android.graphics.Bitmap

/**
 * The lock screen's own backdrop for the playing track, as HyperMusicCover-Enhanced composes
 * it (the cropped cover blurred under its colour regions), handed over at a landscape size.
 * Main thread only.
 */
object HmcBackdrop {
    var bitmap: Bitmap? = null
        private set
    var key = ""
        private set
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun set(k: String, b: Bitmap) {
        key = k
        bitmap = b
        listeners.toList().forEach { it() }
    }
}

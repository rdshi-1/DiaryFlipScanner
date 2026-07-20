package com.example.diaryflip.scanner

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * A low-resolution luminance signature. It is deliberately stricter than a
 * classic average hash because diary pages are mostly white and otherwise
 * produce many false duplicate matches.
 */
data class DocumentFingerprint(val pixels: ByteArray) {
    fun distance(other: DocumentFingerprint): Double {
        if (pixels.size != other.pixels.size) return Double.MAX_VALUE
        var total = 0L
        for (i in pixels.indices) {
            total += abs((pixels[i].toInt() and 0xFF) - (other.pixels[i].toInt() and 0xFF))
        }
        return total.toDouble() / pixels.size
    }

    companion object {
        fun from(bitmap: Bitmap): DocumentFingerprint {
            val width = 64
            val height = 48
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val argb = IntArray(width * height)
            scaled.getPixels(argb, 0, width, 0, 0, width, height)
            if (scaled !== bitmap) scaled.recycle()

            val luminance = ByteArray(argb.size)
            for (i in argb.indices) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = (299 * r + 587 * g + 114 * b) / 1000
                luminance[i] = y.toByte()
            }
            return DocumentFingerprint(luminance)
        }
    }
}

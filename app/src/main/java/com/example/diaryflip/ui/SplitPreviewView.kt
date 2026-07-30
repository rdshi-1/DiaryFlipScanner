package com.example.diaryflip.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class SplitPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = 0x66000000 }
    private val splitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var splitFraction: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.30f, 0.70f)
            invalidate()
        }

    var gutterFraction: Float = 0.018f
        set(value) {
            field = value.coerceIn(0f, 0.08f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        if (width <= 0 || height <= 0) return

        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f
        val destination = RectF(left, top, left + drawnWidth, top + drawnHeight)

        canvas.drawBitmap(source, null, destination, imagePaint)
        canvas.drawRect(destination, borderPaint)

        val splitX = destination.left + destination.width() * splitFraction
        val halfGap = destination.width() * gutterFraction / 2f
        if (halfGap > 0f) {
            canvas.drawRect(
                splitX - halfGap,
                destination.top,
                splitX + halfGap,
                destination.bottom,
                shadePaint
            )
        }
        canvas.drawLine(splitX, destination.top, splitX, destination.bottom, splitPaint)
    }
}

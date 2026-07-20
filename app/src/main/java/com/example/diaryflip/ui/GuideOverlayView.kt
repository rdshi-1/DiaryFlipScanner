package com.example.diaryflip.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }

    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val shadePaint = Paint().apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val insetX = width * 0.08f
        val insetY = height * 0.14f
        val guide = RectF(insetX, insetY, width - insetX, height - insetY)

        canvas.drawRect(0f, 0f, width.toFloat(), guide.top, shadePaint)
        canvas.drawRect(0f, guide.bottom, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawRect(0f, guide.top, guide.left, guide.bottom, shadePaint)
        canvas.drawRect(guide.right, guide.top, width.toFloat(), guide.bottom, shadePaint)

        canvas.drawRoundRect(guide, 22f, 22f, borderPaint)
        canvas.drawLine(width / 2f, guide.top, width / 2f, guide.bottom, centrePaint)
    }
}

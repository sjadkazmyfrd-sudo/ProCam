package com.example.procam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Rule-of-thirds grid drawn over the visible preview area (centered, aspect-fit). */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** width / height of the preview content (e.g. 3f/4f for 4:3 portrait). */
    var contentRatio: Float = 3f / 4f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return

        var w = vw
        var h = w / contentRatio
        if (h > vh) {
            h = vh
            w = h * contentRatio
        }
        val left = (vw - w) / 2f
        val top = (vh - h) / 2f

        for (i in 1..2) {
            val x = left + w * i / 3f
            canvas.drawLine(x, top, x, top + h, paint)
            val y = top + h * i / 3f
            canvas.drawLine(left, y, left + w, y, paint)
        }
    }
}

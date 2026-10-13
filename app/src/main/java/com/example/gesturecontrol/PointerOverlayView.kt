package com.example.gesturecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** النقطة التي تُرسم فوق الشاشة كلها (تتبع السبابة). حمراء وأصغر أثناء القرصة. */
class PointerOverlayView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private var px = -100f
    private var py = -100f
    private var pinched = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = 0xFFFFFFFF.toInt()
    }

    fun update(x: Float, y: Float, isPinched: Boolean) {
        px = x
        py = y
        pinched = isPinched
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = if (pinched) 0xCCFF3B30.toInt() else 0xAA00E5FF.toInt()
        val r = (if (pinched) 12f else 16f) * d
        canvas.drawCircle(px, py, r, fill)
        canvas.drawCircle(px, py, r, ring)
    }
}

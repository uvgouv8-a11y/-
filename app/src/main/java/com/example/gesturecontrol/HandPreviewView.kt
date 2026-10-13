package com.example.gesturecontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * يعرض إطار الكاميرا بملء الإطار، ويرسم فوقه هيكل اليد بخطوط خضراء مضيئة،
 * مع اسم الإيماءة المكتشفة وشريط تقدّم التثبيت ولافتة تعليمات (العدّ التنازلي).
 */
class HandPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var points: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** لافتة كبيرة في منتصف المعاينة (تعليمات التسجيل). */
    var banner: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val green = Color.parseColor("#39FF14")

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        alpha = 70
        strokeWidth = 14f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        strokeWidth = 4f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        alpha = 90
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f * d
        textAlign = Paint.Align.CENTER
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        textSize = 24f * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 18f * d
        textAlign = Paint.Align.CENTER
    }
    private val barBgPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val barFillPaint = Paint().apply { color = green }

    private val dst = RectF()
    private val pill = RectF()

    override fun onDraw(canvas: Canvas) {
        val bm = bitmap
        if (bm == null) {
            canvas.drawText("الكاميرا متوقفة", width / 2f, height / 2f, emptyPaint)
            return
        }

        // نملأ الإطار بالصورة (قص بسيط من الأطراف إن لزم) مع الحفاظ على النسبة
        val scale = max(width / bm.width.toFloat(), height / bm.height.toFloat())
        val w = bm.width * scale
        val h = bm.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bm, null, dst, null)

        val p = points
        val hasHand = p != null && p.size >= 42

        if (hasHand && p != null) {
            for ((a, b) in CONNECTIONS) {
                val x1 = left + p[a * 2] * w
                val y1 = top + p[a * 2 + 1] * h
                val x2 = left + p[b * 2] * w
                val y2 = top + p[b * 2 + 1] * h
                canvas.drawLine(x1, y1, x2, y2, glowPaint)
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }
            for (i in 0 until 21) {
                val x = left + p[i * 2] * w
                val y = top + p[i * 2 + 1] * h
                canvas.drawCircle(x, y, 9f * d, dotGlowPaint)
                canvas.drawCircle(x, y, 4.5f * d, dotPaint)
            }
        }

        // اسم الإيماءة المكتشفة
        if (hasHand && label.isNotEmpty() && label != "—") {
            val tw = labelPaint.measureText(label)
            val fm = labelPaint.fontMetrics
            val padH = 16f * d
            val padV = 8f * d
            val pillTop = 14f * d
            val pillLeft = (width - tw) / 2f - padH
            pill.set(pillLeft, pillTop, pillLeft + tw + 2 * padH, pillTop + (fm.descent - fm.ascent) + 2 * padV)
            canvas.drawRoundRect(pill, 20f * d, 20f * d, pillPaint)
            canvas.drawText(label, width / 2f, pillTop + padV - fm.ascent, labelPaint)
        }

        // شريط تقدّم التثبيت (عند الامتلاء ينفَّذ الأمر)
        if (hasHand && progress > 0f) {
            val barH = 8f * d
            canvas.drawRect(0f, height - barH, width.toFloat(), height.toFloat(), barBgPaint)
            canvas.drawRect(0f, height - barH, width * progress, height.toFloat(), barFillPaint)
        }

        // لافتة التعليمات (العدّ التنازلي أثناء تسجيل إيماءة)
        if (banner.isNotEmpty()) {
            val tw = bannerPaint.measureText(banner)
            val fm = bannerPaint.fontMetrics
            val pad = 18f * d
            val cy = height / 2f
            val half = (fm.descent - fm.ascent) / 2f
            pill.set(width / 2f - tw / 2f - pad, cy - half - pad, width / 2f + tw / 2f + pad, cy + half + pad)
            canvas.drawRoundRect(pill, 24f * d, 24f * d, pillPaint)
            canvas.drawText(banner, width / 2f, cy - (fm.ascent + fm.descent) / 2f, bannerPaint)
        }
    }

    companion object {
        private val CONNECTIONS = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17
        )
    }
}

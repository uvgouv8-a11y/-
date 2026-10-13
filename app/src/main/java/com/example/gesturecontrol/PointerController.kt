package com.example.gesturecontrol

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** مرشّح تنعيم يقلّل ارتعاش اليد عند البطء ويبقى سريعاً عند الحركة السريعة. */
class OneEuro(
    private val minCutoff: Float = 1.5f,
    private val beta: Float = 6f,
    private val dCutoff: Float = 1f
) {
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = 0L
    private var ready = false

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(x: Float, t: Long): Float {
        if (!ready) {
            ready = true
            xPrev = x
            dxPrev = 0f
            tPrev = t
            return x
        }
        val dt = ((t - tPrev) / 1000f).coerceAtLeast(0.001f)
        tPrev = t
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        dxPrev = aD * dx + (1f - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxPrev)
        val a = alpha(cutoff, dt)
        xPrev = a * x + (1f - a) * xPrev
        return xPrev
    }

    fun reset() {
        ready = false
    }
}

/**
 * وضع المؤشر: طرف السبابة يحرّك نقطة على الشاشة، والقرصة (إبهام + سبابة) تعمل كلمسة:
 *  - قرصة سريعة بدون تحريك = ضغطة
 *  - قرصة مع تثبيت ثم فكّ = ضغطة طويلة
 *  - قرصة مع تحريك = سحب مستمر (تمرير/تقليب)، وفكّ القرصة يرفع الإصبع
 */
class PointerController {

    private class P(val t: Long, val x: Float, val y: Float)

    private val fx = OneEuro()
    private val fy = OneEuro()
    private val trail = ArrayDeque<P>()

    private var pinched = false
    private var state = IDLE
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastSeen = 0L
    private var hidden = true

    fun reset() {
        fx.reset()
        fy.reset()
        trail.clear()
        pinched = false
        state = IDLE
        lastSeen = 0L
        hidden = true
    }

    /** يُستدعى عند غياب اليد. نتسامح مع فقدان لحظي قبل إنهاء السحب أو إخفاء النقطة. */
    fun onNoHand(now: Long, svc: ControlAccessibilityService?) {
        if (lastSeen == 0L) return
        val gap = now - lastSeen
        if (gap > LOST_MS) {
            if (state == DRAGGING) svc?.dragEnd()
            pinched = false
            state = IDLE
            fx.reset()
            fy.reset()
            trail.clear()
            GestureState.gesture.value = "—"
        }
        if (!hidden && gap > HIDE_AFTER_MS) {
            svc?.hidePointer()
            hidden = true
        }
    }

    fun onHand(lm: List<NormalizedLandmark>, now: Long, svc: ControlAccessibilityService) {
        val size = svc.screenSize()
        val w = size[0].toFloat()
        val h = size[1].toFloat()
        if (w <= 0f || h <= 0f) return
        lastSeen = now

        // مستطيل في منتصف الصورة (حجمه من الإعدادات) يتحوّل إلى الشاشة كاملة
        val half = PointerSettings.range / 2f
        val tip = lm[8]
        val nx = ((tip.x() - (0.5f - half)) / (2f * half)).coerceIn(0f, 1f)
        val ny = ((tip.y() - (0.5f - half)) / (2f * half)).coerceIn(0f, 1f)
        val sx = (fx.filter(nx, now) * w).coerceIn(0f, w - 1f)
        val sy = (fy.filter(ny, now) * h).coerceIn(0f, h - 1f)

        trail.addLast(P(now, sx, sy))
        while (trail.size > 1 && now - trail.first().t > TRAIL_MS) trail.removeFirst()

        // القرصة: المسافة بين طرفي الإبهام والسبابة نسبةً لطول الكف (مع تأخير للفكّ)
        val palm = hypot(lm[9].x() - lm[0].x(), lm[9].y() - lm[0].y()).coerceAtLeast(1e-4f)
        val ratio = hypot(lm[4].x() - lm[8].x(), lm[4].y() - lm[8].y()) / palm
        val nowPinched = if (pinched) ratio < PINCH_RELEASE else ratio < PINCH_PRESS

        if (nowPinched && !pinched) {
            // بداية القرصة: نأخذ موضع المؤشر قبل لحظات، لأن الأصابع تتحرك وهي تقترب من بعضها
            pinched = true
            state = PINCHED
            downT = now
            val ref = trail.lastOrNull { now - it.t >= PRE_PINCH_MS } ?: trail.first()
            downX = ref.x
            downY = ref.y
        } else if (!nowPinched && pinched) {
            pinched = false
            when (state) {
                PINCHED -> {
                    if (now - downT >= LONG_MS) {
                        svc.pointerLongPress(downX, downY)
                        GestureState.lastAction.value = "ضغطة طويلة بالمؤشر"
                    } else {
                        svc.pointerTap(downX, downY)
                        GestureState.lastAction.value = "ضغطة بالمؤشر"
                    }
                }
                DRAGGING -> svc.dragEnd()
            }
            state = IDLE
        }

        if (state == PINCHED && hypot(sx - downX, sy - downY) > DRAG_START_FRACTION * min(w, h)) {
            state = DRAGGING
            svc.dragStart(downX, downY)
        }
        if (state == DRAGGING) svc.dragMove(sx, sy)

        // أثناء القرصة قبل بدء السحب تبقى النقطة ثابتة في مكان اللمس
        val shownX = if (state == PINCHED) downX else sx
        val shownY = if (state == PINCHED) downY else sy
        svc.showPointer(shownX, shownY, pinched)
        hidden = false

        GestureState.gesture.value = when (state) {
            DRAGGING -> "↕ سحب"
            PINCHED -> "🤏 لمس"
            else -> "🖱 مؤشر"
        }
        GestureState.holdProgress.value = 0f
    }

    companion object {
        private const val IDLE = 0
        private const val PINCHED = 1
        private const val DRAGGING = 2

        const val PINCH_PRESS = 0.28f      // أقل من هذا = قرصة
        const val PINCH_RELEASE = 0.45f    // أكبر من هذا = فكّ القرصة
        const val PRE_PINCH_MS = 120L
        const val TRAIL_MS = 400L
        const val LONG_MS = 650L
        const val DRAG_START_FRACTION = 0.035f
        const val LOST_MS = 250L
        const val HIDE_AFTER_MS = 800L
    }
}

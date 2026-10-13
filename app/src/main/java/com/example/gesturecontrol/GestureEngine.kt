package com.example.gesturecontrol

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot

enum class Gesture(val label: String) {
    NONE("—"),
    FIST("✊ قبضة"),
    OPEN_PALM("🖐 كف مفتوح"),
    PEACE("✌️ علامة النصر"),
    THUMBS_UP("👍 إبهام للأعلى"),
    POINT_UP("☝️ سبابة للأعلى"),
    POINT_DOWN("👇 سبابة للأسفل")
}

enum class Command(val label: String) {
    HOME("الشاشة الرئيسية"),
    BACK("رجوع"),
    RECENTS("التطبيقات الأخيرة"),
    CLICK("نقرة في منتصف الشاشة"),
    DOUBLE_TAP("نقرتان في منتصف الشاشة"),
    SWIPE_UP("سحب لأعلى (ريلز تالٍ)"),
    SWIPE_DOWN("سحب لأسفل (ريلز سابق)"),
    SWIPE_LEFT("سحب لليسار"),
    SWIPE_RIGHT("سحب لليمين"),
    NOTIFICATIONS("فتح الإشعارات"),
    QUICK_SETTINGS("الإعدادات السريعة"),
    SCREENSHOT("لقطة شاشة"),
    LOCK_SCREEN("قفل الشاشة")
}

/**
 * يحوّل نقاط اليد (21 نقطة من MediaPipe) إلى إيماءة ثم إلى أمر، حسب الإعدادات
 * في [GestureConfig] (تفعيل/تعطيل، تغيير الأمر، والإيماءات التي سجّلها المستخدم).
 * معرّفات الإيماءات المدمجة = أسماء [Gesture] (POINT_UP...) وحركات الكف SWIPE_UP/DOWN/LEFT/RIGHT.
 * الإحداثيات مُعدّلة بحيث يكون اليمين = يمين المستخدم (الصورة معكوسة مسبقاً).
 */
class GestureEngine {

    private data class Sample(val t: Long, val x: Float, val y: Float)

    private val history = ArrayDeque<Sample>()
    private var currentKey = ""
    private var holdStart = 0L
    private var anchorX = 0f
    private var anchorY = 0f
    private var latched = false
    private var lastFire = 0L

    fun onNoHand() {
        currentKey = ""
        latched = false
        history.clear()
        GestureState.gesture.value = "—"
        GestureState.holdProgress.value = 0f
    }

    /** الأمر المرتبط بمعرّف إيماءة إن كانت مفعّلة، وإلا null. */
    private fun commandFor(id: String): Command? =
        if (GestureConfig.isEnabled(id)) GestureConfig.action(id) else null

    fun onHand(lm: List<NormalizedLandmark>, now: Long): Command? {
        // أثناء تسجيل إيماءة جديدة: نغذّي المسجّل ولا ننفّذ أي أمر
        if (GestureRecorder.active) {
            GestureRecorder.feed(lm)
            GestureState.gesture.value = "جارٍ التسجيل…"
            GestureState.holdProgress.value = 0f
            currentKey = ""
            latched = false
            history.clear()
            return null
        }

        // الإيماءات المسجّلة لها الأولوية، ثم المدمجة
        val custom = GestureConfig.matchCustom(HandMath.vector(lm))
        val builtin = if (custom == null) classify(lm) else Gesture.NONE

        val key: String
        val label: String
        val command: Command?
        if (custom != null) {
            key = custom.id
            command = GestureConfig.action(custom.id)
            label = "⭐ " + custom.name
        } else if (builtin != Gesture.NONE) {
            key = builtin.name
            command = commandFor(key)
            label = when {
                !GestureConfig.isEnabled(key) -> builtin.label + " (معطّلة)"
                command == null -> builtin.label + " (بدون أمر)"
                else -> builtin.label
            }
        } else {
            key = ""
            command = null
            label = "—"
        }
        GestureState.gesture.value = label

        val cx = (lm[0].x() + lm[5].x() + lm[9].x() + lm[13].x() + lm[17].x()) / 5f
        val cy = (lm[0].y() + lm[5].y() + lm[9].y() + lm[13].y() + lm[17].y()) / 5f

        // ---- السحب (حركة الكف المفتوح) ----
        history.addLast(Sample(now, cx, cy))
        while (history.isNotEmpty() && now - history.first().t > SWIPE_WINDOW_MS) history.removeFirst()

        if (builtin == Gesture.OPEN_PALM && now - lastFire > COOLDOWN_MS && history.size >= 4) {
            val first = history.first()
            val dx = cx - first.x
            val dy = cy - first.y
            val swipeId = when {
                abs(dx) > SWIPE_DIST && abs(dx) > 1.6f * abs(dy) ->
                    if (dx > 0) "SWIPE_RIGHT" else "SWIPE_LEFT"
                abs(dy) > SWIPE_DIST && abs(dy) > 1.6f * abs(dx) ->
                    if (dy < 0) "SWIPE_UP" else "SWIPE_DOWN"
                else -> null
            }
            if (swipeId != null) {
                val swipe = commandFor(swipeId)
                if (swipe != null) return fire(swipe, now)
            }
        }

        // ---- الإيماءات الثابتة (تثبيت لمدة قصيرة) ----
        if (key != currentKey) {
            currentKey = key
            holdStart = now
            anchorX = cx
            anchorY = cy
            latched = false
        } else if (hypot(cx - anchorX, cy - anchorY) > HOLD_MOVE_TOLERANCE) {
            holdStart = now
            anchorX = cx
            anchorY = cy
        }

        // أي إيماءة مرتبطة بسحب لأعلى/لأسفل: تثبيت أقصر وتتكرر طالما بقيت اليد (للريلز)
        val repeating = command == Command.SWIPE_UP || command == Command.SWIPE_DOWN
        val holdNeeded = if (repeating) POINT_HOLD_MS else HOLD_MS
        val gap = if (repeating) POINT_REPEAT_MS else COOLDOWN_MS
        val armed = command != null && (repeating || !latched)

        GestureState.holdProgress.value =
            if (armed) ((now - holdStart).toFloat() / holdNeeded).coerceIn(0f, 1f) else 0f

        if (armed && command != null && now - holdStart >= holdNeeded && now - lastFire > gap) {
            return fire(command, now)
        }
        return null
    }

    private fun fire(cmd: Command, now: Long): Command {
        lastFire = now
        latched = true
        holdStart = now
        history.clear()
        return cmd
    }

    // ---------------- تصنيف الإيماءات المدمجة ----------------

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark) =
        hypot(a.x() - b.x(), a.y() - b.y())

    /** الإصبع ممدود إذا كان طرفه أبعد عن المعصم من مفصله الأوسط. */
    private fun extended(lm: List<NormalizedLandmark>, tip: Int, pip: Int): Boolean =
        dist(lm[tip], lm[0]) > dist(lm[pip], lm[0]) * 1.08f

    private fun classify(lm: List<NormalizedLandmark>): Gesture {
        val index = extended(lm, 8, 6)
        val middle = extended(lm, 12, 10)
        val ring = extended(lm, 16, 14)
        val pinky = extended(lm, 20, 18)
        // الإبهام: طرفه أبعد عن قاعدة الخنصر من مفصله
        val thumb = dist(lm[4], lm[17]) > dist(lm[3], lm[17]) * 1.05f

        val fingers = listOf(index, middle, ring, pinky).count { it }

        return when {
            fingers == 4 -> Gesture.OPEN_PALM
            index && middle && !ring && !pinky -> Gesture.PEACE
            // سبابة فقط: الاتجاه يحدده موضع طرف السبابة بالنسبة لقاعدتها
            index && !middle && !ring && !pinky -> {
                val dx = lm[8].x() - lm[5].x()
                val dy = lm[8].y() - lm[5].y()
                when {
                    abs(dy) < abs(dx) * 1.2f -> Gesture.NONE // أفقية: تجاهل
                    dy < 0 -> Gesture.POINT_UP
                    else -> Gesture.POINT_DOWN
                }
            }
            fingers == 0 && thumb && lm[4].y() < lm[5].y() - 0.04f -> Gesture.THUMBS_UP
            fingers == 0 -> Gesture.FIST
            else -> Gesture.NONE
        }
    }

    companion object {
        const val HOLD_MS = 900L
        const val COOLDOWN_MS = 1000L
        const val POINT_HOLD_MS = 350L
        const val POINT_REPEAT_MS = 1200L
        const val SWIPE_WINDOW_MS = 450L
        const val SWIPE_DIST = 0.22f
        const val HOLD_MOVE_TOLERANCE = 0.07f
    }
}

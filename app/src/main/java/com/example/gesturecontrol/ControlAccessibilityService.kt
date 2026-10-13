package com.example.gesturecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** مدة كل مقطع من السحب المستمر (مللي ثانية). */
private const val DRAG_SEGMENT_MS = 40L

/** ينفّذ الأوامر على الهاتف: رجوع، رئيسية، تمرير، نقر... */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        removeOverlayNow()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---------------- وضع المؤشر: النقطة + اللمس + السحب المستمر ----------------

    private val ui = Handler(Looper.getMainLooper())
    private var overlay: PointerOverlayView? = null
    private var screenCache = intArrayOf(0, 0)
    private var screenAt = 0L

    private fun wm() = getSystemService(WINDOW_SERVICE) as WindowManager

    /** حجم الشاشة الكامل بالبكسل [العرض، الارتفاع]. */
    fun screenSize(): IntArray {
        val now = SystemClock.uptimeMillis()
        if (screenCache[0] == 0 || now - screenAt > 1000) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm().defaultDisplay.getRealMetrics(dm)
            screenCache = intArrayOf(dm.widthPixels, dm.heightPixels)
            screenAt = now
        }
        return screenCache
    }

    fun showPointer(x: Float, y: Float, pinched: Boolean) {
        ui.post {
            val v = overlay ?: createOverlay() ?: return@post
            v.visibility = View.VISIBLE
            v.update(x, y, pinched)
        }
    }

    fun hidePointer() {
        ui.post { overlay?.visibility = View.GONE }
    }

    /** يزيل النقطة وينهي أي سحب جارٍ (عند إيقاف وضع المؤشر). */
    fun pointerOff() {
        ui.post { removeOverlayNow() }
    }

    private fun createOverlay(): PointerOverlayView? {
        return try {
            val v = PointerOverlayView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            wm().addView(v, lp)
            overlay = v
            v
        } catch (e: Exception) {
            null
        }
    }

    private fun removeOverlayNow() {
        dragReset()
        overlay?.let { v ->
            try {
                wm().removeView(v)
            } catch (e: Exception) {
                // النافذة أُزيلت بالفعل
            }
        }
        overlay = null
    }

    fun pointerTap(x: Float, y: Float) {
        ui.post { tap(x, y) }
    }

    fun pointerLongPress(x: Float, y: Float) {
        ui.post {
            val path = Path().apply { moveTo(x, y) }
            dispatch(GestureDescription.StrokeDescription(path, 0, 700))
        }
    }

    // السحب المستمر: سلسلة مقاطع قصيرة، كل مقطع يكمل السابق (إصبع واحد لم يُرفع بعد)
    @Volatile private var dragTx = 0f
    @Volatile private var dragTy = 0f
    private var dragLx = 0f
    private var dragLy = 0f
    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragging = false
    private var dragFinishing = false
    private var dragInFlight = false

    fun dragStart(x: Float, y: Float) {
        ui.post {
            dragReset()
            dragging = true
            dragLx = x
            dragLy = y
            dragTx = x
            dragTy = y
            dragSegment(first = true, last = false)
        }
    }

    /** يحدّث الهدف فقط؛ المقطع التالي سيصل إليه عند انتهاء المقطع الجاري. */
    fun dragMove(x: Float, y: Float) {
        dragTx = x
        dragTy = y
    }

    fun dragEnd() {
        ui.post {
            if (!dragging) return@post
            dragging = false
            if (dragInFlight) dragFinishing = true else dragSegment(first = false, last = true)
        }
    }

    private fun dragSegment(first: Boolean, last: Boolean) {
        val prev = dragStroke
        val path = Path().apply {
            moveTo(dragLx, dragLy)
            lineTo(dragTx, dragTy)
        }
        val stroke = if (first || prev == null) {
            GestureDescription.StrokeDescription(path, 0, DRAG_SEGMENT_MS, !last)
        } else {
            prev.continueStroke(path, 0, DRAG_SEGMENT_MS, !last)
        }
        dragStroke = if (last) null else stroke
        dragLx = dragTx
        dragLy = dragTy
        dragInFlight = true

        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    dragInFlight = false
                    afterSegment(last)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    dragInFlight = false
                    dragReset()
                }
            },
            null
        )
        if (!ok) {
            dragInFlight = false
            dragReset()
        }
    }

    private fun afterSegment(wasLast: Boolean) {
        if (wasLast) {
            dragReset()
            return
        }
        if (dragFinishing) {
            dragFinishing = false
            dragSegment(first = false, last = true)
        } else if (dragging) {
            dragSegment(first = false, last = false)
        }
    }

    private fun dragReset() {
        dragging = false
        dragFinishing = false
        dragInFlight = false
        dragStroke = null
    }

    fun perform(cmd: Command) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        when (cmd) {
            Command.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Command.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Command.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            Command.CLICK -> tap(w / 2f, h / 2f)
            Command.SWIPE_UP -> swipe(w / 2f, h * 0.7f, w / 2f, h * 0.3f)
            Command.SWIPE_DOWN -> swipe(w / 2f, h * 0.3f, w / 2f, h * 0.7f)
            Command.SWIPE_LEFT -> swipe(w * 0.8f, h / 2f, w * 0.2f, h / 2f)
            Command.SWIPE_RIGHT -> swipe(w * 0.2f, h / 2f, w * 0.8f, h / 2f)
            Command.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            Command.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            Command.SCREENSHOT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                }
            }
            Command.LOCK_SCREEN -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            Command.DOUBLE_TAP -> {
                tap(w / 2f, h / 2f)
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ tap(w / 2f, h / 2f) }, 120)
            }
        }
        GestureState.lastAction.value = cmd.label
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.StrokeDescription(path, 0, 50))
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(GestureDescription.StrokeDescription(path, 0, 300))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}

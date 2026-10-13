package com.example.gesturecontrol

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** حالة مشتركة بين الخدمة والواجهة. */
object GestureState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("متوقف")
    val gesture = MutableStateFlow("—")
    val lastAction = MutableStateFlow("—")

    /** آخر إطار من الكاميرا (مصغّر ومعكوس كالمرآة). */
    val frame = MutableStateFlow<Bitmap?>(null)

    /** نقاط اليد المطبّعة: x0,y0,x1,y1,... (21 نقطة) أو null إذا لا توجد يد. */
    val points = MutableStateFlow<FloatArray?>(null)

    /** تقدّم تثبيت الإيماءة من 0 إلى 1 (عند 1 ينفَّذ الأمر). */
    val holdProgress = MutableStateFlow(0f)

    // المعاينة تعمل فقط ما دامت شاشة واحدة على الأقل من التطبيق ظاهرة،
    // حتى لا نستهلك المعالج والبطارية في الخلفية.
    private val previewUsers = AtomicInteger(0)

    val previewActive: Boolean get() = previewUsers.get() > 0

    fun acquirePreview() {
        previewUsers.incrementAndGet()
    }

    fun releasePreview() {
        if (previewUsers.decrementAndGet() <= 0) {
            previewUsers.set(0)
            frame.value = null
            points.value = null
        }
    }
}

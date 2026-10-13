package com.example.gesturecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** خدمة أمامية تشغّل الكاميرا الأمامية وتحوّل إيماءات اليد إلى أوامر. */
class GestureService : LifecycleService() {

    private lateinit var executor: ExecutorService
    private lateinit var tracker: HandTracker
    private val engine = GestureEngine()
    private val pointer = PointerController()
    private var pointerOn = false
    private val main = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        GestureConfig.init(applicationContext)
        PointerSettings.init(applicationContext)
        executor = Executors.newSingleThreadExecutor()
        tracker = HandTracker(this) { hand, now -> handleResult(hand, now) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        GestureState.running.value = true
        GestureState.status.value = "جارٍ التحضير…"

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { tracker.start() }
                bindCamera()
                GestureState.status.value = "يعمل — أرِ يدك للكاميرا الأمامية"
            } catch (e: Exception) {
                GestureState.status.value = "تعذّر البدء: ${e.message}"
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { image -> analyze(image) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val rotation = image.imageInfo.rotationDegrees
        val buffer = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        image.use { buffer.copyPixelsFromBuffer(it.planes[0].buffer) }

        // تدوير الصورة لتصبح عمودية + عكسها أفقياً (كالمرآة) حتى يوافق اليمين يمين المستخدم
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(buffer, 0, 0, buffer.width, buffer.height, matrix, true)

        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestamp) ts = lastTimestamp + 1
        lastTimestamp = ts
        tracker.detect(upright, ts)

        // معاينة داخل التطبيق: فقط والتطبيق ظاهر، وبمعدل ~15 إطار/ثانية
        if (GestureState.previewActive && ts - lastPreview > 66) {
            lastPreview = ts
            val w = 320
            val h = (upright.height * w / upright.width.toFloat()).toInt()
            GestureState.frame.value = Bitmap.createScaledBitmap(upright, w, h, true)
        }
    }

    private var lastPreview = 0L

    private fun handleResult(hand: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?, now: Long) {
        if (GestureState.previewActive) {
            GestureState.points.value = hand?.let { lm ->
                FloatArray(lm.size * 2) { i ->
                    if (i % 2 == 0) lm[i / 2].x() else lm[i / 2].y()
                }
            }
        }
        // وضع المؤشر يوقف الإيماءات العادية؛ عند التبديل ننظّف حالة الوضع السابق
        val pointerMode = PointerSettings.enabled
        if (pointerMode != pointerOn) {
            pointerOn = pointerMode
            pointer.reset()
            engine.onNoHand()
            if (!pointerMode) ControlAccessibilityService.instance?.pointerOff()
        }
        if (hand == null || hand.size < 21) {
            if (pointerMode) {
                pointer.onNoHand(now, ControlAccessibilityService.instance)
            } else {
                engine.onNoHand()
            }
            return
        }
        if (pointerMode) {
            val svc = ControlAccessibilityService.instance
            if (svc == null) {
                GestureState.status.value = "فعّل خدمة إمكانية الوصول أولاً"
            } else {
                pointer.onHand(hand, now, svc)
            }
            return
        }
        val cmd = engine.onHand(hand, now) ?: return
        val service = ControlAccessibilityService.instance
        if (service == null) {
            GestureState.status.value = "فعّل خدمة إمكانية الوصول أولاً"
            return
        }
        main.post { service.perform(cmd) }
    }

    private fun startAsForeground() {
        val channelId = "gesture_control"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "التحكم بالإيماءات", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("التحكم بالإيماءات يعمل")
            .setContentText("الكاميرا الأمامية مفعّلة. اضغط للفتح أو الإيقاف.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        ControlAccessibilityService.instance?.pointerOff()
        cameraProvider?.unbindAll()
        tracker.close()
        executor.shutdown()
        GestureState.running.value = false
        GestureState.status.value = "متوقف"
        GestureState.gesture.value = "—"
        super.onDestroy()
    }
}

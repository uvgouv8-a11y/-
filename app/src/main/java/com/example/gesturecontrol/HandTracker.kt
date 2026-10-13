package com.example.gesturecontrol

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** غلاف بسيط حول MediaPipe HandLandmarker في وضع البث المباشر. */
class HandTracker(
    context: Context,
    private val onResult: (List<NormalizedLandmark>?, Long) -> Unit
) {
    private val appContext = context.applicationContext
    private var landmarker: HandLandmarker? = null

    /** يُستدعى من خيط خلفي: ينزّل النموذج أول مرة ثم ينشئ المتتبّع. */
    fun start() {
        val model = File(appContext.filesDir, MODEL_NAME)
        if (!model.exists() || model.length() == 0L) {
            GestureState.status.value = "جارٍ تنزيل نموذج اليد (مرة واحدة)…"
            download(model)
        }

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.absolutePath).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result: HandLandmarkerResult, _ ->
                val hand = result.landmarks().firstOrNull()
                onResult(hand, android.os.SystemClock.uptimeMillis())
            }
            .setErrorListener { e -> GestureState.status.value = "خطأ: ${e.message}" }
            .build()

        landmarker = HandLandmarker.createFromOptions(appContext, options)
    }

    fun detect(bitmap: Bitmap, timestampMs: Long) {
        landmarker?.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
    }

    fun close() {
        landmarker?.close()
        landmarker = null
    }

    private fun download(target: File) {
        val tmp = File(target.parentFile, "$MODEL_NAME.part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) error("تعذّر حفظ النموذج")
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        private const val MODEL_NAME = "hand_landmarker.task"
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/" +
                "hand_landmarker/float16/1/hand_landmarker.task"
    }
}

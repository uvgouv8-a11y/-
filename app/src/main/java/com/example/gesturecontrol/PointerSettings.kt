package com.example.gesturecontrol

import android.content.Context
import android.content.SharedPreferences

/** إعدادات وضع المؤشر (ماوس بالإصبع). */
object PointerSettings {

    private var prefs: SharedPreferences? = null

    /** تشغيل وضع المؤشر: يوقف الإيماءات العادية ويحوّل السبابة إلى مؤشر. */
    @Volatile
    var enabled = false
        private set

    /**
     * جزء الصورة الذي تتحرك فيه يدك ليغطي الشاشة كلها (0.4 إلى 0.9).
     * أقل = حركة صغيرة تكفي (حساسية أعلى)، أكبر = حركة أوسع وأدق.
     */
    @Volatile
    var range = 0.6f
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("pointer", Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("enabled", false)
        range = p.getFloat("range", 0.6f).coerceIn(0.4f, 0.9f)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs?.edit()?.putBoolean("enabled", value)?.apply()
    }

    fun setRange(value: Float) {
        range = value.coerceIn(0.4f, 0.9f)
        prefs?.edit()?.putFloat("range", range)?.apply()
    }
}

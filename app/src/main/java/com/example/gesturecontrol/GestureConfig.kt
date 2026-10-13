package com.example.gesturecontrol

import android.content.Context
import android.content.SharedPreferences
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/** إيماءة جاهزة داخل التطبيق. */
data class Builtin(val id: String, val label: String, val defaultAction: Command)

/** إيماءة سجّلها المستخدم بيده (قالب من 21 نقطة). */
class CustomGesture(val id: String, val name: String, val template: FloatArray)

/** حسابات مساعدة على نقاط اليد. */
object HandMath {
    /** متجه من 42 رقماً: النقاط نسبةً للمعصم ومقسومة على حجم الكف (مستقلة عن بُعد اليد). */
    fun vector(lm: List<NormalizedLandmark>): FloatArray {
        val w = lm[0]
        val scale = hypot(lm[9].x() - w.x(), lm[9].y() - w.y()).coerceAtLeast(1e-3f)
        return FloatArray(42) { i ->
            val p = lm[i / 2]
            if (i % 2 == 0) (p.x() - w.x()) / scale else (p.y() - w.y()) / scale
        }
    }

    /** متوسط المسافة بين النقاط المتقابلة (كلما قلّ كان التشابه أكبر). */
    fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until 21) sum += hypot(a[i * 2] - b[i * 2], a[i * 2 + 1] - b[i * 2 + 1])
        return sum / 21f
    }
}

/** إعدادات الإيماءات: تفعيل/إلغاء، الأمر المرتبط، والإيماءات المسجّلة. تُحفظ على الهاتف. */
object GestureConfig {

    const val CUSTOM_THRESHOLD = 0.30f

    val BUILTINS = listOf(
        Builtin("POINT_UP", "☝️ سبابة للأعلى", Command.SWIPE_UP),
        Builtin("POINT_DOWN", "👇 سبابة للأسفل", Command.SWIPE_DOWN),
        Builtin("FIST", "✊ قبضة (تثبيت)", Command.HOME),
        Builtin("PEACE", "✌️ علامة النصر (تثبيت)", Command.BACK),
        Builtin("OPEN_PALM", "🖐 كف مفتوح (تثبيت)", Command.RECENTS),
        Builtin("THUMBS_UP", "👍 إبهام للأعلى (تثبيت)", Command.CLICK),
        Builtin("SWIPE_UP", "⬆️ تحريك الكف لأعلى", Command.SWIPE_UP),
        Builtin("SWIPE_DOWN", "⬇️ تحريك الكف لأسفل", Command.SWIPE_DOWN),
        Builtin("SWIPE_LEFT", "⬅️ تحريك الكف يساراً", Command.SWIPE_LEFT),
        Builtin("SWIPE_RIGHT", "➡️ تحريك الكف يميناً", Command.SWIPE_RIGHT)
    )

    private lateinit var prefs: SharedPreferences
    private var inited = false

    @Volatile
    var customs: List<CustomGesture> = emptyList()
        private set

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        prefs = context.applicationContext.getSharedPreferences("gestures", Context.MODE_PRIVATE)
        loadCustoms()
        inited = true
    }

    fun isEnabled(id: String): Boolean = prefs.getBoolean("enabled_$id", true)

    fun setEnabled(id: String, value: Boolean) {
        prefs.edit().putBoolean("enabled_$id", value).apply()
    }

    fun action(id: String): Command? {
        val saved = prefs.getString("action_$id", null)
            ?: return BUILTINS.firstOrNull { it.id == id }?.defaultAction
        if (saved == "NONE") return null
        return try {
            Command.valueOf(saved)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun setAction(id: String, command: Command?) {
        prefs.edit().putString("action_$id", command?.name ?: "NONE").apply()
    }

    fun labelOf(id: String): String =
        BUILTINS.firstOrNull { it.id == id }?.label
            ?: customs.firstOrNull { it.id == id }?.let { "⭐ ${it.name}" }
            ?: id

    // ---------- الإيماءات المسجّلة ----------

    fun matchCustom(vec: FloatArray): CustomGesture? {
        var best: CustomGesture? = null
        var bestDistance = CUSTOM_THRESHOLD
        for (c in customs) {
            if (!isEnabled(c.id)) continue
            val d = HandMath.distance(vec, c.template)
            if (d < bestDistance) {
                best = c
                bestDistance = d
            }
        }
        return best
    }

    @Synchronized
    fun addCustom(name: String, template: FloatArray, command: Command?) {
        val id = "custom:${System.currentTimeMillis()}"
        customs = customs + CustomGesture(id, name, template)
        saveCustoms()
        setAction(id, command)
        setEnabled(id, true)
    }

    @Synchronized
    fun removeCustom(id: String) {
        customs = customs.filter { it.id != id }
        saveCustoms()
        prefs.edit().remove("enabled_$id").remove("action_$id").apply()
    }

    private fun loadCustoms() {
        val arr = try {
            JSONArray(prefs.getString("customs", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val list = ArrayList<CustomGesture>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.getJSONArray("t")
            if (t.length() != 42) continue
            list += CustomGesture(
                o.getString("id"),
                o.getString("name"),
                FloatArray(42) { t.getDouble(it).toFloat() }
            )
        }
        customs = list
    }

    private fun saveCustoms() {
        val arr = JSONArray()
        for (c in customs) {
            val t = JSONArray()
            c.template.forEach { t.put(it.toDouble()) }
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("t", t))
        }
        prefs.edit().putString("customs", arr.toString()).apply()
    }
}

/** تسجيل قالب إيماءة جديدة: يجمع عدة إطارات ويحسب متوسطها. */
object GestureRecorder {
    @Volatile
    var active = false
        private set

    private val samples = ArrayList<FloatArray>()
    private var target = 25

    /** يمتلئ بالقالب عند اكتمال التسجيل. */
    val result = MutableStateFlow<FloatArray?>(null)

    @Synchronized
    fun start(frames: Int = 25) {
        samples.clear()
        result.value = null
        target = frames
        active = true
    }

    @Synchronized
    fun cancel() {
        active = false
        samples.clear()
    }

    @Synchronized
    fun feed(lm: List<NormalizedLandmark>) {
        if (!active) return
        samples += HandMath.vector(lm)
        if (samples.size >= target) {
            val avg = FloatArray(42)
            for (s in samples) for (i in 0 until 42) avg[i] += s[i]
            for (i in 0 until 42) avg[i] /= samples.size
            samples.clear()
            active = false
            result.value = avg
        }
    }
}

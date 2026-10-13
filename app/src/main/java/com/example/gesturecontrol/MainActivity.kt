package com.example.gesturecontrol

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var statusText: TextView
    private lateinit var gestureText: TextView
    private lateinit var actionText: TextView
    private lateinit var toggleButton: Button
    private lateinit var preview: HandPreviewView
    private lateinit var gesturesContainer: LinearLayout
    private lateinit var a11yStatus: TextView
    private lateinit var a11yButton: Button

    /** الأوامر المتاحة في القوائم: الخيار الأول "بدون أمر". */
    private val commandOptions: List<Command?> = listOf<Command?>(null) + Command.values()
    private val commandLabels: List<String> = commandOptions.map { it?.label ?: "بدون أمر" }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startGestureService()
            } else {
                GestureState.status.value = "إذن الكاميرا مطلوب"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GestureConfig.init(applicationContext)
        setContentView(R.layout.activity_main)

        scrollView = findViewById(R.id.scrollView)
        statusText = findViewById(R.id.statusText)
        gestureText = findViewById(R.id.gestureText)
        actionText = findViewById(R.id.actionText)
        toggleButton = findViewById(R.id.toggleButton)
        preview = findViewById(R.id.preview)

        PointerSettings.init(applicationContext)
        findViewById<SwitchCompat>(R.id.pointerSwitch).apply {
            isChecked = PointerSettings.enabled
            setOnCheckedChangeListener { _, checked -> PointerSettings.setEnabled(checked) }
        }
        findViewById<android.widget.SeekBar>(R.id.pointerRange).apply {
            progress = ((PointerSettings.range - 0.4f) * 100f).toInt().coerceIn(0, 50)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) PointerSettings.setRange(0.4f + value / 100f)
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
            })
        }
        gesturesContainer = findViewById(R.id.gesturesContainer)

        a11yStatus = findViewById(R.id.a11yStatus)
        a11yButton = findViewById(R.id.accessibilityButton)
        a11yButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        toggleButton.setOnClickListener {
            if (GestureState.running.value) {
                stopService(Intent(this, GestureService::class.java))
            } else {
                requestPermissionsAndStart()
            }
        }

        findViewById<Button>(R.id.addGestureButton).setOnClickListener { showAddDialog() }

        refreshGestureList()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { GestureState.status.collect { statusText.text = it } }
                launch { GestureState.gesture.collect { gestureText.text = it } }
                launch { GestureState.lastAction.collect { actionText.text = "آخر أمر: $it" } }
                launch { GestureState.frame.collect { preview.bitmap = it } }
                launch { GestureState.points.collect { preview.points = it } }
                launch { GestureState.gesture.collect { preview.label = it } }
                launch { GestureState.holdProgress.collect { preview.progress = it } }
                launch {
                    GestureState.running.collect { running ->
                        toggleButton.text = if (running) "إيقاف التحكم" else "تشغيل التحكم"
                        // أحمر عند الإيقاف وسماوي عند التشغيل (نفس ألوان نقطة المؤشر)
                        toggleButton.backgroundTintList = ColorStateList.valueOf(
                            color(if (running) R.color.touch else R.color.brand)
                        )
                        toggleButton.setTextColor(
                            color(if (running) R.color.on_touch else R.color.on_brand)
                        )
                        statusText.compoundDrawableTintList = ColorStateList.valueOf(
                            color(if (running) R.color.live else R.color.idle)
                        )
                        if (!running) {
                            GestureState.frame.value = null
                            GestureState.points.value = null
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        GestureState.acquirePreview()
    }

    override fun onStop() {
        GestureState.releasePreview()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityRow()
        if (ControlAccessibilityService.instance == null && !GestureState.running.value) {
            GestureState.status.value = "فعّل خدمة إمكانية الوصول (الخطوة 1) ثم شغّل التحكم"
        }
    }

    // ---------------- قائمة الإيماءات ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refreshGestureList() {
        gesturesContainer.removeAllViews()
        GestureConfig.BUILTINS.forEach { addGestureRow(it.id, it.label, deletable = false) }
        GestureConfig.customs.forEach { addGestureRow(it.id, "⭐ ${it.name}", deletable = true) }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** حالة خدمة إمكانية الوصول: لونها وزرها يتغيران حسب التفعيل. */
    private fun updateAccessibilityRow() {
        val on = ControlAccessibilityService.instance != null
        a11yStatus.text =
            if (on) "خدمة إمكانية الوصول مفعّلة." else "خدمة إمكانية الوصول غير مفعّلة. فعّلها لتعمل الأوامر."
        a11yStatus.setTextColor(color(if (on) R.color.ink else R.color.touch))
        a11yButton.text = if (on) "الإعدادات" else "تفعيل"
    }

    private fun addGestureRow(id: String, title: String, deletable: Boolean) {
        // خط فاصل بين الصفوف (القائمة مجموعة واحدة وليست بطاقات منفصلة)
        if (gesturesContainer.childCount > 0) {
            gesturesContainer.addView(View(this).apply {
                setBackgroundColor(color(R.color.line))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(color(R.color.ink))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (deletable) {
            top.addView(TextView(this).apply {
                text = "حذف"
                textSize = 14f
                setTextColor(color(R.color.touch))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { confirmDelete(id) }
            })
        }
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = GestureConfig.isEnabled(id)
            setOnCheckedChangeListener { _, checked ->
                GestureConfig.setEnabled(id, checked)
                row.alpha = if (checked) 1f else 0.5f
            }
        }
        row.alpha = if (sw.isChecked) 1f else 0.5f
        top.addView(sw)
        row.addView(top)

        // قائمة الأمر داخل حقل مدوّر بسهم خاص
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(color(R.color.field))
            }
            setPadding(dp(12), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(10) }
        }
        val spinner = Spinner(this).apply {
            background = null
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                commandLabels
            )
            setSelection(commandOptions.indexOf(GestureConfig.action(id)).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, itemId: Long
                ) {
                    GestureConfig.setAction(id, commandOptions[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        box.addView(spinner)
        box.addView(TextView(this).apply {
            text = "▾"
            textSize = 16f
            setTextColor(color(R.color.muted))
        })
        row.addView(box)

        gesturesContainer.addView(row)
    }

    private fun confirmDelete(id: String) {
        AlertDialog.Builder(this)
            .setTitle("حذف الإيماءة؟")
            .setPositiveButton("حذف") { _, _ ->
                GestureConfig.removeCustom(id)
                refreshGestureList()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------- إضافة إيماءة جديدة ----------------

    private fun showAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = "بعد الضغط على «تسجيل» سيبدأ عدّ تنازلي 3 ثوانٍ، اعمل خلاله الإيماءة أمام الكاميرا الأمامية وثبّتها."
            textSize = 13f
        })
        val nameInput = EditText(this).apply { hint = "اسم الإيماءة (مثلاً: إبهام وخنصر)" }
        box.addView(nameInput)
        box.addView(TextView(this).apply {
            text = "الأمر الذي تنفّذه:"
            setPadding(0, dp(12), 0, 0)
        })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                commandLabels
            )
            // الخيار الأول بعد "بدون أمر" كافتراضي
            setSelection(1)
        }
        box.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle("إيماءة جديدة")
            .setView(box)
            .setPositiveButton("تسجيل") { _, _ ->
                val name = nameInput.text.toString().trim()
                    .ifEmpty { "إيماءة ${GestureConfig.customs.size + 1}" }
                recordNewGesture(name, commandOptions[spinner.selectedItemPosition])
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun recordNewGesture(name: String, command: Command?) {
        if (!GestureState.running.value) {
            Toast.makeText(this, "شغّل التحكم بالإيماءات أولاً (الخطوة 2)", Toast.LENGTH_LONG).show()
            return
        }
        scrollView.smoothScrollTo(0, 0)

        lifecycleScope.launch {
            for (n in 3 downTo 1) {
                preview.banner = "جهّز يدك… $n"
                delay(1000)
            }
            preview.banner = "ثبّت الإيماءة…"
            GestureRecorder.start(25)
            val template = withTimeoutOrNull(8000) {
                GestureRecorder.result.filterNotNull().first()
            }
            GestureRecorder.cancel()

            if (template == null) {
                preview.banner = "لم تظهر يدك، حاول مرة أخرى"
                delay(2000)
                preview.banner = ""
                return@launch
            }

            GestureConfig.addCustom(name, template, command)
            preview.banner = "تم حفظ «$name» ✓"
            delay(1500)
            preview.banner = ""
            refreshGestureList()
        }
    }

    // ---------------- الأذونات والتشغيل ----------------

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) startGestureService()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startGestureService() {
        ContextCompat.startForegroundService(this, Intent(this, GestureService::class.java))
    }
}

package com.pranav.focuslock

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var pinInput: EditText
    private lateinit var permsColumn: LinearLayout
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(buildUi())
        ScheduleEngine.scheduleNext(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF0B0F14.toInt())
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(44))
        }
        scroll.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        col.addView(title("Focus Lock"))
        col.addView(body("A calls-only night screen that takes over automatically. Everything else is hidden until morning.", 14f, 0xFF8A94A6.toInt()))

        statusView = body("", 16f, Color.WHITE)
        val slp = lp()
        slp.topMargin = dp(18)
        col.addView(statusView, slp)

        enableSwitch = Switch(this).apply {
            text = "Nightly schedule"
            setTextColor(Color.WHITE)
            isChecked = Prefs.enabled
            setOnCheckedChangeListener { _, checked ->
                Prefs.enabled = checked
                ScheduleEngine.scheduleNext(this@MainActivity)
                if (!checked) NightService.stop(this@MainActivity)
                refresh()
            }
        }
        val elp = lp()
        elp.topMargin = dp(12)
        col.addView(enableSwitch, elp)

        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply { setOnClickListener { pickTime(true) } }
        endButton = Button(this).apply { setOnClickListener { pickTime(false) } }
        times.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        times.addView(endButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tlp = lp()
        tlp.topMargin = dp(8)
        col.addView(times, tlp)

        val tests = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val preview = Button(this).apply {
            text = "Preview 2 min"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    toast("Grant 'Display over other apps' first")
                } else {
                    NightService.start(this@MainActivity, force = true, autoStopMs = 120_000L)
                    toast("Preview started")
                }
            }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { NightService.stop(this@MainActivity) }
        }
        tests.addView(preview, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tests.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        tests.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tslp = lp()
        tslp.topMargin = dp(4)
        col.addView(tests, tslp)

        col.addView(header("Exit PIN"))
        val pinRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
            setText(Prefs.pin)
            setTextColor(Color.WHITE)
            hint = "4 digits"
        }
        pinRow.addView(pinInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val savePin = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val v = pinInput.text.toString()
                if (v.length == 4) {
                    Prefs.pin = v
                    toast("PIN saved")
                } else {
                    toast("PIN must be 4 digits")
                }
            }
        }
        pinRow.addView(savePin)
        col.addView(pinRow)
        col.addView(body("Used to end night mode early from the night screen. Default: 1234", 12f, 0xFF5A6472.toInt()))

        col.addView(header("Permissions"))
        permsColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(permsColumn)

        col.addView(header("Activity log"))
        logView = body("", 11f, 0xFF6B7686.toInt()).apply { typeface = Typeface.MONOSPACE }
        col.addView(logView)

        return scroll
    }

    private fun refresh() {
        val inWindow = Prefs.enabled && ScheduleEngine.isInWindow() &&
            System.currentTimeMillis() >= Prefs.snoozeUntilMs
        val next = ScheduleEngine.nextBoundary().format(DateTimeFormatter.ofPattern("EEE h:mm a"))
        statusView.text = if (inWindow) {
            "● Night mode ACTIVE — ends at ${Format.minutes(Prefs.endMinutes)}"
        } else {
            "Idle — next night mode: $next"
        }
        statusView.setTextColor(if (inWindow) 0xFF66D19E.toInt() else 0xFF9AA4B2.toInt())
        startButton.text = "From: ${Format.minutes(Prefs.startMinutes)}"
        endButton.text = "To: ${Format.minutes(Prefs.endMinutes)}"

        permsColumn.removeAllViews()
        addPerm("Display over other apps", hasOverlay()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addPerm("Accessibility watch", isA11yEnabled()) {
            toast("If the toggle is greyed out: App info → ⋮ → Allow restricted settings")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addPerm("Usage access (fallback)", hasUsageAccess()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addPerm("Notifications", hasNotif()) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
        addPerm("Ignore battery optimization", ignoringBattery()) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT in 31..32) {
            addPerm("Exact alarms", canExact()) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        logView.text = Logx.snapshot().takeLast(30).joinToString("\n").ifEmpty { "No events yet." }
    }

    private fun addPerm(name: String, ok: Boolean, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = if (ok) "✓  " else "✗  "
        row.addView(
            body(
                mark + name, 14f,
                if (ok) 0xFF66D19E.toInt() else 0xFFE5534B.toInt()
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (!ok) {
            row.addView(Button(this).apply {
                text = "Grant"
                setOnClickListener { action() }
            })
        }
        val rlp = lp()
        rlp.topMargin = dp(2)
        permsColumn.addView(row, rlp)
    }

    // ---------------- checks ----------------

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, LockAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
        }
    }

    private fun hasNotif(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBattery(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun canExact(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    // ---------------- actions ----------------

    private fun pickTime(start: Boolean) {
        val cur = if (start) Prefs.startMinutes else Prefs.endMinutes
        TimePickerDialog(this, { _, h, m ->
            if (start) Prefs.startMinutes = h * 60 + m else Prefs.endMinutes = h * 60 + m
            ScheduleEngine.scheduleNext(this)
            refresh()
        }, cur / 60, cur % 60, false).show()
    }

    // ---------------- helpers ----------------

    private fun title(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(s: String) = TextView(this).apply {
        text = s.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFF5A6472.toInt())
        letterSpacing = 0.25f
        setPadding(0, dp(26), 0, dp(6))
    }

    private fun body(s: String, sp: Float, c: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(c)
        setLineSpacing(0f, 1.2f)
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

package com.pranav.focuslock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The full-screen "ultra power saving" style night screen.
 * Black, minimal, big clock, one Phone button, an unobtrusive PIN exit.
 */
class NightShellView(context: Context) : FrameLayout(context) {

    /** Invoked when the correct PIN has been entered. */
    var onExitRequested: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = DateTimeFormatter.ofPattern("h:mm")
    private val ampmFmt = DateTimeFormatter.ofPattern("a")
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")

    private val mainColumn: LinearLayout
    private val exitButton: ImageView
    private val clockView: TextView
    private val ampmView: TextView
    private val dateView: TextView
    private val batteryView: TextView
    private val pinPanel: LinearLayout
    private val dotsRow: LinearLayout
    private val pinError: TextView
    private val shortcutsWrap: LinearLayout

    private val iconCache = HashMap<String, Drawable>()
    private val labelCache = HashMap<String, CharSequence>()
    private var lastShortcutPkgs: List<String>? = null

    private var entered = ""
    private var batteryRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = updateBattery(i)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        Prefs.init(context)
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true

        mainColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(84), dp(28), dp(48))
        }
        addView(mainColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        mainColumn.addView(
            label(
                "NIGHT MODE · ${Format.minutes(Prefs.startMinutes)} – ${Format.minutes(Prefs.endMinutes)}",
                12f
            )
        )

        mainColumn.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        clockView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 92f)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }
        ampmView = TextView(context).apply {
            setTextColor(0xFF9AA4B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val clockRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        clockRow.addView(clockView)
        val ampLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        ampLp.leftMargin = dp(10)
        ampLp.gravity = Gravity.BOTTOM
        clockRow.addView(ampmView, ampLp)
        mainColumn.addView(
            clockRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        dateView = TextView(context).apply {
            setTextColor(0xFF9AA4B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        mainColumn.addView(
            dateView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        mainColumn.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        batteryView = TextView(context).apply {
            setTextColor(0xFF5A6472.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        mainColumn.addView(
            batteryView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Phone button
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener { openDialer() }
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_phone)
            setColorFilter(Color.BLACK)
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        circle.addView(
            icon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val circleLp = LinearLayout.LayoutParams(dp(88), dp(88))
        circleLp.topMargin = dp(36)
        mainColumn.addView(circle, circleLp)

        val phoneLabel = TextView(context).apply {
            text = "Phone"
            setTextColor(0xFF9AA4B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val plp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        plp.topMargin = dp(12)
        mainColumn.addView(phoneLabel, plp)

        // One-tap shortcuts for the apps the user allows at night.
        shortcutsWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val swLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        swLp.topMargin = dp(26)
        mainColumn.addView(shortcutsWrap, swLp)
        refreshShortcuts()

        val hint = TextView(context).apply {
            text = "Emergency calls work from the dialer"
            setTextColor(0xFF3F4753.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val hlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        hlp.topMargin = dp(30)
        mainColumn.addView(hint, hlp)

        // Discreet exit button (bottom-right)
        exitButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(0xFF3A4150.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { showPin(true) }
        }
        val exitLp = LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(16)
            bottomMargin = dp(28)
        }
        addView(exitButton, exitLp)

        // ------- PIN panel -------
        pinPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(32), dp(96), dp(32), dp(48))
            isClickable = true
            visibility = View.GONE
        }
        pinPanel.addView(TextView(context).apply {
            text = "Enter PIN"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })
        pinError = TextView(context).apply {
            text = " "
            setTextColor(0xFFE5534B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val peLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        peLp.topMargin = dp(12)
        pinPanel.addView(pinError, peLp)

        dotsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(4) {
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF2A303C.toInt())
                }
            }
            val dlp = LinearLayout.LayoutParams(dp(16), dp(16))
            dlp.marginStart = dp(10)
            dlp.marginEnd = dp(10)
            dotsRow.addView(dot, dlp)
        }
        val drLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        drLp.topMargin = dp(32)
        pinPanel.addView(dotsRow, drLp)

        pinPanel.addView(View(context), LinearLayout.LayoutParams(1, dp(28)))

        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("Cancel", "0", "⌫")
        )
        for (row in keys) {
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in row) {
                val key = TextView(context).apply {
                    text = k
                    gravity = Gravity.CENTER
                    setTextColor(if (k.length == 1) Color.WHITE else 0xFF9AA4B2.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.length == 1) 30f else 15f)
                    isClickable = true
                    setOnClickListener { pressKey(k) }
                }
                rowLayout.addView(key, LinearLayout.LayoutParams(0, dp(72), 1f))
            }
            val rLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rLp.topMargin = dp(2)
            pinPanel.addView(rowLayout, rLp)
        }

        addView(pinPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        updateClock()
    }

    // ------- lifecycle -------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            val sticky = context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryRegistered = true
            updateBattery(sticky)
        } catch (t: Throwable) {
            Logx.add("battery: ${t.javaClass.simpleName}")
        }
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        if (batteryRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: Throwable) {
            }
            batteryRegistered = false
        }
        super.onDetachedFromWindow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (pinPanel.visibility == View.VISIBLE) showPin(false)
            return true // swallow back: this screen is the night mode
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------- actions -------

    private fun openDialer() {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Logx.add("dialer launch failed: ${t.message}")
        }
    }

    /**
     * Rebuilds the shortcut row for the user's allowed apps. Cheap no-op when the
     * allowed set hasn't changed, so it is also called whenever the shell reappears.
     */
    fun refreshShortcuts() {
        val pkgs = Prefs.allowedPackages
            .filter { it !in AllowedApps.MANDATORY && it != context.packageName }
            .sorted()
        if (pkgs == lastShortcutPkgs) return
        lastShortcutPkgs = pkgs
        shortcutsWrap.removeAllViews()
        if (pkgs.isEmpty()) {
            shortcutsWrap.visibility = View.GONE
            return
        }
        val pm = context.packageManager
        val entries = ArrayList<Triple<String, CharSequence, Drawable?>>()
        for (p in pkgs) {
            val launchable = try {
                pm.getLaunchIntentForPackage(p) != null
            } catch (_: Throwable) {
                false
            }
            if (!launchable) continue
            val label = labelCache.getOrPut(p) {
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(p, 0))
                } catch (_: Throwable) {
                    p
                }
            }
            var icon = iconCache[p]
            if (icon == null) {
                icon = try {
                    pm.getApplicationIcon(p)
                } catch (_: Throwable) {
                    null
                }
                if (icon != null) iconCache[p] = icon
            }
            entries.add(Triple(p, label, icon))
        }
        if (entries.isEmpty()) {
            shortcutsWrap.visibility = View.GONE
            return
        }
        shortcutsWrap.visibility = View.VISIBLE
        val maxApps = 8
        var i = 0
        while (i < entries.size && i < maxApps) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            var j = i
            while (j < entries.size && j < i + 4 && j < maxApps) {
                val (pkg, label, icon) = entries[j]
                row.addView(shortcutTile(pkg, label, icon))
                j++
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (i > 0) lp.topMargin = dp(12)
            shortcutsWrap.addView(row, lp)
            i += 4
        }
    }

    private fun shortcutTile(pkg: String, label: CharSequence, icon: Drawable?): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1A1F27.toInt())
            }
            setOnClickListener { launchPackage(pkg) }
        }
        if (icon != null) {
            val iv = ImageView(context).apply { setImageDrawable(icon) }
            circle.addView(
                iv,
                FrameLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER }
            )
        } else {
            val letter = TextView(context).apply {
                text = label.toString().take(1).uppercase()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
            circle.addView(
                letter,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }
        col.addView(
            circle,
            LinearLayout.LayoutParams(dp(60), dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        val labelView = TextView(context).apply {
            text = label
            setTextColor(0xFF9AA4B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        val llp = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
        llp.topMargin = dp(8)
        col.addView(labelView, llp)
        col.layoutParams = LinearLayout.LayoutParams(
            dp(74),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
        return col
    }

    private fun launchPackage(pkg: String) {
        try {
            val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Logx.add("launch failed: $pkg")
        }
    }

    private fun showPin(show: Boolean) {
        pinPanel.visibility = if (show) View.VISIBLE else View.GONE
        mainColumn.visibility = if (show) View.GONE else View.VISIBLE
        exitButton.visibility = if (show) View.GONE else View.VISIBLE
        entered = ""
        pinError.text = " "
        updateDots()
    }

    private fun pressKey(k: String) {
        when (k) {
            "⌫" -> if (entered.isNotEmpty()) entered = entered.dropLast(1)
            "Cancel" -> {
                showPin(false)
                return
            }
            else -> if (entered.length < 4) entered += k
        }
        pinError.text = " "
        updateDots()
        if (entered.length == 4) {
            if (entered == Prefs.pin) {
                Logx.add("pin accepted: ending for tonight")
                onExitRequested?.invoke()
            } else {
                pinError.text = "Wrong PIN"
                entered = ""
                updateDots()
            }
        }
    }

    private fun updateDots() {
        for (i in 0 until dotsRow.childCount) {
            val filled = i < entered.length
            (dotsRow.getChildAt(i).background as? GradientDrawable)?.setColor(
                if (filled) 0xFFEDEFF3.toInt() else 0xFF2A303C.toInt()
            )
        }
    }

    private fun updateClock() {
        val now = LocalDateTime.now()
        clockView.text = now.format(clockFmt)
        ampmView.text = now.format(ampmFmt)
        dateView.text = now.format(dateFmt)
    }

    private fun updateBattery(intent: Intent?) {
        if (intent == null) {
            batteryView.text = ""
            return
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryView.text =
            if (level >= 0 && scale > 0) "${level * 100 / scale}% battery" else ""
    }

    // ------- helpers -------

    private fun label(s: String, sp: Float): TextView = TextView(context).apply {
        text = s
        setTextColor(0xFF5A6472.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        letterSpacing = 0.25f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

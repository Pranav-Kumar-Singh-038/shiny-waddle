package com.pranav.focuslock

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Allowed at night" screen: pick which apps may stay open during night mode.
 * The dialer is pinned (always allowed) so calls keep working.
 */
class AppPickerActivity : Activity() {

    private data class Entry(val pkg: String, val label: String, val icon: Drawable?)

    private val all = ArrayList<Entry>()
    private lateinit var listColumn: LinearLayout

    private val notRecommended = setOf(
        "com.android.settings",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(buildUi())
        loadApps()
        rebuildRows("")
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF0B0F14.toInt())
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        scroll.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        col.addView(TextView(this).apply {
            text = "Allowed at night"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Phone calls always get through. Choose any other apps you want to stay available during night mode."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF8A94A6.toInt())
            setLineSpacing(0f, 1.2f)
        })

        val locked = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(4))
        }
        locked.addView(CheckBox(this).apply {
            isChecked = true
            isEnabled = false
        })
        locked.addView(TextView(this).apply {
            text = "Phone  ·  always allowed"
            setTextColor(0xFF9AA4B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        col.addView(locked)

        val search = EditText(this).apply {
            hint = "Search apps"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF5A6472.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    rebuildRows(s?.toString() ?: "")
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dp(10)
        col.addView(search, slp)

        listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listColumn)

        return scroll
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg in AllowedApps.MANDATORY) continue
            if (!seen.add(pkg)) continue
            all.add(
                Entry(
                    pkg,
                    ri.loadLabel(pm).toString(),
                    runCatching { ri.loadIcon(pm) }.getOrNull()
                )
            )
        }
        all.sortBy { it.label.lowercase() }
    }

    private fun rebuildRows(query: String) {
        listColumn.removeAllViews()
        val q = query.trim().lowercase()
        for (e in all) {
            if (q.isNotEmpty() && !e.label.lowercase().contains(q)) continue
            listColumn.addView(buildRow(e))
        }
    }

    private fun buildRow(e: Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
        }
        val box = CheckBox(this).apply {
            isChecked = Prefs.allowedPackages.contains(e.pkg)
        }
        val icon = ImageView(this).apply {
            e.icon?.let { setImageDrawable(it) }
        }
        val labelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labelCol.addView(TextView(this).apply {
            text = e.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        labelCol.addView(TextView(this).apply {
            text = if (e.pkg in notRecommended) "⚠ allowing this lets you escape night mode" else e.pkg
            setTextColor(if (e.pkg in notRecommended) 0xFFE5534B.toInt() else 0xFF5A6472.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        row.addView(box)
        val ilp = LinearLayout.LayoutParams(dp(40), dp(40))
        ilp.marginStart = dp(6)
        ilp.marginEnd = dp(12)
        row.addView(icon, ilp)
        row.addView(labelCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener {
            box.isChecked = !box.isChecked
            onToggle(e.pkg, box.isChecked)
        }
        box.setOnClickListener { onToggle(e.pkg, box.isChecked) }
        return row
    }

    private fun onToggle(pkg: String, allowed: Boolean) {
        val set = Prefs.allowedPackages.toMutableSet()
        if (allowed) set.add(pkg) else set.remove(pkg)
        Prefs.allowedPackages = set
        Logx.add((if (allowed) "allowed: " else "removed: ") + pkg)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

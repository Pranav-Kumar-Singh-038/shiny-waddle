package com.pranav.focuslock

import android.content.Context
import android.content.SharedPreferences

/**
 * All app state lives in device-protected storage so the night service can read it
 * even after a reboot before the first unlock (direct boot).
 */
object Prefs {
    private const val FILE = "focuslock"

    @Volatile
    private var sp: SharedPreferences? = null

    fun init(context: Context): SharedPreferences {
        sp?.let { return it }
        synchronized(this) {
            sp?.let { return it }
            val ctx = context.createDeviceProtectedStorageContext()
            val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            sp = p
            return p
        }
    }

    private fun s(): SharedPreferences =
        sp ?: throw IllegalStateException("Prefs.init() was not called")

    var enabled: Boolean
        get() = s().getBoolean("enabled", true)
        set(v) {
            s().edit().putBoolean("enabled", v).apply()
        }

    var startMinutes: Int
        get() = s().getInt("start_min", 23 * 60)
        set(v) {
            s().edit().putInt("start_min", v).apply()
        }

    var endMinutes: Int
        get() = s().getInt("end_min", 6 * 60)
        set(v) {
            s().edit().putInt("end_min", v).apply()
        }

    var pin: String
        get() = s().getString("pin", "1234") ?: "1234"
        set(v) {
            s().edit().putString("pin", v).apply()
        }

    /** While now < snoozeUntilMs, night mode will not start (used by "end for tonight"). */
    var snoozeUntilMs: Long
        get() = s().getLong("snooze_until", 0L)
        set(v) {
            s().edit().putLong("snooze_until", v).apply()
        }
}

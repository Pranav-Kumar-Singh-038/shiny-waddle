package com.pranav.focuslock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the exact alarms for window start/end (and the in-window heartbeat). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        val action = intent.action ?: return
        Logx.add("alarm: ${action.substringAfterLast('.')}")

        ScheduleEngine.scheduleNext(context)

        val active = Prefs.enabled &&
            ScheduleEngine.isInWindow() &&
            System.currentTimeMillis() >= Prefs.snoozeUntilMs

        if (action == ScheduleEngine.ACTION_HEARTBEAT) {
            if (active) {
                startServiceSafely(context)
                ScheduleEngine.scheduleHeartbeat(context)
            } else {
                ScheduleEngine.cancelHeartbeat(context)
            }
            return
        }

        // boundary
        if (active) startServiceSafely(context) else stopService(context)
    }

    private fun startServiceSafely(context: Context) {
        try {
            NightService.start(context)
        } catch (t: Throwable) {
            Logx.add("FGS start blocked: ${t.javaClass.simpleName}")
        }
    }

    private fun stopService(context: Context) {
        NightService.stop(context)
    }
}

package com.pranav.focuslock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot, time change and app-update events: keep the schedule armed and night mode correct. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        val action = intent.action ?: return
        Logx.add("system: ${action.substringAfterLast('.')}")

        ScheduleEngine.scheduleNext(context)

        val active = Prefs.enabled &&
            ScheduleEngine.isInWindow() &&
            System.currentTimeMillis() >= Prefs.snoozeUntilMs

        if (active) {
            try {
                NightService.start(context)
            } catch (t: Throwable) {
                Logx.add("FGS start blocked: ${t.javaClass.simpleName}")
            }
        } else {
            NightService.stop(context)
        }
    }
}

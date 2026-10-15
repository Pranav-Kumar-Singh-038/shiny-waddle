package com.pranav.focuslock

import android.app.Activity
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.time.ZoneId

/**
 * Runs while night mode is active: shows the night shell overlay and decides,
 * from foreground-app signals, whether it should be visible.
 */
class NightService : Service() {

    companion object {
        private const val ACTION_START = "com.pranav.focuslock.action.START"
        private const val ACTION_STOP = "com.pranav.focuslock.action.STOP"
        private const val ACTION_EXIT_TONIGHT = "com.pranav.focuslock.action.EXIT_TONIGHT"
        private const val EXTRA_FORCE = "force"
        private const val EXTRA_AUTO_STOP_MS = "auto_stop_ms"

        private const val CHANNEL_ID = "night_mode"
        private const val NOTIF_ID = 1

        @Volatile
        private var instance: NightService? = null

        fun start(context: Context, force: Boolean = false, autoStopMs: Long = 0L) {
            val i = Intent(context, NightService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FORCE, force)
                .putExtra(EXTRA_AUTO_STOP_MS, autoStopMs)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, NightService::class.java))
            } catch (_: Throwable) {
            }
        }

        /** Called by the accessibility service / usage poller with the current foreground app. */
        fun onForegroundPackage(pkg: String?) {
            instance?.handleForeground(pkg)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var overlay: NightShellView? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var overlayShown = false
    private var forced = false
    private var lastPkg: String? = null

    private val selfCheck = object : Runnable {
        override fun run() {
            if (!forced) {
                val active = Prefs.enabled && ScheduleEngine.isInWindow() &&
                    System.currentTimeMillis() >= Prefs.snoozeUntilMs
                if (!active) {
                    Logx.add("self-check: window over")
                    shutdown()
                    return
                }
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    private val usagePoll = object : Runnable {
        override fun run() {
            pollUsageOnce()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_EXIT_TONIGHT -> {
                snoozeTonight()
                return START_NOT_STICKY
            }
            else -> {
                // START, and also sticky-restarts with a null intent.
                forced = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
                val autoStopMs = intent?.getLongExtra(EXTRA_AUTO_STOP_MS, 0L) ?: 0L

                if (!forced) {
                    val active = Prefs.enabled && ScheduleEngine.isInWindow() &&
                        System.currentTimeMillis() >= Prefs.snoozeUntilMs
                    if (!active) {
                        Logx.add("start ignored (schedule inactive)")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                if (!Settings.canDrawOverlays(this)) {
                    Logx.add("start aborted: no overlay permission")
                    Toast.makeText(this, "Focus Lock: allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }

                startInForeground()
                ensureOverlay()
                ScheduleEngine.scheduleHeartbeat(this)

                handler.removeCallbacks(selfCheck)
                handler.post(selfCheck)
                handler.removeCallbacks(usagePoll)
                handler.post(usagePoll)

                if (autoStopMs > 0L) {
                    handler.postDelayed({
                        Logx.add("preview timer ended")
                        shutdown()
                    }, autoStopMs)
                }

                Logx.add(if (forced) "preview shown" else "night mode started")
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- foreground ----------

    private fun startInForeground() {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Night mode", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps Focus Lock night mode running"
            ch.setShowBadge(false)
            ch.enableVibration(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitTonight = PendingIntent.getService(
            this, 1,
            Intent(this, NightService::class.java).setAction(ACTION_EXIT_TONIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("Night mode · calls only")
            .setContentText("Ends at ${Format.minutes(Prefs.endMinutes)}")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "End for tonight", exitTonight)
            .build()
    }

    // ---------- overlay ----------

    private fun ensureOverlay() {
        if (overlay != null) return
        val v = NightShellView(this)
        v.onExitRequested = { snoozeTonight() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm?.addView(v, params)
            overlay = v
            overlayLp = params
            overlayShown = true
        } catch (t: Throwable) {
            Logx.add("overlay add failed: ${t.javaClass.simpleName}")
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
        overlay = null
        overlayLp = null
        overlayShown = false
    }

    private fun setOverlayShown(shown: Boolean) {
        val v = overlay ?: return
        val p = overlayLp ?: return
        if (shown == overlayShown) return
        overlayShown = shown
        if (shown) {
            p.flags = p.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            v.visibility = View.VISIBLE
        } else {
            p.flags = p.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            v.visibility = View.GONE
        }
        try {
            wm?.updateViewLayout(v, p)
        } catch (_: Throwable) {
        }
    }

    /** The policy: allowed apps (the dialer) keep the screen; everything else gets covered. */
    private fun handleForeground(pkg: String?) {
        val p = pkg ?: return
        if (p == packageName) {
            setOverlayShown(true)
            return
        }
        if (p == lastPkg) return
        lastPkg = p
        val allowed = AllowedApps.isAllowed(p)
        if (!allowed) Logx.add("blocked: $p")
        setOverlayShown(!allowed)
    }

    /** Safety net in case the accessibility service is off or misses an event. */
    private fun pollUsageOnce() {
        try {
            val appOps = getSystemService(AppOpsManager::class.java) ?: return
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            if (mode != AppOpsManager.MODE_ALLOWED) return

            val usm = getSystemService(UsageStatsManager::class.java) ?: return
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10_000L, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.packageName
                }
            }
            if (last != null) handleForeground(last)
        } catch (_: Throwable) {
        }
    }

    // ---------- control ----------

    private fun snoozeTonight() {
        val untilMs = if (ScheduleEngine.isInWindow()) {
            ScheduleEngine.windowEnd().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            System.currentTimeMillis()
        }
        Prefs.snoozeUntilMs = untilMs
        Logx.add("ended for tonight")
        ScheduleEngine.cancelHeartbeat(this)
        ScheduleEngine.scheduleNext(this)
        shutdown()
    }

    private fun shutdown() {
        Logx.add("night mode stopped")
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        stopForeground(true)
        stopSelf()
    }
}

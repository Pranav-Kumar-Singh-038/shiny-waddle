# Focus Lock

A nightly **calls-only lockdown** for Android. Between 11:00 PM and 6:00 AM a
minimal, ultra-power-saving-style black screen takes over the phone. Only the
dialer can stay in the foreground — incoming and outgoing calls (including
emergency calls) work normally, everything else gets covered. At 6:00 AM
everything goes back to normal, automatically.

No root, no factory reset, no account changes, no adb required to use it.

## How it works

| Piece | What it does |
|---|---|
| `ScheduleEngine` | Computes the overnight window (crossing midnight, DST-safe) and keeps exact `AlarmManager` alarms armed for the next boundary. Also arms a 15-minute heartbeat inside the window. |
| `AlarmReceiver` / `SystemEventReceiver` | Start/stop night mode at boundaries; survive reboots, time-zone changes and app updates (direct-boot aware). |
| `NightService` | Foreground service (`specialUse`). Owns the overlay. Decides visibility from foreground-app signals. Self-checks every 30 s as a safety net. |
| `NightShellView` | The night screen itself: black, big clock, date, battery, one Phone button, discreet PIN exit. Rendered as `TYPE_APPLICATION_OVERLAY`. |
| `LockAccessibilityService` | Watches `TYPE_WINDOW_STATE_CHANGED` events and tells the service which app is in the foreground. Also collapses the notification shade / recents when they open at night. |
| UsageStats poller (inside the service) | Fallback foreground detector (~1.5 s interval) in case accessibility misses an event. |

Policy: a foreground package is *allowed* only if it is in the always-allowed
dialer set or in the user's allow-list (Settings → **Choose apps…**). Anything
else — launcher, Settings, other apps — gets the night shell drawn over it.

## Phone-only mode details

- Incoming calls ring and are answerable; an in-call screen is the dialer package,
  so the overlay steps aside during calls and comes back afterwards.
- The Phone button launches `ACTION_DIAL`, so outgoing and emergency calls work.
- `Back` is swallowed by the night screen; Home / Recents / Settings fall back
  under the overlay.

## Setup (on the phone)

1. Install `app-debug.apk`.
2. Open **Focus Lock** and grant the checklist items (the app links to each):
   - **Display over other apps** (required — this is the night screen).
   - **Accessibility watch** — on Android 13+, if the toggle is greyed out, use
     *App info → ⋮ → Allow restricted settings* first.
   - **Usage access** (optional fallback detector).
   - **Notifications**, **Ignore battery optimization**.
3. Set the window times (default 23:00 → 06:00) and your 4-digit exit PIN.
4. Tap **Choose apps…** (Allowed at night) and tick anything you want usable
   during night mode. Phone calls are always allowed and can't be unchecked.
   Ticked apps appear as shortcut icons on the night screen.
5. Tap **Preview 2 min** to see it now.

## Ending night mode early

- From the night screen: tap the small lock icon (bottom-right) and enter your PIN.
- Or from the ongoing notification: **End for tonight**.
- Either way, the rest of tonight is skipped and the schedule resumes tomorrow.

## Build

```bash
# with a local Gradle 8.9+ / JDK 17+
gradle assembleDebug
# or open the folder in Android Studio and run the app module
```

Requires Android SDK platform 35. `minSdk 29`, `targetSdk 35`.

## Known limits

- Safe mode disables accessibility services (and uninstalling is possible during
  the day) — without Device-Owner provisioning this cannot be blocked.
- The notification shade is a system window above overlays; the accessibility
  service collapses it best-effort when it opens at night.
- If "Display over other apps" is revoked, the service refuses to start and logs it.

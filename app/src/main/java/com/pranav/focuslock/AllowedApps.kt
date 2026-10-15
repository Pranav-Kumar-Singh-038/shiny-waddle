package com.pranav.focuslock

/**
 * Policy for what may stay in the foreground during night mode.
 *
 * The dialer/telecom packages are always allowed so calls keep working.
 * Everything else is whatever the user picked on the "Allowed at night" screen.
 */
object AllowedApps {
    val MANDATORY: Set<String> = setOf(
        "com.google.android.dialer",   // Pixel dialer + in-call UI
        "com.android.dialer",          // AOSP dialer
        "com.android.incallui",        // older in-call UI
        "com.android.phone",           // telephony UI
        "com.android.server.telecom"   // telecom (never foreground UI, kept for safety)
    )

    fun isAllowed(pkg: String?): Boolean =
        pkg != null && (pkg in MANDATORY || pkg in Prefs.allowedPackages)
}

package com.pranav.focuslock

/** Apps that may stay in the foreground during night mode. Everything else gets covered. */
object AllowedApps {
    val PACKAGES: Set<String> = setOf(
        "com.google.android.dialer",   // Pixel dialer + in-call UI
        "com.android.dialer",          // AOSP dialer
        "com.android.incallui",        // older in-call UI
        "com.android.phone",           // telephony UI
        "com.android.server.telecom"   // telecom (never foreground UI, kept for safety)
    )

    fun isAllowed(pkg: String?): Boolean = pkg != null && pkg in PACKAGES
}

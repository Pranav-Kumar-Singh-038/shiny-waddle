package com.pranav.focuslock

import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Format {
    private val hma = DateTimeFormatter.ofPattern("h:mm a")

    /** Minutes-since-midnight -> "11:00 PM" */
    fun minutes(m: Int): String {
        val t = LocalTime.of((m / 60) % 24, m % 60)
        return t.format(hma)
    }
}

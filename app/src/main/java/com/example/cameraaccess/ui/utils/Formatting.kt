package com.example.cameraaccess.ui.utils

import java.util.Locale
import kotlin.math.roundToInt

fun formatShutterFromNs(ns: Long): String {
    if (ns <= 0L) return "—"
    val t = ns / 1_000_000_000.0
    return if (t < 1.0) {
        val denom = (1.0 / t).roundToInt().coerceAtLeast(1)
        "1/$denom s"
    } else {
        val sec = String.format(Locale.US, "%.2f", t)
        "$sec s"
    }
}

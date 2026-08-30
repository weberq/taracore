package dev.taracore.app.ui

import java.util.Locale
import kotlin.math.abs

/** Human byte sizes. Base 1000, because storage and RAM are both quoted that way. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return if (unit == 0 || value >= 100) {
        String.format(Locale.US, "%.0f %s", value, units[unit])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

/** A countdown, for the idle timer. */
fun formatDuration(ms: Long): String {
    if (ms < 0) return "never"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    } else {
        "${seconds}s"
    }
}

fun formatTokensPerSecond(tps: Double): String =
    if (tps <= 0) "—" else String.format(Locale.US, "%.1f tok/s", tps)

fun formatPercent(fraction: Float): String =
    String.format(Locale.US, "%.0f%%", (fraction * 100).coerceIn(0f, 100f))

/** Relative time for "last seen", kept coarse: exact seconds would be noise. */
fun formatRelativeTime(epochMs: Long): String {
    if (epochMs <= 0) return "never"
    val delta = abs(System.currentTimeMillis() - epochMs)
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}

package app.opah.tv.ui.views

/** A snapshot is never evidence that a live player is running. */
internal fun cameraPreviewStatus(
    loadedAtMillis: Long?,
    hasImage: Boolean,
    unavailable: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    val age = loadedAtMillis?.let { (nowMillis - it).coerceAtLeast(0) }
    val updated = when {
        age == null -> null
        age < 60_000L -> "just now"
        age < 3_600_000L -> "${age / 60_000L} min ago"
        age < 86_400_000L -> "${age / 3_600_000L} hr ago"
        else -> "${age / 86_400_000L} days ago"
    }
    return when {
        unavailable && hasImage && updated != null -> "Preview unavailable · last $updated"
        unavailable -> "Preview unavailable"
        !hasImage -> "Loading preview"
        updated != null -> "Preview · checked $updated"
        else -> "Preview"
    }
}

internal fun nextNativeMonitorExitMinutes(current: Int?): Int? = when (current) {
    null -> 30
    30 -> 60
    60 -> 120
    else -> null
}

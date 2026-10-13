package app.opah.tv.ui

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class HistoryHourChoice(
    val startMillis: Long,
    val label: String,
    val zoneLabel: String,
)

/** At most one week of local dates; paging never expands the mounted choice list. */
internal fun historyDateChoices(
    anchorMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.getDefault(),
): List<Long> {
    val calendar = Calendar.getInstance(zone).apply {
        timeInMillis = minOf(anchorMillis, nowMillis)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return buildList {
        repeat(7) {
            val start = (calendar.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
            if (start >= 0L) add(start)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
    }
}

internal fun shiftHistoryDate(dayMillis: Long, days: Int, zone: TimeZone = TimeZone.getDefault()): Long =
    Calendar.getInstance(zone).apply {
        timeInMillis = dayMillis
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, days)
        set(Calendar.HOUR_OF_DAY, 0)
    }.timeInMillis

/** Resolve wall-clock hours against real offsets instead of inventing or merging DST hours. */
internal fun historyHourChoices(
    dayMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
): List<HistoryHourChoice> {
    val localDay = Calendar.getInstance(zone).apply { timeInMillis = dayMillis }
    val wallMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(localDay.get(Calendar.YEAR), localDay.get(Calendar.MONTH), localDay.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
    val offsets = (-36..60).map { hour -> zone.getOffset(wallMidnight + hour * 3_600_000L) }.toSet()
    val formatter = DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = zone }
    return (0..23).flatMap { hour ->
        val wallHour = wallMidnight + hour * 3_600_000L
        offsets.mapNotNull { offset ->
            val instant = wallHour - offset
            if (instant < 0L || instant > nowMillis || zone.getOffset(instant) != offset) return@mapNotNull null
            val offsetMinutes = kotlin.math.abs(offset / 60_000)
            val offsetText = String.format(
                Locale.ROOT, "UTC%s%02d:%02d", if (offset < 0) "−" else "+", offsetMinutes / 60, offsetMinutes % 60,
            )
            HistoryHourChoice(
                startMillis = instant,
                label = formatter.format(Date(instant)),
                zoneLabel = "${zone.getDisplayName(zone.inDaylightTime(Date(instant)), TimeZone.SHORT, locale)} • $offsetText",
            )
        }
    }.distinctBy(HistoryHourChoice::startMillis).sortedBy(HistoryHourChoice::startMillis)
}

internal fun requestedHistoryHour(requested: Double?, fallback: Double, nowSeconds: Double): Double =
    (requested ?: fallback).takeIf { it.isFinite() && it >= 0.0 && it <= nowSeconds } ?: hourStart(nowSeconds)

package com.notionwidget.widget

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class DateLabel(val text: String, val overdue: Boolean, val today: Boolean)

private val SAME_YEAR = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
private val OTHER_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
private val HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** A parsed Notion date: local day and, if present, time. */
private class ParsedDate(val day: LocalDate, val dateTime: ZonedDateTime?)

private fun parse(raw: String?): ParsedDate? {
    if (raw.isNullOrBlank()) return null
    val dateTime = if (raw.length > 10) {
        runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()
    } else null
    val day = dateTime?.toLocalDate() ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return null
    return ParsedDate(day, dateTime)
}

private fun dayText(day: LocalDate, now: LocalDate) = when (ChronoUnit.DAYS.between(now, day)) {
    0L -> "Today"
    1L -> "Tomorrow"
    -1L -> "Yesterday"
    else -> day.format(if (day.year == now.year) SAME_YEAR else OTHER_YEAR)
}

/**
 * Turns a Notion date into "Today", "Tomorrow 10:00", "Wed, Oct 15"...
 * Multi-day ranges are judged by their END: in progress → "until Tue, Oct 28" (not overdue);
 * overdue only once the end has passed.
 */
fun dateLabel(start: String?, end: String? = null): DateLabel? {
    val s = parse(start) ?: return null
    val e = parse(end)
    val now = LocalDate.now()

    if (e != null && e.day != s.day && !now.isBefore(s.day)) {
        val overdue = e.dateTime?.toInstant()?.isBefore(Instant.now()) ?: e.day.isBefore(now)
        val text = if (overdue) dayText(e.day, now) else "until " + dayText(e.day, now).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it }
        return DateLabel(text = text, overdue = overdue, today = !overdue && e.day == now)
    }

    val text = dayText(s.day, now).let { d -> s.dateTime?.let { "$d ${it.format(HOUR)}" } ?: d }
    // A same-day range with times (11:00–13:00) becomes overdue when it ends, not when it starts.
    val endInstant = (e?.dateTime ?: s.dateTime)?.toInstant()
    val overdue = endInstant?.isBefore(Instant.now()) ?: s.day.isBefore(now)
    return DateLabel(text = text, overdue = overdue, today = s.day == now)
}

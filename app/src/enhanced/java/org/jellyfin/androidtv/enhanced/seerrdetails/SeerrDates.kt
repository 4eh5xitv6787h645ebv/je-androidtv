package org.jellyfin.androidtv.enhanced.seerrdetails

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/** Seerr timestamps (`2026-10-22T03:58:14.000Z`) for display. */
object SeerrDates {
	/** The local date of an ISO-8601 instant, or null when absent or malformed. */
	fun localDate(iso: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
		if (iso.isNullOrBlank()) return null
		return try {
			Instant.parse(iso).atZone(zone).toLocalDate()
		} catch (_: DateTimeParseException) {
			// Plain dates such as TMDB's air dates
			try {
				LocalDate.parse(iso.take(10))
			} catch (_: DateTimeParseException) {
				null
			}
		}
	}

	/** A medium localized date ("22 Oct 2026"), or null. */
	fun format(iso: String?): String? = localDate(iso)?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

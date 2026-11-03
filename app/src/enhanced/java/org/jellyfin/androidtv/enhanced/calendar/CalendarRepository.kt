package org.jellyfin.androidtv.enhanced.calendar

import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import java.time.LocalDate
import java.time.ZoneId

/** Calendar data from the plugin: `arr/calendar` events and the user's own requests for highlighting. */
class CalendarRepository(
	private val enhancedApi: EnhancedApi,
) {
	private companion object {
		const val REQUEST_PAGE_SIZE = 200
		/** The web pages through all requests; stop somewhere sane on a huge server. */
		const val MAX_REQUEST_PAGES = 10
	}

	suspend fun events(start: LocalDate, zone: ZoneId): CalendarResponse {
		val (from, to) = CalendarLogic.queryRange(start, zone)
		return EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("arr/calendar", mapOf("start" to from, "end" to to)))
	}

	suspend fun config(): CalendarConfig = EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("public-config"))

	/** `movie:<tmdbId>` / `tv:<tmdbId>` keys of everything the signed-in user requested. */
	suspend fun requestedKeys(): Set<String> {
		val api = enhancedApi.pinned()
		val keys = mutableSetOf<String>()
		var page = 1
		var totalPages = 1
		while (page <= totalPages && page <= MAX_REQUEST_PAGES) {
			val response = EnhancedApi.json.decodeFromJsonElement<CalendarRequestsPage>(
				api.get(
					"arr/requests",
					mapOf("take" to REQUEST_PAGE_SIZE, "skip" to (page - 1) * REQUEST_PAGE_SIZE, "userOnly" to "true"),
				)
			)
			response.requests.mapNotNullTo(keys) { it.key }
			totalPages = response.totalPages.coerceAtLeast(1)
			page++
		}
		return keys
	}
}

package org.jellyfin.androidtv.enhanced.calendar

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
		const val POSTER_WIDTH = 240
		const val LOOKUP_CHUNK = 50
	}

	suspend fun events(start: LocalDate, zone: ZoneId): CalendarResponse {
		val (from, to) = CalendarLogic.queryRange(start, zone)
		return EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("arr/calendar", mapOf("start" to from, "end" to to)))
	}

	/**
	 * Library posters for [itemIds] (series or movies), keyed by dash-less lower-case id, as the server serves them to
	 * this user: its image tags carry Spoiler Guard's protection, unlike the calendar's external artwork. Items the user
	 * can't see, or without a poster, are left out.
	 */
	suspend fun libraryPosters(itemIds: Collection<String>): Map<String, String> {
		if (itemIds.isEmpty()) return emptyMap()
		val api = enhancedApi.pinned()
		val userId = api.currentUserId()
		return itemIds.map { it.replace("-", "").lowercase() }.distinct().chunked(LOOKUP_CHUNK).flatMap { chunk ->
			val response = api.get("/Items", mapOf("ids" to chunk.joinToString(","), "userId" to userId, "enableUserData" to false))
			((response as? JsonObject)?.get("Items") as? JsonArray).orEmpty().mapNotNull { element ->
				val item = element as? JsonObject ?: return@mapNotNull null
				val id = (item["Id"] as? JsonPrimitive)?.contentOrNull?.replace("-", "")?.lowercase() ?: return@mapNotNull null
				val tag = ((item["ImageTags"] as? JsonObject)?.get("Primary") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
				id to enhancedApi.imageUrl("/Items/$id/Images/Primary", tag, POSTER_WIDTH)
			}
		}.toMap()
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

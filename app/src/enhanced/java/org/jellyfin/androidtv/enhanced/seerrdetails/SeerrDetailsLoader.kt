package org.jellyfin.androidtv.enhanced.seerrdetails

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.seerr.SeerrDetails

/**
 * Loads Seerr details with the extras of the richer details screen. Stateless, so the Seerr details screen can use it
 * without depending on this feature's Koin module.
 */
object SeerrDetailsLoader {
	/** `GET jellyseerr/{movie|tv}/{tmdbId}`, decoded once into both models. Throws when the call fails. */
	suspend fun load(api: EnhancedApi, mediaType: String, tmdbId: Int): SeerrDetailsBundle {
		val json = api.get("jellyseerr/$mediaType/$tmdbId")
		return SeerrDetailsBundle(
			details = EnhancedApi.json.decodeFromJsonElement<SeerrDetails>(json),
			extras = SeerrDetailsExtras.from(json),
		)
	}

	/**
	 * Season numbers of a library series as the server returns them for the token's user (`/Shows/{id}/Seasons`). Throws
	 * when the call fails, so callers can fall back to Seerr's own statuses instead of treating every season as gone.
	 */
	suspend fun librarySeasons(api: EnhancedApi, seriesId: String): Set<Int> {
		val items = (api.get("/Shows/$seriesId/Seasons") as? JsonObject)
			?.get("Items") as? JsonArray
			?: error("Unexpected seasons response for $seriesId")
		return items
			.mapNotNull { ((it as? JsonObject)?.get("IndexNumber") as? JsonPrimitive)?.intOrNull }
			.filter { it >= 0 }
			.toSet()
	}
}

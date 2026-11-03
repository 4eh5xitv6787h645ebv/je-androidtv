package org.jellyfin.androidtv.enhanced.activity

import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

/** The plugin's activity feed and the active-streams list. */
class ActivityRepository(
	private val enhancedApi: EnhancedApi,
) {
	private companion object {
		const val FEED_LIMIT = 50
	}

	suspend fun feed(): ActivityFeed =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("activity", mapOf("limit" to FEED_LIMIT)))

	suspend fun config(): ActivityConfig = EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("public-config"))

	/** Now-playing sessions. Throws [EnhancedApi.EnhancedApiException] with status 403 when the user may not see them. */
	suspend fun sessions(): List<ActiveSession> =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("active-streams/sessions"))
}

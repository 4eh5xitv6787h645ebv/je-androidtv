package org.jellyfin.androidtv.enhanced.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

@Serializable
data class SpoilerGuardPrefs(
	@SerialName("SkipDisableConfirm") val skipDisableConfirm: Boolean = false,
)

/** `GET /JellyfinEnhanced/spoiler-blur/series`: the user's whole Spoiler Guard document. */
@Serializable
data class SpoilerGuardState(
	@SerialName("Series") val series: Map<String, JsonElement> = emptyMap(),
	@SerialName("Movies") val movies: Map<String, JsonElement> = emptyMap(),
	@SerialName("Collections") val collections: Map<String, JsonElement> = emptyMap(),
	@SerialName("PendingTmdb") val pendingTmdb: Map<String, JsonElement> = emptyMap(),
	@SerialName("Prefs") val prefs: SpoilerGuardPrefs = SpoilerGuardPrefs(),
)

/**
 * Spoiler Guard opt-ins. The plugin does the actual protection server side (it rewrites overviews, titles and
 * images in the normal Jellyfin API responses), so the client only toggles which titles are guarded and then
 * reloads the item.
 */
class SpoilerGuardRepository(
	private val enhancedApi: EnhancedApi,
) {
	enum class Kind(val path: String, val nameField: String?) {
		SERIES("series", null),
		MOVIE("movies", "MovieName"),
		COLLECTION("collections", "CollectionName"),
	}

	companion object {
		fun kindOf(item: BaseItemDto): Kind? = when (item.type) {
			BaseItemKind.SERIES -> Kind.SERIES
			BaseItemKind.MOVIE -> Kind.MOVIE
			BaseItemKind.BOX_SET -> Kind.COLLECTION
			else -> null
		}

		fun key(id: UUID) = id.toString().replace("-", "").lowercase()
	}

	suspend fun getState(): SpoilerGuardState =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("spoiler-blur/series"))

	fun isGuarded(state: SpoilerGuardState, kind: Kind, id: UUID): Boolean {
		val map = when (kind) {
			Kind.SERIES -> state.series
			Kind.MOVIE -> state.movies
			Kind.COLLECTION -> state.collections
		}
		val key = key(id)
		return map.keys.any { it.replace("-", "").equals(key, ignoreCase = true) }
	}

	suspend fun setGuarded(kind: Kind, id: UUID, name: String?, guarded: Boolean) {
		val path = "spoiler-blur/${kind.path}/${key(id)}"
		if (guarded) {
			val body = if (kind.nameField != null && name != null) buildJsonObject { put(kind.nameField, name) } else null
			enhancedApi.post(path, body)
		} else {
			enhancedApi.delete(path)
		}
	}

	// Pending opt-ins for titles that aren't in the library yet (from the Seerr screens)

	fun isPending(state: SpoilerGuardState, mediaType: String, tmdbId: Int) =
		state.pendingTmdb.containsKey("$mediaType:$tmdbId")

	suspend fun setPending(mediaType: String, tmdbId: Int, displayName: String?, enabled: Boolean) {
		val path = "spoiler-blur/pending/$mediaType/$tmdbId"
		if (enabled) enhancedApi.post(path, query = mapOf("displayName" to displayName))
		else enhancedApi.delete(path)
	}
}

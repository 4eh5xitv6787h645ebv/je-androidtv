package org.jellyfin.androidtv.enhanced.bookmarks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/**
 * The user's timestamp bookmarks. Adds and deletes use the plugin's per-bookmark routes, which merge atomically on
 * the server, so a bookmark saved on another device at the same time isn't lost.
 */
class BookmarksRepository(
	private val enhancedApi: EnhancedApi,
	private val settingsRepository: EnhancedSettingsRepository,
) {
	private companion object {
		const val POSTER_WIDTH = 240
		const val LOOKUP_CHUNK = 50
	}

	/** What to save for a new bookmark. */
	class NewBookmark(
		val itemId: UUID,
		val name: String?,
		val mediaType: String,
		val seconds: Double,
		val label: String?,
		val tmdbId: String?,
		val tvdbId: String?,
		val seasonNumber: Int?,
		val episodeNumber: Int?,
	)

	private val _revision = MutableStateFlow(0L)

	/**
	 * Goes up after every add or delete made through this repository (even a failed one, which may still have
	 * reached the server), so copies of `bookmark.json` kept elsewhere, like the player's, know to read it again.
	 */
	val revision: StateFlow<Long> = _revision.asStateFlow()

	suspend fun list(): List<BookmarkEntry> {
		val document = settingsRepository.read(EnhancedSettingsRepository.BOOKMARKS)
		return BookmarkLogic.sorted(EnhancedApi.json.decodeFromJsonElement<BookmarkDocument>(document.json))
	}

	suspend fun delete(bookmarkId: String) {
		changing {
			val api = enhancedApi.pinned()
			api.delete("user-settings/${userKey(api)}/${EnhancedSettingsRepository.BOOKMARKS}/$bookmarkId")
		}
	}

	/** Saves a bookmark and returns its id. */
	suspend fun add(bookmark: NewBookmark): String? = changing {
		val api = enhancedApi.pinned()
		val body = buildJsonObject {
			put("ItemId", key(bookmark.itemId))
			put("MediaType", bookmark.mediaType)
			put("Name", bookmark.name.orEmpty())
			put("Timestamp", bookmark.seconds)
			put("Label", bookmark.label.orEmpty())
			bookmark.tmdbId?.let { put("TmdbId", it) }
			bookmark.tvdbId?.let { put("TvdbId", it) }
			bookmark.seasonNumber?.let { put("SeasonNumber", it) }
			bookmark.episodeNumber?.let { put("EpisodeNumber", it) }
		}
		val response = api.post("user-settings/${userKey(api)}/${EnhancedSettingsRepository.BOOKMARKS}/add", body)
		((response as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
	}

	private inline fun <T> changing(block: () -> T): T = try {
		block()
	} finally {
		_revision.update { it + 1 }
	}

	/**
	 * The bookmarked items the user can still open, by id. Anything missing from the result is gone or no longer
	 * accessible (deleted, moved to a library the user can't see, hidden).
	 */
	suspend fun resolve(itemIds: Collection<UUID>): Map<UUID, BookmarkedItem> {
		if (itemIds.isEmpty()) return emptyMap()
		val api = enhancedApi.pinned()
		val userId = api.currentUserId()
		// Chunked to keep the query string short on large bookmark lists
		val items = itemIds.chunked(LOOKUP_CHUNK).flatMap { chunk ->
			val response = api.get(
				"/Items",
				// User data tells watched episodes (which Spoiler Guard passes through) from unwatched ones
				mapOf("ids" to chunk.joinToString(",") { key(it) }, "userId" to userId, "enableUserData" to true),
			).jsonObject
			response["Items"] as? JsonArray ?: emptyList()
		}
		return items.mapNotNull { element ->
			val item = element as? JsonObject ?: return@mapNotNull null
			fun string(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull
			fun int(key: String) = (item[key] as? JsonPrimitive)?.intOrNull
			val id = string("Id")?.toUUIDOrNull() ?: return@mapNotNull null
			val isEpisode = string("Type") == "Episode"
			val seriesId = string("SeriesId")
			val seriesTag = string("SeriesPrimaryImageTag")
			val primaryTag = ((item["ImageTags"] as? JsonObject)?.get("Primary") as? JsonPrimitive)?.contentOrNull
			// Episodes show the show's poster; keep the server's tags, the plugin encodes its protection in them
			val posterUrl = when {
				isEpisode && seriesId != null && seriesTag != null ->
					enhancedApi.imageUrl("/Items/$seriesId/Images/Primary", seriesTag, POSTER_WIDTH)
				primaryTag != null -> enhancedApi.imageUrl("/Items/${key(id)}/Images/Primary", primaryTag, POSTER_WIDTH)
				else -> null
			}
			val displayName = BookmarkLogic.displayName(
				name = string("Name"),
				seriesName = if (isEpisode) string("SeriesName") else null,
				season = if (isEpisode) int("ParentIndexNumber") else null,
				episode = if (isEpisode) int("IndexNumber") else null,
			)
			val played = ((item["UserData"] as? JsonObject)?.get("Played") as? JsonPrimitive)?.booleanOrNull == true
			id to BookmarkedItem(
				id = id,
				displayName = displayName,
				posterUrl = posterUrl,
				isEpisode = isEpisode,
				seriesId = if (isEpisode) seriesId else null,
				seriesName = if (isEpisode) string("SeriesName") else null,
				seasonNumber = if (isEpisode) int("ParentIndexNumber") else null,
				episodeNumber = if (isEpisode) int("IndexNumber") else null,
				played = isEpisode && played,
			)
		}.toMap()
	}

	private suspend fun userKey(api: EnhancedApi): String =
		requireNotNull(api.currentUserId()) { "No signed-in user" }.replace("-", "").lowercase()

	private fun key(id: UUID) = id.toString().replace("-", "")
}

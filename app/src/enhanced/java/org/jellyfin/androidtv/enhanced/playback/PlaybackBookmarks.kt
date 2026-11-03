package org.jellyfin.androidtv.enhanced.playback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.bookmarks.Bookmark
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkEntry
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkLogic
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarksRepository
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * Bookmarks of the playing item. The user's `bookmark.json` is read when a player starts the item ([refresh]) and
 * cached for that player session; the cache is dropped whenever bookmarks are added or deleted elsewhere in the app
 * (the Bookmarks page, the details screen). Saving goes through the plugin's atomic `bookmark.json/add` route and
 * updates the cache.
 */
class PlaybackBookmarks(
	private val bookmarksRepository: BookmarksRepository,
	private val enhancedApi: EnhancedApi,
) {
	private val cache = mutableMapOf<UUID, List<BookmarkEntry>>()
	private var cacheToken: String? = null
	private var cacheRevision: Long? = null

	/** Bookmarks can be captured for movies and episodes, the types the web's bookmarks support. */
	fun supports(item: BaseItemDto?): Boolean = item?.type == BaseItemKind.MOVIE || item?.type == BaseItemKind.EPISODE

	/** The item's bookmarks, earliest first. Throws on failure so the caller can show it. */
	suspend fun forItem(itemId: UUID): List<BookmarkEntry> {
		resetIfStale()
		cache[itemId]?.let { return it }
		return load(itemId)
	}

	/** Reads the item's bookmarks again, for a new player session (they may have changed on another device). */
	suspend fun refresh(itemId: UUID): List<BookmarkEntry> {
		resetIfStale()
		return load(itemId)
	}

	private suspend fun load(itemId: UUID): List<BookmarkEntry> {
		val revision = bookmarksRepository.revision.value
		val entries = PlaybackBookmarkLogic.forItem(bookmarksRepository.list(), itemId)
		// A change made while reading may not be in it; then the next call reads again
		if (bookmarksRepository.revision.value == revision) cache[itemId] = entries
		return entries
	}

	/** Saves a bookmark at [positionMs] with [label] (the formatted time when blank) and returns it. */
	suspend fun add(item: BaseItemDto, positionMs: Long, label: String?): BookmarkEntry {
		resetIfStale()
		val seconds = PlaybackBookmarkLogic.seconds(positionMs)
		val isEpisode = item.type == BaseItemKind.EPISODE
		// Like the web, episodes carry the show's provider ids (with season/episode) for later re-matching
		val providerIds = if (isEpisode) {
			item.seriesId?.let { attempt { seriesProviderIds(it) }.getOrNull() }
		} else item.providerIds
		val newBookmark = BookmarksRepository.NewBookmark(
			itemId = item.id,
			name = item.name,
			mediaType = if (isEpisode) "tv" else "movie",
			seconds = seconds,
			label = label?.trim()?.takeIf { it.isNotEmpty() } ?: BookmarkLogic.formatTimestamp(seconds),
			tmdbId = providerIds?.provider("Tmdb"),
			tvdbId = providerIds?.provider("Tvdb"),
			seasonNumber = if (isEpisode) item.parentIndexNumber else null,
			episodeNumber = if (isEpisode) item.indexNumber else null,
		)
		val revision = bookmarksRepository.revision.value
		val bookmarkId = bookmarksRepository.add(newBookmark).orEmpty()
		val entry = PlaybackBookmarkLogic.entry(bookmarkId, newBookmark)
		// Our own save is the only change since: keep the cache, with the new bookmark in it
		if (bookmarksRepository.revision.value == revision + 1 && cacheRevision == revision) {
			cacheRevision = revision + 1
			cache[item.id]?.let { cache[item.id] = PlaybackBookmarkLogic.sortedByTime(it + entry) }
		}
		return entry
	}

	/** Drops the cache after a user switch or when bookmarks were added or deleted elsewhere. */
	private fun resetIfStale() {
		val token = enhancedApi.accessToken
		val revision = bookmarksRepository.revision.value
		if (token != cacheToken || revision != cacheRevision) {
			cache.clear()
			cacheToken = token
			cacheRevision = revision
		}
	}

	private suspend fun seriesProviderIds(seriesId: UUID): Map<String, String?> {
		val series = enhancedApi.get("/Items/$seriesId", mapOf("userId" to enhancedApi.currentUserId())).jsonObject
		val ids = series["ProviderIds"] as? JsonObject ?: return emptyMap()
		return ids.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull }
	}

	private fun Map<String, String?>.provider(name: String) =
		entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
}

/** Id and unit handling for bookmarks captured in the player. */
object PlaybackBookmarkLogic {
	/** Bookmark `Timestamp` seconds for a player position (fractional, like the web's `video.currentTime`). */
	fun seconds(positionMs: Long): Double = positionMs.coerceAtLeast(0) / 1000.0

	/** Player position for a bookmark (seconds → ms). */
	fun positionMs(seconds: Double): Long = BookmarkLogic.ticks(seconds) / TICKS_PER_MS

	/** The item's bookmarks in playback order. Ids are compared without dashes and case, as the plugin stores them. */
	fun forItem(entries: List<BookmarkEntry>, itemId: UUID): List<BookmarkEntry> {
		val key = key(itemId)
		return sortedByTime(entries.filter { it.bookmark.itemId.replace("-", "").equals(key, ignoreCase = true) })
	}

	/** `Label · 12:34` for the player's list; just the time when the label is empty or is the time itself. */
	fun menuLabel(bookmark: Bookmark): String {
		val time = BookmarkLogic.formatTimestamp(bookmark.timestampSeconds)
		val label = bookmark.label?.trim().orEmpty()
		return if (label.isEmpty() || label == time) time else "$label · $time"
	}

	fun sortedByTime(entries: List<BookmarkEntry>): List<BookmarkEntry> =
		entries.sortedWith(compareBy<BookmarkEntry> { it.bookmark.timestampSeconds }.thenBy { it.bookmarkId })

	internal fun entry(bookmarkId: String, bookmark: BookmarksRepository.NewBookmark) = BookmarkEntry(
		bookmarkId,
		Bookmark(
			itemId = key(bookmark.itemId),
			tmdbId = bookmark.tmdbId,
			tvdbId = bookmark.tvdbId,
			mediaType = bookmark.mediaType,
			name = bookmark.name,
			timestampSeconds = bookmark.seconds,
			label = bookmark.label,
			seasonNumber = bookmark.seasonNumber,
			episodeNumber = bookmark.episodeNumber,
		),
	)

	private const val TICKS_PER_MS = 10_000L

	private fun key(id: UUID) = id.toString().replace("-", "")
}

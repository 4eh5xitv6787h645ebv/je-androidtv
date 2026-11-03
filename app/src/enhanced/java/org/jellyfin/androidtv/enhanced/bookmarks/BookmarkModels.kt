package org.jellyfin.androidtv.enhanced.bookmarks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.roundToLong

/**
 * One timestamp bookmark from the user's `bookmark.json`. [timestampSeconds] is in **seconds** (not Jellyfin ticks).
 * The TMDB/TVDB ids are strings in the plugin's document.
 */
@Serializable
data class Bookmark(
	/** Jellyfin GUID of the bookmarked movie or episode. */
	@SerialName("ItemId") val itemId: String = "",
	@SerialName("TmdbId") val tmdbId: String? = null,
	@SerialName("TvdbId") val tvdbId: String? = null,
	/** `movie` or `tv`. */
	@SerialName("MediaType") val mediaType: String? = null,
	@SerialName("Name") val name: String? = null,
	@SerialName("Timestamp") val timestampSeconds: Double = 0.0,
	@SerialName("Label") val label: String? = null,
	@SerialName("CreatedAt") val createdAt: String? = null,
	@SerialName("UpdatedAt") val updatedAt: String? = null,
	@SerialName("SeasonNumber") val seasonNumber: Int? = null,
	@SerialName("EpisodeNumber") val episodeNumber: Int? = null,
)

/** The whole `bookmark.json` document; keys are bookmark ids (`Bm_…`). */
@Serializable
data class BookmarkDocument(
	@SerialName("Bookmarks") val bookmarks: Map<String, Bookmark> = emptyMap(),
)

/** A bookmark with its id. */
data class BookmarkEntry(val bookmarkId: String, val bookmark: Bookmark)

/**
 * The library item a bookmark points at, as the server returns it for this user (so already filtered by Spoiler
 * Guard and Hidden Content). [posterUrl] is a server image route.
 */
data class BookmarkedItem(
	val id: java.util.UUID,
	val displayName: String,
	val posterUrl: String?,
)

object BookmarkLogic {
	const val TICKS_PER_SECOND = 10_000_000L

	/** Newest first by `UpdatedAt` (falling back to `CreatedAt`); undated ones last. */
	fun sorted(document: BookmarkDocument): List<BookmarkEntry> = document.bookmarks
		.map { (id, bookmark) -> BookmarkEntry(id, bookmark) }
		.sortedWith(compareByDescending<BookmarkEntry> { updatedAt(it.bookmark) ?: Instant.MIN }.thenBy { it.bookmarkId })

	private fun updatedAt(bookmark: Bookmark): Instant? = listOfNotNull(bookmark.updatedAt, bookmark.createdAt)
		.firstNotNullOfOrNull { runCatching { Instant.parse(it) }.getOrNull() }

	/** Jellyfin ticks for a bookmark position in seconds. */
	fun ticks(seconds: Double): Long = (seconds.coerceAtLeast(0.0) * TICKS_PER_SECOND).roundToLong()

	/** Bookmark seconds for a Jellyfin tick position. */
	fun seconds(ticks: Long): Double = ticks.coerceAtLeast(0) / TICKS_PER_SECOND.toDouble()

	/** `m:ss`, or `h:mm:ss` from an hour on. */
	fun formatTimestamp(seconds: Double): String {
		val total = seconds.coerceAtLeast(0.0).toLong()
		val hours = total / 3600
		val minutes = (total % 3600) / 60
		val secs = total % 60
		return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
	}

	/** `Series · S2E1 · Name` for episodes, the plain name otherwise. */
	fun displayName(name: String?, seriesName: String?, season: Int?, episode: Int?): String = listOfNotNull(
		seriesName?.takeIf { it.isNotBlank() },
		if (season != null && episode != null) "S${season}E$episode" else null,
		name?.takeIf { it.isNotBlank() },
	).joinToString(" · ")
}

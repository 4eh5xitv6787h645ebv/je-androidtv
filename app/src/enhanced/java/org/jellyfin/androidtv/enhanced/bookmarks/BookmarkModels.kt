package org.jellyfin.androidtv.enhanced.bookmarks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.time.Instant
import java.util.UUID
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
 * Guard and Hidden Content). [posterUrl] is a server image route. The episode fields are set for episodes only.
 */
data class BookmarkedItem(
	val id: UUID,
	val displayName: String,
	val posterUrl: String?,
	val isEpisode: Boolean = false,
	val seriesId: String? = null,
	val seriesName: String? = null,
	val seasonNumber: Int? = null,
	val episodeNumber: Int? = null,
	val played: Boolean = false,
)

/**
 * What a bookmark row may call its item: [text] as is, or, when an episode's title must be withheld, [neutral]
 * (series name when known, plus season and episode numbers).
 */
data class BookmarkName(
	val text: String?,
	val neutral: NeutralEpisode? = null,
)

/** An episode named only by its series and numbers, like the plugin's "Season 2, Episode 1" titles. */
data class NeutralEpisode(val seriesName: String?, val seasonNumber: Int?, val episodeNumber: Int?)

object BookmarkLogic {
	const val TICKS_PER_SECOND = 10_000_000L

	/** The web's label input is limited to this many characters. */
	const val MAX_LABEL_LENGTH = 100

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

	/** Whether the bookmark points at an episode, from what it saved. */
	fun isEpisode(bookmark: Bookmark): Boolean =
		bookmark.mediaType.equals("tv", ignoreCase = true) || bookmark.seasonNumber != null || bookmark.episodeNumber != null

	/**
	 * The name to show for a bookmark. The saved name is the episode title at the time it was saved, so it's only
	 * shown when Spoiler Guard is off: once protection is on and the item can't be looked up (failed, deleted, no
	 * longer accessible), nothing tells us its series isn't guarded. A resolved episode of a series whose episode
	 * titles are protected gets the neutral label even if the server's name looks real (fail closed). Movie and series
	 * names are never hidden by the plugin.
	 */
	fun name(bookmark: Bookmark, item: BookmarkedItem?, spoiler: SpoilerSnapshot): BookmarkName {
		if (item != null) {
			if (item.isEpisode && spoiler.forSeries(item.seriesId, played = item.played).hideTitles) {
				return BookmarkName(null, NeutralEpisode(item.seriesName?.takeIf { it.isNotBlank() }, item.seasonNumber, item.episodeNumber))
			}
			return BookmarkName(item.displayName.takeIf { it.isNotBlank() })
		}
		if (isEpisode(bookmark) && spoiler.enabled) {
			return BookmarkName(null, NeutralEpisode(null, bookmark.seasonNumber, bookmark.episodeNumber))
		}
		return BookmarkName(bookmark.name?.takeIf { it.isNotBlank() })
	}

	/**
	 * The bookmarked item ids in list order, without repeats, in lookups of [size]: the rows at the top of the list
	 * resolve first.
	 */
	fun lookupBatches(entries: List<BookmarkEntry>, size: Int): List<List<UUID>> =
		entries.mapNotNull { it.bookmark.itemId.toUUIDOrNull() }.distinct().chunked(size)

	/** `Series · S2E1 · Name` for episodes, the plain name otherwise. */
	fun displayName(name: String?, seriesName: String?, season: Int?, episode: Int?): String = listOfNotNull(
		seriesName?.takeIf { it.isNotBlank() },
		if (season != null && episode != null) "S${season}E$episode" else null,
		name?.takeIf { it.isNotBlank() },
	).joinToString(" · ")

	/**
	 * The label to save from the rename dialog: the trimmed input (at most [MAX_LABEL_LENGTH] characters), or the
	 * bookmark's time ("12:34") when left empty, the label the player gives a quick bookmark.
	 */
	fun labelForSave(input: String?, seconds: Double): String =
		input?.trim()?.take(MAX_LABEL_LENGTH)?.trim()?.ifEmpty { null } ?: formatTimestamp(seconds)

	/**
	 * Sets the label of [bookmarkId] in the fields of a whole `bookmark.json` document and stamps `UpdatedAt` with
	 * [now], like the web's edit. Every other bookmark and field is left as it is; keys are matched ignoring case
	 * (the plugin writes PascalCase but accepts any). Returns false, changing nothing, when the bookmark isn't there
	 * (deleted elsewhere).
	 */
	fun rename(fields: MutableMap<String, JsonElement>, bookmarkId: String, label: String, now: Instant): Boolean {
		val listKey = fields.keyIgnoringCase("Bookmarks") ?: return false
		val list = fields[listKey] as? JsonObject ?: return false
		val bookmark = list[bookmarkId] as? JsonObject ?: return false
		val updated = bookmark.toMutableMap().apply {
			this[keyIgnoringCase("Label") ?: "Label"] = JsonPrimitive(label)
			this[keyIgnoringCase("UpdatedAt") ?: "UpdatedAt"] = JsonPrimitive(now.toString())
		}
		fields[listKey] = JsonObject(list.toMutableMap().apply { this[bookmarkId] = JsonObject(updated) })
		return true
	}

	private fun Map<String, *>.keyIgnoringCase(name: String) = keys.firstOrNull { it.equals(name, ignoreCase = true) }
}

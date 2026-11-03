package org.jellyfin.androidtv.enhanced.management.hidden

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jellyfin.androidtv.enhanced.data.HomeSurface
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import java.time.Instant
import java.util.UUID

/** `HideScope` values the plugin writes. Anything else is kept as is and listed under "Other". */
object HiddenScope {
	const val GLOBAL = "global"
	const val CONTINUE_WATCHING = "continuewatching"
	const val NEXT_UP = "nextup"
	const val HOME_SECTIONS = "homesections"
}

/**
 * One entry of `hidden-content.json` → `Items`. [key] is the document key exactly as stored (dashed or N-format GUID,
 * or `tmdb-<id>` for titles that aren't in the library); removals always use it.
 */
data class HiddenEntry(
	val key: String,
	/** The Jellyfin id, when the entry has a valid one. TMDB-only entries have none. */
	val itemId: UUID?,
	val name: String,
	val type: String,
	val tmdbId: Int?,
	val hiddenAt: Instant?,
	val posterPath: String?,
	val seriesId: UUID?,
	val seriesName: String?,
	val seasonNumber: Int?,
	val episodeNumber: Int?,
	/** Lower-cased scope; an empty value means global, as on the web. */
	val scope: String,
) {
	val isGlobal get() = scope == HiddenScope.GLOBAL
	val isEpisode get() = type.equals("Episode", ignoreCase = true)
	val isSeason get() = type.equals("Season", ignoreCase = true)
	val mediaType get() = if (type.equals("Series", ignoreCase = true) || isSeason || isEpisode) "tv" else "movie"

	/** `S02E05`-style label for episodes and seasons, when the numbers are known. */
	val episodeLabel: String?
		get() = when {
			isEpisode && seasonNumber != null && episodeNumber != null -> "S%02dE%02d".format(seasonNumber, episodeNumber)
			isSeason && seasonNumber != null -> "S%02d".format(seasonNumber)
			else -> null
		}
}

/** The type filter of the Hidden Content page. */
enum class HiddenTypeFilter {
	ALL, MOVIES, SHOWS, SEASONS, EPISODES, OTHER;

	fun matches(entry: HiddenEntry) = when (this) {
		ALL -> true
		MOVIES -> entry.type.equals("Movie", ignoreCase = true)
		SHOWS -> entry.type.equals("Series", ignoreCase = true)
		SEASONS -> entry.isSeason
		EPISODES -> entry.isEpisode
		OTHER -> entry.type.lowercase() !in setOf("movie", "series", "season", "episode")
	}
}

/** The scope filter of the Hidden Content page. */
enum class HiddenScopeFilter {
	ALL, EVERYWHERE, CONTINUE_WATCHING, NEXT_UP, HOME, OTHER;

	fun matches(entry: HiddenEntry) = when (this) {
		ALL -> true
		EVERYWHERE -> entry.scope == HiddenScope.GLOBAL
		CONTINUE_WATCHING -> entry.scope == HiddenScope.CONTINUE_WATCHING
		NEXT_UP -> entry.scope == HiddenScope.NEXT_UP
		HOME -> entry.scope == HiddenScope.HOME_SECTIONS
		OTHER -> entry.scope !in KNOWN_SCOPES
	}

	companion object {
		private val KNOWN_SCOPES = setOf(HiddenScope.GLOBAL, HiddenScope.CONTINUE_WATCHING, HiddenScope.NEXT_UP, HiddenScope.HOME_SECTIONS)
	}
}

/** How to undo one entry. */
sealed interface UnhidePlan {
	/** The plugin's scoped DELETE route, which only removes an entry of exactly that scope. */
	data class ScopedRestore(val itemId: UUID, val surface: HomeSurface) : UnhidePlan

	/** Remove the entry from the document by its exact key (global, combined home and unknown scopes). */
	data class RemoveKey(val key: String) : UnhidePlan
}

object HiddenEntries {
	/** Parse the `Items` map of a hidden-content document, newest first. Malformed entries are skipped. */
	fun parse(document: JsonObject): List<HiddenEntry> {
		val items = document["Items"] as? JsonObject ?: return emptyList()
		return items.mapNotNull { (key, value) ->
			val obj = value as? JsonObject ?: return@mapNotNull null
			HiddenEntry(
				key = key,
				itemId = SpoilerGuardRepository.parseGuid(obj.string("ItemId")),
				name = obj.string("Name").orEmpty(),
				type = obj.string("Type").orEmpty(),
				tmdbId = obj.string("TmdbId")?.toIntOrNull() ?: key.removePrefix("tmdb-").takeIf { key.startsWith("tmdb-") }?.toIntOrNull(),
				hiddenAt = SpoilerGuardRepository.parseInstant(obj.string("HiddenAt")),
				posterPath = obj.string("PosterPath"),
				seriesId = SpoilerGuardRepository.parseGuid(obj.string("SeriesId")),
				seriesName = obj.string("SeriesName"),
				seasonNumber = (obj["SeasonNumber"] as? JsonPrimitive)?.intOrNull,
				episodeNumber = (obj["EpisodeNumber"] as? JsonPrimitive)?.intOrNull,
				scope = obj.string("HideScope")?.lowercase() ?: HiddenScope.GLOBAL,
			)
		}.sortedByDescending { it.hiddenAt }
	}

	fun filter(entries: List<HiddenEntry>, scope: HiddenScopeFilter, type: HiddenTypeFilter) =
		entries.filter { scope.matches(it) && type.matches(it) }

	/**
	 * Continue Watching and Next Up entries go through the plugin's scoped DELETE, which keeps its server-side
	 * read-modify-write; everything else (including `homesections`, which that route deliberately leaves alone, and
	 * scopes this client doesn't know) is removed from the document by key.
	 */
	fun unhidePlan(entry: HiddenEntry): UnhidePlan {
		val itemId = entry.itemId
		return when {
			itemId != null && entry.scope == HiddenScope.CONTINUE_WATCHING -> UnhidePlan.ScopedRestore(itemId, HomeSurface.CONTINUE_WATCHING)
			itemId != null && entry.scope == HiddenScope.NEXT_UP -> UnhidePlan.ScopedRestore(itemId, HomeSurface.NEXT_UP)
			else -> UnhidePlan.RemoveKey(entry.key)
		}
	}

	/**
	 * The title to show for an entry. Episode names are withheld while the show is Spoiler Guarded ([guardedSeries]
	 * holds dash-less lower-case ids), since entries written by the plugin's home-row routes store the real title.
	 */
	fun displayTitle(entry: HiddenEntry, guardedSeries: Set<String>?): String {
		if (!entry.isEpisode && !entry.isSeason) return entry.name
		val series = entry.seriesName?.takeIf { it.isNotBlank() }
		val label = entry.episodeLabel
		val seriesKey = entry.seriesId?.let(SpoilerGuardRepository::key)
		// Unknown protection state (null) counts as protected
		val withholdName = entry.isEpisode && (guardedSeries == null || seriesKey == null || seriesKey in guardedSeries)
		val name = entry.name.takeIf { it.isNotBlank() && !withholdName }
		return listOfNotNull(series, label, name).joinToString(" · ")
	}

	private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}

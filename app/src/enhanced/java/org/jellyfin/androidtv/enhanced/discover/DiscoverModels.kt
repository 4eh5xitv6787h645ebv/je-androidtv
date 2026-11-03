package org.jellyfin.androidtv.enhanced.discover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.androidtv.enhanced.data.EnhancedUserSettings
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One entry of `jellyseerr/discover/genreslider/movie|tv`. `id` is the TMDB genre id of that media type. */
@Serializable
data class DiscoverGenreSlide(
	val id: Int,
	val name: String,
	val backdrops: List<String> = emptyList(),
)

/**
 * The backdrop path for each genre tile: the first one not already used by an earlier tile, so neighbouring tiles
 * don't repeat the same blockbuster (as the web does), or the genre's first one when all are taken.
 */
fun assignGenreBackdrops(genres: List<DiscoverGenreSlide>): List<String?> {
	val used = HashSet<String>()
	return genres.map { genre ->
		val backdrop = genre.backdrops.firstOrNull { it !in used } ?: genre.backdrops.firstOrNull()
		backdrop?.also { used += it }
	}
}

/** A card of an Upcoming row: a Seerr result that shows its release date instead of the year. */
data class DiscoverUpcomingCard(val result: SeerrResult)

/** A genre, studio or network tile that opens the discovery grid for [source]. */
data class DiscoverTile(
	val title: String,
	/** A backdrop (genres) or white logo (studios/networks); null shows the title alone. */
	val imageUrl: String?,
	val logo: Boolean,
	val source: SeerrSource,
)

/** The placeholder card of a row that has no results to show (yet). Selecting an [Error] retries the row. */
sealed interface DiscoverRowStatus {
	data object Loading : DiscoverRowStatus
	data object Empty : DiscoverRowStatus
	data class Error(val message: String?, val retry: () -> Unit) : DiscoverRowStatus
}

/**
 * The release (or first air) date of an upcoming title as a short localized date, or null when it has none or it
 * mustn't be shown: library titles under Spoiler Guard withhold their air dates.
 */
fun upcomingDateLabel(result: SeerrResult, locale: Locale = Locale.getDefault()): String? {
	if (result.libraryArtwork) return null
	val raw = (result.releaseDate ?: result.firstAirDate)?.takeIf { it.isNotBlank() } ?: return null
	return try {
		LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
	} catch (_: Exception) {
		result.year
	}
}

/** The server's defaults for the random pick (`public-config`), used where the user has no setting of their own. */
@Serializable
data class RandomPickDefaults(
	@SerialName("RandomButtonEnabled") val randomButtonEnabled: Boolean = true,
	@SerialName("RandomUnwatchedOnly") val randomUnwatchedOnly: Boolean = false,
	@SerialName("RandomIncludeMovies") val randomIncludeMovies: Boolean = true,
	@SerialName("RandomIncludeShows") val randomIncludeShows: Boolean = true,
)

/** The user's effective random-pick settings, resolved as the web resolves them. */
data class RandomPickSettings(
	val enabled: Boolean,
	val unwatchedOnly: Boolean,
	val includeMovies: Boolean,
	val includeShows: Boolean,
	/** A playlist or collection the user pinned as the source (`RandomSourceId`), or null for the whole library. */
	val sourceId: String?,
) {
	companion object {
		fun resolve(user: EnhancedUserSettings, defaults: RandomPickDefaults, sourceId: String?) = RandomPickSettings(
			enabled = user.randomButtonEnabled ?: defaults.randomButtonEnabled,
			unwatchedOnly = user.randomUnwatchedOnly ?: defaults.randomUnwatchedOnly,
			includeMovies = user.randomIncludeMovies ?: defaults.randomIncludeMovies,
			includeShows = user.randomIncludeShows ?: defaults.randomIncludeShows,
			sourceId = sourceId?.trim()?.takeIf { it.isNotEmpty() },
		)

		/**
		 * The "unwatched" test of the web's random button: a show counts while it has unplayed episodes, anything
		 * else (movies, episodes) while it isn't played.
		 */
		fun isUnwatched(isSeries: Boolean, played: Boolean?, unplayedItemCount: Int?): Boolean =
			if (isSeries) (unplayedItemCount ?: 0) > 0 else played != true
	}
}

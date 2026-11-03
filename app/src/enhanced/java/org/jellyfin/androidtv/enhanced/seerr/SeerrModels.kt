package org.jellyfin.androidtv.enhanced.seerr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Seerr standard-request `mediaInfo.status`. */
object SeerrMediaStatus {
	const val UNKNOWN = 1
	const val PENDING = 2
	const val PROCESSING = 3
	const val PARTIALLY_AVAILABLE = 4
	const val AVAILABLE = 5
	const val BLOCKLISTED = 6
	const val DELETED = 7

	/** Seerr can retain Available after the library copy was deleted, as in JE's effectiveMediaStatus. */
	fun effective(status: Int?, jellyfinId: String?): Int? =
		if (status == AVAILABLE && jellyfinId.isNullOrBlank()) DELETED else status

	fun isRequestable(status: Int?) = status == null || status == 0 || status == UNKNOWN || status == DELETED
}

@Serializable
data class SeerrSeasonStatus(
	val seasonNumber: Int = 0,
	val status: Int? = null,
)

@Serializable
data class SeerrMediaInfo(
	val id: Int? = null,
	val tmdbId: Int? = null,
	val tvdbId: Int? = null,
	val status: Int? = null,
	val jellyfinMediaId: String? = null,
	val jellyfinMediaId4k: String? = null,
	val seasons: List<SeerrSeasonStatus> = emptyList(),
) {
	val effectiveStatus get() = SeerrMediaStatus.effective(status, jellyfinMediaId)

	fun effectiveSeasonStatus(seasonNumber: Int): Int? {
		val season = seasons.firstOrNull { it.seasonNumber == seasonNumber }
		return SeerrMediaStatus.effective(season?.status, jellyfinMediaId)
	}
}

/** A movie, show or person result from a Seerr search/discover/credits list. `id` is the TMDB id. */
@Serializable
data class SeerrResult(
	val id: Int,
	val mediaType: String? = null,
	val title: String? = null,
	val name: String? = null,
	val posterPath: String? = null,
	val profilePath: String? = null,
	val backdropPath: String? = null,
	val overview: String? = null,
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val voteAverage: Double? = null,
	val popularity: Double? = null,
	val voteCount: Int? = null,
	val genreIds: List<Int> = emptyList(),
	val character: String? = null,
	val job: String? = null,
	val mediaInfo: SeerrMediaInfo? = null,
	/**
	 * Library titles while Spoiler Guard is on: show [libraryPosterUrl] (the server's poster, or none when it
	 * couldn't be resolved) instead of the TMDB one.
	 */
	@Transient val libraryArtwork: Boolean = false,
	@Transient val libraryPosterUrl: String? = null,
) {
	val displayTitle get() = title ?: name ?: ""
	val year get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }
	val isMovie get() = mediaType == "movie"
	val isTv get() = mediaType == "tv"
	val status get() = mediaInfo?.effectiveStatus
	val displayCredit get() = if (libraryArtwork) null else character?.takeIf { it.isNotBlank() } ?: job?.takeIf { it.isNotBlank() }
	val dedupeKey get() = "$mediaType-$id"
}

@Serializable
data class SeerrPage(
	val page: Int = 1,
	val totalPages: Int = 1,
	val totalResults: Int = 0,
	val results: List<SeerrResult> = emptyList(),
)

@Serializable
data class SeerrCredits(
	val cast: List<SeerrResult> = emptyList(),
	val crew: List<SeerrResult> = emptyList(),
)

@Serializable
data class SeerrGenre(val id: Int, val name: String)

@Serializable
data class SeerrSeason(
	val id: Int? = null,
	val seasonNumber: Int,
	val name: String? = null,
	val episodeCount: Int = 0,
	val airDate: String? = null,
)

@Serializable
data class SeerrExternalIds(val tvdbId: Int? = null)

/** Full `jellyseerr/movie/{id}` or `jellyseerr/tv/{id}` detail. */
@Serializable
data class SeerrDetails(
	val id: Int,
	val title: String? = null,
	val name: String? = null,
	val tagline: String? = null,
	val overview: String? = null,
	val posterPath: String? = null,
	val backdropPath: String? = null,
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val runtime: Int? = null,
	val voteAverage: Double? = null,
	val genres: List<SeerrGenre> = emptyList(),
	val seasons: List<SeerrSeason> = emptyList(),
	val externalIds: SeerrExternalIds? = null,
	val mediaInfo: SeerrMediaInfo? = null,
) {
	val displayTitle get() = title ?: name ?: ""
	val year get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }
}

/** A Sonarr lookup candidate, used to pick a TVDB match for shows TMDB has none for. */
@Serializable
data class SeerrTvdbCandidate(
	val tvdbId: Int? = null,
	val title: String? = null,
	val year: Int? = null,
)

@Serializable
data class SeerrPartialRequestSettings(
	val partialRequestsEnabled: Boolean = true,
	val enableSpecialEpisodes: Boolean = false,
)

@Serializable
data class SeerrQuotaSide(
	val limit: Int? = null,
	val used: Int? = null,
	val remaining: Int? = null,
	val days: Int? = null,
	val restricted: Boolean = false,
)

@Serializable
data class SeerrQuota(
	val movie: SeerrQuotaSide? = null,
	val tv: SeerrQuotaSide? = null,
)

/** Which TMDB list a discovery screen shows. */
@Serializable
sealed interface SeerrSource {
	val title: String

	@Serializable
	@SerialName("person")
	data class Person(val jellyfinPersonId: String?, val tmdbPersonId: Int?, val name: String, override val title: String) : SeerrSource

	@Serializable
	@SerialName("genre")
	data class Genre(val name: String, override val title: String) : SeerrSource

	@Serializable
	@SerialName("studio")
	data class Studio(val name: String, val jellyfinStudioId: String?, override val title: String) : SeerrSource

	@Serializable
	@SerialName("keyword")
	data class Keyword(val name: String, override val title: String) : SeerrSource

	@Serializable
	@SerialName("recommended")
	data class Recommended(val mediaType: String, val tmdbId: Int, override val title: String) : SeerrSource

	@Serializable
	@SerialName("similar")
	data class Similar(val mediaType: String, val tmdbId: Int, override val title: String) : SeerrSource

	/** One of the plugin's fixed Discover feeds (Trending, Popular, Upcoming). */
	@Serializable
	@SerialName("discover")
	data class Discover(val feed: SeerrDiscoverFeed, override val title: String) : SeerrSource

	/** A TMDB genre of one media type, by id (the Discover screen's genre tiles). */
	@Serializable
	@SerialName("discover-genre")
	data class DiscoverGenre(val mediaType: String, val genreId: Int, override val title: String) : SeerrSource

	/**
	 * A TMDB production company by id: its movies, plus its series when the plugin has a TMDB key (Seerr itself
	 * only filters series by network). The Discover screen's Studios tiles.
	 */
	@Serializable
	@SerialName("company")
	data class Company(val companyId: Int, override val title: String) : SeerrSource

	/**
	 * A TMDB TV network by id, plus the movies of [companyId], the network's own production company, when it has a
	 * clean one. The Discover screen's Networks tiles.
	 */
	@Serializable
	@SerialName("network")
	data class Network(val networkId: Int, val companyId: Int?, override val title: String) : SeerrSource

	/** All parts of a TMDB collection, oldest first. */
	@Serializable
	@SerialName("collection")
	data class Collection(val collectionTmdbId: Int, override val title: String) : SeerrSource
}

/** The plugin's fixed `jellyseerr/discover/...` feeds, in the web's Discover page order. */
@Serializable
enum class SeerrDiscoverFeed(
	val path: String,
	/** The media type every result has, or null for the mixed Trending feed. */
	val mediaType: String?,
) {
	@SerialName("trending")
	TRENDING("jellyseerr/discover/trending", null),

	@SerialName("movies")
	POPULAR_MOVIES("jellyseerr/discover/movies", "movie"),

	@SerialName("tv")
	POPULAR_TV("jellyseerr/discover/tv", "tv"),

	@SerialName("movies-upcoming")
	UPCOMING_MOVIES("jellyseerr/discover/movies/upcoming", "movie"),

	@SerialName("tv-upcoming")
	UPCOMING_TV("jellyseerr/discover/tv/upcoming", "tv");

	val upcoming get() = this == UPCOMING_MOVIES || this == UPCOMING_TV
}

/**
 * Sort presets of the discovery grid, the same four the web offers. [POPULAR] sends no `sortBy` (Seerr's default
 * is popularity). TV feeds sort by first air date instead of release date.
 */
@Serializable
enum class SeerrSort(private val movieSortBy: String?) {
	POPULAR(null),
	TOP_RATED("vote_average.desc"),
	NEWEST("release_date.desc"),
	OLDEST("release_date.asc");

	/** The `sortBy` query value for a feed of [mediaType], or null to leave Seerr's default order. */
	fun sortBy(mediaType: String?): String? =
		if (mediaType == "tv") movieSortBy?.replace("release_date", "first_air_date") else movieSortBy
}

/**
 * Whether the source's feeds accept a `sortBy`. Only Seerr's plain movie/TV discover routes (with or without a
 * genre, company, network or keyword) read it; Trending and Upcoming answer 400 to it, and recommendations,
 * person credits and collections have a fixed order.
 */
val SeerrSource.sortable: Boolean
	get() = when (this) {
		is SeerrSource.Discover -> feed == SeerrDiscoverFeed.POPULAR_MOVIES || feed == SeerrDiscoverFeed.POPULAR_TV
		is SeerrSource.DiscoverGenre,
		is SeerrSource.Company,
		is SeerrSource.Network,
		is SeerrSource.Genre,
		is SeerrSource.Studio,
		is SeerrSource.Keyword -> true

		is SeerrSource.Person,
		is SeerrSource.Recommended,
		is SeerrSource.Similar,
		is SeerrSource.Collection -> false
	}

object SeerrImages {
	private const val BASE = "https://image.tmdb.org/t/p/"

	fun poster(path: String?, size: String = "w342") = path?.takeIf { it.startsWith("/") }?.let { "$BASE$size$it" }
	fun backdrop(path: String?, size: String = "w1280") = path?.takeIf { it.startsWith("/") }?.let { "$BASE$size$it" }
}

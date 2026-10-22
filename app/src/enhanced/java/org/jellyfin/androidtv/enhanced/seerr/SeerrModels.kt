package org.jellyfin.androidtv.enhanced.seerr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Seerr `mediaInfo.status` / `status4k`. */
object SeerrMediaStatus {
	const val UNKNOWN = 1
	const val PENDING = 2
	const val PROCESSING = 3
	const val PARTIALLY_AVAILABLE = 4
	const val AVAILABLE = 5
	const val BLOCKLISTED = 6
	const val DELETED = 7

	fun isRequestable(status: Int?) = status == null || status == 0 || status == UNKNOWN || status == DELETED
}

@Serializable
data class SeerrSeasonStatus(
	val seasonNumber: Int = 0,
	val status: Int? = null,
	val status4k: Int? = null,
)

@Serializable
data class SeerrMediaInfo(
	val id: Int? = null,
	val tmdbId: Int? = null,
	val tvdbId: Int? = null,
	val status: Int? = null,
	val status4k: Int? = null,
	val jellyfinMediaId: String? = null,
	val jellyfinMediaId4k: String? = null,
	val seasons: List<SeerrSeasonStatus> = emptyList(),
)

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
) {
	val displayTitle get() = title ?: name ?: ""
	val year get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }
	val isMovie get() = mediaType == "movie"
	val isTv get() = mediaType == "tv"
	val status get() = mediaInfo?.status
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
}

object SeerrImages {
	private const val BASE = "https://image.tmdb.org/t/p/"

	fun poster(path: String?, size: String = "w342") = path?.takeIf { it.startsWith("/") }?.let { "$BASE$size$it" }
	fun backdrop(path: String?, size: String = "w1280") = path?.takeIf { it.startsWith("/") }?.let { "$BASE$size$it" }
}

package org.jellyfin.androidtv.enhanced.search

import kotlinx.serialization.Serializable
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult

/** A TMDB collection as `jellyseerr/movie-collections` reports it. [id] is the TMDB collection id. */
@Serializable
data class SeerrCollectionRef(
	val id: Int,
	val name: String? = null,
	val posterPath: String? = null,
	val backdropPath: String? = null,
)

/**
 * `GET jellyseerr/movie-collections?ids=`: keyed by movie TMDB id. A null value means the movie belongs to no
 * collection; a missing key means the lookup for that movie failed or was refused, so it may be asked again.
 */
@Serializable
data class SeerrMovieCollections(
	val results: Map<String, SeerrCollectionRef?> = emptyMap(),
)

/** `GET jellyseerr/collection/{collectionId}`. The parts are movies; their `id` is a movie TMDB id. */
@Serializable
data class SeerrCollection(
	val id: Int,
	val name: String? = null,
	val posterPath: String? = null,
	val backdropPath: String? = null,
	val overview: String? = null,
	val parts: List<SeerrResult> = emptyList(),
)

// Card items of the search rows besides plain SeerrResult movie/show cards

/** A `mediaType:"person"` search result. [result]`.id` is the TMDB person id. */
data class SeerrSearchPerson(val result: SeerrResult) {
	val tmdbPersonId get() = result.id
	val name get() = result.displayTitle
}

/**
 * A collection one of the movie hits belongs to. [posterWithheld] is set when Spoiler Guard protects the library
 * copy of that movie, so the TMDB artwork isn't shown for it.
 */
data class SeerrSearchCollection(val collection: SeerrCollectionRef, val posterWithheld: Boolean) {
	val tmdbCollectionId get() = collection.id
}

/** Last card of the Seerr results row when there are more pages than the row loads: opens the paged grid. */
data class SeerrSearchViewAll(val query: String)

/** A card standing in for results that failed to load. Selecting it retries. */
data class SeerrSearchError(val serverMessage: String?)

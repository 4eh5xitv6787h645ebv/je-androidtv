package org.jellyfin.androidtv.enhanced.search

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrPage
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import timber.log.Timber

/**
 * Seerr search through the Jellyfin Enhanced proxy: `jellyseerr/search` for movies, shows and people,
 * `jellyseerr/movie-collections` for the collections of movie hits and `jellyseerr/collection/{id}` for a
 * collection's parts.
 */
class SeerrSearchRepository(
	private val enhancedApi: EnhancedApi,
	private val hiddenContentRepository: HiddenContentRepository,
	private val seerrRepository: SeerrRepository,
) {
	companion object {
		/** The plugin caps queries at 256 UTF-16 units. */
		const val MAX_QUERY_LENGTH = 256

		/** TMDB never serves pages past 500. */
		const val MAX_PAGE = 500

		/** `movie-collections` accepts at most 100 ids per call. */
		const val MAX_COLLECTION_IDS = 100

		/** Whether Seerr results belong in the search screen at all. */
		fun enabled(state: EnhancedState) = state.seerrUsable && state.config.jellyseerrShowSearchResults

		/** People open the person discovery grid, so they follow its gate. */
		fun showPeople(state: EnhancedState) = enabled(state) && state.config.jellyseerrShowPersonDiscovery

		fun showCollections(state: EnhancedState) = enabled(state) && state.config.showCollectionsInSearch
	}

	private suspend inline fun <reified T> getAs(path: String, query: Map<String, Any?> = emptyMap()): T =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get(path, query))

	/** One page of results. `id` of each result is a TMDB id (of a movie, show or person, per `mediaType`). */
	suspend fun search(query: String, page: Int): SeerrPage =
		getAs("jellyseerr/search", mapOf("query" to query.take(MAX_QUERY_LENGTH), "page" to page.coerceIn(1, MAX_PAGE)))

	/** Collections of the given movie TMDB ids; see [SeerrMovieCollections] for missing keys versus nulls. */
	suspend fun movieCollections(movieTmdbIds: List<Int>): SeerrMovieCollections =
		getAs("jellyseerr/movie-collections", mapOf("ids" to movieTmdbIds.take(MAX_COLLECTION_IDS).joinToString(",")))

	/** A collection and its parts, oldest first. Parts carry `mediaType = movie` even where Seerr omits it. */
	suspend fun collection(tmdbCollectionId: Int): SeerrCollection =
		getAs<SeerrCollection>("jellyseerr/collection/$tmdbCollectionId").let { collection ->
			collection.copy(
				parts = collection.parts
					.map { if (it.mediaType == null) it.copy(mediaType = SeerrRepository.MEDIA_MOVIE) else it }
					.sortedBy { it.releaseDate?.takeIf(String::isNotBlank) ?: "9999" },
			)
		}

	/**
	 * The filter for a search by [state]'s user. When the user filters search by Hidden Content but the list can't
	 * be loaded this throws, so hidden titles are never shown by accident.
	 */
	suspend fun filter(state: EnhancedState, year: String?): SeerrSearchFilter {
		val snapshot = if (state.hiddenContent) {
			try {
				hiddenContentRepository.get()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				val filters = state.hiddenSettings?.let { it.enabled && it.filterSearch } ?: true
				if (filters) throw err
				Timber.w(err, "Hidden Content unavailable; search doesn't filter it")
				null
			}
		} else null
		val filterHidden = snapshot != null && snapshot.settings.enabled && snapshot.settings.filterSearch

		return SeerrSearchFilter(
			hiddenTmdbIds = if (filterHidden) snapshot!!.hiddenTmdbIds else emptySet(),
			hiddenItemIds = if (filterHidden) snapshot!!.hiddenItemIds else emptySet(),
			excludeLibraryItems = state.config.jellyseerrSearchExcludeLibraryItems,
			excludeBlocklisted = state.config.jellyseerrExcludeBlocklistedItems,
			year = year,
		)
	}

	/** Library posters through the server while Spoiler Guard is on (see [SeerrRepository.refreshLibraryArtwork]). */
	suspend fun withLibraryArtwork(results: List<SeerrResult>): List<SeerrResult> = seerrRepository.refreshLibraryArtwork(results)
}

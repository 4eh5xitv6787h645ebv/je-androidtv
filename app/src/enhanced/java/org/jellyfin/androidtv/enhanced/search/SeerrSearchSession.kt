package org.jellyfin.androidtv.enhanced.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.koin.core.component.KoinComponent
import timber.log.Timber
import kotlin.time.Duration

/** What the Seerr rows of the search screen show for [query]. */
data class SeerrSearchState(
	val query: String = "",
	/** Movies and shows, in Seerr's order. */
	val media: List<SeerrResult> = emptyList(),
	val people: List<SeerrSearchPerson> = emptyList(),
	val collections: List<SeerrSearchCollection> = emptyList(),
	val loading: Boolean = false,
	/** The last load failed; the row offers Retry (in place of the results when nothing loaded). */
	val error: SeerrSearchError? = null,
	/** The row has loaded all the pages it shows and there are more: it ends in a "View all" card. */
	val viewAll: Boolean = false,
)

/**
 * The Seerr side of one search screen. Owned by the search ViewModel and driven by its query: every new query
 * cancels everything the previous one still had running (page loads, collection lookups, artwork refreshes), so a
 * late response can't render under a newer query. All state changes happen on [scope]'s (main) thread.
 *
 * Does nothing at all when the plugin, Seerr, the user's link or `JellyseerrShowSearchResults` is missing, or when
 * the search module isn't registered.
 */
class SeerrSearchSession(private val scope: CoroutineScope) : KoinComponent {
	companion object {
		/** Pages the row loads as the user scrolls; past that it ends in "View all". */
		const val ROW_PAGES = 3

		/** Pages in a row that may be filtered out entirely before the row stops looking further. */
		private const val MAX_EMPTY_PAGES = 3

		/** A load starts when the selection gets this close to the end of the row. */
		const val LOAD_MORE_THRESHOLD = 5
	}

	private val enhancedRepository by lazy { getKoin().getOrNull<EnhancedRepository>() }
	private val repository by lazy { getKoin().getOrNull<SeerrSearchRepository>() }

	private val _state = MutableStateFlow(SeerrSearchState())
	val state: StateFlow<SeerrSearchState> = _state.asStateFlow()

	// Per query; reset by onQuery
	private var queryScope: CoroutineScope? = null
	private var pageJob: Job? = null
	private var query = SeerrSearchQuery("", null)
	private var filter: SeerrSearchFilter? = null
	private var nextPage = 1
	private var totalPages = 1
	private var pagesLoaded = 0
	private val seen = HashSet<String>()
	private val lookedUpMovies = HashSet<Int>()

	private val hasMorePages get() = nextPage <= totalPages

	/** The search box changed to [trimmedQuery]; results load after [debounce], like the library's. */
	fun onQuery(trimmedQuery: String, debounce: Duration) {
		if (trimmedQuery == _state.value.query && queryScope != null) return
		queryScope?.cancel()
		queryScope = null
		pageJob = null
		filter = null
		nextPage = 1
		totalPages = 1
		pagesLoaded = 0
		seen.clear()
		lookedUpMovies.clear()
		query = SeerrSearchQuery.parse(trimmedQuery)
		_state.value = SeerrSearchState(query = trimmedQuery)
		if (trimmedQuery.isBlank() || enhancedRepository == null || repository == null) return

		// Assigned before launching: on an immediate dispatcher the job can get to use it before launch returns
		val queryScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
		this.queryScope = queryScope
		pageJob = queryScope.launch {
			delay(debounce)
			loadPages()
		}
	}

	/** The selection moved near the end of the row: load the next page if the row has room for it. */
	fun loadMore() {
		val current = _state.value
		if (current.error != null || current.viewAll || pageJob?.isActive == true) return
		if (pagesLoaded == 0 || !hasMorePages || pagesLoaded >= ROW_PAGES) return
		launchPages()
	}

	/** Retry after [SeerrSearchState.error]: loads the page that failed again. */
	fun retry() {
		if (_state.value.error == null || pageJob?.isActive == true) return
		_state.update { it.copy(error = null) }
		launchPages()
	}

	/** Drop a card the user just hid from its menu. */
	fun remove(result: SeerrResult) {
		_state.update { state -> state.copy(media = state.media.filterNot { it.dedupeKey == result.dedupeKey }) }
	}

	/**
	 * The search screen is showing again. Spoiler Guard may have changed meanwhile, so library posters are withheld
	 * at once and re-resolved with the server's current image tags before they show again.
	 */
	fun onShown() {
		val queryScope = queryScope ?: return
		val repository = repository ?: return
		if (_state.value.media.none { SeerrSearchFilter.libraryId(it) != null }) return

		_state.update { state ->
			state.copy(media = state.media.map { if (SeerrSearchFilter.libraryId(it) != null) it.copy(libraryArtwork = true, libraryPosterUrl = null) else it })
		}
		queryScope.launch {
			val refreshed = try {
				repository.withLibraryArtwork(_state.value.media).associateBy { it.dedupeKey }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to refresh library artwork")
				return@launch
			}
			ensureActive()
			_state.update { state -> state.copy(media = state.media.map { refreshed[it.dedupeKey] ?: it }) }
		}
	}

	private fun launchPages() {
		val queryScope = queryScope ?: return
		pageJob = queryScope.launch { loadPages() }
	}

	private suspend fun CoroutineScope.loadPages() {
		val repository = repository ?: return
		val enhanced = enhancedRepository?.ensureLoaded() ?: return
		if (!SeerrSearchRepository.enabled(enhanced)) return

		_state.update { it.copy(loading = true, error = null) }
		try {
			val filter = filter ?: repository.filter(enhanced, query.year).also { filter = it }
			var emptyPages = 0
			while (true) {
				val page = repository.search(query.title, nextPage)
				ensureActive()
				nextPage++
				totalPages = minOf(page.totalPages, SeerrSearchRepository.MAX_PAGE)
				pagesLoaded++

				val batch = splitPage(page.results, filter, SeerrSearchRepository.showPeople(enhanced))
				val media = repository.withLibraryArtwork(batch.media)
				ensureActive()
				_state.update { it.copy(media = it.media + media, people = it.people + batch.people) }
				if (SeerrSearchRepository.showCollections(enhanced)) lookUpCollections(media)

				emptyPages = if (media.isEmpty()) emptyPages + 1 else 0
				// A page that was filtered out entirely leaves nothing to scroll onto, so look further on our own
				if (!hasMorePages || pagesLoaded >= ROW_PAGES || emptyPages == 0 || emptyPages >= MAX_EMPTY_PAGES) break
			}
			_state.update { it.copy(loading = false, viewAll = hasMorePages && pagesLoaded >= ROW_PAGES) }
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Seerr search failed")
			_state.update { it.copy(loading = false, error = SeerrSearchError((err as? EnhancedApi.EnhancedApiException)?.serverMessage)) }
		}
	}

	internal class Batch(val media: List<SeerrResult>, val people: List<SeerrSearchPerson>)

	private fun splitPage(results: List<SeerrResult>, filter: SeerrSearchFilter, people: Boolean): Batch =
		splitResults(results, filter, people, seen)

	/**
	 * Look up the collections of [movies] alongside the page loads (not holding them up); collection cards are a
	 * bonus, so failures only log.
	 */
	private fun lookUpCollections(movies: List<SeerrResult>) {
		val repository = repository ?: return
		val queryScope = queryScope ?: return
		val asked = movies.filter { it.isMovie && lookedUpMovies.add(it.id) }
		if (asked.isEmpty()) return

		queryScope.launch {
			val found = try {
				repository.movieCollections(asked.map { it.id })
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr collection lookup failed")
				// Let a later page ask for these movies again
				asked.forEach { lookedUpMovies.remove(it.id) }
				return@launch
			}
			ensureActive()
			_state.update { state ->
				state.copy(collections = mergeCollections(state.collections, asked, found))
			}
		}
	}
}

/**
 * Split a page of search results into the movie/show cards and the people to show, dropping what [filter] rejects
 * and anything already in [seen] (pages overlap when Seerr's ranking shifts between calls).
 */
internal fun splitResults(
	results: List<SeerrResult>,
	filter: SeerrSearchFilter,
	includePeople: Boolean,
	seen: MutableSet<String>,
): SeerrSearchSession.Batch {
	val media = mutableListOf<SeerrResult>()
	val people = mutableListOf<SeerrSearchPerson>()
	for (item in results) {
		val isPerson = item.mediaType == SeerrSearchFilter.MEDIA_PERSON
		if (!isPerson && !item.isMovie && !item.isTv) continue
		if (isPerson && !includePeople) continue
		if (!filter.accepts(item) || !seen.add(item.dedupeKey)) continue
		if (isPerson) people += SeerrSearchPerson(item) else media += item
	}
	return SeerrSearchSession.Batch(media, people)
}

/**
 * Add the collections [found] for [movies] to [current], in the order of the movies, once per collection. A
 * collection's poster is withheld when any of its movies here has protected library artwork.
 */
internal fun mergeCollections(
	current: List<SeerrSearchCollection>,
	movies: List<SeerrResult>,
	found: SeerrMovieCollections,
): List<SeerrSearchCollection> {
	val byId = LinkedHashMap<Int, SeerrSearchCollection>()
	current.forEach { byId[it.tmdbCollectionId] = it }
	for (movie in movies) {
		val ref = found.results[movie.id.toString()] ?: continue
		val existing = byId[ref.id]
		byId[ref.id] = when {
			existing == null -> SeerrSearchCollection(ref, posterWithheld = movie.libraryArtwork)
			movie.libraryArtwork && !existing.posterWithheld -> existing.copy(posterWithheld = true)
			else -> existing
		}
	}
	return byId.values.toList()
}

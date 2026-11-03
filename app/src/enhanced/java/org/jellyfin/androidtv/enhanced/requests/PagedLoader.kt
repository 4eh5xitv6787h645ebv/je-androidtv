package org.jellyfin.androidtv.enhanced.requests

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Loads a paged list one page at a time as the user scrolls. A failed page keeps what was already loaded and is
 * tried again by [loadMore]; [reload] starts over (and drops any load in flight, so a late page from the previous
 * filter can't land in the new list). Must be driven from the main thread.
 */
internal class PagedLoader<T>(
	private val scope: CoroutineScope,
	private val errorMessage: (Throwable) -> String,
	private val fetch: suspend (page: Int) -> Page<T>,
) {
	companion object {
		// Stop chaining loads after this many pages in a row had every row filtered out
		private const val MAX_EMPTY_PAGES = 5
	}

	class Page<T>(val items: List<T>, val totalPages: Int)

	data class State<T>(
		val items: List<T> = emptyList(),
		val loading: Boolean = false,
		val error: String? = null,
		val started: Boolean = false,
		val endReached: Boolean = false,
	)

	private val _state = MutableStateFlow(State<T>())
	val state: StateFlow<State<T>> = _state

	private var nextPage = 1
	private var job: Job? = null

	/** Load the first page unless that already happened. */
	fun ensureStarted() {
		if (!_state.value.started) reload()
	}

	fun reload() {
		job?.cancel()
		job = null
		nextPage = 1
		_state.value = State(started = true)
		loadMore()
	}

	fun loadMore() {
		if (job?.isActive == true || _state.value.endReached) return
		_state.value = _state.value.copy(loading = true, error = null, started = true)
		job = scope.launch {
			try {
				var emptyPages = 0
				while (true) {
					val page = nextPage
					val result = fetch(page)
					nextPage = page + 1
					val end = page >= result.totalPages
					_state.value = _state.value.copy(items = _state.value.items + result.items, endReached = end)
					// A page whose rows were all filtered out adds nothing to scroll onto, so nothing would trigger the
					// next load: keep going for a few pages
					emptyPages = if (result.items.isEmpty()) emptyPages + 1 else 0
					if (end || emptyPages == 0 || emptyPages >= MAX_EMPTY_PAGES) break
				}
				_state.value = _state.value.copy(loading = false)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Page load failed")
				_state.value = _state.value.copy(loading = false, error = errorMessage(err))
			}
		}
	}

	/** Change the loaded rows in place (drop a removed card, patch artwork) without reloading. */
	fun update(transform: (List<T>) -> List<T>) {
		_state.value = _state.value.copy(items = transform(_state.value.items))
	}
}

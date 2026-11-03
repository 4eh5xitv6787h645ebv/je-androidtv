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
 * filter can't land in the new list); [refresh] fetches the loaded pages again and swaps the rows only once they are
 * all in, so the focused row survives. Rows are unique by [key]: when the server's list shifts between two page
 * loads (a new request arrives), a row seen on an earlier page is skipped. Must be driven from the main thread.
 */
internal class PagedLoader<T>(
	private val scope: CoroutineScope,
	private val errorMessage: (Throwable) -> String,
	val key: (T) -> Any,
	private val fetch: suspend (page: Int) -> Page<T>,
) {
	companion object {
		// Stop chaining loads after this many pages in a row had every row filtered out
		internal const val MAX_EMPTY_PAGES = 5
	}

	class Page<T>(val items: List<T>, val totalPages: Int)

	/**
	 * [paused] means loading stopped after [MAX_EMPTY_PAGES] fully filtered pages although the server has more: the
	 * list isn't known to be complete, so the page offers to load more instead of saying there is nothing.
	 */
	data class State<T>(
		val items: List<T> = emptyList(),
		val loading: Boolean = false,
		val error: String? = null,
		val started: Boolean = false,
		val endReached: Boolean = false,
		val paused: Boolean = false,
	)

	private val _state = MutableStateFlow(State<T>())
	val state: StateFlow<State<T>> = _state

	private var nextPage = 1
	private var job: Job? = null

	/** Set by [invalidate]: the rows may be out of date and are refreshed the next time the list is shown. */
	var stale = false
		private set

	/** The key of the row that last had the focus, so the page can put it back there when it is shown again. */
	var focusedKey: Any? = null

	private var focusRestorePending = false

	/** Ask the grid to focus [focusedKey] again when it is next composed; false when there is nothing to restore. */
	fun requestFocusRestore(): Boolean {
		val key = focusedKey ?: return false
		focusRestorePending = _state.value.items.any { key(it) == key }
		return focusRestorePending
	}

	/** The key to focus again, once (switching tabs later must not pull the focus back into the grid). */
	fun takeFocusRestore(): Any? = focusedKey.takeIf { focusRestorePending }.also { focusRestorePending = false }

	/** Load the first page unless that already happened. */
	fun ensureStarted() {
		if (!_state.value.started) reload()
	}

	/** Load the first page, or refresh the rows when they were [invalidate]d while off screen. */
	fun show() {
		if (!_state.value.started) reload()
		else if (stale) refresh()
	}

	/** Mark the rows out of date (a write elsewhere may have changed them); [show] refreshes them. */
	fun invalidate() {
		if (_state.value.started) stale = true
	}

	fun reload() {
		job?.cancel()
		job = null
		stale = false
		focusedKey = null
		nextPage = 1
		_state.value = State(started = true)
		loadMore()
	}

	/**
	 * Fetch the pages loaded so far again and replace the rows in one go. The old rows stay on screen meanwhile
	 * (rows keep their keys, so focus stays on the same card); a failure keeps them and shows the error under them.
	 */
	fun refresh() {
		if (!_state.value.started) return reload()
		job?.cancel()
		stale = false
		val pages = (nextPage - 1).coerceAtLeast(1)
		_state.value = _state.value.copy(loading = true, error = null)
		job = scope.launch {
			try {
				val items = LinkedHashMap<Any, T>()
				var page = 1
				var end = false
				var emptyPages = 0
				// The same number of pages, plus more while they come back fully filtered (like loadMore)
				while (!end && (page <= pages || (emptyPages in 1 until MAX_EMPTY_PAGES))) {
					val result = fetch(page)
					end = page >= result.totalPages
					var fresh = 0
					result.items.forEach { item -> if (items.putIfAbsent(key(item), item) == null) fresh++ }
					emptyPages = if (fresh == 0) emptyPages + 1 else 0
					page++
				}
				nextPage = page
				_state.value = State(
					items = items.values.toList(),
					started = true,
					endReached = end,
					paused = !end && emptyPages >= MAX_EMPTY_PAGES,
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Page refresh failed")
				_state.value = _state.value.copy(loading = false, error = errorMessage(err))
			}
		}
	}

	fun loadMore() {
		if (job?.isActive == true || _state.value.endReached) return
		_state.value = _state.value.copy(loading = true, error = null, started = true, paused = false)
		job = scope.launch {
			try {
				var emptyPages = 0
				while (true) {
					val page = nextPage
					val result = fetch(page)
					nextPage = page + 1
					val end = page >= result.totalPages
					val known = _state.value.items.mapTo(HashSet(), key)
					val fresh = result.items.filter { known.add(key(it)) }
					_state.value = _state.value.copy(items = _state.value.items + fresh, endReached = end)
					// A page whose rows were all filtered out adds nothing to scroll onto, so nothing would trigger the
					// next load: keep going for a few pages, then stop and let the user ask for more
					emptyPages = if (fresh.isEmpty()) emptyPages + 1 else 0
					if (end || emptyPages == 0) break
					if (emptyPages >= MAX_EMPTY_PAGES) {
						_state.value = _state.value.copy(paused = true)
						break
					}
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

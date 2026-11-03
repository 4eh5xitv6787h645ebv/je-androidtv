package org.jellyfin.androidtv.enhanced.search

import android.content.Context
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The Seerr rows of the search screen: "Movies & shows on Seerr", "Collections on Seerr" and "People on Seerr",
 * below the library rows in [rowsAdapter]. Each row is only in the list while it has cards, so a search without
 * Seerr results (or with Seerr off) looks exactly like stock.
 *
 * The search fragment's delegate owns one of these; it calls [reattach] after rebuilding the library rows and
 * offers clicks and selections to [onItemClicked] and [onItemSelected] first.
 */
class SeerrSearchRows(
	private val context: Context,
	private val rowsAdapter: MutableObjectAdapter<Row>,
) : KoinComponent {
	private val navigationRepository by inject<NavigationRepository>()

	private var session: SeerrSearchSession? = null
	private var state = SeerrSearchState()

	private val mediaAdapter = ArrayObjectAdapter(seerrSearchPresenterSelector { session?.remove(it) })
	private val collectionsAdapter = ArrayObjectAdapter(seerrSearchPresenterSelector())
	private val peopleAdapter = ArrayObjectAdapter(seerrSearchPresenterSelector())

	private val mediaRow = ListRow(HeaderItem(context.getString(R.string.je_search_row_media)), mediaAdapter)
	private val collectionsRow = ListRow(HeaderItem(context.getString(R.string.je_search_row_collections)), collectionsAdapter)
	private val peopleRow = ListRow(HeaderItem(context.getString(R.string.je_search_row_people)), peopleAdapter)
	private val rows = listOf(mediaRow, collectionsRow, peopleRow)

	/** Show [session]'s results until the calling coroutine is cancelled (the search screen goes away). */
	suspend fun bind(session: SeerrSearchSession) {
		this.session = session
		try {
			session.onShown()
			session.state.collect(::render)
		} finally {
			if (this.session === session) this.session = null
		}
	}

	/** Put the Seerr rows back after the library rows were rebuilt (which clears [rowsAdapter]). */
	fun reattach() = placeRows()

	private fun render(newState: SeerrSearchState) {
		state = newState
		mediaAdapter.setItems(rowItems(newState), cardDiff)
		collectionsAdapter.setItems(newState.collections, cardDiff)
		peopleAdapter.setItems(newState.people, cardDiff)
		placeRows()
	}

	private fun placeRows() {
		rows.forEachIndexed { index, row ->
			val shown = row.adapter.size() > 0
			val present = rowsAdapter.indexOf(row) >= 0
			if (shown && !present) {
				// Keep media, collections, people in that order, after whatever the library rows are
				val before = rows.drop(index + 1).map { rowsAdapter.indexOf(it) }.firstOrNull { it >= 0 }
				rowsAdapter.add(before ?: rowsAdapter.size(), row)
			} else if (!shown && present) {
				rowsAdapter.remove(row)
			}
		}
	}

	/** Handle a click on one of the Seerr cards. False for anything else, which the caller handles as before. */
	fun onItemClicked(item: Any?): Boolean {
		when (item) {
			is SeerrResult -> navigationRepository.navigate(
				EnhancedDestinations.seerrDetails(item.mediaType ?: SeerrRepository.MEDIA_MOVIE, item.id)
			)

			is SeerrSearchPerson -> navigationRepository.navigate(
				EnhancedDestinations.seerrDiscovery(
					SeerrSource.Person(
						jellyfinPersonId = null,
						tmdbPersonId = item.tmdbPersonId,
						name = item.name,
						title = context.getString(R.string.je_more_from_title, item.name),
					)
				)
			)

			is SeerrSearchCollection -> navigationRepository.navigate(
				SeerrSearchDestinations.collection(item.tmdbCollectionId, item.collection.name.orEmpty())
			)

			is SeerrSearchViewAll -> navigationRepository.navigate(
				SeerrSearchDestinations.results(item.query, context.getString(R.string.je_search_results_title, item.query))
			)

			is SeerrSearchError -> session?.retry()
			else -> return false
		}
		return true
	}

	/**
	 * Handle a selection in one of the Seerr rows: nearing the end of the results row loads its next page. True when
	 * the selection was in a Seerr row (so it isn't a library item and has no backdrop).
	 */
	fun onItemSelected(item: Any?, row: Row?): Boolean {
		if (row == null || rows.none { it === row }) return false
		if (row === mediaRow && item is SeerrResult) {
			val index = mediaAdapter.indexOf(item)
			if (index >= 0 && index >= mediaAdapter.size() - SeerrSearchSession.LOAD_MORE_THRESHOLD) session?.loadMore()
		}
		return true
	}

	private companion object {
		/** The results row: the cards, then Retry after a failed page or "View all" when the row is full. */
		fun rowItems(state: SeerrSearchState): List<Any> = buildList {
			addAll(state.media)
			when {
				state.error != null -> add(state.error)
				state.viewAll -> add(SeerrSearchViewAll(state.query))
			}
		}

		fun key(item: Any): Any = when (item) {
			is SeerrResult -> item.dedupeKey
			is SeerrSearchPerson -> "person-${item.tmdbPersonId}"
			is SeerrSearchCollection -> "collection-${item.tmdbCollectionId}"
			is SeerrSearchViewAll -> "view-all"
			is SeerrSearchError -> "error"
			else -> item
		}

		val cardDiff = object : DiffCallback<Any>() {
			override fun areItemsTheSame(oldItem: Any, newItem: Any) = key(oldItem) == key(newItem)
			override fun areContentsTheSame(oldItem: Any, newItem: Any) = oldItem == newItem
		}
	}
}

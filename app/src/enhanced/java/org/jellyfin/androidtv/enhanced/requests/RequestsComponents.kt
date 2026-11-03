package org.jellyfin.androidtv.enhanced.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.design.ErrorRow
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.design.LoadingRow
import org.jellyfin.androidtv.enhanced.ui.design.RowDensity
import org.jellyfin.androidtv.enhanced.ui.design.SkeletonShape
import org.jellyfin.androidtv.enhanced.ui.hub.EmptyAction
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.rememberBackAction

/**
 * A grid of list cards over a [PagedLoader]: loading (a skeleton) and first-page errors (with Retry) fill the page,
 * later failures show an inline Retry under the rows, and the next page loads when focus gets near the end. When the
 * loader paused on filtered-out pages, a "Load more" button (under the rows, or as the empty state's action) carries
 * on. [header] (filters, the sync button) sits above the grid and never scrolls.
 */
@Composable
internal fun <T> PagedGrid(
	state: PagedLoader.State<T>,
	loader: PagedLoader<T>,
	columns: Int,
	emptyMessage: String,
	modifier: Modifier = Modifier,
	header: (@Composable () -> Unit)? = null,
	item: @Composable LazyGridItemScope.(T) -> Unit,
) {
	val loadState: LoadState<List<T>> = when {
		state.items.isNotEmpty() -> LoadState.Ready(state.items)
		state.error != null -> LoadState.Error(state.error) { loader.reload() }
		state.loading || !state.started -> LoadState.Loading
		else -> LoadState.Ready(emptyList())
	}
	// Loading stopped after several fully filtered pages: the server has more, so offer them rather than "nothing"
	val loadMoreLabel = stringResource(R.string.je_requests_load_more)
	val loadMore = remember(loader, loadMoreLabel) { EmptyAction(loadMoreLabel) { loader.loadMore() } }

	Column(modifier = modifier.fillMaxSize()) {
		header?.invoke()
		LoadStateContent(
			state = loadState,
			empty = { it.isEmpty() },
			emptyMessage = if (state.paused) stringResource(R.string.je_requests_paused_empty) else emptyMessage,
			emptyAction = if (state.paused) loadMore else rememberBackAction(),
			loading = SkeletonShape.Grid(columns = columns, height = RowDensity.Large.height),
		) { items ->
			val gridState = rememberLazyGridState()
			// Back from another screen: bring the card that had the focus into view and focus it again. Back from a
			// card's dialog: focus the card again unless the focus is still inside it
			LaunchedEffect(loader) {
				// A grid that was just composed has no focused card yet (a destroyed view never reported losing it)
				loader.focusedNow = null
				loader.takeFocusRestore()?.let(loader::focusCard)
			}
			val target by loader.focusTarget.collectAsState()
			val targetFocus = remember { FocusRequester() }
			LaunchedEffect(target) {
				val key = target ?: return@LaunchedEffect
				val index = items.indexOfFirst { loader.key(it) == key }
				if (index >= 0 && loader.focusedNow != key) {
					if (gridState.layoutInfo.visibleItemsInfo.none { it.key == key }) gridState.scrollToItem(index)
					withFrameNanos { }
					runCatching { targetFocus.requestFocus() }
				}
				loader.focusHandled(key)
			}
			LaunchedEffect(gridState, loader) {
				snapshotFlow {
					val info = gridState.layoutInfo
					(info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - columns * 2
				}.distinctUntilChanged().collect { nearEnd -> if (nearEnd) loader.loadMore() }
			}
			LazyVerticalGrid(
				state = gridState,
				columns = GridCells.Fixed(columns),
				contentPadding = PaddingValues(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, top = 4.dp, bottom = JeSpace.pageBottom),
				horizontalArrangement = Arrangement.spacedBy(JeSpace.gridGap),
				verticalArrangement = Arrangement.spacedBy(JeSpace.gridGap),
			) {
				items(items, key = loader.key) { value ->
					val key = loader.key(value)
					// Passes the cell's width on to the card unchanged
					Box(
						propagateMinConstraints = true,
						modifier = Modifier
							.then(if (key == target) Modifier.focusRequester(targetFocus) else Modifier)
							.onFocusChanged {
								if (it.hasFocus) {
									loader.focusedKey = key
									loader.focusedNow = key
								} else if (loader.focusedNow == key) {
									loader.focusedNow = null
								}
							},
					) {
						item(value)
					}
				}
				if (state.error != null || state.loading || state.paused) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						when {
							// ErrorRow brings its own page padding; the grid already has it
							state.error != null -> ErrorRow(message = state.error, onRetry = { loader.loadMore() }, modifier = Modifier.offset(x = -JeSpace.pageHorizontal))
							state.loading -> LoadingRow()
							else -> Box(modifier = Modifier.padding(vertical = 8.dp)) { JeButton(text = loadMoreLabel, onClick = { loader.loadMore() }) }
						}
					}
				}
			}
		}
	}
}

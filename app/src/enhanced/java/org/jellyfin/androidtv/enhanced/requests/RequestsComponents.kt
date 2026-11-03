package org.jellyfin.androidtv.enhanced.requests

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import android.view.KeyEvent
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.hub.EmptyAction
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.hub.rememberBackAction
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonBase
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.design.Tokens

internal val ColorGreen = Color(0xFF22A06B)
internal val ColorBlue = Color(0xFF2D9CDB)
internal val ColorIndigo = Color(0xFF6366F1)
internal val ColorAmber = Color(0xFFD97706)
internal val ColorRed = Color(0xFFB91C1C)
internal val ColorGrey = Color(0xFF6B7280)

/** A filter or tab button: highlighted while [selected]. */
@Composable
internal fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
	Button(
		onClick = onClick,
		modifier = modifier,
		colors = if (selected) ButtonDefaults.colors(
			containerColor = JellyfinTheme.colorScheme.buttonActive,
			contentColor = JellyfinTheme.colorScheme.onButtonActive,
		) else ButtonDefaults.colors(),
	) {
		Text(text)
	}
}

/** A small coloured status label. */
@Composable
internal fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.background(color.copy(alpha = 0.9f), JellyfinTheme.shapes.extraSmall)
			.padding(horizontal = 6.dp, vertical = 2.dp),
	) {
		Text(text = text, color = Color.White, fontSize = 11.sp, maxLines = 1)
	}
}

@Composable
internal fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(4.dp)
			.clip(JellyfinTheme.shapes.extraSmall)
			.background(Color.White.copy(alpha = 0.15f)),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(fraction.coerceIn(0f, 1f))
				.height(4.dp)
				.background(color),
		)
	}
}

/**
 * A focusable card with a poster on the left and [content] on the right. [onMenu] runs on long press and on the
 * remote's menu key, like the app's other cards.
 */
@Composable
internal fun PosterCard(
	posterUrl: String?,
	@DrawableRes placeholder: Int,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	onMenu: (() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = JellyfinTheme.shapes.medium
	val menuModifier = if (onMenu == null) Modifier else Modifier.onPreviewKeyEvent { event ->
		if (event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_MENU) return@onPreviewKeyEvent false
		if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onMenu()
		true
	}

	ButtonBase(
		onClick = onClick,
		onLongClick = onMenu,
		shape = shape,
		interactionSource = interactionSource,
		colors = ButtonDefaults.colors(
			containerColor = Color.White.copy(alpha = 0.06f),
			contentColor = Tokens.Color.colorGrey100,
			focusedContainerColor = JellyfinTheme.colorScheme.listButtonFocused,
			focusedContentColor = Tokens.Color.colorWhite,
		),
		modifier = modifier
			.then(menuModifier)
			.border(2.dp, if (focused) Tokens.Color.colorGrey100 else Color.Transparent, shape),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(10.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Box(
				modifier = Modifier
					.size(64.dp, 96.dp)
					.clip(JellyfinTheme.shapes.small)
					.background(JellyfinTheme.colorScheme.surface),
			) {
				if (posterUrl != null) {
					AsyncImage(
						url = posterUrl,
						aspectRatio = 2f / 3f,
						scaleType = ImageView.ScaleType.CENTER_CROP,
						modifier = Modifier.fillMaxSize(),
					)
				} else {
					Image(
						painter = painterResource(placeholder),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize(0.4f)
							.align(Alignment.Center),
					)
				}
			}
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(4.dp),
				content = content,
			)
		}
	}
}

@Composable
internal fun CardTitle(text: String, trailing: String? = null) {
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
		Text(
			text = text,
			fontSize = 16.sp,
			color = Tokens.Color.colorWhite,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f, fill = false),
		)
		if (trailing != null) Text(text = trailing, fontSize = 13.sp, color = secondaryTextColor(), maxLines = 1)
	}
}

@Composable
internal fun CardLine(text: String, color: Color = secondaryTextColor(), maxLines: Int = 1) {
	Text(text = text, fontSize = 13.sp, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A short message in the page body (empty list, hidden feature…). */
@Composable
internal fun PageMessage(text: String, modifier: Modifier = Modifier) {
	Box(modifier = modifier.fillMaxSize()) {
		Text(text = text, color = secondaryTextColor(), fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
	}
}

/**
 * A grid over a [PagedLoader]: loading and first-page errors (with Retry) fill the page, later failures show a Retry
 * button under the rows, and the next page loads when focus gets near the end. When the loader paused on filtered-out
 * pages, a "Load more" button (under the rows, or in place of the empty message) carries on.
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
		) { items ->
			val gridState = rememberLazyGridState()
			// Back from another screen: bring the card that had the focus into view and focus it again
			val restoreKey = remember(loader) { loader.takeFocusRestore() }
			val restoreFocus = remember { FocusRequester() }
			LaunchedEffect(restoreKey) {
				val index = restoreKey?.let { key -> items.indexOfFirst { loader.key(it) == key } } ?: -1
				if (index < 0) return@LaunchedEffect
				gridState.scrollToItem(index)
				withFrameNanos { }
				runCatching { restoreFocus.requestFocus() }
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
				contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 32.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				items(items, key = loader.key) { value ->
					val key = loader.key(value)
					// Passes the cell's width on to the card unchanged
					Box(
						propagateMinConstraints = true,
						modifier = Modifier
							.then(if (key == restoreKey) Modifier.focusRequester(restoreFocus) else Modifier)
							.onFocusChanged { if (it.hasFocus) loader.focusedKey = key },
					) {
						item(value)
					}
				}
				if (state.error != null || state.loading || state.paused) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						Row(
							horizontalArrangement = Arrangement.spacedBy(12.dp),
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier.padding(vertical = 8.dp),
						) {
							if (state.error != null) {
								Text(text = state.error, color = Tokens.Color.colorGrey100, fontSize = 14.sp)
								Button(onClick = { loader.loadMore() }) { Text(stringResource(R.string.je_retry)) }
							} else if (state.loading) {
								Text(text = stringResource(R.string.je_loading), color = secondaryTextColor(), fontSize = 14.sp)
							} else {
								Button(onClick = { loader.loadMore() }) { Text(loadMoreLabel) }
							}
						}
					}
				}
			}
		}
	}
}

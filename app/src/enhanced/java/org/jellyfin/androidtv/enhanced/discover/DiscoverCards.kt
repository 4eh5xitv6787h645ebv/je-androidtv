package org.jellyfin.androidtv.enhanced.discover

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardMenu
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrStatusBadge
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.design.Tokens
import org.koin.java.KoinJavaComponent

/** Poster card of an Upcoming row: like the Seerr card, with the release date in place of the year. */
@Composable
private fun DiscoverUpcomingContent(card: DiscoverUpcomingCard, focused: Boolean) {
	val item = card.result
	val marquee = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 0) else Modifier
	val date = upcomingDateLabel(item)

	ItemPreview(
		card = {
			ItemCard(
				modifier = Modifier.size(100.dp, 150.dp),
				image = {
					val url = if (item.libraryArtwork) item.libraryPosterUrl else SeerrImages.poster(item.posterPath)
					if (url != null) {
						AsyncImage(
							url = url,
							aspectRatio = 2f / 3f,
							scaleType = ImageView.ScaleType.CENTER_CROP,
							modifier = Modifier.fillMaxSize(),
						)
					} else {
						Image(
							painter = painterResource(if (item.isTv) R.drawable.ic_tv else R.drawable.ic_clapperboard),
							contentDescription = null,
							modifier = Modifier
								.fillMaxSize(0.4f)
								.align(Alignment.Center),
						)
					}
				},
				overlay = {
					SeerrStatusBadge(
						status = item.status,
						modifier = Modifier
							.align(Alignment.TopEnd)
							.padding(Tokens.Space.spaceXs),
					)
				},
			)
		},
		title = {
			Text(
				text = item.displayTitle,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				textAlign = TextAlign.Center,
				modifier = marquee,
			)
		},
		subtitle = date?.let { text ->
			{ Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
		},
	)
}

/** A genre tile (backdrop with the name on it) or a studio/network tile (white logo on a dark card). */
@Composable
private fun DiscoverTileContent(tile: DiscoverTile, focused: Boolean) {
	val showName = !tile.logo || tile.imageUrl == null
	ItemPreview(
		card = {
			ItemCard(
				modifier = Modifier.size(192.dp, 108.dp),
				image = {
					when {
						tile.imageUrl == null -> Box(
							modifier = Modifier
								.fillMaxSize()
								.background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF14161C)))),
						)

						tile.logo -> AsyncImage(
							url = tile.imageUrl,
							aspectRatio = 16f / 9f,
							scaleType = ImageView.ScaleType.FIT_CENTER,
							modifier = Modifier
								.fillMaxSize()
								.padding(horizontal = 24.dp, vertical = 20.dp),
						)

						else -> AsyncImage(
							url = tile.imageUrl,
							aspectRatio = 16f / 9f,
							scaleType = ImageView.ScaleType.CENTER_CROP,
							modifier = Modifier.fillMaxSize(),
						)
					}
				},
				overlay = if (showName) {
					{
						Box(
							modifier = Modifier
								.fillMaxSize()
								.background(Color.Black.copy(alpha = if (tile.imageUrl != null) 0.45f else 0f)),
						)
						Text(
							text = tile.title,
							color = Color.White,
							fontSize = 18.sp,
							maxLines = 2,
							textAlign = TextAlign.Center,
							modifier = Modifier
								.align(Alignment.Center)
								.padding(horizontal = 8.dp),
						)
					}
				} else null,
			)
		},
		// Logo tiles name the company underneath; genre tiles already carry it
		title = if (showName) null else {
			{
				Text(
					text = tile.title,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					textAlign = TextAlign.Center,
					color = if (focused) Tokens.Color.colorWhite else Color.Unspecified,
				)
			}
		},
	)
}

/** Placeholder card while a row loads, when it's empty, or when it failed (selecting it retries). */
@Composable
private fun DiscoverStatusContent(status: DiscoverRowStatus, focused: Boolean) {
	ItemCard(
		modifier = Modifier.size(240.dp, 150.dp),
		image = {
			Column(
				modifier = Modifier
					.align(Alignment.Center)
					.padding(16.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				val text = when (status) {
					DiscoverRowStatus.Loading -> stringResource(R.string.je_loading)
					DiscoverRowStatus.Empty -> stringResource(R.string.je_nothing_found)
					is DiscoverRowStatus.Error -> status.message ?: stringResource(R.string.je_error_generic)
				}
				Text(text = text, color = Tokens.Color.colorGrey100, fontSize = 14.sp, maxLines = 4, textAlign = TextAlign.Center)
				if (status is DiscoverRowStatus.Error) {
					Box(
						modifier = Modifier
							.background(
								if (focused) JellyfinTheme.colorScheme.buttonFocused else JellyfinTheme.colorScheme.button,
								JellyfinTheme.shapes.small,
							)
							.padding(horizontal = 16.dp, vertical = 6.dp),
					) {
						Text(
							text = stringResource(R.string.je_retry),
							color = if (focused) JellyfinTheme.colorScheme.onButtonFocused else JellyfinTheme.colorScheme.onButton,
						)
					}
				}
			}
		},
	)
}

/**
 * Leanback presenter for the Discover screen's own cards ([DiscoverUpcomingCard], [DiscoverTile],
 * [DiscoverRowStatus]). Upcoming cards get the Seerr card menu on long press / menu key, like [SeerrCardPresenter]
 * cards; [onRemoveCard] takes a card out of its row when a menu entry (such as Hide) asks for it.
 */
class DiscoverCardPresenter(
	private val onRemoveCard: (SeerrResult) -> Unit,
) : Presenter() {
	private val enhancedRepository by KoinJavaComponent.inject<EnhancedRepository>(EnhancedRepository::class.java)

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = true
		}
		val holder = CardViewHolder(view)
		view.setOnLongClickListener { showMenu(holder) }
		return holder
	}

	private fun showMenu(holder: CardViewHolder): Boolean {
		val result = (holder.item.value as? DiscoverUpcomingCard)?.result ?: return false
		return SeerrCardMenu.show(holder.view.context, holder.view, result, enhancedRepository.current) { onRemoveCard(result) }
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		(viewHolder as CardViewHolder).item.value = item
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		(viewHolder as CardViewHolder).item.value = null
	}

	private class CardViewHolder(composeView: ComposeView) : ViewHolder(composeView) {
		val item = MutableStateFlow<Any?>(null)
		private val focused = MutableStateFlow(false)

		init {
			composeView.setContent {
				val current by item.collectAsState()
				val isFocused by focused.collectAsState()
				when (val value = current) {
					is DiscoverUpcomingCard -> DiscoverUpcomingContent(value, isFocused)
					is DiscoverTile -> DiscoverTileContent(value, isFocused)
					is DiscoverRowStatus -> DiscoverStatusContent(value, isFocused)
				}
			}
			composeView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> focused.value = hasFocus }
			// Same as Seerr cards: the menu key opens the card menu, and is swallowed so upstream's KeyProcessor
			// doesn't treat the card as a library item
			composeView.setOnKeyListener { _, keyCode, event ->
				if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
				if (event.action == KeyEvent.ACTION_UP) composeView.performLongClick()
				true
			}
		}
	}
}

package org.jellyfin.androidtv.enhanced.ui.seerr

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.design.Tokens

/** Status label and colour for a Seerr media status, or null when the title has not been requested. */
data class SeerrStatusLabel(@StringRes val text: Int, val color: Color)

fun seerrStatusLabel(status: Int?): SeerrStatusLabel? = when (status) {
	SeerrMediaStatus.AVAILABLE -> SeerrStatusLabel(R.string.je_status_available, Color(0xFF22A06B))
	SeerrMediaStatus.PARTIALLY_AVAILABLE -> SeerrStatusLabel(R.string.je_status_partial, Color(0xFF2D9CDB))
	SeerrMediaStatus.PROCESSING -> SeerrStatusLabel(R.string.je_status_processing, Color(0xFF6366F1))
	SeerrMediaStatus.PENDING -> SeerrStatusLabel(R.string.je_status_pending, Color(0xFFD97706))
	SeerrMediaStatus.BLOCKLISTED -> SeerrStatusLabel(R.string.je_status_blocklisted, Color(0xFFB91C1C))
	else -> null
}

@Composable
fun SeerrStatusBadge(status: Int?, modifier: Modifier = Modifier) {
	val label = seerrStatusLabel(status) ?: return
	Box(
		modifier = modifier
			.background(label.color.copy(alpha = 0.9f), JellyfinTheme.shapes.extraSmall)
			.padding(horizontal = 6.dp, vertical = 2.dp),
	) {
		Text(text = stringResource(label.text), color = Color.White, fontSize = 10.sp, maxLines = 1)
	}
}

/** A poster card for a Seerr result, styled like the app's own item cards. */
@Composable
fun SeerrCard(
	item: SeerrResult,
	focused: Boolean,
	modifier: Modifier = Modifier,
) {
	val marquee = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 0) else Modifier
	val subtitle = listOfNotNull(
		item.year.takeIf { !item.libraryArtwork },
		item.displayCredit,
	).joinToString(" · ").ifEmpty { null }

	ItemPreview(
		modifier = modifier,
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
		subtitle = subtitle?.let { text ->
			{
				Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = marquee)
			}
		},
	)
}

/** Seerr cards are the same card when they're the same title, so adapter updates keep the position and focus. */
object SeerrResultDiff : DiffCallback<SeerrResult>() {
	override fun areItemsTheSame(oldItem: SeerrResult, newItem: SeerrResult) = oldItem.dedupeKey == newItem.dedupeKey
	override fun areContentsTheSame(oldItem: SeerrResult, newItem: SeerrResult) = oldItem == newItem
}

/** Card at the end of a Seerr row that opens the full discovery grid. */
data class SeerrViewAllCard(val source: SeerrSource)

@Composable
private fun SeerrViewAllContent(focused: Boolean) {
	ItemPreview(
		card = {
			ItemCard(
				modifier = Modifier.size(100.dp, 150.dp),
				image = {
					Image(
						painter = painterResource(R.drawable.je_ic_travel_explore),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize(0.4f)
							.align(Alignment.Center),
					)
				},
			)
		},
		title = {
			Text(
				text = stringResource(R.string.je_view_all),
				maxLines = 1,
				textAlign = TextAlign.Center,
				color = if (focused) Tokens.Color.colorWhite else Color.Unspecified,
			)
		},
	)
}

/**
 * Leanback presenter for [SeerrResult] and [SeerrViewAllCard] items, used in rows on the details screen and in
 * grids. The menu key / long press opens the [SeerrCardMenu]; [onRemoveCard] lets a menu entry (such as Hide)
 * take the card out of its row.
 */
class SeerrCardPresenter(
	private val onRemoveCard: ((SeerrResult) -> Unit)? = null,
) : Presenter() {
	private val enhancedRepository by org.koin.java.KoinJavaComponent.inject<org.jellyfin.androidtv.enhanced.data.EnhancedRepository>(org.jellyfin.androidtv.enhanced.data.EnhancedRepository::class.java)

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
		val result = holder.item.value as? SeerrResult ?: return false
		return SeerrCardMenu.show(holder.view.context, holder.view, result, enhancedRepository.current) { onRemoveCard?.invoke(result) }
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
					is SeerrResult -> SeerrCard(value, isFocused)
					is SeerrViewAllCard -> SeerrViewAllContent(isFocused)
				}
			}
			composeView.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus -> focused.value = hasFocus }
			// The menu key opens the card menu (same as a long press); swallow it either way so upstream's
			// KeyProcessor doesn't try to treat the card as a library item
			composeView.setOnKeyListener { _, keyCode, event ->
				if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
				if (event.action == KeyEvent.ACTION_UP) composeView.performLongClick()
				true
			}
		}
	}
}

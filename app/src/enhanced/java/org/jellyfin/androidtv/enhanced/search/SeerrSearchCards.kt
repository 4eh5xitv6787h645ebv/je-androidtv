package org.jellyfin.androidtv.enhanced.search

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardPresenter
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.design.Tokens

/**
 * Presenters for the Seerr search rows: movie/show cards are the shared [SeerrCardPresenter] (with its card menu),
 * people, collections, "View all" and error cards are [SeerrSearchCardPresenter].
 */
fun seerrSearchPresenterSelector(onRemoveCard: ((SeerrResult) -> Unit)? = null) = ClassPresenterSelector().apply {
	addClassPresenter(SeerrResult::class.java, SeerrCardPresenter(onRemoveCard))
	val cards = SeerrSearchCardPresenter()
	addClassPresenter(SeerrSearchPerson::class.java, cards)
	addClassPresenter(SeerrSearchCollection::class.java, cards)
	addClassPresenter(SeerrSearchViewAll::class.java, cards)
	addClassPresenter(SeerrSearchError::class.java, cards)
}

@Composable
private fun SearchCard(
	imageUrl: String?,
	@DrawableRes fallbackIcon: Int,
	title: String,
	subtitle: String?,
	focused: Boolean,
) {
	val marquee = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 0) else Modifier
	ItemPreview(
		card = {
			ItemCard(
				modifier = Modifier.size(100.dp, 150.dp),
				image = {
					if (imageUrl != null) {
						AsyncImage(
							url = imageUrl,
							aspectRatio = 2f / 3f,
							scaleType = ImageView.ScaleType.CENTER_CROP,
							modifier = Modifier.fillMaxSize(),
						)
					} else {
						Image(
							painter = painterResource(fallbackIcon),
							contentDescription = null,
							modifier = Modifier
								.fillMaxSize(0.4f)
								.align(Alignment.Center),
						)
					}
				},
			)
		},
		title = {
			Text(
				text = title,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				textAlign = TextAlign.Center,
				color = if (focused) Tokens.Color.colorWhite else Color.Unspecified,
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

@Composable
private fun SearchCardContent(item: Any?, focused: Boolean) {
	when (item) {
		is SeerrSearchPerson -> SearchCard(
			imageUrl = SeerrImages.poster(item.result.profilePath, "w185"),
			fallbackIcon = R.drawable.ic_user,
			title = item.name,
			subtitle = null,
			focused = focused,
		)

		is SeerrSearchCollection -> SearchCard(
			imageUrl = if (item.posterWithheld) null else SeerrImages.poster(item.collection.posterPath),
			fallbackIcon = R.drawable.ic_folder,
			title = item.collection.name.orEmpty(),
			subtitle = stringResource(R.string.je_search_collection),
			focused = focused,
		)

		is SeerrSearchViewAll -> SearchCard(
			imageUrl = null,
			fallbackIcon = R.drawable.je_ic_travel_explore,
			title = stringResource(R.string.je_view_all),
			subtitle = null,
			focused = focused,
		)

		is SeerrSearchError -> SearchCard(
			imageUrl = null,
			fallbackIcon = R.drawable.ic_error,
			title = item.serverMessage ?: stringResource(R.string.je_search_error),
			subtitle = stringResource(R.string.je_search_select_to_retry),
			focused = focused,
		)
	}
}

/** Leanback presenter for the search-only cards (see [seerrSearchPresenterSelector]). */
class SeerrSearchCardPresenter : Presenter() {
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = true
		}
		return CardViewHolder(view)
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
				SearchCardContent(current, isFocused)
			}
			composeView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> focused.value = hasFocus }
			// These cards have no menu; swallow the menu key so nothing treats them as library items
			composeView.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_MENU }
		}
	}
}

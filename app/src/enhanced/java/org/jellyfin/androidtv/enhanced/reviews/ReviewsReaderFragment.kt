package org.jellyfin.androidtv.enhanced.reviews

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.metadata.displayMessage
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * A full-screen reader for a title's reviews: one review at a time in large type, D-pad left/right for the previous
 * or next review (more TMDB pages load as you reach the end), up/down to scroll a long one, Back to return.
 */
class ReviewsReaderFragment : Fragment() {
	companion object {
		private const val ARG_MEDIA_TYPE = "media_type"
		private const val ARG_KEY = "key"
		private const val ARG_TMDB_ID = "tmdb_id"
		private const val ARG_TITLE = "title"
		private const val ARG_USER = "include_user"
		private const val ARG_TMDB = "include_tmdb"
		private const val ARG_START = "start"

		fun destination(target: ReviewTarget, start: Int) = fragmentDestination<ReviewsReaderFragment> {
			putString(ARG_MEDIA_TYPE, target.mediaType)
			putString(ARG_KEY, target.key)
			putInt(ARG_TMDB_ID, target.tmdbId)
			putString(ARG_TITLE, target.title)
			putBoolean(ARG_USER, target.includeUser)
			putBoolean(ARG_TMDB, target.includeTmdb)
			putInt(ARG_START, start)
		}
	}

	private val repository by inject<ReviewsRepository>()

	private val target by lazy {
		val args = requireArguments()
		ReviewTarget(
			mediaType = args.getString(ARG_MEDIA_TYPE).orEmpty(),
			key = args.getString(ARG_KEY).orEmpty(),
			tmdbId = args.getInt(ARG_TMDB_ID),
			title = args.getString(ARG_TITLE).orEmpty(),
			includeUser = args.getBoolean(ARG_USER),
			includeTmdb = args.getBoolean(ARG_TMDB),
		)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		var state by remember { mutableStateOf<LoadState<ReviewsData>>(LoadState.Loading) }
		var index by remember { mutableIntStateOf(arguments?.getInt(ARG_START) ?: 0) }
		var loadingMore by remember { mutableStateOf(false) }
		val scope = rememberCoroutineScope()

		fun load() {
			state = LoadState.Loading
			scope.launch {
				state = try {
					LoadState.Ready(repository.load(target))
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Failed to load reviews")
					LoadState.Error(err.displayMessage(), ::load)
				}
			}
		}

		fun move(delta: Int) {
			val data = (state as? LoadState.Ready)?.value ?: return
			val next = index + delta
			if (next in data.entries.indices) {
				index = next
			} else if (next == data.entries.size && data.hasMoreTmdb && !loadingMore) {
				loadingMore = true
				scope.launch {
					try {
						val more = repository.loadMoreTmdb(target, data)
						state = LoadState.Ready(more)
						if (next in more.entries.indices) index = next
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Failed to load more TMDB reviews")
					} finally {
						loadingMore = false
					}
				}
			}
		}

		LaunchedEffect(Unit) { load() }

		JellyfinTheme {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(JellyfinTheme.colorScheme.background),
			) {
				LoadStateContent(state, empty = { it.entries.isEmpty() }) { data ->
					val entry = data.entries[index.coerceIn(data.entries.indices)]
					ReviewPage(
						title = target.title,
						entry = entry,
						position = stringResource(R.string.je_reviews_position, index + 1, data.entries.size),
						onMove = ::move,
					)
				}
			}
		}
	}
}

@Composable
private fun ReviewPage(title: String, entry: ReviewEntry, position: String, onMove: (Int) -> Unit) {
	val scroll = rememberScrollState()
	val scope = rememberCoroutineScope()
	val focus = remember { FocusRequester() }

	// A new review starts at the top
	LaunchedEffect(entry) { scroll.scrollTo(0) }
	LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 96.dp, vertical = 40.dp)
			.focusRequester(focus)
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				when (event.key.nativeKeyCode) {
					KeyEvent.KEYCODE_DPAD_LEFT -> onMove(-1)
					KeyEvent.KEYCODE_DPAD_RIGHT -> onMove(1)
					KeyEvent.KEYCODE_DPAD_UP -> scope.launch { scroll.animateScrollBy(-SCROLL_STEP) }
					KeyEvent.KEYCODE_DPAD_DOWN -> scope.launch { scroll.animateScrollBy(SCROLL_STEP) }
					else -> return@onPreviewKeyEvent false
				}
				true
			}
			.focusable(),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Text(text = title, fontSize = 26.sp, color = Tokens.Color.colorWhite, modifier = Modifier.weight(1f))
			Text(text = position, fontSize = 16.sp, color = Tokens.Color.colorGrey300)
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(
				text = if (entry.isOwn) stringResource(R.string.je_reviews_you) else entry.author,
				fontSize = 20.sp,
				color = Tokens.Color.colorWhite,
			)
			ReviewRating(entry, 22.dp)
			val meta = listOfNotNull(
				stringResource(R.string.je_reviews_source_tmdb).takeIf { entry.source == ReviewEntry.Source.TMDB },
				reviewDate(entry.date),
			).joinToString(" · ")
			if (meta.isNotEmpty()) Text(text = meta, fontSize = 16.sp, color = Tokens.Color.colorGrey300)
		}
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
		) {
			Text(
				text = entry.content,
				fontSize = 22.sp,
				lineHeight = 34.sp,
				color = Tokens.Color.colorGrey100,
				modifier = Modifier
					.widthIn(max = 1100.dp)
					.verticalScroll(scroll),
			)
		}
		Text(text = stringResource(R.string.je_reviews_reader_hint), fontSize = 14.sp, color = Tokens.Color.colorGrey500)
	}
}

private const val SCROLL_STEP = 320f

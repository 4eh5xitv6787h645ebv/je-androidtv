package org.jellyfin.androidtv.enhanced.catchup

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.calendar.PageChip
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedScreen
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * The Catch Up deck: random unwatched library movies and shows, one card at a time, with explicit buttons instead of
 * the web's swipes. Watched, Watchlist and Dismiss move on to the next card (focus stays on the button row); Undo
 * takes the last one back. Dismissals are kept on this device per server and user; they are not Hidden Content.
 */
class CatchUpFragment : Fragment() {
	private val catchUpRepository by inject<CatchUpRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val playbackHelper by inject<PlaybackHelper>()

	private enum class DeckButton(@DrawableRes val icon: Int, @StringRes val label: Int) {
		WATCH(R.drawable.je_ic_catchup_play_arrow, R.string.je_catchup_watch),
		DETAILS(R.drawable.je_ic_about_info, R.string.je_catchup_details),
		WATCHED(R.drawable.je_ic_catchup_check, R.string.je_catchup_watched),
		WATCHLIST(R.drawable.je_ic_catchup_bookmark_add, R.string.je_catchup_watchlist),
		DISMISS(R.drawable.je_ic_catchup_close, R.string.je_catchup_dismiss),
		UNDO(R.drawable.je_ic_catchup_undo, R.string.je_catchup_undo),
	}

	private var session: CatchUpSession? = null
	private val deck = MutableStateFlow(CatchUpDeck())
	private val filter = MutableStateFlow(CatchUpFilter.ALL)
	private val fetching = MutableStateFlow(false)

	/** Why the last fetch failed; only shown when there's no card to show. */
	private val fetchError = MutableStateFlow<LoadState.Error?>(null)
	private val picker = MutableStateFlow<SeasonPicker?>(null)
	private var fetchJob: Job? = null

	/** An action is running; further presses are ignored so one press can't act on the next card. */
	private var busy = false

	/** The title opened from the deck; re-checked on return in case it was watched meanwhile. */
	private var openedItemId: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val session = catchUpRepository.open() ?: return
		this.session = session
		deck.value = CatchUpDeck(dismissed = session.loadDismissed())
		refill()
	}

	override fun onResume() {
		super.onResume()
		openedItemId?.let(::recheck)
		openedItemId = null
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val deckState by deck.collectAsState()
		val currentFilter by filter.collectAsState()
		val isFetching by fetching.collectAsState()
		val error by fetchError.collectAsState()
		val openPicker by picker.collectAsState()

		EnhancedScreen(
			title = stringResource(R.string.je_catchup_title),
			headerActions = {
				FilterChip(R.string.je_catchup_filter_all, CatchUpFilter.ALL, currentFilter)
				FilterChip(R.string.je_catchup_filter_movies, CatchUpFilter.MOVIES, currentFilter)
				FilterChip(R.string.je_catchup_filter_shows, CatchUpFilter.SHOWS, currentFilter)
				if (deckState.dismissed.isNotEmpty()) {
					PageChip(
						label = stringResource(R.string.je_catchup_reset_dismissed, deckState.dismissed.size),
						selected = false,
						onClick = ::confirmClearDismissed,
					)
				}
			},
		) {
			val current = deckState.current
			val failed = error
			when {
				session == null -> LoadStateContent(
					state = LoadState.Ready(Unit),
					empty = { true },
					emptyMessage = stringResource(R.string.je_catchup_no_session),
				) { }

				current != null -> Deck(current, deckState.canUndo)
				isFetching -> LoadStateContent(LoadState.Loading) { }
				failed != null -> LoadStateContent(failed) { }
				else -> Empty(deckState, currentFilter)
			}
		}

		DialogBase(visible = openPicker != null, onDismissRequest = { picker.value = null }) {
			openPicker?.let { shown ->
				SeasonPickerPanel(
					picker = shown,
					onToggle = { index -> picker.update { it?.copy(selected = SeasonSelection.toggle(it.selected, index)) } },
					onAll = {
						picker.value = null
						perform(shown.series, SeasonSelection.wholeShow(shown.series))
					},
					onConfirm = {
						picker.value = null
						SeasonSelection.action(shown.series, shown.seasons, shown.selected)?.let { perform(shown.series, it) }
					},
					onCancel = { picker.value = null },
				)
			}
		}
	}

	@Composable
	private fun FilterChip(@StringRes label: Int, value: CatchUpFilter, current: CatchUpFilter) {
		PageChip(label = stringResource(label), selected = value == current, onClick = { setFilter(value) })
	}

	@Composable
	private fun Deck(item: CatchUpItem, canUndo: Boolean) {
		val requesters = remember { DeckButton.entries.associateWith { FocusRequester() } }
		// The button that last had focus; it gets it back after the deck moves on or the page is shown again
		var focused by rememberSaveable { mutableIntStateOf(DeckButton.WATCH.ordinal) }

		CatchUpCard(
			item = item,
			posterUrl = session?.posterUrl(item),
			backdropUrl = session?.backdropUrl(item),
		) {
			DeckButton.entries.forEach { button ->
				CatchUpActionButton(
					icon = button.icon,
					label = stringResource(button.label),
					onClick = { onButton(button) },
					dimmed = button == DeckButton.UNDO && !canUndo,
					modifier = Modifier
						.focusRequester(requesters.getValue(button))
						.onFocusChanged { if (it.hasFocus) focused = button.ordinal },
				)
			}
		}

		// Compose may not have attached the buttons on the first frame, and the toolbar grabs focus otherwise
		LaunchedEffect(Unit) {
			repeat(10) {
				withFrameNanos { }
				if (runCatching { requesters.getValue(DeckButton.entries[focused]).requestFocus() }.isSuccess) return@LaunchedEffect
			}
		}
	}

	@Composable
	private fun Empty(deckState: CatchUpDeck, currentFilter: CatchUpFilter) {
		val firstFocus = remember { FocusRequester() }
		CatchUpEmpty(
			message = stringResource(if (currentFilter == CatchUpFilter.ALL) R.string.je_catchup_empty else R.string.je_catchup_empty_filtered),
		) {
			CatchUpActionButton(
				icon = R.drawable.je_ic_catchup_style,
				label = stringResource(R.string.je_catchup_start_over),
				onClick = ::reshuffle,
				modifier = Modifier.focusRequester(firstFocus),
			)
			if (deckState.canUndo) {
				CatchUpActionButton(icon = R.drawable.je_ic_catchup_undo, label = stringResource(R.string.je_catchup_undo), onClick = ::undo)
			}
		}
		LaunchedEffect(Unit) {
			repeat(10) {
				withFrameNanos { }
				if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
			}
		}
	}

	private fun onButton(button: DeckButton) {
		if (button == DeckButton.UNDO) return undo()
		val item = deck.value.current ?: return
		when (button) {
			DeckButton.WATCH -> watch(item)
			DeckButton.DETAILS -> openDetails(item)
			DeckButton.WATCHED -> if (item.isSeries) pickSeasons(item) else perform(item, CatchUpAction.Watched(listOf(item.id), seasonNumbers = null))
			DeckButton.WATCHLIST -> perform(item, CatchUpAction.Watchlist(likedId = item.id, alreadyLiked = item.userData?.likes == true))
			DeckButton.DISMISS -> perform(item, CatchUpAction.Dismiss)
			DeckButton.UNDO -> Unit
		}
	}

	/** Movies play straight away (resuming where they were left); shows open their details to pick an episode. */
	private fun watch(item: CatchUpItem) {
		if (item.isSeries) return openDetails(item)
		val context = context ?: return
		val itemId = item.id.toUUIDOrNull() ?: return
		session?.log(CatchUpEvents.swipe(CatchUpEvents.OPEN, item.id, ok = true))
		openedItemId = item.id
		playbackHelper.retrieveAndPlay(itemId, false, context)
	}

	private fun openDetails(item: CatchUpItem) {
		val itemId = item.id.toUUIDOrNull() ?: return
		session?.log(CatchUpEvents.swipe(CatchUpEvents.OPEN, item.id, ok = true))
		openedItemId = item.id
		navigationRepository.navigate(Destinations.itemDetails(itemId))
	}

	/** Asks which seasons were watched; shows with fewer than two seasons are marked whole, as on the web. */
	private fun pickSeasons(series: CatchUpItem) {
		val session = session ?: return
		if (busy) return
		busy = true
		lifecycleScope.launch {
			val seasons = attempt { SeasonSelection.pickable(session.seasons(series.id)) }
			busy = false
			seasons.onSuccess { list ->
				if (list.size < 2) perform(series, SeasonSelection.wholeShow(series))
				else picker.value = SeasonPicker(series, list)
			}.onFailure { err ->
				Timber.w(err, "Couldn't load seasons for Catch Up")
				toast(getString(R.string.je_catchup_failed, series.name.orEmpty(), errorText(err)))
			}
		}
	}

	/** Runs [action] on the server (if it has a server side), then moves the deck on and logs it. */
	private fun perform(item: CatchUpItem, action: CatchUpAction) {
		val session = session ?: return
		if (busy) return
		busy = true
		lifecycleScope.launch {
			val result = attempt {
				when (action) {
					is CatchUpAction.Watched -> session.markWatched(item, action)
					is CatchUpAction.Watchlist -> action.also { session.addToWatchlist(it) }
					CatchUpAction.Dismiss -> action
				}
			}
			busy = false
			result.onSuccess { applied ->
				deck.update { it.after(item, applied) }
				if (applied is CatchUpAction.Dismiss) session.saveDismissed(deck.value.dismissed)
				session.log(CatchUpEvents.forAction(CatchUpDone(item, applied), ok = true))
				val name = item.name.orEmpty()
				toast(
					when (applied) {
						is CatchUpAction.Watched -> getString(R.string.je_catchup_done_watched, name)
						is CatchUpAction.Watchlist -> getString(R.string.je_catchup_done_watchlist, name)
						CatchUpAction.Dismiss -> getString(R.string.je_catchup_done_dismiss, name)
					}
				)
				refill()
			}.onFailure { err ->
				Timber.w(err, "Catch Up %s failed", action.logName)
				session.log(CatchUpEvents.forAction(CatchUpDone(item, action), ok = false))
				toast(getString(R.string.je_catchup_failed, item.name.orEmpty(), errorText(err)))
			}
		}
	}

	/** Reverts the last action on the server first; the card only comes back once that worked. */
	private fun undo() {
		val session = session ?: return
		if (busy) return
		val last = deck.value.history.lastOrNull() ?: return
		busy = true
		lifecycleScope.launch {
			val result = attempt { session.revert(last.action) }
			busy = false
			session.log(
				CatchUpEvents.swipe(CatchUpEvents.UNDO, last.item.id, ok = result.isSuccess, undoOf = last.action.logName)
			)
			result.onSuccess {
				deck.update { it.undo()?.first ?: it }
				if (last.action is CatchUpAction.Dismiss) session.saveDismissed(deck.value.dismissed)
				toast(getString(R.string.je_catchup_undone, last.item.name.orEmpty()))
			}.onFailure { err ->
				Timber.w(err, "Catch Up undo failed")
				toast(getString(R.string.je_catchup_undo_failed, errorText(err)))
			}
		}
	}

	/** Deals more cards while the deck is low, until the server has nothing new. */
	private fun refill() {
		val session = session ?: return
		if (fetchJob?.isActive == true || !deck.value.needsMore) return
		val requested = filter.value
		fetchError.value = null
		fetching.value = true
		fetchJob = lifecycleScope.launch {
			try {
				while (deck.value.needsMore) {
					val items = session.batch(requested)
					deck.update { it.withBatch(items) }
				}
				fetching.value = false
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Couldn't load Catch Up titles")
				fetchError.value = LoadState.Error(err.serverMessage ?: err.message) { refill() }
				fetching.value = false
			}
		}
	}

	private fun setFilter(value: CatchUpFilter) {
		if (filter.value == value) return
		filter.value = value
		fetchJob?.cancel()
		fetching.value = false
		deck.update { it.restart(value) }
		refill()
	}

	private fun reshuffle() {
		fetchJob?.cancel()
		fetching.value = false
		deck.update { it.reshuffle() }
		refill()
	}

	private fun confirmClearDismissed() {
		val context = context ?: return
		val count = deck.value.dismissed.size
		AlertDialog.Builder(context)
			.setTitle(R.string.je_catchup_reset_dismissed_title)
			.setMessage(getString(R.string.je_catchup_reset_dismissed_message, count))
			.setPositiveButton(R.string.je_catchup_reset_dismissed_confirm) { _, _ ->
				deck.update { it.clearDismissed() }
				session?.saveDismissed(emptySet())
				refill()
			}
			.setNegativeButton(R.string.je_catchup_cancel, null)
			.show()
	}

	/** After returning from a title: drop it if it was watched (or is gone), refresh it otherwise. */
	private fun recheck(itemId: String) {
		val session = session ?: return
		lifecycleScope.launch {
			attempt { session.item(itemId) }.onSuccess { fresh ->
				deck.update { current ->
					when {
						fresh == null || fresh.userData?.played == true -> current.drop(itemId)
						else -> current.copy(cards = current.cards.map { if (it.key == fresh.key) fresh else it })
					}
				}
				refill()
			}.onFailure { Timber.d(it, "Couldn't re-check a Catch Up title") }
		}
	}

	private fun errorText(err: Throwable): String =
		(err as? EnhancedApi.EnhancedApiException)?.serverMessage?.takeIf { it.isNotBlank() }
			?: getString(R.string.je_error_generic)

	private fun toast(text: String) {
		val context = context ?: return
		Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
	}
}

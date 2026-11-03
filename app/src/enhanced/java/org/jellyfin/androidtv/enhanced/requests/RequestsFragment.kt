package org.jellyfin.androidtv.enhanced.requests

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedScreen
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardMenu
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrStatusBadge
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The Requests page, the TV counterpart of JE's Requests/Downloads page: Seerr requests (with approve/decline for
 * managers), the Seerr watchlist, the *arr download queue and history, and Seerr issues, as tabs under the header.
 */
class RequestsFragment : Fragment() {
	companion object {
		private const val PAGE_SIZE = 20
		private const val ISSUE_PAGE_SIZE = 20
		private const val HISTORY_PAGE_SIZE = 30
	}

	private val requestsRepository by inject<RequestsRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	private val tab = MutableStateFlow(RequestsTab.REQUESTS)

	// Requests
	private val requestFilter = MutableStateFlow(RequestFilter.ALL)
	private val mineOnly = MutableStateFlow<Boolean?>(null)
	private val canApprove = MutableStateFlow(false)
	private val busyRequests = MutableStateFlow<Set<Int>>(emptySet())
	private val requests = PagedLoader(lifecycleScope, ::describe, key = { card: RequestsRepository.RequestCard -> card.request.requestId ?: card.request.hashCode() }) { page ->
		val result = requestsRepository.requests(requestFilter.value, mineOnly.value == true, page, PAGE_SIZE)
		canApprove.value = result.canApproveRequests
		PagedLoader.Page(result.cards, result.totalPages)
	}

	// Watchlist
	private val syncing = MutableStateFlow(false)
	private val watchlist = PagedLoader(lifecycleScope, ::describe, key = { result: SeerrResult -> result.dedupeKey }) { page ->
		val result = requestsRepository.watchlist(page)
		PagedLoader.Page(result.results, result.totalPages)
	}

	// Queue
	private val queue = MutableStateFlow<LoadState<RequestsRepository.QueueResult>>(LoadState.Loading)
	private val queueRefreshError = MutableStateFlow<String?>(null)
	private var queueJob: Job? = null

	// History
	private val historyVisible = MutableStateFlow(true)
	private val history = PagedLoader(lifecycleScope, ::describe, key = { item: HistoryItem -> item }) { page ->
		val result = requestsRepository.history(page, HISTORY_PAGE_SIZE)
		historyVisible.value = result.visible
		PagedLoader.Page(result.items, result.totalPages)
	}

	// Issues
	private val issueFilter = MutableStateFlow(IssueFilter.ALL)
	private val issueTitles = HashMap<String, SeerrResult?>()
	private val issues = PagedLoader(lifecycleScope, ::describe, key = { card: RequestsRepository.IssueCard -> card.issue.issueId }) { page ->
		val result = requestsRepository.issues(issueFilter.value, page, ISSUE_PAGE_SIZE, issueTitles)
		PagedLoader.Page(result.cards, result.totalPages)
	}

	private var shownBefore = false

	override fun onStart() {
		super.onStart()
		// Back from a details screen: Spoiler Guard may have changed there, so re-resolve library posters
		if (shownBefore) refreshArtwork()
		shownBefore = true
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val state by enhancedRepository.state.collectAsState()
		val tabs = remember(state) { RequestsTab.available(state) }
		val currentTab by tab.collectAsState()
		val firstFocus = remember { FocusRequester() }

		LaunchedEffect(Unit) {
			val loaded = enhancedRepository.ensureLoaded()
			if (mineOnly.value == null) mineOnly.value = loaded.config.downloadsFilterByUserRequests
			open(tab.value)
			runCatching { firstFocus.requestFocus() }
		}

		EnhancedScreen(
			title = stringResource(R.string.je_requests_title),
			headerActions = {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
					tabs.forEachIndexed { index, value ->
						ChoiceButton(
							text = stringResource(value.label()),
							selected = value == currentTab,
							onClick = { open(value) },
							modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
						)
					}
				}
			},
		) {
			if (!state.requests) {
				PageMessage(stringResource(if (state.available) R.string.je_seerr_unavailable else R.string.je_loading))
				return@EnhancedScreen
			}
			when (currentTab) {
				RequestsTab.REQUESTS -> RequestsTabContent()
				RequestsTab.WATCHLIST -> WatchlistTabContent(RequestsTab.canSyncWatchlist(state))
				RequestsTab.QUEUE -> QueueTabContent(RequestsTab.pollIntervalSeconds(state))
				RequestsTab.HISTORY -> HistoryTabContent()
				RequestsTab.ISSUES -> IssuesTabContent()
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// Poll the queue only while it is on screen and the page is in the foreground
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				tab.collectLatest { current ->
					val interval = RequestsTab.pollIntervalSeconds(enhancedRepository.current)
					if (current != RequestsTab.QUEUE || interval == null) return@collectLatest
					while (true) {
						delay(interval * 1000L)
						refreshQueue(silent = true)
					}
				}
			}
		}
	}

	private fun RequestsTab.label() = when (this) {
		RequestsTab.REQUESTS -> R.string.je_requests_tab_requests
		RequestsTab.WATCHLIST -> R.string.je_requests_tab_watchlist
		RequestsTab.QUEUE -> R.string.je_requests_tab_queue
		RequestsTab.HISTORY -> R.string.je_requests_tab_history
		RequestsTab.ISSUES -> R.string.je_requests_tab_issues
	}

	private fun open(value: RequestsTab) {
		tab.value = value
		when (value) {
			RequestsTab.REQUESTS -> requests.ensureStarted()
			RequestsTab.WATCHLIST -> watchlist.ensureStarted()
			// The queue changes all the time: refresh it whenever it is opened
			RequestsTab.QUEUE -> refreshQueue(silent = queue.value is LoadState.Ready)
			RequestsTab.HISTORY -> history.ensureStarted()
			RequestsTab.ISSUES -> issues.ensureStarted()
		}
	}

	/** The server's message when it sent one, otherwise a generic one with the HTTP status. */
	private fun describe(err: Throwable): String =
		RequestsRepository.serverMessage(err)
			?: (err as? EnhancedApi.EnhancedApiException)?.let { getString(R.string.je_requests_load_failed, it.status) }
			?: getString(R.string.je_error_generic)

	private fun toast(text: String) {
		val context = context ?: return
		Toast.makeText(context, text, Toast.LENGTH_LONG).show()
	}

	private fun openDetails(mediaType: String?, tmdbId: Int?) {
		if (tmdbId == null || tmdbId <= 0) return
		val type = if (mediaType == SeerrRepository.MEDIA_TV) SeerrRepository.MEDIA_TV else SeerrRepository.MEDIA_MOVIE
		navigationRepository.navigate(EnhancedDestinations.seerrDetails(type, tmdbId))
	}

	private fun refreshArtwork() {
		lifecycleScope.launch {
			try {
				val cards = requests.state.value.items
				if (cards.any { it.request.jellyfinMediaId != null }) {
					val refreshed = requestsRepository.refreshArtwork(cards).associateBy { it.request }
					requests.update { list -> list.map { refreshed[it.request] ?: it } }
				}
				val results = watchlist.state.value.items
				if (results.any { it.mediaInfo?.jellyfinMediaId != null || it.mediaInfo?.jellyfinMediaId4k != null }) {
					val refreshed = requestsRepository.refreshWatchlistArtwork(results).associateBy { it.dedupeKey }
					watchlist.update { list -> list.map { refreshed[it.dedupeKey] ?: it } }
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to refresh library artwork")
			}
		}
	}

	// Requests

	@Composable
	private fun RequestsTabContent() {
		val state by requests.state.collectAsState()
		val filter by requestFilter.collectAsState()
		val mine by mineOnly.collectAsState()
		val approve by canApprove.collectAsState()
		val busy by busyRequests.collectAsState()
		val today = remember { RequestsRepository.today() }

		PagedGrid(
			state = state,
			loader = requests,
			columns = 2,
			emptyMessage = stringResource(R.string.je_nothing_found),
			header = {
				Row(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.focusGroup()
						.padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
				) {
					RequestFilter.entries.forEach { value ->
						ChoiceButton(
							text = stringResource(value.label()),
							selected = value == filter,
							onClick = {
								if (requestFilter.value != value) {
									requestFilter.value = value
									requests.reload()
								}
							},
						)
					}
					ChoiceButton(
						text = stringResource(if (mine == true) R.string.je_requests_mine_only_on else R.string.je_requests_mine_only),
						selected = mine == true,
						onClick = {
							mineOnly.value = mineOnly.value != true
							requests.reload()
						},
					)
				}
			},
		) { card ->
			RequestRow(card, today, approve, card.request.requestId?.let { it in busy } == true)
		}
	}

	private fun RequestFilter.label() = when (this) {
		RequestFilter.ALL -> R.string.je_requests_filter_all
		RequestFilter.PENDING -> R.string.je_requests_filter_pending
		RequestFilter.APPROVED -> R.string.je_requests_filter_approved
		RequestFilter.PROCESSING -> R.string.je_requests_filter_processing
		RequestFilter.COMING_SOON -> R.string.je_requests_filter_coming_soon
		RequestFilter.AVAILABLE -> R.string.je_requests_filter_available
	}

	@Composable
	private fun RequestRow(card: RequestsRepository.RequestCard, today: LocalDate, canApprove: Boolean, busy: Boolean) {
		val request = card.request
		val actionable = canApprove && request.isPendingApproval
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
			PosterCard(
				posterUrl = card.posterUrl,
				placeholder = if (request.isTv) R.drawable.ic_tv else R.drawable.ic_clapperboard,
				onClick = { openDetails(request.type, request.tmdbId) },
				onMenu = { showRequestMenu(card, actionable) },
				modifier = Modifier.weight(1f),
			) {
				CardTitle(
					text = request.title ?: stringResource(R.string.je_requests_issue_unknown_title),
					trailing = request.year?.takeIf { !card.protectedTitle }?.toString(),
				)
				val chip = RequestChip.of(request, today)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
					StatusChip(stringResource(chip.label()), chip.color())
					// Release and air dates come from TMDB, outside Spoiler Guard: withheld for protected library titles
					if (!card.protectedTitle) releaseLabel(request, today)?.let { CardLine(releaseText(it)) }
				}
				if (chip == RequestChip.ORPHANED) CardLine(stringResource(R.string.je_requests_orphaned_hint), ColorAmber)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
					requestsRepository.avatarUrl(request.requestedByAvatar)?.let { url ->
						Box(modifier = Modifier.size(18.dp).clip(CircleShape)) {
							AsyncImage(url = url, aspectRatio = 1f, scaleType = ImageView.ScaleType.CENTER_CROP, modifier = Modifier.fillMaxSize())
						}
					}
					val by = request.requestedBy ?: stringResource(R.string.je_requests_chip_unknown)
					val age = relativeAge(request.createdAt)
					CardLine(if (age != null) stringResource(R.string.je_requests_requested_by, by, age) else by)
				}
			}
			if (actionable) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					IconButton(onClick = { approve(card) }, enabled = !busy) {
						Icon(painterResource(R.drawable.je_ic_requests_check), contentDescription = stringResource(R.string.je_requests_approve))
					}
					IconButton(onClick = { confirmDecline(card) }, enabled = !busy) {
						Icon(painterResource(R.drawable.je_ic_requests_close), contentDescription = stringResource(R.string.je_requests_decline))
					}
				}
			}
		}
	}

	private fun RequestChip.label() = when (this) {
		RequestChip.ORPHANED -> R.string.je_requests_chip_orphaned
		RequestChip.COMING_SOON -> R.string.je_requests_chip_coming_soon
		RequestChip.AVAILABLE -> R.string.je_requests_chip_available
		RequestChip.PARTIALLY_AVAILABLE -> R.string.je_requests_chip_partial
		RequestChip.PROCESSING -> R.string.je_requests_chip_processing
		RequestChip.REQUESTED -> R.string.je_requests_chip_requested
		RequestChip.PENDING -> R.string.je_requests_chip_pending
		RequestChip.DECLINED -> R.string.je_requests_chip_declined
		RequestChip.BLOCKLISTED -> R.string.je_requests_chip_blocklisted
		RequestChip.DELETED -> R.string.je_requests_chip_deleted
		RequestChip.FAILED -> R.string.je_requests_chip_failed
		RequestChip.COMPLETED -> R.string.je_requests_chip_completed
		RequestChip.UNKNOWN -> R.string.je_requests_chip_unknown
	}

	private fun RequestChip.color() = when (this) {
		RequestChip.AVAILABLE, RequestChip.COMPLETED, RequestChip.REQUESTED -> ColorGreen
		RequestChip.PARTIALLY_AVAILABLE -> ColorBlue
		RequestChip.PROCESSING, RequestChip.COMING_SOON -> ColorIndigo
		RequestChip.PENDING, RequestChip.ORPHANED -> ColorAmber
		RequestChip.DECLINED, RequestChip.FAILED, RequestChip.BLOCKLISTED -> ColorRed
		RequestChip.DELETED, RequestChip.UNKNOWN -> ColorGrey
	}

	private fun releaseText(label: ReleaseLabel): String {
		val whenText = when {
			label.daysAway == 0L -> getString(R.string.je_requests_release_today)
			label.daysAway == 1L -> getString(R.string.je_requests_release_tomorrow)
			label.daysAway <= 14 -> getString(R.string.je_requests_release_in_days, label.daysAway.toInt())
			else -> getString(R.string.je_requests_release_on, label.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
		}
		return getString(
			when (label.kind) {
				ReleaseKind.NEXT_EPISODE -> R.string.je_requests_next_episode
				ReleaseKind.DIGITAL -> R.string.je_requests_digital_release
				ReleaseKind.THEATRICAL -> R.string.je_requests_theatrical_release
			},
			whenText,
		)
	}

	/** `5m ago`, `3h ago`, `2d ago`, or the date after a month, like the web. */
	private fun relativeAge(timestamp: String?): String? {
		val instant = timestamp?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
		val age = Duration.between(instant, Instant.now())
		if (age.isNegative) return null
		return when {
			age.toMinutes() < 1 -> getString(R.string.je_requests_just_now)
			age.toHours() < 1 -> getString(R.string.je_requests_minutes_ago, age.toMinutes().toInt())
			age.toDays() < 1 -> getString(R.string.je_requests_hours_ago, age.toHours().toInt())
			age.toDays() < 30 -> getString(R.string.je_requests_days_ago, age.toDays().toInt())
			else -> instant.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
		}
	}

	private fun showRequestMenu(card: RequestsRepository.RequestCard, actionable: Boolean) {
		val context = context ?: return
		val request = card.request
		val actions = buildList<Pair<String, () -> Unit>> {
			if (request.tmdbId != null) add(getString(R.string.je_requests_open_details) to { openDetails(request.type, request.tmdbId) })
			if (actionable) {
				add(getString(R.string.je_requests_approve) to { approve(card) })
				add(getString(R.string.je_requests_decline) to { confirmDecline(card) })
			}
		}
		if (actions.isEmpty()) return
		AlertDialog.Builder(context)
			.setTitle(request.title)
			.setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun confirmDecline(card: RequestsRepository.RequestCard) {
		val context = context ?: return
		val title = card.request.title ?: getString(R.string.je_requests_issue_unknown_title)
		AlertDialog.Builder(context)
			.setTitle(getString(R.string.je_requests_decline_title, title))
			.setMessage(getString(R.string.je_requests_decline_message, card.request.requestedBy ?: getString(R.string.je_requests_chip_unknown)))
			.setPositiveButton(R.string.je_requests_decline) { _, _ -> act(card, approve = false) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun approve(card: RequestsRepository.RequestCard) = act(card, approve = true)

	private fun act(card: RequestsRepository.RequestCard, approve: Boolean) {
		val requestId = card.request.requestId ?: return
		if (requestId in busyRequests.value) return
		busyRequests.value += requestId
		val title = card.request.title.orEmpty()
		lifecycleScope.launch {
			try {
				if (approve) requestsRepository.approve(requestId) else requestsRepository.decline(requestId)
				toast(getString(if (approve) R.string.je_requests_approved_toast else R.string.je_requests_declined_toast, title))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Request %d action failed", requestId)
				toast(getString(R.string.je_requests_action_failed, describe(err)))
			} finally {
				busyRequests.value -= requestId
			}
			// Never retry: reload to show what the server now has, and the queue an approval may have started
			requests.reload()
			if (queue.value !is LoadState.Loading) refreshQueue(silent = true)
		}
	}

	// Watchlist

	@Composable
	private fun WatchlistTabContent(canSync: Boolean) {
		val state by watchlist.state.collectAsState()
		val isSyncing by syncing.collectAsState()
		val view = LocalView.current

		PagedGrid(
			state = state,
			loader = watchlist,
			columns = 3,
			emptyMessage = stringResource(R.string.je_requests_watchlist_empty),
			header = if (canSync) {
				{
					Row(
						horizontalArrangement = Arrangement.spacedBy(12.dp),
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
					) {
						Button(onClick = { syncWatchlist() }, enabled = !isSyncing) {
							Icon(painterResource(R.drawable.je_ic_requests_sync), contentDescription = null, modifier = Modifier.size(18.dp))
							Text(stringResource(if (isSyncing) R.string.je_requests_syncing else R.string.je_requests_sync_now), modifier = Modifier.padding(start = 6.dp))
						}
						Text(stringResource(R.string.je_requests_watchlist_hint), color = Tokens.Color.colorGrey300, fontSize = 13.sp)
					}
				}
			} else null,
		) { result ->
			PosterCard(
				posterUrl = if (result.libraryArtwork) result.libraryPosterUrl else SeerrImages.poster(result.posterPath),
				placeholder = if (result.isTv) R.drawable.ic_tv else R.drawable.ic_clapperboard,
				onClick = { openDetails(result.mediaType, result.id) },
				onMenu = {
					context?.let { context ->
						SeerrCardMenu.show(context, view, result, enhancedRepository.current) {
							watchlist.update { list -> list.filterNot { it.dedupeKey == result.dedupeKey } }
						}
					}
				},
			) {
				CardTitle(result.displayTitle, result.year.takeIf { !result.libraryArtwork })
				SeerrStatusBadge(result.status)
			}
		}
	}

	private fun syncWatchlist() {
		if (syncing.value) return
		syncing.value = true
		lifecycleScope.launch {
			try {
				val result = requestsRepository.syncWatchlist()
				val failedUsers = result.errors?.size ?: 0
				toast(
					if (failedUsers > 0) getString(R.string.je_requests_sync_result_errors, result.itemsProcessed, result.itemsAdded, failedUsers)
					else getString(R.string.je_requests_sync_result, result.itemsProcessed, result.itemsAdded)
				)
				watchlist.reload()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Watchlist sync failed")
				toast(getString(R.string.je_requests_sync_failed, describe(err)))
			} finally {
				syncing.value = false
			}
		}
	}

	// Queue

	private fun refreshQueue(silent: Boolean) {
		if (queueJob?.isActive == true) return
		if (!silent) queue.value = LoadState.Loading
		queueJob = lifecycleScope.launch {
			try {
				queue.value = LoadState.Ready(requestsRepository.queue())
				queueRefreshError.value = null
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Queue refresh failed")
				// Keep the rows we have; only a queue that never loaded turns into an error page
				if (queue.value is LoadState.Ready) queueRefreshError.value = describe(err)
				else queue.value = LoadState.Error(describe(err)) { refreshQueue(silent = false) }
			}
		}
	}

	@Composable
	private fun QueueTabContent(pollSeconds: Int?) {
		val state by queue.collectAsState()
		val refreshError by queueRefreshError.collectAsState()

		Column(modifier = Modifier.fillMaxSize()) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
			) {
				Button(onClick = { refreshQueue(silent = true) }) { Text(stringResource(R.string.je_requests_refresh)) }
				if (pollSeconds != null) Text(stringResource(R.string.je_requests_auto_refresh, pollSeconds), color = Tokens.Color.colorGrey300, fontSize = 13.sp)
				refreshError?.let { Text(it, color = ColorAmber, fontSize = 13.sp) }
			}
			LoadStateContent(state) { result ->
				LazyColumn(
					contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 32.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					// One instance failing doesn't hide what the others returned
					items(result.errors) { error ->
						Text(
							text = stringResource(
								R.string.je_requests_queue_error,
								error.source ?: "Arr",
								error.instanceName ?: "?",
								error.reason ?: "",
							),
							color = ColorAmber,
							fontSize = 14.sp,
						)
					}
					if (result.entries.isEmpty()) {
						item { Text(stringResource(R.string.je_requests_queue_empty), color = Tokens.Color.colorGrey300, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp)) }
					}
					// No item keys: *arr ids aren't guaranteed unique across instances, and the list is replaced wholesale
					items(result.entries) { entry -> QueueRow(entry) }
				}
			}
		}
	}

	@Composable
	private fun QueueRow(entry: QueueEntry) {
		val item = entry.item
		PosterCard(
			posterUrl = item.posterUrl,
			placeholder = if (item.isSonarr) R.drawable.ic_tv else R.drawable.ic_clapperboard,
			onClick = { openDetails(if (item.isSonarr) SeerrRepository.MEDIA_TV else SeerrRepository.MEDIA_MOVIE, item.tmdbId) },
			modifier = Modifier.fillMaxWidth(),
		) {
			CardTitle(item.title ?: stringResource(R.string.je_requests_issue_unknown_title))
			val subtitle = when (entry) {
				is QueueEntry.SeasonPack -> listOfNotNull(
					stringResource(R.string.je_requests_season_pack, item.seasonNumber ?: 0, entry.episodes.size),
					entry.firstEpisode?.let { first ->
						entry.lastEpisode?.let { last -> stringResource(R.string.je_requests_episode_range, first, last) }
					},
				).joinToString(" · ")
				is QueueEntry.Single -> item.subtitle
			}
			subtitle?.takeIf { it.isNotBlank() }?.let { CardLine(it) }
			val (statusText, statusColor) = queueStatus(item.status)
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
				StatusChip(statusText, statusColor)
				CardLine(listOfNotNull(item.instanceName ?: item.source).joinToString())
			}
			ProgressBar(fraction = (item.progress / 100.0).toFloat(), color = statusColor)
			CardLine(
				listOfNotNull(
					stringResource(R.string.je_requests_progress, item.progress.toInt()),
					formatTimeRemaining(item.timeRemaining)?.let { stringResource(R.string.je_requests_eta, it) },
					formatDownloadStats(entry.totalSize, entry.sizeRemaining),
				).joinToString("  ·  ")
			)
		}
	}

	@Composable
	private fun queueStatus(status: String?): Pair<String, Color> = when (status?.lowercase()) {
		"downloading" -> stringResource(R.string.je_requests_status_downloading) to ColorBlue
		"queued" -> stringResource(R.string.je_requests_status_queued) to ColorGrey
		"paused" -> stringResource(R.string.je_requests_status_paused) to ColorAmber
		"importing" -> stringResource(R.string.je_requests_status_importing) to ColorGreen
		"completed" -> stringResource(R.string.je_requests_status_completed) to ColorGreen
		"warning" -> stringResource(R.string.je_requests_status_warning) to ColorAmber
		"failed", "downloadclientunavailable" -> stringResource(R.string.je_requests_status_failed) to ColorRed
		"delay", "delayed" -> stringResource(R.string.je_requests_status_delay) to ColorAmber
		null, "", "unknown" -> stringResource(R.string.je_requests_status_unknown) to ColorGrey
		else -> status to ColorGrey
	}

	// History

	@Composable
	private fun HistoryTabContent() {
		val state by history.state.collectAsState()
		val visible by historyVisible.collectAsState()

		// visible:false is the server hiding history from this user, not a failure or an empty history
		if (!visible) {
			PageMessage(stringResource(R.string.je_requests_history_hidden))
			return
		}

		PagedGrid(
			state = state,
			loader = history,
			columns = 2,
			emptyMessage = stringResource(R.string.je_requests_history_empty),
		) { item ->
			PosterCard(
				posterUrl = item.posterUrl,
				placeholder = if (item.isSonarr) R.drawable.ic_tv else R.drawable.ic_clapperboard,
				onClick = { openDetails(if (item.isSonarr) SeerrRepository.MEDIA_TV else SeerrRepository.MEDIA_MOVIE, item.tmdbId) },
			) {
				CardTitle(item.title ?: stringResource(R.string.je_requests_issue_unknown_title))
				item.subtitle?.takeIf { it.isNotBlank() }?.let { CardLine(it) }
				val (text, color) = when (item.outcome) {
					HistoryOutcome.IMPORTED -> R.string.je_requests_history_imported to ColorGreen
					HistoryOutcome.PARTIAL -> R.string.je_requests_history_partial to ColorBlue
					HistoryOutcome.FAILED -> R.string.je_requests_history_failed to ColorRed
				}
				StatusChip(stringResource(text), color)
				if (item.episodeCount != null && item.episodeCount > 0) {
					CardLine(stringResource(R.string.je_requests_history_episodes, item.importedCount ?: 0, item.episodeCount))
				}
				CardLine(listOfNotNull(item.instanceName ?: item.source, relativeAge(item.date)).joinToString(" · "))
			}
		}
	}

	// Issues

	@Composable
	private fun IssuesTabContent() {
		val state by issues.state.collectAsState()
		val filter by issueFilter.collectAsState()

		PagedGrid(
			state = state,
			loader = issues,
			columns = 2,
			emptyMessage = stringResource(R.string.je_requests_issues_empty),
			header = {
				Row(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier
						.focusGroup()
						.padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
				) {
					IssueFilter.entries.forEach { value ->
						ChoiceButton(
							text = stringResource(
								when (value) {
									IssueFilter.ALL -> R.string.je_requests_filter_all
									IssueFilter.OPEN -> R.string.je_requests_issue_open
									IssueFilter.RESOLVED -> R.string.je_requests_issue_resolved
								}
							),
							selected = value == filter,
							onClick = {
								if (issueFilter.value != value) {
									issueFilter.value = value
									issues.reload()
								}
							},
						)
					}
				}
			},
		) { card ->
			val issue = card.issue
			PosterCard(
				posterUrl = card.posterUrl,
				placeholder = if (card.mediaType == SeerrRepository.MEDIA_TV) R.drawable.ic_tv else R.drawable.ic_clapperboard,
				onClick = { openDetails(card.mediaType, issue.media?.tmdbId) },
			) {
				CardTitle(card.title ?: stringResource(R.string.je_requests_issue_unknown_title), card.year)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
					StatusChip(
						stringResource(if (issue.isResolved) R.string.je_requests_issue_resolved else R.string.je_requests_issue_open),
						if (issue.isResolved) ColorGreen else ColorAmber,
					)
					StatusChip(stringResource(issueTypeLabel(issue.issueType)), ColorGrey)
					issue.episodeLabel?.let { CardLine(it) }
				}
				issue.message?.let { CardLine(it, Tokens.Color.colorGrey100, maxLines = 2) }
				CardLine(
					listOfNotNull(
						issue.createdBy?.name,
						relativeAge(issue.createdAt),
						issue.comments.size.takeIf { it > 1 }?.let { stringResource(R.string.je_requests_issue_comments, it) },
					).joinToString(" · ")
				)
			}
		}
	}

	private fun issueTypeLabel(type: Int?) = when (type) {
		SeerrIssueType.VIDEO -> R.string.je_requests_issue_video
		SeerrIssueType.AUDIO -> R.string.je_requests_issue_audio
		SeerrIssueType.SUBTITLES -> R.string.je_requests_issue_subtitles
		else -> R.string.je_requests_issue_other
	}
}


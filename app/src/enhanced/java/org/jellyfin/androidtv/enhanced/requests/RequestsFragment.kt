package org.jellyfin.androidtv.enhanced.requests

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrErrors
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrLink
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkManager
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkRecovery
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.design.ChipRow
import org.jellyfin.androidtv.enhanced.ui.design.ChipSeparator
import org.jellyfin.androidtv.enhanced.ui.design.EmptyState
import org.jellyfin.androidtv.enhanced.ui.design.FilterChip
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeConfirmDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.JeStatus
import org.jellyfin.androidtv.enhanced.ui.design.JeStatuses
import org.jellyfin.androidtv.enhanced.ui.design.JeTabRow
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.ListRow
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.design.LoadingSkeleton
import org.jellyfin.androidtv.enhanced.ui.design.Notice
import org.jellyfin.androidtv.enhanced.ui.design.NoticeKind
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.enhanced.ui.design.RowAction
import org.jellyfin.androidtv.enhanced.ui.design.RowDensity
import org.jellyfin.androidtv.enhanced.ui.design.RowLeading
import org.jellyfin.androidtv.enhanced.ui.design.SkeletonShape
import org.jellyfin.androidtv.enhanced.ui.design.StatusBadgeSpec
import org.jellyfin.androidtv.enhanced.ui.design.TabSpec
import org.jellyfin.androidtv.enhanced.ui.design.WarningLine
import org.jellyfin.androidtv.enhanced.ui.hub.EmptyAction
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardMenu
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrLinkNotice
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrLinkTexts
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
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

		private const val STATE_TAB = "tab"
		private const val STATE_REQUEST_FILTER = "requestFilter"
		private const val STATE_MINE_ONLY = "mineOnly"
		private const val STATE_ISSUE_FILTER = "issueFilter"

		// Approve/decline and the watchlist sync outlive the page: leaving it mid-call must neither cancel the write
		// nor touch a fragment that is no longer attached (feedback goes through the application context)
		private val writeScope = MainScope()
	}

	private val requestsRepository by inject<RequestsRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val seerrLinkManager by inject<SeerrLinkManager>()
	private val navigationRepository by inject<NavigationRepository>()

	private val tab = MutableStateFlow(RequestsTab.REQUESTS)

	// Requests
	private val requestFilter = MutableStateFlow(RequestFilter.ALL)
	private val mineOnly = MutableStateFlow<Boolean?>(null)
	private val canApprove = MutableStateFlow(false)
	private val busyRequests = MutableStateFlow<Set<Int>>(emptySet())
	// The server's count per filter for the current "Only mine" setting, as far as the filters were opened
	private val requestCounts = MutableStateFlow<Map<RequestFilter, Int>>(emptyMap())
	private val requests = PagedLoader(lifecycleScope, ::describe, key = { card: RequestsRepository.RequestCard -> card.request.requestId ?: card.request.hashCode() }) { page ->
		val filter = requestFilter.value
		val mine = mineOnly.value == true
		val result = requestsRepository.requests(filter, mine, page, PAGE_SIZE)
		canApprove.value = result.canApproveRequests
		if (mine == (mineOnly.value == true)) requestCounts.value += filter to result.totalResults
		PagedLoader.Page(result.cards, result.totalPages)
	}

	// Watchlist
	private val syncing = MutableStateFlow(false)
	private val watchlistCount = MutableStateFlow<Int?>(null)
	private val watchlist = PagedLoader(lifecycleScope, ::describe, key = { result: SeerrResult -> result.dedupeKey }) { page ->
		val result = requestsRepository.watchlist(page)
		watchlistCount.value = result.totalResults
		PagedLoader.Page(result.results, result.totalPages)
	}

	// Queue
	private val queue = MutableStateFlow<LoadState<RequestsRepository.QueueResult>>(LoadState.Loading)
	private val queueRefreshError = MutableStateFlow<String?>(null)
	private var queueJob: Job? = null

	// History
	private val historyVisible = MutableStateFlow(true)
	// Lazy list keys must be Bundle-storable, so a string rather than the item itself
	private val history = PagedLoader(lifecycleScope, ::describe, key = { item: HistoryItem -> listOf(item.source, item.instanceName, item.title, item.subtitle, item.eventType, item.date).joinToString("|") }) { page ->
		val result = requestsRepository.history(page, HISTORY_PAGE_SIZE)
		historyVisible.value = result.visible
		PagedLoader.Page(result.items, result.totalPages)
	}

	// Issues
	private val issueFilter = MutableStateFlow(IssueFilter.ALL)
	private val issueTitles = HashMap<String, SeerrResult?>()
	private val issueCounts = MutableStateFlow<Map<IssueFilter, Int>>(emptyMap())
	private val issues = PagedLoader(lifecycleScope, ::describe, key = { card: RequestsRepository.IssueCard -> card.issue.issueId }) { page ->
		val filter = issueFilter.value
		val result = requestsRepository.issues(filter, page, ISSUE_PAGE_SIZE, issueTitles)
		issueCounts.value += filter to result.totalResults
		PagedLoader.Page(result.cards, result.totalPages)
	}

	// The card menu or the decline confirmation on screen, if any
	private val dialog = MutableStateFlow<RequestDialog?>(null)

	private var shownBefore = false
	// Set when the page comes back from another screen: the focused card is focused again instead of the first tab
	private var restoreCardFocus = false
	private val linkRecovery = SeerrLinkRecovery()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		savedInstanceState?.let { state ->
			state.getString(STATE_TAB)?.let { name -> RequestsTab.entries.firstOrNull { it.name == name } }?.let { tab.value = it }
			state.getString(STATE_REQUEST_FILTER)?.let { name -> RequestFilter.entries.firstOrNull { it.name == name } }?.let { requestFilter.value = it }
			if (state.containsKey(STATE_MINE_ONLY)) mineOnly.value = state.getBoolean(STATE_MINE_ONLY)
			state.getString(STATE_ISSUE_FILTER)?.let { name -> IssueFilter.entries.firstOrNull { it.name == name } }?.let { issueFilter.value = it }
		}
		// The link starting to work (by itself, or after an import or check from the notice): the page drops the
		// notice the moment the state says linked, so the change is watched here rather than in the notice
		lifecycleScope.launch {
			enhancedRepository.state.collect { state -> if (linkRecovery.recovered(SeerrLink.kind(state))) reloadAfterLink() }
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putString(STATE_TAB, tab.value.name)
		outState.putString(STATE_REQUEST_FILTER, requestFilter.value.name)
		mineOnly.value?.let { outState.putBoolean(STATE_MINE_ONLY, it) }
		outState.putString(STATE_ISSUE_FILTER, issueFilter.value.name)
	}

	override fun onStart() {
		super.onStart()
		// Back from a details screen: a request, watchlist change, issue, hide or Spoiler Guard change there may have
		// changed any tab. The current one refreshes now (keeping its rows and focus), the others when opened
		if (shownBefore) {
			listOf(requests, watchlist, history, issues).forEach { it.invalidate() }
			open(tab.value)
		}
		shownBefore = true
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val state by enhancedRepository.state.collectAsState()
		val tabs = remember(state) { RequestsTab.available(state) }
		val currentTab by tab.collectAsState()
		val firstFocus = remember { FocusRequester() }
		val restoring = remember {
			// The grid of the current tab puts the focus back on the card that had it (see PagedGrid)
			val restore = restoreCardFocus && pagedLoader(tab.value)?.requestFocusRestore() == true
			restoreCardFocus = false
			restore
		}

		LaunchedEffect(Unit) {
			val loaded = enhancedRepository.ensureLoaded()
			// A restored tab the server no longer offers falls back to the first one
			if (tab.value !in RequestsTab.available(loaded)) tab.value = RequestsTab.REQUESTS
			open(tab.value)
			if (!restoring) runCatching { firstFocus.requestFocus() }
		}

		val counts by requestCounts.collectAsState()
		val watchlistTotal by watchlistCount.collectAsState()
		val queueState by queue.collectAsState()
		val issueTotals by issueCounts.collectAsState()
		val approver by canApprove.collectAsState()
		val subtitle = counts[RequestFilter.ALL]?.let { total ->
			val all = pluralStringResource(R.plurals.je_requests_count, total, total)
			val pending = counts[RequestFilter.PENDING]?.takeIf { approver && it > 0 }
			if (pending == null) all
			else stringResource(R.string.je_requests_subtitle_joined, all, pluralStringResource(R.plurals.je_requests_waiting, pending, pending))
		}

		PageScaffold(
			title = stringResource(R.string.je_requests_title),
			subtitle = subtitle.takeIf { state.requests },
		) {
			if (!state.requests) {
				when {
					SeerrLink.blocksPage(state, state.config.downloadsPageEnabled) -> Box(modifier = Modifier.fillMaxSize()) {
						SeerrLinkNotice(modifier = Modifier.align(Alignment.Center))
					}

					state.available -> EmptyState(title = stringResource(R.string.je_seerr_unavailable))
					else -> LoadingSkeleton(SkeletonShape.Grid())
				}
				return@PageScaffold
			}
			Column(modifier = Modifier.fillMaxSize()) {
				JeTabRow(
					tabs = tabs.map { value ->
						TabSpec(
							label = stringResource(value.label()),
							count = when (value) {
								RequestsTab.REQUESTS -> counts[RequestFilter.ALL]
								RequestsTab.WATCHLIST -> watchlistTotal
								RequestsTab.QUEUE -> (queueState as? LoadState.Ready)?.value?.entries?.size
								RequestsTab.HISTORY -> null
								RequestsTab.ISSUES -> issueTotals[IssueFilter.ALL]
							},
						)
					},
					selected = tabs.indexOf(currentTab).coerceAtLeast(0),
					onSelect = { index -> tabs.getOrNull(index)?.let { open(it) } },
					modifier = Modifier
						.padding(horizontal = JeSpace.pageHorizontal)
						.focusRequester(firstFocus),
				)
				Box(modifier = Modifier.height(JeSpace.headerBottom))
				when (currentTab) {
					RequestsTab.REQUESTS -> RequestsTabContent()
					RequestsTab.WATCHLIST -> WatchlistTabContent(RequestsTab.canSyncWatchlist(state))
					RequestsTab.QUEUE -> QueueTabContent(RequestsTab.pollIntervalSeconds(state))
					RequestsTab.HISTORY -> HistoryTabContent()
					RequestsTab.ISSUES -> IssuesTabContent()
				}
			}
			RequestDialogs()
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		// The next view is the page coming back
		restoreCardFocus = true
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
						if (enhancedRepository.current.requests) refreshQueue(silent = true)
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
		// Nothing loads behind the link notice (or with the page off); the link starting to work opens the tab then
		val state = enhancedRepository.current
		if (!state.requests) return
		if (mineOnly.value == null) mineOnly.value = state.config.downloadsFilterByUserRequests
		when (value) {
			// The queue changes all the time: refresh it whenever it is opened
			RequestsTab.QUEUE -> refreshQueue(silent = queue.value is LoadState.Ready)
			// Load the first page, or refresh rows a write made stale
			else -> pagedLoader(value)?.show()
		}
	}

	private fun pagedLoader(value: RequestsTab): PagedLoader<*>? = when (value) {
		RequestsTab.REQUESTS -> requests
		RequestsTab.WATCHLIST -> watchlist
		RequestsTab.QUEUE -> null
		RequestsTab.HISTORY -> history
		RequestsTab.ISSUES -> issues
	}

	/**
	 * The server's message when it sent one (as a full sentence; "not linked" as the TV's own text), otherwise a
	 * generic one with the HTTP status. A call that says the account isn't in Seerr reloads a state that still says
	 * linked, so the page turns into the link notice.
	 */
	private fun describe(err: Throwable): String = describe(context?.applicationContext, err)

	/** [describe] with a context that outlives the page, for writes that finish after it closed. */
	private fun describe(context: Context?, err: Throwable): String {
		if (SeerrErrors.isUnlinked(err)) seerrLinkManager.onUnlinkedError()
		if (context == null) return RequestsRepository.serverMessage(err).orEmpty()
		if (err is RequestsRepository.HiddenContentUnavailableException) return context.getString(R.string.je_requests_hidden_unavailable)
		return SeerrLinkTexts.error(context, err, enhancedRepository.current.isAdmin)
			?: RequestsRepository.serverMessage(err)
			?: (err as? EnhancedApi.EnhancedApiException)?.let { context.getString(R.string.je_requests_load_failed, it.status) }
			?: context.getString(R.string.je_error_generic)
	}

	/** The Seerr link works now: whatever the tabs loaded before failed, so load them again. */
	private fun reloadAfterLink() {
		if (!enhancedRepository.current.requests) return
		listOf(requests, watchlist, history, issues).forEach { if (it.state.value.started) it.reload() }
		// Opening the current tab again refreshes the queue when that's the one on screen
		open(tab.value)
	}


	private fun openDetails(mediaType: String?, tmdbId: Int?) {
		if (tmdbId == null || tmdbId <= 0) return
		val type = if (mediaType == SeerrRepository.MEDIA_TV) SeerrRepository.MEDIA_TV else SeerrRepository.MEDIA_MOVIE
		navigationRepository.navigate(EnhancedDestinations.seerrDetails(type, tmdbId))
	}

	// Requests

	@Composable
	private fun RequestsTabContent() {
		val state by requests.state.collectAsState()
		val filter by requestFilter.collectAsState()
		val mine by mineOnly.collectAsState()
		val approve by canApprove.collectAsState()
		val busy by busyRequests.collectAsState()
		val counts by requestCounts.collectAsState()
		val today = remember { RequestsRepository.today() }

		PagedGrid(
			state = state,
			loader = requests,
			columns = 2,
			emptyMessage = stringResource(R.string.je_nothing_found),
			header = {
				ChipRow(modifier = Modifier.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.headerBottom)) {
					RequestFilter.entries.forEach { value ->
						FilterChip(
							label = stringResource(value.label()),
							selected = value == filter,
							count = counts[value],
							onClick = {
								if (requestFilter.value != value) {
									requestFilter.value = value
									requests.reload()
								}
							},
						)
					}
					ChipSeparator()
					FilterChip(
						label = stringResource(R.string.je_requests_mine_only),
						selected = mine == true,
						leadingIcon = R.drawable.ic_user,
						onClick = {
							mineOnly.value = mineOnly.value != true
							// The counts were for the other setting
							requestCounts.value = emptyMap()
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
		val chip = RequestChip.of(request, today)
		val by = request.requestedBy ?: stringResource(R.string.je_requests_chip_unknown)
		val requestedBy = relativeAge(request.createdAt)?.let { age -> stringResource(R.string.je_requests_requested_by, by, age) } ?: by
		// Release and air dates come from TMDB, outside Spoiler Guard: withheld for protected library titles
		val release = if (card.protectedTitle) null else releaseLabel(request, today)?.let { releaseText(it) }
		val working = stringResource(R.string.je_requests_working)
		val warning = if (chip == RequestChip.ORPHANED) stringResource(R.string.je_requests_orphaned_hint) else null
		// The card has a fixed height: with the warning line there is room for one caption
		val supporting = if (warning != null) listOf(if (busy) working else requestedBy)
		else listOfNotNull(if (busy) working else release, requestedBy)

		ListRow(
			title = request.title ?: stringResource(R.string.je_requests_issue_unknown_title),
			titleTrailing = request.year?.takeIf { !card.protectedTitle }?.toString(),
			leading = RowLeading.Poster(card.posterUrl, if (request.isTv) R.drawable.ic_tv else R.drawable.ic_clapperboard),
			badges = listOf(StatusBadgeSpec(JeStatuses.label(chip.name).status, stringResource(chip.label()))),
			warning = warning,
			supporting = supporting,
			// Stay focusable while busy (a spinner, presses ignored) so the focus stays put; act() ignores repeats too
			actions = if (actionable) listOf(
				RowAction(R.drawable.je_ic_requests_check, stringResource(R.string.je_requests_approve), { approve(card) }, busy = busy),
				RowAction(R.drawable.je_ic_requests_close, stringResource(R.string.je_requests_decline), { confirmDecline(card) }, busy = busy),
			) else emptyList(),
			onClick = { openDetails(request.type, request.tmdbId) },
			onMenu = { showRequestMenu(card, actionable) },
			density = RowDensity.Large,
		)
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
		if (card.request.tmdbId == null && !actionable) return
		dialog.value = RequestDialog.Menu(card, actionable)
	}

	private fun confirmDecline(card: RequestsRepository.RequestCard) {
		if (card.request.requestId in busyRequests.value) return
		dialog.value = RequestDialog.Decline(card)
	}

	/** The card menu (Show details, Approve, Decline) and the decline confirmation. */
	@Composable
	private fun RequestDialogs() {
		val current by dialog.collectAsState()
		val dismiss = { dialog.value = null }
		val menu = current as? RequestDialog.Menu
		JeDialog(
			visible = menu != null,
			onDismissRequest = dismiss,
			title = menu?.card?.request?.title ?: stringResource(R.string.je_requests_issue_unknown_title),
			actions = { JeButton(text = stringResource(R.string.je_design_cancel), onClick = dismiss) },
		) {
			val card = menu?.card ?: return@JeDialog
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				if (card.request.tmdbId != null) {
					JeButton(text = stringResource(R.string.je_requests_open_details), onClick = {
						dismiss()
						openDetails(card.request.type, card.request.tmdbId)
					})
				}
				if (menu.actionable) {
					JeButton(text = stringResource(R.string.je_requests_approve), icon = R.drawable.je_ic_requests_check, onClick = {
						dismiss()
						approve(card)
					})
					JeButton(text = stringResource(R.string.je_requests_decline), icon = R.drawable.je_ic_requests_close, onClick = {
						// Straight on to the confirmation
						dialog.value = RequestDialog.Decline(card)
					})
				}
			}
		}

		val decline = (current as? RequestDialog.Decline)?.card
		val declineTitle = decline?.request?.title ?: stringResource(R.string.je_requests_issue_unknown_title)
		JeConfirmDialog(
			visible = decline != null,
			title = stringResource(R.string.je_requests_decline_title, declineTitle),
			body = stringResource(R.string.je_requests_decline_message, decline?.request?.requestedBy ?: stringResource(R.string.je_requests_chip_unknown)),
			confirmLabel = stringResource(R.string.je_requests_decline),
			confirmIcon = R.drawable.je_ic_requests_close,
			destructive = true,
			onConfirm = { decline?.let { act(it, approve = false) } },
			onDismissRequest = dismiss,
		)
	}

	private fun approve(card: RequestsRepository.RequestCard) = act(card, approve = true)

	private fun act(card: RequestsRepository.RequestCard, approve: Boolean) {
		val requestId = card.request.requestId ?: return
		if (requestId in busyRequests.value) return
		val appContext = context?.applicationContext ?: return
		busyRequests.value += requestId
		val title = card.request.title.orEmpty()
		writeScope.launch {
			val message = try {
				if (approve) requestsRepository.approve(requestId) else requestsRepository.decline(requestId)
				appContext.getString(if (approve) R.string.je_requests_approved_toast else R.string.je_requests_declined_toast, title)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Request %d action failed", requestId)
				appContext.getString(R.string.je_requests_action_failed, describe(appContext, err))
			} finally {
				busyRequests.value -= requestId
			}
			Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
			// Never retry: refresh to show what the server now has (keeping the focused card), and the queue and
			// history an approval may have started. Nothing to refresh once the page is gone
			if (!lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return@launch
			requests.refresh()
			history.invalidate()
			if (tab.value == RequestsTab.HISTORY) history.show()
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
						horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap),
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.headerBottom),
					) {
						// Busy rather than disabled while syncing so the focus stays put; syncWatchlist() ignores repeats
						JeButton(
							text = stringResource(if (isSyncing) R.string.je_requests_syncing else R.string.je_requests_sync_now),
							icon = R.drawable.je_ic_requests_sync,
							busy = isSyncing,
							onClick = { syncWatchlist() },
						)
						Text(stringResource(R.string.je_requests_watchlist_hint), style = JeType.caption)
					}
				}
			} else null,
		) { result ->
			ListRow(
				title = result.displayTitle,
				titleTrailing = result.year.takeIf { !result.libraryArtwork },
				leading = RowLeading.Poster(
					if (result.libraryArtwork) result.libraryPosterUrl else SeerrImages.poster(result.posterPath),
					if (result.isTv) R.drawable.ic_tv else R.drawable.ic_clapperboard,
				),
				badges = listOfNotNull(JeStatuses.seerrMedia(result.status)),
				onClick = { openDetails(result.mediaType, result.id) },
				onMenu = {
					context?.let { context ->
						SeerrCardMenu.show(context, view, result, enhancedRepository.current) {
							watchlist.update { list -> list.filterNot { it.dedupeKey == result.dedupeKey } }
						}
					}
				},
				density = RowDensity.Large,
			)
		}
	}

	private fun syncWatchlist() {
		if (syncing.value) return
		val appContext = context?.applicationContext ?: return
		syncing.value = true
		writeScope.launch {
			val (message, synced) = try {
				val result = requestsRepository.syncWatchlist()
				val failedUsers = result.errors?.size ?: 0
				val text = if (failedUsers > 0) appContext.getString(R.string.je_requests_sync_result_errors, result.itemsProcessed, result.itemsAdded, failedUsers)
				else appContext.getString(R.string.je_requests_sync_result, result.itemsProcessed, result.itemsAdded)
				text to true
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Watchlist sync failed")
				appContext.getString(R.string.je_requests_sync_failed, describe(appContext, err)) to false
			} finally {
				syncing.value = false
			}
			Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
			if (synced && lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) watchlist.refresh()
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
		val refreshLabel = stringResource(R.string.je_requests_refresh)

		Column(modifier = Modifier.fillMaxSize()) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.headerBottom),
			) {
				JeButton(text = refreshLabel, onClick = { refreshQueue(silent = true) })
				if (pollSeconds != null) Text(stringResource(R.string.je_requests_auto_refresh, pollSeconds), style = JeType.caption)
				refreshError?.let { WarningLine(it) }
			}
			LoadStateContent(state, loading = SkeletonShape.Rows(density = RowDensity.Compact)) { result ->
				// One instance failing doesn't hide what the others returned
				val errors = result.errors.map { error ->
					stringResource(R.string.je_requests_queue_error, error.source ?: "Arr", error.instanceName ?: "?", error.reason ?: "")
				}
				if (result.entries.isEmpty()) {
					Column(modifier = Modifier.fillMaxSize()) {
						errors.forEach { Notice(NoticeKind.Warning, it, inline = true, modifier = Modifier.padding(horizontal = JeSpace.pageHorizontal, vertical = 2.dp)) }
						EmptyState(
							title = stringResource(R.string.je_requests_queue_empty),
							action = remember(refreshLabel) { EmptyAction(refreshLabel) { refreshQueue(silent = true) } },
							modifier = Modifier.weight(1f),
						)
					}
					return@LoadStateContent
				}
				LazyColumn(
					contentPadding = PaddingValues(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, top = 4.dp, bottom = JeSpace.pageBottom),
					verticalArrangement = Arrangement.spacedBy(JeSpace.rowGap),
				) {
					items(errors) { error -> Notice(NoticeKind.Warning, error, inline = true) }
					// No item keys: *arr ids aren't guaranteed unique across instances, and the list is replaced wholesale
					items(result.entries) { entry -> QueueRow(entry) }
				}
			}
		}
	}

	@Composable
	private fun QueueRow(entry: QueueEntry) {
		val item = entry.item
		val subtitle = when (entry) {
			is QueueEntry.SeasonPack -> listOfNotNull(
				stringResource(R.string.je_requests_season_pack, item.seasonNumber ?: 0, entry.episodes.size),
				entry.firstEpisode?.let { first ->
					entry.lastEpisode?.let { last -> stringResource(R.string.je_requests_episode_range, first, last) }
				},
			).joinToString(" · ")
			is QueueEntry.Single -> item.subtitle
		}
		val stats = listOfNotNull(
			item.instanceName ?: item.source,
			stringResource(R.string.je_requests_progress, item.progress.toInt()),
			formatTimeRemaining(item.timeRemaining)?.let { stringResource(R.string.je_requests_eta, it) },
			formatDownloadStats(entry.totalSize, entry.sizeRemaining),
		).joinToString(" · ")
		val status = JeStatuses.queue(item.status)
		ListRow(
			title = item.title ?: stringResource(R.string.je_requests_issue_unknown_title),
			leading = RowLeading.Poster(item.posterUrl, if (item.isSonarr) R.drawable.ic_tv else R.drawable.ic_clapperboard),
			badges = listOf(status),
			supporting = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, stats),
			progress = (item.progress / 100.0).toFloat(),
			onClick = { openDetails(if (item.isSonarr) SeerrRepository.MEDIA_TV else SeerrRepository.MEDIA_MOVIE, item.tmdbId) },
			// Grows with its lines: a full-width row has room for the subtitle, the stats and the progress line
			density = RowDensity.Compact,
		)
	}

	// History

	@Composable
	private fun HistoryTabContent() {
		val state by history.state.collectAsState()
		val visible by historyVisible.collectAsState()

		// visible:false is the server hiding history from this user, not a failure or an empty history
		if (!visible) {
			EmptyState(title = stringResource(R.string.je_requests_history_hidden))
			return
		}

		PagedGrid(
			state = state,
			loader = history,
			columns = 2,
			emptyMessage = stringResource(R.string.je_requests_history_empty),
		) { item ->
			val (outcome, text) = when (item.outcome) {
				HistoryOutcome.IMPORTED -> JeStatus.Available to R.string.je_requests_history_imported
				HistoryOutcome.PARTIAL -> JeStatus.PartlyAvailable to R.string.je_requests_history_partial
				HistoryOutcome.FAILED -> JeStatus.Declined to R.string.je_requests_history_failed
			}
			val episodes = item.episodeCount?.takeIf { it > 0 }?.let { stringResource(R.string.je_requests_history_episodes, item.importedCount ?: 0, it) }
			ListRow(
				title = item.title ?: stringResource(R.string.je_requests_issue_unknown_title),
				leading = RowLeading.Poster(item.posterUrl, if (item.isSonarr) R.drawable.ic_tv else R.drawable.ic_clapperboard),
				badges = listOf(StatusBadgeSpec(outcome, stringResource(text))),
				supporting = listOfNotNull(
					listOfNotNull(item.subtitle?.takeIf { it.isNotBlank() }, episodes).joinToString(" · ").ifEmpty { null },
					listOfNotNull(item.instanceName ?: item.source, relativeAge(item.date)).joinToString(" · "),
				),
				onClick = { openDetails(if (item.isSonarr) SeerrRepository.MEDIA_TV else SeerrRepository.MEDIA_MOVIE, item.tmdbId) },
				density = RowDensity.Large,
			)
		}
	}

	// Issues

	@Composable
	private fun IssuesTabContent() {
		val state by issues.state.collectAsState()
		val filter by issueFilter.collectAsState()
		val counts by issueCounts.collectAsState()

		PagedGrid(
			state = state,
			loader = issues,
			columns = 2,
			emptyMessage = stringResource(R.string.je_requests_issues_empty),
			header = {
				ChipRow(modifier = Modifier.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.headerBottom)) {
					IssueFilter.entries.forEach { value ->
						FilterChip(
							label = stringResource(
								when (value) {
									IssueFilter.ALL -> R.string.je_requests_filter_all
									IssueFilter.OPEN -> R.string.je_requests_issue_open
									IssueFilter.RESOLVED -> R.string.je_requests_issue_resolved
								}
							),
							selected = value == filter,
							count = counts[value],
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
			ListRow(
				title = card.title ?: stringResource(R.string.je_requests_issue_unknown_title),
				titleTrailing = card.year,
				leading = RowLeading.Poster(card.posterUrl, if (card.mediaType == SeerrRepository.MEDIA_TV) R.drawable.ic_tv else R.drawable.ic_clapperboard),
				badges = listOf(
					StatusBadgeSpec(
						if (issue.isResolved) JeStatus.Available else JeStatus.Warning,
						stringResource(if (issue.isResolved) R.string.je_requests_issue_resolved else R.string.je_requests_issue_open),
					),
					StatusBadgeSpec(JeStatus.Neutral, stringResource(issueTypeLabel(issue.issueType))),
				),
				supporting = listOfNotNull(
					listOfNotNull(issue.episodeLabel, issue.message).joinToString(" · ").ifEmpty { null },
					listOfNotNull(
						issue.createdBy?.name,
						relativeAge(issue.createdAt),
						issue.comments.size.takeIf { it > 1 }?.let { stringResource(R.string.je_requests_issue_comments, it) },
					).joinToString(" · ").ifEmpty { null },
				),
				onClick = { openDetails(card.mediaType, issue.media?.tmdbId) },
				density = RowDensity.Large,
			)
		}
	}

	private fun issueTypeLabel(type: Int?) = when (type) {
		SeerrIssueType.VIDEO -> R.string.je_requests_issue_video
		SeerrIssueType.AUDIO -> R.string.je_requests_issue_audio
		SeerrIssueType.SUBTITLES -> R.string.je_requests_issue_subtitles
		else -> R.string.je_requests_issue_other
	}
}

/** A dialog of the Requests page; null in the fragment's state when none is shown. */
private sealed interface RequestDialog {
	val card: RequestsRepository.RequestCard

	data class Menu(override val card: RequestsRepository.RequestCard, val actionable: Boolean) : RequestDialog
	data class Decline(override val card: RequestsRepository.RequestCard) : RequestDialog
}

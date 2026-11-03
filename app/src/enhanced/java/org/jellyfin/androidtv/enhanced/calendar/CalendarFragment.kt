package org.jellyfin.androidtv.enhanced.calendar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.design.ChipRow
import org.jellyfin.androidtv.enhanced.ui.design.ChipSeparator
import org.jellyfin.androidtv.enhanced.ui.design.EmptyState
import org.jellyfin.androidtv.enhanced.ui.design.FilterChip
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeIconButton
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.JeStatuses
import org.jellyfin.androidtv.enhanced.ui.design.ListRow
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.design.Notice
import org.jellyfin.androidtv.enhanced.ui.design.NoticeKind
import org.jellyfin.androidtv.enhanced.ui.design.Overline
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.enhanced.ui.design.RowDensity
import org.jellyfin.androidtv.enhanced.ui.design.RowLeading
import org.jellyfin.androidtv.enhanced.ui.design.SectionHeader
import org.jellyfin.androidtv.enhanced.ui.design.SkeletonShape
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The JE calendar as a two-week agenda: releases from Sonarr, Radarr and Shoko grouped by day, with week navigation
 * and filters. Selecting a release opens it in the library when the user can see it there, otherwise on Seerr.
 *
 * The plugin passes Sonarr/Radarr data through unfiltered, so this page applies Hidden Content's calendar filter and
 * Spoiler Guard itself: guarded titles get library artwork (or none), no finale marker, episode titles only when
 * the user's policy allows them, and, when air dates are protected, no date or time at all: they are listed apart,
 * from a fixed range starting today, whichever week is shown (see [CalendarLogic.agenda]).
 */
class CalendarFragment : Fragment() {
	private val calendarRepository by inject<CalendarRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val spoilerProtection by inject<SpoilerProtection>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()
	private val hiddenContentRepository by inject<HiddenContentRepository>()
	private val enhancedApi by inject<EnhancedApi>()
	private val navigationRepository by inject<NavigationRepository>()

	private data class Loaded(
		val start: LocalDate,
		val response: CalendarResponse,
		/**
		 * Releases from today on, independent of the window, for the undated group; null when no date can be hidden.
		 * Its errors are sources whose guarded releases may be missing from that group.
		 */
		val upcoming: CalendarResponse?,
		val config: CalendarConfig,
		/** Request keys; null when they couldn't be loaded (the highlight is optional unless it's enforced). */
		val requested: Set<String>?,
		/** Not every request could be read, so some requested titles may be unmarked (or missing when enforced). */
		val requestedTruncated: Boolean,
		val seerrEnabled: Boolean,
		/** The user's Spoiler Guard lists and policy; incomplete ones fail closed. */
		val spoiler: SpoilerSnapshot,
		/** Library posters of guarded titles by dash-less id; guarded titles without one show no artwork. */
		val posters: Map<String, String>,
		val firstDayOfWeek: java.time.DayOfWeek,
		val use12Hour: Boolean,
	)

	private val zone: ZoneId = ZoneId.systemDefault()
	private val start = MutableStateFlow(LocalDate.now(zone))
	private val loadState = MutableStateFlow<LoadState<Loaded>>(LoadState.Loading)
	private val filter = MutableStateFlow(CalendarFilter())
	private var filterInitialised = false
	private var loadJob: Job? = null
	/** [SpoilerGuardRepository.generation] when the current data started loading. */
	private var loadGeneration = -1L
	/** Whether the page has been started before: later starts are returns (from details, or to the app). */
	private var started = false
	private var opening = false
	/** Focus goes to the agenda once per view; after that, week navigation keeps focus on its buttons. */
	private var initialFocusDone = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		savedInstanceState?.let(::restore)
		reload()
	}

	override fun onStart() {
		super.onStart()
		if (!started) {
			started = true
			return
		}
		// Back from details (or the app): the user may have guarded, hidden or requested a title there
		if (spoilerGuardRepository.generation != loadGeneration) {
			// Everything shown was resolved against the old Spoiler Guard lists: drop it now, not after the network
			reload()
		} else if (loadState.value is LoadState.Ready) {
			reload(refresh = true)
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putString(STATE_START, start.value.toString())
		val current = filter.value
		outState.putStringArrayList(STATE_SOURCES, ArrayList(current.sources))
		outState.putStringArrayList(STATE_KINDS, ArrayList(current.kinds.map { it.name }))
		outState.putBoolean(STATE_HAS_FILE, current.hasFileOnly)
		outState.putBoolean(STATE_REQUESTED, current.requestedOnly)
		outState.putBoolean(STATE_FILTER_INITIALISED, filterInitialised)
	}

	private fun restore(state: Bundle) {
		state.getString(STATE_START)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { start.value = it }
		filter.value = CalendarFilter(
			sources = state.getStringArrayList(STATE_SOURCES).orEmpty().toSet(),
			kinds = state.getStringArrayList(STATE_KINDS).orEmpty()
				.mapNotNull { name -> CalendarFilter.Kind.entries.firstOrNull { it.name == name } }
				.toSet(),
			hasFileOnly = state.getBoolean(STATE_HAS_FILE),
			requestedOnly = state.getBoolean(STATE_REQUESTED),
		)
		filterInitialised = state.getBoolean(STATE_FILTER_INITIALISED)
	}

	/**
	 * [retry]: an explicit Retry, which re-reads an unknown configuration at once instead of waiting out its backoff.
	 * [refresh]: revalidate in the background, keeping the current agenda on screen until the new one is ready.
	 * Both re-read the user's requests; moving between weeks reuses them.
	 */
	private fun reload(retry: Boolean = false, refresh: Boolean = false) {
		loadJob?.cancel()
		if (!refresh) loadState.value = LoadState.Loading
		val windowStart = start.value
		val generation = spoilerGuardRepository.generation
		loadGeneration = generation
		val refreshRequests = retry || refresh
		loadJob = lifecycleScope.launch {
			val result = attempt {
				val state = enhancedRepository.ensureLoaded(force = retry && enhancedRepository.current.configUnavailable)
				// Without the configuration neither Spoiler Guard nor Hidden Content can be applied
				if (state.configUnavailable) error("Jellyfin Enhanced configuration unavailable")
				coroutineScope {
					val eventsCall = async { calendarRepository.events(windowStart, zone) }
					val configCall = async { calendarRepository.config() }
					val requestedCall = async {
						if (state.config.jellyseerrEnabled) {
							attempt { calendarRepository.requested(refresh = refreshRequests) }.getOrNull()
						} else {
							RequestedKeys(emptySet(), truncated = false)
						}
					}
					val spoilerCall = async { spoilerProtection.snapshot() }
					val upcomingCall = async {
						if (CalendarLogic.mayHideDates(spoilerCall.await())) calendarRepository.upcomingEvents(LocalDate.now(zone), zone) else null
					}
					// A failed read is reported rather than showing titles the user hid
					val hiddenCall = async { if (state.hiddenContent) CalendarHiddenFilter.of(hiddenContentRepository.get()) else null }
					val config = configCall.await()
					val requested = requestedCall.await()
					// Enforced "only requested" with no request list would show everything: report the failure instead
					if (config.forceOnlyRequested && requested == null) error("Requests unavailable")
					val hidden = hiddenCall.await()
					val response = eventsCall.await().let { response ->
						if (hidden == null) response else response.copy(events = response.events.filterNot(hidden::isHidden))
					}
					val upcoming = upcomingCall.await()?.let { upcoming ->
						if (hidden == null) upcoming else upcoming.copy(events = upcoming.events.filterNot(hidden::isHidden))
					}
					val spoiler = spoilerCall.await()
					if (spoiler.configUnavailable) error("Jellyfin Enhanced configuration unavailable")
					val guardedIds = (response.events + upcoming?.events.orEmpty())
						.filter { CalendarLogic.protection(it, spoiler).hideArtwork }
						.mapNotNull { it.libraryItemId?.takeIf(String::isNotBlank) }
					val posters = if (guardedIds.isEmpty()) emptyMap() else {
						attempt { calendarRepository.libraryPosters(guardedIds) }
							.onFailure { Timber.w(it, "Failed to load library posters; withholding artwork of guarded titles") }
							.getOrDefault(emptyMap())
					}
					Loaded(
						start = windowStart,
						response = response,
						upcoming = upcoming,
						config = config,
						requested = requested?.keys,
						requestedTruncated = requested?.truncated == true,
						seerrEnabled = state.config.jellyseerrEnabled,
						spoiler = spoiler,
						posters = posters,
						firstDayOfWeek = CalendarLogic.firstDayOfWeek(state.config.calendarFirstDayOfWeek),
						use12Hour = CalendarLogic.uses12HourClock(state.config.calendarTimeFormat),
					)
				}
			}
			// A Spoiler Guard write in this client overlapped the load, so it may have used the old lists
			if (result.isSuccess && spoilerGuardRepository.generation != generation) {
				reload()
				return@launch
			}
			result.onSuccess { loaded ->
				if (!filterInitialised) {
					filterInitialised = true
					if (loaded.config.showOnlyRequested && loaded.requested != null) filter.value = filter.value.copy(requestedOnly = true)
				}
				loadState.value = LoadState.Ready(loaded)
			}.onFailure { err ->
				Timber.w(err, "Failed to load the JE calendar")
				loadState.value = LoadState.Error(err.serverMessage) { reload(retry = true) }
			}
		}
	}

	private fun requestInitialFocus(target: FocusRequester) {
		if (initialFocusDone) return
		initialFocusDone = runCatching { target.requestFocus() }.isSuccess
	}

	private fun moveTo(newStart: LocalDate) {
		if (newStart == start.value) return
		start.value = newStart
		reload()
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): ViewGroup {
		initialFocusDone = false
		return createContent()
	}

	private fun createContent() = content {
		val state by loadState.collectAsState()
		val windowStart by start.collectAsState()
		val currentFilter by filter.collectAsState()
		val firstDay = (state as? LoadState.Ready)?.value?.firstDayOfWeek
			?: CalendarLogic.firstDayOfWeek(enhancedRepository.current.config.calendarFirstDayOfWeek)
		val rangeFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
		val todayFocus = remember { FocusRequester() }

		PageScaffold(
			title = stringResource(R.string.je_calendar_title),
			subtitle = stringResource(
				R.string.je_calendar_range,
				windowStart.format(rangeFormat),
				windowStart.plusDays(CalendarLogic.WINDOW_DAYS - 1).format(rangeFormat),
			),
			actions = {
				JeIconButton(
					icon = R.drawable.je_ic_calendar_chevron_left,
					contentDescription = stringResource(R.string.je_calendar_previous_week),
					onClick = { moveTo(CalendarLogic.shiftWeeks(windowStart, -1, firstDay)) },
				)
				JeButton(
					text = stringResource(R.string.je_calendar_today),
					onClick = { moveTo(LocalDate.now(zone)) },
					modifier = Modifier.focusRequester(todayFocus),
				)
				JeIconButton(
					icon = R.drawable.je_design_ic_chevron_right,
					contentDescription = stringResource(R.string.je_calendar_next_week),
					onClick = { moveTo(CalendarLogic.shiftWeeks(windowStart, 1, firstDay)) },
				)
			},
		) {
			LoadStateContent(state, loading = SkeletonShape.Rows(density = RowDensity.Large)) { loaded ->
				Agenda(loaded, currentFilter, todayFocus)
			}
		}
	}

	@Composable
	private fun Agenda(loaded: Loaded, currentFilter: CalendarFilter, fallbackFocus: FocusRequester) {
		val requested = loaded.requested.orEmpty()
		val agenda = remember(loaded, currentFilter) {
			CalendarLogic.agenda(
				events = loaded.response.events,
				start = loaded.start,
				zone = zone,
				filter = currentFilter,
				requested = requested,
				forceOnlyRequested = loaded.config.forceOnlyRequested,
				hideDate = { CalendarLogic.protection(it, loaded.spoiler).hideAirDate },
				upcoming = loaded.upcoming?.events,
			)
		}
		val firstEventFocus = remember { FocusRequester() }

		Column(modifier = Modifier.fillMaxSize()) {
			Filters(loaded, currentFilter)

			Column(
				modifier = Modifier.padding(horizontal = JeSpace.pageHorizontal),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				for (error in loaded.response.errors) {
					CalendarNotice(stringResource(R.string.je_calendar_source_failed, error.instanceName ?: error.source.orEmpty(), error.reason.orEmpty()))
				}
				if (loaded.seerrEnabled && loaded.requested == null) CalendarNotice(stringResource(R.string.je_calendar_requests_failed))
				if (loaded.requestedTruncated) CalendarNotice(stringResource(R.string.je_calendar_requests_truncated, CalendarRepository.MAX_REQUEST_PAGES * CalendarRepository.REQUEST_PAGE_SIZE))
				if (!loaded.spoiler.complete) CalendarNotice(stringResource(R.string.je_calendar_spoiler_unavailable))
				// A source that failed only for the upcoming range would make guarded releases vanish from both groups
				val upcomingErrors = loaded.upcoming?.errors.orEmpty()
				if (upcomingErrors.isNotEmpty()) {
					Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
						CalendarNotice(stringResource(R.string.je_calendar_upcoming_failed, CalendarLogic.sourceNames(upcomingErrors).joinToString()))
						JeButton(text = stringResource(R.string.je_design_retry), onClick = { reload(retry = true) })
					}
				}
			}

			if (agenda.isEmpty) {
				val filtered = currentFilter != CalendarFilter() || loaded.config.forceOnlyRequested
				// The week buttons in the header are the way on: the focus goes to Today, as before
				EmptyState(
					title = stringResource(if (filtered) R.string.je_calendar_empty_filtered else R.string.je_calendar_empty),
					icon = R.drawable.ic_calendar,
					action = null,
					modifier = Modifier.weight(1f),
				)
				LaunchedEffect(loaded) { requestInitialFocus(fallbackFocus) }
				return@Column
			}

			LazyColumn(
				contentPadding = PaddingValues(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, top = 4.dp, bottom = JeSpace.pageBottom),
				verticalArrangement = Arrangement.spacedBy(JeSpace.rowGap),
			) {
				val firstEvent = agenda.days.firstOrNull()?.second?.first() ?: agenda.undated.first()
				for ((day, events) in agenda.days) {
					item(key = "day-$day") { DayHeader(day) }
					items(events, key = { "${it.source}-${it.instanceName}-${it.eventId}" }) { event ->
						EventRow(loaded, event, requested, if (event === firstEvent) Modifier.focusRequester(firstEventFocus) else Modifier)
					}
				}
				if (agenda.undated.isNotEmpty()) {
					item(key = "undated") { Overline(stringResource(R.string.je_calendar_undated), Modifier.padding(top = JeSpace.sectionGap - JeSpace.rowGap)) }
					items(agenda.undated, key = { "${it.source}-${it.instanceName}-${it.eventId}" }) { event ->
						EventRow(loaded, event, requested, if (event === firstEvent) Modifier.focusRequester(firstEventFocus) else Modifier)
					}
				}
			}
			LaunchedEffect(loaded) { requestInitialFocus(firstEventFocus) }
		}
	}

	@Composable
	private fun Filters(loaded: Loaded, currentFilter: CalendarFilter) {
		val sources = remember(loaded) {
			(loaded.response.events.map { it.source } + loaded.upcoming?.events.orEmpty().map { it.source } + loaded.response.errors.mapNotNull { it.source })
				.filter { it.isNotBlank() }
				.distinctBy { it.lowercase() }
				.sorted()
		}

		val requestedChip = loaded.seerrEnabled && loaded.requested != null && !loaded.config.forceOnlyRequested
		ChipRow(
			modifier = Modifier
				.horizontalScroll(rememberScrollState())
				.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.headerBottom),
		) {
			if (sources.size > 1) {
				for (source in sources) {
					FilterChip(source, source in currentFilter.sources, onClick = {
						filter.value = currentFilter.copy(sources = currentFilter.sources.toggle(source))
					})
				}
				ChipSeparator()
			}
			FilterChip(stringResource(R.string.je_calendar_movies), CalendarFilter.Kind.MOVIE in currentFilter.kinds, onClick = {
				filter.value = currentFilter.copy(kinds = currentFilter.kinds.toggle(CalendarFilter.Kind.MOVIE))
			})
			FilterChip(stringResource(R.string.je_calendar_episodes), CalendarFilter.Kind.EPISODE in currentFilter.kinds, onClick = {
				filter.value = currentFilter.copy(kinds = currentFilter.kinds.toggle(CalendarFilter.Kind.EPISODE))
			})
			ChipSeparator()
			FilterChip(stringResource(R.string.je_calendar_has_file), currentFilter.hasFileOnly, onClick = {
				filter.value = currentFilter.copy(hasFileOnly = !currentFilter.hasFileOnly)
			})
			// Enforced server-side filtering needs no chip; without a request list there's nothing to filter by
			if (requestedChip) {
				FilterChip(stringResource(R.string.je_calendar_requested), currentFilter.requestedOnly, onClick = {
					filter.value = currentFilter.copy(requestedOnly = !currentFilter.requestedOnly)
				})
			}
		}
	}

	@Composable
	private fun DayHeader(day: LocalDate) {
		val format = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
		val today = LocalDate.now(zone)
		val label = when (day) {
			today -> stringResource(R.string.je_calendar_day_today, day.format(format))
			today.plusDays(1) -> stringResource(R.string.je_calendar_day_tomorrow, day.format(format))
			else -> day.format(format)
		}
		GroupHeader(label)
	}

	@Composable
	private fun GroupHeader(label: String) {
		SectionHeader(text = label, modifier = Modifier.padding(top = JeSpace.sectionGap - JeSpace.rowGap))
	}

	/** A failed source or a partial result: an inline warning above the agenda. */
	@Composable
	private fun CalendarNotice(text: String) {
		Notice(NoticeKind.Warning, text, inline = true)
	}

	@Composable
	private fun EventRow(loaded: Loaded, event: CalendarEvent, requested: Set<String>, modifier: Modifier) {
		val protection = CalendarLogic.protection(event, loaded.spoiler)
		val subtitle = CalendarLogic.subtitle(event, protection)
		val time = if (event.isMovie || protection.hideAirDate) null else event.instant?.atZone(zone)?.toLocalTime()?.format(
			DateTimeFormatter.ofPattern(if (loaded.use12Hour) "h:mm a" else "HH:mm", Locale.getDefault())
		)
		val poster = if (protection.hideArtwork) {
			event.libraryItemId?.let { loaded.posters[it.replace("-", "").lowercase()] }
		} else event.posterUrl
		val meta = listOfNotNull(
			time,
			releaseTypeLabel(event.releaseType),
			// "Series finale" is a plot fact: never for a guarded title
			if (protection.guarded) null else finaleLabel(event.finaleType),
			event.instanceName?.takeIf { it.isNotBlank() } ?: event.source.takeIf { it.isNotBlank() },
		).joinToString(" · ")
		val isRequested = event.requestKey?.let { it in requested } == true

		ListRow(
			title = event.title,
			leading = RowLeading.Poster(poster, if (event.isMovie) R.drawable.ic_clapperboard else R.drawable.ic_tv),
			badges = listOfNotNull(
				JeStatuses.label("Downloaded").takeIf { event.hasFile },
				JeStatuses.label("Requested").takeIf { isRequested },
			),
			supporting = listOfNotNull(subtitle, meta.ifEmpty { null }),
			onClick = { open(event) },
			mainModifier = modifier,
			density = RowDensity.Large,
		)
	}

	@Composable
	private fun releaseTypeLabel(releaseType: String?): String? = when (releaseType?.lowercase()) {
		"episode" -> stringResource(R.string.je_calendar_release_episode)
		"cinemarelease" -> stringResource(R.string.je_calendar_release_cinema)
		"digitalrelease" -> stringResource(R.string.je_calendar_release_digital)
		"physicalrelease" -> stringResource(R.string.je_calendar_release_physical)
		else -> releaseType?.takeIf { it.isNotBlank() }
	}

	@Composable
	private fun finaleLabel(finaleType: String?): String? = when (finaleType?.lowercase()) {
		"series" -> stringResource(R.string.je_calendar_finale_series)
		"season" -> stringResource(R.string.je_calendar_finale_season)
		"midseason" -> stringResource(R.string.je_calendar_finale_midseason)
		else -> null
	}

	/**
	 * Library details when the user can open the item (loaded as the user first: the calendar's ids aren't filtered
	 * by library access), otherwise Seerr details when there's a TMDB id and Seerr is usable.
	 */
	private fun open(event: CalendarEvent) {
		if (opening) return
		opening = true
		lifecycleScope.launch {
			try {
				val candidates = listOfNotNull(event.libraryEpisodeId, event.libraryItemId).mapNotNull { it.toUUIDOrNull() }
				for (id in candidates) {
					if (enhancedApi.canOpen(id)) {
						navigationRepository.navigate(Destinations.itemDetails(id))
						return@launch
					}
				}
				val tmdbId = event.validTmdbId
				if (tmdbId != null && enhancedRepository.current.seerrUsable) {
					navigationRepository.navigate(EnhancedDestinations.seerrDetails(event.mediaType, tmdbId))
					return@launch
				}
				val context = context ?: return@launch
				Toast.makeText(context, R.string.je_calendar_not_in_library, Toast.LENGTH_SHORT).show()
			} finally {
				opening = false
			}
		}
	}

	private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

	private companion object {
		const val STATE_START = "je_calendar_start"
		const val STATE_SOURCES = "je_calendar_sources"
		const val STATE_KINDS = "je_calendar_kinds"
		const val STATE_HAS_FILE = "je_calendar_has_file"
		const val STATE_REQUESTED = "je_calendar_requested"
		const val STATE_FILTER_INITIALISED = "je_calendar_filter_initialised"
	}
}

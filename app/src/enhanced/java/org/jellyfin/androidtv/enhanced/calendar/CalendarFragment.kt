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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedScreen
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.design.Tokens
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
 */
class CalendarFragment : Fragment() {
	private val calendarRepository by inject<CalendarRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()
	private val enhancedApi by inject<EnhancedApi>()
	private val navigationRepository by inject<NavigationRepository>()

	private data class Loaded(
		val start: LocalDate,
		val response: CalendarResponse,
		val config: CalendarConfig,
		/** Request keys; null when they couldn't be loaded (the highlight is optional unless it's enforced). */
		val requested: Set<String>?,
		val seerrEnabled: Boolean,
		val spoilerGuard: Boolean,
		/** Null with [spoilerGuard] on means the guard list failed to load: every episode is withheld. */
		val spoilerState: SpoilerGuardState?,
		val firstDayOfWeek: java.time.DayOfWeek,
		val use12Hour: Boolean,
	)

	private val zone: ZoneId = ZoneId.systemDefault()
	private val start = MutableStateFlow(LocalDate.now(zone))
	private val loadState = MutableStateFlow<LoadState<Loaded>>(LoadState.Loading)
	private val filter = MutableStateFlow(CalendarFilter())
	private var filterInitialised = false
	private var loadJob: Job? = null
	private var opening = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		reload()
	}

	private fun reload() {
		loadJob?.cancel()
		loadState.value = LoadState.Loading
		val windowStart = start.value
		loadJob = lifecycleScope.launch {
			val result = attempt {
				val state = enhancedRepository.ensureLoaded()
				coroutineScope {
					val eventsCall = async { calendarRepository.events(windowStart, zone) }
					val configCall = async { calendarRepository.config() }
					val requestedCall = async {
						if (state.config.jellyseerrEnabled) attempt { calendarRepository.requestedKeys() }.getOrNull() else emptySet()
					}
					val spoilerCall = async {
						if (state.spoilerGuard) attempt { spoilerGuardRepository.getState() }.getOrNull() else null
					}
					val config = configCall.await()
					val requested = requestedCall.await()
					// Enforced "only requested" with no request list would show everything: report the failure instead
					if (config.forceOnlyRequested && requested == null) error("Requests unavailable")
					Loaded(
						start = windowStart,
						response = eventsCall.await(),
						config = config,
						requested = requested,
						seerrEnabled = state.config.jellyseerrEnabled,
						spoilerGuard = state.spoilerGuard,
						spoilerState = spoilerCall.await(),
						firstDayOfWeek = CalendarLogic.firstDayOfWeek(state.config.calendarFirstDayOfWeek),
						use12Hour = CalendarLogic.uses12HourClock(state.config.calendarTimeFormat),
					)
				}
			}
			result.onSuccess { loaded ->
				if (!filterInitialised) {
					filterInitialised = true
					if (loaded.config.showOnlyRequested && loaded.requested != null) filter.value = filter.value.copy(requestedOnly = true)
				}
				loadState.value = LoadState.Ready(loaded)
			}.onFailure { err ->
				Timber.w(err, "Failed to load the JE calendar")
				loadState.value = LoadState.Error(err.serverMessage) { reload() }
			}
		}
	}

	private fun moveTo(newStart: LocalDate) {
		if (newStart == start.value) return
		start.value = newStart
		reload()
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val state by loadState.collectAsState()
		val windowStart by start.collectAsState()
		val currentFilter by filter.collectAsState()
		val firstDay = (state as? LoadState.Ready)?.value?.firstDayOfWeek
			?: CalendarLogic.firstDayOfWeek(enhancedRepository.current.config.calendarFirstDayOfWeek)
		val rangeFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
		val todayFocus = remember { FocusRequester() }

		EnhancedScreen(
			title = stringResource(R.string.je_calendar_title),
			subtitle = stringResource(
				R.string.je_calendar_range,
				windowStart.format(rangeFormat),
				windowStart.plusDays(CalendarLogic.WINDOW_DAYS - 1).format(rangeFormat),
			),
			headerActions = {
				Button(onClick = { moveTo(CalendarLogic.shiftWeeks(windowStart, -1, firstDay)) }) {
					Text(stringResource(R.string.je_calendar_previous_week))
				}
				Button(onClick = { moveTo(LocalDate.now(zone)) }, modifier = Modifier.focusRequester(todayFocus)) {
					Text(stringResource(R.string.je_calendar_today))
				}
				Button(onClick = { moveTo(CalendarLogic.shiftWeeks(windowStart, 1, firstDay)) }) {
					Text(stringResource(R.string.je_calendar_next_week))
				}
			},
		) {
			LoadStateContent(state) { loaded ->
				Agenda(loaded, currentFilter, todayFocus)
			}
		}
	}

	@Composable
	private fun Agenda(loaded: Loaded, currentFilter: CalendarFilter, fallbackFocus: FocusRequester) {
		val requested = loaded.requested.orEmpty()
		val days = remember(loaded, currentFilter) {
			CalendarLogic.agenda(
				events = loaded.response.events,
				start = loaded.start,
				zone = zone,
				filter = currentFilter,
				requested = requested,
				forceOnlyRequested = loaded.config.forceOnlyRequested,
			)
		}
		val firstEventFocus = remember { FocusRequester() }

		Column(modifier = Modifier.fillMaxSize()) {
			Filters(loaded, currentFilter)

			Column(modifier = Modifier.padding(horizontal = 48.dp)) {
				for (error in loaded.response.errors) {
					PageNotice(stringResource(R.string.je_calendar_source_failed, error.instanceName ?: error.source.orEmpty(), error.reason.orEmpty()))
				}
				if (loaded.seerrEnabled && loaded.requested == null) PageNotice(stringResource(R.string.je_calendar_requests_failed))
			}

			if (days.isEmpty()) {
				val filtered = currentFilter != CalendarFilter() || loaded.config.forceOnlyRequested
				Text(
					text = stringResource(if (filtered) R.string.je_calendar_empty_filtered else R.string.je_calendar_empty),
					color = Tokens.Color.colorGrey300,
					fontSize = 18.sp,
					modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
				)
				LaunchedEffect(loaded) { runCatching { fallbackFocus.requestFocus() } }
				return@Column
			}

			LazyColumn(
				contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				val firstEvent = days.first().second.first()
				for ((day, events) in days) {
					item(key = "day-$day") { DayHeader(day) }
					items(events, key = { "${it.source}-${it.instanceName}-${it.eventId}" }) { event ->
						EventRow(loaded, event, requested, if (event === firstEvent) Modifier.focusRequester(firstEventFocus) else Modifier)
					}
				}
			}
			LaunchedEffect(loaded) { runCatching { firstEventFocus.requestFocus() } }
		}
	}

	@Composable
	private fun Filters(loaded: Loaded, currentFilter: CalendarFilter) {
		val sources = remember(loaded) {
			(loaded.response.events.map { it.source } + loaded.response.errors.mapNotNull { it.source })
				.filter { it.isNotBlank() }
				.distinctBy { it.lowercase() }
				.sorted()
		}

		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.horizontalScroll(rememberScrollState())
				.padding(horizontal = 48.dp, vertical = 4.dp),
		) {
			if (sources.size > 1) {
				for (source in sources) {
					PageChip(source, source in currentFilter.sources, onClick = {
						filter.value = currentFilter.copy(sources = currentFilter.sources.toggle(source))
					})
				}
			}
			PageChip(stringResource(R.string.je_calendar_movies), CalendarFilter.Kind.MOVIE in currentFilter.kinds, onClick = {
				filter.value = currentFilter.copy(kinds = currentFilter.kinds.toggle(CalendarFilter.Kind.MOVIE))
			})
			PageChip(stringResource(R.string.je_calendar_episodes), CalendarFilter.Kind.EPISODE in currentFilter.kinds, onClick = {
				filter.value = currentFilter.copy(kinds = currentFilter.kinds.toggle(CalendarFilter.Kind.EPISODE))
			})
			PageChip(stringResource(R.string.je_calendar_has_file), currentFilter.hasFileOnly, onClick = {
				filter.value = currentFilter.copy(hasFileOnly = !currentFilter.hasFileOnly)
			})
			// Enforced server-side filtering needs no chip; without a request list there's nothing to filter by
			if (loaded.seerrEnabled && loaded.requested != null && !loaded.config.forceOnlyRequested) {
				PageChip(stringResource(R.string.je_calendar_requested), currentFilter.requestedOnly, onClick = {
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
		Text(
			text = label,
			fontSize = 20.sp,
			color = Tokens.Color.colorWhite,
			modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
		)
	}

	@Composable
	private fun EventRow(loaded: Loaded, event: CalendarEvent, requested: Set<String>, modifier: Modifier) {
		val guarded = CalendarLogic.isSpoilerGuarded(event, loaded.spoilerGuard, loaded.spoilerState)
		val subtitle = CalendarLogic.subtitle(event, guarded)
		val time = if (event.isMovie) null else event.instant?.atZone(zone)?.toLocalTime()?.format(
			DateTimeFormatter.ofPattern(if (loaded.use12Hour) "h:mm a" else "HH:mm", Locale.getDefault())
		)
		val meta = listOfNotNull(
			time,
			releaseTypeLabel(event.releaseType),
			finaleLabel(event.finaleType),
			event.instanceName?.takeIf { it.isNotBlank() } ?: event.source.takeIf { it.isNotBlank() },
		).joinToString("  ·  ")
		val isRequested = event.requestKey?.let { it in requested } == true

		PageListRow(onClick = { open(event) }, modifier = modifier) {
			PageImage(url = event.posterUrl, modifier = Modifier.size(56.dp, 84.dp))
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(text = event.title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
				subtitle?.let { Text(text = it, fontSize = 15.sp, color = Tokens.Color.colorGrey300, maxLines = 1, overflow = TextOverflow.Ellipsis) }
				if (meta.isNotEmpty()) Text(text = meta, fontSize = 13.sp, color = Tokens.Color.colorGrey300, maxLines = 1)
			}
			Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
				if (event.hasFile) Text(stringResource(R.string.je_calendar_marker_downloaded), fontSize = 13.sp, color = Tokens.Color.colorGreen300)
				if (isRequested) Text(stringResource(R.string.je_calendar_marker_requested), fontSize = 13.sp, color = Tokens.Color.colorOrange300)
			}
		}
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
}

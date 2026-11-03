package org.jellyfin.androidtv.enhanced.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.Protection
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.time.temporal.TemporalAdjusters

/**
 * One release from `GET /JellyfinEnhanced/arr/calendar`. The wrapper is camelCase (the plugin writes it by hand).
 *
 * The overview is deliberately not decoded: it comes straight from Sonarr/Radarr, outside the plugin's Spoiler Guard
 * filtering, and the agenda has no place that needs it.
 */
@Serializable
data class CalendarEvent(
	/** The source's event id (`28510`, `465-DigitalRelease`); not a Jellyfin id. */
	@SerialName("id") val eventId: String = "",
	/** `Sonarr`, `Radarr` or `Shoko`. */
	val source: String = "",
	/** `Movie` or `Series`. */
	val type: String = "",
	val title: String = "",
	val subtitle: String? = null,
	/** ISO instant in UTC. Movie releases are dates at midnight UTC. */
	val releaseDate: String? = null,
	/** `Episode`, `CinemaRelease`, `DigitalRelease` or `PhysicalRelease`. */
	val releaseType: String? = null,
	val hasFile: Boolean = false,
	val monitored: Boolean = true,
	/** External artwork URL (TVDB/TMDB), used as given. */
	val posterUrl: String? = null,
	/** Jellyfin GUID of the movie, or of the series for episodes, when the library has it. */
	@SerialName("itemId") val libraryItemId: String? = null,
	/** Jellyfin GUID of the episode, when the library has it. */
	@SerialName("itemEpisodeId") val libraryEpisodeId: String? = null,
	val seasonNumber: Int? = null,
	val episodeNumber: Int? = null,
	val episodeTitle: String? = null,
	/** `series`, `season` or `midseason` on finales. */
	val finaleType: String? = null,
	/** TMDB id of the movie or show; 0 or absent when unknown. */
	val tmdbId: Int? = null,
	val instanceName: String? = null,
) {
	val isMovie get() = type.equals("Movie", ignoreCase = true)

	/** Seerr/TMDB media type. */
	val mediaType get() = if (isMovie) "movie" else "tv"

	val validTmdbId get() = tmdbId?.takeIf { it > 0 }

	/** Key into the user's requested titles, as the web calendar builds it. */
	val requestKey get() = validTmdbId?.let { "$mediaType:$it" }

	val instant: Instant? get() = releaseDate?.let { runCatching { Instant.parse(it) }.getOrNull() }

	/**
	 * The day the release belongs to. Episodes air at a real time, so they use the viewer's time zone; movie
	 * releases are plain dates stored as midnight UTC and would otherwise move to the previous day west of UTC.
	 */
	fun day(zone: ZoneId): LocalDate? = instant?.atZone(if (isMovie) ZoneOffset.UTC else zone)?.toLocalDate()
}

/** One source that failed to answer (the other sources' events are still returned). */
@Serializable
data class CalendarSourceError(
	val instanceName: String? = null,
	val source: String? = null,
	val reason: String? = null,
)

@Serializable
data class CalendarResponse(
	val events: List<CalendarEvent> = emptyList(),
	val errors: List<CalendarSourceError> = emptyList(),
)

/** The calendar options of `public-config` that [org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig] doesn't carry. */
@Serializable
data class CalendarConfig(
	@SerialName("CalendarShowOnlyRequested") val showOnlyRequested: Boolean = false,
	@SerialName("CalendarForceOnlyRequested") val forceOnlyRequested: Boolean = false,
)

/** `GET arr/requests` page, only what the requested-title highlight needs. */
@Serializable
data class CalendarRequestsPage(
	val totalPages: Int = 1,
	val requests: List<CalendarRequest> = emptyList(),
)

@Serializable
data class CalendarRequest(
	/** `movie` or `tv`. */
	val type: String? = null,
	val tmdbId: Int? = null,
) {
	val key get() = if (type.isNullOrBlank() || tmdbId == null || tmdbId <= 0) null else "${type.lowercase()}:$tmdbId"
}

/** The agenda's interactive filters. An empty set means "any" for that group. */
data class CalendarFilter(
	val sources: Set<String> = emptySet(),
	val kinds: Set<Kind> = emptySet(),
	val hasFileOnly: Boolean = false,
	val requestedOnly: Boolean = false,
) {
	enum class Kind { MOVIE, EPISODE }

	fun matches(event: CalendarEvent, requested: Set<String>): Boolean {
		if (sources.isNotEmpty() && sources.none { it.equals(event.source, ignoreCase = true) }) return false
		if (kinds.isNotEmpty() && (if (event.isMovie) Kind.MOVIE else Kind.EPISODE) !in kinds) return false
		if (hasFileOnly && !event.hasFile) return false
		if (requestedOnly && event.requestKey !in requested) return false
		return true
	}
}

/**
 * The agenda: dated [days], plus the releases of Spoiler Guarded titles whose air dates are hidden for this user
 * ([undated]). Those are shown under one neutral heading with no date or time, ordered by title and episode, so
 * neither their grouping nor their order gives the date away. [undated] is the same for every window (see
 * [CalendarLogic.agenda]), so paging through weeks doesn't reveal when they air either.
 */
data class CalendarAgenda(
	val days: List<Pair<LocalDate, List<CalendarEvent>>>,
	val undated: List<CalendarEvent> = emptyList(),
) {
	val isEmpty get() = days.isEmpty() && undated.isEmpty()
}

/**
 * Hidden Content's calendar filter, as the web's `filterCalendarEvents` applies it: an event is dropped when its
 * TMDB id or library id is hidden everywhere (scope `global`), or when its title matches the name of any hidden entry
 * (any scope, case-insensitive; a trailing `(…)` qualifier on the entry's name is optional). The episode's own library
 * id is matched too, so a hidden episode drops its release.
 */
class CalendarHiddenFilter(
	private val itemIds: Set<String>,
	private val tmdbIds: Set<String>,
	private val names: Set<String>,
) {
	fun isHidden(event: CalendarEvent): Boolean {
		val tmdbId = event.validTmdbId
		if (tmdbId != null && tmdbId.toString() in tmdbIds) return true
		if (listOfNotNull(event.libraryItemId, event.libraryEpisodeId).any { normalize(it) in itemIds }) return true
		return event.title.isNotEmpty() && event.title.lowercase(Locale.ROOT) in names
	}

	companion object {
		private val TRAILING_QUALIFIER = Regex("""\s*\([^)]*\)\s*$""")

		private fun normalize(id: String) = id.replace("-", "").lowercase(Locale.ROOT)

		/** The filter for [snapshot], or null when the user's settings don't filter the calendar. */
		fun of(snapshot: HiddenContentRepository.Snapshot): CalendarHiddenFilter? {
			if (!snapshot.settings.enabled || !snapshot.settings.filterCalendar) return null
			return fromEntries(snapshot.entries)
		}

		fun fromEntries(entries: List<JsonObject>): CalendarHiddenFilter {
			fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
			val global = entries.filter { it.string("HideScope").ifEmpty { "global" } == "global" }
			val names = entries.map { it.string("Name") }.filter { it.isNotEmpty() }.flatMap { name ->
				val lower = name.lowercase(Locale.ROOT)
				listOf(lower, lower.replace(TRAILING_QUALIFIER, ""))
			}
			return CalendarHiddenFilter(
				itemIds = global.map { normalize(it.string("ItemId")) }.filter { it.isNotEmpty() }.toSet(),
				tmdbIds = global.map { it.string("TmdbId") }.filter { it.isNotEmpty() }.toSet(),
				names = names.toSet(),
			)
		}
	}
}

/** Pure calendar logic, kept apart from the UI so it can be tested. */
object CalendarLogic {
	/** How many days the agenda shows from its start date. */
	const val WINDOW_DAYS = 14L

	/** How far ahead the undated group looks, from today, independent of the window. */
	const val UNDATED_HORIZON_DAYS = 90L

	/** `CalendarFirstDayOfWeek` is a day name (`Monday`, `Sunday`, …); anything else falls back to Monday. */
	fun firstDayOfWeek(value: String?): DayOfWeek =
		DayOfWeek.entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DayOfWeek.MONDAY

	/** The start of the week containing [date]. */
	fun weekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(firstDay))

	/** The window start after moving [weeks] weeks from [start], snapped to the configured first day of the week. */
	fun shiftWeeks(start: LocalDate, weeks: Long, firstDay: DayOfWeek): LocalDate =
		weekStart(start.plusWeeks(weeks), firstDay)

	/**
	 * Query bounds for the window starting on [start]: local midnight to local midnight [WINDOW_DAYS] later, widened
	 * by a day each side so movie dates (midnight UTC) near the edges aren't lost; [inWindow] trims them again.
	 */
	fun queryRange(start: LocalDate, zone: ZoneId): Pair<String, String> {
		val from = start.minusDays(1).atStartOfDay(zone).toInstant()
		val to = start.plusDays(WINDOW_DAYS + 1).atStartOfDay(zone).toInstant()
		return from.toString() to to.toString()
	}

	fun inWindow(day: LocalDate, start: LocalDate) = !day.isBefore(start) && day.isBefore(start.plusDays(WINDOW_DAYS))

	/** Query bounds for the undated group: local midnight [today] to [UNDATED_HORIZON_DAYS] later. */
	fun upcomingRange(today: LocalDate, zone: ZoneId): Pair<String, String> {
		val from = today.atStartOfDay(zone).toInstant()
		val to = today.plusDays(UNDATED_HORIZON_DAYS).atStartOfDay(zone).toInstant()
		return from.toString() to to.toString()
	}

	/**
	 * Monitored events of the window, filtered and grouped by day in date order, each day sorted by release time.
	 * Unmonitored events are left out like the web calendar does by default.
	 *
	 * Events for which [hideDate] is true never appear in [CalendarAgenda.days]. [CalendarAgenda.undated] lists them
	 * from [upcoming] instead, the releases of a fixed range from today ([upcomingRange]) rather than of the window:
	 * picking them from the window would let a guarded title appear in the week it airs and vanish from the others.
	 * Without [upcoming] (nothing could have its date hidden when it was loaded) the undated group is empty. The
	 * chips and the request filter apply to both groups.
	 */
	fun agenda(
		events: List<CalendarEvent>,
		start: LocalDate,
		zone: ZoneId,
		filter: CalendarFilter,
		requested: Set<String>,
		forceOnlyRequested: Boolean,
		hideDate: (CalendarEvent) -> Boolean = { false },
		upcoming: List<CalendarEvent>? = null,
	): CalendarAgenda {
		fun Sequence<CalendarEvent>.shown() = this
			.filter { it.monitored }
			.filter { !forceOnlyRequested || it.requestKey in requested }
			.filter { filter.matches(it, requested) }
		val dated = events
			.asSequence()
			.shown()
			.filterNot(hideDate)
			.mapNotNull { event -> event.day(zone)?.takeIf { inWindow(it, start) }?.let { it to event } }
			.toList()
		val undated = upcoming.orEmpty()
			.asSequence()
			.shown()
			.filter(hideDate)
			.distinctBy { listOf(it.source, it.instanceName, it.eventId) }
			.toList()
		return CalendarAgenda(
			days = dated
				.groupBy({ it.first }, { it.second })
				.toSortedMap()
				.map { (day, dayEvents) -> day to dayEvents.sortedWith(compareBy({ it.instant }, { it.title })) },
			undated = undated.sortedWith(
				compareBy({ it.title.lowercase(Locale.ROOT) }, { it.seasonNumber }, { it.episodeNumber }, { it.eventId })
			),
		)
	}

	/**
	 * Whether any release could have its date hidden under [spoiler], so the undated group needs its own query:
	 * Spoiler Guard is on and either its air date category is in effect or its lists are incomplete (unresolved
	 * titles are fully protected).
	 */
	fun mayHideDates(spoiler: SpoilerSnapshot): Boolean = spoiler.enabled && (spoiler.policy.hideAirDate || !spoiler.complete)

	/**
	 * What to withhold for the event. Calendar data comes straight from Sonarr/Radarr (artwork, dates, titles), so
	 * the plugin doesn't filter it: series by the show's library id, movies by theirs (or a guarded collection), and
	 * titles opted in before they reached the library by TMDB id. Fails closed when the guard lists couldn't be read.
	 */
	fun protection(event: CalendarEvent, spoiler: SpoilerSnapshot): Protection =
		spoiler.forTitle(event.isMovie, event.libraryItemId, event.validTmdbId)

	/**
	 * The line under the title: `S02E01 · Episode title` for episodes (just `S02E01` when episode titles are
	 * protected), the source's subtitle (the year) for movies.
	 */
	fun subtitle(event: CalendarEvent, protection: Protection): String? {
		if (event.isMovie) return event.subtitle?.takeIf { it.isNotBlank() }
		val season = event.seasonNumber
		val episode = event.episodeNumber
		val code = if (season != null && episode != null) "S%02dE%02d".format(season, episode) else null
		// The source's subtitle carries the episode title too, so it can't stand in for the code
		if (protection.hideTitles) return code
		val episodeTitle = event.episodeTitle?.takeIf { it.isNotBlank() && !it.equals("TBA", ignoreCase = true) }
		return when {
			code != null && episodeTitle != null -> "$code · $episodeTitle"
			code != null -> code
			else -> event.subtitle?.takeIf { it.isNotBlank() }
		}
	}

	/** `CalendarTimeFormat` is `5pm/5:30pm` (12 hour) or `17:00/17:30` (24 hour). */
	fun uses12HourClock(timeFormat: String?): Boolean = timeFormat?.contains("pm", ignoreCase = true) == true
}

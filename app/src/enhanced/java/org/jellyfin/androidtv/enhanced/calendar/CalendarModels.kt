package org.jellyfin.androidtv.enhanced.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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

/** Pure calendar logic, kept apart from the UI so it can be tested. */
object CalendarLogic {
	/** How many days the agenda shows from its start date. */
	const val WINDOW_DAYS = 14L

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

	/**
	 * Monitored events of the window, filtered and grouped by day in date order, each day sorted by release time.
	 * Unmonitored events are left out like the web calendar does by default.
	 */
	fun agenda(
		events: List<CalendarEvent>,
		start: LocalDate,
		zone: ZoneId,
		filter: CalendarFilter,
		requested: Set<String>,
		forceOnlyRequested: Boolean,
	): List<Pair<LocalDate, List<CalendarEvent>>> = events
		.asSequence()
		.filter { it.monitored }
		.filter { !forceOnlyRequested || it.requestKey in requested }
		.filter { filter.matches(it, requested) }
		.mapNotNull { event -> event.day(zone)?.takeIf { inWindow(it, start) }?.let { it to event } }
		.groupBy({ it.first }, { it.second })
		.toSortedMap()
		.map { (day, dayEvents) -> day to dayEvents.sortedWith(compareBy({ it.instant }, { it.title })) }

	/**
	 * Whether the event's episode details must be withheld. Calendar data comes straight from Sonarr, so it isn't
	 * filtered by the plugin. Fail closed: when Spoiler Guard is on but the user's guard list couldn't be read
	 * ([spoilerState] null), every episode is treated as guarded.
	 */
	fun isSpoilerGuarded(event: CalendarEvent, spoilerGuardEnabled: Boolean, spoilerState: SpoilerGuardState?): Boolean {
		if (!spoilerGuardEnabled || event.isMovie) return false
		if (spoilerState == null) return true
		val seriesKey = event.libraryItemId?.replace("-", "")?.lowercase()
		if (seriesKey != null && spoilerState.series.keys.any { it.replace("-", "").equals(seriesKey, ignoreCase = true) }) return true
		// Opted in before the show reached the library
		val tmdbId = event.validTmdbId
		return tmdbId != null && spoilerState.pendingTmdb.containsKey("tv:$tmdbId")
	}

	/**
	 * The line under the title: `S02E01 · Episode title` for episodes (just `S02E01` when guarded), the source's
	 * subtitle (the year) for movies.
	 */
	fun subtitle(event: CalendarEvent, guarded: Boolean): String? {
		if (event.isMovie) return event.subtitle?.takeIf { it.isNotBlank() }
		val season = event.seasonNumber
		val episode = event.episodeNumber
		val code = if (season != null && episode != null) "S%02dE%02d".format(season, episode) else null
		if (guarded) return code
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

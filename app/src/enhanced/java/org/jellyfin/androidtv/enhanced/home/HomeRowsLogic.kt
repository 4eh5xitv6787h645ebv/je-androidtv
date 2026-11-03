package org.jellyfin.androidtv.enhanced.home

import androidx.annotation.StringRes
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkEntry
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkLogic
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkName
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkedItem
import org.jellyfin.androidtv.enhanced.calendar.CalendarEvent
import org.jellyfin.androidtv.enhanced.calendar.CalendarFilter
import org.jellyfin.androidtv.enhanced.calendar.CalendarHiddenFilter
import org.jellyfin.androidtv.enhanced.calendar.CalendarLogic
import org.jellyfin.androidtv.enhanced.catchup.normalizeItemId
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.time.ZoneId

/**
 * The optional Jellyfin Enhanced rows of the home screen, in the order they are added below upstream's rows. Each is
 * off until the user turns it on (Settings › Jellyfin Enhanced › Home rows) and only shows while its JE feature is
 * available ([gate]).
 */
enum class HomeRowKind(
	/** Stable id saved in the preferences. */
	val key: String,
	@StringRes val title: Int,
	/** What the row shows, for its switch on the settings page. */
	@StringRes val caption: Int,
	val gate: (EnhancedState) -> Boolean,
) {
	COMING_UP("coming_up", R.string.je_home_coming_up, R.string.je_home_settings_coming_up, { it.calendar }),
	JUMP_BACK_IN("jump_back_in", R.string.je_home_jump_back_in, R.string.je_home_settings_jump_back_in, { it.bookmarks }),
	TRENDING("trending", R.string.je_home_trending, R.string.je_home_settings_trending, { it.discover }),
	CATCH_UP("catch_up", R.string.je_home_catch_up, R.string.je_home_settings_catch_up, { it.available && it.config.catchUpEnabled });

	companion object {
		fun fromKey(key: String): HomeRowKind? = entries.firstOrNull { it.key == key }

		/** The rows to show: turned on by the user and offered by the server, in display order. */
		fun shown(enabled: Set<HomeRowKind>, state: EnhancedState): List<HomeRowKind> =
			if (!state.available) emptyList() else entries.filter { it in enabled && it.gate(state) }
	}
}

/** How a "Coming up" card names the day of a release. [NONE] when the air date is withheld (Spoiler Guard). */
sealed interface DayLabel {
	data object NONE : DayLabel
	data object TODAY : DayLabel
	data object TOMORROW : DayLabel
	data class Weekday(val day: java.time.DayOfWeek) : DayLabel
}

/**
 * One "Coming up" card. [libraryIds] are the Jellyfin GUIDs to show it with, best first (the episode, then its
 * series; or the movie). [day] is null for releases whose air date Spoiler Guard withholds.
 */
data class ComingUpEntry(
	val event: CalendarEvent,
	val day: LocalDate?,
	val libraryIds: List<String>,
)

/** A bookmark card: the bookmark, the item it points at as the server returns it, and the name it may show. */
data class JumpBackInEntry(
	val entry: BookmarkEntry,
	val item: BookmarkedItem,
	val name: BookmarkName,
)

/** Pure logic of the home rows, kept apart from the views so it can be tested. */
object HomeRowsLogic {
	/** "Coming up" covers today and the next six days. */
	const val COMING_UP_DAYS = 7L
	const val COMING_UP_LIMIT = 30
	const val JUMP_BACK_IN_LIMIT = 20
	const val TRENDING_LIMIT = 20
	const val CATCH_UP_LIMIT = 10

	/**
	 * The "Coming up" cards: monitored releases of the next [COMING_UP_DAYS] days that the library already knows
	 * (they carry a Jellyfin id), in air order, then the releases whose air date Spoiler Guard withholds, taken from
	 * [upcoming] (a fixed range from today, as the calendar's undated group) and shown without a day. Everything goes
	 * through Hidden Content's calendar filter ([hidden]) and the enforced "only requested" option the way the
	 * calendar page applies them. A movie's several releases (cinema, digital, physical) count once.
	 */
	fun comingUp(
		events: List<CalendarEvent>,
		upcoming: List<CalendarEvent>?,
		today: LocalDate,
		zone: ZoneId,
		spoiler: SpoilerSnapshot,
		hidden: CalendarHiddenFilter?,
		requested: Set<String>,
		forceOnlyRequested: Boolean,
		limit: Int = COMING_UP_LIMIT,
	): List<ComingUpEntry> {
		fun List<CalendarEvent>.usable() = filter { libraryIds(it).isNotEmpty() && hidden?.isHidden(it) != true }
		val agenda = CalendarLogic.agenda(
			events = events.usable(),
			start = today,
			zone = zone,
			filter = CalendarFilter(),
			requested = requested,
			forceOnlyRequested = forceOnlyRequested,
			hideDate = { CalendarLogic.protection(it, spoiler).hideAirDate },
			upcoming = upcoming?.usable(),
		)
		val end = today.plusDays(COMING_UP_DAYS)
		val dated = agenda.days
			.filter { (day, _) -> day.isBefore(end) }
			.flatMap { (day, dayEvents) -> dayEvents.map { ComingUpEntry(it, day, libraryIds(it)) } }
		val undated = agenda.undated.map { ComingUpEntry(it, null, libraryIds(it)) }
		val seenMovies = HashSet<String>()
		return (dated + undated)
			.filter { !it.event.isMovie || seenMovies.add(it.libraryIds.first()) }
			.take(limit)
	}

	/** The library ids of a release, best first: the episode, then the movie or series. Normalised, no blanks. */
	fun libraryIds(event: CalendarEvent): List<String> =
		listOfNotNull(event.libraryEpisodeId, event.libraryItemId)
			.filter { it.isNotBlank() }
			.map(::normalizeItemId)
			.distinct()

	/** The day label of a card: none when withheld, otherwise relative to [today] within the week. */
	fun dayLabel(day: LocalDate?, today: LocalDate): DayLabel = when (day) {
		null -> DayLabel.NONE
		today -> DayLabel.TODAY
		today.plusDays(1) -> DayLabel.TOMORROW
		else -> DayLabel.Weekday(day.dayOfWeek)
	}

	/** `S02E07` for an episode release with both numbers, null otherwise. Never the episode title. */
	fun episodeCode(event: CalendarEvent): String? {
		if (event.isMovie) return null
		val season = event.seasonNumber ?: return null
		val episode = event.episodeNumber ?: return null
		return "S%02dE%02d".format(season, episode)
	}

	/**
	 * The bookmark cards: newest bookmarks first, only those whose item the server still returns for this user (gone
	 * or inaccessible ones belong on the Bookmarks page, where they can be deleted), named by [BookmarkLogic.name] so
	 * a guarded episode never shows its saved title.
	 */
	fun jumpBackIn(
		entries: List<BookmarkEntry>,
		items: Map<String, BookmarkedItem>,
		spoiler: SpoilerSnapshot,
		limit: Int = JUMP_BACK_IN_LIMIT,
	): List<JumpBackInEntry> = entries
		.mapNotNull { entry ->
			val item = items[normalizeItemId(entry.bookmark.itemId)] ?: return@mapNotNull null
			JumpBackInEntry(entry, item, BookmarkLogic.name(entry.bookmark, item, spoiler))
		}
		.take(limit)

	/** What the bookmark name logic needs to know about a library item, from the server's DTO. */
	fun bookmarkedItem(item: BaseItemDto): BookmarkedItem {
		val isEpisode = item.type == BaseItemKind.EPISODE
		return BookmarkedItem(
			id = item.id,
			displayName = BookmarkLogic.displayName(
				name = item.name,
				seriesName = if (isEpisode) item.seriesName else null,
				season = if (isEpisode) item.parentIndexNumber else null,
				episode = if (isEpisode) item.indexNumber else null,
			),
			posterUrl = null,
			isEpisode = isEpisode,
			seriesId = if (isEpisode) item.seriesId?.toString() else null,
			seriesName = if (isEpisode) item.seriesName else null,
			seasonNumber = if (isEpisode) item.parentIndexNumber else null,
			episodeNumber = if (isEpisode) item.indexNumber else null,
			played = isEpisode && item.userData?.played == true,
		)
	}

	/**
	 * The Trending cards: discovery-filtered Seerr results (hidden titles already gone) without anything the library
	 * already has, nor titles the user put under Spoiler Guard before they reached the library.
	 */
	fun trending(results: List<SeerrResult>, spoiler: SpoilerSnapshot, limit: Int = TRENDING_LIMIT): List<SeerrResult> {
		val seen = HashSet<String>()
		return results
			.filter { seen.add(it.dedupeKey) }
			.filter { !inLibrary(it) }
			.filter { !spoiler.isPending(it.mediaType ?: "movie", it.id) }
			.take(limit)
	}

	/**
	 * Whether the library has the title: Seerr links it to a Jellyfin item. "Available" without that link is a stale
	 * entry (the item was deleted, see [SeerrMediaStatus.effective]), which can be requested again.
	 */
	fun inLibrary(result: SeerrResult): Boolean {
		val media = result.mediaInfo ?: return false
		return !media.jellyfinMediaId.isNullOrBlank() || !media.jellyfinMediaId4k.isNullOrBlank()
	}

	/**
	 * The Catch Up cards: unwatched titles not dismissed from the deck on this device, in the server's random order.
	 * Shows count as unwatched while any episode is.
	 */
	fun catchUp(items: List<BaseItemDto>, dismissed: Set<String>, limit: Int = CATCH_UP_LIMIT): List<BaseItemDto> = items
		.filter { normalizeItemId(it.id.toString()) !in dismissed }
		.filter { item ->
			val userData = item.userData
			when {
				userData == null -> true
				item.type == BaseItemKind.SERIES -> userData.unplayedItemCount?.let { it > 0 } ?: !userData.played
				else -> !userData.played
			}
		}
		.distinctBy { it.id }
		.take(limit)
}

package org.jellyfin.androidtv.enhanced.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.bookmarks.Bookmark
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkEntry
import org.jellyfin.androidtv.enhanced.bookmarks.NeutralEpisode
import org.jellyfin.androidtv.enhanced.calendar.CalendarEvent
import org.jellyfin.androidtv.enhanced.calendar.CalendarHiddenFilter
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SeerrUserStatus
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardPrefs
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.data.SpoilerPolicy
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaInfo
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val SERIES_ID = "326d7084082700b220b683fc0dc0ae78"
private const val EPISODE_ID = "fd8067ea9ef35106714e66075a2df7ae"
private const val MOVIE_ID = "0b1c2d3e4f5061728394a5b6c7d8e9f0"

private fun guardedSnapshot(state: SpoilerGuardState?, prefs: SpoilerGuardPrefs = SpoilerGuardPrefs()) =
	SpoilerSnapshot(enabled = true, state = state, policy = SpoilerPolicy.of(EnhancedPublicConfig(spoilerBlurEnabled = true), prefs), collectionMovieIds = emptySet())

private fun episode(
	eventId: String,
	date: String,
	season: Int = 2,
	number: Int = 7,
	seriesId: String? = SERIES_ID,
	episodeId: String? = null,
	monitored: Boolean = true,
	title: String = "Scrubs",
) = CalendarEvent(
	eventId = eventId,
	source = "Sonarr",
	type = "Series",
	title = title,
	releaseDate = date,
	releaseType = "Episode",
	monitored = monitored,
	libraryItemId = seriesId,
	libraryEpisodeId = episodeId,
	seasonNumber = season,
	episodeNumber = number,
	episodeTitle = "My Secret Title",
	tmdbId = 295778,
)

private fun movie(eventId: String, date: String, releaseType: String = "DigitalRelease", libraryId: String? = MOVIE_ID, tmdbId: Int = 1361955) =
	CalendarEvent(
		eventId = eventId,
		source = "Radarr",
		type = "Movie",
		title = "Some Movie",
		releaseDate = date,
		releaseType = releaseType,
		libraryItemId = libraryId,
		tmdbId = tmdbId,
	)

private fun userData(id: UUID, played: Boolean, unplayed: Int? = null) =
	UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = false, played = played, key = "", itemId = id, unplayedItemCount = unplayed)

class HomeRowsLogicTest : FunSpec({
	val today = LocalDate.of(2026, 11, 3) // a Tuesday
	val zone = ZoneOffset.UTC

	test("rows show only when turned on, offered by the server and the plugin is there, in fixed order") {
		val config = EnhancedPublicConfig(
			calendarPageEnabled = true,
			bookmarksEnabled = true,
			recommendationsPageEnabled = true,
			jellyseerrEnabled = true,
			catchUpEnabled = true,
		)
		val linked = SeerrUserStatus(active = true, userFound = true)
		val state = EnhancedState(available = true, config = config, seerr = linked)
		val all = HomeRowKind.entries.toSet()

		HomeRowKind.shown(all, state) shouldContainExactly listOf(
			HomeRowKind.COMING_UP, HomeRowKind.JUMP_BACK_IN, HomeRowKind.TRENDING, HomeRowKind.CATCH_UP,
		)
		HomeRowKind.shown(setOf(HomeRowKind.CATCH_UP, HomeRowKind.COMING_UP), state) shouldContainExactly
			listOf(HomeRowKind.COMING_UP, HomeRowKind.CATCH_UP)
		HomeRowKind.shown(emptySet(), state).shouldBeEmpty()
		// Stock server, or the configuration couldn't be read
		HomeRowKind.shown(all, EnhancedState()).shouldBeEmpty()
		HomeRowKind.shown(all, EnhancedState(configUnavailable = true)).shouldBeEmpty()
		// Trending needs a linked Seerr user; each row follows its own feature
		HomeRowKind.shown(all, state.copy(seerr = SeerrUserStatus())) shouldContainExactly
			listOf(HomeRowKind.COMING_UP, HomeRowKind.JUMP_BACK_IN, HomeRowKind.CATCH_UP)
		HomeRowKind.shown(all, state.copy(config = config.copy(calendarPageEnabled = false, catchUpEnabled = false))) shouldContainExactly
			listOf(HomeRowKind.JUMP_BACK_IN, HomeRowKind.TRENDING)
	}

	test("preference keys round-trip and are stable") {
		HomeRowKind.entries.forEach { HomeRowKind.fromKey(it.key) shouldBe it }
		HomeRowKind.fromKey("something-else") shouldBe null
		HomeRowsStore.key("ABCD-EF", "12-34") shouldBe "rows_abcdef_1234"
	}

	test("coming up: library-mapped, monitored releases of the next seven days in air order") {
		val events = listOf(
			movie("m-1", "2026-11-05T00:00:00Z"),
			episode("e-today", "2026-11-03T20:00:00Z", episodeId = EPISODE_ID),
			episode("e-tomorrow", "2026-11-04T20:00:00Z", number = 8),
			// Not in the library
			episode("e-unmapped", "2026-11-04T21:00:00Z", seriesId = null),
			// Unmonitored, like the calendar page leaves it out
			episode("e-unmonitored", "2026-11-05T20:00:00Z", number = 9, monitored = false),
			// Eighth day
			episode("e-later", "2026-11-10T20:00:00Z", number = 10),
			// Already aired
			episode("e-past", "2026-11-02T20:00:00Z", number = 6),
		)
		val entries = HomeRowsLogic.comingUp(events, null, today, zone, SpoilerSnapshot.DISABLED, null, emptySet(), false)

		entries.map { it.event.eventId } shouldContainExactly listOf("e-today", "e-tomorrow", "m-1")
		entries.map { it.day } shouldContainExactly listOf(today, today.plusDays(1), today.plusDays(2))
		// The episode itself first, its series as fallback
		entries.first().libraryIds shouldContainExactly listOf(EPISODE_ID, SERIES_ID)
		entries[1].libraryIds shouldContainExactly listOf(SERIES_ID)
	}

	test("coming up: a movie's several releases count once, and the limit applies") {
		val events = listOf(
			movie("m-cinema", "2026-11-04T00:00:00Z", releaseType = "CinemaRelease"),
			movie("m-digital", "2026-11-06T00:00:00Z"),
			movie("other", "2026-11-06T00:00:00Z", libraryId = "ffffffffffffffffffffffffffffffff", tmdbId = 7),
		)
		HomeRowsLogic.comingUp(events, null, today, zone, SpoilerSnapshot.DISABLED, null, emptySet(), false)
			.map { it.event.eventId } shouldContainExactly listOf("m-cinema", "other")

		val many = (1..40).map { episode("e-$it", "2026-11-04T20:00:00Z", number = it) }
		HomeRowsLogic.comingUp(many, null, today, zone, SpoilerSnapshot.DISABLED, null, emptySet(), false).size shouldBe HomeRowsLogic.COMING_UP_LIMIT
	}

	test("coming up: Hidden Content's calendar filter and enforced requests apply") {
		val events = listOf(
			episode("e-1", "2026-11-04T20:00:00Z"),
			movie("m-1", "2026-11-05T00:00:00Z"),
		)
		val hidden = CalendarHiddenFilter.fromEntries(
			listOf(JsonObject(mapOf("ItemId" to JsonPrimitive(SERIES_ID.uppercase()), "HideScope" to JsonPrimitive("global"))))
		)
		HomeRowsLogic.comingUp(events, null, today, zone, SpoilerSnapshot.DISABLED, hidden, emptySet(), false)
			.map { it.event.eventId } shouldContainExactly listOf("m-1")

		HomeRowsLogic.comingUp(events, null, today, zone, SpoilerSnapshot.DISABLED, null, setOf("movie:1361955"), forceOnlyRequested = true)
			.map { it.event.eventId } shouldContainExactly listOf("m-1")
	}

	test("coming up: guarded titles with hidden air dates come from the fixed range, last and without a day") {
		val guarded = guardedSnapshot(SpoilerGuardState(series = mapOf(SERIES_ID to JsonPrimitive(true))))
		val airing = episode("e-guarded", "2026-11-04T20:00:00Z")
		val farOut = episode("e-guarded-later", "2026-12-20T20:00:00Z", number = 12)
		val events = listOf(airing, movie("m-1", "2026-11-05T00:00:00Z"))

		// Without the upcoming list it is left out entirely, never dated
		HomeRowsLogic.comingUp(events, null, today, zone, guarded, null, emptySet(), false)
			.map { it.event.eventId } shouldContainExactly listOf("m-1")

		val entries = HomeRowsLogic.comingUp(events, listOf(farOut, airing), today, zone, guarded, null, emptySet(), false)
		entries.map { it.event.eventId } shouldContainExactly listOf("m-1", "e-guarded", "e-guarded-later")
		entries.drop(1).map { it.day } shouldContainExactly listOf(null, null)

		// The user chose to see air dates: dated like any other release
		val datesShown = guardedSnapshot(guarded.state, SpoilerGuardPrefs(hideAirDate = false))
		HomeRowsLogic.comingUp(events, listOf(farOut, airing), today, zone, datesShown, null, emptySet(), false)
			.map { it.day } shouldContainExactly listOf(today.plusDays(1), today.plusDays(2))
	}

	test("coming up: unknown Spoiler Guard state withholds every date") {
		val events = listOf(episode("e-1", "2026-11-04T20:00:00Z"), movie("m-1", "2026-11-05T00:00:00Z"))
		HomeRowsLogic.comingUp(events, events, today, zone, SpoilerSnapshot.UNKNOWN, null, emptySet(), false)
			.map { it.day } shouldContainExactly listOf(null, null)
	}

	test("card labels: relative day, weekday, episode code without the title") {
		HomeRowsLogic.dayLabel(null, today) shouldBe DayLabel.NONE
		HomeRowsLogic.dayLabel(today, today) shouldBe DayLabel.TODAY
		HomeRowsLogic.dayLabel(today.plusDays(1), today) shouldBe DayLabel.TOMORROW
		HomeRowsLogic.dayLabel(today.plusDays(2), today) shouldBe DayLabel.Weekday(DayOfWeek.THURSDAY)

		HomeRowsLogic.episodeCode(episode("e", "2026-11-04T20:00:00Z", season = 2, number = 7)) shouldBe "S02E07"
		HomeRowsLogic.episodeCode(episode("e", "2026-11-04T20:00:00Z").copy(episodeNumber = null)) shouldBe null
		HomeRowsLogic.episodeCode(movie("m", "2026-11-04T00:00:00Z")) shouldBe null
		HomeRowsLogic.libraryIds(movie("m", "x", libraryId = "326D7084-0827-00B2-20B6-83FC0DC0AE78")) shouldContainExactly listOf(SERIES_ID)
		HomeRowsLogic.libraryIds(movie("m", "x", libraryId = " ")).shouldBeEmpty()
	}

	test("jump back in: resolved bookmarks only, newest first, guarded episode titles withheld") {
		val episodeId = UUID.fromString("fd8067ea-9ef3-5106-714e-66075a2df7ae")
		val seriesId = UUID.fromString("326d7084-0827-00b2-20b6-83fc0dc0ae78")
		val movieId = UUID.randomUUID()
		val episodeDto = BaseItemDto(
			id = episodeId,
			type = BaseItemKind.EPISODE,
			name = "My Secret Title",
			seriesId = seriesId,
			seriesName = "Scrubs",
			parentIndexNumber = 2,
			indexNumber = 7,
			userData = userData(episodeId, played = false),
		)
		val movieDto = BaseItemDto(id = movieId, type = BaseItemKind.MOVIE, name = "Some Movie")
		val entries = listOf(
			BookmarkEntry("Bm_new", Bookmark(itemId = EPISODE_ID, mediaType = "tv", name = "My Secret Title", timestampSeconds = 90.0)),
			BookmarkEntry("Bm_gone", Bookmark(itemId = "00000000000000000000000000000001", mediaType = "movie", timestampSeconds = 5.0)),
			BookmarkEntry("Bm_old", Bookmark(itemId = movieId.toString(), mediaType = "movie", timestampSeconds = 600.0)),
		)
		val items = listOf(episodeDto, movieDto).associate { it.id.toString().replace("-", "") to HomeRowsLogic.bookmarkedItem(it) }

		val open = HomeRowsLogic.jumpBackIn(entries, items, SpoilerSnapshot.DISABLED)
		open.map { it.entry.bookmarkId } shouldContainExactly listOf("Bm_new", "Bm_old")
		open.map { it.name.text } shouldContainExactly listOf("Scrubs · S2E7 · My Secret Title", "Some Movie")

		val guarded = guardedSnapshot(SpoilerGuardState(series = mapOf(SERIES_ID to JsonPrimitive(true))))
		val withheld = HomeRowsLogic.jumpBackIn(entries, items, guarded)
		withheld.first().name.text shouldBe null
		withheld.first().name.neutral shouldBe NeutralEpisode("Scrubs", 2, 7)
		withheld.last().name.text shouldBe "Some Movie"

		HomeRowsLogic.jumpBackIn(entries, items, SpoilerSnapshot.DISABLED, limit = 1).map { it.entry.bookmarkId } shouldContainExactly listOf("Bm_new")
	}

	test("bookmarked item from the server's DTO") {
		val id = UUID.randomUUID()
		val seriesId = UUID.randomUUID()
		val played = HomeRowsLogic.bookmarkedItem(
			BaseItemDto(id = id, type = BaseItemKind.EPISODE, name = "Ep", seriesId = seriesId, seriesName = "Show", parentIndexNumber = 1, indexNumber = 3, userData = userData(id, played = true))
		)
		played.isEpisode shouldBe true
		played.seriesId shouldBe seriesId.toString()
		played.played shouldBe true
		played.displayName shouldBe "Show · S1E3 · Ep"

		val movieItem = HomeRowsLogic.bookmarkedItem(BaseItemDto(id = id, type = BaseItemKind.MOVIE, name = "Film", seriesName = "ignored", userData = userData(id, played = true)))
		movieItem.isEpisode shouldBe false
		movieItem.seriesName shouldBe null
		movieItem.played shouldBe false
		movieItem.displayName shouldBe "Film"
	}

	test("trending: no library titles, no pending guarded titles, no duplicates, at most 20") {
		val inLibrary = SeerrResult(id = 1, mediaType = "movie", mediaInfo = SeerrMediaInfo(jellyfinMediaId = MOVIE_ID))
		val in4k = SeerrResult(id = 2, mediaType = "movie", mediaInfo = SeerrMediaInfo(jellyfinMediaId4k = MOVIE_ID))
		val available = SeerrResult(id = 3, mediaType = "tv", mediaInfo = SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE, jellyfinMediaId = SERIES_ID))
		// "Available" without a library link: deleted since, requestable again
		val stale = SeerrResult(id = 7, mediaType = "tv", mediaInfo = SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE))
		val requested = SeerrResult(id = 4, mediaType = "tv", mediaInfo = SeerrMediaInfo(status = SeerrMediaStatus.PENDING))
		val fresh = SeerrResult(id = 5, mediaType = "movie")
		val pending = SeerrResult(id = 6, mediaType = "tv")
		val sameIdOtherType = SeerrResult(id = 5, mediaType = "tv")
		val results = listOf(inLibrary, in4k, available, stale, requested, fresh, pending, fresh, sameIdOtherType)

		HomeRowsLogic.trending(results, SpoilerSnapshot.DISABLED) shouldContainExactly listOf(stale, requested, fresh, pending, sameIdOtherType)
		val guarded = guardedSnapshot(SpoilerGuardState(pendingTmdb = mapOf("tv:6" to JsonPrimitive(true))))
		HomeRowsLogic.trending(results, guarded) shouldContainExactly listOf(stale, requested, fresh, sameIdOtherType)

		val many = (100..140).map { SeerrResult(id = it, mediaType = "movie") }
		HomeRowsLogic.trending(many, SpoilerSnapshot.DISABLED).size shouldBe HomeRowsLogic.TRENDING_LIMIT
	}

	test("catch up: unwatched, not dismissed, at most 10, in the server's order") {
		fun movieItem(played: Boolean? = false) = UUID.randomUUID().let { id ->
			BaseItemDto(id = id, type = BaseItemKind.MOVIE, userData = played?.let { userData(id, it) })
		}
		fun showItem(unplayed: Int?, played: Boolean = false) = UUID.randomUUID().let { id ->
			BaseItemDto(id = id, type = BaseItemKind.SERIES, userData = userData(id, played, unplayed))
		}
		val dismissed = movieItem()
		val watched = movieItem(played = true)
		val noUserData = movieItem(played = null)
		val showWithEpisodesLeft = showItem(unplayed = 3, played = false)
		val showDone = showItem(unplayed = 0)
		val showUnknownCount = showItem(unplayed = null, played = false)
		val keep = movieItem()
		val items = listOf(dismissed, watched, noUserData, showWithEpisodesLeft, showDone, showUnknownCount, keep, keep)

		HomeRowsLogic.catchUp(items, setOf(dismissed.id.toString().replace("-", ""))) shouldContainExactly
			listOf(noUserData, showWithEpisodesLeft, showUnknownCount, keep)

		val many = (1..15).map { movieItem() }
		HomeRowsLogic.catchUp(many, emptySet()) shouldContainExactly many.take(HomeRowsLogic.CATCH_UP_LIMIT)
	}
})

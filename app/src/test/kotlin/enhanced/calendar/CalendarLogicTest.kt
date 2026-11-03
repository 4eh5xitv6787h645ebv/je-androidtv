package org.jellyfin.androidtv.enhanced.calendar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarLogicTest : FunSpec({
	// Trimmed from a live `arr/calendar` response
	val json = """
		{"events":[
		 {"id":"28510","source":"Sonarr","type":"Series","title":"Scrubs (2026)","subtitle":"S02E01 - My First Time in a While",
		  "releaseDate":"2026-10-01T00:00:00.0000000Z","releaseType":"Episode","hasFile":true,"monitored":true,
		  "posterUrl":"https://artworks.thetvdb.com/p.jpg","itemId":"326d7084082700b220b683fc0dc0ae78",
		  "itemEpisodeId":"fd8067ea9ef35106714e66075a2df7ae","overview":"J.D. enlists Turk...","tvdbId":465690,
		  "seasonNumber":2,"episodeNumber":1,"episodeTitle":"My First Time in a While","seriesId":94,"tmdbId":295778,
		  "instanceName":"Sonarr"},
		 {"id":"465-DigitalRelease","source":"Radarr","type":"Movie","title":"I Hate Myself and Want to Die","subtitle":"2024",
		  "releaseDate":"2026-10-09T00:00:00.0000000Z","releaseType":"DigitalRelease","hasFile":false,"monitored":true,
		  "tmdbId":1361955,"instanceName":"Radarr"},
		 {"id":"38613","source":"Sonarr","type":"Series","title":"Love Island VIP (DE)","subtitle":"S03E15 - TBA",
		  "releaseDate":"2026-10-03T23:00:00.0000000Z","releaseType":"Episode","hasFile":false,"monitored":false,
		  "seasonNumber":3,"episodeNumber":15,"episodeTitle":"TBA","tmdbId":0}
		],"errors":[{"instanceName":"Shoko","source":"Shoko","reason":"unreachable"}]}
	""".trimIndent()
	val response = EnhancedApi.json.decodeFromString<CalendarResponse>(json)
	val (episode, movie, unmonitored) = response.events

	test("decodes events with distinct source, library and TMDB ids") {
		response.events.size shouldBe 3
		episode.eventId shouldBe "28510"
		episode.libraryItemId shouldBe "326d7084082700b220b683fc0dc0ae78"
		episode.libraryEpisodeId shouldBe "fd8067ea9ef35106714e66075a2df7ae"
		episode.validTmdbId shouldBe 295778
		episode.mediaType shouldBe "tv"
		movie.isMovie shouldBe true
		movie.mediaType shouldBe "movie"
		movie.requestKey shouldBe "movie:1361955"
		unmonitored.validTmdbId shouldBe null
		unmonitored.requestKey shouldBe null
		response.errors.single().reason shouldBe "unreachable"
	}

	test("movie dates stay on their UTC day west of UTC, episodes move to local time") {
		val newYork = ZoneId.of("America/New_York")
		movie.day(newYork) shouldBe LocalDate.of(2026, 10, 9)
		episode.day(newYork) shouldBe LocalDate.of(2026, 9, 30)
		episode.day(ZoneOffset.UTC) shouldBe LocalDate.of(2026, 10, 1)
	}

	test("agenda groups by day, drops unmonitored and out-of-window events") {
		val agenda = CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 1), ZoneOffset.UTC, CalendarFilter(), emptySet(), false)
		agenda.map { it.first } shouldContainExactly listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))
		CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 2), ZoneOffset.UTC, CalendarFilter(), emptySet(), false)
			.flatMap { it.second } shouldContainExactly listOf(movie)
	}

	test("filters combine across groups and treat empty groups as any") {
		val requested = setOf("tv:295778")
		CalendarFilter(kinds = setOf(CalendarFilter.Kind.MOVIE)).matches(movie, requested) shouldBe true
		CalendarFilter(kinds = setOf(CalendarFilter.Kind.MOVIE)).matches(episode, requested) shouldBe false
		CalendarFilter(sources = setOf("sonarr")).matches(episode, requested) shouldBe true
		CalendarFilter(hasFileOnly = true).matches(movie, requested) shouldBe false
		CalendarFilter(requestedOnly = true).matches(episode, requested) shouldBe true
		CalendarFilter(requestedOnly = true, kinds = setOf(CalendarFilter.Kind.EPISODE)).matches(movie, requested) shouldBe false
	}

	test("forced only-requested keeps just requested titles") {
		val agenda = CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 1), ZoneOffset.UTC, CalendarFilter(), setOf("movie:1361955"), true)
		agenda.flatMap { it.second } shouldContainExactly listOf(movie)
	}

	test("requests decode into type:tmdb keys") {
		val page = EnhancedApi.json.decodeFromString<CalendarRequestsPage>(
			"""{"totalPages":2,"requests":[{"id":185,"type":"tv","tmdbId":79680},{"type":"movie"},{"type":"Movie","tmdbId":5}]}"""
		)
		page.totalPages shouldBe 2
		page.requests.mapNotNull { it.key } shouldContainExactly listOf("tv:79680", "movie:5")
	}

	test("week navigation snaps to the configured first day") {
		val tuesday = LocalDate.of(2026, 11, 3)
		CalendarLogic.firstDayOfWeek("Sunday") shouldBe DayOfWeek.SUNDAY
		CalendarLogic.firstDayOfWeek("monday") shouldBe DayOfWeek.MONDAY
		CalendarLogic.firstDayOfWeek(null) shouldBe DayOfWeek.MONDAY
		CalendarLogic.firstDayOfWeek("1") shouldBe DayOfWeek.MONDAY
		CalendarLogic.shiftWeeks(tuesday, 1, DayOfWeek.MONDAY) shouldBe LocalDate.of(2026, 11, 9)
		CalendarLogic.shiftWeeks(tuesday, -1, DayOfWeek.MONDAY) shouldBe LocalDate.of(2026, 10, 26)
		CalendarLogic.shiftWeeks(tuesday, 1, DayOfWeek.SUNDAY) shouldBe LocalDate.of(2026, 11, 8)
	}

	test("query range covers the window with a day of slack each side") {
		CalendarLogic.queryRange(LocalDate.of(2026, 11, 3), ZoneOffset.UTC) shouldBe ("2026-11-02T00:00:00Z" to "2026-11-18T00:00:00Z")
	}

	test("guarded shows lose episode titles; unknown guard state withholds every episode") {
		val guarded = SpoilerGuardState(series = mapOf("326D7084-0827-00B2-20B6-83FC0DC0AE78" to JsonPrimitive(true)))
		CalendarLogic.isSpoilerGuarded(episode, true, guarded) shouldBe true
		CalendarLogic.isSpoilerGuarded(episode, true, SpoilerGuardState()) shouldBe false
		CalendarLogic.isSpoilerGuarded(episode, false, guarded) shouldBe false
		CalendarLogic.isSpoilerGuarded(episode, true, null) shouldBe true
		CalendarLogic.isSpoilerGuarded(movie, true, null) shouldBe false
		val pending = SpoilerGuardState(pendingTmdb = mapOf("tv:295778" to JsonPrimitive(true)))
		CalendarLogic.isSpoilerGuarded(episode.copy(libraryItemId = null), true, pending) shouldBe true

		CalendarLogic.subtitle(episode, guarded = true) shouldBe "S02E01"
		CalendarLogic.subtitle(episode, guarded = false) shouldBe "S02E01 · My First Time in a While"
		CalendarLogic.subtitle(unmonitored, guarded = false) shouldBe "S03E15"
		CalendarLogic.subtitle(movie, guarded = true) shouldBe "2024"
	}

	test("time format follows the plugin's option") {
		CalendarLogic.uses12HourClock("5pm/5:30pm") shouldBe true
		CalendarLogic.uses12HourClock("17:00/17:30") shouldBe false
		CalendarLogic.uses12HourClock(null) shouldBe false
	}
})

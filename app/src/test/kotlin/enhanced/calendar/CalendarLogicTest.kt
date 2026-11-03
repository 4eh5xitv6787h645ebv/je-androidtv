package org.jellyfin.androidtv.enhanced.calendar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.Protection
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardPrefs
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.data.SpoilerPolicy
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private fun snapshot(state: SpoilerGuardState?, prefs: SpoilerGuardPrefs = SpoilerGuardPrefs(), members: Set<String>? = emptySet()) =
	SpoilerSnapshot(enabled = true, state = state, policy = SpoilerPolicy.of(EnhancedPublicConfig(spoilerBlurEnabled = true), prefs), collectionMovieIds = members)

private fun hiddenEntry(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (key, value) -> key to JsonPrimitive(value) })

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
		agenda.days.map { it.first } shouldContainExactly listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))
		agenda.undated shouldBe emptyList()
		CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 2), ZoneOffset.UTC, CalendarFilter(), emptySet(), false)
			.days.flatMap { it.second } shouldContainExactly listOf(movie)
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
		agenda.days.flatMap { it.second } shouldContainExactly listOf(movie)
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

	test("guarded titles follow the category policy; unknown guard state withholds everything") {
		val guarded = SpoilerGuardState(series = mapOf("326D7084-0827-00B2-20B6-83FC0DC0AE78" to JsonPrimitive(true)))
		CalendarLogic.protection(episode, snapshot(guarded)).guarded shouldBe true
		CalendarLogic.protection(episode, snapshot(SpoilerGuardState())) shouldBe Protection.NONE
		CalendarLogic.protection(episode, SpoilerSnapshot.DISABLED) shouldBe Protection.NONE
		CalendarLogic.protection(episode, snapshot(null)) shouldBe Protection.FULL
		// Movies are covered too, including when the guard state is unknown
		CalendarLogic.protection(movie, snapshot(null)) shouldBe Protection.FULL
		val pendingMovie = SpoilerGuardState(pendingTmdb = mapOf("movie:1361955" to JsonPrimitive(true)))
		CalendarLogic.protection(movie, snapshot(pendingMovie)).hideArtwork shouldBe true
		val pending = SpoilerGuardState(pendingTmdb = mapOf("tv:295778" to JsonPrimitive(true)))
		CalendarLogic.protection(episode.copy(libraryItemId = null), snapshot(pending)).guarded shouldBe true

		val full = CalendarLogic.protection(episode, snapshot(guarded))
		CalendarLogic.subtitle(episode, full) shouldBe "S02E01"
		CalendarLogic.subtitle(episode, Protection.NONE) shouldBe "S02E01 · My First Time in a While"
		CalendarLogic.subtitle(unmonitored, Protection.NONE) shouldBe "S03E15"
		CalendarLogic.subtitle(movie, Protection.FULL) shouldBe "2024"
		// The user kept real episode titles but still hides air dates
		val titlesKept = CalendarLogic.protection(episode, snapshot(guarded, SpoilerGuardPrefs(replaceEpisodeTitles = false)))
		titlesKept.hideAirDate shouldBe true
		CalendarLogic.subtitle(episode, titlesKept) shouldBe "S02E01 · My First Time in a While"
	}

	test("protected air dates never depend on the window") {
		val guarded = snapshot(SpoilerGuardState(series = mapOf("326d7084082700b220b683fc0dc0ae78" to JsonPrimitive(true))))
		val hideDate = { event: CalendarEvent -> CalendarLogic.protection(event, guarded).hideAirDate }
		// The episode airs on 1 October; the fixed range from today has it whichever week is shown
		val upcoming = listOf(episode, movie)
		val airing = CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 1), ZoneOffset.UTC, CalendarFilter(), emptySet(), false, hideDate, upcoming)
		airing.days.flatMap { it.second } shouldContainExactly listOf(movie)
		airing.undated shouldContainExactly listOf(episode)
		val otherWeek = CalendarLogic.agenda(emptyList(), LocalDate.of(2026, 10, 29), ZoneOffset.UTC, CalendarFilter(), emptySet(), false, hideDate, upcoming)
		otherWeek.days shouldBe emptyList()
		otherWeek.undated shouldBe airing.undated

		// Without the upcoming list a protected release is left out, never dated
		val notLoaded = CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 1), ZoneOffset.UTC, CalendarFilter(), emptySet(), false, hideDate)
		notLoaded.days.flatMap { it.second } shouldContainExactly listOf(movie)
		notLoaded.undated shouldBe emptyList()

		// Listed once, and the chips apply to the undated group too
		CalendarLogic.agenda(emptyList(), LocalDate.of(2026, 10, 1), ZoneOffset.UTC, CalendarFilter(), emptySet(), false, hideDate, listOf(episode, episode.copy()))
			.undated shouldContainExactly listOf(episode)
		CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 1), ZoneOffset.UTC, CalendarFilter(kinds = setOf(CalendarFilter.Kind.MOVIE)), emptySet(), false, hideDate, upcoming)
			.undated shouldBe emptyList()

		// Kept on its day when the user lets air dates through
		val datesShown = snapshot(guarded.state, SpoilerGuardPrefs(hideAirDate = false))
		CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 1), ZoneOffset.UTC, CalendarFilter(), emptySet(), false, { CalendarLogic.protection(it, datesShown).hideAirDate }, upcoming)
			.let { agenda ->
				agenda.days.flatMap { it.second } shouldContainExactly listOf(episode, movie)
				agenda.undated shouldBe emptyList()
			}

		// Unknown state: nothing is dated, everything upcoming and monitored is listed without dates
		val unknown = CalendarLogic.agenda(response.events, LocalDate.of(2026, 10, 2), ZoneOffset.UTC, CalendarFilter(), emptySet(), false, {
			CalendarLogic.protection(it, snapshot(null)).hideAirDate
		}, response.events)
		unknown.days shouldBe emptyList()
		unknown.undated shouldContainExactly listOf(movie, episode)
	}

	test("the undated group is only loaded when a date can be hidden, from a fixed range") {
		val guarded = SpoilerGuardState(series = mapOf("326d7084082700b220b683fc0dc0ae78" to JsonPrimitive(true)))
		CalendarLogic.mayHideDates(SpoilerSnapshot.DISABLED) shouldBe false
		CalendarLogic.mayHideDates(snapshot(guarded)) shouldBe true
		CalendarLogic.mayHideDates(snapshot(guarded, SpoilerGuardPrefs(hideAirDate = false))) shouldBe false
		// Lists unknown: unresolved titles are fully protected whatever the policy
		CalendarLogic.mayHideDates(snapshot(null, SpoilerGuardPrefs(hideAirDate = false))) shouldBe true
		CalendarLogic.mayHideDates(snapshot(guarded, SpoilerGuardPrefs(hideAirDate = false), members = null)) shouldBe true
		CalendarLogic.mayHideDates(SpoilerSnapshot.UNKNOWN) shouldBe true
		CalendarLogic.upcomingRange(LocalDate.of(2026, 10, 1), ZoneOffset.UTC) shouldBe ("2026-10-01T00:00:00Z" to "2026-12-30T00:00:00Z")
	}

	test("hidden content drops releases by TMDB id, library ids or name, like the web") {
		val filter = CalendarHiddenFilter.fromEntries(listOf(
			hiddenEntry("ItemId" to "", "TmdbId" to "1361955", "Name" to "Other", "HideScope" to "global"),
			// Scoped (home row) hides don't match by id, but their names count, as on the web
			hiddenEntry("ItemId" to "326d7084082700b220b683fc0dc0ae78", "TmdbId" to "", "Name" to "Love Island VIP", "HideScope" to "nextup"),
		))
		filter.isHidden(movie) shouldBe true
		filter.isHidden(episode) shouldBe false
		// "Love Island VIP" matches "Love Island VIP (DE)" only the other way round: the qualifier is stripped from entries
		filter.isHidden(unmonitored) shouldBe false
		CalendarHiddenFilter.fromEntries(listOf(hiddenEntry("Name" to "love island vip (de)"))).isHidden(unmonitored) shouldBe true
		CalendarHiddenFilter.fromEntries(listOf(hiddenEntry("Name" to "Scrubs (2026)"))).isHidden(episode.copy(title = "Scrubs")) shouldBe true

		CalendarHiddenFilter.fromEntries(listOf(hiddenEntry("ItemId" to "326D7084-0827-00B2-20B6-83FC0DC0AE78"))).isHidden(episode) shouldBe true
		CalendarHiddenFilter.fromEntries(listOf(hiddenEntry("ItemId" to "fd8067ea9ef35106714e66075a2df7ae", "HideScope" to "global"))).isHidden(episode) shouldBe true
		// TMDB id 0 means unknown and never matches
		CalendarHiddenFilter.fromEntries(listOf(hiddenEntry("TmdbId" to "0"))).isHidden(unmonitored) shouldBe false
	}

	test("time format follows the plugin's option") {
		CalendarLogic.uses12HourClock("5pm/5:30pm") shouldBe true
		CalendarLogic.uses12HourClock("17:00/17:30") shouldBe false
		CalendarLogic.uses12HourClock(null) shouldBe false
	}
})

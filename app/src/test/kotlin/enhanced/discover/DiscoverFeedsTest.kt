package org.jellyfin.androidtv.enhanced.discover

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.seerr.SeerrDiscoverFeed
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrSort
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.seerr.sortable

private fun page(page: Int, totalPages: Int, ids: List<Int>, mediaType: String? = "movie"): JsonElement = Json.parseToJsonElement(
	"""{"page":$page,"totalPages":$totalPages,"totalResults":${totalPages * 20},"results":[${
		ids.joinToString(",") { id -> """{"id":$id${mediaType?.let { ""","mediaType":"$it"""" } ?: ""},"title":"T$id"}""" }
	}]}"""
)

private class Harness(tmdbEnabled: Boolean = false, private val respond: (String, Map<String, Any?>) -> JsonElement) {
	val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
	val api = mockk<EnhancedApi>().also { api ->
		coEvery { api.get(any(), any()) } answers {
			val path = firstArg<String>()
			val query = secondArg<Map<String, Any?>>().filterValues { it != null }
			calls += path to query
			respond(path, query)
		}
	}
	val enhancedRepository = mockk<EnhancedRepository>().also {
		val state = EnhancedState(available = true, config = EnhancedPublicConfig(tmdbEnabled = tmdbEnabled))
		every { it.current } returns state
		coEvery { it.ensureLoaded(any()) } returns state
	}
	val seerr = SeerrRepository(api, enhancedRepository, mockk(relaxed = true))
}

class DiscoverFeedsTest : FunSpec({
	test("sort presets map to Seerr's sortBy, with first_air_date for TV") {
		SeerrSort.POPULAR.sortBy("movie") shouldBe null
		SeerrSort.TOP_RATED.sortBy("tv") shouldBe "vote_average.desc"
		SeerrSort.NEWEST.sortBy("movie") shouldBe "release_date.desc"
		SeerrSort.NEWEST.sortBy("tv") shouldBe "first_air_date.desc"
		SeerrSort.OLDEST.sortBy("tv") shouldBe "first_air_date.asc"
	}

	test("only sources whose routes read sortBy are sortable") {
		SeerrSource.Discover(SeerrDiscoverFeed.TRENDING, "").sortable shouldBe false
		SeerrSource.Discover(SeerrDiscoverFeed.UPCOMING_MOVIES, "").sortable shouldBe false
		SeerrSource.Discover(SeerrDiscoverFeed.UPCOMING_TV, "").sortable shouldBe false
		SeerrSource.Discover(SeerrDiscoverFeed.POPULAR_MOVIES, "").sortable shouldBe true
		SeerrSource.DiscoverGenre("tv", 18, "").sortable shouldBe true
		SeerrSource.Network(213, null, "").sortable shouldBe true
		SeerrSource.Collection(10, "").sortable shouldBe false
		SeerrSource.Person(null, 1, "", "").sortable shouldBe false
		SeerrSource.Recommended("movie", 1, "").sortable shouldBe false
	}

	test("new sources survive the navigation argument round trip with stable type names") {
		val sources = listOf(
			SeerrSource.Discover(SeerrDiscoverFeed.UPCOMING_TV, "Upcoming"),
			SeerrSource.DiscoverGenre("movie", 28, "Action"),
			SeerrSource.Company(420, "Marvel Studios"),
			SeerrSource.Network(213, 178464, "Netflix"),
			SeerrSource.Collection(10, "Star Wars Collection"),
		)
		sources.forEach { source ->
			val encoded = Json.encodeToString(SeerrSource.serializer(), source)
			Json.decodeFromString(SeerrSource.serializer(), encoded) shouldBe source
		}
		Json.encodeToString(SeerrSource.serializer(), sources[0]) shouldBe """{"type":"discover","feed":"tv-upcoming","title":"Upcoming"}"""
	}

	test("trending and upcoming never send a sortBy, which Seerr rejects there") {
		val harness = Harness { _, _ -> page(1, 1, listOf(1)) }
		harness.seerr.feedsFor(SeerrSource.Discover(SeerrDiscoverFeed.TRENDING, ""), SeerrSort.TOP_RATED).single().load(1)
		harness.seerr.feedsFor(SeerrSource.Discover(SeerrDiscoverFeed.UPCOMING_TV, ""), SeerrSort.NEWEST).single().load(2)
		harness.calls shouldContainExactly listOf(
			"jellyseerr/discover/trending" to mapOf("page" to 1),
			"jellyseerr/discover/tv/upcoming" to mapOf("page" to 2),
		)
	}

	test("genre tiles read one media type's genre feed in the chosen order") {
		val harness = Harness { _, _ -> page(1, 1, listOf(1), mediaType = null) }
		val result = harness.seerr.feedsFor(SeerrSource.DiscoverGenre("tv", 18, "Drama"), SeerrSort.OLDEST).single().load(1)
		harness.calls shouldContainExactly listOf("jellyseerr/discover/tv/genre/18" to mapOf("page" to 1, "sortBy" to "first_air_date.asc"))
		// Feeds without mediaType get it from the route
		result.results.single().mediaType shouldBe "tv"
	}

	test("a network pages its series beside its company's movies") {
		val harness = Harness { _, _ -> page(1, 1, listOf(1)) }
		harness.seerr.feedsFor(SeerrSource.Network(213, 178464, "Netflix"), SeerrSort.NEWEST).forEach { it.load(1) }
		harness.calls shouldContainExactly listOf(
			"jellyseerr/discover/tv/network/213" to mapOf("page" to 1, "sortBy" to "first_air_date.desc"),
			"jellyseerr/discover/movies/studio/178464" to mapOf("page" to 1, "sortBy" to "release_date.desc"),
		)
		harness.seerr.feedsFor(SeerrSource.Network(2739, null, "Disney+")).size shouldBe 1
	}

	test("a studio adds its series only when the plugin can ask TMDB") {
		Harness(tmdbEnabled = false) { _, _ -> page(1, 1, emptyList()) }.seerr
			.feedsFor(SeerrSource.Company(420, "Marvel Studios")).size shouldBe 1
		val harness = Harness(tmdbEnabled = true) { _, _ -> page(1, 1, emptyList()) }
		harness.seerr.feedsFor(SeerrSource.Company(420, "Marvel Studios")).forEach { it.load(1) }
		harness.calls.map { it.first } shouldContainExactly listOf(
			"jellyseerr/discover/movies/studio/420",
			"jellyseerr/discover/tv/studio/420",
		)
	}

	test("collection parts are one page, oldest first, undated last") {
		val harness = Harness { _, _ ->
			Json.parseToJsonElement(
				"""{"id":10,"name":"C","parts":[
					{"id":3,"title":"Later","releaseDate":"2019-12-18"},
					{"id":4,"title":"Undated"},
					{"id":1,"title":"First","releaseDate":"1977-05-25","mediaType":"movie"}
				]}"""
			)
		}
		val feed = harness.seerr.feedsFor(SeerrSource.Collection(10, "C"), SeerrSort.NEWEST).single()
		val first = feed.load(1)
		first.results.map { it.id } shouldContainExactly listOf(1, 3, 4)
		first.results.map { it.mediaType }.distinct() shouldContainExactly listOf("movie")
		first.totalPages shouldBe 1
		feed.load(2).results shouldBe emptyList()
		harness.calls.single() shouldBe ("jellyseerr/collection/10" to emptyMap())
	}

	test("row previews read further pages until the row has enough cards") {
		val harness = Harness { _, query ->
			val pageNumber = query["page"] as Int
			page(pageNumber, 5, (1..4).map { pageNumber * 100 + it })
		}
		val repository = DiscoverRepository(harness.api, harness.enhancedRepository, mockk(relaxed = true), harness.seerr, mockk(relaxed = true), mockk(relaxed = true))
		val preview = repository.rowPreview(SeerrSource.Discover(SeerrDiscoverFeed.POPULAR_MOVIES, ""))
		harness.calls.map { it.second["page"] } shouldContainExactly listOf(1, 2, 3)
		preview.results.size shouldBe 12
		preview.hasMore shouldBe true
	}

	test("a row fails when its first page fails, but keeps what it has when a later one does") {
		val failing = Harness { _, _ -> throw EnhancedApi.EnhancedApiException(503, "down", serverMessage = "Seerr is down") }
		val repository = DiscoverRepository(failing.api, failing.enhancedRepository, mockk(relaxed = true), failing.seerr, mockk(relaxed = true), mockk(relaxed = true))
		shouldThrow<EnhancedApi.EnhancedApiException> {
			repository.rowPreview(SeerrSource.Discover(SeerrDiscoverFeed.TRENDING, ""))
		}.serverMessage shouldBe "Seerr is down"

		val flaky = Harness { _, query ->
			if (query["page"] == 1) page(1, 5, listOf(1, 2)) else throw EnhancedApi.EnhancedApiException(500, "boom")
		}
		val flakyRepository = DiscoverRepository(flaky.api, flaky.enhancedRepository, mockk(relaxed = true), flaky.seerr, mockk(relaxed = true), mockk(relaxed = true))
		flakyRepository.rowPreview(SeerrSource.Discover(SeerrDiscoverFeed.TRENDING, "")).results.map { it.id } shouldContainExactly listOf(1, 2)
	}

	test("genre backdrops are withheld while the user protects anything, or when that can't be checked") {
		fun repository(
			spoilerGuard: Boolean,
			enhanced: EnhancedState = EnhancedState(available = true, config = EnhancedPublicConfig(spoilerBlurEnabled = spoilerGuard)),
			state: (suspend () -> SpoilerGuardState),
		): DiscoverRepository {
			val enhancedRepository = mockk<EnhancedRepository>()
			coEvery { enhancedRepository.ensureLoaded(any()) } returns enhanced
			val spoilerGuardRepository = mockk<SpoilerGuardRepository>()
			coEvery { spoilerGuardRepository.getState() } coAnswers { state() }
			return DiscoverRepository(mockk(), enhancedRepository, mockk(), mockk(), mockk(), spoilerGuardRepository)
		}
		repository(spoilerGuard = false) { error("not asked") }.genreBackdropsAllowed() shouldBe true
		repository(spoilerGuard = true) { SpoilerGuardState() }.genreBackdropsAllowed() shouldBe true
		repository(spoilerGuard = true) { SpoilerGuardState(series = mapOf("abc" to JsonPrimitive(true))) }.genreBackdropsAllowed() shouldBe false
		repository(spoilerGuard = true) { SpoilerGuardState(pendingTmdb = mapOf("tv-1" to JsonPrimitive(true))) }.genreBackdropsAllowed() shouldBe false
		repository(spoilerGuard = true) { throw EnhancedApi.EnhancedApiException(500, "boom") }.genreBackdropsAllowed() shouldBe false
		// Unknown configuration: Spoiler Guard may be on, so the lists decide (or their failure withholds)
		val unknown = EnhancedState(configUnavailable = true)
		repository(spoilerGuard = false, enhanced = unknown) { SpoilerGuardState(movies = mapOf("abc" to JsonPrimitive(true))) }
			.genreBackdropsAllowed() shouldBe false
		repository(spoilerGuard = false, enhanced = unknown) { throw EnhancedApi.EnhancedApiException(0, "offline") }
			.genreBackdropsAllowed() shouldBe false
	}
})

package org.jellyfin.androidtv.enhanced.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaInfo
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSeasonStatus

class SeerrSearchFilterTest : FunSpec({
	fun movie(id: Int, info: SeerrMediaInfo? = null, releaseDate: String? = null) =
		SeerrResult(id = id, mediaType = "movie", title = "Movie $id", releaseDate = releaseDate, mediaInfo = info)

	fun show(id: Int, info: SeerrMediaInfo? = null) = SeerrResult(id = id, mediaType = "tv", name = "Show $id", mediaInfo = info)
	fun person(id: Int) = SeerrResult(id = id, mediaType = "person", name = "Person $id")

	test("a closed year in parentheses filters by year; a partial one is part of the title") {
		SeerrSearchQuery.parse("The Matrix (1999)") shouldBe SeerrSearchQuery("The Matrix", "1999")
		SeerrSearchQuery.parse("The Matrix(1999)  ") shouldBe SeerrSearchQuery("The Matrix", "1999")
		SeerrSearchQuery.parse("The Matrix (199") shouldBe SeerrSearchQuery("The Matrix (199", null)
		SeerrSearchQuery.parse("(1999)") shouldBe SeerrSearchQuery("(1999)", null)
		SeerrSearchQuery.parse("1917") shouldBe SeerrSearchQuery("1917", null)
	}

	test("year filter keeps only results from that year") {
		val filter = SeerrSearchFilter(year = "1999")
		filter.accepts(movie(603, releaseDate = "1999-03-30")) shouldBe true
		filter.accepts(movie(604, releaseDate = "2003-05-15")) shouldBe false
		filter.accepts(movie(605)) shouldBe false
	}

	test("hidden TMDB and Jellyfin ids drop movies and shows but never people") {
		val filter = SeerrSearchFilter(hiddenTmdbIds = setOf("603"), hiddenItemIds = setOf("abcdef0123"))
		filter.accepts(movie(603)) shouldBe false
		filter.accepts(show(603)) shouldBe false
		filter.accepts(person(603)) shouldBe true
		filter.accepts(movie(1, SeerrMediaInfo(jellyfinMediaId = "ABCDEF01-23"))) shouldBe false
		filter.accepts(movie(2, SeerrMediaInfo(jellyfinMediaId = "other"))) shouldBe true
	}

	test("library exclusion uses the cards' in-library state, not just a Jellyfin id") {
		val filter = SeerrSearchFilter(excludeLibraryItems = true)
		filter.accepts(movie(1, SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE, jellyfinMediaId = "a"))) shouldBe false
		filter.accepts(movie(2, SeerrMediaInfo(status = SeerrMediaStatus.PARTIALLY_AVAILABLE, jellyfinMediaId4k = "b"))) shouldBe false
		// Requested but not there yet, or a stale Available without a library copy
		filter.accepts(movie(3, SeerrMediaInfo(status = SeerrMediaStatus.PROCESSING, jellyfinMediaId = "c"))) shouldBe true
		filter.accepts(movie(4, SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE))) shouldBe true
		SeerrSearchFilter().accepts(movie(1, SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE, jellyfinMediaId = "a"))) shouldBe true
	}

	test("shows are in the library by their seasons") {
		fun seasons(vararg statuses: Pair<Int, Int?>) = statuses.map { (n, s) -> SeerrSeasonStatus(seasonNumber = n, status = s) }
		val inLibrary = show(1, SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE, jellyfinMediaId = "a", seasons = seasons(1 to 5, 2 to 1)))
		SeerrSearchFilter.isInLibrary(inLibrary) shouldBe true
		// A Jellyfin id with no season present doesn't count
		val stale = show(2, SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE, jellyfinMediaId = "a", seasons = seasons(1 to 1, 2 to 1)))
		SeerrSearchFilter.isInLibrary(stale) shouldBe false
		val requested = show(3, SeerrMediaInfo(status = SeerrMediaStatus.PROCESSING, jellyfinMediaId = "a", seasons = seasons(1 to 3, 2 to 1)))
		SeerrSearchFilter.isInLibrary(requested) shouldBe false
	}

	test("season analysis follows the web's overall status") {
		fun s(vararg statuses: Pair<Int, Int?>) = statuses.map { (n, st) -> SeerrSeasonStatus(seasonNumber = n, status = st) }
		SeerrSearchFilter.overallSeasonStatus(emptyList()) shouldBe SeerrMediaStatus.UNKNOWN
		SeerrSearchFilter.overallSeasonStatus(s(0 to 5)) shouldBe SeerrMediaStatus.UNKNOWN
		SeerrSearchFilter.overallSeasonStatus(s(1 to 5, 2 to 5)) shouldBe SeerrMediaStatus.AVAILABLE
		SeerrSearchFilter.overallSeasonStatus(s(1 to 5, 2 to 2)) shouldBe SeerrMediaStatus.DELETED
		SeerrSearchFilter.overallSeasonStatus(s(1 to 5, 2 to 1)) shouldBe SeerrMediaStatus.PARTIALLY_AVAILABLE
		SeerrSearchFilter.overallSeasonStatus(s(1 to 2, 2 to 1)) shouldBe SeerrMediaStatus.PROCESSING
		SeerrSearchFilter.overallSeasonStatus(s(1 to 1, 2 to 1)) shouldBe SeerrMediaStatus.UNKNOWN
		// Unrequested specials keep the show requestable
		SeerrSearchFilter.overallSeasonStatus(s(0 to 1, 1 to 5)) shouldBe SeerrMediaStatus.DELETED
	}

	test("blocklisted titles follow the blocklist flag") {
		val blocked = movie(1, SeerrMediaInfo(status = SeerrMediaStatus.BLOCKLISTED))
		SeerrSearchFilter(excludeBlocklisted = true).accepts(blocked) shouldBe false
		SeerrSearchFilter().accepts(blocked) shouldBe true
	}

	test("pages split into media and people, deduplicated across pages") {
		val seen = HashSet<String>()
		val page = listOf(movie(1), person(1), show(1), SeerrResult(id = 9, mediaType = "collection"), SeerrResult(id = 8), movie(1))
		val first = splitResults(page, SeerrSearchFilter(), includePeople = true, seen = seen)
		first.media.map { it.dedupeKey } shouldBe listOf("movie-1", "tv-1")
		first.people.map { it.tmdbPersonId } shouldBe listOf(1)

		val second = splitResults(listOf(movie(1), movie(2), person(1)), SeerrSearchFilter(), includePeople = true, seen = seen)
		second.media.map { it.id } shouldBe listOf(2)
		second.people shouldBe emptyList()

		splitResults(listOf(person(5)), SeerrSearchFilter(), includePeople = false, seen = HashSet()).people shouldBe emptyList()
	}

	test("collections keep movie order, appear once and withhold artwork of protected library movies") {
		val found = SeerrMovieCollections(
			mapOf(
				"603" to SeerrCollectionRef(2344, "The Matrix Collection", "/m.jpg"),
				"604" to SeerrCollectionRef(2344, "The Matrix Collection", "/m.jpg"),
				"10" to SeerrCollectionRef(7, "Other", "/o.jpg"),
				"11" to null,
			)
		)
		val movies = listOf(movie(10), movie(603), movie(604).copy(libraryArtwork = true), movie(11), movie(12))
		val merged = mergeCollections(emptyList(), movies, found)
		merged.map { it.tmdbCollectionId } shouldBe listOf(7, 2344)
		merged.map { it.posterWithheld } shouldBe listOf(false, true)

		// A later page doesn't duplicate a known collection
		val again = mergeCollections(merged, listOf(movie(603)), found)
		again shouldBe merged
	}

	test("movie-collections decodes null (no collection) apart from a missing key (lookup failed)") {
		val json = """{"results":{"603":{"id":2344,"name":"The Matrix Collection","posterPath":"/bV9.jpg","backdropPath":"/bRm.jpg"},"411948":null}}"""
		val decoded = EnhancedApi.json.decodeFromString(SeerrMovieCollections.serializer(), json)
		decoded.results["603"]?.id shouldBe 2344
		decoded.results.containsKey("411948") shouldBe true
		decoded.results["411948"] shouldBe null
		decoded.results.containsKey("604") shouldBe false
	}

	test("collection parts decode with unknown fields") {
		val json = """{"id":2344,"name":"The Matrix Collection","overview":"x","parts":[{"adult":false,"id":603,"mediaType":"movie","title":"The Matrix","releaseDate":"1999-03-30","video":false},{"id":604,"title":"The Matrix Reloaded"}]}"""
		val decoded = EnhancedApi.json.decodeFromString(SeerrCollection.serializer(), json)
		decoded.parts.map { it.id } shouldBe listOf(603, 604)
		decoded.parts[1].mediaType shouldBe null
	}

	test("Hidden Content's FilterSearch is read from the document and off by default") {
		val on = EnhancedApi.json.parseToJsonElement("""{"Settings":{"Enabled":true,"FilterSearch":true},"Items":{}}""").jsonObject
		HiddenContentRepository.Snapshot(on).settings.filterSearch shouldBe true
		val missing = EnhancedApi.json.parseToJsonElement("""{"Settings":{"Enabled":true},"Items":{}}""").jsonObject
		HiddenContentRepository.Snapshot(missing).settings.filterSearch shouldBe false
		EnhancedApi.json.decodeFromJsonElement<SeerrMovieCollections>(EnhancedApi.json.parseToJsonElement("{}")).results shouldBe emptyMap()
	}
})

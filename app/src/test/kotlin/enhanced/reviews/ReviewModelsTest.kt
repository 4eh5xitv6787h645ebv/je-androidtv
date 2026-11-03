package org.jellyfin.androidtv.enhanced.reviews

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

private inline fun <reified T> decode(json: String): T =
	EnhancedApi.json.decodeFromJsonElement(EnhancedApi.json.parseToJsonElement(json))

class ReviewModelsTest : FunSpec({
	val userReviews = decode<JeReviewList>(
		"""{"reviews":[
			{"userId":"aaa","userName":"alice","tmdbId":"603","mediaType":"movie","content":"Great","rating":4.5,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z"},
			{"userId":"7ef882cd307344279240d0eca8333a9f","userName":"me","tmdbId":"603","mediaType":"movie","content":"","rating":3,"createdAt":"2025-01-01T00:00:00Z","updatedAt":""},
			{"userId":"bbb","userName":"bob","tmdbId":"603","mediaType":"movie","content":"Text only","rating":null,"createdAt":"2026-03-01T00:00:00Z","updatedAt":"2026-03-01T00:00:00Z"}
		]}"""
	).reviews

	test("JE reviews decode and summarise only actual ratings") {
		userReviews.size shouldBe 3
		val summary = summarize(userReviews)
		summary.average shouldBe 3.75
		summary.count shouldBe 2
		summary.reviewCount shouldBe 3
		summarize(emptyList()).average shouldBe null
	}

	test("own review first, then newest, then TMDB reviews with text") {
		val tmdb = decode<TmdbReviewPage>(
			"""{"id":155,"page":1,"total_pages":2,"total_results":3,"results":[
				{"author":"tricksy","author_details":{"name":"","username":"tricksy","avatar_path":null,"rating":null},"content":"**Excellent** movie.<br/>Nolan is a [genius](https://example.com).","created_at":"2015-06-09T07:21:30Z","id":"557"},
				{"author":"empty","author_details":{"rating":10.0},"content":"  ","created_at":"2016-05-25T23:49:13Z","id":"574"}]}"""
		)
		tmdb.totalPages shouldBe 2
		val entries = buildEntries(userReviews, tmdb.results, "7ef882cd-3073-4427-9240-d0eca8333a9f")
		entries.map { it.author } shouldContainExactly listOf("me", "bob", "alice", "tricksy")
		entries.first().isOwn shouldBe true
		entries.first().date shouldBe "2025-01-01T00:00:00Z"
		entries.last().content shouldBe "Excellent movie.\nNolan is a genius."
		entries.last().source shouldBe ReviewEntry.Source.TMDB
	}

	test("ratings step in halves between no rating and five") {
		stepRating(0.0, up = true) shouldBe 0.5
		stepRating(0.5, up = false) shouldBe 0.0
		stepRating(0.0, up = false) shouldBe 0.0
		stepRating(5.0, up = true) shouldBe 5.0
		stepRating(3.5, up = true) shouldBe 4.0
	}

	test("star icons round to the nearest half") {
		starsOf(3.5) shouldBe Stars(full = 3, half = true, empty = 1)
		starsOf(3.75) shouldBe Stars(full = 4, half = false, empty = 1)
		starsOf(0.0) shouldBe Stars(full = 0, half = false, empty = 5)
		starsOf(7.0) shouldBe Stars(full = 5, half = false, empty = 0)
		formatRating(4.0) shouldBe "4"
		formatRating(3.5) shouldBe "3.5"
	}

	test("user ids match with or without dashes") {
		sameUser("7ef882cd-3073-4427-9240-d0eca8333a9f", "7EF882CD307344279240D0ECA8333A9F") shouldBe true
		sameUser("aaa", "bbb") shouldBe false
	}
})

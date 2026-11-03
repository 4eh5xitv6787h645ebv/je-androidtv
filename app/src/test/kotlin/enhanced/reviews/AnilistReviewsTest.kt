package org.jellyfin.androidtv.enhanced.reviews

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

private inline fun <reified T> decode(json: String): T =
	EnhancedApi.json.decodeFromJsonElement(EnhancedApi.json.parseToJsonElement(json))

class AnilistReviewsTest : FunSpec({
	// Trimmed from the lab server's `anilist/reviews?anilistId=1` (Cowboy Bebop), plus a review with nothing set
	val response = decode<AnilistReviewList>(
		"""{"SiteUrl":"https://anilist.co/anime/1","Reviews":[
			{"Author":"ToeBeans","Summary":"Review of Cowboy Bebop","Content":"##~~~__Cowboy Bebop Review__~~~\n\nI know it first aired back in 1998, but I just finished watching Cowboy Bebop recently.","Score":100,"Rating":324,"RatingAmount":381,"CreatedAt":"2017-07-10T05:59:44.0000000Z","Url":"https://anilist.co/review/2242"},
			{"Author":"paradigm","Summary":"Cowboy Bebop was a bet.","Content":"#~~~ _Where Everyone and Nobody Intersect_~~~\nFollow [Spike](https://anilist.co/character/1/Spike-Spiegel) home. Then, [spoiler hidden] the end.","Score":85,"Rating":115,"RatingAmount":124,"CreatedAt":"2021-08-21T15:52:15.0000000Z","Url":"https://anilist.co/review/13067"},
			{"Author":null,"Summary":null,"Content":"","Score":null,"Rating":0,"RatingAmount":0,"CreatedAt":null,"Url":null}
		]}"""
	)

	test("the PascalCase response decodes") {
		response.siteUrl shouldBe "https://anilist.co/anime/1"
		response.reviews.size shouldBe 3
		with(response.reviews[1]) {
			author shouldBe "paradigm"
			score shouldBe 85
			rating shouldBe 115
			ratingAmount shouldBe 124
			url shouldBe "https://anilist.co/review/13067"
		}
		response.reviews[2].score shouldBe null
	}

	test("an unknown id decodes to no reviews") {
		val empty = decode<AnilistReviewList>("""{"Reviews":[]}""")
		empty.siteUrl shouldBe null
		empty.reviews shouldBe emptyList()
	}

	test("entries carry score, headline, helpfulness and clean text") {
		val entry = ReviewEntry.of(response.reviews[0])
		entry.source shouldBe ReviewEntry.Source.ANILIST
		entry.author shouldBe "ToeBeans"
		entry.rating shouldBe 100.0
		entry.summary shouldBe "Review of Cowboy Bebop"
		entry.helpful shouldBe 324
		entry.votes shouldBe 381
		entry.date shouldBe "2017-07-10T05:59:44.0000000Z"
		entry.content shouldBe "Cowboy Bebop Review\n\nI know it first aired back in 1998, but I just finished watching Cowboy Bebop recently."

		ReviewEntry.of(response.reviews[1]).content shouldBe
			"Where Everyone and Nobody Intersect\nFollow Spike home. Then, [spoiler hidden] the end."

		val bare = ReviewEntry.of(response.reviews[2])
		bare.author shouldBe ""
		bare.rating shouldBe null
		bare.summary shouldBe null
	}

	test("AniList markup is reduced to words") {
		cleanAnilistText("~~~img220(https://x/y.png)~~~\n\n\n\n__Bold__ and _italic_ and ~~struck~~\n---\nend") shouldBe
			"img220(https://x/y.png)\n\nBold and italic and struck\n\nend"
		// Snake_case words and URLs with underscores are left alone
		cleanAnilistText("a snake_case_word stays") shouldBe "a snake_case_word stays"
	}

	test("AniList reviews sit between JE and TMDB reviews, empty ones dropped") {
		val user = listOf(JeReview(userId = "aaa", userName = "alice", content = "Great", rating = 4.0, createdAt = "2026-01-01T00:00:00Z"))
		val tmdb = listOf(TmdbReview(id = "1", author = "tricksy", content = "Fine"))
		val entries = buildEntries(user, tmdb, null, response.reviews)
		entries.map { it.source } shouldContainExactly listOf(
			ReviewEntry.Source.JELLYFIN,
			ReviewEntry.Source.ANILIST,
			ReviewEntry.Source.ANILIST,
			ReviewEntry.Source.TMDB,
		)
	}

	test("the first failure picks the error card's message") {
		ReviewsData(anilistError = ReviewsData.Failure("AniList reviews are not enabled.")).firstError?.message shouldBe
			"AniList reviews are not enabled."
		ReviewsData(tmdbError = ReviewsData.Failure("tmdb"), anilistError = ReviewsData.Failure("anilist")).firstError?.message shouldBe "tmdb"
		ReviewsData().firstError shouldBe null
	}

	test("AniList ids come from either spelling of the provider key") {
		anilistIdOf(mapOf("AniList" to "1")) shouldBe 1
		anilistIdOf(mapOf("Tmdb" to "30991", "Anilist" to " 21 ")) shouldBe 21
		anilistIdOf(mapOf("anilist" to "154587")) shouldBe 154587
		anilistIdOf(mapOf("AniList" to "0")) shouldBe null
		anilistIdOf(mapOf("AniList" to "abc")) shouldBe null
		anilistIdOf(mapOf("AniList" to null)) shouldBe null
		anilistIdOf(mapOf("AniDB" to "23")) shouldBe null
		anilistIdOf(null) shouldBe null
	}
})

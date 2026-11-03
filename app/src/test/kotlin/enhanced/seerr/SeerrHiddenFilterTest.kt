package org.jellyfin.androidtv.enhanced.seerr

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository

private class HiddenHarness(hiddenContent: Boolean) {
	val hiddenContentRepository = mockk<HiddenContentRepository>()
	val enhancedRepository = mockk<EnhancedRepository>().also {
		val state = EnhancedState(available = true, config = EnhancedPublicConfig(hiddenContentEnabled = hiddenContent))
		every { it.current } returns state
		coEvery { it.ensureLoaded(any()) } returns state
	}
	val seerr = SeerrRepository(mockk(relaxed = true), enhancedRepository, hiddenContentRepository)
	val pagesLoaded = mutableListOf<Int>()
	val cursor = SeerrRepository.FeedCursor(listOf(SeerrRepository.Feed { page ->
		pagesLoaded += page
		SeerrPage(page = page, totalPages = 3, results = listOf(SeerrResult(id = page * 10 + 1, mediaType = "movie"), SeerrResult(id = page * 10 + 2, mediaType = "movie")))
	}))
}

private fun hiddenDocument(vararg tmdbIds: Int) = HiddenContentRepository.Snapshot(Json.parseToJsonElement(
	"""{"Settings":{"Enabled":true},"Items":{${
		tmdbIds.joinToString(",") { """"tmdb-$it":{"ItemId":"","TmdbId":"$it","HideScope":"global"}""" }
	}}}"""
).jsonObject)

class SeerrHiddenFilterTest : FunSpec({
	test("discovery fails when the Hidden Content document can't be read, and a retry loads the same page") {
		val harness = HiddenHarness(hiddenContent = true)
		coEvery { harness.hiddenContentRepository.get() } throws IllegalStateException("offline")

		shouldThrow<IllegalStateException> { harness.seerr.loadNext(harness.cursor) }
		harness.cursor.nextPage[0] shouldBe 1

		coEvery { harness.hiddenContentRepository.get() } returns hiddenDocument(11)
		harness.seerr.loadNext(harness.cursor).results.map { it.id } shouldContainExactly listOf(12)
		// The failed attempt may or may not have fetched its page before being cancelled; the retry fetched page 1
		harness.pagesLoaded.last() shouldBe 1
		harness.cursor.nextPage[0] shouldBe 2
	}

	test("filterResults fails closed too") {
		val harness = HiddenHarness(hiddenContent = true)
		coEvery { harness.hiddenContentRepository.get() } throws IllegalStateException("offline")
		shouldThrow<IllegalStateException> { harness.seerr.filterResults(listOf(SeerrResult(id = 1, mediaType = "movie"))) }
	}

	test("without Hidden Content the document isn't needed") {
		val harness = HiddenHarness(hiddenContent = false)
		harness.seerr.loadNext(harness.cursor).results.map { it.id } shouldContainExactly listOf(11, 12)
		coVerify(exactly = 0) { harness.hiddenContentRepository.get() }
	}
})

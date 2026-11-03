package org.jellyfin.androidtv.enhanced.calendar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

class CalendarRequestedKeysTest : FunSpec({
	fun page(totalPages: Int, vararg tmdbIds: Int) = CalendarRequestsPage(totalPages, tmdbIds.map { CalendarRequest("tv", it) })

	test("reads every page up to the cap and says when it stopped early") {
		val read = mutableListOf<Int>()
		val all = CalendarRepository.collectRequestedKeys(maxPages = 3) { page -> read += page; page(2, page) }
		all shouldBe RequestedKeys(setOf("tv:1", "tv:2"), truncated = false)
		read shouldBe listOf(1, 2)

		val capped = CalendarRepository.collectRequestedKeys(maxPages = 2) { page -> page(5, page) }
		capped shouldBe RequestedKeys(setOf("tv:1", "tv:2"), truncated = true)
	}

	test("request keys are reused across weeks for the same user, re-read on refresh or another user") {
		val api = mockk<EnhancedApi>()
		var token = "a"
		var now = 0L
		every { api.pinned() } returns api
		every { api.accessToken } answers { token }
		val response: JsonElement = Json.parseToJsonElement("""{"totalPages":1,"requests":[{"type":"movie","tmdbId":7}]}""")
		coEvery { api.get("arr/requests", any()) } returns response
		val repository = CalendarRepository(api) { now }

		repository.requested().keys shouldBe setOf("movie:7")
		repository.requested()
		coVerify(exactly = 1) { api.get("arr/requests", any()) }

		repository.requested(refresh = true)
		coVerify(exactly = 2) { api.get("arr/requests", any()) }

		token = "b"
		repository.requested()
		coVerify(exactly = 3) { api.get("arr/requests", any()) }

		now += CalendarRepository.REQUESTED_TTL_MS
		repository.requested()
		coVerify(exactly = 4) { api.get("arr/requests", any()) }
	}
})

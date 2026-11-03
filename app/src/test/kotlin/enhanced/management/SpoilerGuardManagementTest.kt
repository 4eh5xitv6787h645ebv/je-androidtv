package org.jellyfin.androidtv.enhanced.management

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardPrefs
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.management.spoiler.SpoilerGuardCategories
import java.time.Instant
import java.util.UUID

class SpoilerGuardManagementTest : FunSpec({
	// Shape of the live JE 12.13 document, plus a movie, a collection and a pending opt-in
	val document = EnhancedApi.json.parseToJsonElement(
		"""
		{"Series":{"f494bed919557e62d08c44ee252d877a":{"SeriesId":"f494bed919557e62d08c44ee252d877a","SeriesName":"Silo","EnabledAt":"2026-10-22T03:56:14.4148714Z"}},
		 "Movies":{"3793faf537c6e8a36700b87a5f6ca800":{"MovieId":"3793faf537c6e8a36700b87a5f6ca800","MovieName":"Avatar","EnabledAt":"2026-10-23T10:00:00Z"}},
		 "Collections":{"0123456789abcdef0123456789abcdef":{"CollectionId":"0123456789abcdef0123456789abcdef","CollectionName":"Alien Collection","EnabledAt":""}},
		 "PendingTmdb":{"tv:1399":{"MediaType":"tv","TmdbId":"1399","DisplayName":"Game of Thrones","RequestedAt":"2026-10-24T08:00:00.000+02:00"}},
		 "Prefs":{"SkipDisableConfirm":false}}
		""".trimIndent()
	)

	test("the live document decodes with only SkipDisableConfirm in Prefs") {
		val state = EnhancedApi.json.decodeFromJsonElement<SpoilerGuardState>(document)
		state.prefs shouldBe SpoilerGuardPrefs()
		state.prefs.hideEpisodeDescriptions.shouldBeNull()
	}

	test("entries are typed per kind with ids, names and dates") {
		val state = EnhancedApi.json.decodeFromJsonElement<SpoilerGuardState>(document)
		val entries = SpoilerGuardRepository.entries(state)
		entries.map { it.kind } shouldContainExactly listOf(SpoilerGuardRepository.Kind.SERIES, SpoilerGuardRepository.Kind.MOVIE, SpoilerGuardRepository.Kind.COLLECTION)
		entries[0].name shouldBe "Silo"
		entries[0].itemId shouldBe UUID.fromString("f494bed9-1955-7e62-d08c-44ee252d877a")
		entries[0].enabledAt shouldBe Instant.parse("2026-10-22T03:56:14.414871400Z")
		entries[2].enabledAt.shouldBeNull()

		val pending = SpoilerGuardRepository.pendingEntries(state).single()
		pending.mediaType shouldBe "tv"
		pending.tmdbId shouldBe 1399
		pending.requestedAt shouldBe Instant.parse("2026-10-24T06:00:00Z")
	}

	test("guids parse from either format and reject anything else") {
		SpoilerGuardRepository.parseGuid("3edb510e-52d3-dd02-aac4-211c045310d4") shouldBe UUID.fromString("3edb510e-52d3-dd02-aac4-211c045310d4")
		SpoilerGuardRepository.parseGuid("3edb510e52d3dd02aac4211c045310d4") shouldBe UUID.fromString("3edb510e-52d3-dd02-aac4-211c045310d4")
		SpoilerGuardRepository.parseGuid("tmdb-603").shouldBeNull()
		SpoilerGuardRepository.parseGuid("").shouldBeNull()
	}

	test("only categories the admin enabled are offered; advanced mode only when turned on") {
		val defaults = SpoilerGuardCategories.available(EnhancedPublicConfig(spoilerBlurEnabled = true))
		defaults.map { it.key } shouldBe SpoilerGuardCategories.all.map { it.key } - SpoilerGuardRepository.PREF_USE_ADVANCED_CATEGORIES

		val limited = SpoilerGuardCategories.available(EnhancedPublicConfig(spoilerStripCast = false, spoilerAdvancedMode = true))
		limited.map { it.key }.contains("HideCast") shouldBe false
		limited.map { it.key }.contains(SpoilerGuardRepository.PREF_USE_ADVANCED_CATEGORIES) shouldBe true
	}

	test("null and true follow the server, false opts out; flipping writes false or clears to null") {
		val cast = SpoilerGuardCategories.all.first { it.key == "HideCast" }
		cast.applied(SpoilerGuardPrefs(hideCast = null)) shouldBe true
		cast.applied(SpoilerGuardPrefs(hideCast = true)) shouldBe true
		cast.applied(SpoilerGuardPrefs(hideCast = false)) shouldBe false
		cast.toggled(SpoilerGuardPrefs(hideCast = null)) shouldBe false
		cast.toggled(SpoilerGuardPrefs(hideCast = true)) shouldBe false
		cast.toggled(SpoilerGuardPrefs(hideCast = false)).shouldBeNull()
	}

	test("setPrefs reads the stored prefs and writes them all back with one change") {
		val api = mockk<EnhancedApi>()
		every { api.pinned() } returns api
		every { api.accessToken } returns "token"
		coEvery { api.get("spoiler-blur/user-prefs", any()) } returns
			EnhancedApi.json.parseToJsonElement("""{"SkipDisableConfirm":true,"HideCast":false,"FutureField":1}""")
		val body = slot<JsonObject>()
		coEvery { api.post("spoiler-blur/user-prefs", capture(body), any()) } returns JsonObject(emptyMap())

		val prefs = runBlocking { SpoilerGuardRepository(api).setPrefs(mapOf("HideCast" to null, "HideRatings" to false)) }

		body.captured["SkipDisableConfirm"] shouldBe JsonPrimitive(true)
		body.captured["HideCast"] shouldBe JsonNull
		body.captured["HideRatings"] shouldBe JsonPrimitive(false)
		body.captured["FutureField"] shouldBe JsonPrimitive(1)
		prefs.skipDisableConfirm shouldBe true
		prefs.hideRatings shouldBe false
	}

	test("setPrefs writes nothing when the stored prefs can't be read") {
		val api = mockk<EnhancedApi>()
		every { api.pinned() } returns api
		coEvery { api.get("spoiler-blur/user-prefs", any()) } throws
			EnhancedApi.EnhancedApiException(503, "corrupt", serverMessage = "Spoiler Guard data was corrupt and has been backed up.")

		val result = runCatching { runBlocking { SpoilerGuardRepository(api).setPrefs(mapOf("HideCast" to false)) } }

		(result.exceptionOrNull() as EnhancedApi.EnhancedApiException).serverMessage shouldBe "Spoiler Guard data was corrupt and has been backed up."
		coVerify(exactly = 0) { api.post(any(), any(), any()) }
	}

	test("SkipDisableConfirm is never written as null") {
		val api = mockk<EnhancedApi>()
		every { api.pinned() } returns api
		coEvery { api.get("spoiler-blur/user-prefs", any()) } returns document.jsonObject["Prefs"]!!
		val body = slot<JsonObject>()
		coEvery { api.post("spoiler-blur/user-prefs", capture(body), any()) } returns JsonObject(emptyMap())

		runBlocking { SpoilerGuardRepository(api).setPrefs(mapOf(SpoilerGuardRepository.PREF_SKIP_DISABLE_CONFIRM to null)) }

		body.captured[SpoilerGuardRepository.PREF_SKIP_DISABLE_CONFIRM] shouldBe JsonPrimitive(false)
	}
})

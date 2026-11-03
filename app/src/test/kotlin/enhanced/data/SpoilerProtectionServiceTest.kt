package org.jellyfin.androidtv.enhanced.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

private const val USER_KEY = "c799f011678d4423bd7c07d24c672de8"
private val GUARDED_COLLECTION: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
private val LISTED_MOVIE: UUID = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")
private val HIDDEN_MOVIE: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
private val OTHER_MOVIE: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")
private val SERIES: UUID = UUID.fromString("326d7084-0827-00b2-20b6-83fc0dc0ae78")

private fun movie(id: UUID) = BaseItemDto(id = id, type = BaseItemKind.MOVIE)

/** [SpoilerProtection] against mocked repositories and API: what it loads, how it fails closed, what it reuses. */
class SpoilerProtectionServiceTest : FunSpec({
	val json = EnhancedApi.json

	class Fixture(
		enhanced: EnhancedState = EnhancedState(
			available = true,
			config = EnhancedPublicConfig(spoilerBlurEnabled = true, hiddenContentEnabled = true),
		),
		guardState: SpoilerGuardState = SpoilerGuardState(
			collections = mapOf(SpoilerGuardRepository.key(GUARDED_COLLECTION) to JsonPrimitive(true)),
		),
		hiddenDocument: String = """{"Settings":{"Enabled":true,"FilterLibrary":true},"Items":{
			"a":{"ItemId":"${HIDDEN_MOVIE}","HideScope":"global"}}}""",
	) {
		var now = 0L
		var lastRead: SpoilerGuardState? = null
		val api = mockk<EnhancedApi>()
		val enhancedRepository = mockk<EnhancedRepository> { coEvery { ensureLoaded(any()) } returns enhanced }
		val guard = mockk<SpoilerGuardRepository> {
			// Like the real repository: getState() remembers the object it returned until a write clears it
			coEvery { getState() } answers { guardState.copy().also { lastRead = it } }
			every { cachedState() } answers { lastRead }
		}
		val protection = SpoilerProtection(enhancedRepository, guard, api) { now }

		init {
			every { api.pinned() } returns api
			every { api.accessToken } returns "token"
			coEvery { api.currentUserId() } returns USER_KEY
			coEvery { api.get("/Items", any()) } returns json.parseToJsonElement("""{"Items":[{"Id":"${SpoilerGuardRepository.key(LISTED_MOVIE)}"}]}""")
			coEvery { api.get("user-settings/$USER_KEY/hidden-content.json", any()) } returns json.parseToJsonElement(hiddenDocument)
		}
	}

	test("configuration unavailable: everything is fully protected, not unprotected") {
		val fixture = Fixture(enhanced = EnhancedState(configUnavailable = true))
		val snapshot = fixture.protection.snapshot()
		snapshot.configUnavailable shouldBe true
		snapshot.complete shouldBe false
		snapshot.forItem(movie(OTHER_MOVIE)) shouldBe Protection.FULL
		snapshot.forItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, seriesId = SERIES)) shouldBe Protection.FULL
		snapshot.forTitle(isMovie = false, libraryId = null, tmdbId = 5) shouldBe Protection.FULL
		coVerify(exactly = 0) { fixture.guard.getState() }
	}

	test("plugin absent: Spoiler Guard is off") {
		val fixture = Fixture(enhanced = EnhancedState())
		fixture.protection.snapshot() shouldBeSameInstanceAs SpoilerSnapshot.DISABLED
	}

	test("a hidden movie is a possible member of a guarded collection although the listing leaves it out") {
		val fixture = Fixture()
		val snapshot = fixture.protection.snapshot()
		snapshot.complete shouldBe true
		snapshot.forItem(movie(LISTED_MOVIE)).guarded shouldBe true
		snapshot.forItem(movie(HIDDEN_MOVIE)).guarded shouldBe true
		snapshot.forItem(movie(OTHER_MOVIE)) shouldBe Protection.NONE
	}

	test("an unreadable Hidden Content document leaves membership unknown: every other movie fails closed") {
		val fixture = Fixture()
		coEvery { fixture.api.get("user-settings/$USER_KEY/hidden-content.json", any()) } throws
			EnhancedApi.EnhancedApiException(500, "failed")
		val snapshot = fixture.protection.snapshot()
		snapshot.complete shouldBe false
		snapshot.forItem(movie(OTHER_MOVIE)) shouldBe Protection.FULL
	}

	test("the Hidden Content document is only read when the plugin filters library lists") {
		val fixture = Fixture(enhanced = EnhancedState(available = true, config = EnhancedPublicConfig(spoilerBlurEnabled = true)))
		val snapshot = fixture.protection.snapshot()
		snapshot.forItem(movie(HIDDEN_MOVIE)) shouldBe Protection.NONE
		coVerify(exactly = 0) { fixture.api.get("user-settings/$USER_KEY/hidden-content.json", any()) }
	}

	test("hidden ids count only when the user's settings filter the library, in any scope") {
		fun ids(document: String) = SpoilerProtection.hiddenLibraryItemIds(
			HiddenContentRepository.Snapshot(json.parseToJsonElement(document).jsonObject)
		)
		ids("""{"Settings":{"Enabled":true},"Items":{"a":{"ItemId":"${HIDDEN_MOVIE}","HideScope":"nextup"},"b":{"ItemId":""}}}""") shouldContainExactlyInAnyOrder
			listOf(SpoilerGuardRepository.key(HIDDEN_MOVIE))
		ids("""{"Settings":{"Enabled":true,"FilterLibrary":false},"Items":{"a":{"ItemId":"${HIDDEN_MOVIE}"}}}""") shouldBe emptySet()
		ids("""{"Settings":{"Enabled":false},"Items":{"a":{"ItemId":"${HIDDEN_MOVIE}"}}}""") shouldBe emptySet()
	}

	test("a complete snapshot is reused briefly, and never after a Spoiler Guard write") {
		val fixture = Fixture()
		val first = fixture.protection.snapshot()
		fixture.now = SpoilerProtection.CACHE_TTL_MS - 1
		fixture.protection.snapshot() shouldBeSameInstanceAs first
		coVerify(exactly = 1) { fixture.guard.getState() }

		// A write in this client clears the repository's state
		fixture.lastRead = null
		val afterWrite = fixture.protection.snapshot()
		(afterWrite === first) shouldBe false

		fixture.now += SpoilerProtection.CACHE_TTL_MS
		(fixture.protection.snapshot() === afterWrite) shouldBe false
		coVerify(exactly = 3) { fixture.guard.getState() }
	}

	test("an incomplete snapshot is not reused") {
		val fixture = Fixture()
		coEvery { fixture.api.get("/Items", any()) } throws EnhancedApi.EnhancedApiException(0, "offline")
		fixture.protection.snapshot().complete shouldBe false
		fixture.protection.snapshot()
		coVerify(exactly = 2) { fixture.guard.getState() }
	}
})

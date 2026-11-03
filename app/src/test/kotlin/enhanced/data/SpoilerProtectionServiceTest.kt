package org.jellyfin.androidtv.enhanced.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull
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
		/** The server's member count of the guarded collection: the listed movie and the hidden one. */
		childCount: Int = 2,
	) {
		var now = 0L
		var lastRead: SpoilerGuardState? = null
		val api = mockk<EnhancedApi>()
		val enhancedRepository = mockk<EnhancedRepository> { coEvery { ensureLoaded(any()) } returns enhanced }
		val guard = mockk<SpoilerGuardRepository> {
			// Like the real repository: getState() remembers the object it returned until a write clears it
			coEvery { getState() } answers { guardState.copy().also { lastRead = it } }
			every { cachedState() } answers { lastRead }
			every { generation } returns 0L
		}
		val protection = SpoilerProtection(enhancedRepository, guard, api) { now }

		init {
			every { api.pinned() } returns api
			every { api.accessToken } returns "token"
			coEvery { api.currentUserId() } returns USER_KEY
			coEvery { api.get("/Items", any()) } returns json.parseToJsonElement("""{"Items":[{"Id":"${SpoilerGuardRepository.key(LISTED_MOVIE)}"}]}""")
			coEvery { api.get("user-settings/$USER_KEY/hidden-content.json", any()) } returns json.parseToJsonElement(hiddenDocument)
			coEvery { api.get("/Items/$GUARDED_COLLECTION", any()) } returns json.parseToJsonElement("""{"Type":"BoxSet","ChildCount":$childCount}""")
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

	test("an empty Hidden Content document can't account for members missing from the listing") {
		// An unreadable file comes back as an empty document while the plugin's filter still hides from its cache
		val fixture = Fixture(hiddenDocument = """{"Settings":{"Enabled":true,"FilterLibrary":true},"Items":{}}""")
		val snapshot = fixture.protection.snapshot()
		snapshot.complete shouldBe false
		snapshot.forItem(movie(LISTED_MOVIE)).guarded shouldBe true
		snapshot.forItem(movie(OTHER_MOVIE)) shouldBe Protection.FULL
	}

	test("a listing as long as the collection is complete without any hidden ids") {
		val fixture = Fixture(hiddenDocument = """{"Settings":{"Enabled":true,"FilterLibrary":true},"Items":{}}""", childCount = 1)
		val snapshot = fixture.protection.snapshot()
		snapshot.complete shouldBe true
		snapshot.forItem(movie(OTHER_MOVIE)) shouldBe Protection.NONE
	}

	test("a collection without a readable ChildCount leaves membership unknown") {
		val fixture = Fixture()
		coEvery { fixture.api.get("/Items/$GUARDED_COLLECTION", any()) } returns json.parseToJsonElement("""{"Type":"BoxSet"}""")
		fixture.protection.snapshot().complete shouldBe false
	}

	test("missing members are accounted for only by enough hidden ids that aren't listed") {
		val listing = SpoilerProtection.CollectionListing(listed = setOf("a", "b"), childCount = 4)
		listing.accountedFor(setOf("c", "d")) shouldBe true
		listing.accountedFor(setOf("a", "c")) shouldBe false
		listing.accountedFor(emptySet()) shouldBe false
		SpoilerProtection.CollectionListing(listed = setOf("a"), childCount = 1).accountedFor(emptySet()) shouldBe true
		// The listing can't be filtered: nothing to account for
		SpoilerProtection.CollectionListing(listed = setOf("a"), childCount = null).accountedFor(emptySet()) shouldBe true
	}

	test("the Hidden Content document is only read when the plugin filters library lists") {
		val fixture = Fixture(enhanced = EnhancedState(available = true, config = EnhancedPublicConfig(spoilerBlurEnabled = true)))
		val snapshot = fixture.protection.snapshot()
		snapshot.forItem(movie(HIDDEN_MOVIE)) shouldBe Protection.NONE
		coVerify(exactly = 0) { fixture.api.get("user-settings/$USER_KEY/hidden-content.json", any()) }
		coVerify(exactly = 0) { fixture.api.get("/Items/$GUARDED_COLLECTION", any()) }
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

	test("a state read while a write is in flight is used once, never cached") {
		val api = mockk<EnhancedApi>()
		every { api.accessToken } returns "token"
		val path = "spoiler-blur/movies/${SpoilerGuardRepository.key(OTHER_MOVIE)}"
		var serverState = """{"Movies":{}}"""
		val writeArrived = CompletableDeferred<Unit>()
		val writeMayFinish = CompletableDeferred<Unit>()
		coEvery { api.get("spoiler-blur/series", any()) } answers { json.parseToJsonElement(serverState) }
		// The write is slow: a read made while it is in flight still gets the old list
		coEvery { api.post(path, any(), any()) } coAnswers {
			writeArrived.complete(Unit)
			writeMayFinish.await()
			serverState = """{"Movies":{"${SpoilerGuardRepository.key(OTHER_MOVIE)}":{}}}"""
			JsonNull
		}
		val enhanced = EnhancedState(available = true, config = EnhancedPublicConfig(spoilerBlurEnabled = true))
		val repository = SpoilerGuardRepository(api)
		val protection = SpoilerProtection(mockk<EnhancedRepository> { coEvery { ensureLoaded(any()) } returns enhanced }, repository, api) { 0L }

		coroutineScope {
			val write = async { repository.setGuarded(SpoilerGuardRepository.Kind.MOVIE, OTHER_MOVIE, "Other", guarded = true) }
			writeArrived.await()
			// Read during the write: old state, so the movie isn't protected yet
			protection.snapshot().forItem(movie(OTHER_MOVIE)) shouldBe Protection.NONE
			writeMayFinish.complete(Unit)
			write.await()
		}

		// Within the cache's lifetime, but the write happened: the new list is read
		protection.snapshot().forItem(movie(OTHER_MOVIE)).guarded shouldBe true
		repository.cachedState()?.movies?.keys shouldBe setOf(SpoilerGuardRepository.key(OTHER_MOVIE))
		coVerify(exactly = 2) { api.get("spoiler-blur/series", any()) }
	}

	test("a read that overlaps a write isn't remembered by the repository") {
		val api = mockk<EnhancedApi>()
		every { api.accessToken } returns "token"
		val readArrived = CompletableDeferred<Unit>()
		val readMayFinish = CompletableDeferred<Unit>()
		coEvery { api.get("spoiler-blur/series", any()) } coAnswers {
			readArrived.complete(Unit)
			readMayFinish.await()
			json.parseToJsonElement("""{"Movies":{}}""")
		}
		coEvery { api.post(any(), any(), any()) } returns JsonNull
		val repository = SpoilerGuardRepository(api)

		coroutineScope {
			val read = async { repository.getState() }
			readArrived.await()
			val before = repository.generation
			repository.setPending("tv", 1399, "Show", enabled = true)
			repository.generation shouldBe before + 2
			readMayFinish.complete(Unit)
			read.await()
		}
		repository.cachedState() shouldBe null
	}

	test("an incomplete snapshot is not reused") {
		val fixture = Fixture()
		coEvery { fixture.api.get("/Items", any()) } throws EnhancedApi.EnhancedApiException(0, "offline")
		fixture.protection.snapshot().complete shouldBe false
		fixture.protection.snapshot()
		coVerify(exactly = 2) { fixture.guard.getState() }
	}
})

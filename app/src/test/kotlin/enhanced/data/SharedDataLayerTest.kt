package org.jellyfin.androidtv.enhanced.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import java.util.UUID

/**
 * The shared data layer's account and concurrency guarantees: queued writes act as the user who started them, and
 * Spoiler Guard reads are shared without ever outliving a write or an account.
 */
class SharedDataLayerTest : FunSpec({
	val json = EnhancedApi.json
	val itemId = UUID.fromString("3edb510e-52d3-dd02-aac4-211c045310d4")

	/** A shared client whose pinned copy follows whichever account [signedIn] holds when it's pinned. */
	class Accounts {
		val userA = mockk<EnhancedApi>(relaxed = true)
		val userB = mockk<EnhancedApi>(relaxed = true)
		var signedIn = userA
		val shared = mockk<EnhancedApi> {
			every { pinned() } answers { signedIn }
			every { accessToken } answers { if (signedIn === userA) "a" else "b" }
		}
	}

	test("a home removal queued behind an edit still acts as the user who asked") {
		val accounts = Accounts()
		val settings = EnhancedSettingsRepository(accounts.shared, mockk<EnhancedRepository>(relaxed = true))
		val repository = HiddenContentRepository(accounts.shared, mockk<UserRepository>(), settings)
		val lockHeld = CompletableDeferred<Unit>()
		val releaseLock = CompletableDeferred<Unit>()

		coroutineScope {
			val holder = async { settings.withDocumentLock { lockHeld.complete(Unit); releaseLock.await() } }
			lockHeld.await()
			val removal = async(start = CoroutineStart.UNDISPATCHED) { repository.removeFromHome(itemId, HomeSurface.NEXT_UP) }
			val undo = async(start = CoroutineStart.UNDISPATCHED) { repository.undoRemoveFromHome(itemId, HomeSurface.CONTINUE_WATCHING) }
			// The account changes while both wait for the lock
			accounts.signedIn = accounts.userB
			releaseLock.complete(Unit)
			listOf(holder, removal, undo).awaitAll()
		}

		coVerify(exactly = 1) { accounts.userA.post("next-up/hide/$itemId", any(), any()) }
		coVerify(exactly = 1) { accounts.userA.delete("continue-watching/hide/$itemId", any()) }
		coVerify(exactly = 0) { accounts.userB.post(any(), any(), any()) }
		coVerify(exactly = 0) { accounts.userB.delete(any(), any()) }
	}

	test("a Spoiler Guard prefs change queued behind another one saves for the user who made it") {
		val accounts = Accounts()
		val firstReadArrived = CompletableDeferred<Unit>()
		val firstReadMayFinish = CompletableDeferred<Unit>()
		coEvery { accounts.userA.get("spoiler-blur/user-prefs", any()) } coAnswers {
			firstReadArrived.complete(Unit)
			firstReadMayFinish.await()
			JsonObject(emptyMap())
		}
		val repository = SpoilerGuardRepository(accounts.shared)

		coroutineScope {
			val first = async { repository.setPrefs(mapOf("HideCast" to false)) }
			firstReadArrived.await()
			val second = async(start = CoroutineStart.UNDISPATCHED) { repository.setPrefs(mapOf("HideRatings" to false)) }
			accounts.signedIn = accounts.userB
			firstReadMayFinish.complete(Unit)
			listOf(first, second).awaitAll()
		}

		coVerify(exactly = 2) { accounts.userA.post("spoiler-blur/user-prefs", any(), any()) }
		coVerify(exactly = 0) { accounts.userB.get(any(), any()) }
		coVerify(exactly = 0) { accounts.userB.post(any(), any(), any()) }
	}

	test("concurrent Spoiler Guard reads share one request, which a write or another account invalidates") {
		var now = 0L
		var token = "a"
		val api = mockk<EnhancedApi> {
			every { accessToken } answers { token }
			coEvery { post(any(), any(), any()) } returns JsonNull
		}
		val readMayFinish = CompletableDeferred<Unit>()
		coEvery { api.get("spoiler-blur/series", any()) } coAnswers {
			readMayFinish.await()
			json.parseToJsonElement("""{"Movies":{}}""")
		}
		val repository = SpoilerGuardRepository(api) { now }

		coroutineScope {
			val reads = List(4) { async(start = CoroutineStart.UNDISPATCHED) { repository.getState() } }
			readMayFinish.complete(Unit)
			reads.awaitAll()
		}
		coVerify(exactly = 1) { api.get("spoiler-blur/series", any()) }

		// Reused within the window
		now += SpoilerGuardRepository.SHARE_WINDOW_MS - 1
		repository.getState()
		coVerify(exactly = 1) { api.get("spoiler-blur/series", any()) }

		// Not after it
		now += 1
		repository.getState()
		coVerify(exactly = 2) { api.get("spoiler-blur/series", any()) }

		// A write in this client invalidates it at once
		repository.setPending("tv", 1399, "Show", enabled = true)
		repository.getState()
		coVerify(exactly = 3) { api.get("spoiler-blur/series", any()) }

		// So does another account
		token = "b"
		repository.getState()
		coVerify(exactly = 4) { api.get("spoiler-blur/series", any()) }
	}

	test("a failed Spoiler Guard read is not shared") {
		val api = mockk<EnhancedApi> { every { accessToken } returns "a" }
		coEvery { api.get("spoiler-blur/series", any()) } throws EnhancedApi.EnhancedApiException(0, "offline")
		val repository = SpoilerGuardRepository(api) { 0L }

		runCatching { repository.getState() }.isFailure shouldBe true
		coEvery { api.get("spoiler-blur/series", any()) } returns json.parseToJsonElement("""{"Movies":{}}""")
		repository.getState().movies shouldBe emptyMap()
		coVerify(exactly = 2) { api.get("spoiler-blur/series", any()) }
	}
})

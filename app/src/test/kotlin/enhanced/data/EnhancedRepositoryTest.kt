package org.jellyfin.androidtv.enhanced.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

/** How [EnhancedRepository] tells "no plugin" (cached) from "configuration unavailable" (retried, fails closed). */
class EnhancedRepositoryTest : FunSpec({
	val json = EnhancedApi.json

	class Fixture {
		val api = mockk<EnhancedApi>()
		val user = mockk<UserDto> {
			every { id } returns UUID.fromString("c799f011-678d-4423-bd7c-07d24c672de8")
			every { policy } returns null
		}
		val currentUser = MutableStateFlow<UserDto?>(user)
		private val users = mockk<UserRepository> { every { currentUser } returns this@Fixture.currentUser }

		init {
			every { api.invalidatePublicConfig() } just runs
			// Optional parts of the state are missing; only public-config decides availability
			coEvery { api.get(any(), any()) } throws EnhancedApi.EnhancedApiException(404, "not found")
			coEvery { api.getText(any(), any()) } throws EnhancedApi.EnhancedApiException(404, "not found")
		}

		var now = 0L

		// Created after the stubs: its init loads the state for the current user in the background
		val repository by lazy { EnhancedRepository(api, users, processLifecycle = null) { now } }

		fun configFails(err: Throwable) {
			coEvery { api.publicConfig(any()) } throws err
		}

		fun configLoads(body: String = """{"SpoilerBlurEnabled":true}""") {
			coEvery { api.publicConfig(any()) } returns json.parseToJsonElement(body)
		}

		/** Signals each `public-config` fetch after the first, for background refreshes. */
		fun configLoadsSignalling(body: String, fetched: CompletableDeferred<Unit>) {
			coEvery { api.publicConfig(any()) } answers {
				fetched.complete(Unit)
				json.parseToJsonElement(body)
			}
		}

		fun configFailsSignalling(err: Throwable, fetched: CompletableDeferred<Unit>) {
			coEvery { api.publicConfig(any()) } answers {
				fetched.complete(Unit)
				throw err
			}
		}
	}

	test("a network failure leaves the configuration unknown and is retried") {
		val fixture = Fixture()
		fixture.configFails(EnhancedApi.EnhancedApiException(0, "offline"))
		val failed = fixture.repository.ensureLoaded()
		failed.configUnavailable shouldBe true
		failed.available shouldBe false
		failed.spoilerGuard shouldBe false
		failed.spoilerGuardMayApply shouldBe true

		fixture.configLoads()
		fixture.now += EnhancedRepository.RETRY_BACKOFF_MS
		val loaded = fixture.repository.ensureLoaded()
		loaded.available shouldBe true
		loaded.configUnavailable shouldBe false
		loaded.spoilerGuard shouldBe true
	}

	test("a server error or an unreadable response is not mistaken for a missing plugin") {
		val fixture = Fixture()
		fixture.configFails(EnhancedApi.EnhancedApiException(503, "unavailable"))
		fixture.repository.ensureLoaded().configUnavailable shouldBe true

		coEvery { fixture.api.publicConfig(any()) } returns json.parseToJsonElement("[]")
		fixture.now += EnhancedRepository.RETRY_BACKOFF_MS
		fixture.repository.ensureLoaded().configUnavailable shouldBe true
	}

	test("an unknown configuration is retried at most once per backoff, unless forced") {
		val fixture = Fixture()
		fixture.configFails(EnhancedApi.EnhancedApiException(0, "offline"))
		fixture.repository.ensureLoaded().configUnavailable shouldBe true
		clearMocks(fixture.api, answers = false)

		fixture.configLoads()
		fixture.now += EnhancedRepository.RETRY_BACKOFF_MS - 1
		fixture.repository.ensureLoaded().configUnavailable shouldBe true
		coVerify(exactly = 0) { fixture.api.publicConfig(any()) }

		fixture.repository.ensureLoaded(force = true).available shouldBe true
		// An explicit retry bypasses the shared public-config cache
		coVerify(exactly = 1) { fixture.api.publicConfig(true) }
	}

	test("the backoff ends after its interval and is never cached as a missing plugin") {
		val fixture = Fixture()
		fixture.configFails(EnhancedApi.EnhancedApiException(503, "unavailable"))
		fixture.repository.ensureLoaded().configUnavailable shouldBe true

		fixture.configLoads()
		fixture.now += EnhancedRepository.RETRY_BACKOFF_MS
		fixture.repository.ensureLoaded().available shouldBe true
	}

	test("a 404 means no plugin: stock behaviour, cached") {
		val fixture = Fixture()
		fixture.configFails(EnhancedApi.EnhancedApiException(404, "not found"))
		fixture.repository.ensureLoaded() shouldBe EnhancedState()

		fixture.configLoads()
		fixture.repository.ensureLoaded() shouldBe EnhancedState()
		fixture.repository.ensureLoaded(force = true).available shouldBe true
	}

	test("a failed refresh keeps the configuration already loaded") {
		val fixture = Fixture()
		fixture.configLoads()
		fixture.repository.ensureLoaded().available shouldBe true

		fixture.configFails(EnhancedApi.EnhancedApiException(0, "offline"))
		val refreshed = fixture.repository.ensureLoaded(force = true)
		refreshed.available shouldBe true
		refreshed.configUnavailable shouldBe false
	}

	test("cancellation is rethrown, not cached as a missing plugin") {
		val fixture = Fixture()
		fixture.configFails(CancellationException("left the screen"))
		shouldThrow<CancellationException> { fixture.repository.ensureLoaded() }

		fixture.configLoads()
		fixture.repository.ensureLoaded().available shouldBe true
	}

	test("a stale state is returned at once and refreshed in the background") {
		val fixture = Fixture()
		fixture.configLoads()
		fixture.repository.ensureLoaded().config.hiddenContentEnabled shouldBe false

		fixture.configLoads("""{"SpoilerBlurEnabled":true,"HiddenContentEnabled":true}""")
		// Not stale yet: nothing is fetched
		fixture.now += EnhancedRepository.STALE_AFTER_MS - 1
		fixture.repository.ensureLoaded().config.hiddenContentEnabled shouldBe false
		coVerify(exactly = 1) { fixture.api.publicConfig(any()) }

		fixture.now += 1
		fixture.repository.ensureLoaded().config.hiddenContentEnabled shouldBe false
		withTimeout(5_000) { fixture.repository.state.first { it.config.hiddenContentEnabled } }
		// The session's first load and the refresh both bypass the shared public-config cache
		coVerify(exactly = 2) { fixture.api.publicConfig(true) }
	}

	test("returning to the foreground refreshes only a stale state") {
		val fixture = Fixture()
		fixture.configLoads()
		fixture.repository.ensureLoaded().available shouldBe true

		val fetched = CompletableDeferred<Unit>()
		fixture.configLoadsSignalling("""{"SpoilerBlurEnabled":true,"JellyseerrEnabled":true}""", fetched)
		fixture.repository.refreshIfStale()
		// Wait for the check to run (it takes the same lock)
		fixture.repository.ensureLoaded()
		fetched.isCompleted shouldBe false

		fixture.now += EnhancedRepository.STALE_AFTER_MS
		fixture.repository.refreshIfStale()
		withTimeout(5_000) { fixture.repository.state.first { it.config.jellyseerrEnabled } }
	}

	test("a failed background refresh keeps the good state, even for a 404") {
		for (err in listOf(EnhancedApi.EnhancedApiException(0, "offline"), EnhancedApi.EnhancedApiException(404, "not found"))) {
			val fixture = Fixture()
			fixture.configLoads()
			fixture.repository.ensureLoaded().available shouldBe true

			val fetched = CompletableDeferred<Unit>()
			fixture.configFailsSignalling(err, fetched)
			fixture.now += EnhancedRepository.STALE_AFTER_MS
			fixture.repository.refreshIfStale()
			withTimeout(5_000) { fetched.await() }
			// Waits for the refresh to finish; the state is fresh again, so this doesn't fetch
			val after = fixture.repository.ensureLoaded()
			after.available shouldBe true
			after.spoilerGuard shouldBe true
			fixture.repository.current.available shouldBe true
			// And the failure isn't retried on every screen until the next interval
			coVerify(exactly = 2) { fixture.api.publicConfig(any()) }
		}
	}

	test("settings saved while a refresh is loading are not overwritten by it") {
		val fixture = Fixture()
		fixture.configLoads()
		fixture.repository.ensureLoaded().userSettings.showFileSizes shouldBe null

		val saved = EnhancedUserSettings(showFileSizes = true)
		val fetchStarted = CompletableDeferred<Unit>()
		val fetchMayFinish = CompletableDeferred<Unit>()
		coEvery { fixture.api.publicConfig(any()) } coAnswers {
			fetchStarted.complete(Unit)
			fetchMayFinish.await()
			json.parseToJsonElement("""{"SpoilerBlurEnabled":true,"JellyseerrEnabled":true}""")
		}
		fixture.now += EnhancedRepository.STALE_AFTER_MS
		fixture.repository.refreshIfStale()
		withTimeout(5_000) { fetchStarted.await() }
		fixture.repository.updateUserSettings(saved)
		fetchMayFinish.complete(Unit)

		val refreshed = withTimeout(5_000) { fixture.repository.state.first { it.config.jellyseerrEnabled } }
		refreshed.userSettings shouldBe saved
	}

	test("a new session, a restored saved-token login included, starts with a fresh configuration") {
		val fixture = Fixture()
		// The shared cache still holds a copy fetched before the session; only a fresh read gets the current one
		coEvery { fixture.api.publicConfig(false) } returns json.parseToJsonElement("""{"SpoilerBlurEnabled":true}""")
		coEvery { fixture.api.publicConfig(true) } returns json.parseToJsonElement("""{"SpoilerBlurEnabled":true,"JellyseerrEnabled":true}""")
		fixture.repository.ensureLoaded().config.jellyseerrEnabled shouldBe true

		// Signed out, then the saved token restores the same user (same server, same token: same cache key)
		fixture.currentUser.value = null
		withTimeout(5_000) { fixture.repository.state.first { !it.available } }
		coEvery { fixture.api.publicConfig(true) } returns json.parseToJsonElement("""{"SpoilerBlurEnabled":true,"HiddenContentEnabled":true}""")
		fixture.currentUser.value = fixture.user
		val restored = withTimeout(5_000) { fixture.repository.state.first { it.available } }
		restored.config.hiddenContentEnabled shouldBe true
		restored.config.jellyseerrEnabled shouldBe false

		coVerify(exactly = 0) { fixture.api.publicConfig(false) }
		// The shared copy is dropped too, for features that read public-config themselves
		verify(atLeast = 1) { fixture.api.invalidatePublicConfig() }
	}
})

package org.jellyfin.androidtv.enhanced.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

/** How [EnhancedRepository] tells "no plugin" (cached) from "configuration unavailable" (retried, fails closed). */
class EnhancedRepositoryTest : FunSpec({
	val json = EnhancedApi.json

	class Fixture {
		val api = mockk<EnhancedApi>()
		private val user = mockk<UserDto> {
			every { id } returns UUID.fromString("c799f011-678d-4423-bd7c-07d24c672de8")
			every { policy } returns null
		}
		private val users = mockk<UserRepository> { every { currentUser } returns MutableStateFlow(user) }

		init {
			// Optional parts of the state are missing; only public-config decides availability
			coEvery { api.get(any(), any()) } throws EnhancedApi.EnhancedApiException(404, "not found")
			coEvery { api.getText(any(), any()) } throws EnhancedApi.EnhancedApiException(404, "not found")
		}

		var now = 0L

		// Created after the stubs: its init loads the state for the current user in the background
		val repository by lazy { EnhancedRepository(api, users) { now } }

		fun configFails(err: Throwable) {
			coEvery { api.get("public-config", any()) } throws err
		}

		fun configLoads() {
			coEvery { api.get("public-config", any()) } returns json.parseToJsonElement("""{"SpoilerBlurEnabled":true}""")
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

		coEvery { fixture.api.get("public-config", any()) } returns json.parseToJsonElement("[]")
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
		coVerify(exactly = 0) { fixture.api.get("public-config", any()) }

		fixture.repository.ensureLoaded(force = true).available shouldBe true
		coVerify(exactly = 1) { fixture.api.get("public-config", any()) }
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
})

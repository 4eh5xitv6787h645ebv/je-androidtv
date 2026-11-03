package org.jellyfin.androidtv.enhanced.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SeerrUserStatus
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

private fun user(id: UUID) = mockk<UserDto> { every { this@mockk.id } returns id }

/** [SeerrLinkManager] links where it can and never loops: each automatic look happens once per user and session. */
class SeerrLinkManagerTest : FunSpec({
	val json = EnhancedApi.json

	fun state(status: SeerrUserStatus, admin: Boolean = false) = EnhancedState(
		available = true,
		config = EnhancedPublicConfig(jellyseerrEnabled = true),
		seerr = status,
		isAdmin = admin,
	)

	val unlinked = SeerrUserStatus(active = true, userFound = false, reason = "unlinked")
	val linked = SeerrUserStatus(active = true, userFound = true, reason = "linked", jellyseerrUserId = "7")

	class Fixture(initial: EnhancedState, statusAnswer: String) {
		val api = mockk<EnhancedApi>()
		val states = MutableStateFlow(initial)
		val users = MutableStateFlow<UserDto?>(user(UUID.randomUUID()))
		val repository = mockk<EnhancedRepository> {
			every { state } returns states
			every { current } answers { states.value }
			every { refreshInBackground() } just runs
		}
		var statusBody = statusAnswer

		init {
			coEvery { api.get("jellyseerr/user-status", any()) } answers { json.parseToJsonElement(statusBody) }
			// A forced load re-reads the status like EnhancedRepository does and publishes it
			coEvery { repository.ensureLoaded(true) } answers {
				states.value = states.value.copy(seerr = json.decodeFromString(SeerrUserStatus.serializer(), statusBody))
				states.value
			}
		}

		// Unconfined and no delay: the delayed second look runs at once, inside the state collection
		val manager = SeerrLinkManager(
			enhancedApi = api,
			enhancedRepository = repository,
			userRepository = mockk<UserRepository> { every { currentUser } returns users },
			scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
			recheckDelay = { 0L },
		)

		fun statusReads(count: Int) = coVerify(exactly = count) { api.get("jellyseerr/user-status", any()) }
	}

	test("an unlinked user is looked at again once; the server linked them, so the state reloads") {
		val fixture = Fixture(state(unlinked), """{"active":true,"userFound":true,"jellyseerrUserId":"7","reason":"linked"}""")
		fixture.manager
		fixture.statusReads(1)
		coVerify(exactly = 1) { fixture.repository.ensureLoaded(true) }
		SeerrLink.kind(fixture.states.value) shouldBe SeerrLinkKind.LINKED
	}

	test("still unlinked after the second look: no third one, however often the state is published") {
		val fixture = Fixture(state(unlinked), """{"active":true,"userFound":false,"reason":"unlinked"}""")
		fixture.manager
		repeat(5) { fixture.states.value = state(unlinked.copy(canRequestAdvanced = it % 2 == 0)) }
		fixture.statusReads(1)
		// Nothing changed, so nothing to reload
		coVerify(exactly = 0) { fixture.repository.ensureLoaded(true) }
	}

	test("blocked, disabled, unreachable and no-user are never retried automatically") {
		for (reason in listOf("blocked", "disabled", "unreachable", "no_user")) {
			val status = SeerrUserStatus(active = reason == "blocked", userFound = false, reason = reason)
			val fixture = Fixture(state(status), """{"active":true,"userFound":true,"reason":"linked"}""")
			fixture.manager
			repeat(3) { fixture.states.value = state(status.copy(canRequestAdvanced = it % 2 == 0)) }
			fixture.statusReads(0)
		}
	}

	test("a linked user is left alone") {
		val fixture = Fixture(state(linked), """{"active":true,"userFound":true,"reason":"linked"}""")
		fixture.manager
		fixture.states.value = state(linked.copy(canRequestAdvanced = true))
		fixture.statusReads(0)
	}

	test("another user signing in gets their own single look") {
		val fixture = Fixture(state(unlinked), """{"active":true,"userFound":false,"reason":"unlinked"}""")
		fixture.manager
		fixture.statusReads(1)
		fixture.users.value = user(UUID.randomUUID())
		fixture.states.value = EnhancedState()
		fixture.states.value = state(unlinked)
		fixture.states.value = state(unlinked.copy(canRequestAdvanced = true))
		fixture.statusReads(2)
	}

	test("a call saying unlinked while the state says linked reloads the state once per session") {
		val fixture = Fixture(state(linked), """{"active":true,"userFound":true,"reason":"linked"}""")
		repeat(4) { fixture.manager.onUnlinkedError() }
		verify(exactly = 1) { fixture.repository.refreshInBackground() }
	}

	test("a call saying unlinked when the state already knows doesn't reload anything") {
		val fixture = Fixture(state(SeerrUserStatus(active = true, userFound = false, reason = "blocked")), "{}")
		fixture.manager.onUnlinkedError()
		verify(exactly = 0) { fixture.repository.refreshInBackground() }
	}

	test("the admin import re-reads the link, and a failed status read is UNKNOWN, not a crash") {
		val fixture = Fixture(state(linked, admin = true), """{"active":true,"userFound":true,"jellyseerrUserId":"7","reason":"linked"}""")
		coEvery { fixture.api.post("jellyseerr/import-users", any(), any()) } returns
			json.parseToJsonElement("""{"success":true,"usersImported":3,"totalUsers":9,"errors":[]}""")
		runBlocking {
			fixture.manager.importUsers() shouldBe SeerrImportResult(success = true, usersImported = 3, totalUsers = 9)
			fixture.statusReads(1)
			coEvery { fixture.api.get("jellyseerr/user-status", any()) } throws EnhancedApi.EnhancedApiException(0, "offline")
			fixture.manager.recheck() shouldBe SeerrLinkKind.UNKNOWN
		}
	}

	test("the display name comes from the admin-only user list, and only for admins") {
		val fixture = Fixture(state(linked, admin = true), "{}")
		coEvery { fixture.api.get("jellyseerr/user", any()) } returns json.parseToJsonElement(
			"""{"results":[{"id":2,"displayName":"real-viewer"},{"id":7,"displayName":"","jellyfinUsername":"tv-admin"}]}"""
		)
		runBlocking {
			fixture.manager.linkedDisplayName(state(linked, admin = true)) shouldBe "tv-admin"
			fixture.manager.linkedDisplayName(state(linked.copy(jellyseerrUserId = "8"), admin = false)) shouldBe null
		}
		coVerify(exactly = 1) { fixture.api.get("jellyseerr/user", any()) }
	}
})

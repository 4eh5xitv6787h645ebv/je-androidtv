package org.jellyfin.androidtv.enhanced.subtitles

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

/** [SubtitleStyleSync] only writes a style read for, and still applying to, the session it started with. */
class SubtitleStyleSyncTest : FunSpec({
	val server = UUID.fromString("5d9e7c1a-0b6f-4b52-9b1e-3f1c2a7d8e90")
	val sessionA = Session(UUID.fromString("c799f011-678d-4423-bd7c-07d24c672de8"), server, "token-a")
	val sessionB = Session(UUID.fromString("0d4a1e55-2f0c-4a8e-8f53-5a6b7c8d9e01"), server, "token-b")

	class Fixture(session: Session) {
		/** The shared client: its token follows a switch first. */
		var sharedToken = session.accessToken
		val api = mockk<EnhancedApi> {
			every { accessToken } answers { sharedToken }
			every { pinned() } answers { pinnedFor(sharedToken) }
			// An unpinned read would get whoever the shared client belongs to by then
			coEvery { get(any(), any()) } returns JsonObject(emptyMap())
		}
		val sessions = MutableStateFlow<Session?>(session)
		val users = MutableStateFlow<UserDto?>(user(session))
		val state = MutableStateFlow(EnhancedState(available = true))
		val store = mockk<SubtitleSyncStore>(relaxed = true) {
			every { isEnabled(any()) } returns true
			every { backup } returns null
			every { lastApplied } returns null
		}
		val preferences = mockk<UserPreferences>(relaxed = true) {
			every { this@mockk[UserPreferences.subtitlesTextColor] } returns 0xFFFFFFFFL
			every { this@mockk[UserPreferences.subtitlesBackgroundColor] } returns 0x00FFFFFFL
			every { this@mockk[UserPreferences.subtitleTextStrokeColor] } returns 0xFF000000L
			every { this@mockk[UserPreferences.subtitlesTextSize] } returns 24f
			every { this@mockk[UserPreferences.subtitlesOffsetPosition] } returns 0.08f
		}

		/** Runs while a pinned client reads its documents, e.g. a switch landing mid-read. */
		var duringRead: () -> Unit = {}

		private fun pinnedFor(token: String) = mockk<EnhancedApi> {
			every { accessToken } returns token
			coEvery { get(any(), any()) } answers {
				duringRead()
				JsonObject(emptyMap())
			}
		}

		fun user(session: Session) = mockk<UserDto> { every { id } returns session.userId }

		val sync by lazy {
			SubtitleStyleSync(
				enhancedApi = api,
				enhancedRepository = mockk { every { state } returns this@Fixture.state },
				sessionRepository = mockk<SessionRepository> { every { currentSession } returns sessions },
				userRepository = mockk<UserRepository> { every { currentUser } returns users },
				userPreferences = preferences,
				store = store,
			)
		}

		/** The background sync has had time to run (it starts with the sync and on every state change). */
		suspend fun settle() {
			sync
			verify(timeout = 2_000) { api.pinned() }
			delay(200)
		}

		fun verifyWrites(count: Int) = verify(exactly = count) { store.saveApplied(any(), any()) }
	}

	test("a consistent session gets its style applied") {
		val fixture = Fixture(sessionA)
		fixture.settle()
		verify(timeout = 2_000) { fixture.store.saveApplied(any(), any()) }
	}

	test("state for the next account arriving while the session still names the previous one writes nothing") {
		// Mid-switch to B: the shared client and current user are B's, the plugin state is B's, the session still A
		val fixture = Fixture(sessionA)
		fixture.sharedToken = sessionB.accessToken
		fixture.users.value = fixture.user(sessionB)
		fixture.settle()
		fixture.state.value = EnhancedState(available = true, pluginVersion = "b")
		delay(200)
		fixture.verifyWrites(0)

		shouldThrow<IllegalStateException> { fixture.sync.refresh() }
		fixture.verifyWrites(0)
		verify(exactly = 0) { fixture.preferences[UserPreferences.subtitlesTextColor] = any() }
	}

	test("a switch landing while the documents load discards what was read") {
		val fixture = Fixture(sessionA)
		fixture.duringRead = {
			fixture.sharedToken = sessionB.accessToken
			fixture.users.value = fixture.user(sessionB)
		}
		fixture.settle()
		fixture.verifyWrites(0)
	}
})

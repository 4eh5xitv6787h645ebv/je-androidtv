package org.jellyfin.androidtv.enhanced.subtitles

import android.content.Context
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.preference.UserPreferences
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class SubtitlesModuleTest : FunSpec({
	test("the sync is created with the module, idle while nobody is signed in") {
		val context = mockk<Context>(relaxed = true)
		val koin = koinApplication {
			androidContext(context)
			modules(
				module {
					single { mockk<EnhancedApi>(relaxed = true) }
					single { mockk<EnhancedRepository> { every { state } returns MutableStateFlow(EnhancedState()) } }
					single { mockk<EnhancedSettingsRepository>(relaxed = true) }
					single<SessionRepository> { mockk { every { currentSession } returns MutableStateFlow<Session?>(null) } }
					single { mockk<UserPreferences>(relaxed = true) }
				},
				subtitlesModule,
			)
		}.koin

		koin.get<SubtitleStyleSync>().enabled.value shouldBe false
		subtitlesSettingsRoutes.keys shouldBe setOf(SubtitlesRoutes.STYLE)
		subtitlesSettingsEntry.route shouldBe "/enhanced/subtitles"
	}
})

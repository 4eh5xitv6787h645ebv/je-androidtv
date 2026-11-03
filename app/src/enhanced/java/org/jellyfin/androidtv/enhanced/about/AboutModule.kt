package org.jellyfin.androidtv.enhanced.about

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.settings.EnhancedSettingsRoutes
import org.jellyfin.androidtv.enhanced.ui.settings.SettingsEntry
import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.koin.dsl.module

/** Koin bindings of the About screen. */
val aboutModule = module {
	single { AboutRepository(get()) }
}

/** "About Jellyfin Enhanced" on the Jellyfin Enhanced settings screen (last). */
val aboutSettingsEntry = SettingsEntry(
	route = EnhancedSettingsRoutes.ABOUT,
	title = R.string.je_about_title,
	caption = R.string.je_about_caption,
	icon = R.drawable.je_ic_about_info,
	enabled = { it.available },
)

/** The About screen's settings routes. */
val aboutSettingsRoutes: Map<String, RouteComposable> = mapOf(
	EnhancedSettingsRoutes.ABOUT to { AboutSettingsScreen() },
	AboutRoutes.RELEASE_NOTES to { ReleaseNotesSettingsScreen() },
)

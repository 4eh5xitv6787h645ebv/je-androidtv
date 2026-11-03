package org.jellyfin.androidtv.enhanced.home

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.settings.SettingsEntry
import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Koin bindings of the Jellyfin Enhanced home rows. */
val homeModule: Module = module {
	single { HomeRowsStore(androidContext(), get()) }
	single { HomeRowsRepository(get(), get(), get(), get(), get(), get(), get(), get()) }
}

/** Settings routes of the home rows. */
object HomeSettingsRoutes {
	const val HOME_ROWS = "/enhanced/home-rows"
}

val homeSettingsRoutes: Map<String, RouteComposable> = mapOf(
	HomeSettingsRoutes.HOME_ROWS to { HomeRowsSettingsScreen() },
)

/** "Home rows" on the Jellyfin Enhanced settings screen, while the server offers at least one of the rows. */
val homeSettingsEntry = SettingsEntry(
	route = HomeSettingsRoutes.HOME_ROWS,
	title = R.string.je_home_settings_title,
	caption = R.string.je_home_settings_caption,
	icon = R.drawable.je_ic_home_rows,
	enabled = { state -> state.available && HomeRowKind.entries.any { it.gate(state) } },
)

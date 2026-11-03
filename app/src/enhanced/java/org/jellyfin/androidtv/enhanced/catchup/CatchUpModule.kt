package org.jellyfin.androidtv.enhanced.catchup

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.ui.hub.HubEntry
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Koin bindings of the Catch Up deck. */
val catchUpModule: Module = module {
	single { CatchUpDismissStore(androidContext()) }
	single { CatchUpRepository(get(), get(), get()) }
}

/** Whether the server offers Catch Up (`CatchUpEnabled`). Logging (`CatchUpLogEnabled`) is server-only state. */
fun catchUpEnabled(state: EnhancedState): Boolean = state.available && state.config.catchUpEnabled

/** The Catch Up hub page. */
val catchUpHubEntry = HubEntry(
	id = "catchup",
	title = R.string.je_catchup_title,
	description = R.string.je_catchup_description,
	icon = R.drawable.je_ic_catchup_style,
	enabled = ::catchUpEnabled,
	destination = { fragmentDestination<CatchUpFragment>() },
	order = 18,
)

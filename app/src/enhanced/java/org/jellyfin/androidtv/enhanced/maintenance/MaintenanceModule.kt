package org.jellyfin.androidtv.enhanced.maintenance

import org.jellyfin.androidtv.enhanced.ui.hub.HubNotice
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin bindings of the maintenance notice: the hub banner and the app-start dialog. */
val maintenanceModule = module {
	single { MaintenanceRepository(get()) }
	single { MaintenanceDismissStore(androidContext()) }
	single { MaintenanceHubNotice(get()) } bind HubNotice::class
	// Created at start: it watches the app's screens to show the dialog, nothing else asks for it
	single(createdAtStart = true) { MaintenanceNotifier(androidContext(), get(), get(), get(), get()) }
}

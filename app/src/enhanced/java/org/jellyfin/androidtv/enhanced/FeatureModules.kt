package org.jellyfin.androidtv.enhanced

import org.jellyfin.androidtv.enhanced.requests.requestsHubEntry
import org.jellyfin.androidtv.enhanced.requests.requestsModule
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedHub
import org.koin.core.module.Module

/**
 * Koin modules of the feature packages (search, discover, requests, …), one per feature, each defined in its own
 * file. Maintained by the integration owner. Hub entries are registered from the same place so the feature's
 * presence in the hub follows its module.
 */
val featureModules: List<Module> = listOf(
	requestsModule,
).also {
	EnhancedHub.register(
		requestsHubEntry,
	)
}

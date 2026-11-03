package org.jellyfin.androidtv.enhanced.requests

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.hub.HubEntry
import org.jellyfin.androidtv.enhanced.seerr.SeerrLink
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.koin.dsl.module

/** Koin bindings of the Requests page. Listed in `featureModules` by the integration owner. */
val requestsModule = module {
	single { RequestsRepository(get(), get(), get(), get(), get()) }
}

/** The Requests page: requests, watchlist, download queue/history and issues. */
object RequestsDestinations {
	fun requests() = fragmentDestination<RequestsFragment>()
}

/**
 * Hub entry for the Requests page; shown when the server's downloads page is on and Seerr is linked for the user, or
 * when the page's link notice can fix the link ([SeerrLink.offersPage]).
 */
val requestsHubEntry = HubEntry(
	id = "requests",
	title = R.string.je_requests_title,
	description = R.string.je_requests_description,
	icon = R.drawable.je_ic_requests_list_alt,
	enabled = { state -> state.requests || SeerrLink.offersPage(state, state.config.downloadsPageEnabled) },
	destination = { RequestsDestinations.requests() },
	order = 20,
)

package org.jellyfin.androidtv.enhanced.discover

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.seerr.SeerrLink
import org.jellyfin.androidtv.enhanced.ui.hub.HubEntry
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.koin.dsl.module

/** Koin bindings of the Discover feature. */
val discoverModule = module {
	single { DiscoverRepository(get(), get(), get(), get(), get(), get()) }
}

object DiscoverDestinations {
	fun discover() = fragmentDestination<DiscoverFragment>()

	fun randomPick() = fragmentDestination<RandomPickFragment>()
}

/**
 * Whether to offer "Pick something for me". The user's `RandomButtonEnabled`, else the plugin's default (on);
 * the server's own default isn't part of the shared config model yet.
 */
fun randomPickEnabled(state: EnhancedState): Boolean = state.available && (state.userSettings.randomButtonEnabled ?: state.config.randomButtonEnabled)

/** Hub pages of the Discover feature. */
object DiscoverHubEntries {
	val discover = HubEntry(
		id = "discover",
		title = R.string.je_discover_title,
		description = R.string.je_discover_description,
		icon = R.drawable.je_ic_discover_explore,
		enabled = { it.discover || SeerrLink.offersPage(it, it.config.recommendationsPageEnabled) },
		destination = DiscoverDestinations::discover,
		order = 10,
	)

	val random = HubEntry(
		id = "random",
		title = R.string.je_discover_random_title,
		description = R.string.je_discover_random_description,
		icon = R.drawable.je_ic_discover_casino,
		enabled = ::randomPickEnabled,
		destination = DiscoverDestinations::randomPick,
		order = 15,
	)

	val all = arrayOf(discover, random)
}

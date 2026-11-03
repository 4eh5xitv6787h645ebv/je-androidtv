package org.jellyfin.androidtv.enhanced.activity

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.hub.HubEntry
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.koin.dsl.module

/** Koin bindings of the activity page. */
val activityModule = module {
	single { ActivityRepository(get()) }
}

/**
 * The activity hub page. It also carries the active streams, so it shows when either the feed or the streams are
 * available to the user.
 */
val activityHubEntry = HubEntry(
	id = "activity",
	title = R.string.je_activity_title,
	description = R.string.je_activity_description,
	icon = R.drawable.je_ic_activity_timeline,
	enabled = { it.activity || it.activeStreams },
	destination = { fragmentDestination<ActivityFragment>() },
	order = 60,
)

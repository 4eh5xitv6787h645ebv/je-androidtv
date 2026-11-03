package org.jellyfin.androidtv.enhanced

import org.jellyfin.androidtv.enhanced.about.aboutModule
import org.jellyfin.androidtv.enhanced.activity.activityHubEntry
import org.jellyfin.androidtv.enhanced.activity.activityModule
import org.jellyfin.androidtv.enhanced.bookmarks.bookmarksHubEntry
import org.jellyfin.androidtv.enhanced.bookmarks.bookmarksModule
import org.jellyfin.androidtv.enhanced.calendar.calendarHubEntry
import org.jellyfin.androidtv.enhanced.calendar.calendarModule
import org.jellyfin.androidtv.enhanced.catchup.catchUpHubEntry
import org.jellyfin.androidtv.enhanced.catchup.catchUpModule
import org.jellyfin.androidtv.enhanced.discover.DiscoverHubEntries
import org.jellyfin.androidtv.enhanced.discover.discoverModule
import org.jellyfin.androidtv.enhanced.management.ManagementEntries
import org.jellyfin.androidtv.enhanced.management.managementModule
import org.jellyfin.androidtv.enhanced.metadata.metadataModule
import org.jellyfin.androidtv.enhanced.requests.requestsHubEntry
import org.jellyfin.androidtv.enhanced.requests.requestsModule
import org.jellyfin.androidtv.enhanced.reviews.reviewsModule
import org.jellyfin.androidtv.enhanced.search.searchModule
import org.jellyfin.androidtv.enhanced.seerrdetails.seerrDetailsModule
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedHub
import org.koin.core.module.Module

/**
 * Koin modules of the feature packages, one per feature, each defined in its own file. Hub entries are registered
 * from the same place so a feature's presence in the hub follows its module. Order matters for details-screen
 * contributors: their rows and buttons appear in registration order (metadata before reviews, bookmarks last).
 */
val featureModules: List<Module> = listOf(
	searchModule,
	discoverModule,
	requestsModule,
	seerrDetailsModule,
	managementModule,
	metadataModule,
	reviewsModule,
	calendarModule,
	bookmarksModule,
	activityModule,
	catchUpModule,
	aboutModule,
).also {
	EnhancedHub.register(
		*DiscoverHubEntries.all,
		requestsHubEntry,
		calendarHubEntry,
		bookmarksHubEntry,
		activityHubEntry,
		catchUpHubEntry,
		*ManagementEntries.hubEntries.toTypedArray(),
	)
}

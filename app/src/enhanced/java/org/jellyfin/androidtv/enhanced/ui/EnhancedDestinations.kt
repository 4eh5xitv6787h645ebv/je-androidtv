package org.jellyfin.androidtv.enhanced.ui

import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedHubFragment
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsFragment
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDiscoveryFragment
import org.jellyfin.androidtv.ui.navigation.fragmentDestination

object EnhancedDestinations {
	/** The Jellyfin Enhanced hub, or straight to one of its pages by [org.jellyfin.androidtv.enhanced.ui.hub.HubEntry.id]. */
	fun hub(page: String? = null) = fragmentDestination<EnhancedHubFragment> {
		if (page != null) putString(EnhancedHubFragment.ARG_PAGE, page)
	}

	fun seerrDiscovery(source: SeerrSource) = fragmentDestination<SeerrDiscoveryFragment> {
		putString(SeerrDiscoveryFragment.ARG_SOURCE, Json.encodeToString(SeerrSource.serializer(), source))
	}

	fun seerrDetails(mediaType: String, tmdbId: Int) = fragmentDestination<SeerrDetailsFragment> {
		putString(SeerrDetailsFragment.ARG_MEDIA_TYPE, mediaType)
		putInt(SeerrDetailsFragment.ARG_TMDB_ID, tmdbId)
	}
}

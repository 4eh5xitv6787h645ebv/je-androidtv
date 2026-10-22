package org.jellyfin.androidtv.enhanced.ui

import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsFragment
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDiscoveryFragment
import org.jellyfin.androidtv.ui.navigation.fragmentDestination

object EnhancedDestinations {
	fun seerrDiscovery(source: SeerrSource) = fragmentDestination<SeerrDiscoveryFragment> {
		putString(SeerrDiscoveryFragment.ARG_SOURCE, Json.encodeToString(SeerrSource.serializer(), source))
	}

	fun seerrDetails(mediaType: String, tmdbId: Int) = fragmentDestination<SeerrDetailsFragment> {
		putString(SeerrDetailsFragment.ARG_MEDIA_TYPE, mediaType)
		putInt(SeerrDetailsFragment.ARG_TMDB_ID, tmdbId)
	}
}

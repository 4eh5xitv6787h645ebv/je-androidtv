package org.jellyfin.androidtv.enhanced.search

import org.jellyfin.androidtv.ui.navigation.fragmentDestination

/** Screens opened from the Seerr search rows. */
object SeerrSearchDestinations {
	/** Every page of the movie and show results for [query] (the search box text, "Title (YYYY)" included). */
	fun results(query: String, title: String) = fragmentDestination<SeerrSearchGridFragment> {
		putString(SeerrSearchGridFragment.ARG_QUERY, query)
		putString(SeerrSearchGridFragment.ARG_TITLE, title)
	}

	/** The parts of the TMDB collection [tmdbCollectionId]. */
	fun collection(tmdbCollectionId: Int, name: String) = fragmentDestination<SeerrSearchGridFragment> {
		putInt(SeerrSearchGridFragment.ARG_COLLECTION_ID, tmdbCollectionId)
		putString(SeerrSearchGridFragment.ARG_TITLE, name)
	}
}

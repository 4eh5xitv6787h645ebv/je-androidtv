package org.jellyfin.androidtv.enhanced.bookmarks

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.androidtv.enhanced.ui.hub.HubEntry
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin bindings of the bookmarks page and the "Add bookmark" details action. */
val bookmarksModule = module {
	single { BookmarksRepository(get(), get()) }
	single { BookmarkDetailsContributor(get(), get()) } bind DetailsContributor::class
}

/** The bookmarks hub page, shown when the plugin's bookmarks are enabled. */
val bookmarksHubEntry = HubEntry(
	id = "bookmarks",
	title = R.string.je_bookmarks_title,
	description = R.string.je_bookmarks_description,
	icon = R.drawable.je_ic_bookmarks_bookmark,
	enabled = { it.bookmarks },
	destination = { fragmentDestination<BookmarksFragment>() },
	order = 50,
)

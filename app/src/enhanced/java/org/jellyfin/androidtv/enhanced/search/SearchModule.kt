package org.jellyfin.androidtv.enhanced.search

import org.koin.dsl.module

/**
 * Seerr results in the app's search screen. Without this module the search screen stays stock: the hooks in
 * `ui/search` look the repository up optionally.
 */
val searchModule = module {
	single { SeerrSearchRepository(get(), get(), get()) }
}

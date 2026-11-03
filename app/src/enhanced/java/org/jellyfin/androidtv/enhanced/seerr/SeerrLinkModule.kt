package org.jellyfin.androidtv.enhanced.seerr

import org.koin.dsl.module

/**
 * Koin bindings of the Seerr account link. Created at start: [SeerrLinkManager] watches the Jellyfin Enhanced state
 * from app start, so an unlinked user gets their automatic second look without opening any Seerr page.
 */
val seerrLinkModule = module {
	single(createdAtStart = true) { SeerrLinkManager(get(), get(), get()) }
}

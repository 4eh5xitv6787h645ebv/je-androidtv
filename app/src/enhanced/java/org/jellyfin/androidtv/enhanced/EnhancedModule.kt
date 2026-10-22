package org.jellyfin.androidtv.enhanced

import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.koin.dsl.module

/** Koin bindings for the Jellyfin Enhanced layer. Registered from KoinInitializer. */
val enhancedModule = module {
	single { EnhancedApi(get(), get()) }
	single(createdAtStart = true) { EnhancedRepository(get(), get()) }
	single { SpoilerGuardRepository(get()) }
	single { HiddenContentRepository(get(), get()) }
	single { SeerrRepository(get(), get(), get()) }
}

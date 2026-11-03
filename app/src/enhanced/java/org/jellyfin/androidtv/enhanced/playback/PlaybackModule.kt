package org.jellyfin.androidtv.enhanced.playback

import org.koin.dsl.module

/** Koin bindings of the player features (pause screen, rating badge and OSD ratings, bookmarks from the player). */
val playbackModule = module {
	single { PlaybackFeaturesRepository(get(), get()) }
	single { PlaybackBookmarks(get(), get()) }
}

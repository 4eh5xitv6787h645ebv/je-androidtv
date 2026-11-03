package org.jellyfin.androidtv.enhanced.subtitles

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.settings.SettingsEntry
import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Settings routes of the subtitle style sync. */
object SubtitlesRoutes {
	const val STYLE = "/enhanced/subtitles"
}

/**
 * Koin bindings of the subtitle style sync. The sync is created at start so it applies the style once the plugin
 * state loads, without any screen asking for it.
 */
val subtitlesModule = module {
	single { SubtitleSyncStore(androidContext()) }
	single(createdAtStart = true) { SubtitleStyleSync(get(), get(), get(), get(), get(), get()) }
}

/** "Subtitle style" on the Jellyfin Enhanced settings screen. */
val subtitlesSettingsEntry = SettingsEntry(
	route = SubtitlesRoutes.STYLE,
	title = R.string.je_subtitles_title,
	caption = R.string.je_subtitles_caption,
	icon = R.drawable.je_ic_subtitles,
	enabled = { it.available },
)

/** The subtitle style page's settings routes. */
val subtitlesSettingsRoutes: Map<String, RouteComposable> = mapOf(
	SubtitlesRoutes.STYLE to { SubtitlesSettingsScreen() },
)

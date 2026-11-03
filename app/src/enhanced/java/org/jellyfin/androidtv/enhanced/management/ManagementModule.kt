package org.jellyfin.androidtv.enhanced.management

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.management.hidden.HiddenContentFragment
import org.jellyfin.androidtv.enhanced.management.hidden.HiddenContentSettingsScreen
import org.jellyfin.androidtv.enhanced.management.hidden.SeerrHideDetailsContributor
import org.jellyfin.androidtv.enhanced.management.hidden.SeerrHideMenuContributor
import org.jellyfin.androidtv.enhanced.management.settings.JeDetailsSettingsScreen
import org.jellyfin.androidtv.enhanced.management.settings.JePlaybackSettingsScreen
import org.jellyfin.androidtv.enhanced.management.spoiler.SpoilerGuardFragment
import org.jellyfin.androidtv.enhanced.management.spoiler.SpoilerGuardSettingsScreen
import org.jellyfin.androidtv.enhanced.ui.hub.HubEntry
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardMenuContributor
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContributor
import org.jellyfin.androidtv.enhanced.ui.settings.EnhancedSettingsRoutes
import org.jellyfin.androidtv.enhanced.ui.settings.SettingsEntry
import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin bindings of the management feature: Hide on Seerr cards and the Seerr details screen. */
val managementModule = module {
	single { SeerrHideMenuContributor(get()) } bind SeerrCardMenuContributor::class
	single { SeerrHideDetailsContributor(get()) } bind SeerrDetailsContributor::class
}

/** Hub pages and settings screens of the management feature. */
object ManagementEntries {
	const val SPOILER_GUARD = "spoiler-guard"
	const val HIDDEN_CONTENT = "hidden-content"

	val spoilerGuardHub = HubEntry(
		id = SPOILER_GUARD,
		title = R.string.je_spoiler_guard,
		description = R.string.je_management_sg_hub_description,
		icon = R.drawable.je_ic_blur_on,
		enabled = { it.spoilerGuard },
		destination = { fragmentDestination<SpoilerGuardFragment>() },
		order = 60,
	)

	val hiddenContentHub = HubEntry(
		id = HIDDEN_CONTENT,
		title = R.string.je_management_hc_title,
		description = R.string.je_management_hc_hub_description,
		icon = R.drawable.je_ic_visibility_off,
		enabled = { it.hiddenContent },
		destination = { fragmentDestination<HiddenContentFragment>() },
		order = 61,
	)

	val hubEntries = listOf(spoilerGuardHub, hiddenContentHub)

	val settingsEntries = listOf(
		SettingsEntry(
			route = EnhancedSettingsRoutes.SPOILER_GUARD,
			title = R.string.je_spoiler_guard,
			caption = R.string.je_management_sg_settings_entry,
			icon = R.drawable.je_ic_blur_on,
			enabled = { it.spoilerGuard },
		),
		SettingsEntry(
			route = EnhancedSettingsRoutes.HIDDEN_CONTENT,
			title = R.string.je_management_hc_title,
			caption = R.string.je_management_hc_settings_entry,
			icon = R.drawable.je_ic_visibility_off,
			enabled = { it.hiddenContent },
		),
		SettingsEntry(
			route = EnhancedSettingsRoutes.DETAILS,
			title = R.string.je_management_set_details_title,
			caption = R.string.je_management_set_details_entry,
			icon = R.drawable.je_ic_management_sell,
		),
		SettingsEntry(
			route = EnhancedSettingsRoutes.PLAYBACK,
			title = R.string.je_management_set_playback_title,
			caption = R.string.je_management_set_playback_entry,
			icon = R.drawable.je_ic_management_play_circle,
		),
	)

	val settingsRoutes: Map<String, RouteComposable> = mapOf<String, RouteComposable>(
		EnhancedSettingsRoutes.SPOILER_GUARD to { SpoilerGuardSettingsScreen() },
		EnhancedSettingsRoutes.HIDDEN_CONTENT to { HiddenContentSettingsScreen() },
		EnhancedSettingsRoutes.DETAILS to { JeDetailsSettingsScreen() },
		EnhancedSettingsRoutes.PLAYBACK to { JePlaybackSettingsScreen() },
	)
}

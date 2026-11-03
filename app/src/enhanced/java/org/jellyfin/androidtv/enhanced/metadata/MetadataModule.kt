package org.jellyfin.androidtv.enhanced.metadata

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContributor
import org.jellyfin.androidtv.enhanced.ui.settings.SettingsEntry
import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin bindings of the details metadata feature (info row, cast ages, content warnings, Seerr details section). */
val metadataModule = module {
	single { MetadataRepository(get(), get(), get(), get()) }
	single { MetadataDetailsContributor(get(), get(), get(), get()) } bind DetailsContributor::class
	single { MetadataSeerrContributor(get()) } bind SeerrDetailsContributor::class
}

/** Settings screens of this feature, keyed by route. */
val metadataSettingsRoutes: Map<String, RouteComposable> = mapOf(
	MetadataSettingsRoutes.ELSEWHERE to { ElsewhereSettingsScreen() },
	MetadataSettingsRoutes.ELSEWHERE_REGION to { ElsewhereRegionSettingsScreen() },
	MetadataSettingsRoutes.ELSEWHERE_SERVICES to { ElsewhereServicesSettingsScreen() },
	MetadataSettingsRoutes.CONTENT_WARNINGS to { ContentWarningTriggersSettingsScreen() },
)

/** Entries on the Jellyfin Enhanced settings screen. */
val metadataSettingsEntries: List<SettingsEntry> = listOf(
	SettingsEntry(
		route = MetadataSettingsRoutes.ELSEWHERE,
		title = R.string.je_metadata_elsewhere_title,
		caption = R.string.je_metadata_elsewhere_caption,
		enabled = { it.available && it.config.elsewhereEnabled && it.config.tmdbEnabled },
	),
	SettingsEntry(
		route = MetadataSettingsRoutes.CONTENT_WARNINGS,
		title = R.string.je_metadata_triggers_title,
		caption = R.string.je_metadata_triggers_caption,
		icon = R.drawable.je_ic_metadata_warning,
		enabled = { it.available && it.config.doesTheDogDieEnabled },
	),
)

package org.jellyfin.androidtv.enhanced.ui.settings

import org.jellyfin.androidtv.ui.navigation.RouteComposable

/**
 * Settings screens contributed by feature modules, keyed by route (use the constants in [EnhancedSettingsRoutes] or
 * add new ones there). Maintained by the integration owner: one entry per feature, each screen in its own file.
 */
val featureSettingsRoutes: Map<String, RouteComposable> = mapOf<String, RouteComposable>(
)

/**
 * Entries shown on the Jellyfin Enhanced settings screen, in order. Each feature contributes a [SettingsEntry]
 * (title/caption/route/gate) from its own file; the integration owner lists them here.
 */
val featureSettingsEntries: List<SettingsEntry> = listOf(
)

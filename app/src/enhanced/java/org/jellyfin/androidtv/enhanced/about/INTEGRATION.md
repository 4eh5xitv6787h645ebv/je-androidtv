# About: integration

- Koin: add `org.jellyfin.androidtv.enhanced.about.aboutModule` to `featureModules`.
- Settings routes: add `org.jellyfin.androidtv.enhanced.about.aboutSettingsRoutes` to `featureSettingsRoutes`
  (`EnhancedSettingsRoutes.ABOUT` → About screen, `AboutRoutes.RELEASE_NOTES` = `/enhanced/about/release-notes` →
  release notes, `AboutRoutes.WHATS_NEW` = `/enhanced/about/whats-new` → all new plugin settings), e.g.
  `val featureSettingsRoutes = mapOf<String, RouteComposable>(…) + aboutSettingsRoutes`. If `aboutSettingsRoutes` is
  already spread into the map, the new route comes along with no change.
- Settings entries: add `org.jellyfin.androidtv.enhanced.about.aboutSettingsEntry` as the **last** entry of
  `featureSettingsEntries`.
- Hub: none. Upstream hooks: none.
- Network: fetches `https://api.github.com/repos/n00bcodr/Jellyfin-Enhanced/releases/latest` once per app run with
  `HttpURLConnection` (fails silently).
- What's New (admins only, `state.isAdmin`): `GET whats-new` → when `newSettings` is non-empty, a "New in this plugin
  version" section with the five newest settings (label, version, key), "Show all N new settings" (→
  `AboutRoutes.WHATS_NEW`) and a Dismiss row. Loading, errors and an empty list show nothing. `POST whats-new/dismiss`
  is only called from that Dismiss row (never on page open); it's server-wide, so the caption says so. Non-admins
  never call either route (the server answers 403 to them anyway).
- Uses `je_ic_catchup_check` (from the Catch Up stream's drawables, same branch) as the Dismiss row icon.

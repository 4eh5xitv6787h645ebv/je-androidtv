# About: integration

- Koin: add `org.jellyfin.androidtv.enhanced.about.aboutModule` to `featureModules`.
- Settings routes: add `org.jellyfin.androidtv.enhanced.about.aboutSettingsRoutes` to `featureSettingsRoutes`
  (`EnhancedSettingsRoutes.ABOUT` → About screen, `AboutRoutes.RELEASE_NOTES` = `/enhanced/about/release-notes` →
  release notes), e.g. `val featureSettingsRoutes = mapOf<String, RouteComposable>(…) + aboutSettingsRoutes`.
- Settings entries: add `org.jellyfin.androidtv.enhanced.about.aboutSettingsEntry` as the **last** entry of
  `featureSettingsEntries`.
- Hub: none. Upstream hooks: none.
- Network: fetches `https://api.github.com/repos/n00bcodr/Jellyfin-Enhanced/releases/latest` once per app run with
  `HttpURLConnection` (fails silently). Never calls `whats-new/dismiss`.

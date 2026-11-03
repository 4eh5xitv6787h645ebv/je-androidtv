# Calendar: integration

- Koin: add `org.jellyfin.androidtv.enhanced.calendar.calendarModule` to `featureModules` in `FeatureModules.kt`.
- Hub: `EnhancedHub.register(org.jellyfin.androidtv.enhanced.calendar.calendarHubEntry)` (id `calendar`, gate
  `state.calendar`, order 40).
- Settings: none. Upstream hooks: none.
- Gates not in `EnhancedConfig.kt`: `CalendarShowOnlyRequested`, `CalendarForceOnlyRequested`. The page decodes them
  itself (`CalendarConfig`, from `public-config`), so nothing needs to change; add them to `EnhancedPublicConfig` if
  another feature wants them.
- `PageSupport.kt` in this package holds small helpers (`attempt`, `canOpen`, `PageChip`, `PageListRow`, `PageImage`,
  `PageNotice`) that the bookmarks, activity and about packages also use.

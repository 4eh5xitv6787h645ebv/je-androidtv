# Activity and active streams: integration

- Koin: add `org.jellyfin.androidtv.enhanced.activity.activityModule` to `featureModules`.
- Hub: `EnhancedHub.register(org.jellyfin.androidtv.enhanced.activity.activityHubEntry)` (id `activity`, gate
  `state.activity || state.activeStreams`, order 60). The page shows active streams on top and the feed below; either
  part can be absent.
- Settings: none. Upstream hooks: none.
- Gates not in `EnhancedConfig.kt`: `ActivityFeedShowWatched/Favorited/Reviewed/ActiveStreams`, decoded by the page
  itself (`ActivityConfig`).

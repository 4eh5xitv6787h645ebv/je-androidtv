# Bookmarks: integration

- Koin: add `org.jellyfin.androidtv.enhanced.bookmarks.bookmarksModule` to `featureModules`. It binds
  `BookmarkDetailsContributor` as a `DetailsContributor` ("Add bookmark" on movie/episode details with a resume
  position, priority 80 so it folds into "⋯" early).
- Hub: `EnhancedHub.register(org.jellyfin.androidtv.enhanced.bookmarks.bookmarksHubEntry)` (id `bookmarks`, gate
  `state.bookmarks`, order 50).
- Settings: none. Upstream hooks: none. Playback goes through upstream's `PlaybackHelper.retrieveAndPlay(ids, false,
  positionTicks, 0, context)`.
- Capturing a bookmark from inside the player lives in `playback/` (the Bookmark action of the player overlay).
- Needs `SpoilerProtection` (bound in `enhancedModule`) for the bookmark list's episode names.

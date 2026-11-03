# Playback: integration

Pause screen, playback-start rating badge, community/critic ratings in the OSD, and bookmarks from the player.

## Koin

Add `org.jellyfin.androidtv.enhanced.playback.playbackModule` to `featureModules` in `FeatureModules.kt`, anywhere
after `bookmarksModule` (it uses `BookmarksRepository` from there). It binds `PlaybackFeaturesRepository` and
`PlaybackBookmarks`. No contributors, hub entries or settings routes: the user settings it reads are already on the
Playback settings page (`JeUserSettings.playback`).

```kotlin
import org.jellyfin.androidtv.enhanced.playback.playbackModule
// …
	bookmarksModule,
	playbackModule,
```

Until the module is registered every hook below is a no-op (the hooks look the classes up with `getOrNull`).

## Upstream hooks (already in this branch)

All three call `org.jellyfin.androidtv.enhanced.playback.EnhancedPlayback` and do nothing without the plugin.

| File | Hook | Purpose |
|---|---|---|
| `ui/playback/CustomPlaybackOverlayFragment.java`, end of `onViewCreated` | `org.jellyfin.androidtv.enhanced.playback.EnhancedPlayback.attach(this, view);` | Adds the non-focusable overlays (pause screen + badge over the video, ratings line in `topPanel`) and starts the player watcher, bound to the view lifecycle. |
| same file, first line of `keyListener.onKey` (the leanback key intercept listener) | `if (org.jellyfin.androidtv.enhanced.playback.EnhancedPlayback.onKey(CustomPlaybackOverlayFragment.this, keyCode, event)) return true;` | Lets the pause screen see remote input. Keys are passed on, except Back while the pause screen is showing, which closes only the pause screen (like Back on a visible OSD). |
| `ui/playback/overlay/CustomPlaybackTransportControlGlue.java`, end of `addMediaActions` | `org.jellyfin.androidtv.enhanced.playback.EnhancedPlayback.addSecondaryActions(getContext(), this, secondaryActionsAdapter);` | Adds the "Bookmark" action after Zoom in the secondary row. |

If an upstream merge conflicts there, keep upstream's version and re-add the line. Add a row to ENHANCED.md's hook
table:

| `ui/playback/CustomPlaybackOverlayFragment.java` | `EnhancedPlayback.attach(…)` at the end of `onViewCreated`, `EnhancedPlayback.onKey(…)` first in `keyListener` |
| `ui/playback/overlay/CustomPlaybackTransportControlGlue.java` | `EnhancedPlayback.addSecondaryActions(…)` at the end of `addMediaActions` |

## Gates

Nothing is added unless `EnhancedState.available`. Per feature:

| Feature | Gate |
|---|---|
| Pause screen | user `PauseScreenEnabled` ?: public `PauseScreenEnabled` ?: true; delay user `PauseScreenDelaySeconds` ?: public ?: 5 (1–60). Videos only, not live TV. |
| Rating badge | public `ShowPlaybackRatingBadge`; colours only with public `ColoredRatingsEnabled` (as on the web) |
| OSD ratings | user `ShowRatingInPlayer` ?: public `ShowRatingInPlayer` ?: true |
| Bookmark action | `state.bookmarks` (public `BookmarksEnabled`); movies and episodes that can seek |

`PauseScreenEnabled`, `PauseScreenDelaySeconds`, `ShowRatingInPlayer` (public defaults) and `ColoredRatingsEnabled`
aren't in `EnhancedPublicConfig`; `PlaybackFeaturesRepository` decodes them itself from `public-config` once per
session token. Moving them into `EnhancedPublicConfig` later is optional.

## Routes

- `GET public-config` (extra fields above).
- `GET user-settings/{uid}/bookmark.json` (through `BookmarksRepository.list()`, once per item per player session, and
  again after any add/delete through `BookmarksRepository`, which bumps its `revision` flow).
- `POST user-settings/{uid}/bookmark.json/add` (through `BookmarksRepository.add()`), body `ItemId, MediaType
  (movie|tv), Name, Timestamp (seconds), Label (formatted time), TmdbId/TvdbId (the show's for episodes),
  SeasonNumber/EpisodeNumber`. No `SyncedFrom`: on the web that field means "migrated from item X" and makes the
  bookmarks page offer a sync clean-up, so a fresh capture leaves it empty like the web does.
- `GET /Items/{seriesId}?userId=` for an episode's show provider ids (server-filtered, like the details action).

# Catch Up: integration

Branch `je/catchup`. No upstream (`app/src/main`) hooks, no settings routes, nothing to add to `EnhancedConfig.kt`.

## Koin module and hub entry

In `FeatureModules.kt`:

```kotlin
import org.jellyfin.androidtv.enhanced.catchup.catchUpHubEntry
import org.jellyfin.androidtv.enhanced.catchup.catchUpModule

val featureModules: List<Module> = listOf(
	// …
	catchUpModule,
	aboutModule,
).also {
	EnhancedHub.register(
		// …
		catchUpHubEntry,
	)
}
```

As a patch against `enhanced` at cb1642089 (compiled with it applied, then reverted):

```diff
@@ -7,6 +7,8 @@ import org.jellyfin.androidtv.enhanced.bookmarks.bookmarksHubEntry
 import org.jellyfin.androidtv.enhanced.bookmarks.bookmarksModule
 import org.jellyfin.androidtv.enhanced.calendar.calendarHubEntry
 import org.jellyfin.androidtv.enhanced.calendar.calendarModule
+import org.jellyfin.androidtv.enhanced.catchup.catchUpHubEntry
+import org.jellyfin.androidtv.enhanced.catchup.catchUpModule
 import org.jellyfin.androidtv.enhanced.discover.DiscoverHubEntries
 import org.jellyfin.androidtv.enhanced.discover.discoverModule
 import org.jellyfin.androidtv.enhanced.management.ManagementEntries
@@ -36,6 +38,7 @@ val featureModules: List<Module> = listOf(
 	calendarModule,
 	bookmarksModule,
 	activityModule,
+	catchUpModule,
 	aboutModule,
 ).also {
 	EnhancedHub.register(
@@ -44,6 +47,7 @@ val featureModules: List<Module> = listOf(
 		calendarHubEntry,
 		bookmarksHubEntry,
 		activityHubEntry,
+		catchUpHubEntry,
 		*ManagementEntries.hubEntries.toTypedArray(),
 	)
 }
```

`catchUpModule` binds `CatchUpDismissStore(androidContext())` and
`CatchUpRepository(EnhancedApi, SessionRepository, CatchUpDismissStore)`; `EnhancedApi` comes from `enhancedModule`,
`SessionRepository` from upstream's `authModule`.

| id | title | gate | order | opens |
|---|---|---|---|---|
| `catchup` | Catch Up | `catchUpEnabled(state)` = `state.available && state.config.catchUpEnabled` | 18 (after Discover 10 / Pick something 15) | `CatchUpFragment` |

`EnhancedDestinations.hub("catchup")` jumps straight to it.

## Gates

- `CatchUpEnabled` is already in `EnhancedPublicConfig` (`config.catchUpEnabled`); nothing to add.
- `CatchUpLogEnabled` is **not** in `public-config` (server-only). The app always posts `catchup/event` and the
  server no-ops (204) unless both `CatchUpEnabled` and `CatchUpLogEnabled` are on, so no gate is needed.
- `CatchUpUsePluginPages` / `CatchUpUseCustomTabs` / `CatchUpUseNativeTab` only choose how the web embeds the page;
  the TV app ignores them.

## Routes called

All as the signed-in user through a pinned `EnhancedApi` (`CatchUpRepository.open()` pins one per page visit):

| Call | Why |
|---|---|
| `GET /Items?userId&IncludeItemTypes=Movie,Series&Recursive=true&Filters=IsUnplayed&SortBy=Random&Limit=20&Fields=…ChildCount&EnableImageTypes=Primary,Backdrop&ImageTypeLimit=1&EnableUserData=true` | deal a batch (refilled when < 6 cards remain) |
| `GET /Items?userId&Ids={id}` | re-check a title after returning from playback/details (dropped if now played or gone) |
| `GET /Shows/{seriesId}/Seasons?userId&Fields=ChildCount` | season picker |
| `GET /Items?userId&ParentId={target}&Recursive=true&IncludeItemTypes=Episode&Filters=IsUnplayed&IsMissing=false` | before marking a show/season: the episodes to mark (server-filtered, so hidden episodes are left alone) |
| `POST` / `DELETE /Users/{uid}/PlayedItems/{id}` | Watched / Undo, always per item (the movie, or each listed episode; never a show or season), 4 at a time. Undo unmarks exactly the ids Watched marked; a partly failed Undo stays on the stack with the ids still marked, so Undo again finishes it. Writes run in `NonCancellable`, so leaving the page doesn't stop them halfway |
| `POST /Users/{uid}/Items/{id}/Rating?likes=true` / `DELETE /Users/{uid}/Items/{id}/Rating` | Watchlist (JE's "Likes" watchlist) / Undo (skipped when the title was already liked) |
| `POST /JellyfinEnhanced/catchup/event` | fire-and-forget log `{type:"swipe",action,undoOf?,itemId,ok,seasons?}` |

Dismissals are local only: `SharedPreferences` file `je_catchup`, key `dismissed_<serverId>_<userId>` (dashless
GUIDs). They are not Hidden Content.

## Resources

`res/values/je_catchup.xml` (`je_catchup_*` strings and plurals), `res/drawable/je_ic_catchup_{style,play_arrow,
check,bookmark_add,close,undo}.xml`. `CatchUpFragment` also reuses `je_ic_about_info` (Details button) and the shared
`je_error_generic`, `je_loading`, `je_retry` strings.

## Shared code used (not changed)

`calendar/PageSupport.kt` (`attempt`, `serverMessage`, `PageChip`, `PageImage`), `ui/hub/EnhancedScreen`,
`LoadStateContent`, upstream `PlaybackHelper`, `DialogBase`, `Destinations.itemDetails`.

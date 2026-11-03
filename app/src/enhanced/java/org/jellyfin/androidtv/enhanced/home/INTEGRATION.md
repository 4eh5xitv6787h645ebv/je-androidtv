# Home rows: integration

Branch `je/home`. Optional Jellyfin Enhanced rows on the upstream home screen ("Coming up", "Jump back in", "Trending
on Seerr", "Catch Up"), each off by default, switched on Settings › Jellyfin Enhanced › Home rows. Nothing to add to
`EnhancedConfig.kt`; no hub entry.

## Koin module and settings (FeatureModules.kt, FeatureSettingsRoutes.kt)

Patch against `enhanced` at 7ba6d6246 (compiled with it applied, then reverted):

```diff
--- a/app/src/enhanced/java/org/jellyfin/androidtv/enhanced/FeatureModules.kt
+++ b/app/src/enhanced/java/org/jellyfin/androidtv/enhanced/FeatureModules.kt
@@ -11,6 +11,7 @@ import org.jellyfin.androidtv.enhanced.catchup.catchUpHubEntry
 import org.jellyfin.androidtv.enhanced.catchup.catchUpModule
 import org.jellyfin.androidtv.enhanced.discover.DiscoverHubEntries
 import org.jellyfin.androidtv.enhanced.discover.discoverModule
+import org.jellyfin.androidtv.enhanced.home.homeModule
 import org.jellyfin.androidtv.enhanced.management.ManagementEntries
 import org.jellyfin.androidtv.enhanced.management.managementModule
 import org.jellyfin.androidtv.enhanced.metadata.metadataModule
@@ -41,6 +42,7 @@ val featureModules: List<Module> = listOf(
 	playbackModule,
 	activityModule,
 	catchUpModule,
+	homeModule,
 	aboutModule,
 ).also {
 	EnhancedHub.register(
--- a/app/src/enhanced/java/org/jellyfin/androidtv/enhanced/ui/settings/FeatureSettingsRoutes.kt
+++ b/app/src/enhanced/java/org/jellyfin/androidtv/enhanced/ui/settings/FeatureSettingsRoutes.kt
@@ -2,6 +2,8 @@ package org.jellyfin.androidtv.enhanced.ui.settings
 
 import org.jellyfin.androidtv.enhanced.about.aboutSettingsEntry
 import org.jellyfin.androidtv.enhanced.about.aboutSettingsRoutes
+import org.jellyfin.androidtv.enhanced.home.homeSettingsEntry
+import org.jellyfin.androidtv.enhanced.home.homeSettingsRoutes
 import org.jellyfin.androidtv.enhanced.management.ManagementEntries
 import org.jellyfin.androidtv.enhanced.metadata.metadataSettingsEntries
 import org.jellyfin.androidtv.enhanced.metadata.metadataSettingsRoutes
@@ -13,11 +15,11 @@ import org.jellyfin.androidtv.ui.navigation.RouteComposable
 val featureSettingsRoutes: Map<String, RouteComposable> =
-	ManagementEntries.settingsRoutes + metadataSettingsRoutes + aboutSettingsRoutes
+	ManagementEntries.settingsRoutes + metadataSettingsRoutes + homeSettingsRoutes + aboutSettingsRoutes
 
 val featureSettingsEntries: List<SettingsEntry> =
-	ManagementEntries.settingsEntries + metadataSettingsEntries + listOf(aboutSettingsEntry)
+	ManagementEntries.settingsEntries + metadataSettingsEntries + listOf(homeSettingsEntry, aboutSettingsEntry)
```

- `homeModule` binds `HomeRowsStore(androidContext(), SessionRepository)` and `HomeRowsRepository(ApiClient,
  EnhancedRepository, CalendarRepository, BookmarksRepository, SeerrRepository, CatchUpRepository, SpoilerProtection,
  HiddenContentRepository)`. It needs `calendarModule`, `bookmarksModule` and `catchUpModule` (already registered).
- Settings route `/enhanced/home-rows` (`HomeSettingsRoutes.HOME_ROWS`) → `HomeRowsSettingsScreen()`. Entry "Home
  rows" (icon `je_ic_home_rows`), shown when the plugin is present and at least one row's gate holds; placed before
  About.
- Until `homeModule` is registered the hooks below are harmless: `EnhancedHomeRows` finds no `HomeRowsStore`, logs
  once and adds nothing.

## Upstream hooks (already in this branch)

`app/src/main/java/org/jellyfin/androidtv/ui/home/HomeRowsFragment.kt`, 4 lines + 1 import:

| Where | Line | Why |
|---|---|---|
| `onCreate`, right after `adapter = MutableObjectAdapter<Row>(PositionableListRowPresenter())` | `val enhancedRows = EnhancedHomeRows(this)` | Starts loading the JE rows at once, in parallel with upstream's |
| `onCreate`, after the `for (section in homesections) when (section) { … }` block | `rows.add(enhancedRows)` | Adds the JE rows after upstream's, once those are in the adapter |
| `ItemViewClickedListener.onItemClicked`, first line | `if (EnhancedHomeRows.onItemClicked(requireContext(), item)) return` | Bookmark cards play from the bookmark, Seerr cards open Seerr details, "Open Catch Up" opens the deck |
| `onResume`, "React to deletion" | `(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)` → `(currentRow!!.adapter as? ItemRowAdapter)?.remove(currentItem)` | The JE rows aren't `ItemRowAdapter`s; deleting an item opened from one of them would crash on the hard cast |

For ENHANCED.md's hooks table:
`ui/home/HomeRowsFragment.kt` | `val enhancedRows = EnhancedHomeRows(this)` in `onCreate`; `rows.add(enhancedRows)` after the
sections; `if (EnhancedHomeRows.onItemClicked(requireContext(), item)) return` first in the click listener; safe
`as? ItemRowAdapter` cast in `onResume` | Optional JE home rows.

## Rows

All four are off by default and stored per server and user in `SharedPreferences` file `je_home`, key
`rows_<serverId>_<userId>` (dash-less lower-case GUIDs), a string set of `coming_up`, `jump_back_in`, `trending`,
`catch_up`. JE's `settings.json` has no keys for them. Rows are added after upstream's rows in this order and appear,
disappear and reload live (settings change, gate change, home resumed).

| Row | Gate | Cards | Click |
|---|---|---|---|
| Coming up | `state.calendar` | Monitored calendar releases of the next 7 days that carry a Jellyfin id and that the server returns for this user (`/Items?ids=`), max 30; "Series" + "S02E07 · Thu" / "Today" / "Tomorrow", movies "Movie" + day; a movie's several releases once | Upstream launcher: details of the episode (or series / movie) |
| Jump back in | `state.bookmarks` | Newest 20 bookmarks whose item still resolves; title = bookmark label or item name, subtitle = name (when labelled) + `m:ss` | Plays from the bookmark (`PlaybackHelper.retrieveAndPlay(ids, false, ticks, 0, ctx)`) |
| Trending on Seerr | `state.discover` (Seerr usable + `RecommendationsPageEnabled`) | Seerr trending, discovery-filtered by `SeerrRepository.loadNext` (hidden, blocklisted, excluded library items), then without anything Seerr links to a Jellyfin item and without pending Spoiler Guard opt-ins; first 20 (up to 3 pages) | Seerr details; long-press = Seerr card menu (Hide removes the card) |
| Catch Up | `state.available && config.catchUpEnabled` | "Open Catch Up" tile, then 10 random unwatched movies/shows not dismissed in Catch Up on this device. On resume the same titles are re-checked (no reshuffle); a fresh deal only when none is left | Tile: `EnhancedDestinations.hub("catchup")`; titles: upstream launcher (details) |

Library cards are `BaseItemDtoBaseRowItem`s (upstream `CardPresenter`, series poster for episodes) built from the
server's DTOs, so card size, focus, background, Menu key and Spoiler Guard artwork are upstream's. Seerr cards use
`SeerrCardPresenter` like the search rows.

## Spoiler Guard and Hidden Content

- Coming up follows the calendar page: Hidden Content's calendar filter (`CalendarHiddenFilter`, failure drops the
  row), `CalendarForceOnlyRequested`, `CalendarLogic.protection`. Releases whose air date is protected are never
  dated: they come from the calendar's fixed today + 90 days range (`upcomingEvents`) and are listed last without a
  day, so their presence doesn't say they air this week. Episode titles are never shown (only the S/E code); artwork
  is always the server's. Unreadable JE config → no row.
- Jump back in names episodes through `BookmarkLogic.name` (neutral "Series · Season 2, Episode 1" for guarded
  episodes). Unreadable JE config → no row.
- Trending leaves out pending Spoiler Guard opt-ins; with Spoiler Guard on but its lists unreadable → no row.
- Catch Up and every library card come from the server's normal item queries, which apply both features.

## Routes called

`GET /JellyfinEnhanced/arr/calendar` (twice when dates may be hidden), `public-config`, `arr/requests?userOnly=true`
(only with `CalendarForceOnlyRequested`), Hidden Content and Spoiler Guard documents (through the shared
repositories), `user-settings/{uid}/bookmark.json`, `jellyseerr/discover/trending?page=n`, and Jellyfin
`GET /Items?ids=…` (chunks of 50) and `GET /Items?IncludeItemTypes=Movie,Series&Recursive=true&Filters=IsUnplayed&SortBy=Random&Limit=30`.

## Resources

`res/values/je_home.xml` (`je_home_*`), `res/drawable/je_ic_home_rows.xml` (Material Symbols `view_agenda`),
`res/drawable/je_home_catchup_tile.xml` (vector tile for the "Open Catch Up" card). Reuses `je_bookmarks_untitled`,
`je_bookmarks_episode_numbered`, `je_bookmarks_episode_number`, `je_bookmarks_episode`.

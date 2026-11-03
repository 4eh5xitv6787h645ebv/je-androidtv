# Management (stream E): integration

Spoiler Guard and Hidden Content management pages, their settings screens, the JE user settings pages ("Details &
tags", "Playback"), Seerr Hide, and Spoiler Guard for collections from the card menu.

No upstream (`app/src/main`) hooks are needed: the card-menu entries go through the existing
`EnhancedItemMenu.contribute/onMenuItemClick` hooks.

## 1. Koin module (`enhanced/FeatureModules.kt`)

```kotlin
import org.jellyfin.androidtv.enhanced.management.managementModule

val featureModules: List<Module> = listOf(
	// …
	managementModule,
)
```

`managementModule` binds `SeerrHideMenuContributor` as `SeerrCardMenuContributor` and `SeerrHideDetailsContributor` as
`SeerrDetailsContributor`. It uses the shared `HiddenContentRepository` from `enhancedModule`.

## 2. Hub entries

```kotlin
import org.jellyfin.androidtv.enhanced.management.ManagementEntries

EnhancedHub.register(*ManagementEntries.hubEntries.toTypedArray())
```

| id | gate | page | order |
|---|---|---|---|
| `spoiler-guard` | `state.spoilerGuard` | `SpoilerGuardFragment` | 60 |
| `hidden-content` | `state.hiddenContent` | `HiddenContentFragment` | 61 |

`EnhancedDestinations.hub("spoiler-guard")` / `hub("hidden-content")` jump straight to them.

## 3. Settings (`enhanced/ui/settings/FeatureSettingsRoutes.kt`)

```kotlin
import org.jellyfin.androidtv.enhanced.management.ManagementEntries

val featureSettingsRoutes: Map<String, RouteComposable> = mapOf<String, RouteComposable>(
	// …
) + ManagementEntries.settingsRoutes

val featureSettingsEntries: List<SettingsEntry> = listOf(
	// …
) + ManagementEntries.settingsEntries
```

Routes: `EnhancedSettingsRoutes.SPOILER_GUARD` (gate `spoilerGuard`), `HIDDEN_CONTENT` (gate `hiddenContent`),
`DETAILS` and `PLAYBACK` (no gate beyond `available`). Each screen also shows a "turned off on this server" message
if opened while its gate is off.

## 4. Gates

Nothing is missing from `EnhancedConfig.kt`. The JE settings pages read their public defaults (`ShowFileSizes`,
`WatchProgressDefaultMode`, `PauseScreenDelaySeconds`, …) from their own `public-config` read, so no new fields are
needed there.

## 5. Shared files changed (owned by this stream)

- `data/SpoilerGuardRepository.kt`: `SpoilerGuardPrefs` has every category field (nullable); new
  `SpoilerGuardEntry`/`SpoilerGuardPendingEntry`, `entries()`, `pendingEntries()`, `parseGuid()`, `parseInstant()`,
  `getPrefs()`, `setPrefs(changes)` (GET → change → POST whole object, pinned), `removeEntry()`, and `cachedState()`
  (last `getState()` for the current token, used for the synchronous card menu). Existing signatures unchanged.
- `data/HiddenContentRepository.kt`: `HiddenContentSettings` gained `FilterLibrary`, `FilterUpcoming`,
  `FilterCalendar`, `FilterSearch`, `FilterRequests`, `ShowButtonJellyseerr`, `ShowButtonLibrary`,
  `ExperimentalHideCollections` (appended, with the plugin's defaults); new `Snapshot.isHiddenTmdb()`, `hideTmdb()`,
  `unhideTmdb()`, `removeEntries(keys)`. Existing signatures unchanged.
- `ui/menu/EnhancedItemMenu.kt`: "Spoiler Guard" entry on collection (BoxSet) cards; Hide is no longer offered on
  collections unless the user's `ExperimentalHideCollections` is on (as on the web); the remove-from-home toast says
  where to restore the card.

## 6. Focus polish (stream T, branch `je/polish`)

Nothing new to register. Changes:

- `ui/hub/ListFocus.kt` (new): focus requesters per row key, `moveTo(key)` (now if the row is placed, else retried
  for a few frames through `Effect()`), and `ListFocus.neighbour(order, removed)` (next remaining row, else the
  previous one, else null). Pages move the focus *before* dropping a row, so Compose never loses it to the toolbar.
- Hidden Content page: after Unhide/Restore the focus goes to the next entry's Unhide/Restore button (repeated presses
  walk down the list), else the previous entry's; when the filtered list empties but other entries remain, to the
  header's Scope filter button; when nothing is left, the empty state's Back. "Restore all" moves it to the Scope
  filter first (or the empty state when everything was restored).
- Spoiler Guard page: after "Turn off" the focus goes to the next row's "Turn off" (or the previous one's), else the
  empty state's Back.
- `ManagementRow` has a new `actionModifier` (for the trailing button's focus requester); `actionFocusKey(rowKey)`
  names it.
- `LoadStateContent` (`ui/hub/EnhancedScreen.kt`) has a new `emptyAction: EmptyAction? = rememberBackAction()`: the
  empty state shows a focusable button ("Back" → `NavigationRepository.goBack()` by default; pass your own or null)
  that takes the focus unless something else below the toolbar already has it (`EnhancedScreen` tracks that), so a
  Requests tab or a page with header filters keeps its focus. This covers the empty Spoiler Guard, Hidden Content and
  Bookmarks pages, the Requests tabs (through `PagedGrid`), Catch Up without a session and the reviews reader.
- Settings: `EnhancedToggleRow` / `EnhancedChoiceRow` take `initialFocus`; the first toggle of the Spoiler Guard
  (first category, or "skip confirmation" when the admin allows no category), Hidden Content (master switch),
  Details & tags and Playback pages gets it, so the focus no longer starts on the page heading. New
  `enhancedSettingsUnavailable(message)` adds a focused Back row under the "turned off on this server" message.

Other hub pages checked (not owned, unchanged):

- Calendar: empty agenda keeps the focus on the filter row / week navigation (`fallbackFocus`). OK.
- Catch Up: the empty deck has a focused "Start over". OK.
- Requests: the tab row stays focusable; empty tabs now also get Back from `PagedGrid`'s `LoadStateContent`. OK.
- Activity: **dead end** when there are no active streams (or they're hidden/failed), at most one feed category and an
  empty or turned-off feed: nothing on the page is focusable. Suggested patch for the activity owner (untested, not
  applied; `ActivityFragment.FeedContent`, just before `if (entries == null || entryKeys == null) {`):

  ```kotlin
  // Nothing else on the page takes the focus: give the D-pad somewhere to land
  val nothingFocusable = sessions.isEmpty() && (entries == null || (entries.isEmpty() && categories.size <= 1))
  if (nothingFocusable && currentStreams != Streams.Loading) item(key = "back") {
  	Button(onClick = { navigationRepository.goBack() }, modifier = Modifier.focusRequester(firstFocus)) {
  		Text(stringResource(R.string.je_hub_back))
  	}
  }
  ```

  (`navigationRepository` is already injected there; needs `org.jellyfin.androidtv.ui.base.button.Button`.)

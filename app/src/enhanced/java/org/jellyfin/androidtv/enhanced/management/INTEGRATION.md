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

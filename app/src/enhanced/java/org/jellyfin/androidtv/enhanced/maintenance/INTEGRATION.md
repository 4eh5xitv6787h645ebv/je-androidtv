# Maintenance notice (stream T): integration

Branch `je/polish`. The plugin's maintenance mode (inventory #41) as a banner at the top of the Enhanced hub and a
one-time dialog on app start. No upstream (`app/src/main`) hooks, no settings routes, no hub entry.

## 1. Koin module (`enhanced/FeatureModules.kt`)

```kotlin
import org.jellyfin.androidtv.enhanced.maintenance.maintenanceModule

val featureModules: List<Module> = listOf(
	// …
	maintenanceModule,
)
```

As a patch against `enhanced` at 7ba6d6246 (compiled and built with it applied, then reverted):

```diff
@@ -11,6 +11,7 @@ import org.jellyfin.androidtv.enhanced.catchup.catchUpHubEntry
 import org.jellyfin.androidtv.enhanced.catchup.catchUpModule
 import org.jellyfin.androidtv.enhanced.discover.DiscoverHubEntries
 import org.jellyfin.androidtv.enhanced.discover.discoverModule
+import org.jellyfin.androidtv.enhanced.maintenance.maintenanceModule
 import org.jellyfin.androidtv.enhanced.management.ManagementEntries
 import org.jellyfin.androidtv.enhanced.management.managementModule
 import org.jellyfin.androidtv.enhanced.metadata.metadataModule
@@ -42,6 +43,7 @@ val featureModules: List<Module> = listOf(
 	activityModule,
 	catchUpModule,
 	aboutModule,
+	maintenanceModule,
 ).also {
 	EnhancedHub.register(
 		*DiscoverHubEntries.all,
```

`maintenanceModule` binds:

| Binding | What |
|---|---|
| `MaintenanceRepository(EnhancedApi)` | reads `GET /JellyfinEnhanced/public-config` fresh each time (the cached `EnhancedState` is loaded once per sign-in, a window can start or end any time) |
| `MaintenanceDismissStore(androidContext())` | `SharedPreferences` file `je_maintenance`, key `seen_<serverId>_<userId>` (dashless) → the window whose dialog was dismissed |
| `MaintenanceHubNotice(MaintenanceRepository)` `bind HubNotice::class` | the hub banner |
| `MaintenanceNotifier(androidContext(), EnhancedRepository, SessionRepository, MaintenanceRepository, MaintenanceDismissStore)` with `createdAtStart = true` | the app-start dialog. Must be eager: nothing else asks for it. It registers `Application.ActivityLifecycleCallbacks` and, on `MainActivity`, `FragmentLifecycleCallbacks`, so it needs no hook |

## 2. Gates and fields

Nothing to add to `EnhancedConfig.kt`. `EnhancedPublicConfig` already has `MaintenanceModeEnabled/Message/EndsAt`, but
the feature decodes its own `MaintenanceInfo` (adds `MaintenanceModeAction` and `MaintenanceModeAffectedUsers`) from
its own fresh `public-config` read. Admin status is `EnhancedState.isAdmin`.

Plugin facts (checked in `JellyfinEnhancedController.cs` / `MaintenanceModeService.cs` on `origin/main`):
`MaintenanceModeEnabled` is the live state (`IsActive || config toggle`, so scheduled and timed windows count),
`MaintenanceModeMessage` is the live message (may contain `{countdown}` / `{ends_at}`), `MaintenanceModeEndsAt` is a
UTC ISO timestamp only while a timed window is active (absent otherwise), `MaintenanceModeAction` is
`none|disable_accounts|disable_remote|both`, and `MaintenanceModeAffectedUsers` is only the *shape* `all|selected`
(the anonymous endpoint never sends the ids). There is no start time in public-config (`StartedAt` is only in the
admin-only `MaintenanceMode/Status`).

## 3. Behaviour

- Applies (`MaintenanceLogic.notice`): enabled and end time not passed. Every user for action `none`; for disabling
  actions administrators (never disabled by the plugin) get the banner only, worded "Maintenance mode is on for other
  users", and no dialog. Non-admins are told even when only selected users are affected (they can't know they
  aren't), with a "yours may not be" line.
- Banner (hub only, between the header and the tiles): not focusable, so the first tile keeps the focus; title,
  message with tokens resolved, "Back around 9:30 PM · 1h 05m left"; refreshed every 30 s, disappears at the end time.
- Dialog: once per window per server and user. A window is known by its end time, or by its message while
  open-ended. Remembered when the user closes it (OK or Back); forgotten when the app later sees maintenance off, so
  the next window shows again even with the same text. It waits while a player screen
  (`org.jellyfin.androidtv.ui.playback.*`, `…ui.player.*`) is resumed and closes without counting as seen if one
  opens, so it never covers playback. It only appears on `MainActivity`, never on the startup/login screens.
  Checked once per loaded sign-in (process start or user switch), not on resume from background.

## 4. Shared files changed (`ui/hub`, owned by this stream for the notice slot)

- New `ui/hub/HubNotice.kt`: `interface HubNotice { @Composable fun Content(state: EnhancedState) }`.
- `ui/hub/EnhancedHubFragment.kt`: draws every Koin-bound `HubNotice` (`getKoin().getAll<HubNotice>()`) above the
  grid, inside a `Column`. With none bound it looks exactly as before.
- New `ui/hub/ListFocus.kt` and the `LoadStateContent` empty action in `ui/hub/EnhancedScreen.kt`: see the focus
  section of `management/INTEGRATION.md`.

## 5. Resources

`res/values/je_maintenance.xml` (`je_maintenance_*`), `res/drawable/je_ic_maintenance_construction.xml` (Material
Symbols "construction"), `res/values/je_hub.xml` (`je_hub_back`, the empty-state Back).

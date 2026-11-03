# Requests page: integration

Stream C (Requests, watchlist, downloads). Everything lives in `enhanced/requests/`; nothing in shared files or
`app/src/main` was changed.

## Koin module

Add to `FeatureModules.kt`:

```kotlin
import org.jellyfin.androidtv.enhanced.requests.requestsModule

val featureModules: List<Module> = listOf(
	// …
	requestsModule,
)
```

`requestsModule` binds a single `RequestsRepository(EnhancedApi, EnhancedRepository, SeerrRepository,
HiddenContentRepository, SpoilerGuardRepository)`; all five come from `enhancedModule`. No contributors.

## Hub entry

Register once, next to the module (wherever the other `EnhancedHub.register(...)` calls go):

```kotlin
import org.jellyfin.androidtv.enhanced.requests.requestsHubEntry

EnhancedHub.register(requestsHubEntry)
```

`requestsHubEntry`: id `requests`, title "Requests", icon `je_ic_requests_list_alt`, order 20, gate
`state.requests` (Seerr linked for the user and `DownloadsPageEnabled`), destination
`RequestsDestinations.requests()` (a `fragmentDestination<RequestsFragment>()`).
`EnhancedDestinations.hub("requests")` then jumps straight to it.

Verified with this exact patch applied locally (`assembleDebug` + unit tests green), then removed:

```diff
--- a/app/src/enhanced/java/org/jellyfin/androidtv/enhanced/FeatureModules.kt
+++ b/app/src/enhanced/java/org/jellyfin/androidtv/enhanced/FeatureModules.kt
@@
 package org.jellyfin.androidtv.enhanced
 
+import org.jellyfin.androidtv.enhanced.requests.requestsHubEntry
+import org.jellyfin.androidtv.enhanced.requests.requestsModule
+import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedHub
 import org.koin.core.module.Module
@@
 val featureModules: List<Module> = listOf(
-)
+	requestsModule,
+).also {
+	EnhancedHub.register(requestsHubEntry)
+}
```

## Settings

None. The page follows the server's settings only.

## Upstream hooks

None.

## Gates

All read from the existing `EnhancedState`; nothing is missing from `EnhancedConfig.kt`:

| Control | Gate |
|---|---|
| Hub entry / page | `state.requests` |
| Queue tab | `config.showDownloadsInRequests` |
| History tab | `config.downloadsShowHistory` and (`!config.downloadsHistoryAdminOnly` or `state.isAdmin`); the server still answers `visible:false` when it hides history, shown as an explanation |
| Issues tab | `config.downloadsPageShowIssues` |
| "Only mine" default | `config.downloadsFilterByUserRequests` |
| Approve / Decline | the response's `canApproveRequests` and the request's own `requestStatus == 1` |
| "Sync watchlists now" | `state.isAdmin && config.syncJellyseerrWatchlist` |
| Queue polling | `config.downloadsPagePollingEnabled`, every `config.downloadsPollIntervalSeconds` (min 5 s), only while the Queue tab is visible and the fragment is resumed |

Not in `EnhancedConfig.kt` and deliberately not used: `DownloadsUsePluginPages`, `DownloadsUseCustomTabs`,
`DownloadsUseNativeTab` (web placement options). Hidden Content's `FilterRequests` user setting is read straight from
`hidden-content.json` (`Settings.FilterRequests`, default true) because `HiddenContentSettings` doesn't have it; if
the data-layer owner adds it there, `RequestsRepository.hiddenFilter()` can use it instead.

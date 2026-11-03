# Discover: integration

Branch `je/discover`. No upstream (`app/src/main`) hooks are needed.

## Koin module

Add to `FeatureModules.kt`:

```kotlin
import org.jellyfin.androidtv.enhanced.discover.discoverModule

val featureModules: List<Module> = listOf(
	discoverModule,
)
```

`discoverModule` binds `DiscoverRepository(EnhancedApi, EnhancedRepository, EnhancedSettingsRepository,
SeerrRepository, ApiClient, SpoilerGuardRepository)`; all of these come from `enhancedModule` / the app modules.

## Hub entries

Register both from wherever the hub entries are registered (e.g. next to the module list):

```kotlin
import org.jellyfin.androidtv.enhanced.discover.DiscoverHubEntries

EnhancedHub.register(*DiscoverHubEntries.all)
```

| id | title | gate | opens |
|---|---|---|---|
| `discover` | Discover (order 10) | `state.discover` (Seerr usable + `RecommendationsPageEnabled`) | `DiscoverFragment` |
| `random` | Pick something (order 15) | `randomPickEnabled(state)`: user `RandomButtonEnabled`, else `true` (the plugin default) | `RandomPickFragment`, which replaces itself with the picked item's details |

`EnhancedDestinations.hub("discover")` / `hub("random")` jump straight to them. `DiscoverDestinations.discover()` and
`DiscoverDestinations.randomPick()` are the plain destinations.

## Settings

No settings screen or routes. The random pick reads `RandomUnwatchedOnly`, `RandomIncludeMovies`,
`RandomIncludeShows` and `RandomSourceId` from the user's `settings.json` (public-config defaults otherwise), so
whoever builds the JE user settings editor can expose them there.

## Gates missing from `EnhancedConfig.kt` (optional)

The random pick decodes its own defaults from `public-config` (`RandomPickDefaults`). For the synchronous hub
gate to follow the server default instead of assuming "on", add to `EnhancedPublicConfig`:

```kotlin
@SerialName("RandomButtonEnabled") val randomButtonEnabled: Boolean = false,
```

and change `randomPickEnabled` in `DiscoverModule.kt` to
`state.available && (state.userSettings.randomButtonEnabled ?: state.config.randomButtonEnabled)`.

## Shared code changed in this branch

- `seerr/SeerrModels.kt` (additive): `SeerrSource.Discover`, `DiscoverGenre`, `Company`, `Network`, `Collection`
  (serial names `discover`, `discover-genre`, `company`, `network`, `collection`), `SeerrDiscoverFeed`, `SeerrSort`,
  `SeerrSource.sortable`. Any exhaustive `when (source)` elsewhere needs the new cases (only
  `SeerrRepository.feedsFor` has one today).
- `seerr/SeerrRepository.kt`: `feedsFor(source, sort)` overload (the old `feedsFor(source)` is unchanged and
  means `POPULAR`), feeds for the new sources, public `collectionParts(collectionTmdbId)` (`collectionMissing` now
  uses it, same behaviour).
- `ui/seerr/SeerrDiscoveryFragment.kt`: Popular / Top rated / Newest / Oldest buttons for sortable sources (kept
  across details and back, saved in instance state); the All / Movies / TV switch is hidden for single-type
  sources. Other streams can open any grid with `EnhancedDestinations.seerrDiscovery(SeerrSource.Collection(id, name))`
  etc.

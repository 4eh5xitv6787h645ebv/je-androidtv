# Integration: details metadata and reviews (stream F)

Two packages: `enhanced/metadata/` (info row, cast ages, content warnings, Seerr details section, Where to watch
and trigger settings) and `enhanced/reviews/` (Rate button, Reviews row, reader). `reviewsModule` needs
`metadataModule` (it uses `MetadataRepository` for TMDB id resolution). No upstream hooks, no new hub pages.

## Koin (`FeatureModules.kt`)

```kotlin
import org.jellyfin.androidtv.enhanced.metadata.metadataModule
import org.jellyfin.androidtv.enhanced.reviews.reviewsModule

val featureModules: List<Module> = listOf(
	// …
	metadataModule,
	reviewsModule,
)
```

`metadataModule` binds `MetadataDetailsContributor` as `DetailsContributor` and `MetadataSeerrContributor` as
`SeerrDetailsContributor`; `reviewsModule` binds `ReviewsDetailsContributor` as `DetailsContributor`. Register
metadata before reviews so the "Enhanced info" row comes before the "Reviews" row.

## Settings (`FeatureSettingsRoutes.kt`)

```kotlin
import org.jellyfin.androidtv.enhanced.metadata.metadataSettingsEntries
import org.jellyfin.androidtv.enhanced.metadata.metadataSettingsRoutes

val featureSettingsRoutes: Map<String, RouteComposable> = mapOf<String, RouteComposable>(
	// …
) + metadataSettingsRoutes

val featureSettingsEntries: List<SettingsEntry> = listOf(
	// …
) + metadataSettingsEntries
```

Routes (constants in `MetadataSettingsRoutes`; add them to `EnhancedSettingsRoutes` if you prefer one list):
`/enhanced/elsewhere`, `/enhanced/elsewhere/region`, `/enhanced/elsewhere/services`, `/enhanced/content-warnings`.
Entries: "Where to watch" (gate `ElsewhereEnabled && TmdbEnabled`) and "Content warning triggers" (gate
`DoesTheDogDieEnabled`).

## Hub

None.

## Upstream hooks

None. The info rows are appended after upstream's rows because `EnhancedDetails.addRows` appends contributor rows.
If you want the "Enhanced info" row directly under the overview, a positional hint on `DetailsContributor` (e.g.
`rowsAtTop`) would be needed in the shared seam; the feature works without it.

## Gates read outside `EnhancedConfig.kt`

`MetadataRepository.publicConfig()` decodes its own `MetadataPublicConfig` from `public-config` (re-read when
`EnhancedRepository.state` changes): `ShowFileSizes`, `ShowWatchProgress`, `WatchProgressDefaultMode`,
`WatchProgressTimeFormat`, `ShowAudioLanguages`, `PeopleTagsEnabled`, `ReviewsExpandedByDefault` (public defaults of
user settings), `DEFAULT_REGION`, `DEFAULT_PROVIDERS`, `IGNORE_PROVIDERS`, `MdblistRatingsSources`. Consider moving
them into `EnhancedPublicConfig` later; nothing else needs changing.

## Spoiler protection (`data/SpoilerProtection.kt`)

`SpoilerProtection` resolves, per title, which Spoiler Guard categories apply to data the plugin doesn't filter
(TMDB/MDBList/Seerr ratings, release dates, reviews, content warnings, Sonarr/Radarr calendar data, saved bookmark
names). It is bound once in the shared `enhancedModule` next to `SpoilerGuardRepository` and shared by the
metadata, reviews, calendar and bookmarks features.

`reviewsModule`'s `ReviewsDetailsContributor` takes it as a fourth constructor argument (already updated in
`ReviewsModule.kt`).

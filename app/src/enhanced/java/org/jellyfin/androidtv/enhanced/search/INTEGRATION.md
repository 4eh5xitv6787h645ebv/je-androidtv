# Search (stream A): integration notes

Seerr results in the app's search screen: a "Movies & shows on Seerr" row (paged, ends in "View all"), a
"Collections on Seerr" row and a "People on Seerr" row, below the library rows.

## Koin module

Add `searchModule` (`org.jellyfin.androidtv.enhanced.search.searchModule`, in `SearchModule.kt`) to
`featureModules` in `enhanced/FeatureModules.kt`:

```kotlin
import org.jellyfin.androidtv.enhanced.search.searchModule

val featureModules: List<Module> = listOf(
	searchModule,
)
```

It binds `SeerrSearchRepository(get(), get(), get())` (EnhancedApi, HiddenContentRepository, SeerrRepository).
Without the module the hooks below do nothing: the session looks the repository up with `getOrNull()`.

## Hub entries / settings

None. Search has no hub page and no settings of its own (the gates are the plugin's).

## Upstream hooks (already in this branch, `app/src/main/java/org/jellyfin/androidtv/ui/search/`)

Add these to the hook table in `ENHANCED.md`:

| File | Hook |
|---|---|
| `ui/search/SearchViewModel.kt` | `val seerr = SeerrSearchSession(viewModelScope)` field; `seerr.onQuery(trimmed, debounce)` right after `previousQuery = trimmed` in `searchDebounced` |
| `ui/search/SearchFragmentDelegate.kt` | `val seerrRows = SeerrSearchRows(context, rowsAdapter)`; `seerrRows.reattach()` after the library adapters' `Retrieve()` in `showResults`; `if (seerrRows.onItemClicked(item)) return@…` first in the click listener; `seerrRows.onItemSelected(item, row)` plus a safe `(item as? BaseRowItem)` cast in the selection listener |
| `ui/search/SearchFragment.kt` | `launch { searchFragmentDelegate.seerrRows.bind(viewModel.seerr) }` in the `LaunchedEffect`, before `searchResultsFlow.collect` |

`SearchResultGroup.kt` is unchanged.

## Shared files touched

- `enhanced/data/HiddenContentRepository.kt`: added `filterSearch` (`FilterSearch`, default false, as the plugin's
  `UserConfiguration`) to `HiddenContentSettings`. Additive only.

## Gates used (all already in `EnhancedConfig.kt`)

- Rows at all: `state.seerrUsable && config.jellyseerrShowSearchResults`.
- People row: also `config.jellyseerrShowPersonDiscovery` (a person card opens person discovery).
- Collections row: also `config.showCollectionsInSearch`.
- Filtering: `config.jellyseerrSearchExcludeLibraryItems` (search flag, not the discovery one),
  `config.jellyseerrExcludeBlocklistedItems`, Hidden Content `Settings.Enabled && Settings.FilterSearch`.

## ENHANCED.md

The "Not done yet" line "Seerr search from the app's search screen." can go; suggested Features row:

| Seerr in search | Search screen | Movies and shows from Seerr (with their status) below the library results, paged with a "View all" grid, plus the collections of the movie hits and matching people. "Title (1999)" narrows Seerr results to that year. |

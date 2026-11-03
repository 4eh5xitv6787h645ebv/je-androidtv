# Stream D integration: Request More, issue reporting, richer Seerr details

## Koin module

Add to `FeatureModules.kt`:

```kotlin
import org.jellyfin.androidtv.enhanced.seerrdetails.seerrDetailsModule

val featureModules: List<Module> = listOf(
	// …
	seerrDetailsModule,
)
```

`seerrDetailsModule` (in `seerrdetails/SeerrDetailsModule.kt`) binds:

| Binding | Type | What |
|---|---|---|
| `SeerrIssueRepository(get())` | single | Seerr issue list/report calls |
| `RequestMoreAction(get(), get())` | `DetailsContributor` | "Request more" on library series |
| `IssueDetailsContributor(get(), get())` | `DetailsContributor` | "Report a problem" on library movies/series/seasons/episodes |
| `IssueSeerrDetailsContributor(get(), get())` | `SeerrDetailsContributor` | "Report a problem" button and open-issues section on Seerr details |

Dependencies: `EnhancedApi` (from `enhancedModule`) and `NavigationRepository` (app module).

The richer Seerr details sections (cast row, seasons row, requester lines, age rating / original title) live in
`ui/seerr/SeerrDetailsFragment.kt` itself and need no registration. `SeerrDetailsLoader` is a stateless object, so the
fragment works even without `seerrDetailsModule` (only the issue contributions would be missing).

## Hub entries / settings

None. (An issues list on the Requests page belongs to the Requests stream; it can reuse
`issues/SeerrIssueRepository` and `issues/SeerrIssueModels.kt`.)

## Upstream hooks

None.

## Gates

All gates used already exist in `EnhancedConfig.kt`:
- Request More: `state.seerrUsable && config.jellyseerrShowRequestMoreOnSeries`
- Report a problem: `state.seerrUsable && config.jellyseerrShowReportButton`
- Open-issues indicator: `state.seerrUsable && config.jellyseerrShowIssueIndicator`

## Shared files touched

- `ui/seerr/SeerrDetailsFragment.kt` (owned by this stream): now loads details through `SeerrDetailsLoader` (same
  `jellyseerr/{type}/{id}` call, decoded into `SeerrDetails` plus `SeerrDetailsExtras`), loads the library copy's
  seasons for shows, scrolls vertically, and composes contributed `sections` after the requester lines. The request
  button and season picker now use `SeerrSeasonAvailability` (open requests and library seasons count), so a show
  Seerr calls "Available" can still request missing seasons.
- `SeerrModels.kt` is **not** modified: the extra fields are decoded in `seerrdetails/SeerrDetailsExtras.kt`, so there
  is nothing to merge with stream B there.

## Suggested ENHANCED.md rows

| Feature | Where | What it does |
|---|---|---|
| Request more | Show details | Appears when Seerr still has regular seasons to request (not in the library, not requested); opens the Seerr details screen with the season picker. |
| Report a problem | Movie, show, season and episode details; Seerr details | Reports a video, audio, subtitle or other problem to Seerr, for the whole title or a season/episode (prefilled from the episode you came from), with an optional message. The Seerr details screen shows how many issues are open and lists them. |
| Richer Seerr details | Seerr details | Cast row (opens the person's discovery), seasons with availability, who requested it and when, age rating and original title. Protected library titles use the server's own cast and show no Seerr season names. |

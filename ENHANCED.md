# Jellyfin Enhanced for Android TV

This is the official [Jellyfin Android TV client](https://github.com/jellyfin/jellyfin-androidtv) with a native
[Jellyfin Enhanced](https://github.com/n00bcodr/Jellyfin-Enhanced) (JE) layer on top. Everything upstream does still
works the same way; the extra screens, buttons and rows only appear when the server runs the Jellyfin Enhanced plugin
and the feature is turned on there (and, where JE has one, in the user's own JE settings). On a server without the
plugin the app looks and behaves like stock. It installs as its own app ("Jellyfin Enhanced",
`org.jellyfin.androidtv.enhanced`), so it can sit next to the official app.

## Features

"Seerr" means the plugin's Jellyseerr/Overseerr integration is set up (`JellyseerrEnabled`) and the signed-in user is
linked to a Seerr account. Gates are public-config flags unless marked "user" (the user's JE `settings.json`, falling
back to the server default). Packages are under `app/src/enhanced/java/org/jellyfin/androidtv/enhanced/`.

| Feature | Where in the app | JE gate / setting | Package |
|---|---|---|---|
| Enhanced hub | "Enhanced" button in the main toolbar | plugin present and at least one hub page enabled | `ui/hub`, `ui/EnhancedToolbarButton.kt` |
| Seerr in search | Search screen, below the library rows | Seerr + `JellyseerrShowSearchResults`; people row `JellyseerrShowPersonDiscovery`; collections row `ShowCollectionsInSearch` | `search` |
| Discover | Hub | Seerr + `RecommendationsPageEnabled` | `discover` |
| Pick something (random title) | Hub, Discover header | user `RandomButtonEnabled` (on by default) | `discover` |
| Catch Up deck | Hub | `CatchUpEnabled` | `catchup` |
| Requests, watchlist, downloads, issues | Hub | Seerr + `DownloadsPageEnabled`; tabs: `ShowDownloadsInRequests`, `DownloadsShowHistory` (+ `DownloadsHistoryAdminOnly`), `DownloadsPageShowIssues` | `requests` |
| Calendar | Hub | `CalendarPageEnabled` | `calendar` |
| Bookmarks | Hub page, "Add bookmark" on details, Bookmark action in the player | `BookmarksEnabled` | `bookmarks`, `playback` |
| Activity and active streams | Hub | `ActivityFeedEnabled` or `ActiveStreamsEnabled` (non-admins also need `ActiveStreamsAllUsers`) | `activity` |
| Spoiler Guard | Details button, collection card menu, Seerr details (pending opt-in), hub page, settings | `SpoilerBlurEnabled` | `data`, `ui/details`, `ui/menu`, `management` |
| Hidden Content (Hide/Unhide) | Details, card menu, Seerr cards and details, hub page, settings | `HiddenContentEnabled` + the user's Hidden Content "show Hide buttons" options | `data`, `ui/menu`, `management` |
| Remove from Continue Watching / Next Up | Card menu on those home rows | user `RemoveContinueWatchingEnabled` | `ui/menu`, `data` |
| More from… and Seerr rows | Movie, show, person and collection details | Seerr + `JellyseerrShowPerson/Genre/Network/Tag/CollectionDiscovery`, `JellyseerrShowRecommended`, `JellyseerrShowSimilar` | `ui/details`, `ui/seerr`, `seerr` |
| Seerr details and requests | Any Seerr card | Seerr | `ui/seerr`, `seerrdetails` |
| Request more | Show details | Seerr + `JellyseerrShowRequestMoreOnSeries` | `requests/RequestMoreAction.kt` (bound in `seerrdetails`) |
| Report a problem, open issues | Library details, Seerr details | Seerr + `JellyseerrShowReportButton`; indicator `JellyseerrShowIssueIndicator` | `issues`, `seerrdetails` |
| Enhanced info row | Library details | user `ShowFileSizes`, `ShowWatchProgress`, `ShowAudioLanguages`, `PeopleTagsEnabled`; `ShowReleaseDates`, `MdblistRatingsEnabled` + `MdblistRatingsShowOnItemDetails`, `ShowAwards`, `ElsewhereEnabled` + `TmdbEnabled` | `metadata` |
| Cast ages | Library details | user `PeopleTagsEnabled` | `metadata` |
| Content warnings | Library details button | `DoesTheDogDieEnabled` | `metadata` |
| Ratings, awards, where to watch on Seerr details | Seerr details | same flags as the info row | `metadata` |
| Reviews and "Rate" | Library details | `ShowUserReviews` (JE reviews), `ShowReviews` + `TmdbEnabled` (TMDB reviews) | `reviews` |
| Pause screen | Video player | user `PauseScreenEnabled`, `PauseScreenDelaySeconds` | `playback` |
| Rating badge at playback start | Video player | `ShowPlaybackRatingBadge` (colours with `ColoredRatingsEnabled`) | `playback` |
| Community and critic ratings in the OSD | Video player | user `ShowRatingInPlayer` | `playback` |
| JE settings | Settings › Jellyfin Enhanced | plugin present | `ui/settings`, `management`, `metadata`, `about` |
| What's New | Settings › Jellyfin Enhanced › About | administrators only | `about` |

## Hub pages

The toolbar's "Enhanced" button opens a grid of the pages that apply to this server and user, in this order:

| Page | What it shows |
|---|---|
| Discover | Trending, Popular and Upcoming Seerr rows (each ending in "View all"), genre tiles and curated studio and network tiles, all opening the Seerr discovery grid (Popular / Top rated / Newest / Oldest where Seerr can sort). "Pick something for me" in the header. |
| Pick something | Picks a random library title (following the user's `RandomUnwatchedOnly`, `RandomIncludeMovies/Shows`, `RandomSourceId`) and replaces itself with its details, so Back returns to the hub. |
| Catch Up | Random unwatched movies and shows one card at a time, with Watch, Details, Watched (season picker for shows), Watchlist (JE's "Likes"), Dismiss and Undo. Dismissals are kept on the device per server and user; they are not Hidden Content. Actions are logged to `catchup/event`. |
| Requests | Tabs Requests (status filters, "Only mine", Approve/Decline for Seerr managers), Watchlist ("Sync watchlists now" for admins with `SyncJellyseerrWatchlist`), Queue (polled while visible when `DownloadsPagePollingEnabled`), History and Issues. |
| Calendar | Two-week agenda of Sonarr, Radarr and Shoko releases grouped by day, with week navigation and filters (Radarr, Sonarr, Movies, Episodes, Downloaded, My requests). Opens the title in the library when the user can see it there, otherwise on Seerr. |
| Bookmarks | The user's timestamp bookmarks, newest first. OK plays from the saved moment; long-press offers Open details and Delete. Bookmarks whose item is gone are marked and can be deleted. |
| Activity | Active streams on top (refreshed every 30 s while visible) and the Watched / Favorited / Reviewed feed below. |
| Spoiler Guard | Every protected show, movie and collection, plus pending opt-ins for titles not in the library yet. Select opens the title; "Turn off" removes protection (confirming unless the user skipped that). |
| Hidden Content | Everything the user hid, everywhere or only from Continue Watching / Next Up, with scope and type filters, per-entry Unhide/Restore and "Restore all". |

`EnhancedDestinations.hub("<id>")` opens the hub straight on a page (ids `discover`, `random`, `catchup`, `requests`,
`calendar`, `bookmarks`, `activity`, `spoiler-guard`, `hidden-content`).

## Details-screen additions

On the library item details screen (`FullDetailsFragment`):

- Buttons: Spoiler Guard (movies and shows), Hide/Unhide (movies, shows, seasons, episodes, people; episodes and
  seasons ask whether to hide the item or the whole show), More from… (cast and crew, genres, studios/networks, tags,
  Seerr recommendations and similar titles), Request more (shows with seasons still requestable on Seerr), Report a
  problem (movies, shows, seasons, episodes; seasons and episodes report against the show with the season/episode
  prefilled), Rate (half-star picker with optional review text), Add bookmark (movies and episodes with a resume
  position) and Content warnings (DoesTheDogDie, with the user's triggers counted).
- The screen only fits six buttons. Contributed buttons are ordered by priority and the least important move into
  upstream's "Other options" (⋯) menu, the same way upstream handles its own (Favorite, trailers, More from…, Hide,
  Rate, Add bookmark, Report a problem typically end up there).
- Rows, after upstream's rows: "Enhanced info" (file size, watch progress, audio languages, release dates, MDBList and
  Seerr ratings, awards, where to watch, a person's birth facts), "Cast ages", "Reviews" (JE reviews first, then
  TMDB's; cards open a full-screen reader), "Recommended" and "Similar" Seerr titles on movies and shows, "More from
  <person>" on cast pages and the missing parts of a collection.
- Seerr details (any Seerr card, `SeerrDetailsFragment`): poster, overview and status, Request (standard requests;
  a season picker for shows that counts open requests and the library's own seasons), "Open in library" once
  available, "Enable Spoiler Guard" as a pending opt-in, Hide/Unhide, Report a problem with the open issues listed,
  ratings/awards/where to watch, a cast row (opens the person's discovery), seasons with availability, who requested
  it and when, age rating and original title. Protected library titles use the server's own cast and show no Seerr
  season names.
- Card menu (long-press OK or Menu on any card): Hide; "Remove from Continue Watching" / "Remove from Next Up" on those
  home rows; Spoiler Guard on collection cards (collections open in the library browser, which has no button row).
  Hide on collections only with the user's `ExperimentalHideCollections`, as on the web. Seerr cards get Hide too.

## Settings pages

Settings › Jellyfin Enhanced (only when the plugin is present). Every page edits the user's JE documents through the
plugin, so the web client sees the same choices.

| Page | Contents | Shown when |
|---|---|---|
| Spoiler Guard | Which categories protected titles hide for this user (show and episode descriptions, episode titles, chapter names, trickplay, cast, ratings, air dates, reviews, taglines, tags) within what the admin allows, and skipping the turn-off confirmation | `SpoilerBlurEnabled` |
| Hidden Content | Where hidden titles are filtered (library, discovery, search, upcoming, calendar, recommendations, requests, Next Up, Continue Watching) and which Hide buttons show (details, library, cast, Seerr) | `HiddenContentEnabled` |
| Details & tags | Watch progress (on/off, percentage or time, time format), file sizes, audio languages, reviews expanded by default, server-drawn poster tags (`UseNativePosterTags`), people tags, "Remove from Continue Watching", the random button (unwatched only, movies, shows) | plugin present |
| Playback | Pause screen and its delay, `ShowRatingInPlayer` (community and critic ratings in the OSD), JE auto-skip intro/credits (saved for the web client) | plugin present |
| Where to watch | Region and streaming services for "where to watch" | `ElsewhereEnabled` + `TmdbEnabled` |
| Content warning triggers | DoesTheDogDie topics to highlight | `DoesTheDogDieEnabled` |
| About Jellyfin Enhanced | Plugin and server versions, Seerr link state, enabled features, latest JE release notes (from GitHub); for admins plugin diagnostics and "New in this plugin version" (What's New) with a server-wide Dismiss row | plugin present |

## Screenshots

Taken on the Android TV emulator against the lab server (Jellyfin 12.1, JE 12.13, Seerr) on 3 November 2026, except
the four marked "22 Oct", which are from the first release and still match.

| | |
|---|---|
| ![Hub](docs/enhanced/hub.jpg) The hub as an admin, with Catch Up | ![Hub as a viewer](docs/enhanced/hub-viewer.jpg) The hub as a non-admin user |
| ![Seerr in search](docs/enhanced/search-seerr-rows.jpg) Seerr titles and collections in search | ![Discover](docs/enhanced/discover.jpg) Discover |
| ![Hide on a Seerr card](docs/enhanced/seerr-card-hide.jpg) Hide in a Seerr card's menu | ![Discovery grid](docs/enhanced/discovery-grid.jpg) Seerr discovery for an actor (22 Oct) |
| ![Seerr movie details](docs/enhanced/seerr-details-movie.jpg) Seerr details: ratings, awards, where to watch, cast | ![Seerr show details](docs/enhanced/seerr-details-show.jpg) Seerr details of a show: Request more, Report a problem, requester, seasons |
| ![Request more](docs/enhanced/request-more-seasons.jpg) Requesting the missing seasons | ![Details overflow](docs/enhanced/details-other-options.jpg) Hide, More from…, Rate, Add bookmark and Report a problem in "Other options" |
| ![More from menu](docs/enhanced/more-from-menu.jpg) "More from…" lists cast, crew, genres, studios and tags (22 Oct) | ![Person row](docs/enhanced/person-more-from-row.jpg) "More from" row on a cast page (22 Oct) |
| ![Rate](docs/enhanced/rate-dialog.jpg) Rating a title | ![Remove from Continue Watching](docs/enhanced/remove-from-continue-watching.jpg) Card menu on Continue Watching (22 Oct) |
| ![Requests](docs/enhanced/requests.jpg) Requests with Approve/Decline | ![Calendar](docs/enhanced/calendar.jpg) Calendar |
| ![Calendar with Spoiler Guard](docs/enhanced/calendar-spoiler-guard.jpg) A guarded show's releases, listed without dates | ![Bookmarks](docs/enhanced/bookmarks.jpg) Bookmarks |
| ![Hidden Content](docs/enhanced/hidden-content.jpg) Hidden Content with scope labels | ![Activity](docs/enhanced/activity.jpg) Activity |
| ![Spoiler Guard page](docs/enhanced/spoiler-guard-page.jpg) Spoiler Guard page | ![Spoiler Guard on](docs/enhanced/spoiler-guard-on.jpg) Turning Spoiler Guard on for a show (22 Oct) |
| ![Catch Up](docs/enhanced/catch-up.jpg) Catch Up | ![Catch Up seasons](docs/enhanced/catch-up-seasons.jpg) Catch Up season picker |
| ![Pause screen](docs/enhanced/pause-screen.jpg) Pause screen | ![Rating badge](docs/enhanced/rating-badge.jpg) Rating badge at playback start |
| ![OSD ratings](docs/enhanced/player-osd-ratings.jpg) OSD while the badge is up; the ratings line sits under it | ![Player bookmarks](docs/enhanced/player-bookmarks.jpg) Bookmark action with saved bookmarks |
| ![Bookmark saved](docs/enhanced/player-bookmark-saved.jpg) Bookmark saved from the player | ![Settings](docs/enhanced/settings.jpg) Settings › Jellyfin Enhanced |
| ![Spoiler Guard settings](docs/enhanced/settings-spoiler-guard.jpg) Spoiler Guard categories | ![Details & tags settings](docs/enhanced/settings-details.jpg) Details & tags |
| ![Playback settings](docs/enhanced/settings-playback.jpg) Playback settings | ![About](docs/enhanced/settings-about.jpg) About Jellyfin Enhanced |
| ![What's New](docs/enhanced/whats-new.jpg) What's New for admins | |

## Upstream hooks

All fork code lives in `app/src/enhanced/` (Kotlin sources and resources, added to the `main` source set by a block
at the end of `app/build.gradle.kts`). Upstream files only get these hook lines. Each calls into the fork and does
nothing on a server without the plugin. `git diff upstream/master..HEAD --stat -- app/src/main` lists them all.

| Upstream file (`app/src/main/java/org/jellyfin/androidtv/…`) | Hook line(s) | Purpose |
|---|---|---|
| `app/build.gradle.kts` (repo path) | `android { … }` block at the end | Application id `org.jellyfin.androidtv.enhanced`, `src/enhanced` source and resource dirs, app name and search authority |
| `di/KoinInitializer.kt` | `enhancedModule` in the module list | Registers the fork's Koin bindings (it includes every feature module) |
| `ui/itemdetail/FullDetailsFragment.java` | `ps.addClassPresenter(EnhancedComposeRow.class, …)`; `EnhancedDetails.addRows(…)` after `addAdditionalRows`; `EnhancedDetails.addButtons(…)` before the "more" button; `if (EnhancedDetails.onItemClicked(item)) return;` first in the row click listener | Details buttons, Compose and Seerr rows, clicks on Seerr cards |
| `ui/itemdetail/FullDetailsFragmentHelper.kt` | `EnhancedDetails.addOverflowItems(…)` at the end of `showDetailsMenu` | Buttons that didn't fit go into "Other options" |
| `util/KeyProcessor.java` | `EnhancedItemMenu.contribute(…)` in `createItemMenu`; `return EnhancedItemMenu.onMenuItemClick(…)` as the click listener's fallback | Card menu entries (Hide, remove from home rows, Spoiler Guard on collections) |
| `ui/home/HomeRowsFragment.kt` | `EnhancedItemMenu.onItemSelected(item, row)` first in the item selected listener | Remembers which home row a card is in, for "Remove from Continue Watching / Next Up" |
| `ui/shared/toolbar/MainToolbar.kt` | `Enhanced` in `MainToolbarActiveButton`; `EnhancedToolbarButton(…)` after the Search button | The "Enhanced" toolbar button that opens the hub |
| `ui/settings/routes.kt` | `val routes = enhancedSettingsRoutes + mapOf(…)` | JE settings pages |
| `ui/settings/screen/SettingsMainScreen.kt` | `item { EnhancedSettingsEntry() }` | The "Jellyfin Enhanced" entry in Settings |
| `ui/search/SearchViewModel.kt` | `val seerr = SeerrSearchSession(viewModelScope)`; `seerr.onQuery(trimmed, debounce)` after `previousQuery = trimmed` | Runs the Seerr search with the library search |
| `ui/search/SearchFragmentDelegate.kt` | `val seerrRows = SeerrSearchRows(context, rowsAdapter)`; `seerrRows.reattach()` after the adapters' `Retrieve()`; `if (seerrRows.onItemClicked(item)) return@…` first in the click listener; `seerrRows.onItemSelected(item, row)` and a safe `(item as? BaseRowItem)` cast in the selection listener | Seerr rows below the library rows |
| `ui/search/SearchFragment.kt` | `launch { searchFragmentDelegate.seerrRows.bind(viewModel.seerr) }` before `searchResultsFlow.collect` | Binds the rows to the search session |
| `ui/playback/CustomPlaybackOverlayFragment.java` | `EnhancedPlayback.attach(this, view)` at the end of `onViewCreated`; `if (EnhancedPlayback.onKey(…)) return true;` first in `keyListener.onKey` | Pause screen, rating badge and OSD ratings overlays; lets the pause screen see remote keys (only Back while it shows is consumed) |
| `ui/playback/overlay/CustomPlaybackTransportControlGlue.java` | `EnhancedPlayback.addSecondaryActions(…)` at the end of `addMediaActions` | The "Bookmark" action after Zoom |

## How it talks to the plugin

The app calls the plugin's REST API (`/JellyfinEnhanced/…`) with the signed-in user's token, through the Jellyfin
SDK client the app already uses (`EnhancedApi`). Multi-call writes are pinned to the user who started them, so
switching accounts mid-write can't mix users. Spoiler Guard and Hidden Content filtering of library data happens on
the server, in the normal Jellyfin API responses, so for library items the app only toggles them. Seerr calls go
through the plugin's proxy, which acts as the linked Seerr user and applies its permission checks. Public-config flags
and user settings only decide whether a control is shown; the server decides whether a call succeeds, and its error
message is shown (never turned into an empty list).

**Spoiler Guard for data the plugin doesn't filter.** TMDB, MDBList and Seerr ratings, release dates, reviews,
DoesTheDogDie warnings, Sonarr/Radarr calendar data and names saved with bookmarks reach the app unfiltered.
`data/SpoilerProtection.kt` resolves per title which categories apply, mirroring the plugin's
`ShouldStrip(adminOn, userOverride)`: the admin's `SpoilerStrip*` flags are the cap and a user override of `false` opts
out of a category. Membership comes from the user's Spoiler Guard lists (series, movies, collections and the movies
in them). Played movies and episodes pass through like on the server, except that reviews stay withheld. The player
features use only the item DTO the player already holds, which the server has filtered.

**Fail closed.** Only a 404 from `public-config` means "no plugin". Any other failure marks the plugin config as
unavailable (retried on the next load) and every title counts as fully protected. The same applies when Spoiler Guard
is on and anything needed to decide is missing: the user's lists, the policy, or a guarded collection's members (the
plugin's Hidden Content filter drops hidden items from collection listings, so every hidden item counts as a possible
member, and an unreadable Hidden Content document leaves membership unknown). Fully protected means no external
story, ratings, dates, cast, reviews, tags or artwork. Releases whose air date is protected never appear in the
calendar's dated weeks; they are listed apart from a fixed range (today + 90 days). The Reviews row and content
warnings are left out, bookmarks show no saved episode name, and the calendar and bookmarks pages show an error with
Retry when protection can't be resolved.

| Area | Code |
|---|---|
| API client and plugin state (gates) | `api/EnhancedApi.kt`, `data/EnhancedRepository.kt`, `data/EnhancedConfig.kt` |
| User JE documents (`settings.json`, `elsewhere.json`, …) | `data/EnhancedSettingsRepository.kt` |
| Spoiler Guard lists and policy | `data/SpoilerGuardRepository.kt`, `data/SpoilerProtection.kt` |
| Hidden Content and home removal | `data/HiddenContentRepository.kt` |
| Seerr discovery and requests | `seerr/`, `ui/seerr/`, `seerrdetails/`, `issues/` |
| Details buttons and rows, card menu | `ui/details/`, `ui/menu/` |
| Feature modules, hub entries, settings entries | `FeatureModules.kt`, `ui/settings/FeatureSettingsRoutes.kt`, each package's `INTEGRATION.md` |

## Install

Sideload the APK onto your TV, for example with `adb install jellyfin-enhanced.apk` or the Downloader app.
The local build delivered in `../artifacts/jellyfin-enhanced-androidtv.apk` is optimized with R8 and signed with
this machine's Android debug key for testing. Use your own persistent release key for distribution.

## Build

Needs JDK 21 and the Android SDK (platform 37).

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew assembleRelease        # needs a keystore, see below
./gradlew :app:testDebugUnitTest # unit tests (fork tests are in app/src/test/kotlin/enhanced/)
```

Release signing uses the same `keystore.*` / `signing.*` Gradle properties as upstream. Without them the release APK
is unsigned; sign it with `apksigner` before installing.

## Keeping up with upstream

```sh
scripts/enhanced-sync-upstream.sh          # merges jellyfin/jellyfin-androidtv master
scripts/enhanced-sync-upstream.sh v0.20.0  # or a release tag
```

Upstream files only carry the hook lines in [Upstream hooks](#upstream-hooks), so merges rarely conflict. If one
does, it will be in one of those spots: keep upstream's version and re-add the hook line.
`git diff upstream/master..HEAD -- app/src/main` shows them.

## Verification

- Unit tests: the last full run (3 November 2026) passed all 336 app unit tests, 244 of them in the fork's packages
  (decoders, id and casing conversions, gates, filters, Spoiler Guard policy, season availability, Catch Up deck and
  season selection, pause-screen timer, rating colours, bookmarks). The fail-closed fixes merged after it add their
  own tests (`EnhancedRepositoryTest`, `SpoilerProtectionServiceTest`, `CalendarLogicTest`).
- Device: each feature was exercised with the D-pad on the Android TV emulator against an isolated lab server
  (Jellyfin 12.1, JE 12.13, Seerr), as an admin and as a non-admin user: search, Discover, Seerr details for movies
  and shows, requests and Request more, Report a problem, Rate, bookmarks from details and from the player, Hide and
  Unhide, all hub pages, all JE settings pages and About, Catch Up (Dismiss, Watched, Watchlist and their Undo, season
  picker), pause screen, rating badge, OSD ratings, and the calendar with a Spoiler Guarded show.
- Stock behaviour: on a plain Jellyfin 12.1 server without the plugin there is no Enhanced button and no JE settings
  entry, and search and details work as upstream.
- The merged code went through independent review rounds; their must-fix findings (Spoiler Guard in metadata,
  calendar and bookmarks, Hidden Content write locking, activity row keys) were fixed before these checks.
- The first release (22 October 2026) was also smoke-tested as an R8-optimized signed APK.

## Known limitations

- Seerr requests are standard quality only, by design: no 4K option and no server, quality profile or root folder
  choice; Seerr's defaults apply. Shows still get the season picker.
- Upstream's login form submits twice when Enter is pressed on the password field; the first token is revoked and the
  app can crash once. Use the Sign in button. This is upstream behaviour, not changed by the fork.
- The calendar leaves out unmonitored Sonarr/Radarr entries, like the web calendar does by default.
- JE's auto-skip intro/credits settings are saved for the web client only. The app skips according to its own
  Settings › Playback › Media segments.
- Player features: the pause screen is for videos only (not live TV); the Bookmark action is only on movies and
  episodes that can seek, saves at once with the time as its label (no label editor in the player), and reads the
  item's bookmarks once per playback, so bookmarks added elsewhere meanwhile show up next time; ratings and the badge
  come only from the server-filtered item, so a protected title shows only what the server lets through; the badge
  shows for 5 seconds after the first frame and covers the OSD ratings line if the OSD is opened in that time.
- The details screen fits six buttons; the rest are in "Other options". The "Enhanced info" row comes after
  upstream's rows, not directly under the overview.
- Hidden Content is saved as a whole document, as on the web, so a hide made on another device at the same moment can
  be overwritten. The plugin has no per-item API for global hides.
- Catch Up dismissals are stored on the device, not on the server.
- What's New "Dismiss" is server-wide (for every administrator), as on the web.
- Bookmarks whose item was replaced or removed are marked and can be deleted, but are not re-linked to a new copy.

## Not done yet

- Review translation and AniList reviews (JE and TMDB reviews are shown).
- Client-drawn poster tags (quality, language, rating, age). Only the server-drawn native poster tags setting is
  offered.
- JE playback extras beyond the pause screen: keyboard shortcuts, auto-pause/resume, auto PiP, hold for 2×.
- JE subtitle style synchronisation.
- Editing bookmark labels.
- Maintenance banner, admin operations (Spoiler Guard repair, cross-user Hidden Content, review moderation, active
  stream broadcast) and external links to Seerr, Sonarr/Radarr or Letterboxd.

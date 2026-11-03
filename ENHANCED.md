# Jellyfin Enhanced for Android TV

This is the official [Jellyfin Android TV client](https://github.com/jellyfin/jellyfin-androidtv) with a native
[Jellyfin Enhanced](https://github.com/n00bcodr/Jellyfin-Enhanced) (JE) layer on top. Everything upstream does still
works the same way; the extra screens, buttons and rows only appear when the server runs the Jellyfin Enhanced plugin
and the feature is turned on there (and, where JE has one, in the user's own JE settings). On a server without the
plugin the app looks and behaves like stock. It installs as its own app ("Jellyfin Enhanced",
`org.jellyfin.androidtv.enhanced`), so it can sit next to the official app.

## Features

"Seerr" means the plugin's Jellyseerr/Overseerr integration is set up (`JellyseerrEnabled`) and the signed-in user is
linked to a Seerr account. Nobody signs in to Seerr from the app: the plugin links a Jellyfin user to the Seerr account
carrying their Jellyfin user id, importing them automatically when its `JellyseerrAutoImportUsers` option is on, and an
administrator can import everyone from the About page (see [How it talks to the plugin](#how-it-talks-to-the-plugin)).
Gates are public-config flags unless marked "user" (the user's JE `settings.json`, falling back to the server
default). Packages are under `app/src/enhanced/java/org/jellyfin/androidtv/enhanced/`.

| Feature | Where in the app | JE gate / setting | Package |
|---|---|---|---|
| Enhanced hub | "Enhanced" button in the main toolbar | plugin present and at least one hub page enabled | `ui/hub`, `ui/EnhancedToolbarButton.kt` |
| Maintenance notice | Banner at the top of the hub; one-time dialog after start-up | the plugin's maintenance mode (`MaintenanceModeEnabled`, read fresh from `public-config`) | `maintenance`, `ui/hub/HubNotice.kt` |
| Home rows: Coming up, Jump back in, Trending on Seerr, Catch Up | Home screen, after upstream's rows | each off until turned on in Settings › Jellyfin Enhanced › Home rows (stored on the device); then `CalendarPageEnabled`, `BookmarksEnabled`, Seerr + `RecommendationsPageEnabled`, `CatchUpEnabled` | `home` |
| Seerr in search | Search screen, below the library rows | Seerr + `JellyseerrShowSearchResults`; people row `JellyseerrShowPersonDiscovery`; collections row `ShowCollectionsInSearch` | `search` |
| Discover | Hub | Seerr + `RecommendationsPageEnabled` (shown with a link notice when only the Seerr link is missing) | `discover` |
| Pick something (random title) | Hub, Discover header | user `RandomButtonEnabled` (on by default) | `discover` |
| Catch Up deck | Hub | `CatchUpEnabled` | `catchup` |
| Requests, watchlist, downloads, issues | Hub | Seerr + `DownloadsPageEnabled` (shown with a link notice when only the Seerr link is missing); tabs: `ShowDownloadsInRequests`, `DownloadsShowHistory` (+ `DownloadsHistoryAdminOnly`), `DownloadsPageShowIssues` | `requests` |
| Calendar | Hub | `CalendarPageEnabled` | `calendar` |
| Bookmarks | Hub page (play, open details, rename, delete), "Add bookmark" on details, Bookmark action in the player | `BookmarksEnabled` | `bookmarks`, `playback` |
| Activity and active streams | Hub | `ActivityFeedEnabled` or `ActiveStreamsEnabled` (non-admins also need `ActiveStreamsAllUsers`) | `activity` |
| Spoiler Guard | Details button, collection card menu, Seerr details (pending opt-in), hub page, settings | `SpoilerBlurEnabled` | `data`, `ui/details`, `ui/menu`, `management` |
| Hidden Content (Hide/Unhide) | Details, card menu, Seerr cards and details, hub page, settings | `HiddenContentEnabled` + the user's Hidden Content "show Hide buttons" options | `data`, `ui/menu`, `management` |
| Remove from Continue Watching / Next Up | Card menu on those home rows | user `RemoveContinueWatchingEnabled` | `ui/menu`, `data` |
| More from… and Seerr rows | Movie, show, person and collection details | Seerr + `JellyseerrShowPerson/Genre/Network/Tag/CollectionDiscovery`, `JellyseerrShowRecommended`, `JellyseerrShowSimilar` | `ui/details`, `ui/seerr`, `seerr` |
| Seerr details and requests | Any Seerr card | Seerr | `ui/seerr`, `seerrdetails` |
| Seerr account link | About page (link status, admin "Import Jellyfin users into Seerr"), link notice on Discover and Requests, TV-worded link errors on every Seerr screen | `JellyseerrEnabled`; automatic linking follows the plugin's `JellyseerrAutoImportUsers` | `seerr/SeerrLink*.kt`, `ui/seerr/SeerrLinkUi.kt`, `about` |
| Request more | Show details | Seerr + `JellyseerrShowRequestMoreOnSeries` | `requests/RequestMoreAction.kt` (bound in `seerrdetails`) |
| Report a problem, open issues | Library details, Seerr details | Seerr + `JellyseerrShowReportButton`; indicator `JellyseerrShowIssueIndicator` | `issues`, `seerrdetails` |
| Enhanced info row | Library details | user `ShowFileSizes`, `ShowWatchProgress`, `ShowAudioLanguages`, `PeopleTagsEnabled`; `ShowReleaseDates`, `MdblistRatingsEnabled` + `MdblistRatingsShowOnItemDetails`, `ShowAwards`, `ElsewhereEnabled` + `TmdbEnabled` | `metadata` |
| Cast ages | Library details | user `PeopleTagsEnabled` | `metadata` |
| Content warnings | Library details button | `DoesTheDogDieEnabled` | `metadata` |
| Ratings, awards, where to watch on Seerr details | Seerr details | same flags as the info row | `metadata` |
| Reviews and "Rate" | Library details | `ShowUserReviews` (JE reviews), `ShowReviews` + `TmdbEnabled` (TMDB reviews), `ShowAnilistReviews` + an AniList provider id on the item or its series (AniList reviews) | `reviews` |
| Review translation | Reviews reader (OK on a review) | `ReviewTranslationEnabled` + a translation server set (`ReviewTranslationServer`, plugin 12.14+) | `reviews` |
| Pause screen | Video player | user `PauseScreenEnabled`, `PauseScreenDelaySeconds` | `playback` |
| Rating badge at playback start | Video player | `ShowPlaybackRatingBadge` (colours with `ColoredRatingsEnabled`) | `playback` |
| Community and critic ratings in the OSD | Video player | user `ShowRatingInPlayer` | `playback` |
| Native poster tags | Every poster the app loads from the server | server `NativePosterTagsEnabled`, user `UseNativePosterTags` (Details & tags page) | none: the server composites quality, codec, audio, rating and language badges into the poster images |
| Subtitle style sync | Player subtitles; Settings › Jellyfin Enhanced › Subtitle style | plugin present; applied only when the user turns it on for this device and user `DisableCustomSubtitleStyles` is off | `subtitles` |
| JE settings | Settings › Jellyfin Enhanced | plugin present | `ui/settings`, `management`, `metadata`, `home`, `subtitles`, `about` |
| What's New | Settings › Jellyfin Enhanced › About | administrators only | `about` |

## Hub pages

The toolbar's "Enhanced" button opens a grid of the pages that apply to this server and user, in this order. While
the plugin's maintenance mode applies to the user, a banner above the grid shows its message and end time (refreshed
every 30 s; not focusable). Administrators see "Maintenance mode is on for other users" when the mode disables
accounts or remote access, since it never disables them. The same notice comes up once per maintenance window as a
dialog after start-up, never over the player or the sign-in screens.

| Page | What it shows |
|---|---|
| Discover | Trending, Popular and Upcoming Seerr rows (each ending in "View all"), genre tiles and curated studio and network tiles, all opening the Seerr discovery grid (Popular / Top rated / Newest / Oldest where Seerr can sort). "Pick something for me" in the header. When the user's Seerr account isn't linked, a notice with "Check again" (admins: "Import Jellyfin users into Seerr") replaces the page. |
| Pick something | Picks a random library title (following the user's `RandomUnwatchedOnly`, `RandomIncludeMovies/Shows`, `RandomSourceId`) and replaces itself with its details, so Back returns to the hub. |
| Catch Up | Random unwatched movies and shows one card at a time, with Watch, Details, Watched (season picker for shows), Watchlist (JE's "Likes"), Dismiss and Undo. Dismissals are kept on the device per server and user; they are not Hidden Content. Actions are logged to `catchup/event`. |
| Requests | Tabs Requests (status filters, "Only mine", Approve/Decline for Seerr managers), Watchlist ("Sync watchlists now" for admins with `SyncJellyseerrWatchlist`), Queue (polled while visible when `DownloadsPagePollingEnabled`), History and Issues. The same Seerr link notice as Discover replaces the page when the account isn't linked. |
| Calendar | Two-week agenda of Sonarr, Radarr and Shoko releases grouped by day, with week navigation and filters (Radarr, Sonarr, Movies, Episodes, Downloaded, My requests). Opens the title in the library when the user can see it there, otherwise on Seerr. |
| Bookmarks | The user's timestamp bookmarks, newest first. OK plays from the saved moment; long-press, Menu or the row's ⋯ button offers Play from here, Open details, Rename (empty label = the time) and Delete (with confirmation). Changes apply in place, so the focus stays on the list. Bookmarks whose item is gone are marked and can be deleted. |
| Activity | Active streams on top (refreshed every 30 s while visible) and the Watched / Favorited / Reviewed feed below. |
| Spoiler Guard | Every protected show, movie and collection, plus pending opt-ins for titles not in the library yet. Select opens the title; "Turn off" removes protection (confirming unless the user skipped that). |
| Hidden Content | Everything the user hid, everywhere or only from Continue Watching / Next Up, with scope and type filters, per-entry Unhide/Restore and "Restore all". |

Empty Spoiler Guard, Hidden Content and Bookmarks pages, empty Requests tabs and the reviews reader show a focusable
Back button; after Unhide, Restore or "Turn off" the focus moves to the next row.

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
  AniList's, then TMDB's; cards open a full-screen reader), "Recommended" and "Similar" Seerr titles on movies and
  shows, "More from <person>" on cast pages and the missing parts of a collection.
- AniList reviews show their AniList score. In the full-screen reader, when the server
  translates reviews, OK translates the review (asking for the language the first time, remembered per server and
  user on the device) and toggles back to the original; holding OK or Menu picks another language. Translations are
  kept in memory for the session.
- Seerr details (any Seerr card, `SeerrDetailsFragment`): poster, overview and status, Request (standard requests;
  a season picker for shows that counts open requests and the library's own seasons), "Open in library" once
  available, "Enable Spoiler Guard" as a pending opt-in, Hide/Unhide, Report a problem with the open issues listed,
  ratings/awards/where to watch, a cast row (opens the person's discovery), seasons with availability, who requested
  it and when, age rating and original title. Protected library titles use the server's own cast and show no Seerr
  season names.
- Card menu (long-press OK or Menu on any card): Hide; "Remove from Continue Watching" / "Remove from Next Up" on those
  home rows; Spoiler Guard on collection cards (collections open in the library browser, which has no button row).
  Hide on collections only with the user's `ExperimentalHideCollections`, as on the web. Seerr cards get Hide too.

## Home rows

Four optional rows after upstream's home rows, each off by default and switched on in Settings › Jellyfin Enhanced ›
Home rows (per server and user, stored on the device; JE's `settings.json` has no keys for them). They appear,
disappear and reload live (settings change, gate change, home screen resumed); a row that fails to load is left out.

| Row | Shows | Select |
|---|---|---|
| Coming up | Monitored calendar releases of the next 7 days that are in the library and visible to the user (max 30), "S02E07 · Thu" / "Today" / "Tomorrow"; never episode titles. Releases whose date Spoiler Guard hides come last, without a day | Details of the episode, series or movie |
| Jump back in | The newest 20 bookmarks whose item still exists, label and time | Plays from the bookmark |
| Trending on Seerr | Seerr trending titles not in the library, without hidden, blocklisted or pending Spoiler Guard titles (max 20) | Seerr details; long-press for Hide |
| Catch Up | An "Open Catch Up" tile, then 10 random unwatched movies and shows not dismissed in Catch Up | The deck, or the title's details |

Library cards are upstream's cards built from the server's own items, so Spoiler Guard, Hidden Content and poster
tags apply as everywhere else.

## Settings pages

Settings › Jellyfin Enhanced (only when the plugin is present). The pages edit the user's JE documents through the
plugin, so the web client sees the same choices; Home rows and Subtitle style are the exceptions and stay on the
device. The Spoiler Guard, Hidden Content, Details & tags and Playback pages open with the focus on their first
toggle; a page that is turned off on the server shows a focused Back.

| Page | Contents | Shown when |
|---|---|---|
| Spoiler Guard | Which categories protected titles hide for this user (show and episode descriptions, episode titles, chapter names, trickplay, cast, ratings, air dates, reviews, taglines, tags) within what the admin allows, and skipping the turn-off confirmation | `SpoilerBlurEnabled` |
| Hidden Content | Where hidden titles are filtered (library, discovery, search, upcoming, calendar, recommendations, requests, Next Up, Continue Watching) and which Hide buttons show (details, library, cast, Seerr) | `HiddenContentEnabled` |
| Details & tags | Watch progress (on/off, percentage or time, time format), file sizes, audio languages, reviews expanded by default, server-drawn poster tags (`UseNativePosterTags`), people tags, "Remove from Continue Watching", the random button (unwatched only, movies, shows) | plugin present |
| Playback | Pause screen and its delay, `ShowRatingInPlayer` (community and critic ratings in the OSD), JE auto-skip intro/credits (saved for the web client) | plugin present |
| Home rows | On/off for Coming up, Jump back in, Trending on Seerr and Catch Up (stored on the device, per server and user) | plugin present and at least one row's gate holds |
| Subtitle style | "Use my Jellyfin Enhanced subtitle style on this device" (stored on the device, per server and user) and what the JE style becomes here: preview, size, colours, outline, position, and what can't be mapped (font, horizontal position, shadows). Turning it off restores the app's own subtitle settings, keeping changes made in the app meanwhile | plugin present |
| Where to watch | Region and streaming services for "where to watch" | `ElsewhereEnabled` + `TmdbEnabled` |
| Content warning triggers | DoesTheDogDie topics to highlight | `DoesTheDogDieEnabled` |
| About Jellyfin Enhanced | Plugin and server versions, Seerr link state ("Linked as <name>" for admins, "Not linked…" with Check again), for admins "Import Jellyfin users into Seerr", enabled features, latest JE release notes (from GitHub); for admins plugin diagnostics and "New in this plugin version" (What's New) with a server-wide Dismiss row | plugin present |

## Screenshots

Taken on the Android TV emulator against the lab server (Jellyfin 12.1, JE 12.13, Seerr) on 3 November 2026, except
the four marked "22 Oct", which are from the first release and still match.

| | |
|---|---|
| ![Hub](docs/enhanced/hub.jpg) The hub as an admin, with Catch Up | ![Hub as a viewer](docs/enhanced/hub-viewer.jpg) The hub as a non-admin user |
| ![Home rows](docs/enhanced/home-rows.jpg) Coming up, Jump back in and Trending on Seerr on the home screen | ![Catch Up home row](docs/enhanced/home-rows-catch-up.jpg) The Catch Up home row with its "Open Catch Up" tile |
| ![Native poster tags](docs/enhanced/native-poster-tags.jpg) Server-drawn native poster tags (quality, codec, audio, ratings, language) | ![Home rows settings](docs/enhanced/settings-home-rows.jpg) Settings › Jellyfin Enhanced › Home rows |
| ![Seerr in search](docs/enhanced/search-seerr-rows.jpg) Seerr titles and collections in search | ![Discover](docs/enhanced/discover.jpg) Discover |
| ![Hide on a Seerr card](docs/enhanced/seerr-card-hide.jpg) Hide in a Seerr card's menu | ![Discovery grid](docs/enhanced/discovery-grid.jpg) Seerr discovery for an actor (22 Oct) |
| ![Seerr movie details](docs/enhanced/seerr-details-movie.jpg) Seerr details: ratings, awards, where to watch, cast | ![Seerr show details](docs/enhanced/seerr-details-show.jpg) Seerr details of a show: Request more, Report a problem, requester, seasons |
| ![Request more](docs/enhanced/request-more-seasons.jpg) Requesting the missing seasons | ![Details overflow](docs/enhanced/details-other-options.jpg) Hide, More from…, Rate, Add bookmark and Report a problem in "Other options" |
| ![More from menu](docs/enhanced/more-from-menu.jpg) "More from…" lists cast, crew, genres, studios and tags (22 Oct) | ![Person row](docs/enhanced/person-more-from-row.jpg) "More from" row on a cast page (22 Oct) |
| ![Rate](docs/enhanced/rate-dialog.jpg) Rating a title | ![Remove from Continue Watching](docs/enhanced/remove-from-continue-watching.jpg) Card menu on Continue Watching (22 Oct) |
| ![Requests](docs/enhanced/requests.jpg) Requests with Approve/Decline | ![Calendar](docs/enhanced/calendar.jpg) Calendar |
| ![Calendar with Spoiler Guard](docs/enhanced/calendar-spoiler-guard.jpg) A guarded show's releases, listed without dates | ![Bookmarks](docs/enhanced/bookmarks.jpg) Bookmarks, one renamed, each with its ⋯ menu button |
| ![Hidden Content](docs/enhanced/hidden-content.jpg) Hidden Content with scope labels | ![Activity](docs/enhanced/activity.jpg) Activity |
| ![Spoiler Guard page](docs/enhanced/spoiler-guard-page.jpg) Spoiler Guard page | ![Spoiler Guard on](docs/enhanced/spoiler-guard-on.jpg) Turning Spoiler Guard on for a show (22 Oct) |
| ![Catch Up](docs/enhanced/catch-up.jpg) Catch Up | ![Catch Up seasons](docs/enhanced/catch-up-seasons.jpg) Catch Up season picker |
| ![Pause screen](docs/enhanced/pause-screen.jpg) Pause screen | ![Rating badge](docs/enhanced/rating-badge.jpg) Rating badge at playback start |
| ![OSD ratings](docs/enhanced/player-osd-ratings.jpg) OSD while the badge is up; the ratings line sits under it | ![Player bookmarks](docs/enhanced/player-bookmarks.jpg) Bookmark action with saved bookmarks |
| ![Bookmark saved](docs/enhanced/player-bookmark-saved.jpg) Bookmark saved from the player | ![Settings](docs/enhanced/settings.jpg) Settings › Jellyfin Enhanced |
| ![Spoiler Guard settings](docs/enhanced/settings-spoiler-guard.jpg) Spoiler Guard categories | ![Details & tags settings](docs/enhanced/settings-details.jpg) Details & tags |
| ![Playback settings](docs/enhanced/settings-playback.jpg) Playback settings | ![Subtitle style](docs/enhanced/settings-subtitle-style.jpg) Subtitle style sync |
| ![About](docs/enhanced/settings-about.jpg) About as an admin: Seerr linked, "Import Jellyfin users into Seerr" | ![What's New](docs/enhanced/whats-new.jpg) What's New for admins |

## Upstream hooks

All fork code lives in `app/src/enhanced/` (Kotlin sources and resources, added to the `main` source set by a block
at the end of `app/build.gradle.kts`). Upstream files only get these hook lines. Each calls into the fork and does
nothing on a server without the plugin. Subtitle style sync, the maintenance notice and Seerr linking need no hooks:
they start with Koin (`createdAtStart`) and write upstream's subtitle preferences or watch activity lifecycles.
`git diff upstream/master..HEAD --stat -- app/src/main` lists them all.

| Upstream file (`app/src/main/java/org/jellyfin/androidtv/…`) | Hook line(s) | Purpose |
|---|---|---|
| `app/build.gradle.kts` (repo path) | `android { … }` block at the end | Application id `org.jellyfin.androidtv.enhanced`, `src/enhanced` source and resource dirs, app name and search authority |
| `di/KoinInitializer.kt` | `enhancedModule` in the module list | Registers the fork's Koin bindings (it includes every feature module) |
| `ui/itemdetail/FullDetailsFragment.java` | `ps.addClassPresenter(EnhancedComposeRow.class, …)`; `EnhancedDetails.addRows(…)` after `addAdditionalRows`; `EnhancedDetails.addButtons(…)` before the "more" button; `if (EnhancedDetails.onItemClicked(item)) return;` first in the row click listener | Details buttons, Compose and Seerr rows, clicks on Seerr cards |
| `ui/itemdetail/FullDetailsFragmentHelper.kt` | `EnhancedDetails.addOverflowItems(…)` at the end of `showDetailsMenu` | Buttons that didn't fit go into "Other options" |
| `util/KeyProcessor.java` | `EnhancedItemMenu.contribute(…)` in `createItemMenu`; `return EnhancedItemMenu.onMenuItemClick(…)` as the click listener's fallback | Card menu entries (Hide, remove from home rows, Spoiler Guard on collections) |
| `ui/home/HomeRowsFragment.kt` | `val enhancedRows = EnhancedHomeRows(this)` in `onCreate`; `rows.add(enhancedRows)` after the home sections; `if (EnhancedHomeRows.onItemClicked(requireContext(), item)) return` first in the click listener; safe `as? ItemRowAdapter` cast in `onResume`'s deletion handling | Optional JE home rows (the JE rows aren't `ItemRowAdapter`s, so the hard cast would crash after deleting an item opened from one) |
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
message is shown (never turned into an empty list). Calls are cancelled with the screen that started them.

**Seerr account link.** The app never asks for Seerr credentials. `GET jellyseerr/user-status`, which every state load
calls, already links (auto-imports) the user when the plugin's `JellyseerrAutoImportUsers` is on. `SeerrLinkManager`
looks once more, 65 s later (past the plugin's 60 s negative cache), when the status says unlinked or couldn't be
read, and reloads the state once when a Seerr call says `unlinked` while the state says linked; blocked, disabled and
unreachable are never retried by themselves. Administrators can run `POST jellyseerr/import-users` from About or from
the link notice (the plugin allows one run per 30 s). Seerr error codes (`unlinked`, `blocked`, `unreachable`,
permission codes) are shown as TV sentences.

**Spoiler Guard for data the plugin doesn't filter.** TMDB, MDBList and Seerr ratings, release dates, reviews,
DoesTheDogDie warnings, Sonarr/Radarr calendar data and names saved with bookmarks reach the app unfiltered.
`data/SpoilerProtection.kt` resolves per title which categories apply, mirroring the plugin's
`ShouldStrip(adminOn, userOverride)`: the admin's `SpoilerStrip*` flags are the cap and a user override of `false` opts
out of a category. Membership comes from the user's Spoiler Guard lists (series, movies, collections and the movies
in them). Played movies and episodes pass through like on the server, except that reviews stay withheld. The player
features use only the item DTO the player already holds, which the server has filtered.

**Fail closed.** Only a 404 from `public-config` means "no plugin". Any other failure keeps the state already loaded
for this user (so protection stays on through a transient error); without one, the plugin config is marked unavailable
and every title counts as fully protected. The same applies when Spoiler Guard
is on and anything needed to decide is missing: the user's lists, the policy, or a guarded collection's members (the
plugin's Hidden Content filter drops hidden items from collection listings, so every hidden item counts as a possible
member, and an unreadable Hidden Content document leaves membership unknown). Fully protected means no external
story, ratings, dates, cast, reviews, tags or artwork. Releases whose air date is protected never appear in the
calendar's dated weeks; they are listed apart from a fixed range (today + 90 days). The Reviews row and content
warnings are left out, bookmarks show no saved episode name, the calendar and bookmarks pages show an error with
Retry when protection can't be resolved, and with the config unknown the Activity and Hidden Content pages treat Spoiler
Guard as on. Automatic reloads after a failed config load wait 30 s (an explicit Retry tries at once), so a struggling
server isn't hit by every screen.

| Area | Code |
|---|---|
| API client and plugin state (gates) | `api/EnhancedApi.kt`, `data/EnhancedRepository.kt`, `data/EnhancedConfig.kt` |
| User JE documents (`settings.json`, `elsewhere.json`, …) | `data/EnhancedSettingsRepository.kt` |
| Spoiler Guard lists and policy | `data/SpoilerGuardRepository.kt`, `data/SpoilerProtection.kt` |
| Hidden Content and home removal | `data/HiddenContentRepository.kt` |
| Seerr discovery and requests | `seerr/`, `ui/seerr/`, `seerrdetails/`, `issues/` |
| Seerr account link | `seerr/SeerrLink.kt`, `seerr/SeerrLinkManager.kt`, `ui/seerr/SeerrLinkUi.kt` |
| Home rows, subtitle style, maintenance | `home/`, `subtitles/`, `maintenance/` |
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

- Unit tests: the last full run (3 November 2026, with every feature above merged) passed all 460 app unit tests, 368
  of them in the fork's packages (decoders, id and casing conversions, gates, filters, Spoiler Guard policy and the
  fail-closed config load, season availability, Catch Up deck and season selection, pause-screen timer, rating
  colours, bookmarks, home rows, subtitle style mapping and sync rules, AniList decoding and review translation,
  maintenance notice rules, Seerr link states and rechecks).
- Device: each feature was exercised with the D-pad on the Android TV emulator against an isolated lab server
  (Jellyfin 12.1, JE 12.13, Seerr), as an admin and as a non-admin user: search, Discover, Seerr details for movies
  and shows, requests and Request more, Report a problem, Rate, bookmarks from details and from the player, Hide and
  Unhide, all hub pages, all JE settings pages and About, Catch Up (Dismiss, Watched, Watchlist and their Undo, season
  picker), pause screen, rating badge, OSD ratings, and the calendar with a Spoiler Guarded show. Later additions
  checked on the device: the four home rows, the Bookmarks ⋯ menu (rename, delete), the Subtitle style and Home rows
  pages, About with the Seerr link and import rows, and native poster tags. AniList reviews and review translation
  were only unit-tested (no lab item has an AniList id, and the lab's JE 12.13 has no translation routes); the
  maintenance notice and the Discover/Requests link notices were checked by unit tests of their rules.
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
  shows for 5 seconds after the first frame and is hidden while the OSD is up.
- The details screen fits six buttons; the rest are in "Other options". The "Enhanced info" row comes after
  upstream's rows, not directly under the overview.
- Hidden Content is saved as a whole document, as on the web, so a hide made on another device at the same moment can
  be overwritten. The plugin has no per-item API for global hides.
- Catch Up dismissals are stored on the device, not on the server.
- What's New "Dismiss" is server-wide (for every administrator), as on the web.
- Bookmarks whose item was replaced or removed are marked and can be deleted, but are not re-linked to a new copy.
- Review translation needs JE 12.14 or later with a LibreTranslate server set in the plugin. Only the reviews reader
  translates (not the cards in the row).
- AniList reviews need an AniList provider id on the item (or, for seasons and episodes, on the series), as written by
  an anime metadata plugin.
- Home-row choices and the subtitle style sync switch are stored on the device (per server and user), not in JE's
  settings, so each TV is set up on its own. The chosen translation language is also per device.
- Subtitle style sync can't map the font family, horizontal position or shadows (approximated by an outline); the
  player keeps the app's own font, centred.
- About shows the linked Seerr account's name only to administrators (reading Seerr users needs admin rights);
  other users see "Linked".
- The Activity page has nothing focusable when there are no active streams and the feed is empty or off; use Back.

## Not done yet

- Client-drawn poster tags (quality, language, rating, age). Only the server-drawn native poster tags are shown.
- JE playback extras beyond the pause screen: keyboard shortcuts, auto-pause/resume, auto PiP, hold for 2×.
- Admin operations (Spoiler Guard repair, cross-user Hidden Content, review moderation, active stream broadcast) and
  external links to Seerr, Sonarr/Radarr or Letterboxd.

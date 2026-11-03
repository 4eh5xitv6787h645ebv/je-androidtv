<h1 align="center">Jellyfin Enhanced for Android TV</h1>
<p align="center">The official <a href="https://github.com/jellyfin/jellyfin-androidtv">Jellyfin Android TV client</a> with a native <a href="https://github.com/n00bcodr/Jellyfin-Enhanced">Jellyfin Enhanced</a> layer on top.</p>

<p align="center">
<a href="https://github.com/4eh5xitv6787h645ebv/jellyfin-enhanced-androidtv/releases"><img alt="Release" src="https://img.shields.io/github/v/release/4eh5xitv6787h645ebv/jellyfin-enhanced-androidtv?label=download"/></a>
<a href="LICENSE"><img alt="GPL 2.0 License" src="https://img.shields.io/github/license/jellyfin/jellyfin-androidtv.svg"/></a>
</p>

<p align="center"><img src="docs/enhanced/hub.jpg" alt="The Enhanced hub" width="800"/></p>

Every Jellyfin Enhanced screen shares one design system built on the stock app's own theme: the same focus style
everywhere, tabs and filter chips that show counts and make the selected one obvious, one status palette for
requests and seasons, and backdrop-led pages for Seerr details and Catch Up.

Everything the stock app does still works exactly the same. The extra screens, rows and buttons only appear when your
server runs the [Jellyfin Enhanced](https://github.com/n00bcodr/Jellyfin-Enhanced) plugin and the feature is switched
on there. On a server without the plugin the app looks and behaves like stock. It installs as its own app
(`org.jellyfin.androidtv.enhanced`), so it can sit next to the official client.

## Install

1. Download `jellyfin-enhanced-androidtv-release.apk` from the [latest release](https://github.com/4eh5xitv6787h645ebv/jellyfin-enhanced-androidtv/releases).
2. Sideload it onto your TV: `adb install jellyfin-enhanced-androidtv-release.apk`, or use the Downloader app.
3. Sign in to your Jellyfin server as usual. Nothing else to set up: the app finds the plugin on its own.

Needs Jellyfin 10.11 or 12 with Jellyfin Enhanced 12.13 or newer. Seerr features need the plugin's Jellyseerr/Overseerr
integration configured. Review translation needs plugin 12.14 with a translation server.

## What you get

### The Enhanced hub

A new **Enhanced** tab in the toolbar opens a hub of plugin pages. Each tile only shows when that feature is on for you.

| | |
|---|---|
| ![Discover](docs/enhanced/discover.jpg) **Discover**: trending, popular and upcoming titles from Seerr, with View all grids, sort presets and genre tiles | ![Catch Up](docs/enhanced/catch-up.jpg) **Catch Up**: random unwatched titles one card at a time, with Watch, Watched, Watchlist, Dismiss and Undo |
| ![Requests](docs/enhanced/requests.jpg) **Requests**: your Seerr requests, watchlist, download queue, history and issues; admins approve and decline | ![Calendar](docs/enhanced/calendar.jpg) **Calendar**: a two-week agenda of Sonarr and Radarr releases with source, type and request filters |
| ![Bookmarks](docs/enhanced/bookmarks.jpg) **Bookmarks**: moments you saved, with play from here, rename and delete | ![Activity](docs/enhanced/activity.jpg) **Activity**: who is watching now and what people on the server watched, liked and reviewed |
| ![Spoiler Guard](docs/enhanced/spoiler-guard-page.jpg) **Spoiler Guard**: every protected show, movie and collection, and pending opt-ins for titles not in the library yet | ![Hidden Content](docs/enhanced/hidden-content.jpg) **Hidden Content**: everything you hid or removed from home rows, with scope labels and restore |

### Spoiler Guard and Hidden Content

| | |
|---|---|
| ![Spoiler Guard on](docs/enhanced/spoiler-guard-on.jpg) Turn Spoiler Guard on from a show, movie or collection. The server then hides synopses, episode titles, chapters, cast, ratings and air dates for unwatched episodes | ![Calendar with Spoiler Guard](docs/enhanced/calendar-spoiler-guard.jpg) Protection carries into everything the app fetches itself: calendar releases of a guarded show are listed without dates or episode titles |
| ![Remove from Continue Watching](docs/enhanced/remove-from-continue-watching.jpg) Remove from Continue Watching or Next Up straight from the card menu | ![Hide on a Seerr card](docs/enhanced/seerr-card-hide.jpg) Hide a title you never want to see again, in the library or on Seerr |

Every withholding decision fails closed: if the plugin's configuration or your hidden list cannot be read, the app
withholds rather than reveals.

### Seerr: discover and request

| | |
|---|---|
| ![Seerr in search](docs/enhanced/search-seerr-rows.jpg) Searching also shows movies, shows, collections and people from Seerr | ![More from menu](docs/enhanced/more-from-menu.jpg) **More from…** on any title lists its cast, crew, genres, studios and tags |
| ![Person row](docs/enhanced/person-more-from-row.jpg) A "More from" row on cast pages opens Seerr discovery for that person | ![Discovery grid](docs/enhanced/discovery-grid.jpg) Discovery grids with All, Movies and TV filters |
| ![Seerr movie details](docs/enhanced/seerr-details-movie.jpg) Seerr details with Request, ratings from IMDb and Rotten Tomatoes, awards, where to watch and cast | ![Seerr show details](docs/enhanced/seerr-details-show.jpg) Shows list seasons and who requested what, with **Request more** and **Report a problem** |
| ![Request more](docs/enhanced/request-more-seasons.jpg) Pick the missing seasons to request | ![About](docs/enhanced/settings-about.jpg) Nobody signs in to Seerr: accounts link automatically through the plugin, and admins can import every Jellyfin user from the About page |

Requests are standard quality only, by design.

### Richer details pages

| | |
|---|---|
| ![Enhanced info](docs/enhanced/details-enhanced-info.jpg) An **Enhanced info** row right under the buttons: watch progress, file size, audio languages, release dates, IMDb and Rotten Tomatoes, awards and where it streams | ![Details overflow](docs/enhanced/details-other-options.jpg) Hide, More from…, Rate, Add bookmark and Report a problem join the details buttons |
| ![Rate](docs/enhanced/rate-dialog.jpg) Rate with half stars and write a review; reviews from Jellyfin Enhanced users, AniList and TMDB show in a reader with optional translation | ![Bookmark rename](docs/enhanced/bookmark-rename.jpg) Bookmarks can be renamed or deleted from their ⋯ menu |

Details pages also gain cast ages, DoesTheDogDie content warnings and a reviews row further down.

### Player

| | |
|---|---|
| ![Pause screen](docs/enhanced/pause-screen.jpg) A pause screen with logo, synopsis, progress and the time it ends, after a few seconds paused | ![Rating badge](docs/enhanced/rating-badge.jpg) The age rating and genres at playback start |
| ![OSD ratings](docs/enhanced/player-osd-ratings.jpg) Critic and community ratings in the player OSD | ![Player bookmarks](docs/enhanced/player-bookmarks.jpg) A Bookmark action saves the current moment or jumps to a saved one |

### Home screen

| | |
|---|---|
| ![Home rows](docs/enhanced/home-rows.jpg) Optional rows below the stock home sections: **Coming up**, **Jump back in**, **Trending on Seerr** | ![Catch Up home row](docs/enhanced/home-rows-catch-up.jpg) A **Catch Up** row with a shortcut to the deck |
| ![Native poster tags](docs/enhanced/native-poster-tags.jpg) With the plugin's native poster tags on, posters carry quality, codec, audio, rating and language badges drawn by the server | ![Home rows settings](docs/enhanced/settings-home-rows.jpg) Each row is off by default and switched on per device |

### Settings

| | |
|---|---|
| ![Settings](docs/enhanced/settings.jpg) Settings › Jellyfin Enhanced | ![Spoiler Guard settings](docs/enhanced/settings-spoiler-guard.jpg) Choose which categories Spoiler Guard hides for you |
| ![Details & tags settings](docs/enhanced/settings-details.jpg) Watch progress, file sizes, tags and the random button | ![Playback settings](docs/enhanced/settings-playback.jpg) Pause screen, in-player ratings and auto-skip |
| ![Subtitle style](docs/enhanced/settings-subtitle-style.jpg) Use your Jellyfin Enhanced subtitle style on this device | ![What's New](docs/enhanced/whats-new.jpg) Admins see the plugin's new settings and a maintenance-mode notice when one is scheduled |

Also: Where to watch (region and streaming services), content warning triggers, Home rows, and About with the plugin
and server versions and the Seerr link state.

## How it stays updatable

All fork code lives in `app/src/enhanced/`. Upstream files only receive a handful of one-line hook calls, listed in
[ENHANCED.md](ENHANCED.md), so merging the official client's `master` is routine:

```sh
git remote add upstream https://github.com/jellyfin/jellyfin-androidtv.git
scripts/enhanced-sync-upstream.sh   # or: git fetch upstream && git merge upstream/master
```

## Build

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug             # debug build, installs as org.jellyfin.androidtv.enhanced.debug
./gradlew assembleRelease           # unsigned release; sign it with zipalign + apksigner and your own key
./gradlew :app:testDebugUnitTest    # unit tests (fork tests are in app/src/test/kotlin/enhanced/)
```

[ENHANCED.md](ENHANCED.md) has the full feature table with the plugin gate behind each feature, the hook table, how the
app talks to the plugin, verification notes and known limitations. The upstream README is kept in
[docs/UPSTREAM_README.md](docs/UPSTREAM_README.md).

## Credits and licence

Built on [jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv) by the Jellyfin project and on
[Jellyfin Enhanced](https://github.com/n00bcodr/Jellyfin-Enhanced) by n00bcodr. Licensed under the GPL 2.0 like the
upstream client; see [LICENSE](LICENSE).

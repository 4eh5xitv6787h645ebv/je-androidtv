# Seerr account link: integration

Nobody signs in to Seerr from the TV. The plugin links a Jellyfin user to the Seerr account carrying their Jellyfin
user id; this package makes that automatic where the server allows it, lets administrators import users, and turns
every "not linked" error into a TV sentence.

## Required

- Koin: add `org.jellyfin.androidtv.enhanced.seerr.seerrLinkModule` to `featureModules` (any position). It binds
  `SeerrLinkManager` with `createdAtStart = true`; Discover, Requests, the search rows, Seerr details, the discovery
  grid and the About page inject it, so without the module those screens fail to resolve it.
- Hub, settings routes, upstream hooks: none.

## Recommended (files owned by others; one or two lines each)

- `issues/IssueReportFlow.kt`, `errorMessage(context, err)` (line ~260): today `unlinked` shows the plugin's
  technical line ("Current Jellyfin user is not linked to a Jellyseerr user."). Make the first branch
  `SeerrLinkTexts.error(context, err, isAdmin = false)?.let { return it }` (import
  `org.jellyfin.androidtv.enhanced.ui.seerr.SeerrLinkTexts`), or keep the `when` and add before the `serverMessage`
  branch: `SeerrErrors.isUnlinked(err) -> SeerrLinkTexts.error(context, err, false)!!`.
- `search/SeerrSearchSession.kt` line ~181 keeps only `serverMessage` in `SeerrSearchError`; `SeerrSearchRows`
  therefore recognises `unlinked` by the plugin's fixed message (`SeerrErrors.UNLINKED_SERVER_MESSAGE`). Carrying
  `(err as? EnhancedApiException)?.code` in `SeerrSearchError` would make that exact; then use `SeerrErrors.isUnlinked`.
- `search/SeerrSearchCards.kt` line ~137: the error card's subtitle is always "Select to retry". For an admin's
  unlinked card the title already says "Not in Seerr yet: select to import users"; the subtitle could be dropped
  for that case.
- Hub gates (`discover/DiscoverModule.kt` line 33 `enabled = { it.discover }`, `requests/RequestsModule.kt` line 24
  `enabled = { state -> state.requests }`): an unlinked user doesn't see Discover/Requests at all, so the pages'
  new link notice (with "Import Jellyfin users into Seerr" for admins) is only reachable by deep link or when the
  link breaks mid-session. To surface it, widen the gates to
  `it.discover || SeerrLink.blocksPage(it, it.config.recommendationsPageEnabled)` and
  `state.requests || SeerrLink.blocksPage(state, state.config.downloadsPageEnabled)`. Both pages handle that state.
  The About page (Settings › Jellyfin Enhanced › About) shows the link and the import either way.
- `ENHANCED.md`: the "Seerr" paragraph could say linking is automatic (plugin auto-import, or an admin's "Import
  Jellyfin users into Seerr" on the About page), and the About row in "Settings pages" could mention the import.

## What links a user (plugin `origin/main`)

- `GetJellyseerrUser(jellyfinUserId, bypassCache, allowAutoImport = true)` imports the user into Seerr
  (`POST /api/v1/user/import-from-jellyfin`) when it can't find them and `JellyseerrAutoImportUsers` is on.
  Every caller uses the default `allowAutoImport = true` except `GET jellyseerr/permission-audit`. So
  `GET jellyseerr/user-status` (called by `EnhancedRepository` on every state load) already auto-links, as does
  any proxied Seerr call. Blocked users (`JellyseerrImportBlockedUsers`) are never looked up or imported.
- A failed lookup is cached for 60 s when the import gave a definite answer; a found user for 30 min.
- `POST jellyseerr/import-users` (admin, one run per 30 s, 429 `{"error": "Import was run recently…"}` otherwise)
  imports every non-blocked Jellyfin user and clears the plugin's user caches when it imported anyone.

## What the app does

- `SeerrLinkManager` watches `EnhancedRepository.state`. An `unlinked` (or unreadable) status gets exactly one
  more `user-status` read per user and app session, 65 s later (past the 60 s negative cache); `blocked`,
  `disabled`, `unreachable` and `no_user` never get an automatic retry. A changed link reloads the state
  (`ensureLoaded(force = true)`). A Seerr call failing with `unlinked` while the state says linked reloads the
  state once per user and session (`onUnlinkedError`). `SeerrRecheckGate` enforces the "once".
- `SeerrErrors.forError/forLink` map codes and reasons to texts: `unlinked` →
  "Your account isn't in Seerr yet. Ask an administrator to import Jellyfin users into Seerr (Jellyfin Enhanced ›
  Seerr)." (admins: "… Import Jellyfin users into Seerr to add it, here or in Jellyfin Enhanced › Seerr." plus the
  import action); other codes keep the server's text as a full sentence, with our own fallback per code.
- Screens: About (Seerr row "Linked as <Seerr name>" / "Not linked. …" / "Seerr unreachable. …" / "Seerr disabled.
  …", select to check again; admin row "Import Jellyfin users into Seerr" with the result), Discover and Requests
  (`SeerrLinkNotice` with Import or Check again in place of the page when the link is the problem; row/tab errors in
  TV words), search error card, Seerr details (load error, Request button, the "Seerr unavailable" line), the
  Seerr discovery grid.

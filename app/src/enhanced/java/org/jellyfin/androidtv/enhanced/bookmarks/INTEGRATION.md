# Bookmarks: integration

- Koin: add `org.jellyfin.androidtv.enhanced.bookmarks.bookmarksModule` to `featureModules`. It binds
  `BookmarkDetailsContributor` as a `DetailsContributor` ("Add bookmark" on movie/episode details with a resume
  position, priority 80 so it folds into "⋯" early).
- Hub: `EnhancedHub.register(org.jellyfin.androidtv.enhanced.bookmarks.bookmarksHubEntry)` (id `bookmarks`, gate
  `state.bookmarks`, order 50).
- Settings: none. Upstream hooks: none. Playback goes through upstream's `PlaybackHelper.retrieveAndPlay(ids, false,
  positionTicks, 0, context)`.
- Capturing a bookmark from inside the player lives in `playback/` (the Bookmark action of the player overlay).
- Needs `SpoilerProtection` and `SpoilerGuardRepository` (both bound in `enhancedModule`) for the bookmark list's
  episode names; the list is re-read when the page is shown again (bookmarks added in the player appear).

## Editing (stream T, branch `je/polish`)

Nothing new to register: `bookmarksModule` and `bookmarksHubEntry` are unchanged.

- Each row of the Bookmarks page has a focusable "⋯" button after it (for remotes without long-press); it, a long
  press and the Menu key open the menu: Play from 12:34, Open details (both only while the item exists), Rename,
  Delete bookmark. Delete asks first. Rename shows the current label (max 100 characters, like the web); left empty,
  the label becomes the bookmark's time ("12:34"), the label the player gives a quick bookmark.
- Renames and deletes change the list in place (no reload), so the focus stays put; after a delete it moves to the
  next row, else the previous one, else the empty state's Back (`ui/hub/ListFocus`), never to the toolbar.
- Writes (`BookmarksRepository`):
  - rename: `EnhancedSettingsRepository.edit("bookmark.json")`, i.e. GET → change `Label` + `UpdatedAt` of that
    bookmark (keys matched ignoring case, every other field kept) → POST the whole document, under the shared document
    lock. The plugin has no per-bookmark update route (the web does the same whole-document save). A bookmark gone
    from the re-read document throws `BookmarkGoneException` before anything is saved; the page then drops the row.
  - add (`POST …/bookmark.json/add`, also used by the player) and delete (`DELETE …/bookmark.json/{bookmarkId}`) now
    run under `EnhancedSettingsRepository.withDocumentLock`, so neither can land between a rename's GET and POST
    (which would bring a deleted bookmark back or drop a new one). `delete` returns false on the plugin's 404 (already
    gone) instead of failing.
  - Every add, delete and rename bumps `revision`, so `playback/PlaybackBookmarks` re-reads its cache.

## Suggested ENHANCED.md changes (for the docs owner)

- Hub pages table, Bookmarks: "OK plays from the saved moment; long-press, Menu or the row's ⋯ button offers Play from
  here, Open details, Rename and Delete (with confirmation)."
- "Not done yet": remove "Editing bookmark labels" and "Maintenance banner" (keep the admin operations).
- Features table: add "Maintenance notice | Hub banner, one-time dialog at app start | `MaintenanceModeEnabled` (live
  state from `public-config`) | `maintenance`".

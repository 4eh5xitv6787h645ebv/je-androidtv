# Native+ design system (`enhanced/ui/design`)

The shared look of every Jellyfin Enhanced screen: upstream's own colours, pills and inversion focus, plus a few
rules for what upstream doesn't have (status colours, list cards with artwork, hero screens). Everything is built on
`JellyfinTheme`, `org.jellyfin.design.Tokens` and `ui/base` (`Text`, `Button`, `IconButton`, `ListButton`,
`DialogBase`, `Popover`), so Enhanced pages look like the rest of the app. The contract is the design spec
(`spec.md` of the design pass); this file is the quick reference.

Resources: strings in `res/values/je_design.xml` (`je_design_*`), icons `res/drawable/je_design_ic_*.xml`.

## The focus rule

| Element contains… | Focus looks like | Components |
|---|---|---|
| Only text or icons | **Inversion**: upstream `buttonFocused` fill, `onButtonFocused` content | `JeButton`, `JeIconButton`, `LabeledIconButton`, `FilterChip`, `ChoiceChip`, `JeTabRow` tabs, `HubTile`, text-only `ListRow` |
| Artwork or coloured badges | **Ring**: 2 dp light border (`Modifier.jeFocusRing`) + `surfaceRaised` fill; content colours unchanged | `ListRow` with a poster/thumb/avatar, badges or progress; `MediaCard`; `SeasonCard` |
| Settings drawer rows | upstream `ListButton` | `SettingsToggle`, `SettingsChoice`, `SettingsNav` |
| Leanback cards | upstream card presenter | (not in this package) |

- **Selection is never a light fill.** Selected tab = cyan underline; selected chip = `buttonActive` fill + check.
  A focused, selected chip is inverted *and* keeps its check, so "focused" and "selected" are always distinct.
- No focus scaling, no animated focus colours, no shadows, no blur.
- Don't disable a focused button while it works: pass `busy = true` (spinner, clicks ignored) instead.
- Rows of controls (`ChipRow`, `JeTabRow`, `ActionRow`, `PageHeader` actions) restore the last focused child when the
  D-pad re-enters them; `JeTabRow` falls back to the selected tab.
- Text on inverted focus is drawn at full `onButtonFocused` strength (translucent dark captions fall below 4.5:1).
- Status containers keep white/dark text at ≥ 4.5:1 (`JeContrastTest` checks the whole palette).

## Tokens (`JeTokens.kt`, `JeStatus.kt`, `JeFocus.kt`)

| Object | What |
|---|---|
| `JeColors` | `background`, `surface`, `surfaceRaised`, `surfaceOverArt`, `onSurface`, `textSecondary`, `textTertiary`, `outline`, `divider`, `accent`, `accentOnDark`, `scrim`, `focusRing`, `countPill`, `imagePlaceholder`, `warningText`, `ratingFresh`/`ratingAudience`/`ratingStar`. Interactive colours: read `JellyfinTheme.colorScheme`. |
| `NoticeKind` | `Info`, `Warning`, `Error` with container / stripe / title / body colours |
| `JeType` | `heroTitle`(48)/`heroTitleWrapped`(40), `pageTitle`(28), `pageSubtitle`(15), `sectionHeader`(18), `overline`(12, upper), `tab`(16), `cardTitle`(17), `cardTitleTrailing`(14), `body`(15), `bodyLarge`(16), `meta`(15), `caption`(14), `chip`(14), `badge`(12 bold), `dialogTitle`(20). Nothing under 12 sp, body ≥ 14 sp. |
| `JeSpace` | `pageHorizontal` 48, `pageBottom` 32, `headerBottom` 16, `sectionGap` 24, `sectionHeaderGap` 8, `rowGap`/`gridGap` 12, `rowPadding` 12 (`rowPaddingCompact` 10), `chipGap` 8, `actionGap` 12, `labeledActionGap` 20, `heroTop` 60 / `heroTopWithToolbar` 85 |
| `JeShapes` | `pill` (pressable), `container` 12 dp (rows, cards, tiles, banners), `image` 8 dp (posters, info chips), `badge` 4 dp, `dialog` 16 dp |
| `JeStatus` | `Pending`, `Approved`, `Processing`, `ComingSoon`, `Available`, `PartlyAvailable`, `Declined`, `Warning` (with icon), `Neutral` — container, content, default label |
| `StatusBadgeSpec` | `(status, text?, labelRes)`: what a badge shows |
| `JeStatuses` | `seerrMedia(Int?)` (null = show nothing), `seerrSeason(Int?)`, `seerrRequest(Int?)`, `seerrIssue(Int?)`, `label(String?)` (plugin labels, `RequestChip` names, calendar "Downloaded"/"Monitored"), `queue(String?)` (*arr queue states) |
| `JeContrast` | `ratio(fg, bg)`, `composite(fg, bg)`, `luminance(c)` (WCAG) |
| Focus | `rememberFocusedState()`, `Modifier.jeFocusRing(focused, shape)`, `Modifier.jeRingContainer(focused, shape)`, `Modifier.jeDisabled(enabled)`, `Modifier.onMenuKey(onMenu)` |

## Component catalogue

| Component | File | Use it for |
|---|---|---|
| `PageScaffold(title, subtitle?, actions?, backdropUrl?, backdropBlurHash?, showToolbar) { }` | `PageScaffold.kt` | Every Compose page. Toolbar + fixed header + content; provides the "page has focus" signal `EmptyState` uses. |
| `PageHeader(title, subtitle?, actions?)` | `PageScaffold.kt` | Header alone, above leanback grids. |
| `Backdrop(url, blurHash?, dim)` | `PageScaffold.kt` | Full-bleed art with the spec's two scrims (drawn once). |
| `HeroScaffold(backdropUrl, header = { collapsed -> }, actions = { }, topEnd?, aside?, below?)` | `PageScaffold.kt` | Seerr details and Catch Up: fixed hero, rows scroll under it, hero collapses (overview hidden) while focus is below. |
| `HeroHeader(title, overline?, status?, meta?, overview?, collapsed, extra?)`, `HeroTitle`, `MetaLine(items)`, `AgeRatingTag(text)`, `MetaRating(text, leading)` | `PageScaffold.kt` | The hero's text block and meta row. |
| `SectionHeader(text, count?, trailing?)`, `Overline(text)` | `Sections.kt` | "Seasons", "Watching now 0"; "STREAMING IN US". |
| `ListRow(title, onClick, titleTrailing?, supporting, leading, badges, warning?, progress?, actions, onMenu?, density, mainModifier)` | `Rows.kt` | All list rows and 2-column list cards. `RowLeading.Poster/Thumb/Avatar/Icon/None`, `RowAction(icon?, label, onClick, busy, showLabel)`, `RowDensity.Compact/Regular/Large` (fixed heights). |
| `WarningLine(text)`, `ProgressLine(fraction)` | `Rows.kt` | The orange hint line; the 4 dp accent progress bar. |
| `MediaCard(imageUrl, title, onClick?, subtitle?, aspect, width, badge?, progress?, onMenu?, blurHash?, fallbackIcon?, imageAlpha)` | `Cards.kt` | Compose rows: cast, up-next (onClick null = decorative), reviews, Discover tiles. |
| `HubTile(icon, title, description, onClick, count?)` | `Cards.kt` | Hub grid (3 columns, 16 dp gaps). |
| `SeasonCard(name, onClick?, episodes?, status?, imageUrl?)` | `Cards.kt` | Seerr details season row. |
| `JeTabRow(tabs: List<TabSpec(label, count?)>, selected, onSelect)` | `Chips.kt` | Page tabs; selects on click. |
| `FilterChip(label, selected, onClick, count?, leadingIcon?, trailingIcon?)` | `Chips.kt` | Filters and toggles. |
| `ChoiceChip(label, options, selected, onSelect)` | `Chips.kt` | "Where: All ▾" popover choice. |
| `ChipRow { }`, `ChipSeparator()` | `Chips.kt` | Chip row with focus restore; divider before toggle chips. |
| `InfoChip(text, leading?: InfoLeading.Dot/Icon/Image, overArt)` | `Chips.kt` | Non-focusable facts: ratings, awards, providers. |
| `StatusBadge(status, text?, compact)` / `StatusBadge(spec)`, `StatusRibbon(spec)` | `Chips.kt` | Status labels; poster-bottom band. |
| `JeButton(text, onClick, icon?, enabled, busy, onLongClick?)` | `Buttons.kt` | Every text button (one style; first = primary). |
| `JeIconButton(icon, contentDescription, onClick, busy, size)` | `Buttons.kt` | Round icon actions (in rows: 40 dp). |
| `LabeledIconButton(icon, label, onClick, enabled, busy)`, `ActionRow(primary, secondary: List<LabeledIconButtonSpec>)` | `Buttons.kt` | Hero action rows (B layout). |
| `LoadStateContent(state, empty?, emptyMessage, emptyAction, emptyBody?, emptyIcon?, loading: SkeletonShape) { }` | `States.kt` | Same leading signature as `ui.hub.LoadStateContent`: swap the import. |
| `EmptyState(title, icon?, body?, action = Back)` | `States.kt` | Empty pages, with a focusable action. |
| `ErrorState(message?, onRetry, secondary?)`, `ErrorRow(message?, onRetry)` | `States.kt` | Load errors (Retry focused); inline error at the end of paged lists. |
| `LoadingSkeleton(shape = Rows/Grid/Hero, delayMillis = 300)`, `LoadingRow()` | `States.kt` | Loading (the spec's "LoadingState"); "loading more" footer. |
| `Notice(kind, title, body?, icon?, inline, trailing?)` | `Notice.kt` | Banners and inline notices; not focusable. |
| `JeDialog(visible, onDismissRequest, title, body?, actions?, content?)`, `JeConfirmDialog(...)`, `JeChoiceDialog(options, selected, multi, captions?, indicators, ...)`, `JeTextInputDialog(title, initialValue, confirmLabel, onConfirm, hint?, maxLength, ...)` | `JeDialog.kt` | All dialogs (replace `AlertDialog`). `indicators = false` drops the radio buttons for an action menu (a row's "⋯"); `JeTextInputDialog` asks for one line of text (rename). |
| `SettingsSection(overline, heading?)`, `SettingsIntro(text)`, `SettingsToggle`, `SettingsChoice`, `SettingsNav` | `SettingsRows.kt` | Settings drawer pages. |
| `OverlayPanel { }`, `OverlayAgeRating(text, container, content, border?)`, `OsdInfo(text, leading?)`, `onVideoTextStyle()`, `OverlayToast(visible, text, onTimeout)`, `JePlayerStyle` | `PlayerOverlayStyle.kt` | Rating badge, OSD ratings line, pause screen text, bookmark toast. |

## Migration cheat-sheet (phase 2)

| Old helper (file) | New |
|---|---|
| `EnhancedScreen(title, subtitle, headerActions) { }` (`ui/hub/EnhancedScreen.kt`) | `PageScaffold(title, subtitle = …, actions = { … }) { }`. Old `headerActions` rendered *below* the title; filter rows now go at the top of `content` in a `ChipRow`, and only ≤ 2 buttons go in `actions`. Keep `EnhancedScreen` as a deprecated wrapper that calls `PageScaffold` until every page moved. |
| `EnhancedScreenHeader` | `PageHeader` |
| `LoadStateContent` (`ui/hub/EnhancedScreen.kt`) and its private `EmptyState` | `ui.design.LoadStateContent` (same parameters + `emptyBody`, `emptyIcon`, `loading`). `LoadState`, `EmptyAction`, `rememberBackAction` stay in `ui/hub` and are reused. |
| `secondaryTextColor()` (`ui/EnhancedColors.kt`) | `JeColors.textSecondary` / `JeType.caption`; inside inverted focus use `onButtonFocused`. Make `secondaryTextColor` delegate (fallback `colorGrey200`) when its callers are migrated. |
| `PageChip` (`calendar/PageSupport.kt`) | `FilterChip` (in a `ChipRow`; toggles after `ChipSeparator`) |
| `PageListRow` (`calendar/PageSupport.kt`) | `ListRow` |
| `PageImage` (`calendar/PageSupport.kt`) | `RowLeading.Poster(url)` / `Thumb(url)` in a `ListRow`, or `MediaCard` |
| `PageNotice` (`calendar/PageSupport.kt`) | `Notice(kind, title, inline = true)` |
| `ManagementRow` + `ManagementPoster` (`management/ManagementUi.kt`) | `ListRow(leading = RowLeading.Poster(url, fallbackIcon), actions = listOf(RowAction(icon = null, label = actionLabel, onClick, busy, modifier = actionModifier)), mainModifier = …)`; `chips` → `badges = chips.map { StatusBadgeSpec(JeStatus.Neutral, it) }`. `actionFocusKey` / `ListFocus` keep working through `RowAction.modifier` and `mainModifier`. |
| `Chip(text, color)` (`management/ManagementUi.kt`) | `StatusBadge(JeStatus.Neutral, text = …)` (or the matching status) |
| `ManagementSectionHeader` | `SectionHeader(text, Modifier.padding(horizontal = JeSpace.pageHorizontal))` |
| `ChoiceButton` as tabs (`requests/RequestsComponents.kt`) | `JeTabRow(tabs = listOf(TabSpec(label, count)), selected, onSelect)` |
| `ChoiceButton` as filters | `FilterChip(label, selected, onClick, count)` |
| `StatusChip(text, color)` | `StatusBadge(JeStatuses.label(chip.name))` / `StatusBadge(JeStatuses.queue(status))` / `JeStatuses.seerrIssue(…)` |
| `ProgressBar(fraction, color)` | `ProgressLine(fraction)` or `ListRow(progress = …)` |
| `PosterCard` + `CardTitle` + `CardLine` | `ListRow(title, titleTrailing = year, leading = RowLeading.Poster(url, placeholder), badges, warning, supporting = lines, actions, onMenu, density = RowDensity.Large)` |
| `PageMessage(text)` | `EmptyState(title = text)` or `Notice(Info, text, inline = true)` |
| `PagedGrid` footer (loading / error) | `LoadingRow()` / `ErrorRow(message, onRetry)` |
| `ColorGreen`, `ColorBlue`, `ColorIndigo`, `ColorAmber`, `ColorRed`, `ColorGrey` | `JeStatus` palette via `JeStatuses`; `ColorAmber` hint text → `WarningLine` / `JeColors.warningText` |
| `RequestChip` → colour `when` (`RequestsFragment`) | `JeStatuses.label(chip.name)` (names are understood as is) |
| `queueStatus(status)` (`RequestsFragment`) | `JeStatuses.queue(status)` |
| History outcome colours | `JeStatuses.label("completed" / "partial" / "failed")` |
| `seerrStatusLabel` + `SeerrStatusBadge` (`ui/seerr/SeerrCard.kt`) | `JeStatuses.seerrMedia(status)` + `StatusBadge(spec)` (or `StatusRibbon(spec)` on the poster) |
| `SeerrCard` (`ui/seerr/SeerrCard.kt`) | keep `ItemPreview`; only its badge becomes `StatusRibbon` |
| `SeerrDetailsSectionTitle` (`ui/seerr/SeerrDetailsSections.kt`) | `SectionHeader` |
| `SeerrDetailsMetaStrip` | `MetaLine` / `AgeRatingTag` / `MetaRating` inside `HeroHeader(meta = { })` |
| `SeerrDetailsRequesters` | `HeroHeader(status = { StatusBadge(…); Text(requestLine) })` |
| `SeerrDetailsSeasonsRow` / its cards | `LazyRow` of `SeasonCard(name, onClick, episodes, JeStatuses.seerrSeason(status), imageUrl)` |
| `SeerrDetailsCastRow` / `SeerrDetailsFocusCard` | `LazyRow` of `MediaCard(imageUrl, name, onClick, subtitle = role)` |
| `SeerrDetailsFragment` single `verticalScroll` column, own loading/error text | `HeroScaffold(showToolbar = false, below = { … })`, `LoadingSkeleton(SkeletonShape.Hero)`, `ErrorState` |
| `SeerrLinkNotice` container (`ui/seerr/SeerrLinkUi.kt`) | `Notice(NoticeKind.Warning, title, body)` + its `JeButton`s after it; `EmptyState` when it replaces a page |
| `EnhancedHubFragment` `Button` tiles | `HubTile(entry.icon, title, description, onClick, count)` in a 3-column grid |
| `HubNotice` panels, `MaintenanceBanner` (`maintenance/`) | `Notice(NoticeKind.Warning, title, body, icon = R.drawable.je_ic_maintenance_construction)`; the one-time dialog → `JeDialog` |
| `CatchUpFragment` private `FilterChip`, `PageChip` | `FilterChip` in `HeroScaffold(topEnd = { … })` |
| `CatchUpCard`, `CatchUpActionButton` (`catchup/CatchUpUi.kt`) | `HeroScaffold` + `HeroHeader(overline = "SHOW · 1 OF 47")` + `ActionRow(primary = { JeButton(Watch, icon = play) }, secondary = listOf(LabeledIconButtonSpec(…)))`; up-next stack = `aside` with `MediaCard(onClick = null, imageAlpha = …)` |
| `CatchUpEmpty` | `EmptyState(title, body, action)` |
| `SeasonPickerPanel` | `JeChoiceDialog(multi = true)` |
| `CalendarFragment` `Filters` / `DayHeader` / `GroupHeader` / `EventRow` | `ChipRow` + `FilterChip` (+ `ChipSeparator`) / `SectionHeader` / `Overline` / `ListRow(badges = listOf(JeStatuses.label("Downloaded")))` |
| `ActivityFragment` `SectionHeader` / `EntryRow` / `SessionRow` / `Avatar` | `SectionHeader(text, count)` / `ListRow(leading = RowLeading.Poster(…))` / `ListRow(leading = RowLeading.Avatar(url, initial), progress = …)` |
| `BookmarksFragment` `BookmarkItem` (+ its ⋯ button) | `ListRow(leading = RowLeading.Thumb(…), actions = listOf(RowAction(R.drawable.ic_more, label, onClick)))`; delete / rename → `JeConfirmDialog` / `JeDialog` |
| `HiddenContentFragment` `HiddenRow`, Where/Type cycling pills | `ListRow(badges = listOf(StatusBadgeSpec(JeStatus.Neutral, scope)), actions = listOf(RowAction(null, "Restore", …)))`; `ChoiceChip("Where", …)`, `ChoiceChip("Type", …)` |
| `SpoilerGuardFragment` rows and headers | `SectionHeader` per type + `ListRow(leading = Poster, actions = listOf(RowAction(null, "Turn off", …)))` + `JeConfirmDialog(destructive = true)` |
| `MetadataChip` / `ChipLine` (`metadata/MetadataUi.kt`, `MetadataInfoRow.kt`) | `InfoChip` rows under an `Overline`; actionable ones → `FilterChip` |
| `MetadataDialogPanel`, `AwardsDialog`, `ContentWarnings` dialogs | `JeDialog` |
| `ReviewsUi` `ReviewCardSurface` / `ReviewCard` / star colours | `jeRingContainer` surface card (or `MediaCard`-sized), `JeColors.ratingStar`; reader → `PageScaffold(showToolbar = false)` + `ErrorState` |
| `RateDialog` | `JeDialog(content = { StarPicker … })` |
| `IssueReportFlow` / `IssueContributors` `AlertDialog`s | `JeChoiceDialog` (issue type), `JeDialog` with an input row (message), `StatusBadge(JeStatuses.seerrIssue(…))` |
| `HideFlow` / `EnhancedItemMenu` scope `AlertDialog` | `JeChoiceDialog` ("This episode / Whole show") |
| `enhancedSettingsHeader` / `enhancedSettingsSection` (`management/settings/ManagementSettingsUi.kt`) | `SettingsSection(overline, heading)` + `SettingsIntro` |
| `EnhancedToggleRow` / `EnhancedChoiceRow` | `SettingsToggle` / `SettingsChoice` (one-line captions) |
| Navigation rows in settings / About | `SettingsNav` |
| `RatingBadge` / `RatingBox` (`playback/PlaybackOverlays.kt`) | `OverlayPanel { OverlayAgeRating(text, bucket colours); Text(genres, style = JePlayerStyle.badgeGenres) }`; drop `Color(0xB8101010)` and the accent bar |
| `OsdRating` | a `Row` of `OsdInfo(text, InfoLeading.Dot(JeColors.ratingFresh))` |
| Bookmark toast | `OverlayToast(visible, text, onTimeout)` |
| `fontSize = N.sp` literals anywhere in `enhanced/` | a `JeType` style (`grep -rE "fontSize = [0-9]+\.sp\|Color\(0x" app/src/enhanced/java` should only hit `ui/design` and `PlaybackRatingColors.kt` when done) |

## Notes for implementers

- `ListRow` heights are fixed for `Regular` (108 dp) and `Large` (116 dp) so grid cards line up; keep supporting
  lines to two and use `Compact` (min 64 dp, grows) for text-only lists.
- `HeroScaffold` keeps the action row visible when collapsed so D-pad up from the rows always lands on it; the
  `header` lambda decides what to hide with `collapsed`.
- `Backdrop` centre-crops (`AsyncImage` is an `ImageView`, so the spec's `BiasAlignment(0.3, -0.4)` isn't
  available); request backdrops at ≤ 1280 px.
- `JeDialog` inherits `DialogBase`'s behaviour: Back dismisses, the first focusable element gets the focus.
  `JeConfirmDialog(destructive = false)` and `JeChoiceDialog` move it to the confirm button / selected option.
- Hub counts must come from cached state only; never load data to draw a `HubTile`.

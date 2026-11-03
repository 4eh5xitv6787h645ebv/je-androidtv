# Subtitle style sync: integration

Applies the user's Jellyfin Enhanced subtitle appearance (web settings panel › subtitles) to the app's own subtitle
preferences (`UserPreferences.subtitles*`), when the user turns that on for this device. No upstream hooks: the
player (`VideoManager`) already reads those preferences each time playback starts.

## Register

- Koin: add `org.jellyfin.androidtv.enhanced.subtitles.subtitlesModule` to `featureModules` (`FeatureModules.kt`).
  It binds `SubtitleSyncStore` and `SubtitleStyleSync` (`createdAtStart = true`, so the sync starts with Koin like
  `EnhancedRepository` and applies the style once the plugin state loads, without any screen asking).
- Settings routes: add `org.jellyfin.androidtv.enhanced.subtitles.subtitlesSettingsRoutes` to
  `featureSettingsRoutes` (`SubtitlesRoutes.STYLE` = `/enhanced/subtitles` → `SubtitlesSettingsScreen()`).
- Settings entries: add `org.jellyfin.androidtv.enhanced.subtitles.subtitlesSettingsEntry` to
  `featureSettingsEntries`, before `aboutSettingsEntry` (shown when `state.available`). The exact patch I tested:

```kotlin
// FeatureModules.kt
import org.jellyfin.androidtv.enhanced.subtitles.subtitlesModule
	…
	catchUpModule,
	subtitlesModule,
	aboutModule,

// ui/settings/FeatureSettingsRoutes.kt
import org.jellyfin.androidtv.enhanced.subtitles.subtitlesSettingsEntry
import org.jellyfin.androidtv.enhanced.subtitles.subtitlesSettingsRoutes
val featureSettingsRoutes: Map<String, RouteComposable> =
	ManagementEntries.settingsRoutes + metadataSettingsRoutes + subtitlesSettingsRoutes + aboutSettingsRoutes
val featureSettingsEntries: List<SettingsEntry> =
	ManagementEntries.settingsEntries + metadataSettingsEntries + listOf(subtitlesSettingsEntry, aboutSettingsEntry)
```

- Hub: none. Upstream hooks: none. Optionally add `SUBTITLES = "/enhanced/subtitles"` to `EnhancedSettingsRoutes`
  (the package uses its own `SubtitlesRoutes.STYLE` constant, same value).
- Gates: none missing from `EnhancedConfig.kt` for showing the page. The sync reads `DefaultSubtitleStyle`,
  `DefaultSubtitleSize`, `DefaultSubtitleFont`, `DefaultSubtitleTextEffect` and `DisableCustomSubtitleStyles` from
  its own `GET public-config` (they're only defaults; `EnhancedPublicConfig` doesn't decode them). Adding them there
  would let it skip that call later; not needed now.
- Resources: `res/values/je_subtitles.xml` (strings `je_subtitles_*`), `res/drawable/je_ic_subtitles.xml` (Material
  Symbols "subtitles"). It also reuses `je_hub_unavailable`, `je_settings_title`, `je_loading`, `je_retry`,
  `je_error_generic`, `je_management_saving` and the `management.settings` helpers (`enhancedSettingsHeader`,
  `enhancedSettingsSection`, `enhancedSettingsLoad`, `EnhancedToggleRow`) and upstream's `SubtitleStylePreview`.

## How it works

- JE fields (all in `user-settings/{userId}/settings.json`, PascalCase): `DisableCustomSubtitleStyles`,
  `SelectedStylePresetIndex`, `SelectedFontSizePresetIndex`, `SelectedFontFamilyPresetIndex`,
  `SelectedTextEffectPresetIndex`, `CustomSubtitleTextColor`, `CustomSubtitleBgColor`, `SubtitleVerticalPosition`,
  `SubtitleHorizontalPosition`. Resolved like the web's `JE.loadSettings`: stored value → public-config default →
  web default. The web always renders the `Custom*` colours (picking a style preset copies its colours there), so
  the style preset index isn't used for colour.
- Mapping (`SubtitleStyleMapper`):
  - Colours: `#RRGGBBAA` / `#RRGGBB` / `transparent` (and CSS `#RGB(A)`) → ARGB text and background colour,
    alpha kept. Unreadable values fall back to JE's defaults (`#FFFFFFFF`, `#00000000`).
  - Size presets (0.8, 1, 1.2, 1.8, 2, 3 vw) → 16, 20, 24, 36, 40, 48 dp: proportional, with JE's default "Normal"
    on upstream's default 24 dp; clamped to upstream's 8..48 (Gigantic would be 60). A strictly physical vw → dp
    conversion would make every preset about half of upstream's default.
  - Text effect → upstream's edge colour: Outline, Outline + Shadow and Shadow → opaque black outline; None → no
    outline; Auto → outline on a transparent background (the web's shadow), none on a visible one.
  - Vertical position (bottom edge, % from the top) → bottom offset `(100 − y) / 100`, clamped to 0..0.8.
  - Not mapped (shown on the page): font family (the player always uses the app font), horizontal position (always
    centred), shadows (approximated by the outline). Text weight isn't touched (JE has no weight setting).
- Local state (`SharedPreferences` file `je_subtitles`): `enabled_<serverId>_<userId>` (dashless) per account, and a
  device-wide `backup_*` of the user's own five values plus `applied_*` (what the last sync wrote).
- When it runs: every `EnhancedRepository.state` emission (startup, account switch, settings saved from the app,
  `refreshInBackground`) and when the page opens or the toggle turns on. Changes made in the web client arrive with
  the next of these (opening the page is the quickest).
- Rules (`SubtitleSyncPlanner`): sync on and style known → save a backup if none yet, write the mapped values (only
  the ones that differ). `DisableCustomSubtitleStyles` true, plugin absent or state not loaded → no writes at all.
  Sync off for the signed-in account (turned off, or another account without the sync signs in) → put the backup
  back, except values the user changed in the app's own subtitle settings since the last sync, then forget it.
  Signed out → nothing. Restoring is the only write that can happen while JE's custom styles are off; it puts back the
  user's own values, never JE's.
- Routes called: `GET /JellyfinEnhanced/public-config`, `GET /JellyfinEnhanced/user-settings/{userId}/settings.json`
  (read only, through `EnhancedSettingsRepository.read`, pinned client). Nothing is written to the server.

## Suggested ENHANCED.md rows

- Features: `| Subtitle style sync | Settings › Jellyfin Enhanced › Subtitle style | plugin present; applied only when the user turns it on for this device and user `DisableCustomSubtitleStyles` is off | `subtitles` |`
- Settings pages: `| Subtitle style | "Use my Jellyfin Enhanced subtitle style on this device" (local, per server and user) and what the JE style becomes here: preview, size, colours, outline, position, and what can't be mapped (font, horizontal position, shadows) | plugin present |`

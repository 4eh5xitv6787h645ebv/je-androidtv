# Integration: reviews

Wired together with the metadata feature; see `../metadata/INTEGRATION.md`. In short: add `reviewsModule` to
`featureModules` after `metadataModule`. No settings routes, hub entries or upstream hooks.

## AniList reviews and review translation (je/reviews2)

Nothing new to register: `reviewsModule` now also binds `TranslationService` (`EnhancedTranslationService`),
`ReviewTranslator` and `TranslationTargetStore`, and `ReviewsRepository` takes `EnhancedRepository` as a third
constructor argument (all inside `ReviewsModule.kt`). `TranslationTargetStore` needs `SessionRepository` and the
Android context, both already in the app's Koin graph; `ReviewTranslator` is keyed by `SessionRepository.currentSession`
(its cache is per server, user and token).

- AniList reviews: gate `EnhancedPublicConfig.showAnilistReviews` (already in `EnhancedConfig.kt`) plus an AniList
  provider id (`AniList`/`Anilist`, case-insensitive) on the item or, for seasons and episodes, on the series.
  They load in the existing reviews row/reader, after the Spoiler Guard `hideReviews` check like the other sources.
- Translation: gate `EnhancedPublicConfig.reviewTranslationEnabled` and `ReviewTranslationServer`. The latter isn't
  in `EnhancedConfig.kt`, so `ReviewsRepository.publicConfig()` decodes it from `public-config` itself
  (`ReviewsPublicConfig`). Adding `@SerialName("ReviewTranslationServer") val reviewTranslationServer: Boolean = false`
  to `EnhancedPublicConfig` would let it drop that call; optional.
- SharedPreferences file `je_reviews` (key `translate_target_<serverId>_<userId>`) holds the chosen language.

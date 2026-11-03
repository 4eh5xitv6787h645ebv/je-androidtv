package org.jellyfin.androidtv.enhanced.management.spoiler

import androidx.annotation.StringRes
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardPrefs
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository

/**
 * One per-user Spoiler Guard override, as in the web settings panel: [key] is the `Prefs` field, [adminEnabled] the
 * public-config switch that must be on for the row to show, [value] reads the current preference.
 */
class SpoilerGuardCategory(
	val key: String,
	@StringRes val title: Int,
	@StringRes val description: Int,
	val adminEnabled: (EnhancedPublicConfig) -> Boolean,
	val value: (SpoilerGuardPrefs) -> Boolean?,
) {
	/**
	 * Whether the category is applied to this user. Null (follow the server) and true (recorded, but it can't do more
	 * than follow) both mean "applied"; only false opts out.
	 */
	fun applied(prefs: SpoilerGuardPrefs) = value(prefs) != false

	/** The value to store when the user flips the row: opting out writes false, opting back in clears it to null. */
	fun toggled(prefs: SpoilerGuardPrefs): Boolean? = if (applied(prefs)) false else null
}

object SpoilerGuardCategories {
	val all = listOf(
		SpoilerGuardCategory("HideSeriesDescriptions", R.string.je_management_sg_series_overview, R.string.je_management_sg_series_overview_desc, { it.spoilerStripSeriesOverview }, { it.hideSeriesDescriptions }),
		SpoilerGuardCategory("HideEpisodeDescriptions", R.string.je_management_sg_overview, R.string.je_management_sg_overview_desc, { it.spoilerStripOverview }, { it.hideEpisodeDescriptions }),
		SpoilerGuardCategory("ReplaceEpisodeTitles", R.string.je_management_sg_titles, R.string.je_management_sg_titles_desc, { it.spoilerReplaceTitle }, { it.replaceEpisodeTitles }),
		SpoilerGuardCategory("HideChapterNames", R.string.je_management_sg_chapters, R.string.je_management_sg_chapters_desc, { it.spoilerStripChapters }, { it.hideChapterNames }),
		SpoilerGuardCategory("HideCast", R.string.je_management_sg_cast, R.string.je_management_sg_cast_desc, { it.spoilerStripCast }, { it.hideCast }),
		SpoilerGuardCategory("HideRatings", R.string.je_management_sg_ratings, R.string.je_management_sg_ratings_desc, { it.spoilerStripRatings }, { it.hideRatings }),
		SpoilerGuardCategory("HideAirDate", R.string.je_management_sg_air_date, R.string.je_management_sg_air_date_desc, { it.spoilerStripPremiereDate }, { it.hideAirDate }),
		SpoilerGuardCategory("HideTaglines", R.string.je_management_sg_taglines, R.string.je_management_sg_taglines_desc, { it.spoilerStripTaglines }, { it.hideTaglines }),
		SpoilerGuardCategory("HideTags", R.string.je_management_sg_tags, R.string.je_management_sg_tags_desc, { it.spoilerStripTags }, { it.hideTags }),
		SpoilerGuardCategory("HideReviews", R.string.je_management_sg_reviews, R.string.je_management_sg_reviews_desc, { it.spoilerStripReviews }, { it.hideReviews }),
		SpoilerGuardCategory("HideTrickplay", R.string.je_management_sg_trickplay, R.string.je_management_sg_trickplay_desc, { it.spoilerBlurTrickplay }, { it.hideTrickplay }),
		// Only offered when the admin turned advanced mode on; unchecking it makes this user's protection stricter
		SpoilerGuardCategory(SpoilerGuardRepository.PREF_USE_ADVANCED_CATEGORIES, R.string.je_management_sg_advanced, R.string.je_management_sg_advanced_desc, { it.spoilerAdvancedMode }, { it.useAdvancedCategories }),
	)

	/** The categories the admin enabled, in display order. */
	fun available(config: EnhancedPublicConfig) = all.filter { it.adminEnabled(config) }
}

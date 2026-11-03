package org.jellyfin.androidtv.enhanced.management.settings

import androidx.annotation.StringRes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jellyfin.androidtv.R

/**
 * A field of the user's `settings.json` that the TV settings screens edit. The shown value is the user's stored one,
 * else the server default from public-config ([publicKeys], the field's own name first), else the web client's
 * built-in default.
 */
sealed class JeUserSetting(val key: String, @StringRes val title: Int, @StringRes val caption: Int?) {
	open val publicKeys: List<String> get() = listOf(key)

	class Toggle(
		key: String,
		@StringRes title: Int,
		@StringRes caption: Int? = null,
		val fallback: Boolean,
	) : JeUserSetting(key, title, caption) {
		fun value(user: JsonObject, public: JsonObject): Boolean =
			user.bool(key) ?: publicKeys.firstNotNullOfOrNull { public.bool(it) } ?: fallback
	}

	/** A string with a few known values, cycled in order. Unknown stored values are shown as is until changed. */
	class Choice(
		key: String,
		@StringRes title: Int,
		val options: List<Pair<String, Int>>,
		val fallback: String,
		private val aliases: List<String> = emptyList(),
	) : JeUserSetting(key, title, null) {
		override val publicKeys get() = listOf(key) + aliases

		fun value(user: JsonObject, public: JsonObject): String =
			user.string(key) ?: publicKeys.firstNotNullOfOrNull { public.string(it) } ?: fallback

		fun next(current: String): String {
			val index = options.indexOfFirst { it.first == current }
			return options[(index + 1) % options.size].first
		}
	}

	/** A number picked from [options]; a stored value outside them is kept in the cycle so it isn't lost silently. */
	class Number(
		key: String,
		@StringRes title: Int,
		@StringRes caption: Int? = null,
		val options: List<Int>,
		val fallback: Int,
	) : JeUserSetting(key, title, caption) {
		fun value(user: JsonObject, public: JsonObject): Int =
			user.int(key) ?: publicKeys.firstNotNullOfOrNull { public.int(it) } ?: fallback

		fun next(current: Int): Int {
			val cycle = (options + current).distinct().sorted()
			return cycle[(cycle.indexOf(current) + 1) % cycle.size]
		}
	}

	/** Nullable boolean where null means "use the server's default" (kept until the user picks a side). */
	class TriState(
		key: String,
		@StringRes title: Int,
		@StringRes caption: Int? = null,
	) : JeUserSetting(key, title, caption) {
		fun value(user: JsonObject): Boolean? = user.bool(key)

		/** Server default → On → Off → Server default. */
		fun next(current: Boolean?): Boolean? = when (current) {
			null -> true
			true -> false
			false -> null
		}
	}

	protected companion object {
		fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
		fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
		fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
	}
}

/** The TV-relevant `settings.json` fields, grouped per settings page. Fallbacks match the web client's defaults. */
object JeUserSettings {
	val showWatchProgress = JeUserSetting.Toggle("ShowWatchProgress", R.string.je_management_set_watch_progress, R.string.je_management_set_watch_progress_desc, fallback = false)
	val watchProgressMode = JeUserSetting.Choice(
		"WatchProgressMode",
		R.string.je_management_set_watch_progress_mode,
		options = listOf(
			"percentage" to R.string.je_management_set_mode_percentage,
			"time" to R.string.je_management_set_mode_time,
			"remaining" to R.string.je_management_set_mode_remaining,
		),
		fallback = "percentage",
		aliases = listOf("WatchProgressDefaultMode"),
	)
	val watchProgressTimeFormat = JeUserSetting.Choice(
		"WatchProgressTimeFormat",
		R.string.je_management_set_time_format,
		options = listOf(
			"hours" to R.string.je_management_set_time_format_hours,
			"full" to R.string.je_management_set_time_format_full,
		),
		fallback = "hours",
	)
	val showFileSizes = JeUserSetting.Toggle("ShowFileSizes", R.string.je_management_set_file_sizes, fallback = false)
	val showAudioLanguages = JeUserSetting.Toggle("ShowAudioLanguages", R.string.je_management_set_audio_languages, fallback = true)
	val reviewsExpanded = JeUserSetting.Toggle("ReviewsExpandedByDefault", R.string.je_management_set_reviews_expanded, fallback = false)
	val peopleTags = JeUserSetting.Toggle("PeopleTagsEnabled", R.string.je_management_set_people_tags, R.string.je_management_set_people_tags_desc, fallback = false)
	val nativePosterTags = JeUserSetting.TriState("UseNativePosterTags", R.string.je_management_set_native_poster_tags, R.string.je_management_set_native_poster_tags_desc)
	val removeContinueWatching = JeUserSetting.Toggle("RemoveContinueWatchingEnabled", R.string.je_management_set_remove_cw, R.string.je_management_set_remove_cw_desc, fallback = false)
	val randomButton = JeUserSetting.Toggle("RandomButtonEnabled", R.string.je_management_set_random, fallback = true)
	val randomUnwatchedOnly = JeUserSetting.Toggle("RandomUnwatchedOnly", R.string.je_management_set_random_unwatched, fallback = false)
	val randomMovies = JeUserSetting.Toggle("RandomIncludeMovies", R.string.je_management_set_random_movies, fallback = true)
	val randomShows = JeUserSetting.Toggle("RandomIncludeShows", R.string.je_management_set_random_shows, fallback = true)

	val pauseScreen = JeUserSetting.Toggle("PauseScreenEnabled", R.string.je_management_set_pause_screen, R.string.je_management_set_pause_screen_desc, fallback = true)
	val pauseScreenDelay = JeUserSetting.Number("PauseScreenDelaySeconds", R.string.je_management_set_pause_delay, options = listOf(3, 5, 10, 15, 30, 60), fallback = 5)
	val ratingInPlayer = JeUserSetting.Toggle("ShowRatingInPlayer", R.string.je_management_set_rating_in_player, fallback = true)
	val autoSkipIntro = JeUserSetting.Toggle("AutoSkipIntro", R.string.je_management_set_skip_intro, R.string.je_management_set_skip_desc, fallback = false)
	val autoSkipOutro = JeUserSetting.Toggle("AutoSkipOutro", R.string.je_management_set_skip_outro, R.string.je_management_set_skip_desc, fallback = false)

	/** Sections of the "Details & tags" page. */
	val details: List<Pair<Int, List<JeUserSetting>>> = listOf(
		R.string.je_management_set_section_details to listOf(showWatchProgress, watchProgressMode, watchProgressTimeFormat, showFileSizes, showAudioLanguages, reviewsExpanded),
		R.string.je_management_set_section_tags to listOf(nativePosterTags, peopleTags),
		R.string.je_management_set_section_home to listOf(removeContinueWatching, randomButton, randomUnwatchedOnly, randomMovies, randomShows),
	)

	/** Sections of the "Playback" page. */
	val playback: List<Pair<Int, List<JeUserSetting>>> = listOf(
		R.string.je_management_set_section_pause to listOf(pauseScreen, pauseScreenDelay, ratingInPlayer),
		R.string.je_management_set_section_skip to listOf(autoSkipIntro, autoSkipOutro),
	)
}

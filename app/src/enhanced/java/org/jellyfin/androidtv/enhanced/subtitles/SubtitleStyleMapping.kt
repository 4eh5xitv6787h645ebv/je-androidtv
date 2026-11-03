package org.jellyfin.androidtv.enhanced.subtitles

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * The user's Jellyfin Enhanced subtitle appearance, resolved the way the web client's `JE.loadSettings` does: the
 * value stored in `settings.json`, else the server default from `public-config`, else the web client's built-in
 * default. Colours stay as stored (`#RRGGBBAA`, `#RRGGBB` or `transparent`); the web applies the custom colours
 * whatever style preset is selected (choosing a preset copies its colours into them), so the preset index itself is
 * only kept for display.
 */
data class JeSubtitleStyle(
	/** The user turned JE's subtitle styling off (`DisableCustomSubtitleStyles`); nothing may be applied then. */
	val disabled: Boolean = false,
	val stylePreset: Int = DEFAULT_STYLE_PRESET,
	val textColor: String = DEFAULT_TEXT_COLOR,
	val backgroundColor: String = DEFAULT_BACKGROUND_COLOR,
	val fontSizePreset: Int = DEFAULT_SIZE_PRESET,
	val fontFamilyPreset: Int = DEFAULT_FONT_PRESET,
	val textEffectPreset: Int = TEXT_EFFECT_AUTO,
	/** Bottom edge of the subtitles, in percent of the video height from the top. */
	val verticalPosition: Int = DEFAULT_VERTICAL_POSITION,
	/** Horizontal centre of the subtitles, in percent of the video width. */
	val horizontalPosition: Int = DEFAULT_HORIZONTAL_POSITION,
) {
	companion object {
		const val DEFAULT_STYLE_PRESET = 0
		const val DEFAULT_TEXT_COLOR = "#FFFFFFFF"
		const val DEFAULT_BACKGROUND_COLOR = "#00000000"
		const val DEFAULT_SIZE_PRESET = 2
		const val DEFAULT_FONT_PRESET = 0
		const val DEFAULT_VERTICAL_POSITION = 95
		const val DEFAULT_HORIZONTAL_POSITION = 50

		const val TEXT_EFFECT_AUTO = 0
		const val TEXT_EFFECT_NONE = 1
		const val TEXT_EFFECT_SHADOW = 2
		const val TEXT_EFFECT_OUTLINE = 3
		const val TEXT_EFFECT_OUTLINE_SHADOW = 4

		/**
		 * Resolve from the user's `settings.json` ([user]) and `public-config` ([public]). Public defaults use the
		 * aliases of the web client (`DefaultSubtitleSize` for `SelectedFontSizePresetIndex` etc.); the text effect
		 * also falls back to `DefaultSubtitleTextEffect`, which the server uses to seed new settings documents.
		 */
		fun resolve(user: JsonObject, public: JsonObject) = JeSubtitleStyle(
			disabled = user.bool("DisableCustomSubtitleStyles") ?: public.bool("DisableCustomSubtitleStyles") ?: false,
			stylePreset = user.int("SelectedStylePresetIndex") ?: public.int("DefaultSubtitleStyle") ?: DEFAULT_STYLE_PRESET,
			// The web uses `stored || default`, so an empty string also means the default
			textColor = user.string("CustomSubtitleTextColor")?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT_COLOR,
			backgroundColor = user.string("CustomSubtitleBgColor")?.takeIf { it.isNotBlank() } ?: DEFAULT_BACKGROUND_COLOR,
			fontSizePreset = user.int("SelectedFontSizePresetIndex") ?: public.int("DefaultSubtitleSize") ?: DEFAULT_SIZE_PRESET,
			fontFamilyPreset = user.int("SelectedFontFamilyPresetIndex") ?: public.int("DefaultSubtitleFont") ?: DEFAULT_FONT_PRESET,
			textEffectPreset = user.int("SelectedTextEffectPresetIndex") ?: public.int("DefaultSubtitleTextEffect") ?: TEXT_EFFECT_AUTO,
			verticalPosition = user.int("SubtitleVerticalPosition") ?: DEFAULT_VERTICAL_POSITION,
			horizontalPosition = user.int("SubtitleHorizontalPosition") ?: DEFAULT_HORIZONTAL_POSITION,
		)

		private fun JsonObject.primitive(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
		private fun JsonObject.string(key: String) = primitive(key)?.content
		private fun JsonObject.bool(key: String) = primitive(key)?.content?.toBooleanStrictOrNull()
		private fun JsonObject.int(key: String) = primitive(key)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
	}
}

/**
 * The values of upstream's subtitle preferences (`UserPreferences.subtitles*`), as the player reads them: ARGB colours
 * (an edge colour with zero alpha means no outline), text size in dp and the bottom padding as a fraction of the
 * video height.
 */
data class NativeSubtitleStyle(
	val textColor: Int,
	val backgroundColor: Int,
	val strokeColor: Int,
	val textSize: Float,
	val offsetPosition: Float,
)

/** JE appearance that has no exact native equivalent, shown on the settings page. */
enum class SubtitleMappingGap {
	/** A font family other than "Default": the player always uses the app's font. */
	FONT,

	/** The subtitles are moved sideways: the player always centres them. */
	HORIZONTAL_POSITION,

	/** A drop shadow: the player can only draw an outline, so a shadow becomes a black outline. */
	SHADOW,

	/** The size preset is larger than the player's maximum text size. */
	SIZE_CLAMPED,
}

/** Translates a [JeSubtitleStyle] into upstream's [NativeSubtitleStyle]. */
object SubtitleStyleMapper {
	/** The web client's size presets, in `vw` (Tiny, Small, Normal, Large, Extra Large, Gigantic). */
	val fontSizePresets = floatArrayOf(0.8f, 1f, 1.2f, 1.8f, 2f, 3f)

	/** Number of font family presets (Default, Noto Sans, Sans Serif, Typewriter, Roboto). */
	const val FONT_FAMILY_PRESETS = 5

	/** Number of text effect presets (Auto, None, Shadow, Outline, Outline + Shadow). */
	const val TEXT_EFFECT_PRESETS = 5

	/** Upstream's text size range and default, in dp (`SettingsSubtitlesScreen`, `UserPreferences`). */
	const val MIN_TEXT_SIZE = 8f
	const val MAX_TEXT_SIZE = 48f
	const val DEFAULT_TEXT_SIZE = 24f

	/** Upstream's bottom offset range (`SettingsSubtitlesScreen`). */
	const val MIN_OFFSET = 0f
	const val MAX_OFFSET = 0.8f

	private const val OPAQUE_BLACK = 0xFF000000.toInt()
	private const val NO_STROKE = 0x00000000

	fun map(style: JeSubtitleStyle): NativeSubtitleStyle {
		val textColor = parseColor(style.textColor) ?: parseColor(JeSubtitleStyle.DEFAULT_TEXT_COLOR)!!
		val backgroundColor = parseColor(style.backgroundColor) ?: parseColor(JeSubtitleStyle.DEFAULT_BACKGROUND_COLOR)!!
		return NativeSubtitleStyle(
			textColor = textColor,
			backgroundColor = backgroundColor,
			strokeColor = if (hasOutline(style.textEffectPreset, backgroundColor)) OPAQUE_BLACK else NO_STROKE,
			textSize = textSize(style.fontSizePreset),
			offsetPosition = offset(style.verticalPosition),
		)
	}

	/** What the mapping of [style] can't reproduce exactly. */
	fun gaps(style: JeSubtitleStyle): Set<SubtitleMappingGap> = buildSet {
		if (style.fontFamilyPreset in 1 until FONT_FAMILY_PRESETS) add(SubtitleMappingGap.FONT)
		if (style.horizontalPosition.coerceIn(0, 100) != JeSubtitleStyle.DEFAULT_HORIZONTAL_POSITION) add(SubtitleMappingGap.HORIZONTAL_POSITION)
		val background = parseColor(style.backgroundColor) ?: 0
		val effect = effectiveTextEffect(style.textEffectPreset, background)
		if (effect == JeSubtitleStyle.TEXT_EFFECT_SHADOW || effect == JeSubtitleStyle.TEXT_EFFECT_OUTLINE_SHADOW) add(SubtitleMappingGap.SHADOW)
		if (rawTextSize(style.fontSizePreset) > MAX_TEXT_SIZE) add(SubtitleMappingGap.SIZE_CLAMPED)
	}

	/**
	 * Text size in dp. The web sizes text in `vw`; scaling by the same factor keeps the presets' proportions and puts
	 * JE's default ("Normal", 1.2 vw) on upstream's default of 24 dp, so the default JE style doesn't change the size.
	 * A strictly proportional conversion (1 vw = 9.6 dp on a 960 dp wide TV) would make every preset half the size
	 * of upstream's default, much smaller than TV viewers are used to. Unknown presets fall back to "Normal", as the
	 * web shows an unknown preset as its default. Rounded to whole dp, the steps of upstream's slider.
	 */
	fun textSize(preset: Int): Float = rawTextSize(preset).roundToInt().toFloat().coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)

	private fun rawTextSize(preset: Int): Float {
		val vw = fontSizePresets.getOrElse(preset) { fontSizePresets[JeSubtitleStyle.DEFAULT_SIZE_PRESET] }
		return vw * DEFAULT_TEXT_SIZE / fontSizePresets[JeSubtitleStyle.DEFAULT_SIZE_PRESET]
	}

	/**
	 * Bottom padding fraction. JE anchors the bottom edge of the subtitles at [verticalPosition] percent from the top
	 * (`bottom: 100 - y %`), which is what upstream's offset is, as a fraction. Rounded to the slider's 0.01 steps.
	 */
	fun offset(verticalPosition: Int): Float =
		((100 - verticalPosition.coerceIn(0, 100)) / 100f).coerceIn(MIN_OFFSET, MAX_OFFSET).let { (it * 100).roundToInt() / 100f }

	/**
	 * Whether the text gets an outline. The player can draw a black outline or nothing; JE's outline is black too.
	 * A shadow (chosen, or what "Auto" uses on a transparent background) is approximated by the outline, since both
	 * keep text readable on the picture. Unknown presets count as "Auto", like on the web.
	 */
	fun hasOutline(textEffect: Int, backgroundColor: Int): Boolean =
		effectiveTextEffect(textEffect, backgroundColor) != JeSubtitleStyle.TEXT_EFFECT_NONE

	/** "Auto" resolved: a shadow on a transparent background, nothing on a visible one. */
	private fun effectiveTextEffect(textEffect: Int, backgroundColor: Int): Int = when (textEffect) {
		in 1 until TEXT_EFFECT_PRESETS -> textEffect
		else -> if (alpha(backgroundColor) == 0) JeSubtitleStyle.TEXT_EFFECT_SHADOW else JeSubtitleStyle.TEXT_EFFECT_NONE
	}

	/**
	 * Parse a stored colour into ARGB: `transparent`, `#RRGGBBAA`, `#RRGGBB` (opaque), and the CSS short forms
	 * `#RGBA` and `#RGB`, case-insensitive. Returns null for anything else.
	 */
	fun parseColor(value: String?): Int? {
		val text = value?.trim() ?: return null
		if (text.equals("transparent", ignoreCase = true)) return 0
		if (!text.startsWith("#")) return null
		val hex = text.substring(1)
		if (hex.any { Character.digit(it, 16) < 0 }) return null
		val full = when (hex.length) {
			3, 4 -> hex.map { "$it$it" }.joinToString("")
			6, 8 -> hex
			else -> return null
		}
		val rgb = full.substring(0, 6).toInt(16)
		val alpha = if (full.length == 8) full.substring(6, 8).toInt(16) else 0xFF
		return (alpha shl 24) or rgb
	}

	/** `#RRGGBBAA` for display, the format JE stores. */
	fun formatColor(argb: Int): String = "#%06X%02X".format(argb and 0xFFFFFF, alpha(argb))

	fun alpha(argb: Int) = argb ushr 24
}

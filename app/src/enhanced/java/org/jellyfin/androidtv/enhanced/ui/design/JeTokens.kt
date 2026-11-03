package org.jellyfin.androidtv.enhanced.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.ShapeDefaults
import org.jellyfin.androidtv.ui.base.TypographyDefaults
import org.jellyfin.design.Tokens
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colour roles of the Native+ design system (spec §1). Static values built from upstream's [Tokens] and the
 * defaults of `JellyfinTheme.colorScheme`, so they can be used outside composition (and in unit tests).
 *
 * Interactive colours (`button`, `buttonFocused`, `buttonActive`, `listButtonFocused`…) are not repeated here: read
 * them from `JellyfinTheme.colorScheme` so a themed upstream stays the source of truth. Rule: only interactive
 * elements use the `button` grey; containers at rest use [surface] / [surfaceOverArt].
 */
object JeColors {
	/** Page background (`colorScheme.background`). */
	val background = Tokens.Color.colorGrey975

	/** Rows, cards, tiles, panels (`colorScheme.surface`). */
	val surface = Tokens.Color.colorBluegrey900

	/** Fill of a ring-focused card or row. */
	val surfaceRaised = Tokens.Color.colorBluegrey850

	/** Info chips and panels drawn on top of a backdrop. */
	val surfaceOverArt = Tokens.Color.colorBluegrey900.copy(alpha = 0.85f)

	/** Titles and body text. */
	val onSurface = Tokens.Color.colorGrey25

	/** Captions and meta lines (≈10.5:1 on [surface]). */
	val textSecondary = Tokens.Color.colorGrey200

	/** Overlines, counts, years (≈6:1 on [surface]). */
	val textTertiary = Tokens.Color.colorGrey400

	/** Border of an unselected filter chip. */
	val outline = Color.White.copy(alpha = 0.22f)

	/** Line under tab rows and between table-like rows; chip-row separators. */
	val divider = Color.White.copy(alpha = 0.12f)

	/** Tab indicator and progress fills (upstream `badge` / `rangeControlFill`). */
	val accent = Tokens.Color.colorCyan500

	/** Icons on dark containers (hub tile icon circles). */
	val accentOnDark = Tokens.Color.colorCyan400

	/** Dialog scrim (`colorScheme.scrim`). */
	val scrim = Tokens.Color.colorBlack.copy(alpha = 0.67f)

	/** The 2 dp focus ring of cards and rows with artwork or badges. */
	val focusRing = Tokens.Color.colorGrey25

	/** Count pills on hub tiles. */
	val countPill = Tokens.Color.colorBluegrey800

	/** Placeholder behind images that haven't loaded or don't exist. */
	val imagePlaceholder = Tokens.Color.colorBluegrey850

	/** Rotten Tomatoes "fresh" mark. Brand colour, keep as is. */
	val ratingFresh = Color(0xFFFA320A)

	/** Rotten Tomatoes audience mark. Brand colour, keep as is. */
	val ratingAudience = Color(0xFFF8C33C)

	/** Star ratings (TMDB, community). */
	val ratingStar = Tokens.Color.colorYellow300

	/** Warning text on a plain surface ("Approved, but not in Radarr/Sonarr"). */
	val warningText = Tokens.Color.colorOrange200
}

/** Notice palettes (spec §1.2): container, stripe + icon, title and body colours. */
enum class NoticeKind(val container: Color, val stripe: Color, val title: Color, val body: Color) {
	Info(Tokens.Color.colorBluegrey850, Tokens.Color.colorCyan400, JeColors.onSurface, JeColors.textSecondary),
	Warning(Tokens.Color.colorOrange900, Tokens.Color.colorOrange400, JeColors.onSurface, Tokens.Color.colorOrange200),
	Error(Tokens.Color.colorRed900, Tokens.Color.colorRed400, JeColors.onSurface, Tokens.Color.colorRed200),
}

/**
 * Type scale (spec §2). Every style derives from upstream's default (system Roboto). Minimum body text is 14 sp;
 * only [overline] and [badge] are 12 sp, both bold. Buttons keep upstream's `ButtonBase` style.
 */
object JeType {
	private val base: TextStyle = TypographyDefaults.Default

	/** Seerr details / Catch Up title, one line. */
	val heroTitle = base.copy(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.W500, color = JeColors.onSurface)

	/** [heroTitle] once the title wraps (max 2 lines). */
	val heroTitleWrapped = heroTitle.copy(fontSize = 40.sp, lineHeight = 46.sp)
	val pageTitle = base.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.W400, color = JeColors.onSurface)
	val pageSubtitle = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W400, color = JeColors.textSecondary)
	val sectionHeader = base.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.W500, color = JeColors.onSurface)

	/** Uppercase the text yourself ([Overline] does): `TextStyle` has no text transform. */
	val overline = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.W600, letterSpacing = 0.8.sp, color = JeColors.textTertiary)
	val tab = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.W500)
	val cardTitle = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.W500, color = JeColors.onSurface)

	/** The year after a title. */
	val cardTitleTrailing = base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.W400, color = JeColors.textTertiary)
	val body = base.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.W400, color = JeColors.onSurface.copy(alpha = 0.9f))
	val bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.W400, color = JeColors.onSurface.copy(alpha = 0.9f))
	val meta = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W400, color = JeColors.textSecondary)
	val caption = base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.W400, color = JeColors.textSecondary)
	val chip = base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.W400)
	val badge = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.W700)
	val dialogTitle = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.W500, color = JeColors.onSurface)
}

/** Spacing and layout (spec §3), built from upstream's [Tokens.Space]. */
object JeSpace {
	/** Overscan-safe page edge, same as upstream. */
	val pageHorizontal: Dp = Tokens.Space.space3xl
	val pageBottom: Dp = Tokens.Space.spaceXl
	val headerBottom: Dp = Tokens.Space.spaceMd
	val sectionGap: Dp = Tokens.Space.spaceLg
	val sectionHeaderGap: Dp = Tokens.Space.spaceSm
	val rowGap: Dp = 12.dp
	val gridGap: Dp = 12.dp
	val rowPadding: Dp = 12.dp
	val rowPaddingCompact: Dp = 10.dp
	val chipGap: Dp = Tokens.Space.spaceSm
	val actionGap: Dp = 12.dp

	/** Between [LabeledIconButton]s and after the primary button of an [ActionRow]. */
	val labeledActionGap: Dp = 20.dp

	/** Hero content start without / with the main toolbar. */
	val heroTop: Dp = 60.dp
	val heroTopWithToolbar: Dp = 85.dp
}

/**
 * Shapes (spec §4). Pills are pressable; rounded rectangles are containers. Values are upstream's [ShapeDefaults],
 * which is what `JellyfinTheme.shapes` holds unless a screen overrides it.
 */
object JeShapes {
	/** Buttons, icon buttons, chips, the tab focus pill. */
	val pill: Shape = CircleShape

	/** Rows, cards, hub tiles, season cards, panels, banners. */
	val container: CornerBasedShape = ShapeDefaults.Medium

	/** Posters, thumbnails, info chips (never a pill: a pill means pressable). */
	val image: CornerBasedShape = ShapeDefaults.Small

	/** Status badges. */
	val badge: CornerBasedShape = ShapeDefaults.ExtraSmall

	/** Dialog panels. */
	val dialog: CornerBasedShape = ShapeDefaults.Large
}

/** WCAG contrast helpers, used by the tests that keep the palette honest and by callers picking text on art. */
object JeContrast {
	/** Relative luminance of an opaque colour (alpha is ignored). */
	fun luminance(color: Color): Double =
		0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

	/** One sRGB channel (0–1) to linear light, with the sRGB standard's 0.04045 breakpoint (continuous there). */
	internal fun linearize(value: Float): Double =
		if (value <= 0.04045f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

	/** [foreground] composited over an opaque [background]. */
	fun composite(foreground: Color, background: Color): Color {
		val a = foreground.alpha
		return Color(
			red = foreground.red * a + background.red * (1 - a),
			green = foreground.green * a + background.green * (1 - a),
			blue = foreground.blue * a + background.blue * (1 - a),
		)
	}

	/** Contrast ratio (1–21) of [foreground] (composited if translucent) on an opaque [background]. */
	fun ratio(foreground: Color, background: Color): Double {
		val fg = luminance(composite(foreground, background))
		val bg = luminance(background)
		return (max(fg, bg) + 0.05) / (min(fg, bg) + 0.05)
	}
}

package org.jellyfin.androidtv.enhanced.ui.design

import androidx.compose.ui.graphics.Color
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.base.colorScheme

/** Keeps the palette at WCAG AA (4.5:1) for its text, as spec §1 and §9.3 require. */
class JeContrastTest : FunSpec({
	val scheme = colorScheme()

	test("contrast ratio of the extremes") {
		JeContrast.ratio(Color.White, Color.Black) shouldBe (21.0 plusOrMinus 0.01)
		JeContrast.ratio(JeColors.surface, JeColors.surface) shouldBe (1.0 plusOrMinus 0.001)
	}

	test("channels linearize with the sRGB breakpoint 0.04045") {
		// 0.04 sits between WCAG 2.0's old 0.03928 and sRGB's 0.04045: still on the linear segment
		JeContrast.linearize(0.04f) shouldBe (0.04 / 12.92 plusOrMinus 1e-7)
		// Both segments meet at the breakpoint, so there is no jump either side of it
		JeContrast.linearize(0.04045f) shouldBe (JeContrast.linearize(0.04046f) plusOrMinus 1e-6)
		JeContrast.linearize(0.5f) shouldBe (0.2140 plusOrMinus 1e-3)
		JeContrast.linearize(1f) shouldBe (1.0 plusOrMinus 1e-6)
	}

	test("translucent text is composited over its background") {
		val half = JeContrast.composite(Color.White.copy(alpha = 0.5f), Color.Black)
		half.red.toDouble() shouldBe (0.5 plusOrMinus 0.01)
		JeContrast.ratio(Color.White.copy(alpha = 0f), Color.Black) shouldBe (1.0 plusOrMinus 0.001)
	}

	test("every status badge is legible at 12 sp bold") {
		for (status in JeStatus.entries) {
			withClue(status.name) { JeContrast.ratio(status.content, status.container) shouldBeGreaterThanOrEqual 4.5 }
		}
	}

	test("text roles on the surfaces they sit on") {
		for (background in listOf(JeColors.surface, JeColors.surfaceRaised, JeColors.background)) {
			JeContrast.ratio(JeColors.onSurface, background) shouldBeGreaterThanOrEqual 4.5
			JeContrast.ratio(JeColors.textSecondary, background) shouldBeGreaterThanOrEqual 4.5
			JeContrast.ratio(JeColors.textTertiary, background) shouldBeGreaterThanOrEqual 4.5
			JeContrast.ratio(JeColors.warningText, background) shouldBeGreaterThanOrEqual 4.5
		}
		JeContrast.ratio(JeColors.textSecondary, JeColors.surface) shouldBeGreaterThanOrEqual 10.0
	}

	test("notice titles and bodies on their containers") {
		for (kind in NoticeKind.entries) {
			withClue(kind.name) {
				JeContrast.ratio(kind.title, kind.container) shouldBeGreaterThanOrEqual 4.5
				JeContrast.ratio(kind.body, kind.container) shouldBeGreaterThanOrEqual 4.5
			}
		}
	}

	test("text on the inverted focus fill stays readable at full strength") {
		val focusFill = JeContrast.composite(scheme.buttonFocused, JeColors.background)
		JeContrast.ratio(scheme.onButtonFocused, focusFill) shouldBeGreaterThanOrEqual 4.5
	}

	test("text over the backdrop's left column") {
		// The horizontal scrim is at least 75 % background over the text column; assume white art beneath it
		val scrimmed = JeContrast.composite(JeColors.background.copy(alpha = 0.75f), Color.White)
		JeContrast.ratio(JeColors.onSurface, scrimmed) shouldBeGreaterThanOrEqual 4.5
		JeContrast.ratio(JeColors.onSurface, JeContrast.composite(JeColors.surfaceOverArt, Color.White)) shouldBeGreaterThanOrEqual 4.5
		// Unselected chips and captions on the hero's top-right panel (Catch Up)
		JeContrast.ratio(JeColors.textSecondary, JeContrast.composite(JeColors.surfaceOverArt, Color.White)) shouldBeGreaterThanOrEqual 4.5
	}

	test("the hero text column ends inside the dark part of the scrim") {
		// 960 dp is the TV's width in dp; the column starts and ends at the page margins
		val width = 960f
		val margin = JeSpace.pageHorizontal.value
		val textEnd = (margin + HeroTextWidth * (width - 2 * margin)) / width
		textEnd shouldBeLessThanOrEqual HeroScrimEnd
	}
})

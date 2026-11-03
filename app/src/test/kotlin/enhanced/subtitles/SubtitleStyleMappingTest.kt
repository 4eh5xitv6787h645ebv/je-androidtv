package org.jellyfin.androidtv.enhanced.subtitles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

class SubtitleStyleMappingTest : FunSpec({
	fun json(text: String) = EnhancedApi.json.parseToJsonElement(text).jsonObject
	val empty = JsonObject(emptyMap())

	test("colours parse JE's #RRGGBBAA as ARGB, including alpha") {
		SubtitleStyleMapper.parseColor("#FFFFFFFF") shouldBe 0xFFFFFFFF.toInt()
		SubtitleStyleMapper.parseColor("#000000B2") shouldBe 0xB2000000.toInt()
		SubtitleStyleMapper.parseColor("#444444B2") shouldBe 0xB2444444.toInt()
		SubtitleStyleMapper.parseColor("#FFFF00FF") shouldBe 0xFFFFFF00.toInt()
		SubtitleStyleMapper.parseColor("#00000000") shouldBe 0
		// The web's colour picker stores a lowercase swatch with an uppercase alpha
		SubtitleStyleMapper.parseColor("#ff8800CC") shouldBe 0xCCFF8800.toInt()
	}

	test("colours accept transparent, #RRGGBB and the CSS short forms, and reject anything else") {
		SubtitleStyleMapper.parseColor("transparent") shouldBe 0
		SubtitleStyleMapper.parseColor(" Transparent ") shouldBe 0
		SubtitleStyleMapper.parseColor("#123456") shouldBe 0xFF123456.toInt()
		SubtitleStyleMapper.parseColor("#abc") shouldBe 0xFFAABBCC.toInt()
		SubtitleStyleMapper.parseColor("#abc8") shouldBe 0x88AABBCC.toInt()
		SubtitleStyleMapper.parseColor("#12345").shouldBeNull()
		SubtitleStyleMapper.parseColor("#GGGGGG").shouldBeNull()
		SubtitleStyleMapper.parseColor("rgba(0,0,0,0.5)").shouldBeNull()
		SubtitleStyleMapper.parseColor("").shouldBeNull()
		SubtitleStyleMapper.parseColor(null).shouldBeNull()
	}

	test("colours format back to JE's #RRGGBBAA") {
		SubtitleStyleMapper.formatColor(0xB2000000.toInt()) shouldBe "#000000B2"
		SubtitleStyleMapper.formatColor(0xFFFFFF00.toInt()) shouldBe "#FFFF00FF"
	}

	test("size presets keep their proportions with Normal on upstream's 24 dp default, clamped to 8..48") {
		(0..5).map { SubtitleStyleMapper.textSize(it) } shouldBe listOf(16f, 20f, 24f, 36f, 40f, 48f)
		SubtitleStyleMapper.textSize(2) shouldBe SubtitleStyleMapper.DEFAULT_TEXT_SIZE
		// Unknown presets fall back to Normal
		SubtitleStyleMapper.textSize(-1) shouldBe 24f
		SubtitleStyleMapper.textSize(17) shouldBe 24f
	}

	test("vertical position maps the bottom edge to upstream's bottom offset, clamped to 0..0.8") {
		SubtitleStyleMapper.offset(95) shouldBe 0.05f
		SubtitleStyleMapper.offset(85) shouldBe 0.15f
		SubtitleStyleMapper.offset(100) shouldBe 0f
		SubtitleStyleMapper.offset(130) shouldBe 0f
		SubtitleStyleMapper.offset(20) shouldBe 0.8f
		SubtitleStyleMapper.offset(-5) shouldBe 0.8f
	}

	test("text effects map to a black outline or none; Auto depends on the background") {
		val transparent = 0
		val box = 0xB2000000.toInt()
		SubtitleStyleMapper.hasOutline(JeSubtitleStyle.TEXT_EFFECT_AUTO, transparent) shouldBe true
		SubtitleStyleMapper.hasOutline(JeSubtitleStyle.TEXT_EFFECT_AUTO, box) shouldBe false
		SubtitleStyleMapper.hasOutline(JeSubtitleStyle.TEXT_EFFECT_NONE, transparent) shouldBe false
		SubtitleStyleMapper.hasOutline(JeSubtitleStyle.TEXT_EFFECT_SHADOW, box) shouldBe true
		SubtitleStyleMapper.hasOutline(JeSubtitleStyle.TEXT_EFFECT_OUTLINE, box) shouldBe true
		SubtitleStyleMapper.hasOutline(JeSubtitleStyle.TEXT_EFFECT_OUTLINE_SHADOW, box) shouldBe true
		// Unknown presets resolve to Auto, like the web
		SubtitleStyleMapper.hasOutline(9, box) shouldBe false
		SubtitleStyleMapper.hasOutline(9, transparent) shouldBe true
	}

	test("the default JE style maps to upstream's defaults except for the position") {
		SubtitleStyleMapper.map(JeSubtitleStyle()) shouldBe NativeSubtitleStyle(
			textColor = 0xFFFFFFFF.toInt(),
			backgroundColor = 0,
			strokeColor = 0xFF000000.toInt(),
			textSize = 24f,
			offsetPosition = 0.05f,
		)
	}

	test("a preset style maps colours, size, effect and position") {
		val style = JeSubtitleStyle(
			textColor = "#FFFF00FF",
			backgroundColor = "#000000B2",
			fontSizePreset = 3,
			textEffectPreset = JeSubtitleStyle.TEXT_EFFECT_AUTO,
			verticalPosition = 85,
		)
		SubtitleStyleMapper.map(style) shouldBe NativeSubtitleStyle(
			textColor = 0xFFFFFF00.toInt(),
			backgroundColor = 0xB2000000.toInt(),
			strokeColor = 0,
			textSize = 36f,
			offsetPosition = 0.15f,
		)
	}

	test("unreadable colours fall back to JE's defaults") {
		val native = SubtitleStyleMapper.map(JeSubtitleStyle(textColor = "red", backgroundColor = "#12"))
		native.textColor shouldBe 0xFFFFFFFF.toInt()
		native.backgroundColor shouldBe 0
	}

	test("gaps name what the player can't reproduce") {
		SubtitleStyleMapper.gaps(JeSubtitleStyle(backgroundColor = "#000000FF")).shouldBeEmpty()
		// Auto on a transparent background is a shadow on the web
		SubtitleStyleMapper.gaps(JeSubtitleStyle()) shouldContainExactlyInAnyOrder listOf(SubtitleMappingGap.SHADOW)
		SubtitleStyleMapper.gaps(
			JeSubtitleStyle(
				backgroundColor = "#000000FF",
				fontFamilyPreset = 3,
				horizontalPosition = 30,
				textEffectPreset = JeSubtitleStyle.TEXT_EFFECT_OUTLINE_SHADOW,
				fontSizePreset = 5,
			)
		) shouldContainExactlyInAnyOrder SubtitleMappingGap.entries
		// Unknown font presets are shown as the default font, not as a gap
		SubtitleStyleMapper.gaps(JeSubtitleStyle(backgroundColor = "#000000FF", fontFamilyPreset = 12)).shouldBeEmpty()
	}

	test("resolve prefers the stored value, then the public default, then the web default") {
		val user = json(
			"""{"DisableCustomSubtitleStyles":false,"SelectedStylePresetIndex":3,"SelectedFontSizePresetIndex":4,
			|"SelectedFontFamilyPresetIndex":1,"SelectedTextEffectPresetIndex":3,"CustomSubtitleTextColor":"#FFFF00FF",
			|"CustomSubtitleBgColor":"#000000B2","SubtitleVerticalPosition":85,"SubtitleHorizontalPosition":40}""".trimMargin()
		)
		val public = json("""{"DefaultSubtitleStyle":1,"DefaultSubtitleSize":0,"DefaultSubtitleFont":2,"DefaultSubtitleTextEffect":4,"DisableCustomSubtitleStyles":true}""")
		JeSubtitleStyle.resolve(user, public) shouldBe JeSubtitleStyle(
			disabled = false,
			stylePreset = 3,
			textColor = "#FFFF00FF",
			backgroundColor = "#000000B2",
			fontSizePreset = 4,
			fontFamilyPreset = 1,
			textEffectPreset = 3,
			verticalPosition = 85,
			horizontalPosition = 40,
		)
		JeSubtitleStyle.resolve(empty, public) shouldBe JeSubtitleStyle(
			disabled = true,
			stylePreset = 1,
			fontSizePreset = 0,
			fontFamilyPreset = 2,
			textEffectPreset = 4,
		)
		JeSubtitleStyle.resolve(empty, empty) shouldBe JeSubtitleStyle()
	}

	test("resolve treats nulls and empty colours as missing") {
		val user = json("""{"SelectedFontSizePresetIndex":null,"CustomSubtitleTextColor":"","DisableCustomSubtitleStyles":null}""")
		val public = json("""{"DefaultSubtitleSize":5,"DisableCustomSubtitleStyles":true}""")
		val style = JeSubtitleStyle.resolve(user, public)
		style.fontSizePreset shouldBe 5
		style.textColor shouldBe JeSubtitleStyle.DEFAULT_TEXT_COLOR
		style.disabled shouldBe true
	}
})

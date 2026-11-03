package org.jellyfin.androidtv.enhanced.about

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

class WhatsNewSettingsTest : FunSpec({
	test("setting keys become readable labels") {
		WhatsNewSettings.label("qualityTagsPreferredAudioLanguage") shouldBe "Quality tags preferred audio language"
		WhatsNewSettings.label("DefaultSubtitleTextEffect") shouldBe "Default subtitle text effect"
		WhatsNewSettings.label("mmSchedAction_accounts") shouldBe "Mm sched action accounts"
		WhatsNewSettings.label("showTMDBRatings") shouldBe "Show TMDB ratings"
		WhatsNewSettings.label("catchUpEnabled") shouldBe "Catch up enabled"
		WhatsNewSettings.label("") shouldBe ""
	}

	test("versions compare numerically") {
		WhatsNewSettings.versionOrder.compare("12.13.0.0", "12.8.0.0") shouldBe 1
		WhatsNewSettings.versionOrder.compare("12.8", "12.8.0.0") shouldBe 0
		WhatsNewSettings.versionOrder.compare("1.x", "1.0") shouldBe 0
	}

	test("new settings are listed newest version first, then by label") {
		val sorted = WhatsNewSettings.sorted(
			mapOf("spoilerAdvancedMode" to "12.8.0.0", "tagStylesEnabled" to "12.13.0.0", "catchUpEnabled" to "12.13.0.0")
		)
		sorted.map { it.first } shouldBe listOf("catchUpEnabled", "tagStylesEnabled", "spoilerAdvancedMode")
	}

	test("decodes the whats-new response, including an empty state") {
		val news = EnhancedApi.json.decodeFromString<WhatsNew>("""{"pluginVersion":"12.13.0.0","newSettings":{"catchUpEnabled":"12.13.0.0"}}""")
		news.newSettings shouldBe mapOf("catchUpEnabled" to "12.13.0.0")
		EnhancedApi.json.decodeFromString<WhatsNew>("""{"pluginVersion":null,"newSettings":{}}""").newSettings shouldBe emptyMap()
	}
})

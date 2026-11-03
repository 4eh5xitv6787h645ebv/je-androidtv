package org.jellyfin.androidtv.enhanced.management

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.management.settings.JeUserSettings

class JeUserSettingsTest : FunSpec({
	fun json(text: String) = EnhancedApi.json.parseToJsonElement(text).jsonObject
	val empty = JsonObject(emptyMap())

	test("the stored value wins, then the public default, then the web client's default") {
		val setting = JeUserSettings.showFileSizes
		setting.value(json("""{"ShowFileSizes":true}"""), json("""{"ShowFileSizes":false}""")) shouldBe true
		setting.value(empty, json("""{"ShowFileSizes":true}""")) shouldBe true
		setting.value(json("""{"ShowFileSizes":null}"""), empty) shouldBe false
	}

	test("watch progress mode falls back to the public WatchProgressDefaultMode and cycles its values") {
		val mode = JeUserSettings.watchProgressMode
		mode.value(empty, json("""{"WatchProgressDefaultMode":"time"}""")) shouldBe "time"
		mode.value(json("""{"WatchProgressMode":"remaining"}"""), json("""{"WatchProgressDefaultMode":"time"}""")) shouldBe "remaining"
		mode.value(json("""{"WatchProgressMode":""}"""), empty) shouldBe "percentage"
		mode.next("percentage") shouldBe "time"
		mode.next("remaining") shouldBe "percentage"
		mode.next("unknown") shouldBe "percentage"
	}

	test("pause delay cycles through the presets and keeps a custom stored value in the cycle") {
		val delay = JeUserSettings.pauseScreenDelay
		delay.value(empty, json("""{"PauseScreenDelaySeconds":7}""")) shouldBe 7
		delay.next(5) shouldBe 10
		delay.next(60) shouldBe 3
		delay.next(7) shouldBe 10
	}

	test("native poster tags keep null as the server default until chosen") {
		val tags = JeUserSettings.nativePosterTags
		tags.value(json("""{"UseNativePosterTags":null}""")).shouldBeNull()
		tags.value(json("""{"UseNativePosterTags":false}""")) shouldBe false
		tags.next(null) shouldBe true
		tags.next(true) shouldBe false
		tags.next(false).shouldBeNull()
	}
})

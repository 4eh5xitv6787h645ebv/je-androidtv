package org.jellyfin.androidtv.enhanced.management

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ManagementArtworkTest : FunSpec({
	fun item(json: String) = Json.parseToJsonElement(json).jsonObject

	test("posters fall back from Primary to Thumb to the first backdrop") {
		artworkPath(item("""{"ImageTags":{"Primary":"p","Thumb":"t"},"BackdropImageTags":["b"]}""")) shouldBe ("Primary" to "p")
		// The lab's Silo: a guarded series with only a Thumb
		artworkPath(item("""{"ImageTags":{"Thumb":"sb-69aeea2c-jeu082365608004"},"BackdropImageTags":[]}""")) shouldBe
			("Thumb" to "sb-69aeea2c-jeu082365608004")
		artworkPath(item("""{"ImageTags":{},"BackdropImageTags":["b0","b1"]}""")) shouldBe ("Backdrop/0" to "b0")
		artworkPath(item("""{"ImageTags":{"Primary":""}}""")).shouldBeNull()
		artworkPath(item("""{}""")).shouldBeNull()
	}
})

package org.jellyfin.androidtv.enhanced.reviews

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import java.util.Locale

/** Records calls and answers with "[target] text", or throws [failure] when set. */
private class FakeTranslationService(var failure: Exception? = null) : TranslationService {
	val calls = mutableListOf<Pair<String, String>>()
	var languageList = listOf("de", "fr", "zt")

	override suspend fun languages(): List<String> {
		failure?.let { throw it }
		return languageList
	}

	override suspend fun translate(text: String, target: String): String {
		failure?.let { throw it }
		calls += text to target
		return "[$target] $text"
	}
}

private const val SESSION = "server-a/user-a/token-a"

class ReviewTranslationTest : FunSpec({
	val review = ReviewEntry(ReviewEntry.Source.ANILIST, "paradigm", 85.0, null, "Body text.", summary = "Headline")

	test("translates the headline and the body and caches them per target") {
		val service = FakeTranslationService()
		val translator = ReviewTranslator(service) { SESSION }

		val german = translator.translate(review, "de")
		german shouldBe TranslatedReview("[de] Headline", "[de] Body text.", truncated = false)
		service.calls.size shouldBe 2

		translator.translate(review, "de") shouldBe german
		service.calls.size shouldBe 2

		translator.translate(review, "fr").content shouldBe "[fr] Body text."
		service.calls.size shouldBe 4
	}

	test("reviews without a headline make one call") {
		val service = FakeTranslationService()
		val tmdb = ReviewEntry(ReviewEntry.Source.TMDB, "tricksy", null, null, "Only text")
		ReviewTranslator(service) { SESSION }.translate(tmdb, "de") shouldBe TranslatedReview(null, "[de] Only text", false)
		service.calls shouldContainExactly listOf("Only text" to "de")
	}

	test("failures map to disabled, service down or the server's message, and nothing is cached") {
		val service = FakeTranslationService(EnhancedApi.EnhancedApiException(503, "HTTP 503", JsonPrimitive("Review translation is not enabled.")))
		val translator = ReviewTranslator(service) { SESSION }
		val disabled = shouldThrow<EnhancedApi.EnhancedApiException> { translator.translate(review, "de") }
		TranslationFailure.of(disabled) shouldBe TranslationFailure.Disabled
		service.calls shouldBe emptyList()

		TranslationFailure.of(EnhancedApi.EnhancedApiException(502, "HTTP 502")) shouldBe TranslationFailure.ServiceDown
		val bad = TranslationFailure.of(EnhancedApi.EnhancedApiException(400, "HTTP 400", JsonPrimitive("target must be a language code.")))
		bad.shouldBeInstanceOf<TranslationFailure.Other>().message shouldBe "target must be a language code."
		TranslationFailure.of(IllegalStateException("boom")) shouldBe TranslationFailure.Other(null)

		service.failure = null
		translator.translate(review, "de").content shouldBe "[de] Body text."
	}

	test("the language list is trimmed and de-duplicated") {
		val service = FakeTranslationService().apply { languageList = listOf("de", " fr ", "", "de") }
		ReviewTranslator(service) { SESSION }.languages() shouldContainExactly listOf("de", "fr")
	}

	test("the plugin service posts Text and Target and reads text back") {
		val api = mockk<EnhancedApi>()
		val body = slot<JsonObject>()
		coEvery { api.post("translate", capture(body), any()) } returns buildJsonObject { put("text", "Hallo") }
		coEvery { api.get("translate/languages", any()) } returns
			EnhancedApi.json.parseToJsonElement("""{"languages":["de","zt"]}""")

		val service = EnhancedTranslationService(api)
		service.translate("Hello", "de") shouldBe "Hallo"
		body.captured["Text"]?.jsonPrimitive?.content shouldBe "Hello"
		body.captured["Target"]?.jsonPrimitive?.content shouldBe "de"
		service.languages() shouldContainExactly listOf("de", "zt")
		coVerify(exactly = 1) { api.post("translate", any(), any()) }

		coEvery { api.post("translate", any(), any()) } returns buildJsonObject { }
		shouldThrow<IllegalStateException> { service.translate("Hello", "de") }
	}

	test("text is sent as plain prose within the plugin's limit") {
		prepareForTranslation("Read **this** at https://example.com/x  now.\n\nThen `that`.") shouldBe
			PreparedText("Read this at now.\n\nThen that.", truncated = false)

		val long = (1..400).joinToString("\n\n") { "Paragraph $it " + "word ".repeat(10).trim() }
		val prepared = prepareForTranslation(long, limit = 1000)
		prepared.truncated shouldBe true
		(prepared.text.length <= 1000) shouldBe true
		prepared.text.endsWith("word") shouldBe true
		prepared.text shouldNotContain "\n\n\n"

		prepareForTranslation("x".repeat(30), limit = 10) shouldBe PreparedText("x".repeat(10), truncated = true)
		prepareForTranslation("a".repeat(ReviewTranslator.MAX_TEXT_LENGTH)).truncated shouldBe false
	}

	test("language names and the preselected target") {
		translationLanguageName("de", Locale.ENGLISH) shouldBe "German"
		translationLanguageName("zt", Locale.ENGLISH) shouldBe "Chinese (Traditional)"
		translationLanguageName("pb", Locale.ENGLISH) shouldBe "Portuguese (Brazil)"
		translationLanguageName("qq", Locale.ENGLISH) shouldBe "qq"

		val codes = listOf("de", "fr", "zh", "zt", "pt", "pb")
		defaultTranslationTarget(codes, Locale.GERMANY) shouldBe "de"
		defaultTranslationTarget(codes, Locale.TAIWAN) shouldBe "zt"
		defaultTranslationTarget(codes, Locale.SIMPLIFIED_CHINESE) shouldBe "zh"
		defaultTranslationTarget(codes, Locale.forLanguageTag("pt-BR")) shouldBe "pb"
		defaultTranslationTarget(codes, Locale.forLanguageTag("pt-PT")) shouldBe "pt"
		defaultTranslationTarget(codes, Locale.US) shouldBe null
		defaultTranslationTarget(codes, Locale.JAPAN) shouldBe null
	}

	test("the remembered target is keyed by server and user") {
		TranslationTargetStore.key("8F3A-11", "7EF882CD-3073-4427-9240-D0ECA8333A9F") shouldBe
			"translate_target_8f3a11_7ef882cd307344279240d0eca8333a9f"
	}
})

package org.jellyfin.androidtv.enhanced.reviews

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Answers "[target] text" once [release] is called for that target, so tests decide the order calls finish in. */
private class GatedTranslationService : TranslationService {
	val calls = mutableListOf<Pair<String, String>>()
	private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

	private fun gate(target: String) = gates.getOrPut(target) { CompletableDeferred() }

	fun release(target: String) = gate(target).complete(Unit)

	override suspend fun languages(): List<String> = listOf("de", "fr")

	override suspend fun translate(text: String, target: String): String {
		calls += text to target
		gate(target).await()
		return "[$target] $text"
	}
}

class ReaderTranslationsTest : FunSpec({
	val review = ReviewEntry(ReviewEntry.Source.TMDB, "tricksy", null, null, "Only text")

	// Unconfined: launched work runs to its first suspension at once, and resumes as soon as a gate opens
	fun reader(service: TranslationService, language: String?, session: () -> Any? = { "a" }) =
		ReaderTranslations(ReviewTranslator(service, session), CoroutineScope(Dispatchers.Unconfined), language) { }

	test("a translation into the old language finishing after the new one was asked for is not shown") {
		val service = GatedTranslationService()
		val translations = reader(service, "de")

		translations.translate(review, "de")
		translations.translating shouldBe review
		translations.changeLanguage("fr")
		translations.translate(review, "fr")
		service.calls shouldContainExactly listOf("Only text" to "de", "Only text" to "fr")
		translations.translating shouldBe review

		// German answers first, after French was issued
		service.release("de")
		translations.shown(review) shouldBe null
		translations.translating shouldBe review

		service.release("fr")
		translations.shown(review) shouldBe TranslatedReview(null, "[fr] Only text", truncated = false)
		translations.translating shouldBe null
		translations.language shouldBe "fr"
	}

	test("results are kept per language and the toggle flips between translation and original") {
		val service = GatedTranslationService().apply { release("de"); release("fr") }
		val translations = reader(service, "de")
		var picked = false

		translations.toggle(review) { picked = true }
		translations.shown(review)?.content shouldBe "[de] Only text"
		translations.toggle(review) { picked = true }
		translations.shown(review) shouldBe null
		translations.toggle(review) { picked = true }
		translations.shown(review)?.content shouldBe "[de] Only text"
		service.calls.size shouldBe 1

		translations.changeLanguage("fr")
		translations.shown(review) shouldBe null
		translations.toggle(review) { picked = true }
		translations.shown(review)?.content shouldBe "[fr] Only text"
		picked shouldBe false

		// No language yet: the picker is asked for
		reader(service, null).toggle(review) { picked = true }
		picked shouldBe true
	}

	test("the translator cache is per session") {
		val service = GatedTranslationService().apply { release("de") }
		var session = "a"
		val translator = ReviewTranslator(service) { session }

		translator.translate(review, "de")
		translator.translate(review, "de")
		service.calls.size shouldBe 1

		session = "b"
		translator.translate(review, "de")
		service.calls.size shouldBe 2
	}

	test("a translation that outlives its session doesn't fill the next session's cache") {
		val service = GatedTranslationService()
		var session = "a"
		val translator = ReviewTranslator(service) { session }

		val old = CoroutineScope(Dispatchers.Unconfined).launch { translator.translate(review, "de") }
		session = "b"
		service.release("de")
		old.isCompleted shouldBe true

		translator.translate(review, "de")
		service.calls.size shouldBe 2
		translator.translate(review, "de")
		service.calls.size shouldBe 2
	}
})

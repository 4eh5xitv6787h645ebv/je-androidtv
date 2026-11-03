package org.jellyfin.androidtv.enhanced.reviews

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The review reader's translations: results per review and target language, which reviews show theirs, and the one
 * translation in flight. Changing the language cancels a translation into the old one, and a result only counts for
 * the language it was asked in, so a late answer can't be shown under another language's name.
 */
class ReaderTranslations(
	private val translator: ReviewTranslator,
	private val scope: CoroutineScope,
	language: String?,
	private val onFailure: (TranslationFailure) -> Unit,
) {
	private class Request(val entry: ReviewEntry, val target: String)

	/** The language reviews are translated into, once known. */
	var language by mutableStateOf(language)
		private set

	private val results = mutableStateMapOf<Pair<ReviewEntry, String>, TranslatedReview>()
	private val showing = mutableStateMapOf<ReviewEntry, Boolean>()
	private var request by mutableStateOf<Request?>(null)
	private var job: Job? = null

	/** The review being translated, if any. */
	val translating: ReviewEntry? get() = request?.entry

	/** [entry]'s translation into the current language, when it is the one shown. */
	fun shown(entry: ReviewEntry): TranslatedReview? =
		language?.let { results[entry to it] }?.takeIf { showing[entry] == true }

	/** Switch to [code]; reviews show their original again and a translation into the old language is dropped. */
	fun changeLanguage(code: String) {
		if (code == language) return
		language = code
		showing.clear()
		job?.cancel()
		job = null
		request = null
	}

	/**
	 * OK on [entry]: back to the original, the translation already made, or a new one. [pickLanguage] runs when no
	 * language was chosen yet.
	 */
	fun toggle(entry: ReviewEntry, pickLanguage: () -> Unit) {
		val code = language
		when {
			request != null -> Unit
			showing[entry] == true -> showing[entry] = false
			code != null && results[entry to code] != null -> showing[entry] = true
			code != null -> translate(entry, code)
			else -> pickLanguage()
		}
	}

	/** Translate [entry] into [target] (the current language) and show it; ignored while another one runs. */
	fun translate(entry: ReviewEntry, target: String) {
		if (request != null || target != language) return
		val started = Request(entry, target)
		request = started
		job = scope.launch {
			try {
				val translated = translator.translate(entry, target)
				results[entry to target] = translated
				if (target == language) showing[entry] = true
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to translate a review")
				onFailure(TranslationFailure.of(err))
			} finally {
				// A newer request may have replaced this one
				if (request === started) {
					request = null
					job = null
				}
			}
		}
	}
}

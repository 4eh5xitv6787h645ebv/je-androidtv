package org.jellyfin.androidtv.enhanced.reviews

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.metadata.displayMessage
import java.util.Locale

/** `GET translate/languages`: the codes English reviews can be translated into (LibreTranslate's, e.g. `zt`, `pb`). */
@Serializable
data class TranslationLanguages(val languages: List<String> = emptyList())

/** `POST translate` response. */
@Serializable
data class TranslationResult(val text: String? = null)

/** The plugin's review translation routes. An interface so [ReviewTranslator] can be tested with fakes. */
interface TranslationService {
	suspend fun languages(): List<String>

	/** [text] (English, 1–[ReviewTranslator.MAX_TEXT_LENGTH] characters) translated into [target]. */
	suspend fun translate(text: String, target: String): String
}

/** [TranslationService] over the plugin's `translate` routes (503 when off, 502 when LibreTranslate fails). */
class EnhancedTranslationService(private val enhancedApi: EnhancedApi) : TranslationService {
	override suspend fun languages(): List<String> =
		EnhancedApi.json.decodeFromJsonElement<TranslationLanguages>(enhancedApi.get("translate/languages")).languages

	override suspend fun translate(text: String, target: String): String {
		val body = buildJsonObject {
			put("Text", text)
			put("Target", target)
		}
		val result = EnhancedApi.json.decodeFromJsonElement<TranslationResult>(enhancedApi.post("translate", body))
		return result.text ?: throw IllegalStateException("The translation service returned no text")
	}
}

/** A review in another language. [truncated] when only the first [ReviewTranslator.MAX_TEXT_LENGTH] characters were sent. */
data class TranslatedReview(val summary: String?, val content: String, val truncated: Boolean)

/** Why a translation failed, for the reader's toast. */
sealed interface TranslationFailure {
	/** 503: the admin turned translation off (or removed the service URL) since the screen opened. */
	data object Disabled : TranslationFailure

	/** 502: the plugin couldn't reach the translation service. */
	data object ServiceDown : TranslationFailure

	/** Anything else, with the server's message when there was one. */
	data class Other(val message: String?) : TranslationFailure

	companion object {
		fun of(err: Throwable): TranslationFailure = when ((err as? EnhancedApi.EnhancedApiException)?.status) {
			503 -> Disabled
			502 -> ServiceDown
			else -> Other(err.displayMessage())
		}
	}
}

/**
 * Translates reviews through [service] and keeps the results in memory for the session, per review text and
 * target language, so flipping between a translation and the original (or coming back to a review) costs nothing.
 *
 * [session] identifies the signed-in session (server, user and token); the cache is per session, so another account
 * never gets a translation made through someone else's server or access. A result is only cached if its request
 * finished in the session it started in, so a call still running across a switch can't fill the new session's cache.
 */
class ReviewTranslator(
	private val service: TranslationService,
	private val session: () -> Any?,
) {
	companion object {
		/** The plugin's limit on the text of one translate call. */
		const val MAX_TEXT_LENGTH = 20_000
		private const val CACHE_SIZE = 64
	}

	private val mutex = Mutex()
	private val cache = object : LinkedHashMap<CacheKey, String>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>?) = size > CACHE_SIZE
	}

	private data class CacheKey(val session: Any, val text: String, val target: String)

	suspend fun languages(): List<String> = service.languages().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

	/** [entry]'s text and headline in [target]. Throws what [service] throws; see [TranslationFailure.of]. */
	suspend fun translate(entry: ReviewEntry, target: String): TranslatedReview = coroutineScope {
		val content = prepareForTranslation(entry.content)
		val summary = entry.summary?.let(::prepareForTranslation)?.takeIf { it.text.isNotEmpty() }
		val translatedSummary = summary?.let { async { translate(it.text, target) } }
		val translatedContent = if (content.text.isEmpty()) "" else translate(content.text, target)
		TranslatedReview(translatedSummary?.await(), translatedContent, content.truncated)
	}

	private suspend fun translate(text: String, target: String): String {
		val session = session() ?: error("No signed-in user")
		val key = CacheKey(session, text, target)
		mutex.withLock { cache[key] }?.let { return it }
		val translated = service.translate(text, target).trim()
		mutex.withLock {
			// Other sessions' entries can't be hit again, and a call that outlived its session mustn't add one
			val current = session()
			cache.keys.removeAll { it.session != current }
			if (current == session) cache[key] = translated
		}
		return translated
	}
}

/** Review text ready to send: plain prose, at most [ReviewTranslator.MAX_TEXT_LENGTH] characters. */
data class PreparedText(val text: String, val truncated: Boolean)

private val bareUrl = Regex("""https?://\S+""")
private val markupLeftovers = Regex("""(\*\*|__|~~|`)""")
private val runsOfSpaces = Regex("""[ \t]+""")
private val spacesAroundNewlines = Regex(""" ?\n ?""")

/**
 * Like the web client: translators mangle URLs and Markdown, so bare links and leftover emphasis markers are
 * dropped (row and reader text is already cleaned of the rest). Text over the plugin's limit is cut at the last
 * paragraph (or line, or space) that fits.
 */
fun prepareForTranslation(text: String, limit: Int = ReviewTranslator.MAX_TEXT_LENGTH): PreparedText {
	val plain = text
		.replace(bareUrl, "")
		.replace(markupLeftovers, "")
		.replace(runsOfSpaces, " ")
		.replace(spacesAroundNewlines, "\n")
		.trim()
	if (plain.length <= limit) return PreparedText(plain, false)
	val head = plain.substring(0, limit)
	val cut = listOf("\n\n", "\n", " ").map { head.lastIndexOf(it) }.firstOrNull { it > limit / 2 } ?: limit
	return PreparedText(head.substring(0, cut).trim(), true)
}

/** LibreTranslate codes that aren't BCP 47 tags. */
private val libreTranslateTags = mapOf("zt" to "zh-Hant", "zh" to "zh-Hans", "pb" to "pt-BR")

/** A translation target as a language name in [locale] ("German", "Chinese (Traditional)"); the code when unknown. */
fun translationLanguageName(code: String, locale: Locale = Locale.getDefault()): String {
	val language = Locale.forLanguageTag(libreTranslateTags[code.lowercase(Locale.ROOT)] ?: code)
	// Unknown languages "display" as their own code
	if (language.language.isEmpty() || language.getDisplayLanguage(locale).equals(language.language, ignoreCase = true)) return code
	return language.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

/** The code in [languages] closest to [locale] (the language to preselect in the picker), never English. */
fun defaultTranslationTarget(languages: List<String>, locale: Locale = Locale.getDefault()): String? {
	val language = locale.language.lowercase(Locale.ROOT)
	if (language.isEmpty() || language == "en") return null
	val traditional = language == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO"))
	val candidates = buildList {
		if (traditional) addAll(listOf("zt", "zh-Hant", "zh-TW"))
		if (language == "pt" && locale.country == "BR") addAll(listOf("pb", "pt-BR"))
		if (locale.country.isNotEmpty()) add("$language-${locale.country}")
		if (language == "zh" && !traditional) add("zh-Hans")
		add(language)
	}
	return candidates.firstNotNullOfOrNull { candidate -> languages.firstOrNull { it.equals(candidate, ignoreCase = true) } }
}

/** The translation target the user picked, remembered per server and user. */
class TranslationTargetStore(context: Context, private val sessionRepository: SessionRepository) {
	private val prefs = context.getSharedPreferences("je_reviews", Context.MODE_PRIVATE)

	private fun currentKey(): String? = sessionRepository.currentSession.value?.let { key(it.serverId.toString(), it.userId.toString()) }

	fun get(): String? = currentKey()?.let { prefs.getString(it, null) }

	fun set(code: String) {
		val key = currentKey() ?: return
		prefs.edit().putString(key, code).apply()
	}

	internal companion object {
		fun key(serverId: String, userId: String) =
			"translate_target_${serverId.replace("-", "").lowercase(Locale.ROOT)}_${userId.replace("-", "").lowercase(Locale.ROOT)}"
	}
}

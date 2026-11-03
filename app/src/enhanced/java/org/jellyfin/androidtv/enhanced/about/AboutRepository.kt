package org.jellyfin.androidtv.enhanced.about

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.calendar.attempt
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/** `GET host-compat` (admin): whether the installed plugin build matches the server. */
@Serializable
data class HostCompat(
	val builtFor: String? = null,
	val hostTarget: String? = null,
	val hostVersion: String? = null,
	val pluginVersion: String? = null,
	val mismatch: Boolean = false,
	val stale: Boolean = false,
	val expectedAsset: String? = null,
	val message: String? = null,
)

/** `GET whats-new` (admin): plugin settings introduced since the admin last acknowledged them. Not a news feed. */
@Serializable
data class WhatsNew(
	val pluginVersion: String? = null,
	/** Setting name → version that introduced it. */
	val newSettings: Map<String, String> = emptyMap(),
)

/** The latest JE release on GitHub; only what the notes screen shows. */
@Serializable
data class PluginRelease(
	@SerialName("tag_name") val tagName: String? = null,
	val name: String? = null,
	val body: String? = null,
	@SerialName("published_at") val publishedAt: String? = null,
)

/** Version and diagnostics for the About screen. */
class AboutRepository(
	private val enhancedApi: EnhancedApi,
) {
	private companion object {
		const val RELEASES_URL = "https://api.github.com/repos/n00bcodr/Jellyfin-Enhanced/releases/latest"
		const val TIMEOUT_MS = 10_000
	}

	/** Fetched once per app run: GitHub rate-limits anonymous calls and the notes rarely change. */
	@Volatile
	private var release: PluginRelease? = null

	suspend fun serverVersion(): String? =
		((enhancedApi.get("/System/Info/Public") as? JsonObject)?.get("Version") as? JsonPrimitive)?.contentOrNull

	suspend fun hostCompat(): HostCompat = EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("host-compat"))

	/** Read only: never dismisses, that's server-wide state the admin clears from the plugin's settings page. */
	suspend fun whatsNew(): WhatsNew = EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("whats-new"))

	/** The cached release, if [latestRelease] already fetched it. */
	val cachedRelease: PluginRelease? get() = release

	/** The latest release notes from GitHub, or null on any failure (they're a nicety, not plugin data). */
	suspend fun latestRelease(): PluginRelease? = release ?: withContext(Dispatchers.IO) {
		attempt {
			val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
			try {
				connection.connectTimeout = TIMEOUT_MS
				connection.readTimeout = TIMEOUT_MS
				connection.setRequestProperty("Accept", "application/vnd.github+json")
				connection.setRequestProperty("User-Agent", "JellyfinEnhanced-AndroidTV")
				if (connection.responseCode !in 200..299) error("GitHub returned ${connection.responseCode}")
				val text = connection.inputStream.bufferedReader().use { it.readText() }
				EnhancedApi.json.decodeFromString<PluginRelease>(text)
			} finally {
				connection.disconnect()
			}
		}.onFailure { Timber.i(it, "Couldn't fetch JE release notes") }
			.getOrNull()
			?.also { release = it }
	}
}

object ReleaseNotes {
	private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
	private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
	private val emphasis = Regex("""(\*\*|__|`)""")
	private val html = Regex("""<[^>]+>""")

	/**
	 * GitHub-flavoured Markdown reduced to plain lines for a TV list: headings and bullets keep a marker, links keep
	 * their text, images and HTML tags go, blank lines are dropped.
	 */
	fun lines(markdown: String?): List<String> = markdown.orEmpty()
		.lineSequence()
		.map { it.replace(image, "").replace(link, "$1").replace(html, "").replace(emphasis, "").trim() }
		.map { line ->
			when {
				line.startsWith("#") -> line.trimStart('#').trim().uppercase()
				line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> "• " + line.substring(2).trim()
				else -> line
			}
		}
		.filter { it.isNotBlank() && it.trim('-', '*', '_', ' ').isNotEmpty() }
		.toList()
}

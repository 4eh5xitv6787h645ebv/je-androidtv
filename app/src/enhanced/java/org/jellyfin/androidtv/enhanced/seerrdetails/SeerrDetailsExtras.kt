package org.jellyfin.androidtv.enhanced.seerrdetails

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.seerr.SeerrDetails
import timber.log.Timber

/** A Seerr user as embedded in requests and issues (`requestedBy`, `createdBy`). */
@Serializable
data class SeerrUserRef(
	val id: Int? = null,
	val displayName: String? = null,
	val username: String? = null,
	val jellyfinUsername: String? = null,
	val plexUsername: String? = null,
) {
	/** The name Seerr shows. Never the e-mail address, which Seerr may fill in for local users. */
	val label: String?
		get() = listOf(displayName, jellyfinUsername, username, plexUsername).firstOrNull { !it.isNullOrBlank() }
}

/** Seerr `MediaRequestStatus`, which is a different enum from the media status. */
object SeerrRequestStatus {
	const val PENDING = 1
	const val APPROVED = 2
	const val DECLINED = 3
	const val FAILED = 4
	const val COMPLETED = 5

	/** Whether a request still holds its seasons, so they can't be requested again (as Seerr checks it). */
	fun blocksSeasons(status: Int?) = status != DECLINED && status != COMPLETED
}

/** One season of a request: its number and request status. */
@Serializable
data class SeerrRequestSeason(
	val seasonNumber: Int = 0,
	val status: Int? = null,
)

/** An entry of `mediaInfo.requests`. [id] is the Seerr request id, not a TMDB or media id. */
@Serializable
data class SeerrMediaRequest(
	val id: Int = 0,
	val status: Int? = null,
	val is4k: Boolean = false,
	val createdAt: String? = null,
	val requestedBy: SeerrUserRef? = null,
	val seasons: List<SeerrRequestSeason> = emptyList(),
)

/** A cast entry of `credits.cast`. [id] is the TMDB person id. */
@Serializable
data class SeerrCastMember(
	val id: Int? = null,
	val name: String? = null,
	val character: String? = null,
	val profilePath: String? = null,
	val order: Int? = null,
)

@Serializable
data class SeerrReleaseDate(
	val certification: String? = null,
	val type: Int? = null,
)

@Serializable
data class SeerrReleaseCountry(
	@SerialName("iso_3166_1") val country: String? = null,
	@SerialName("release_dates") val releaseDates: List<SeerrReleaseDate> = emptyList(),
)

@Serializable
data class SeerrContentRating(
	@SerialName("iso_3166_1") val country: String? = null,
	val rating: String? = null,
)

/**
 * The parts of `jellyseerr/{movie|tv}/{tmdbId}` the richer details screen uses beyond [SeerrDetails]. Each part is
 * decoded on its own (see [from]), so one malformed section only loses that section.
 */
data class SeerrDetailsExtras(
	val originalTitle: String? = null,
	val cast: List<SeerrCastMember> = emptyList(),
	val requests: List<SeerrMediaRequest> = emptyList(),
	/** Movies: TMDB release certifications per country. */
	val releases: List<SeerrReleaseCountry> = emptyList(),
	/** Shows: TMDB content ratings per country. */
	val contentRatings: List<SeerrContentRating> = emptyList(),
) {
	companion object {
		/** Release types in TMDB's order of relevance for a certification: theatrical, limited, digital, physical… */
		private val RELEASE_TYPE_ORDER = listOf(3, 2, 4, 5, 1, 6)

		fun from(json: JsonElement): SeerrDetailsExtras {
			val obj = json as? JsonObject ?: return SeerrDetailsExtras()

			fun <T> part(name: String, block: () -> T?): T? = try {
				block()
			} catch (err: IllegalArgumentException) {
				// SerializationException is an IllegalArgumentException
				Timber.w(err, "Ignoring malformed Seerr details part %s", name)
				null
			}

			val mediaInfo = obj["mediaInfo"] as? JsonObject
			return SeerrDetailsExtras(
				originalTitle = part("originalTitle") {
					listOf("originalTitle", "originalName").firstNotNullOfOrNull { key ->
						obj[key]?.let { EnhancedApi.json.decodeFromJsonElement<String?>(it) }?.takeIf { it.isNotBlank() }
					}
				},
				cast = part("credits") {
					(obj["credits"] as? JsonObject)?.get("cast")?.let { EnhancedApi.json.decodeFromJsonElement<List<SeerrCastMember>>(it) }
				}.orEmpty().filter { it.id != null && !it.name.isNullOrBlank() },
				requests = part("requests") {
					mediaInfo?.get("requests")?.let { EnhancedApi.json.decodeFromJsonElement<List<SeerrMediaRequest>>(it) }
				}.orEmpty(),
				releases = part("releases") {
					(obj["releases"] as? JsonObject)?.get("results")?.let { EnhancedApi.json.decodeFromJsonElement<List<SeerrReleaseCountry>>(it) }
				}.orEmpty(),
				contentRatings = part("contentRatings") {
					(obj["contentRatings"] as? JsonObject)?.get("results")?.let { EnhancedApi.json.decodeFromJsonElement<List<SeerrContentRating>>(it) }
				}.orEmpty(),
			)
		}
	}

	/** Standard (non-4K) requests, newest first. The app only makes standard requests. */
	val standardRequests: List<SeerrMediaRequest>
		get() = requests.filter { !it.is4k }.sortedByDescending { it.createdAt.orEmpty() }

	/**
	 * The age rating for the first of [countries] (ISO 3166-1 codes, most preferred first) that has one. For movies
	 * the theatrical certification is preferred over other release types.
	 */
	fun certification(countries: List<String>): String? {
		for (country in countries) {
			contentRatings.firstOrNull { it.country.equals(country, ignoreCase = true) && !it.rating.isNullOrBlank() }
				?.let { return it.rating!!.trim() }

			val dates = releases.firstOrNull { it.country.equals(country, ignoreCase = true) }?.releaseDates.orEmpty()
				.filter { !it.certification.isNullOrBlank() }
			dates.minByOrNull { RELEASE_TYPE_ORDER.indexOf(it.type).let { index -> if (index < 0) Int.MAX_VALUE else index } }
				?.let { return it.certification!!.trim() }
		}
		return null
	}

	/** The original title when it differs from [displayTitle] (ignoring case), so it's worth showing. */
	fun originalTitleIfDifferent(displayTitle: String): String? =
		originalTitle?.takeIf { !it.equals(displayTitle.trim(), ignoreCase = true) }
}

/** A Seerr details response decoded both as the shared [SeerrDetails] and as [SeerrDetailsExtras]. */
class SeerrDetailsBundle(
	val details: SeerrDetails,
	val extras: SeerrDetailsExtras,
)

package org.jellyfin.androidtv.enhanced.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Data for the Discover screen: row previews of the plugin's Seerr feeds, the genre tiles, and the "Pick something
 * for me" random library pick. Mirrors `js/jellyseerr/recommendations/recommendations-data.js` and
 * `js/enhanced/features-random-button.js`.
 */
class DiscoverRepository(
	private val enhancedApi: EnhancedApi,
	private val enhancedRepository: EnhancedRepository,
	private val settingsRepository: EnhancedSettingsRepository,
	private val seerrRepository: SeerrRepository,
	private val api: ApiClient,
	private val spoilerGuardRepository: SpoilerGuardRepository,
) {
	companion object {
		const val ROW_PREVIEW_SIZE = 20

		// Library exclusion can empty most of a page; read a couple more before settling for a short row
		private const val ROW_MIN_RESULTS = 10
		private const val ROW_MAX_PAGES = 3
		private const val RANDOM_CANDIDATES = 100
	}

	/** The first cards of a row and whether "View all" has more to show. */
	class RowPreview(val results: List<SeerrResult>, val hasMore: Boolean)

	/**
	 * The first page(s) of [source], filtered with discovery semantics (hidden titles, library exclusion,
	 * blocklist). Throws when the first page can't be loaded, so the row can offer Retry.
	 */
	suspend fun rowPreview(source: SeerrSource): RowPreview {
		val cursor = SeerrRepository.FeedCursor(seerrRepository.feedsFor(source))
		val results = mutableListOf<SeerrResult>()
		var pages = 0
		while (cursor.hasMore && pages < ROW_MAX_PAGES && results.size < ROW_MIN_RESULTS) {
			val batch = try {
				seerrRepository.loadNext(cursor)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				// A later page failing just leaves a shorter preview
				if (pages == 0) throw err
				Timber.w(err, "Discover row %s stopped after %d page(s)", source.title, pages)
				break
			}
			pages++
			val known = results.mapTo(HashSet()) { it.dedupeKey }
			results += batch.results.filter { it.dedupeKey !in known }
		}
		return RowPreview(results.take(ROW_PREVIEW_SIZE), cursor.hasMore || results.size > ROW_PREVIEW_SIZE)
	}

	/** The genre tiles of [mediaType] (`movie` or `tv`). */
	suspend fun genreSlider(mediaType: String): List<DiscoverGenreSlide> =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("jellyseerr/discover/genreslider/$mediaType"))

	/**
	 * Whether genre tiles may show their backdrops. The slider's backdrops aren't tied to a title we could check, so
	 * a protected title's artwork could turn up on a tile: while the user has anything under Spoiler Guard (or it
	 * can't be checked) the tiles show the genre name alone.
	 */
	suspend fun genreBackdropsAllowed(): Boolean {
		if (!enhancedRepository.current.spoilerGuard) return true
		val state = try {
			spoilerGuardRepository.getState()
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load Spoiler Guard state")
			return false
		}
		return state.series.isEmpty() && state.movies.isEmpty() && state.collections.isEmpty() && state.pendingTmdb.isEmpty()
	}

	// Random pick

	/** Why there was nothing to pick. */
	enum class RandomFailure { NO_TYPES, NOTHING, NOTHING_UNWATCHED }

	class RandomPickException(val reason: RandomFailure) : Exception("Random pick failed: $reason")

	/** Why the pick came from the whole library although the user pinned a playlist or collection. */
	sealed interface RandomNotice {
		data object SourceMissing : RandomNotice
		data class SourceEmpty(val name: String) : RandomNotice
	}

	class RandomPick(val itemId: UUID, val notice: RandomNotice?)

	/** The user's effective random-pick settings: their own, else the server's defaults. */
	suspend fun randomSettings(): RandomPickSettings {
		val state = enhancedRepository.ensureLoaded()
		val defaults = try {
			EnhancedApi.json.decodeFromJsonElement<RandomPickDefaults>(enhancedApi.get("public-config"))
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load random pick defaults")
			RandomPickDefaults()
		}
		// RandomSourceId isn't part of the shared settings model
		val sourceId = try {
			settingsRepository.read(EnhancedSettingsRepository.SETTINGS).string("RandomSourceId")
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to read the random source")
			null
		}
		return RandomPickSettings.resolve(state.userSettings, defaults, sourceId)
	}

	/**
	 * A random movie or show from the library (or from the pinned playlist/collection, falling back to the library
	 * when that has nothing eligible), following the user's settings. Throws [RandomPickException] when nothing
	 * matches, or the API error when the server can't be asked.
	 */
	suspend fun randomPick(): RandomPick = withContext(Dispatchers.IO) {
		val settings = randomSettings()
		if (!settings.includeMovies && !settings.includeShows) throw RandomPickException(RandomFailure.NO_TYPES)

		var notice: RandomNotice? = null
		if (settings.sourceId != null) {
			val container = settings.sourceId.toUUIDOrNull()?.let { id ->
				try {
					api.userLibraryApi.getItem(itemId = id).content
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Random source %s can't be read", id)
					null
				}
			}?.takeIf { it.type == BaseItemKind.PLAYLIST || it.type == BaseItemKind.BOX_SET }

			if (container == null) {
				notice = RandomNotice.SourceMissing
			} else {
				candidates(settings, container.id).randomOrNull()?.let { return@withContext RandomPick(it, null) }
				notice = RandomNotice.SourceEmpty(container.name.orEmpty())
			}
		}

		val picked = candidates(settings, null).randomOrNull()
			?: throw RandomPickException(if (settings.unwatchedOnly) RandomFailure.NOTHING_UNWATCHED else RandomFailure.NOTHING)
		RandomPick(picked, notice)
	}

	private suspend fun candidates(settings: RandomPickSettings, parentId: UUID?): List<UUID> {
		val kinds = buildSet {
			if (settings.includeMovies) add(BaseItemKind.MOVIE)
			if (settings.includeShows) {
				add(BaseItemKind.SERIES)
				// Inside a playlist episodes count as shows, so an episode playlist isn't empty
				if (parentId != null) add(BaseItemKind.EPISODE)
			}
		}
		val response = api.itemsApi.getItems(
			parentId = parentId,
			recursive = parentId == null,
			includeItemTypes = kinds,
			sortBy = setOf(ItemSortBy.RANDOM),
			limit = RANDOM_CANDIDATES,
			filters = if (settings.unwatchedOnly) setOf(ItemFilter.IS_UNPLAYED) else null,
			enableUserData = true,
		).content
		return response.items
			.filter { item ->
				!settings.unwatchedOnly || RandomPickSettings.isUnwatched(
					isSeries = item.type == BaseItemKind.SERIES,
					played = item.userData?.played,
					unplayedItemCount = item.userData?.unplayedItemCount,
				)
			}
			.map { it.id }
	}
}

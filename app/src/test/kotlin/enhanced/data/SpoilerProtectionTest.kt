package org.jellyfin.androidtv.enhanced.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

private val SERIES_ID: UUID = UUID.fromString("326d7084-0827-00b2-20b6-83fc0dc0ae78")
private val MOVIE_ID: UUID = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")
private val COLLECTION_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

private val ON = EnhancedPublicConfig(spoilerBlurEnabled = true)

private fun snapshot(
	state: SpoilerGuardState?,
	config: EnhancedPublicConfig = ON,
	members: Set<String>? = emptySet(),
) = SpoilerSnapshot(enabled = true, state = state, policy = SpoilerPolicy.of(config, state?.prefs), collectionMovieIds = members)

private fun item(type: BaseItemKind, id: UUID = UUID.randomUUID(), seriesId: UUID? = null, played: Boolean? = null) = BaseItemDto(
	id = id,
	type = type,
	seriesId = seriesId,
	userData = played?.let {
		UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = false, played = it, key = "", itemId = id)
	},
)

class SpoilerProtectionTest : FunSpec({
	// Admin flag × user pref: the admin flag is the cap, a user false opts out, null or true follow the admin

	test("admin on, no user override: category hidden") {
		SpoilerPolicy.of(ON, SpoilerGuardPrefs()).hideReviews shouldBe true
	}

	test("admin on, user opts out: category shown") {
		SpoilerPolicy.of(ON, SpoilerGuardPrefs(hideReviews = false)).hideReviews shouldBe false
	}

	test("admin on, user re-affirms: category hidden") {
		SpoilerPolicy.of(ON, SpoilerGuardPrefs(hideAirDate = true)).hideAirDate shouldBe true
	}

	test("admin off: a user true never re-enables the category") {
		SpoilerPolicy.of(ON.copy(spoilerStripRatings = false), SpoilerGuardPrefs(hideRatings = true)).hideRatings shouldBe false
		SpoilerPolicy.of(ON.copy(spoilerStripRatings = false), SpoilerGuardPrefs()).hideRatings shouldBe false
	}

	test("unknown prefs follow the admin policy") {
		SpoilerPolicy.of(ON, null) shouldBe SpoilerPolicy.of(ON, SpoilerGuardPrefs())
		SpoilerPolicy.of(ON, null).hideTitles shouldBe true
	}

	test("categories are independent: keeping ratings doesn't release reviews or air dates") {
		val policy = SpoilerPolicy.of(ON, SpoilerGuardPrefs(hideRatings = false, hideEpisodeDescriptions = false))
		policy.hideRatings shouldBe false
		policy.hideOverview shouldBe false
		policy.hideReviews shouldBe true
		policy.hideAirDate shouldBe true
		policy.hideSeriesOverview shouldBe true
	}

	// × advanced mode: reveals need the admin master and no user opt-out, and never relax client-side protection

	test("advanced mode needs the admin master switch") {
		SpoilerPolicy.of(ON, SpoilerGuardPrefs(useAdvancedCategories = true)).advancedReveals shouldBe false
		SpoilerPolicy.of(ON.copy(spoilerAdvancedMode = true), SpoilerGuardPrefs()).advancedReveals shouldBe true
	}

	test("a user opting out of advanced categories restores the uniform strip") {
		val advanced = ON.copy(spoilerAdvancedMode = true)
		SpoilerPolicy.of(advanced, SpoilerGuardPrefs(useAdvancedCategories = false)).advancedReveals shouldBe false
	}

	test("advanced mode never relaxes what the client withholds") {
		val advanced = ON.copy(spoilerAdvancedMode = true)
		listOf(null, true, false).forEach { useAdvanced ->
			val prefs = SpoilerGuardPrefs(useAdvancedCategories = useAdvanced, hideReviews = false)
			SpoilerPolicy.of(advanced, prefs).copy(advancedReveals = false) shouldBe
				SpoilerPolicy.of(ON, prefs).copy(advancedReveals = false)
		}
	}

	test("admin off with advanced on still leaves the category off") {
		val policy = SpoilerPolicy.of(ON.copy(spoilerAdvancedMode = true, spoilerStripPremiereDate = false), SpoilerGuardPrefs())
		policy.hideAirDate shouldBe false
		policy.advancedReveals shouldBe true
	}

	// Membership

	test("episodes and seasons follow their series; series use the series description policy") {
		val state = SpoilerGuardState(
			series = mapOf(SpoilerGuardRepository.key(SERIES_ID) to JsonPrimitive(true)),
			prefs = SpoilerGuardPrefs(hideSeriesDescriptions = false),
		)
		val snapshot = snapshot(state)
		snapshot.forItem(item(BaseItemKind.SERIES, id = SERIES_ID)).hideOverview shouldBe false
		val episode = snapshot.forItem(item(BaseItemKind.EPISODE, seriesId = SERIES_ID))
		episode.guarded shouldBe true
		episode.hideOverview shouldBe true
		episode.hideTitles shouldBe true
		snapshot.forItem(item(BaseItemKind.SEASON, seriesId = SERIES_ID)).hideReviews shouldBe true
		snapshot.forItem(item(BaseItemKind.EPISODE, seriesId = UUID.randomUUID())) shouldBe Protection.NONE
		// Played episodes pass through on the server; reviews stay withheld like on the web
		val played = snapshot.forItem(item(BaseItemKind.EPISODE, seriesId = SERIES_ID, played = true))
		played.hideRatings shouldBe false
		played.hideTitles shouldBe false
		played.hideReviews shouldBe true
	}

	test("protection can't be ruled out: fail closed") {
		snapshot(null).forItem(item(BaseItemKind.SERIES)) shouldBe Protection.FULL
		snapshot(null).forItem(item(BaseItemKind.MOVIE)) shouldBe Protection.FULL
		// An episode without its series id
		snapshot(SpoilerGuardState()).forItem(item(BaseItemKind.EPISODE)) shouldBe Protection.FULL
		snapshot(SpoilerGuardState()).complete shouldBe true
		snapshot(null).complete shouldBe false
	}

	test("movies are guarded directly or through a guarded collection") {
		val direct = SpoilerGuardState(movies = mapOf(SpoilerGuardRepository.key(MOVIE_ID) to JsonPrimitive(true)))
		snapshot(direct).forItem(item(BaseItemKind.MOVIE, id = MOVIE_ID)).guarded shouldBe true
		snapshot(direct).forItem(item(BaseItemKind.MOVIE)) shouldBe Protection.NONE

		val viaCollection = SpoilerGuardState(collections = mapOf(SpoilerGuardRepository.key(COLLECTION_ID) to JsonPrimitive(true)))
		snapshot(viaCollection, members = setOf(SpoilerGuardRepository.key(MOVIE_ID)))
			.forItem(item(BaseItemKind.MOVIE, id = MOVIE_ID)).guarded shouldBe true
		snapshot(viaCollection, members = emptySet()).forItem(item(BaseItemKind.MOVIE, id = MOVIE_ID)) shouldBe Protection.NONE
		// The collection's movies couldn't be listed
		snapshot(viaCollection, members = null).forItem(item(BaseItemKind.MOVIE, id = MOVIE_ID)) shouldBe Protection.FULL
		snapshot(viaCollection).forItem(item(BaseItemKind.BOX_SET, id = COLLECTION_ID)).guarded shouldBe true
	}

	test("disabled Spoiler Guard protects nothing, people are never guarded") {
		SpoilerSnapshot.DISABLED.forItem(item(BaseItemKind.EPISODE)) shouldBe Protection.NONE
		SpoilerSnapshot.DISABLED.forTitle(isMovie = true, libraryId = null, tmdbId = 5) shouldBe Protection.NONE
		snapshot(SpoilerGuardState()).forItem(item(BaseItemKind.PERSON)) shouldBe Protection.NONE
	}
})

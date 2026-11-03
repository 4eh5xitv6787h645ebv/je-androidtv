package org.jellyfin.androidtv.enhanced.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.bookmarks.Bookmark
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkEntry
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.EnhancedUserSettings
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale
import java.util.UUID

class PlaybackLogicTest : FunSpec({
	val itemId = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001")

	test("rating colours follow the web stylesheet's groups") {
		PlaybackRatingColors.bucket("G") shouldBe RatingBucket.GENERAL
		PlaybackRatingColors.bucket("PG") shouldBe RatingBucket.PARENTAL_GUIDANCE
		PlaybackRatingColors.bucket("PG-13") shouldBe RatingBucket.PARENTAL_CAUTION
		PlaybackRatingColors.bucket("TV-14") shouldBe RatingBucket.PARENTAL_CAUTION
		PlaybackRatingColors.bucket("TV-MA") shouldBe RatingBucket.RESTRICTED
		PlaybackRatingColors.bucket("NC-17") shouldBe RatingBucket.ADULTS_ONLY
		PlaybackRatingColors.bucket("GB-15") shouldBe RatingBucket.PARENTAL_CAUTION
		PlaybackRatingColors.bucket("AU-MA 15+") shouldBe RatingBucket.MATURE
		PlaybackRatingColors.bucket("R") shouldBe RatingBucket.RESTRICTED
		PlaybackRatingColors.bucket("AU-M") shouldBe RatingBucket.EXEMPT
		PlaybackRatingColors.bucket("AU-R18+") shouldBe RatingBucket.REFUSED
		PlaybackRatingColors.bucket("IN-S") shouldBe RatingBucket.SPECIAL
	}

	test("rating normalization mirrors colored-ratings.js") {
		PlaybackRatingColors.bucket("FSK 12") shouldBe RatingBucket.FSK_12
		PlaybackRatingColors.bucket("de-16") shouldBe RatingBucket.FSK_16
		PlaybackRatingColors.bucket("Not Rated") shouldBe RatingBucket.UNRATED
		PlaybackRatingColors.bucket(" NR ") shouldBe RatingBucket.UNRATED
		PlaybackRatingColors.displayText("FSK16") shouldBe "FSK-16"
		PlaybackRatingColors.displayText(" PG-13 ") shouldBe "PG-13"
	}

	test("unknown ratings and case mismatches fall back to the default, as CSS attribute selectors do") {
		PlaybackRatingColors.bucket("XYZ-99") shouldBe RatingBucket.DEFAULT
		PlaybackRatingColors.bucket("pg-13") shouldBe RatingBucket.DEFAULT
	}

	test("features are all off without the plugin") {
		PlaybackFeatures.resolve(EnhancedState(available = false), PlaybackPublicConfig(pauseScreenEnabled = true)) shouldBe PlaybackFeatures.NONE
		PlaybackFeatures.NONE.any shouldBe false
	}

	test("user settings win over server defaults, which win over the web's built-in defaults") {
		val state = EnhancedState(available = true)
		val defaults = PlaybackFeatures.resolve(state, PlaybackPublicConfig())
		defaults.pauseScreen shouldBe true
		defaults.pauseDelaySeconds shouldBe 5
		defaults.ratingInPlayer shouldBe true
		defaults.ratingBadge shouldBe false
		defaults.bookmarks shouldBe false

		val server = PlaybackPublicConfig(pauseScreenEnabled = false, pauseScreenDelaySeconds = 10, showRatingInPlayer = false)
		PlaybackFeatures.resolve(state, server).let {
			it.pauseScreen shouldBe false
			it.pauseDelaySeconds shouldBe 10
			it.ratingInPlayer shouldBe false
		}

		val user = state.copy(userSettings = EnhancedUserSettings(pauseScreenEnabled = true, pauseScreenDelaySeconds = 30, showRatingInPlayer = true))
		PlaybackFeatures.resolve(user, server).let {
			it.pauseScreen shouldBe true
			it.pauseDelaySeconds shouldBe 30
			it.ratingInPlayer shouldBe true
		}
	}

	test("public gates and the delay range") {
		val state = EnhancedState(
			available = true,
			config = EnhancedPublicConfig(showPlaybackRatingBadge = true, bookmarksEnabled = true),
			userSettings = EnhancedUserSettings(pauseScreenDelaySeconds = 0),
		)
		PlaybackFeatures.resolve(state, PlaybackPublicConfig(coloredRatingsEnabled = true)).let {
			it.ratingBadge shouldBe true
			it.bookmarks shouldBe true
			it.coloredRatings shouldBe true
			it.pauseDelaySeconds shouldBe 1
		}
		PlaybackFeatures.resolve(state.copy(userSettings = EnhancedUserSettings(pauseScreenDelaySeconds = 600)), PlaybackPublicConfig())
			.pauseDelaySeconds shouldBe 60
	}

	test("public-config extras decode from the plugin's PascalCase keys") {
		val json = EnhancedApi.json.parseToJsonElement(
			"""{"PauseScreenEnabled":false,"PauseScreenDelaySeconds":15,"ShowRatingInPlayer":true,"ColoredRatingsEnabled":true,"Other":1}"""
		).jsonObject
		EnhancedApi.json.decodeFromJsonElement<PlaybackPublicConfig>(json) shouldBe
			PlaybackPublicConfig(pauseScreenEnabled = false, pauseScreenDelaySeconds = 15, showRatingInPlayer = true, coloredRatingsEnabled = true)
		EnhancedApi.json.decodeFromJsonElement<PlaybackPublicConfig>(EnhancedApi.json.parseToJsonElement("{}")) shouldBe PlaybackPublicConfig()
	}

	test("episode line, title and runtime") {
		val episode = BaseItemDto(
			id = itemId,
			type = BaseItemKind.EPISODE,
			name = "Pilot",
			seriesName = "Final Space",
			parentIndexNumber = 1,
			indexNumber = 2,
			runTimeTicks = 6_720_000_000L * 10,
		)
		PlaybackItemInfo.episodeLine(episode) shouldBe "Final Space · S1E2 · Pilot"
		PlaybackItemInfo.title(episode) shouldBe "Final Space"
		PlaybackItemInfo.runtime(episode.runTimeTicks) shouldBe "1h 52m"
		PlaybackItemInfo.runtime(600_000_000L * 48) shouldBe "48m"
		PlaybackItemInfo.runtime(null) shouldBe null

		// Spoiler Guard replaces or strips the episode title server side; nothing is invented here
		PlaybackItemInfo.episodeLine(episode.copy(name = null)) shouldBe "Final Space · S1E2"
		val movie = BaseItemDto(id = itemId, type = BaseItemKind.MOVIE, name = "Avatar")
		PlaybackItemInfo.episodeLine(movie) shouldBe null
		PlaybackItemInfo.title(movie) shouldBe "Avatar"
	}

	test("clock, progress and ratings") {
		PlaybackItemInfo.clock(0) shouldBe "0:00"
		PlaybackItemInfo.clock(65_000) shouldBe "1:05"
		PlaybackItemInfo.clock(3_725_000) shouldBe "1:02:05"
		PlaybackItemInfo.progress(30_000, 120_000) shouldBe 0.25f
		PlaybackItemInfo.progress(30_000, 0) shouldBe 0f

		val item = BaseItemDto(id = itemId, type = BaseItemKind.MOVIE, officialRating = " PG-13 ", communityRating = 8.246f, criticRating = 94.4f)
		PlaybackItemInfo.officialRating(item) shouldBe "PG-13"
		PlaybackItemInfo.officialRating(item.copy(officialRating = "")) shouldBe null
		PlaybackItemInfo.criticPercent(item) shouldBe 94
		PlaybackItemInfo.criticPercent(item.copy(criticRating = 140f)) shouldBe 100
		PlaybackItemInfo.criticPercent(item.copy(criticRating = null)) shouldBe null
		PlaybackItemInfo.criticFresh(60) shouldBe true
		PlaybackItemInfo.criticFresh(59) shouldBe false
		val previous = Locale.getDefault()
		try {
			Locale.setDefault(Locale.US)
			PlaybackItemInfo.communityRating(item) shouldBe "8.2"
		} finally {
			Locale.setDefault(previous)
		}
		PlaybackItemInfo.badgeGenres(item.copy(genres = listOf("Action", " ", "Drama", "Comedy", "Horror"))) shouldBe listOf("Action", "Drama", "Comedy")
	}

	test("bookmark positions use seconds, not ticks") {
		PlaybackBookmarkLogic.seconds(1_200_500) shouldBe 1200.5
		PlaybackBookmarkLogic.seconds(-5) shouldBe 0.0
		PlaybackBookmarkLogic.positionMs(1200.5) shouldBe 1_200_500L
	}

	test("the item's bookmarks are matched without dashes or case and listed in playback order") {
		fun entry(id: String, item: String, seconds: Double, label: String? = null) =
			BookmarkEntry(id, Bookmark(itemId = item, timestampSeconds = seconds, label = label))
		val key = itemId.toString().replace("-", "")
		val entries = listOf(
			entry("Bm_3", key, 900.0),
			entry("Bm_1", key.uppercase(), 60.0),
			entry("Bm_2", itemId.toString(), 300.0),
			entry("Bm_x", "ffffffffffffffffffffffffffffffff", 10.0),
		)
		PlaybackBookmarkLogic.forItem(entries, itemId).map { it.bookmarkId } shouldBe listOf("Bm_1", "Bm_2", "Bm_3")
	}

	test("bookmark menu labels") {
		PlaybackBookmarkLogic.menuLabel(Bookmark(timestampSeconds = 754.0, label = "Big reveal")) shouldBe "Big reveal · 12:34"
		PlaybackBookmarkLogic.menuLabel(Bookmark(timestampSeconds = 754.0, label = "12:34")) shouldBe "12:34"
		PlaybackBookmarkLogic.menuLabel(Bookmark(timestampSeconds = 3_725.0, label = " ")) shouldBe "1:02:05"
	}
})

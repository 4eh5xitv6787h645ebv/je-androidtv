package org.jellyfin.androidtv.enhanced.bookmarks

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardPrefs
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.data.SpoilerPolicy
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import java.util.UUID

class BookmarkLogicTest : FunSpec({
	// As saved by the plugin's bookmark.json/add route
	val json = """
		{"Bookmarks":{
		 "Bm_1793665876519_250ddeaf0":{"ItemId":"fd8067ea9ef35106714e66075a2df7ae","TmdbId":"295778","TvdbId":"","MediaType":"tv",
		  "Name":"My First Time in a While","Timestamp":754.5,"Label":"Great bit","CreatedAt":"2026-11-03T00:31:16.5192107Z",
		  "UpdatedAt":"2026-11-03T00:31:16.5192107Z","SyncedFrom":"","SeasonNumber":2,"EpisodeNumber":1},
		 "Bm_1793665876589_ce17c23f2":{"ItemId":"00000000000000000000000000000001","TmdbId":"","TvdbId":"","MediaType":"movie",
		  "Name":"Gone movie","Timestamp":61,"Label":"","CreatedAt":"2026-11-03T00:31:16.5893750Z","UpdatedAt":"2026-11-03T00:31:16.5893750Z"},
		 "Bm_old":{"ItemId":"abc","Timestamp":5,"CreatedAt":"2025-01-01T00:00:00Z"},
		 "Bm_undated":{"ItemId":"def","Timestamp":1}
		}}
	""".trimIndent()

	test("decodes the document and sorts newest first, undated last") {
		val entries = BookmarkLogic.sorted(EnhancedApi.json.decodeFromString<BookmarkDocument>(json))
		entries.map { it.bookmarkId } shouldContainExactly listOf(
			"Bm_1793665876589_ce17c23f2",
			"Bm_1793665876519_250ddeaf0",
			"Bm_old",
			"Bm_undated",
		)
		val episode = entries[1].bookmark
		episode.timestampSeconds shouldBe 754.5
		episode.tmdbId shouldBe "295778"
		episode.seasonNumber shouldBe 2
		episode.label shouldBe "Great bit"
	}

	test("bookmark seconds and Jellyfin ticks convert both ways") {
		BookmarkLogic.ticks(754.5) shouldBe 7_545_000_000L
		BookmarkLogic.ticks(-3.0) shouldBe 0L
		BookmarkLogic.seconds(12_948_770_000L) shouldBe 1294.877
	}

	test("timestamps format as m:ss or h:mm:ss") {
		BookmarkLogic.formatTimestamp(0.0) shouldBe "0:00"
		BookmarkLogic.formatTimestamp(754.5) shouldBe "12:34"
		BookmarkLogic.formatTimestamp(3_725.0) shouldBe "1:02:05"
	}

	test("episode names include show and episode numbers") {
		BookmarkLogic.displayName("My First Time in a While", "Scrubs (2026)", 2, 1) shouldBe "Scrubs (2026) · S2E1 · My First Time in a While"
		BookmarkLogic.displayName("Backrooms", null, null, null) shouldBe "Backrooms"
	}

	test("saved episode names are never shown once protection can't be established") {
		val episode = Bookmark(itemId = "fd8067ea9ef35106714e66075a2df7ae", mediaType = "tv", name = "My First Time in a While", seasonNumber = 2, episodeNumber = 1)
		val movie = Bookmark(itemId = "00000000000000000000000000000001", mediaType = "movie", name = "Gone movie")
		val seriesId = "326d7084082700b220b683fc0dc0ae78"
		fun snapshot(state: SpoilerGuardState?, prefs: SpoilerGuardPrefs = SpoilerGuardPrefs()) = SpoilerSnapshot(
			enabled = true,
			state = state,
			policy = SpoilerPolicy.of(EnhancedPublicConfig(spoilerBlurEnabled = true), prefs),
			collectionMovieIds = emptySet(),
		)
		val none = snapshot(SpoilerGuardState())
		val guarded = snapshot(SpoilerGuardState(series = mapOf(seriesId to JsonPrimitive(true))))

		// Lookup failed or item gone: neutral numbers while Spoiler Guard is on, even if this series isn't guarded
		BookmarkLogic.name(episode, null, none) shouldBe BookmarkName(null, NeutralEpisode(null, 2, 1))
		BookmarkLogic.name(episode, null, snapshot(null)) shouldBe BookmarkName(null, NeutralEpisode(null, 2, 1))
		BookmarkLogic.name(episode, null, SpoilerSnapshot.DISABLED) shouldBe BookmarkName("My First Time in a While")
		// Movie names are never hidden
		BookmarkLogic.name(movie, null, guarded) shouldBe BookmarkName("Gone movie")

		val resolved = BookmarkedItem(
			id = UUID.randomUUID(),
			displayName = "Scrubs · S2E1 · My First Time in a While",
			posterUrl = null,
			isEpisode = true,
			seriesId = seriesId,
			seriesName = "Scrubs",
			seasonNumber = 2,
			episodeNumber = 1,
		)
		BookmarkLogic.name(episode, resolved, none) shouldBe BookmarkName(resolved.displayName)
		BookmarkLogic.name(episode, resolved, guarded) shouldBe BookmarkName(null, NeutralEpisode("Scrubs", 2, 1))
		// Watched, or the user keeps real titles
		BookmarkLogic.name(episode, resolved.copy(played = true), guarded) shouldBe BookmarkName(resolved.displayName)
		val titlesKept = snapshot(guarded.state, SpoilerGuardPrefs(replaceEpisodeTitles = false))
		BookmarkLogic.name(episode, resolved, titlesKept) shouldBe BookmarkName(resolved.displayName)
	}

	test("lookups go top-down without repeats, and an unknown policy withholds resolved episode titles") {
		val entries = BookmarkLogic.sorted(EnhancedApi.json.decodeFromString<BookmarkDocument>(json))
		val episodeId = UUID.fromString("fd8067ea-9ef3-5106-714e-66075a2df7ae")
		val goneId = UUID.fromString("00000000-0000-0000-0000-000000000001")
		// "abc" and "def" aren't ids: nothing to look up
		BookmarkLogic.lookupBatches(entries, 1) shouldBe listOf(listOf(goneId), listOf(episodeId))
		BookmarkLogic.lookupBatches(entries + entries, 50) shouldBe listOf(listOf(goneId, episodeId))

		val episode = entries[1].bookmark
		val resolved = BookmarkedItem(
			id = episodeId,
			displayName = "Scrubs · S2E1 · My First Time in a While",
			posterUrl = "poster",
			isEpisode = true,
			seriesId = "326d7084082700b220b683fc0dc0ae78",
			seriesName = "Scrubs",
			seasonNumber = 2,
			episodeNumber = 1,
			played = true,
		)
		BookmarkLogic.name(episode, resolved, SpoilerSnapshot.UNKNOWN) shouldBe BookmarkName(null, NeutralEpisode("Scrubs", 2, 1))
	}
})

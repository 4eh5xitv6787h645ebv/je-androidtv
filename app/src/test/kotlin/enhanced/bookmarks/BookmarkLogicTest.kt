package org.jellyfin.androidtv.enhanced.bookmarks

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

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
})

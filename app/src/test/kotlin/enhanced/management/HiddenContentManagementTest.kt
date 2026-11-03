package org.jellyfin.androidtv.enhanced.management

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.HomeSurface
import org.jellyfin.androidtv.enhanced.management.hidden.HiddenEntries
import org.jellyfin.androidtv.enhanced.management.hidden.HiddenScopeFilter
import org.jellyfin.androidtv.enhanced.management.hidden.HiddenTypeFilter
import org.jellyfin.androidtv.enhanced.management.hidden.UnhidePlan
import java.util.UUID

class HiddenContentManagementTest : FunSpec({
	// Two entries as the plugin's home-row routes write them (dashed keys), plus a global hide, a TMDB-only Seerr
	// hide, a combined home entry and one with a scope from a future plugin version
	val document = EnhancedApi.json.parseToJsonElement(
		"""
		{"Items":{
		  "3edb510e-52d3-dd02-aac4-211c045310d4":{"ItemId":"3edb510e-52d3-dd02-aac4-211c045310d4","Name":"The Golden Rule","Type":"Episode","TmdbId":"","HiddenAt":"2026-10-22T04:00:48.2918334Z","PosterPath":"","SeriesId":"637ea41b-08d2-f79d-3a12-5196a14acd9e","SeriesName":"Fallout","SeasonNumber":2,"EpisodeNumber":2,"HideScope":"continuewatching"},
		  "549eb3fe-f011-4c72-24c1-506090423391":{"ItemId":"549eb3fe-f011-4c72-24c1-506090423391","Name":"The Innovator","Type":"Episode","TmdbId":"","HiddenAt":"2026-10-22T04:01:26.1037087Z","PosterPath":"","SeriesId":"637ea41b-08d2-f79d-3a12-5196a14acd9e","SeriesName":"Fallout","SeasonNumber":2,"EpisodeNumber":1,"HideScope":"nextup"},
		  "7907a56192c41bb6885566e991dd27d3":{"ItemId":"7907a56192c41bb6885566e991dd27d3","Name":"Alphablocks","Type":"Series","TmdbId":"32037","HiddenAt":"2026-11-03T00:00:00Z","PosterPath":"","SeriesId":"","SeriesName":"","SeasonNumber":null,"EpisodeNumber":null,"HideScope":"global"},
		  "tmdb-603":{"ItemId":"","Name":"The Matrix","Type":"Movie","TmdbId":"603","HiddenAt":"2026-11-01T00:00:00Z","PosterPath":"/p.jpg","SeriesId":"","SeriesName":"","SeasonNumber":null,"EpisodeNumber":null,"HideScope":"global","Extra":"kept"},
		  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa":{"ItemId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","Name":"Combined","Type":"Movie","HiddenAt":"2026-10-01T00:00:00Z","HideScope":"homesections"},
		  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb":{"ItemId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Name":"Future","Type":"Movie","HideScope":"Sidebar"},
		  "broken":"not an object"
		 },
		 "Settings":{"Enabled":true,"FilterSearch":true,"ShowButtonJellyseerr":false},
		 "Unknown":{"keep":true}}
		""".trimIndent()
	).jsonObject

	test("entries parse newest first, keep their stored keys and skip malformed values") {
		val entries = HiddenEntries.parse(document)
		entries.map { it.key }.take(4) shouldContainExactly listOf(
			"7907a56192c41bb6885566e991dd27d3",
			"tmdb-603",
			"549eb3fe-f011-4c72-24c1-506090423391",
			"3edb510e-52d3-dd02-aac4-211c045310d4",
		)
		entries.size shouldBe 6
		val matrix = entries.first { it.key == "tmdb-603" }
		matrix.itemId.shouldBeNull()
		matrix.tmdbId shouldBe 603
		entries.first { it.key.startsWith("bbbb") }.scope shouldBe "sidebar"
	}

	test("scope and type filters, including the combined and unknown scopes") {
		val entries = HiddenEntries.parse(document)
		HiddenEntries.filter(entries, HiddenScopeFilter.EVERYWHERE, HiddenTypeFilter.ALL).map { it.key } shouldContainExactlyInAnyOrder listOf("7907a56192c41bb6885566e991dd27d3", "tmdb-603")
		HiddenEntries.filter(entries, HiddenScopeFilter.HOME, HiddenTypeFilter.ALL).single().name shouldBe "Combined"
		HiddenEntries.filter(entries, HiddenScopeFilter.OTHER, HiddenTypeFilter.ALL).single().name shouldBe "Future"
		HiddenEntries.filter(entries, HiddenScopeFilter.ALL, HiddenTypeFilter.EPISODES).size shouldBe 2
		HiddenEntries.filter(entries, HiddenScopeFilter.NEXT_UP, HiddenTypeFilter.MOVIES).size shouldBe 0
	}

	test("home-row entries use the scoped routes, everything else is removed by its stored key") {
		val entries = HiddenEntries.parse(document).associateBy { it.key }
		HiddenEntries.unhidePlan(entries.getValue("3edb510e-52d3-dd02-aac4-211c045310d4")) shouldBe
			UnhidePlan.ScopedRestore(UUID.fromString("3edb510e-52d3-dd02-aac4-211c045310d4"), HomeSurface.CONTINUE_WATCHING)
		HiddenEntries.unhidePlan(entries.getValue("549eb3fe-f011-4c72-24c1-506090423391")) shouldBe
			UnhidePlan.ScopedRestore(UUID.fromString("549eb3fe-f011-4c72-24c1-506090423391"), HomeSurface.NEXT_UP)
		HiddenEntries.unhidePlan(entries.getValue("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")) shouldBe UnhidePlan.RemoveKey("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
		HiddenEntries.unhidePlan(entries.getValue("tmdb-603")) shouldBe UnhidePlan.RemoveKey("tmdb-603")
		HiddenEntries.unhidePlan(entries.getValue("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")) shouldBe UnhidePlan.RemoveKey("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
	}

	test("episode titles are withheld while the show is guarded or its protection is unknown") {
		val episode = HiddenEntries.parse(document).first { it.key.startsWith("3edb") }
		val fallout = "637ea41b08d2f79d3a125196a14acd9e"
		HiddenEntries.displayTitle(episode, emptySet()) shouldBe "Fallout · S02E02 · The Golden Rule"
		HiddenEntries.displayTitle(episode, setOf(fallout)) shouldBe "Fallout · S02E02"
		HiddenEntries.displayTitle(episode, null) shouldBe "Fallout · S02E02"
	}

	test("the typed settings know every flag, with the plugin's defaults for missing ones") {
		val settings = HiddenContentRepository.Snapshot(document).settings
		settings.filterSearch shouldBe true
		settings.showButtonJellyseerr shouldBe false
		settings.filterLibrary shouldBe true
		settings.showButtonLibrary shouldBe false
		settings.experimentalHideCollections shouldBe false
	}

	test("Seerr titles count as hidden by TMDB id or by their library id") {
		val snapshot = HiddenContentRepository.Snapshot(document)
		snapshot.isHiddenTmdb(603) shouldBe true
		snapshot.isHiddenTmdb(1) shouldBe false
		snapshot.isHiddenTmdb(1, "7907a561-92c4-1bb6-8855-66e991dd27d3") shouldBe true
		// Scoped entries don't hide a title everywhere
		snapshot.isHiddenTmdb(1, "3edb510e52d3dd02aac4211c045310d4") shouldBe false
	}

	fun repository(api: EnhancedApi, posted: io.mockk.CapturingSlot<JsonObject>): HiddenContentRepository {
		every { api.pinned() } returns api
		coEvery { api.currentUserId() } returns "7ef882cd-3073-4427-9240-d0eca8333a9f"
		coEvery { api.get("user-settings/7ef882cd307344279240d0eca8333a9f/hidden-content.json", any()) } returns document
		coEvery { api.post("user-settings/7ef882cd307344279240d0eca8333a9f/hidden-content.json", capture(posted), any()) } returns JsonObject(emptyMap())
		return HiddenContentRepository(api, mockk<UserRepository>())
	}

	test("removing entries by key keeps everything else, including unknown fields") {
		val posted = slot<JsonObject>()
		val repo = repository(mockk(), posted)

		val removed = runBlocking { repo.removeEntries(listOf("tmdb-603", "3edb510e-52d3-dd02-aac4-211c045310d4", "gone")) }

		removed shouldBe 2
		val items = posted.captured["Items"]!!.jsonObject
		items.keys shouldContainExactlyInAnyOrder listOf(
			"549eb3fe-f011-4c72-24c1-506090423391",
			"7907a56192c41bb6885566e991dd27d3",
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			"broken",
		)
		posted.captured["Unknown"] shouldBe document["Unknown"]
		posted.captured["Settings"] shouldBe document["Settings"]
	}

	test("hiding a Seerr title writes a TMDB-only record, or a library record when it's in the library") {
		val posted = slot<JsonObject>()
		val repo = repository(mockk(), posted)

		runBlocking { repo.hideTmdb(1399, "tv", "Game of Thrones", "/got.jpg", null) }
		val record = posted.captured["Items"]!!.jsonObject["tmdb-1399"]!!.jsonObject
		record["ItemId"] shouldBe JsonPrimitive("")
		record["TmdbId"] shouldBe JsonPrimitive("1399")
		record["Type"] shouldBe JsonPrimitive("Series")
		record["PosterPath"] shouldBe JsonPrimitive("/got.jpg")
		record["HideScope"] shouldBe JsonPrimitive("global")
		record["SeasonNumber"] shouldBe JsonNull

		// A library title replaces its narrower home-row entry
		runBlocking { repo.hideTmdb(42, "tv", "Fallout ep", null, "3EDB510E52D3DD02AAC4211C045310D4") }
		val items = posted.captured["Items"]!!.jsonObject
		items.containsKey("3edb510e-52d3-dd02-aac4-211c045310d4") shouldBe false
		items["3edb510e52d3dd02aac4211c045310d4"]!!.jsonObject["ItemId"] shouldBe JsonPrimitive("3edb510e52d3dd02aac4211c045310d4")
	}

	test("unhiding a Seerr title removes only its global entries") {
		val posted = slot<JsonObject>()
		val repo = repository(mockk(), posted)

		runBlocking { repo.unhideTmdb(603, null) }
		posted.captured["Items"]!!.jsonObject.containsKey("tmdb-603") shouldBe false

		runBlocking { repo.unhideTmdb(1, "3edb510e52d3dd02aac4211c045310d4") }
		// The Continue Watching entry for that id is not a global hide and stays
		posted.captured["Items"]!!.jsonObject.containsKey("3edb510e-52d3-dd02-aac4-211c045310d4") shouldBe true
	}
})

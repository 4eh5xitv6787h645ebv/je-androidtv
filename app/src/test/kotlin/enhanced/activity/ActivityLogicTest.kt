package org.jellyfin.androidtv.enhanced.activity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState

class ActivityLogicTest : FunSpec({
	val json = """
		{"items":[
		 {"ActivityType":"Reviewed","UserId":"42847155cc4642f5b60f93256c9e25ee","UserName":"rb-reviewer-2","UserHasPrimaryImage":false,
		  "Timestamp":1790318850623,"Item":{"Id":"c33cf33ffbedc5e5f200b1e7d619d512","Name":"Michael","Type":"Movie","ProductionYear":2026,
		  "HasPrimaryImage":true,"HasThumbImage":true,"SeriesHasPrimaryImage":false,"SeriesHasThumbImage":false},
		  "Rating":3.5,"Content":"","Completed":false,"Progress":0},
		 {"ActivityType":"Watched","UserId":"u2","UserName":"viewer","UserHasPrimaryImage":true,"Timestamp":1790318860000,
		  "Item":{"Id":"fd8067ea9ef35106714e66075a2df7ae","Name":"My First Time in a While","Type":"Episode",
		  "SeriesName":"Scrubs (2026)","SeriesId":"326d7084082700b220b683fc0dc0ae78","SeasonNumber":2,"EpisodeNumber":1,
		  "HasPrimaryImage":true,"SeriesHasPrimaryImage":true},"Completed":false,"Progress":0.425},
		 {"ActivityType":"Favorited","UserId":"u3","UserName":"fan","Timestamp":1790318800000,"Item":{"Id":"x","Name":"X"}},
		 {"ActivityType":"SomethingNew","UserId":"u4","UserName":"n","Timestamp":1}
		]}
	""".trimIndent()
	val entries = EnhancedApi.json.decodeFromString<ActivityFeed>(json).items
	val (review, watch, favorite, unknown) = entries

	test("decodes PascalCase entries with episode numbers and fractional progress") {
		review.type shouldBe ActivityType.REVIEWED
		review.rating shouldBe 3.5
		watch.type shouldBe ActivityType.WATCHED
		watch.item?.seasonNumber shouldBe 2
		watch.item?.episodeNumber shouldBe 1
		watch.progress shouldBe 0.425
		unknown.type shouldBe ActivityType.OTHER
	}

	test("filters by category, newest first; no selection shows everything") {
		ActivityLogic.filter(entries, emptySet()) shouldContainExactly listOf(watch, review, favorite, unknown)
		ActivityLogic.filter(entries, setOf(ActivityType.FAVORITED, ActivityType.REVIEWED)) shouldContainExactly listOf(review, favorite)
	}

	test("only categories the server includes are offered") {
		ActivityConfig(showFavorited = false).categories shouldContainExactly listOf(ActivityType.WATCHED, ActivityType.REVIEWED)
	}

	test("guarded episodes lose their name; unknown guard state withholds") {
		val state = SpoilerGuardState(series = mapOf("326d7084-0827-00b2-20b6-83fc0dc0ae78" to JsonPrimitive(true)))
		val item = watch.item!!
		ActivityLogic.isGuarded(item.id, item.seriesId, true, state) shouldBe true
		ActivityLogic.isGuarded(review.item!!.id, null, true, state) shouldBe false
		ActivityLogic.isGuarded(review.item!!.id, null, true, null) shouldBe true
		ActivityLogic.isGuarded(review.item!!.id, null, false, null) shouldBe false
		ActivityLogic.isGuarded(review.item!!.id, null, true, SpoilerGuardState(movies = mapOf("C33CF33FFBEDC5E5F200B1E7D619D512" to JsonPrimitive(true)))) shouldBe true

		ActivityLogic.displayName(item.name, item.seriesName, item.seasonNumber, item.episodeNumber, guarded = true) shouldBe "Scrubs (2026) · S2:E1"
		ActivityLogic.displayName(item.name, item.seriesName, item.seasonNumber, item.episodeNumber, guarded = false) shouldBe
			"Scrubs (2026) · S2:E1 · My First Time in a While"
		ActivityLogic.displayName("Michael", null, null, null, guarded = true) shouldBe "Michael"
	}

	test("ages are compact and timestamps are milliseconds") {
		val now = 1_790_318_850_623L
		ActivityLogic.compactAge(now - 30_000, now) shouldBe null
		ActivityLogic.compactAge(now - 5 * 60_000, now) shouldBe "5m"
		ActivityLogic.compactAge(now - 3 * 3_600_000, now) shouldBe "3h"
		ActivityLogic.compactAge(now - 9L * 86_400_000, now) shouldBe "1w"
		ActivityLogic.compactAge(now + 60_000, now) shouldBe null
	}

	test("stars, progress and session percentages") {
		ActivityLogic.stars(3.5) shouldBe "★★★½"
		ActivityLogic.stars(5.0) shouldBe "★★★★★"
		ActivityLogic.stars(9.0) shouldBe "★★★★★"
		ActivityLogic.percent(0.425) shouldBe 42
		ActivityLogic.percent(1.4) shouldBe 100
		ActivityLogic.sessionPercent(6_000_000_000, 12_000_000_000) shouldBe 50
		ActivityLogic.sessionPercent(1, null) shouldBe null
		ActivityLogic.sessionPercent(1, 0) shouldBe null
	}

	test("active sessions decode") {
		val sessions = EnhancedApi.json.decodeFromString<List<ActiveSession>>(
			"""[{"UserId":"u","UserName":"admin","UserHasPrimaryImage":false,"Client":"Jellyfin Web","DeviceName":"Firefox",
			"NowPlayingItem":{"Id":"i","Type":"Episode","Name":"Pilot","SeriesName":"Show","ParentIndexNumber":1,"IndexNumber":1,
			"RunTimeTicks":100,"MediaStreams":[{"Type":"Video","Codec":"h264"}]},
			"PlayState":{"IsPaused":true,"PositionTicks":25,"PlayMethod":"Transcode"},"TranscodingInfo":{"IsVideoDirect":false}}]"""
		)
		val session = sessions.single()
		session.nowPlaying?.parentIndexNumber shouldBe 1
		session.playState?.isPaused shouldBe true
		session.playState?.playMethod shouldBe "Transcode"
	}
})

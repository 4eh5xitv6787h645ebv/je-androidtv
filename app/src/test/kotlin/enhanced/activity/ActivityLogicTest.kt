package org.jellyfin.androidtv.enhanced.activity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.data.SpoilerPolicy
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot

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
		val policy = SpoilerPolicy.STRICT
		fun snapshot(state: SpoilerGuardState?, members: Set<String>? = emptySet()) =
			SpoilerSnapshot(enabled = true, state = state, policy = policy, collectionMovieIds = members)
		val state = SpoilerGuardState(series = mapOf("326d7084-0827-00b2-20b6-83fc0dc0ae78" to JsonPrimitive(true)))
		val item = watch.item!!
		val movie = review.item!!
		ActivityLogic.protection(snapshot(state), item.type, item.id, item.seriesId).hideTitles shouldBe true
		ActivityLogic.protection(snapshot(state), movie.type, movie.id, null).guarded shouldBe false
		ActivityLogic.protection(snapshot(null), movie.type, movie.id, null).hideReviews shouldBe true
		ActivityLogic.protection(SpoilerSnapshot.DISABLED, movie.type, movie.id, null).guarded shouldBe false
		val movies = SpoilerGuardState(movies = mapOf("C33CF33FFBEDC5E5F200B1E7D619D512" to JsonPrimitive(true)))
		ActivityLogic.protection(snapshot(movies), movie.type, movie.id, null).hideReviews shouldBe true
		// An episode without its series can't be checked
		ActivityLogic.protection(snapshot(state), "Episode", "e1", null).hideTitles shouldBe true

		ActivityLogic.displayName(item.name, item.seriesName, item.seasonNumber, item.episodeNumber, hideTitle = true) shouldBe "Scrubs (2026) · S2:E1"
		ActivityLogic.displayName(item.name, item.seriesName, item.seasonNumber, item.episodeNumber, hideTitle = false) shouldBe
			"Scrubs (2026) · S2:E1 · My First Time in a While"
		ActivityLogic.displayName("Michael", null, null, null, hideTitle = true) shouldBe "Michael"
	}

	test("movies inherit protection from a guarded collection; unknown membership withholds") {
		val movie = review.item!!
		val collections = SpoilerGuardState(collections = mapOf("b0x" to JsonPrimitive(true)))
		fun snapshot(members: Set<String>?) =
			SpoilerSnapshot(enabled = true, state = collections, policy = SpoilerPolicy.STRICT, collectionMovieIds = members)
		ActivityLogic.protection(snapshot(setOf("c33cf33ffbedc5e5f200b1e7d619d512")), movie.type, movie.id, null).hideReviews shouldBe true
		ActivityLogic.protection(snapshot(emptySet()), movie.type, movie.id, null).guarded shouldBe false
		ActivityLogic.protection(snapshot(null), movie.type, movie.id, null).hideReviews shouldBe true
	}

	test("reviews follow the user's effective review policy") {
		val movie = review.item!!
		val movies = SpoilerGuardState(movies = mapOf(movie.id to JsonPrimitive(true)))
		val optedOut = SpoilerPolicy.STRICT.copy(hideReviews = false)
		val protection = ActivityLogic.protection(
			SpoilerSnapshot(enabled = true, state = movies, policy = optedOut, collectionMovieIds = emptySet()), movie.type, movie.id, null,
		)
		protection.guarded shouldBe true
		protection.hideReviews shouldBe false
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

	test("lazy keys stay unique for identical sessions and entries") {
		// The same user playing the same item on two devices with the same name: the API has no session id
		val stream = ActiveSession(userId = "u", deviceName = "Living Room", client = "Android TV", nowPlaying = NowPlayingItem(id = "i"))
		val other = stream.copy(deviceName = "Bedroom")
		val keys = ActivityLogic.uniqueKeys(listOf(stream, other, stream.copy(), stream), ActivityLogic::sessionKey)
		keys shouldContainExactly listOf(
			"session-u-Living Room-Android TV-i",
			"session-u-Bedroom-Android TV-i",
			"session-u-Living Room-Android TV-i#2",
			"session-u-Living Room-Android TV-i#3",
		)

		val entryKeys = ActivityLogic.uniqueKeys(listOf(watch, watch.copy(), review), ActivityLogic::entryKey)
		entryKeys.toSet().size shouldBe 3
		entryKeys[1] shouldBe "${entryKeys[0]}#2"
	}
})

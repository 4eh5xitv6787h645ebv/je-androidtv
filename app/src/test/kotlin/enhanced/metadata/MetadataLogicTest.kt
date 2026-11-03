package org.jellyfin.androidtv.enhanced.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.util.UUID

private inline fun <reified T> decode(json: String): T =
	EnhancedApi.json.decodeFromJsonElement(EnhancedApi.json.parseToJsonElement(json))

private fun item(type: BaseItemKind, tmdb: String? = null, communityRating: Float? = null, overview: String? = null, season: Int? = null, episode: Int? = null) =
	BaseItemDto(
		id = UUID.randomUUID(),
		type = type,
		providerIds = tmdb?.let { mapOf("Tmdb" to it) },
		communityRating = communityRating,
		overview = overview,
		indexNumber = episode ?: season,
		parentIndexNumber = if (episode != null) season else null,
	)

class MetadataLogicTest : FunSpec({
	test("review keys use the show's id for seasons and episodes") {
		TmdbTarget.of(item(BaseItemKind.MOVIE, "603"))!!.reviewKey shouldBe "603"
		TmdbTarget.of(item(BaseItemKind.SERIES, "1399"))!!.mediaType shouldBe "tv"
		// A season's own Tmdb id is TMDB's season id and must not be used
		val season = item(BaseItemKind.SEASON, tmdb = "3624", season = 1)
		TmdbTarget.of(season) shouldBe null
		TmdbTarget.of(season, 1399)!!.reviewKey shouldBe "1399:s1"
		TmdbTarget.of(item(BaseItemKind.EPISODE, tmdb = "63056", season = 1, episode = 2), 1399)!!.reviewKey shouldBe "1399:s1:e2"
		TmdbTarget.of(item(BaseItemKind.PERSON, "3894"))!!.mediaType shouldBe "person"
		TmdbTarget(TmdbTarget.TV, 1399).providerKey shouldBe "tv:1399"
	}

	test("spoiler guard withholds when the plugin stripped the rating or overview") {
		val on = EnhancedState(available = true, config = EnhancedPublicConfig(spoilerBlurEnabled = true))
		val off = EnhancedState(available = true)
		isSpoilerGuarded(on, item(BaseItemKind.SERIES)) shouldBe true
		isSpoilerGuarded(on, item(BaseItemKind.SERIES, communityRating = 8.5f)) shouldBe false
		isSpoilerGuarded(off, item(BaseItemKind.SERIES)) shouldBe false
		isSpoilerGuarded(off, item(BaseItemKind.MOVIE, communityRating = 7f, overview = SPOILER_GUARD_OVERVIEW)) shouldBe true
		isSpoilerGuarded(on, item(BaseItemKind.PERSON)) shouldBe false
	}

	test("sizes are 1024-based with two decimals") {
		formatSize(0) shouldBe "0 Bytes"
		formatSize(1536) shouldBe "1.5 KB"
		formatSize(11_663_010_216) shouldBe "10.86 GB"
		formatSize(1024L * 1024 * 1024) shouldBe "1 GB"
	}

	test("watch progress text follows the mode and time format") {
		val stats = ItemStats(success = true, progress = 42, totalPlaybackTicks = 65 * 60 * 10_000_000L, totalRuntimeTicks = 152 * 60 * 10_000_000L)
		watchProgressText(stats, WatchProgressMode.PERCENTAGE, "hours") shouldBe "42%"
		watchProgressText(stats, WatchProgressMode.TIME, "hours") shouldBe "1h 5m / 2h 32m"
		watchProgressText(stats, WatchProgressMode.REMAINING, "hours") shouldBe "-1h 27m / 2h 32m"
		formatTicks(3L * 24 * 3600 * 10_000_000L + 2L * 3600 * 10_000_000L, "days") shouldBe "3d 2h"
		formatTicks(0, "hours") shouldBe "0m"
		WatchProgressMode.of("time").next() shouldBe WatchProgressMode.REMAINING
		WatchProgressMode.REMAINING.next() shouldBe WatchProgressMode.PERCENTAGE
		WatchProgressMode.of(null) shouldBe WatchProgressMode.PERCENTAGE
	}

	test("audio languages are distinct, normalised and skip undetermined") {
		val streams = listOf(
			TagDataStream("Audio", "es-419"),
			TagDataStream("Audio", "pt-BR"),
			TagDataStream("Audio", "en-US"),
			TagDataStream("Subtitle", "fre"),
			TagDataStream("Audio", "und"),
			TagDataStream("Audio", "ger"),
			TagDataStream("Audio", "eng"),
			TagDataStream("Audio", "en"),
		)
		audioLanguages(streams) shouldContainExactly listOf("es-419", "pt-BR", "en-US", "de", "en")
	}

	test("movie release dates prefer the region, then any region per kind") {
		val releases = decode<TmdbReleaseDates>(
			"""{"id":155,"results":[
				{"iso_3166_1":"AU","release_dates":[{"release_date":"2008-07-16T00:00:00.000Z","type":3}]},
				{"iso_3166_1":"GB","release_dates":[{"release_date":"2008-07-24T00:00:00.000Z","type":3},{"release_date":"2008-10-22T00:00:00.000Z","type":5}]},
				{"iso_3166_1":"US","release_dates":[{"release_date":"2008-07-14T00:00:00.000Z","type":1},{"release_date":"2008-07-18T00:00:00.000Z","type":3},{"release_date":"2008-12-09T00:00:00.000Z","type":5}]},
				{"iso_3166_1":"DE","release_dates":[{"release_date":"2009-01-01T00:00:00.000Z","type":4}]}
			]}"""
		)
		movieReleaseInfo(releases, releaseRegions("au", "GB")) shouldContainExactly listOf(
			ReleaseInfo(ReleaseInfo.Kind.CINEMA, LocalDate.of(2008, 7, 16)),
			ReleaseInfo(ReleaseInfo.Kind.DIGITAL, LocalDate.of(2009, 1, 1)),
			ReleaseInfo(ReleaseInfo.Kind.PHYSICAL, LocalDate.of(2008, 10, 22)),
		)
		movieReleaseInfo(releases, releaseRegions(null, null)).first() shouldBe ReleaseInfo(ReleaseInfo.Kind.CINEMA, LocalDate.of(2008, 7, 14))
		releaseRegions(" gb ", "US") shouldContainExactly listOf("GB", "US")
	}

	test("show and season air dates prefer what's next") {
		seriesAirInfo(decode("""{"next_episode_to_air":null,"last_episode_to_air":{"air_date":"2013-09-29"}}"""))!!.date shouldBe LocalDate.of(2013, 9, 29)
		seriesAirInfo(decode("""{"next_episode_to_air":{"air_date":"2027-01-02"},"last_episode_to_air":{"air_date":"2026-02-03"}}"""))!!.date shouldBe LocalDate.of(2027, 1, 2)
		val season = decode<TmdbAirDates>("""{"episodes":[{"air_date":"2026-10-01"},{"air_date":"2026-11-05"},{"air_date":null},{"air_date":"2026-11-12"}]}""")
		seasonAirInfo(season, LocalDate.of(2026, 11, 3))!!.date shouldBe LocalDate.of(2026, 11, 5)
		seasonAirInfo(season, LocalDate.of(2027, 1, 1))!!.date shouldBe LocalDate.of(2026, 11, 12)
	}

	test("providers follow the user's services, the server list and ignore patterns") {
		val response = decode<WatchProviders>(
			"""{"region":"US","results":{"movie:155":[
				{"provider_id":1825,"provider_name":"HBO Max Amazon Channel","logo_path":"/a.png"},
				{"provider_id":2528,"provider_name":"YouTube TV","logo_path":"/b.png"},
				{"provider_id":1899,"provider_name":"HBO Max","logo_path":"/c.png"}],"tv:1":null}}"""
		)
		val providers = response.results["movie:155"].orEmpty()
		providers.first().logoUrl shouldBe "https://image.tmdb.org/t/p/w92/a.png"
		response.results["tv:1"] shouldBe null
		filterProviders(providers, emptyList(), emptyList(), emptyList()).size shouldBe 3
		filterProviders(providers, listOf("HBO Max"), listOf("YouTube TV"), emptyList()).map { it.providerName } shouldContainExactly listOf("HBO Max")
		filterProviders(providers, emptyList(), listOf("YouTube TV"), emptyList()).map { it.providerName } shouldContainExactly listOf("YouTube TV")
		filterProviders(providers, emptyList(), emptyList(), splitProviderList("'amazon channel'\n([")).map { it.providerName } shouldContainExactly
			listOf("YouTube TV", "HBO Max")
	}

	test("unconfirmed MDBList results are unknown, not zero") {
		mdblistChips(decode("""{"Found":false,"Ratings":[],"Confirmed":false,"Ids":{}}"""), null, false).shouldBeEmpty()
		val ratings = decode<MdblistRatings>(
			"""{"Found":true,"Confirmed":true,"Ratings":[
				{"Source":"imdb","Value":7.9,"Score":79},
				{"Source":"tomatoes","Value":92,"Score":92,"Fresh":true},
				{"Source":"letterboxd","Value":3.85},
				{"Source":"trakt"}]}"""
		)
		mdblistChips(ratings, "", false).map { "${it.label} ${it.value}" } shouldContainExactly listOf("RT Critic 92", "IMDb 79", "Letterboxd 3.85")
		mdblistChips(ratings, "letterboxd, imdb", true).map { "${it.label} ${it.value}" } shouldContainExactly listOf("Letterboxd 3.85", "IMDb 79%")
	}

	test("Seerr ratings decode for movies and shows and only fill gaps") {
		val movie = decode<SeerrCombinedRatings>(
			"""{"rt":{"title":"The Dark Knight","criticsRating":"Certified Fresh","criticsScore":94,"audienceRating":"Upright","audienceScore":94,"year":2008},
				"imdb":{"title":"The Dark Knight","criticsScore":9.1,"criticsScoreCount":3246027}}"""
		)
		val chips = seerrChips(movie.rt, movie.imdb)
		chips.map { "${it.label} ${it.value}" } shouldContainExactly listOf("IMDb 9.1", "RT Critic 94%", "RT Audience 94%")
		val tv = decode<SeerrRtRating>("""{"title":"Breaking Bad","criticsScore":96,"audienceScore":97}""")
		seerrChips(tv, null).size shouldBe 2
		mergeRatingChips(listOf(RatingChip("imdb", "IMDb", "79")), chips).map { it.source } shouldContainExactly listOf("imdb", "tomatoes", "popcorn")
	}

	test("awards decode PascalCase and sort wins first, newest first") {
		val awards = decode<Awards>(
			"""{"Found":true,"Wins":1,"Nominations":2,"Awards":[
				{"Name":"A","Result":"Nominated","Year":2019,"Recipients":[],"Works":["Vice"]},
				{"Name":"B","Result":"Won","Year":2011,"Recipients":[],"Works":["The Fighter"]},
				{"Name":"C","Result":"Nominated","Year":2016}],"Confirmed":true,"FetchedAtUnixMs":1}"""
		)
		awards.wins shouldBe 1
		sortAwards(awards.awards).map { it.name } shouldContainExactly listOf("B", "A", "C")
	}

	test("content warnings split triggers from other reported topics") {
		val entry = decode<DddEntry>(
			"""{"Found":true,"ItemId":1,"Name":"X","Topics":[
				{"Id":1,"Name":"a dog dies","Yes":90,"No":10},
				{"Id":2,"Name":"jump scares","Yes":1,"No":20},
				{"Id":3,"Name":"blood","Yes":30,"No":5},
				{"Id":4,"Name":"spiders","Yes":2,"No":0},
				{"Id":5,"Name":"clowns","Yes":40,"No":2}],"Confirmed":true}"""
		)
		val warnings = ContentWarnings.of(entry, setOf(1, 2))
		warnings.triggered.map { it.id } shouldContainExactly listOf(1)
		warnings.clear.map { it.id } shouldContainExactly listOf(2)
		// Spiders has too few votes to count as reported
		warnings.others.map { it.id } shouldContainExactly listOf(5, 3)
		DddTopicVotes(yes = 0, no = 0).percent shouldBe 0
		capitalizeFirst("a dog dies") shouldBe "A dog dies"
	}

	test("people info and tag data decode") {
		val people = decode<PeopleInfo>(
			"""{"people":{"1f6baa886636fef598d4b8aea235c3ad":{"id":"1f6baa886636fef598d4b8aea235c3ad","name":"Heath Ledger","tmdbId":"1810","type":"Person",
				"birthDate":"1979-04-04","deathDate":"2008-01-22","birthPlace":"Perth, Western Australia, Australia","isDeceased":true,"ageAtDeath":28,"ageAtItemRelease":29}}}"""
		)
		val heath = people.people.getValue("1f6baa886636fef598d4b8aea235c3ad")
		heath.isDeceased shouldBe true
		heath.ageAtItemRelease shouldBe 29
		heath.currentAge shouldBe null

		val tagData = decode<TagData>(
			"""{"Items":[{"Id":"6adf","Type":"Series","Genres":["Crime"],"ProviderIds":{"Tmdb":"1396"},"Name":"Breaking Bad",
				"FirstEpisode":{"Id":"766f","Type":"Episode","NeedsStreamFetch":true}}]}"""
		)
		tagData.items.single().firstEpisode!!.id shouldBe "766f"
		tagData.items.single().mediaStreams.shouldBeEmpty()
	}

	test("public config defaults decode with their odd spellings") {
		val config = decode<MetadataPublicConfig>(
			"""{"ShowWatchProgress":true,"WatchProgressDefaultMode":"percentage","WatchProgressTimeFormat":"hours","ShowFileSizes":true,
				"DEFAULT_REGION":"US","DEFAULT_PROVIDERS":"","IGNORE_PROVIDERS":"","PeopleTagsEnabled":true,"ReviewsExpandedByDefault":false}"""
		)
		config.defaultRegion shouldBe "US"
		config.showFileSizes shouldBe true
		config.showAudioLanguages shouldBe false
	}

	test("elsewhere settings and triggers read from their documents") {
		ElsewhereSettings.from(EnhancedApi.json.parseToJsonElement("""{"Region":"","Regions":[],"Services":["Netflix"],"SeasonProviders":true}""") as kotlinx.serialization.json.JsonObject)
			.let {
				it.region shouldBe null
				it.services shouldContainExactly listOf("Netflix")
			}
		MetadataRepository.triggersFrom(EnhancedApi.json.parseToJsonElement("""{"Triggers":[3,1]}""") as kotlinx.serialization.json.JsonObject) shouldBe setOf(1, 3)
		MetadataRepository.sortServices(
			listOf(
				TmdbProviderListEntry("Hulu", 5, mapOf("US" to 2)),
				TmdbProviderListEntry("Netflix", 1, mapOf("US" to 1)),
				TmdbProviderListEntry("Local", 3),
				TmdbProviderListEntry("Netflix", 1, mapOf("US" to 1)),
			),
			"US",
		) shouldContainExactly listOf("Netflix", "Hulu", "Local")
	}
})

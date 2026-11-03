package org.jellyfin.androidtv.enhanced.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedUserSettings
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import java.util.Locale

class DiscoverModelsTest : FunSpec({
	test("genre slider entries decode, with or without backdrops") {
		val genres = EnhancedApi.json.decodeFromJsonElement<List<DiscoverGenreSlide>>(
			EnhancedApi.json.parseToJsonElement("""[{"id":28,"name":"Action","backdrops":["/a.jpg","/b.jpg"]},{"id":99,"name":"Documentary"}]""")
		)
		genres shouldBe listOf(DiscoverGenreSlide(28, "Action", listOf("/a.jpg", "/b.jpg")), DiscoverGenreSlide(99, "Documentary"))
	}

	test("each genre tile gets a backdrop no earlier tile uses, when there is one") {
		val genres = listOf(
			DiscoverGenreSlide(1, "A", listOf("/x.jpg", "/y.jpg")),
			DiscoverGenreSlide(2, "B", listOf("/x.jpg", "/z.jpg")),
			DiscoverGenreSlide(3, "C", listOf("/x.jpg")),
			DiscoverGenreSlide(4, "D"),
		)
		assignGenreBackdrops(genres) shouldContainExactly listOf("/x.jpg", "/z.jpg", "/x.jpg", null)
	}

	test("upcoming cards show a localized date, and nothing for protected library titles") {
		upcomingDateLabel(SeerrResult(id = 1, releaseDate = "2026-11-25"), Locale.US) shouldBe "Nov 25, 2026"
		upcomingDateLabel(SeerrResult(id = 1, firstAirDate = "2026-12-25"), Locale.US) shouldBe "Dec 25, 2026"
		upcomingDateLabel(SeerrResult(id = 1, releaseDate = "2026-11-25", libraryArtwork = true), Locale.US) shouldBe null
		upcomingDateLabel(SeerrResult(id = 1, releaseDate = ""), Locale.US) shouldBe null
		upcomingDateLabel(SeerrResult(id = 1, releaseDate = "2027-xx"), Locale.US) shouldBe "2027"
		upcomingDateLabel(SeerrResult(id = 1, releaseDate = "2027-13-40"), Locale.US) shouldBe "2027"
	}

	test("random defaults decode from the PascalCase public config") {
		val defaults = EnhancedApi.json.decodeFromJsonElement<RandomPickDefaults>(
			EnhancedApi.json.parseToJsonElement("""{"RandomButtonEnabled":false,"RandomUnwatchedOnly":true,"RandomIncludeMovies":true,"RandomIncludeShows":false,"Other":1}""")
		)
		defaults shouldBe RandomPickDefaults(randomButtonEnabled = false, randomUnwatchedOnly = true, randomIncludeMovies = true, randomIncludeShows = false)
		// An older plugin without the fields keeps the plugin's own defaults
		EnhancedApi.json.decodeFromJsonElement<RandomPickDefaults>(EnhancedApi.json.parseToJsonElement("{}")) shouldBe RandomPickDefaults()
	}

	test("the user's random settings win over the server's defaults") {
		val defaults = RandomPickDefaults(randomButtonEnabled = true, randomUnwatchedOnly = true, randomIncludeMovies = true, randomIncludeShows = true)
		val user = EnhancedUserSettings(randomUnwatchedOnly = false, randomIncludeShows = false)
		RandomPickSettings.resolve(user, defaults, sourceId = "  ") shouldBe RandomPickSettings(
			enabled = true,
			unwatchedOnly = false,
			includeMovies = true,
			includeShows = false,
			sourceId = null,
		)
		RandomPickSettings.resolve(EnhancedUserSettings(randomButtonEnabled = false), defaults, "abc").let {
			it.enabled shouldBe false
			it.unwatchedOnly shouldBe true
			it.sourceId shouldBe "abc"
		}
	}

	test("a show is unwatched while it has unplayed episodes; anything else while it isn't played") {
		RandomPickSettings.isUnwatched(isSeries = true, played = false, unplayedItemCount = 3) shouldBe true
		RandomPickSettings.isUnwatched(isSeries = true, played = false, unplayedItemCount = 0) shouldBe false
		RandomPickSettings.isUnwatched(isSeries = true, played = null, unplayedItemCount = null) shouldBe false
		RandomPickSettings.isUnwatched(isSeries = false, played = false, unplayedItemCount = null) shouldBe true
		RandomPickSettings.isUnwatched(isSeries = false, played = null, unplayedItemCount = null) shouldBe true
		RandomPickSettings.isUnwatched(isSeries = false, played = true, unplayedItemCount = null) shouldBe false
	}

	test("the curated catalog has unique ids and white logo urls") {
		DiscoverCatalog.studios.map { it.id }.distinct().size shouldBe DiscoverCatalog.studios.size
		DiscoverCatalog.networks.map { it.id }.distinct().size shouldBe DiscoverCatalog.networks.size
		DiscoverCatalog.logoUrl("/a.png") shouldBe "https://image.tmdb.org/t/p/w300_filter(duotone,ffffff,bababa)/a.png"
		DiscoverCatalog.logoUrl(null) shouldBe null
	}
})

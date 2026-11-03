package org.jellyfin.androidtv.enhanced.seerrdetails

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.seerr.SeerrDetails
import java.time.LocalDate
import java.time.ZoneOffset

class SeerrDetailsExtrasTest : FunSpec({
	// Trimmed from a live `jellyseerr/tv/79680` response
	val tvJson = EnhancedApi.json.parseToJsonElement(
		"""
		{
		  "id": 79680, "name": "Snowpiercer", "originalName": "Snowpiercer",
		  "contentRatings": {"results": [
		    {"descriptors": [], "iso_3166_1": "DE", "rating": "16"},
		    {"descriptors": [], "iso_3166_1": "US", "rating": "TV-MA"}
		  ]},
		  "credits": {"cast": [
		    {"character": "Melanie", "creditId": "a", "id": 2, "name": "Jennifer Connelly", "order": 1, "profilePath": "/b.jpg"},
		    {"character": "Layton", "creditId": "b", "id": 1, "name": "Daveed Diggs", "order": 0, "profilePath": null},
		    {"character": "Nobody", "creditId": "c", "name": "No id"}
		  ], "crew": []},
		  "seasons": [{"seasonNumber": 1, "episodeCount": 10, "name": "Season 1"}],
		  "mediaInfo": {
		    "id": 628, "tmdbId": 79680, "status": 3, "jellyfinMediaId": null, "seasons": [],
		    "requests": [{
		      "id": 185, "status": 2, "createdAt": "2026-10-22T03:58:14.000Z", "type": "tv", "is4k": false,
		      "requestedBy": {"id": 1, "email": "real-admin@je-perf.invalid", "jellyfinUsername": "real-admin", "username": null, "displayName": "real-admin"},
		      "seasons": [{"id": 60, "seasonNumber": 1, "status": 2}, {"id": 61, "seasonNumber": 2, "status": 2}],
		      "seasonCount": 2
		    }]
		  }
		}
		""".trimIndent()
	)

	test("decodes cast, requests and content ratings from a show") {
		val extras = SeerrDetailsExtras.from(tvJson)
		extras.cast.map { it.name } shouldContainExactly listOf("Jennifer Connelly", "Daveed Diggs")
		extras.requests.single().apply {
			id shouldBe 185
			status shouldBe SeerrRequestStatus.APPROVED
			requestedBy?.label shouldBe "real-admin"
			seasons.map { it.seasonNumber } shouldContainExactly listOf(1, 2)
		}
		extras.certification(listOf("GB", "US")) shouldBe "TV-MA"
		extras.certification(listOf("DE", "US")) shouldBe "16"
		extras.originalTitleIfDifferent("Snowpiercer") shouldBe null
	}

	test("the shared details model still decodes from the same response") {
		val details = EnhancedApi.json.decodeFromJsonElement<SeerrDetails>(tvJson)
		details.mediaInfo?.id shouldBe 628
		details.seasons.single().episodeCount shouldBe 10
	}

	test("movie certification prefers the theatrical release and skips blank ones") {
		val movie = EnhancedApi.json.parseToJsonElement(
			"""
			{
			  "id": 272, "title": "Batman Begins", "originalTitle": "Batman Begins",
			  "releases": {"results": [
			    {"iso_3166_1": "AU", "release_dates": [
			      {"certification": "", "type": 3},
			      {"certification": "MA15+", "type": 5},
			      {"certification": "M", "type": 3}
			    ]},
			    {"iso_3166_1": "US", "release_dates": [{"certification": "PG-13", "type": 3}]}
			  ]}
			}
			""".trimIndent()
		)
		val extras = SeerrDetailsExtras.from(movie)
		extras.certification(listOf("AU", "US")) shouldBe "M"
		extras.certification(listOf("FR", "US")) shouldBe "PG-13"
		extras.certification(listOf("FR")) shouldBe null
	}

	test("original title is only offered when it differs") {
		val extras = SeerrDetailsExtras.from(EnhancedApi.json.parseToJsonElement("""{"id": 1, "originalName": "進撃の巨人"}"""))
		extras.originalTitleIfDifferent("Attack on Titan") shouldBe "進撃の巨人"
		extras.originalTitleIfDifferent("進撃の巨人") shouldBe null
	}

	test("a malformed part only loses that part") {
		val extras = SeerrDetailsExtras.from(
			EnhancedApi.json.parseToJsonElement(
				"""
				{
				  "id": 1,
				  "credits": {"cast": "not a list"},
				  "contentRatings": {"results": [{"iso_3166_1": "US", "rating": "TV-14"}]},
				  "mediaInfo": {"requests": [{"id": 3, "status": 1, "requestedBy": {"displayName": "viewer"}}]}
				}
				""".trimIndent()
			)
		)
		extras.cast shouldBe emptyList()
		extras.certification(listOf("US")) shouldBe "TV-14"
		extras.requests.single().requestedBy?.label shouldBe "viewer"
	}

	test("standard requests are newest first and leave out 4K requests") {
		val extras = SeerrDetailsExtras(
			requests = listOf(
				SeerrMediaRequest(id = 1, createdAt = "2026-01-01T00:00:00.000Z"),
				SeerrMediaRequest(id = 2, createdAt = "2026-03-01T00:00:00.000Z", is4k = true),
				SeerrMediaRequest(id = 3, createdAt = "2026-02-01T00:00:00.000Z"),
			)
		)
		extras.standardRequests.map { it.id } shouldContainExactly listOf(3, 1)
	}

	test("user labels never fall back to the e-mail address") {
		SeerrUserRef(displayName = " ", jellyfinUsername = "jelly").label shouldBe "jelly"
		SeerrUserRef().label shouldBe null
	}

	test("Seerr timestamps convert to local dates") {
		SeerrDates.localDate("2026-10-22T23:30:00.000Z", ZoneOffset.ofHours(2)) shouldBe LocalDate.of(2026, 10, 23)
		SeerrDates.localDate("2026-10-22", ZoneOffset.UTC) shouldBe LocalDate.of(2026, 10, 22)
		SeerrDates.localDate("yesterday") shouldBe null
		SeerrDates.localDate(null) shouldBe null
	}
})

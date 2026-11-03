package org.jellyfin.androidtv.enhanced.seerrdetails

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.issues.SeerrIssue
import org.jellyfin.androidtv.enhanced.issues.SeerrIssueMedia
import org.jellyfin.androidtv.enhanced.issues.SeerrIssuePage
import org.jellyfin.androidtv.enhanced.issues.SeerrIssueRepository
import org.jellyfin.androidtv.enhanced.issues.SeerrIssueScope
import org.jellyfin.androidtv.enhanced.issues.SeerrIssueStatus
import org.jellyfin.androidtv.enhanced.issues.SeerrIssueType

class SeerrIssueTest : FunSpec({
	test("decodes a live issue list page") {
		// Trimmed from a live `jellyseerr/issue?take=20&skip=0&filter=all&sort=added` response
		val page = EnhancedApi.json.decodeFromJsonElement<SeerrIssuePage>(
			EnhancedApi.json.parseToJsonElement(
				"""
				{"pageInfo": {"pages": 1, "pageSize": 20, "results": 1, "page": 1}, "results": [{
				  "id": 3, "issueType": 3, "status": 1, "problemSeason": 1, "problemEpisode": 2,
				  "createdAt": "2026-11-03T00:21:32.000Z", "updatedAt": "2026-11-03T00:21:32.000Z",
				  "createdBy": {"id": 1, "email": "real-admin@je-perf.invalid", "jellyfinUsername": "real-admin", "displayName": "real-admin"},
				  "media": {"id": 639, "mediaType": "tv", "tmdbId": 1396, "tvdbId": 81189, "status": 4},
				  "modifiedBy": null,
				  "comments": [{"id": 3, "message": "subtitles missing", "createdAt": "2026-11-03T00:21:32.000Z"}]
				}]}
				""".trimIndent()
			)
		)
		page.pageInfo.results shouldBe 1
		page.results.single().apply {
			type shouldBe SeerrIssueType.SUBTITLES
			isOpen shouldBe true
			problemSeason shouldBe 1
			problemEpisode shouldBe 2
			media?.id shouldBe 639
			media?.tmdbId shouldBe 1396
			createdBy?.label shouldBe "real-admin"
			message shouldBe "subtitles missing"
		}
	}

	test("issues are matched on Seerr's media id, not the TMDB id") {
		val issues = listOf(
			SeerrIssue(id = 1, createdAt = "2026-01-01", media = SeerrIssueMedia(id = 639, tmdbId = 1396)),
			SeerrIssue(id = 2, createdAt = "2026-01-02", media = SeerrIssueMedia(id = 1396, tmdbId = 639)),
			SeerrIssue(id = 3, createdAt = "2026-01-03", media = SeerrIssueMedia(id = 639, tmdbId = 1396), status = SeerrIssueStatus.RESOLVED),
			SeerrIssue(id = 3, createdAt = "2026-01-03", media = SeerrIssueMedia(id = 639, tmdbId = 1396), status = SeerrIssueStatus.RESOLVED),
			SeerrIssue(id = 4, media = null),
		)
		val matched = SeerrIssueRepository.forMedia(issues, 639)
		matched.map { it.id } shouldContainExactly listOf(3, 1)
		SeerrIssueRepository.MediaIssues(matched, complete = true).open shouldBe 0
	}

	test("report body uses the media id, the type number and whole-show zeros") {
		val repository = SeerrIssueRepository(mockk(relaxed = true))
		val body = repository.body(639, SeerrIssueType.AUDIO, SeerrIssueScope(season = 1, episode = 2), "  out of sync ")
		body["mediaId"] shouldBe JsonPrimitive(639)
		body["issueType"] shouldBe JsonPrimitive(2)
		body["problemSeason"] shouldBe JsonPrimitive(1)
		body["problemEpisode"] shouldBe JsonPrimitive(2)
		body["message"] shouldBe JsonPrimitive("out of sync")

		// An episode without a season isn't meaningful to Seerr
		val orphan = repository.body(639, SeerrIssueType.OTHER, SeerrIssueScope(season = 0, episode = 4), "")
		orphan["problemSeason"] shouldBe JsonPrimitive(0)
		orphan["problemEpisode"] shouldBe JsonPrimitive(0)
		orphan["message"] shouldBe JsonPrimitive("")
	}

	test("unknown issue types read as Other and the message is the first comment") {
		SeerrIssueType.of(9) shouldBe SeerrIssueType.OTHER
		SeerrIssueType.of(1) shouldBe SeerrIssueType.VIDEO
		SeerrIssue(id = 1).message shouldBe null
	}
})

package org.jellyfin.androidtv.enhanced.bookmarks

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import java.time.Instant

private const val DOCUMENT = """
	{"Bookmarks":{
	 "Bm_a":{"ItemId":"fd8067ea9ef35106714e66075a2df7ae","MediaType":"tv","Name":"Episode","Timestamp":754.5,"Label":"Great bit",
	  "CreatedAt":"2026-11-03T00:31:16.5192107Z","UpdatedAt":"2026-11-03T00:31:16.5192107Z","SyncedFrom":"","SeasonNumber":2},
	 "Bm_b":{"ItemId":"00000000000000000000000000000001","MediaType":"movie","Name":"Movie","Timestamp":61,"Label":""}
	},"Extra":true}
"""

/** A plugin holding one `bookmark.json`, logging every call. [getGate] holds the next GET until it's completed. */
private class FakePlugin {
	var document: JsonObject = EnhancedApi.json.parseToJsonElement(DOCUMENT).jsonObject
	val calls = mutableListOf<String>()
	var getGate: CompletableDeferred<Unit>? = null
	var deleteStatus = 200

	val api = mockk<EnhancedApi>().also { api ->
		every { api.pinned() } returns api
		every { api.accessToken } returns "token"
		coEvery { api.currentUserId() } returns "c799f011-678d-4423-bd7c-07d24c672de8"
		coEvery { api.get(any(), any()) } coAnswers {
			calls += "GET"
			getGate?.also { getGate = null }?.await()
			document
		}
		coEvery { api.post(any(), any(), any()) } coAnswers {
			calls += "POST ${firstArg<String>()}"
			document = secondArg<JsonElement>().jsonObject
			JsonNull
		}
		coEvery { api.delete(any(), any()) } coAnswers {
			calls += "DELETE ${firstArg<String>()}"
			if (deleteStatus == 404) throw EnhancedApi.EnhancedApiException(404, "Not found")
			JsonNull
		}
	}

	val repository = BookmarksRepository(api, EnhancedSettingsRepository(api, mockk<EnhancedRepository>(relaxed = true)))

	fun bookmark(id: String) = document["Bookmarks"]!!.jsonObject[id]?.jsonObject
}

class BookmarkEditTest : FunSpec({
	test("an empty label becomes the bookmark's time; labels are trimmed and capped") {
		BookmarkLogic.labelForSave("  Great bit ", 754.5) shouldBe "Great bit"
		BookmarkLogic.labelForSave("", 754.5) shouldBe "12:34"
		BookmarkLogic.labelForSave("   ", 3725.0) shouldBe "1:02:05"
		BookmarkLogic.labelForSave(null, 61.0) shouldBe "1:01"
		BookmarkLogic.labelForSave("x".repeat(150), 1.0).length shouldBe BookmarkLogic.MAX_LABEL_LENGTH
	}

	test("rename changes only the label and UpdatedAt of that bookmark") {
		val fields = EnhancedApi.json.parseToJsonElement(DOCUMENT).jsonObject.toMutableMap()
		val now = Instant.parse("2026-11-03T10:00:00Z")

		BookmarkLogic.rename(fields, "Bm_a", "New name", now) shouldBe true

		val bookmarks = fields["Bookmarks"]!!.jsonObject
		val renamed = bookmarks["Bm_a"]!!.jsonObject
		renamed["Label"] shouldBe JsonPrimitive("New name")
		renamed["UpdatedAt"] shouldBe JsonPrimitive("2026-11-03T10:00:00Z")
		renamed["CreatedAt"] shouldBe JsonPrimitive("2026-11-03T00:31:16.5192107Z")
		renamed["Timestamp"] shouldBe JsonPrimitive(754.5)
		renamed["SeasonNumber"] shouldBe JsonPrimitive(2)
		bookmarks["Bm_b"] shouldBe EnhancedApi.json.parseToJsonElement(DOCUMENT).jsonObject["Bookmarks"]!!.jsonObject["Bm_b"]
		fields["Extra"] shouldBe JsonPrimitive(true)
	}

	test("rename matches the document's keys ignoring case") {
		val fields = mutableMapOf<String, JsonElement>(
			"bookmarks" to JsonObject(mapOf("Bm_a" to JsonObject(mapOf("label" to JsonPrimitive("old"), "timestamp" to JsonPrimitive(5))))),
		)
		BookmarkLogic.rename(fields, "Bm_a", "new", Instant.EPOCH) shouldBe true
		val bookmark = fields["bookmarks"]!!.jsonObject["Bm_a"]!!.jsonObject
		bookmark["label"] shouldBe JsonPrimitive("new")
		bookmark.keys shouldContainExactly setOf("label", "timestamp", "UpdatedAt")
	}

	test("rename of a bookmark that is gone changes nothing") {
		val fields = EnhancedApi.json.parseToJsonElement(DOCUMENT).jsonObject.toMutableMap()
		val before = fields.toMap()
		BookmarkLogic.rename(fields, "Bm_missing", "x", Instant.EPOCH) shouldBe false
		BookmarkLogic.rename(mutableMapOf(), "Bm_a", "x", Instant.EPOCH) shouldBe false
		fields shouldBe before
	}

	test("the repository saves a rename as the whole re-read document and bumps the revision") {
		val plugin = FakePlugin()
		val revision = plugin.repository.revision.value

		plugin.repository.rename("Bm_a", "Renamed")

		plugin.calls shouldContainExactly listOf("GET", "POST user-settings/c799f011678d4423bd7c07d24c672de8/bookmark.json")
		plugin.bookmark("Bm_a")!!["Label"]!!.jsonPrimitive.content shouldBe "Renamed"
		plugin.bookmark("Bm_b")!!["Name"]!!.jsonPrimitive.content shouldBe "Movie"
		plugin.document["Extra"] shouldBe JsonPrimitive(true)
		plugin.repository.revision.value shouldBe revision + 1
	}

	test("renaming a bookmark deleted elsewhere saves nothing") {
		val plugin = FakePlugin()
		val revision = plugin.repository.revision.value

		shouldThrow<BookmarksRepository.BookmarkGoneException> { plugin.repository.rename("Bm_missing", "x") }

		plugin.calls shouldContainExactly listOf("GET")
		// The player's cache is still told to re-read: nothing is assumed about a failed write
		plugin.repository.revision.value shouldBe revision + 1
	}

	test("deleting a bookmark that is already gone reports it instead of failing") {
		val plugin = FakePlugin()
		plugin.repository.delete("Bm_a") shouldBe true
		plugin.deleteStatus = 404
		plugin.repository.delete("Bm_a") shouldBe false
		plugin.repository.revision.value shouldBe 2
	}

	test("a delete waits for a rename in progress, so the rename can't bring the bookmark back") {
		val plugin = FakePlugin()
		val gate = CompletableDeferred<Unit>()
		plugin.getGate = gate

		coroutineScope {
			val rename = async { plugin.repository.rename("Bm_b", "Kept") }
			// The rename holds the document lock, waiting on its GET
			while (plugin.calls.isEmpty()) yield()
			val delete = launch { plugin.repository.delete("Bm_a") }
			repeat(5) { yield() }
			plugin.calls shouldContainExactly listOf("GET")

			gate.complete(Unit)
			rename.await()
			delete.join()
		}

		plugin.calls shouldContainExactly listOf(
			"GET",
			"POST user-settings/c799f011678d4423bd7c07d24c672de8/bookmark.json",
			"DELETE user-settings/c799f011678d4423bd7c07d24c672de8/bookmark.json/Bm_a",
		)
	}
})

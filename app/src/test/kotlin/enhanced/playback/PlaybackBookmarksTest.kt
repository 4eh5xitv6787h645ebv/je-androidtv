package org.jellyfin.androidtv.enhanced.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.bookmarks.Bookmark
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkEntry
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarksRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

private val ITEM_ID: UUID = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001")
private val ITEM_KEY = ITEM_ID.toString().replace("-", "")

/** A bookmarks repository whose `bookmark.json` is [stored]; adds and deletes bump the revision like the real one. */
private class FakeBookmarks {
	val stored = mutableListOf<BookmarkEntry>()
	val revision = MutableStateFlow(0L)
	val repository = mockk<BookmarksRepository>().also { repository ->
		every { repository.revision } returns revision
		coEvery { repository.list() } answers { stored.toList() }
		coEvery { repository.add(any()) } answers {
			val id = "Bm_${stored.size + 1}"
			stored += BookmarkEntry(id, Bookmark(itemId = ITEM_KEY, timestampSeconds = firstArg<BookmarksRepository.NewBookmark>().seconds))
			revision.update { it + 1 }
			id
		}
	}
	val api = mockk<EnhancedApi>().also { every { it.accessToken } returns "token" }

	/** A change made outside the player, e.g. a delete on the Bookmarks page. */
	fun deleteElsewhere(bookmarkId: String) {
		stored.removeAll { it.bookmarkId == bookmarkId }
		revision.update { it + 1 }
	}
}

class PlaybackBookmarksTest : FunSpec({
	val movie = BaseItemDto(id = ITEM_ID, type = BaseItemKind.MOVIE, providerIds = emptyMap())

	test("the item's bookmarks are read once and then served from the cache") {
		val fake = FakeBookmarks()
		fake.stored += BookmarkEntry("Bm_1", Bookmark(itemId = ITEM_KEY, timestampSeconds = 60.0))
		val bookmarks = PlaybackBookmarks(fake.repository, fake.api)
		bookmarks.forItem(ITEM_ID).map { it.bookmarkId } shouldContainExactly listOf("Bm_1")
		bookmarks.forItem(ITEM_ID).map { it.bookmarkId } shouldContainExactly listOf("Bm_1")
		coVerify(exactly = 1) { fake.repository.list() }
	}

	test("a bookmark deleted elsewhere is no longer offered") {
		val fake = FakeBookmarks()
		fake.stored += BookmarkEntry("Bm_1", Bookmark(itemId = ITEM_KEY, timestampSeconds = 60.0))
		fake.stored += BookmarkEntry("Bm_2", Bookmark(itemId = ITEM_KEY, timestampSeconds = 120.0))
		val bookmarks = PlaybackBookmarks(fake.repository, fake.api)
		bookmarks.forItem(ITEM_ID).map { it.bookmarkId } shouldContainExactly listOf("Bm_1", "Bm_2")

		fake.deleteElsewhere("Bm_1")
		bookmarks.forItem(ITEM_ID).map { it.bookmarkId } shouldContainExactly listOf("Bm_2")
	}

	test("saving from the player keeps the cache and adds the new bookmark to it") {
		val fake = FakeBookmarks()
		fake.stored += BookmarkEntry("Bm_1", Bookmark(itemId = ITEM_KEY, timestampSeconds = 600.0))
		val bookmarks = PlaybackBookmarks(fake.repository, fake.api)
		bookmarks.forItem(ITEM_ID)
		bookmarks.add(movie, positionMs = 30_000, label = null)
		bookmarks.forItem(ITEM_ID).map { it.bookmarkId } shouldContainExactly listOf("Bm_2", "Bm_1")
		coVerify(exactly = 1) { fake.repository.list() }
	}

	test("a new player session reads the item again") {
		val fake = FakeBookmarks()
		val bookmarks = PlaybackBookmarks(fake.repository, fake.api)
		bookmarks.forItem(ITEM_ID).map { it.bookmarkId } shouldContainExactly emptyList()
		// Saved on another device: the revision doesn't move, only a fresh read sees it
		fake.stored += BookmarkEntry("Bm_9", Bookmark(itemId = ITEM_KEY, timestampSeconds = 5.0))
		bookmarks.refresh(ITEM_ID).map { it.bookmarkId } shouldContainExactly listOf("Bm_9")
		bookmarks.forItem(ITEM_ID).map { it.bookmarkId } shouldContainExactly listOf("Bm_9")
	}
})

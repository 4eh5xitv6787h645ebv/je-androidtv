package org.jellyfin.androidtv.enhanced.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SeerrUserStatus
import java.util.UUID

/** Seerr link status → presentation, error code → text, and the once-per-session gate. */
class SeerrLinkTest : FunSpec({
	val json = EnhancedApi.json

	fun status(body: String) = json.decodeFromJsonElement<SeerrUserStatus>(json.parseToJsonElement(body))
	fun state(seerr: SeerrUserStatus, enabled: Boolean = true, admin: Boolean = false, available: Boolean = true) = EnhancedState(
		available = available,
		config = EnhancedPublicConfig(jellyseerrEnabled = enabled, recommendationsPageEnabled = true),
		seerr = seerr,
		isAdmin = admin,
	)

	test("user-status answers from the lab map to link kinds") {
		val linked = status("""{"active":true,"userFound":true,"jellyseerrUserId":"2","reason":"linked","canRequestAdvanced":false}""")
		linked.jellyseerrUserId shouldBe "2"
		SeerrLink.kind(linked) shouldBe SeerrLinkKind.LINKED
		SeerrLink.kind(status("""{"active":true,"userFound":false,"reason":"unlinked"}""")) shouldBe SeerrLinkKind.UNLINKED
		SeerrLink.kind(status("""{"active":true,"userFound":false,"reason":"blocked"}""")) shouldBe SeerrLinkKind.BLOCKED
		SeerrLink.kind(status("""{"active":false,"userFound":false,"reason":"unreachable"}""")) shouldBe SeerrLinkKind.UNREACHABLE
		SeerrLink.kind(status("""{"active":false,"userFound":false,"reason":"disabled"}""")) shouldBe SeerrLinkKind.OFF
		SeerrLink.kind(status("""{"active":false,"userFound":false,"reason":"no_user"}""")) shouldBe SeerrLinkKind.NO_USER
	}

	test("an older plugin without a reason, and a failed status call") {
		SeerrLink.kind(status("""{"active":true,"userFound":false}""")) shouldBe SeerrLinkKind.UNLINKED
		// What EnhancedRepository keeps when the call failed
		SeerrLink.kind(SeerrUserStatus()) shouldBe SeerrLinkKind.UNKNOWN
	}

	test("Seerr off on the server, or no plugin, is OFF whatever the status says") {
		val linked = SeerrUserStatus(active = true, userFound = true, reason = "linked")
		SeerrLink.kind(state(linked, enabled = false)) shouldBe SeerrLinkKind.OFF
		SeerrLink.kind(state(linked, available = false)) shouldBe SeerrLinkKind.OFF
		SeerrLink.kind(state(linked)) shouldBe SeerrLinkKind.LINKED
	}

	test("only unlinked and unreadable statuses are looked at again automatically") {
		SeerrLinkKind.entries.filter(SeerrLink::autoRecheck) shouldBe listOf(SeerrLinkKind.UNLINKED, SeerrLinkKind.UNKNOWN)
		// Past the plugin's 60 s negative cache of a failed lookup, or the second look gets the cached answer
		SeerrLink.recheckDelayMs(SeerrLinkKind.UNLINKED) shouldBeGreaterThan 60_000L
	}

	test("the import is the main action only for administrators who aren't linked, and offered to every admin") {
		SeerrLink.importIsPrimary(SeerrLinkKind.UNLINKED, isAdmin = true).shouldBeTrue()
		SeerrLink.importIsPrimary(SeerrLinkKind.NO_USER, isAdmin = true).shouldBeTrue()
		SeerrLink.importIsPrimary(SeerrLinkKind.UNLINKED, isAdmin = false).shouldBeFalse()
		SeerrLink.importIsPrimary(SeerrLinkKind.BLOCKED, isAdmin = true).shouldBeFalse()
		SeerrLink.importIsPrimary(SeerrLinkKind.UNREACHABLE, isAdmin = true).shouldBeFalse()
		SeerrLink.importOffered(SeerrLinkKind.LINKED, isAdmin = true).shouldBeTrue()
		SeerrLink.importOffered(SeerrLinkKind.OFF, isAdmin = true).shouldBeFalse()
		SeerrLink.importOffered(SeerrLinkKind.LINKED, isAdmin = false).shouldBeFalse()
	}

	test("a Seerr page shows the link notice only when its own flag is on and the link is the problem") {
		val unlinked = SeerrUserStatus(active = true, userFound = false, reason = "unlinked")
		SeerrLink.blocksPage(state(unlinked), pageEnabled = true).shouldBeTrue()
		SeerrLink.blocksPage(state(unlinked), pageEnabled = false).shouldBeFalse()
		SeerrLink.blocksPage(state(unlinked, enabled = false), pageEnabled = true).shouldBeFalse()
		SeerrLink.blocksPage(state(SeerrUserStatus(active = true, userFound = true)), pageEnabled = true).shouldBeFalse()
	}

	test("the hub offers a Seerr page with a broken link only when its notice can fix it") {
		fun offered(reason: String, admin: Boolean = false) =
			SeerrLink.offersPage(state(SeerrUserStatus(active = true, userFound = false, reason = reason), admin = admin), pageEnabled = true)
		offered("unlinked").shouldBeTrue()
		offered("unreachable").shouldBeTrue()
		// A failed status read (check again)
		SeerrLink.offersPage(state(SeerrUserStatus()), pageEnabled = true).shouldBeTrue()
		offered("no_user", admin = true).shouldBeTrue()
		offered("no_user").shouldBeFalse()
		offered("blocked").shouldBeFalse()
		offered("blocked", admin = true).shouldBeFalse()
		offered("disabled", admin = true).shouldBeFalse()
		SeerrLink.offersPage(state(SeerrUserStatus(active = true, userFound = false, reason = "unlinked")), pageEnabled = false).shouldBeFalse()
		// The page itself still explains a block when it's reached anyway
		SeerrLink.blocksPage(state(SeerrUserStatus(active = true, userFound = false, reason = "blocked")), pageEnabled = true).shouldBeTrue()
	}

	test("link recovery counts only a change to linked after a not-linked state, once each time") {
		val recovery = SeerrLinkRecovery()
		// A state that says linked from the start (stale or not) never counts
		recovery.recovered(SeerrLinkKind.LINKED).shouldBeFalse()
		recovery.recovered(SeerrLinkKind.UNLINKED).shouldBeFalse()
		// Not loaded yet / reloading in between doesn't reset it
		recovery.recovered(SeerrLinkKind.OFF).shouldBeFalse()
		recovery.recovered(SeerrLinkKind.LINKED).shouldBeTrue()
		recovery.recovered(SeerrLinkKind.LINKED).shouldBeFalse()
		recovery.recovered(SeerrLinkKind.OFF).shouldBeFalse()
		recovery.recovered(SeerrLinkKind.LINKED).shouldBeFalse()
		recovery.recovered(SeerrLinkKind.UNREACHABLE).shouldBeFalse()
		recovery.recovered(SeerrLinkKind.LINKED).shouldBeTrue()
	}

	test("each automatic check can be claimed once per user and session") {
		val gate = SeerrRecheckGate()
		val alice = UUID.randomUUID()
		val bob = UUID.randomUUID()
		gate.claim(alice, SeerrRecheckGate.Check.AUTO).shouldBeTrue()
		repeat(3) { gate.claim(alice, SeerrRecheckGate.Check.AUTO).shouldBeFalse() }
		gate.claim(alice, SeerrRecheckGate.Check.STALE).shouldBeTrue()
		gate.claim(bob, SeerrRecheckGate.Check.AUTO).shouldBeTrue()
	}

	test("link states read as TV texts, the admin variant offering the import") {
		SeerrErrors.forLink(SeerrLinkKind.UNLINKED, isAdmin = false) shouldBe
			SeerrProblem(SeerrText.Resource(R.string.je_seerr_link_unlinked), SeerrLinkKind.UNLINKED, offerImport = false)
		SeerrErrors.forLink(SeerrLinkKind.UNLINKED, isAdmin = true) shouldBe
			SeerrProblem(SeerrText.Resource(R.string.je_seerr_link_unlinked_admin), SeerrLinkKind.UNLINKED, offerImport = true)
		SeerrErrors.forLink(SeerrLinkKind.BLOCKED, isAdmin = true).offerImport.shouldBeFalse()
		SeerrErrors.forLink(SeerrLinkKind.UNREACHABLE, isAdmin = false).text shouldBe SeerrText.Resource(R.string.je_seerr_link_unreachable)
		SeerrErrors.forLink(SeerrLinkKind.OFF, isAdmin = false).text shouldBe SeerrText.Resource(R.string.je_seerr_link_off)
	}

	test("unlinked never shows the server's sign-in text, with or without the code") {
		val proxy = EnhancedApi.EnhancedApiException(404, "x", code = "unlinked", serverMessage = SeerrErrors.UNLINKED_SERVER_MESSAGE)
		SeerrErrors.forError(proxy, isAdmin = false)?.text shouldBe SeerrText.Resource(R.string.je_seerr_link_unlinked)
		SeerrErrors.forError(proxy, isAdmin = true)?.offerImport shouldBe true
		// Where only the message survived
		val messageOnly = EnhancedApi.EnhancedApiException(404, "x", serverMessage = SeerrErrors.UNLINKED_SERVER_MESSAGE)
		SeerrErrors.isUnlinked(messageOnly).shouldBeTrue()
		SeerrErrors.forError(messageOnly, isAdmin = false)?.kind shouldBe SeerrLinkKind.UNLINKED
		SeerrErrors.isUnlinked(EnhancedApi.EnhancedApiException(404, "x", code = "not_found")).shouldBeFalse()
	}

	test("other codes keep the server's own text as a full sentence, with ours as the fallback") {
		val blocked = EnhancedApi.EnhancedApiException(403, "x", code = "blocked", serverMessage = "Your administrator has disabled Seerr for your account.")
		SeerrErrors.forError(blocked, isAdmin = true) shouldBe
			SeerrProblem(SeerrText.Server("Your administrator has disabled Seerr for your account."), SeerrLinkKind.BLOCKED)
		SeerrErrors.forError(EnhancedApi.EnhancedApiException(403, "x", code = "blocked"), isAdmin = false)?.text shouldBe
			SeerrText.Resource(R.string.je_seerr_link_blocked)
		SeerrErrors.forError(EnhancedApi.EnhancedApiException(502, "x", code = "unreachable"), isAdmin = false)?.text shouldBe
			SeerrText.Resource(R.string.je_seerr_link_unreachable)
		SeerrErrors.forError(EnhancedApi.EnhancedApiException(403, "x", code = "parental_block"), isAdmin = false)?.text shouldBe
			SeerrText.Resource(R.string.je_seerr_link_parental_block)
		SeerrErrors.forError(EnhancedApi.EnhancedApiException(403, "x", code = "no_request_permission", serverMessage = "you do not have permission"), isAdmin = false)
			?.text shouldBe SeerrText.Server("You do not have permission.")
	}

	test("a textual error body counts as the server's text; nothing to say means the caller's fallback") {
		val throttled = EnhancedApi.EnhancedApiException(
			status = 429,
			message = "x",
			body = json.parseToJsonElement("""{"error":"Import was run recently. Please wait before retrying."}"""),
		)
		SeerrErrors.serverText(throttled) shouldBe "Import was run recently. Please wait before retrying."
		SeerrErrors.forError(throttled, isAdmin = true)?.text shouldBe SeerrText.Server("Import was run recently. Please wait before retrying.")
		// The plugin's `{"error":true,…}` flag isn't text
		val flag = EnhancedApi.EnhancedApiException(500, "x", body = json.parseToJsonElement("""{"error":true}"""))
		SeerrErrors.serverText(flag) shouldBe null
		SeerrErrors.forError(flag, isAdmin = false) shouldBe null
		SeerrErrors.forError(IllegalStateException("boom"), isAdmin = false) shouldBe null
		SeerrErrors.forError(null, isAdmin = false) shouldBe null
	}

	test("server texts become full sentences") {
		SeerrErrors.sentence("  can't reach Seerr ") shouldBe "Can't reach Seerr."
		SeerrErrors.sentence("Done!") shouldBe "Done!"
		SeerrErrors.sentence("Already a sentence.") shouldBe "Already a sentence."
		SeerrErrors.sentence("   ") shouldBe null
		SeerrErrors.sentence(null) shouldBe null
	}

	test("import-users answers decode, including a null error list") {
		val result = json.decodeFromJsonElement<SeerrImportResult>(
			json.parseToJsonElement("""{"success":true,"usersImported":7,"totalUsers":9,"errors":[]}""")
		)
		result shouldBe SeerrImportResult(success = true, usersImported = 7, totalUsers = 9)
		json.decodeFromJsonElement<SeerrImportResult>(json.parseToJsonElement("""{"success":true,"usersImported":0,"totalUsers":2,"errors":null}"""))
			.errors shouldBe emptyList()
	}

	test("a Seerr user's display name falls back through its user names") {
		fun user(body: String) = json.parseToJsonElement(body).jsonObject
		SeerrLinkNames.displayName(user("""{"id":1,"displayName":"real-admin","jellyfinUsername":"ignored"}""")) shouldBe "real-admin"
		SeerrLinkNames.displayName(user("""{"id":5,"displayName":"","jellyfinUsername":null,"email":"x@y"}""")) shouldBe "x@y"
		SeerrLinkNames.displayName(user("""{"id":5,"displayName":"","email":""}""")) shouldBe null
		SeerrLinkNames.displayName(null) shouldBe null
	}

	test("a SeerrText resource is what the problem carries") {
		SeerrErrors.forLink(SeerrLinkKind.UNKNOWN, isAdmin = false).text.shouldBeInstanceOf<SeerrText.Resource>()
	}
})

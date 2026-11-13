package me.lesh.chatter.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The link and the way to delete it in an upload site's answer, the way each site sends them. */
class UploadAnswerTest {
    private val kappaAnswer =
        """{"id":"AbCdE","ext":".png","key":"s3cr3t","link":"https://kappa.lol/AbCdE","delete":"https://kappa.lol/delete?s3cr3t"}"""

    @Test
    fun kappaLolAnswersWithJsonAndAKeyToDeleteWith() {
        val kappa = UploadTarget.of(PresetUploader.KappaLol, null)!!
        assertEquals("https://kappa.lol/AbCdE", UploadAnswer.link(kappaAnswer, kappa.host.linkKey))
        assertEquals(Deletion("https://kappa.lol/api/delete?key=s3cr3t"), kappa.deletion(kappaAnswer, "https://kappa.lol/AbCdE"))
    }

    /** catbox.moe answers with nothing but the link, and deletes only for the account's userhash. */
    @Test
    fun catboxDeletesOnlyWithAUserhash() {
        val answer = "https://files.catbox.moe/abc123.png\n"
        val anonymous = UploadTarget.of(PresetUploader.Catbox, null)!!
        assertEquals("https://files.catbox.moe/abc123.png", UploadAnswer.link(answer, anonymous.host.linkKey))
        assertEquals(mapOf("reqtype" to "fileupload"), anonymous.host.extraFields)
        assertFalse(anonymous.canDelete)
        assertNull(anonymous.deletion(answer, "https://files.catbox.moe/abc123.png"))

        val account = UploadTarget.of(PresetUploader.Catbox, " hash123 ")!!
        assertEquals("hash123", account.host.extraFields["userhash"])
        val deletion = account.deletion(answer, "https://files.catbox.moe/abc123.png")!!
        assertEquals("POST", deletion.method)
        assertEquals(mapOf("reqtype" to "deletefiles", "userhash" to "hash123", "files" to "abc123.png"), deletion.form)
    }

    /** Imgur takes no upload without a Client-ID, and only the user's own: it is not offered without one. */
    @Test
    fun imgurNeedsTheUsersClientId() {
        assertNull(UploadTarget.of(PresetUploader.Imgur, null))
        assertNull(UploadTarget.of(PresetUploader.Imgur, " "))

        val own = UploadTarget.of(PresetUploader.Imgur, "mine")!!
        assertEquals(mapOf("Authorization" to "Client-ID mine"), own.host.headers)
        val answer = """{"data":{"id":"x","link":"https://i.imgur.com/x.png","deletehash":"d3l"},"success":true,"status":200}"""
        assertEquals("https://i.imgur.com/x.png", UploadAnswer.link(answer, own.host.linkKey))
        assertEquals(
            Deletion("https://api.imgur.com/3/image/d3l", method = "DELETE", headers = mapOf("Authorization" to "Client-ID mine")),
            own.deletion(answer, "https://i.imgur.com/x.png"),
        )
    }

    @Test
    fun aCustomSiteDeletesThroughItsPageInTheBrowser() {
        val answer = """{"success":true,"data":{"link":"https://i.example.com/a.png","delete":"https://i.example.com/d/xyz"}}"""
        val host = UploadHost("https://i.example.com/upload", "image", "data.link", deleteKey = "data.delete")
        val custom = UploadTarget.of(CustomUploader("1", host))!!
        assertEquals("i.example.com", custom.name)
        assertEquals("https://i.example.com/a.png", UploadAnswer.link(answer, host.linkKey))
        assertEquals(Deletion("https://i.example.com/d/xyz", inBrowser = true), custom.deletion(answer, "https://i.example.com/a.png"))
    }

    @Test
    fun aCustomSiteWithoutDeleteFieldCannotDelete() {
        val custom = UploadTarget.of(CustomUploader("1", UploadHost("https://i.example.com/upload", "image", "link")))!!
        assertFalse(custom.canDelete)
        assertNull(custom.deletion("""{"link":"https://i.example.com/a.png","delete":"https://x"}""", "https://i.example.com/a.png"))
    }

    @Test
    fun anAnswerWithoutLinkHasNone() {
        assertNull(UploadAnswer.link("""{"error":"too large"}""", "link"))
        assertNull(UploadAnswer.link("""{"link":42}""", "link"))
        assertNull(UploadAnswer.link("""{"link":"javascript:alert(1)"}""", "link"))
        assertNull(UploadAnswer.link("<html>Bad gateway</html>", "link"))
        assertNull(UploadAnswer.link("<html>Bad gateway</html>", ""))
    }

    @Test
    fun aDeletionSaysWhetherItWorked() {
        assertTrue(UploadAnswer.deleted("""{"success":true}"""))
        assertFalse(UploadAnswer.deleted("""{"success":false}"""))
        assertTrue(UploadAnswer.deleted("Files successfully deleted."))
        assertFalse(UploadAnswer.deleted("Not found"))
    }

    @Test
    fun aCustomSiteNeedsAnHttpsAddressAndAField() {
        assertFalse(UploadHost.EMPTY.isValid)
        assertFalse(UploadHost("http://i.example.com/upload", "file", "link").isValid)
        assertFalse(UploadHost("https://i.example.com/upload", " ", "link").isValid)
        assertTrue(UploadHost("https://i.example.com/upload", "file", "").isValid)
        assertNull(UploadTarget.of(CustomUploader("1", UploadHost.EMPTY)))
    }

    @Test
    fun aHeaderIsReadAsNameAndValue() {
        assertEquals("Authorization" to "Client-ID abc", UploadAnswer.header("Authorization: Client-ID abc"))
        assertNull(UploadAnswer.header("no colon"))
        assertNull(UploadAnswer.header("Two words: x"))
        assertNull(UploadAnswer.header("Name:"))
    }

    @Test
    fun theUsersSetupListsPresetsAndTheirOwnSites() {
        val own = CustomUploader("mine", UploadHost("https://i.example.com/upload", "file", "link"))
        val setup = UploaderSetup(custom = listOf(own), keys = mapOf("catbox.moe" to "hash"))
        assertEquals("i.example.com", setup.target("mine")?.name)
        assertTrue(setup.target("catbox.moe")!!.canDelete)
        assertNull(setup.target("gone"))
    }
}

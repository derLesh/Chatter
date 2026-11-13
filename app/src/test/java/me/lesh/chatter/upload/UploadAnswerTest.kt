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
        val host = Uploader.KappaLol.host(UploadHost.EMPTY_CUSTOM)
        assertEquals("https://kappa.lol/AbCdE", UploadAnswer.link(kappaAnswer, host.linkKey))
        assertEquals(Deletion("https://kappa.lol/api/delete?key=s3cr3t", inBrowser = false), Uploader.KappaLol.deletion(kappaAnswer, host))
    }

    /** catbox.moe answers with nothing but the link, and cannot delete without an account. */
    @Test
    fun catboxAnswersWithThePlainLink() {
        val host = Uploader.Catbox.host(UploadHost.EMPTY_CUSTOM)
        val answer = "https://files.catbox.moe/abc123.png\n"
        assertEquals("https://files.catbox.moe/abc123.png", UploadAnswer.link(answer, host.linkKey))
        assertNull(Uploader.Catbox.deletion(answer, host))
        assertEquals(mapOf("reqtype" to "fileupload"), host.extraFields)
    }

    @Test
    fun aNestedFieldIsFoundByItsPath() {
        val answer = """{"success":true,"data":{"link":"https://i.example.com/a.png","delete":"https://i.example.com/d/xyz"}}"""
        val custom = UploadHost("https://i.example.com/upload", "image", "data.link", deleteKey = "data.delete")
        assertEquals("https://i.example.com/a.png", UploadAnswer.link(answer, custom.linkKey))
        // A site of the user's own: only its own page knows what deleting there takes.
        assertEquals(Deletion("https://i.example.com/d/xyz", inBrowser = true), Uploader.Custom.deletion(answer, custom))
    }

    @Test
    fun aCustomSiteWithoutDeleteFieldCannotDelete() {
        val custom = UploadHost("https://i.example.com/upload", "image", "link")
        assertNull(Uploader.Custom.deletion("""{"link":"https://i.example.com/a.png","delete":"https://x"}""", custom))
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
        assertFalse(UploadAnswer.deleted("Not found"))
    }

    @Test
    fun aCustomSiteNeedsAnHttpsAddressAndAField() {
        assertFalse(UploadHost.EMPTY_CUSTOM.isValid)
        assertFalse(UploadHost("http://i.example.com/upload", "file", "link").isValid)
        assertFalse(UploadHost("https://i.example.com/upload", " ", "link").isValid)
        assertTrue(UploadHost("https://i.example.com/upload", "file", "").isValid)
    }

    @Test
    fun aCustomSiteIsNamedByItsAddress() {
        assertEquals("i.example.com", Uploader.Custom.name(UploadHost("https://i.example.com/api/upload", "file", "link")))
        assertEquals("kappa.lol", Uploader.KappaLol.name(UploadHost.EMPTY_CUSTOM))
    }
}

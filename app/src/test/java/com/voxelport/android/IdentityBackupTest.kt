package com.voxelport.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityBackupTest {
    // Made by the desktop app's code (Node crypto) with a fixed salt and iv: Android must open it, and produce the same bytes.
    private val passphrase = "correct horse battery staple"
    private val token = "vp_testtoken_abcdefghijkl"
    private val vpId = "0c67f05015ae"
    private val salt = "0123456789abcdef".toByteArray()
    private val iv = "abcdefghijkl".toByteArray()
    private val desktopBlob = JSONObject(
        """{"v":1,"kdf":"pbkdf2-sha256","i":600000,"salt":"MDEyMzQ1Njc4OWFiY2RlZg","iv":"YWJjZGVmZ2hpamts","ct":"MsyHgM7Ii1e5kIcAq93ktU1lKVGATxIQ5uoB0Q9AYm_7XG9-hIDvmT8"}""",
    )

    @Test fun `the 12 character id is the start of the SHA-256 of the code`() {
        assertEquals(vpId, IdentityBackup.shortId(token))
    }

    @Test fun `a backup made by the desktop app opens here`() {
        assertEquals(token, IdentityBackup.decrypt(desktopBlob, passphrase, vpId))
    }

    @Test fun `a backup made here is byte for byte what the desktop app makes`() {
        val mine = IdentityBackup.encrypt(token, passphrase, salt, iv)
        assertEquals(desktopBlob.getString("ct"), mine.getString("ct"))
        assertEquals(desktopBlob.getString("salt"), mine.getString("salt"))
        assertEquals(desktopBlob.getString("iv"), mine.getString("iv"))
        assertEquals(600000, mine.getInt("i"))
    }

    @Test fun `encrypting twice gives different blobs, and both open`() {
        val a = IdentityBackup.encrypt(token, passphrase)
        val b = IdentityBackup.encrypt(token, passphrase)
        assertNotEquals(a.getString("ct"), b.getString("ct"))
        assertEquals(token, IdentityBackup.decrypt(a, passphrase, vpId))
        assertEquals(token, IdentityBackup.decrypt(b, passphrase, vpId))
    }

    @Test fun `the wrong passphrase opens nothing`() {
        assertNull(IdentityBackup.decrypt(desktopBlob, "correct horse battery stapl3", vpId))
        assertNull(IdentityBackup.decrypt(desktopBlob, "", vpId))
    }

    @Test fun `the same words typed another way still open it`() {
        // "é" as one character or as e plus an accent: the passphrase is normalised first.
        val one = IdentityBackup.encrypt(token, "café café café", salt, iv)
        assertEquals(token, IdentityBackup.decrypt(one, "café café café", vpId))
    }

    @Test fun `a blob filed under another address is refused`() {
        assertNull(IdentityBackup.decrypt(desktopBlob, passphrase, "ffffffffffff"))
    }

    @Test fun `a blob that was changed is refused`() {
        val ct = desktopBlob.getString("ct")
        val flipped = (if (ct[5] == 'A') 'B' else 'A').let { ct.substring(0, 5) + it + ct.substring(6) }
        assertNull(IdentityBackup.decrypt(JSONObject(desktopBlob.toString()).put("ct", flipped), passphrase, vpId))
    }

    @Test fun `blobs with odd settings are refused, not trusted`() {
        fun with(key: String, value: Any) = JSONObject(desktopBlob.toString()).put(key, value)
        assertNull(IdentityBackup.decrypt(with("v", 2), passphrase, vpId))
        assertNull(IdentityBackup.decrypt(with("kdf", "md5"), passphrase, vpId))
        assertNull(IdentityBackup.decrypt(with("i", 1000), passphrase, vpId))
        assertNull(IdentityBackup.decrypt(with("i", 999_999_999), passphrase, vpId))
        assertNull(IdentityBackup.decrypt(with("salt", "AAAA"), passphrase, vpId))
        assertNull(IdentityBackup.decrypt(JSONObject("{}"), passphrase, vpId))
    }

    @Test fun `a passphrase must be long enough and not trivial`() {
        assertEquals(IdentityBackup.Problem.TOO_SHORT, IdentityBackup.passphraseProblem("short one"))
        assertEquals(IdentityBackup.Problem.TOO_SHORT, IdentityBackup.passphraseProblem(""))
        assertEquals(IdentityBackup.Problem.TOO_SIMPLE, IdentityBackup.passphraseProblem("aaaaaaaaaaaaaaaa"))
        assertEquals(IdentityBackup.Problem.TOO_SIMPLE, IdentityBackup.passphraseProblem("1212121212121212"))
        assertNull(IdentityBackup.passphraseProblem("correct horse battery staple"))
        assertNull(IdentityBackup.passphraseProblem("my phone loves pizza"))
        assertTrue(IdentityBackup.MIN_PASSPHRASE >= 12)
        assertFalse(IdentityBackup.passphraseProblem("twelve chars!") != null)
    }
}

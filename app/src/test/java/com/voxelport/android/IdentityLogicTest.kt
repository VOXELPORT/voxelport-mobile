package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityLogicTest {
    private val code = "vp_AbCdEfGhIjKlMnOpQrStUvWx"

    @Test fun pastedCodesAreCleanedUp() {
        assertEquals(code, IdentityLogic.normalize(code))
        assertEquals(code, IdentityLogic.normalize("  $code \n"))
        assertEquals(code, IdentityLogic.normalize("\"$code\""))
        assertEquals(code, IdentityLogic.normalize("“$code”"))
        assertEquals(code, IdentityLogic.normalize("vp_AbCdEfGhIj\nKlMnOpQrStUvWx"))   // wrapped by a messaging app
    }

    @Test fun onlyRealLookingCodesAreAccepted() {
        assertTrue(IdentityLogic.looksValid(code))
        assertTrue(IdentityLogic.looksValid("  $code\n"))
        for (bad in listOf("", "vp_", "vp_short", "AbCdEfGhIjKlMnOpQrStUvWx", "vp_has space!inside", "vp_" + "a".repeat(10) + "/", "https://voxelport.in", "xp_$code"))
            assertFalse(bad, IdentityLogic.looksValid(bad))
    }

    @Test fun maskingNeverRevealsTheWholeCode() {
        val m = IdentityLogic.masked(code)
        assertFalse(m.contains(code))
        assertTrue(m.startsWith("vp_AbC") && m.endsWith("Wx"))
        assertEquals("…", IdentityLogic.masked("abc"))
    }
}

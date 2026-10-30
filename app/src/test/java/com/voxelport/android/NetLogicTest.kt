package com.voxelport.android

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetLogicTest {
    @Test fun `a refused certificate is recognised`() {
        assertTrue(NetLogic.isCertificateProblem(SSLHandshakeException("handshake failed")))
        assertTrue(NetLogic.isCertificateProblem(CertificateException("not trusted")))
        assertTrue(NetLogic.isCertificateProblem(CertPathValidatorException("Trust anchor for certification path not found.")))
    }

    @Test fun `it is found when wrapped, as Android wraps it`() {
        val real = CertPathValidatorException("Trust anchor for certification path not found.")
        val wrapped = SSLHandshakeException("handshake failed").apply { initCause(CertificateException("chain", real)) }
        assertTrue(NetLogic.isCertificateProblem(IOException("outer", wrapped)))
        assertTrue(NetLogic.isCertificateProblem(IllegalStateException("outer", CertificateException("x", real))))
    }

    @Test fun `being offline or a slow site is not a certificate problem`() {
        assertFalse(NetLogic.isCertificateProblem(UnknownHostException("no network")))
        assertFalse(NetLogic.isCertificateProblem(SocketTimeoutException("timed out")))
        assertFalse(NetLogic.isCertificateProblem(IllegalStateException("The download server answered HTTP 500.")))
        assertFalse(NetLogic.isCertificateProblem(null))
    }

    @Test fun `a very deep or looping cause chain ends`() {
        var e: Throwable = IllegalStateException("bottom")
        repeat(20) { e = IllegalStateException("layer $it", e) }
        assertFalse(NetLogic.isCertificateProblem(e))
        val a = IllegalStateException("a"); val b = IllegalStateException("b", a); a.initCause(b)
        assertFalse(NetLogic.isCertificateProblem(a))
    }
}

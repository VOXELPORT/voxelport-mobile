package com.voxelport.android

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Plain network-error rules, kept free of Android calls so they can be unit-tested. */
object NetLogic {
    /**
     * True when a download failed because the phone does not trust the site's certificate ("Trust anchor for certification
     * path not found" and its relatives), as opposed to being offline or the site being down. The cause chain is searched,
     * because the real exception is usually wrapped.
     */
    fun isCertificateProblem(e: Throwable?): Boolean {
        var c = e
        var depth = 0
        while (c != null && depth++ < 8) {
            if (c is SSLException || c is CertificateException || c is CertPathValidatorException) return true
            c = c.cause
        }
        return false
    }
}

package com.voxelport.android

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backs up this install's address code (the token behind its public port and custom address) to the person's Discord
 * account, so a reinstall or a new phone can get the same address back. The code is encrypted HERE, with a passphrase only
 * the person knows, before it is sent: the accounts service keeps an opaque blob and can never read it.
 *
 * The format is shared with the desktop app, so either can open the other's backup:
 *   key  = PBKDF2-HMAC-SHA256(passphrase in Unicode NFC, UTF-8, salt 16 bytes, 600,000 rounds) → 32 bytes
 *   blob = AES-256-GCM(key, iv 12 bytes, aad "voxelport-identity-v1|<12-character id>", the code in UTF-8) = ciphertext + 16-byte tag
 *   sent as { v:1, kdf:"pbkdf2-sha256", i, salt, iv, ct } with base64url (no padding) fields.
 * The 12-character id (first 12 hex digits of SHA-256 of the code) is bound in as associated data, so a blob cannot be
 * passed off as belonging to another address.
 */
object IdentityBackup {
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 12
    private const val AAD_PREFIX = "voxelport-identity-v1|"
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    /** The 12-character VoxelPort ID of a code: what the apps and the accounts service show and match on. */
    fun shortId(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)

    enum class Problem { TOO_SHORT, TOO_SIMPLE }

    /**
     * A backup is only as safe as its passphrase, because anyone who got hold of the stored blob could try guesses offline.
     * So: at least 12 characters, and not one character repeated or a plain run like "123456789012".
     */
    fun passphraseProblem(raw: String): Problem? {
        val p = normalize(raw)
        if (p.length < MIN_PASSPHRASE) return Problem.TOO_SHORT
        if (p.toSet().size < 5) return Problem.TOO_SIMPLE
        return null
    }

    /** What the passphrase is, once: Unicode-normalised so the same words typed on another keyboard still match. */
    private fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun key(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(normalize(passphrase).toCharArray(), salt, iterations, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    /** The blob to send, as a JSON object string. [salt] and [iv] are fresh random bytes unless a test fixes them. */
    fun encrypt(
        token: String, passphrase: String,
        salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
        iv: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) },
        iterations: Int = ITERATIONS,
    ): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD((AAD_PREFIX + shortId(token)).toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return JSONObject().put("v", 1).put("kdf", "pbkdf2-sha256").put("i", iterations)
            .put("salt", enc.encodeToString(salt)).put("iv", enc.encodeToString(iv)).put("ct", enc.encodeToString(ct))
    }

    /** The code, or null if the passphrase is wrong, the blob was changed, or it belongs to another address ([vpId]). */
    fun decrypt(blob: JSONObject, passphrase: String, vpId: String): String? = runCatching {
        if (blob.getInt("v") != 1 || blob.getString("kdf") != "pbkdf2-sha256") return null
        val iterations = blob.getInt("i")
        if (iterations !in 200_000..2_000_000) return null
        val salt = dec.decode(blob.getString("salt"))
        val iv = dec.decode(blob.getString("iv"))
        val ct = dec.decode(blob.getString("ct"))
        if (salt.size != 16 || iv.size != 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD((AAD_PREFIX + vpId).toByteArray(Charsets.UTF_8))
        val token = String(cipher.doFinal(ct), Charsets.UTF_8)
        // Only a code that really is the one this backup was filed under is accepted.
        if (IdentityLogic.looksValid(token) && shortId(token) == vpId) token else null
    }.getOrNull()
}

// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Remote mode keys and sealing (protocol/README.md "Remote mode"): HKDF-SHA256 + ChaCha20-Poly1305, nonce12 || ct. */
class RemoteCrypto(key: ByteArray) {
    companion object {
        private val SALT = "tosbisim-remote-v1".toByteArray()
        val H2C = "h2c".toByteArray()
        val C2H = "c2h".toByteArray()
        private val rng = SecureRandom()

        fun hkdf(ikm: ByteArray, info: String, len: Int = 32): ByteArray {
            val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SALT, "HmacSHA256")) }.doFinal(ikm)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            val out = ByteArray(len)
            var t = ByteArray(0)
            var i = 0
            var counter = 1
            while (i < len) {
                mac.update(t); mac.update(info.toByteArray()); mac.update(counter.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, len - i)
                System.arraycopy(t, 0, out, i, n)
                i += n; counter++
            }
            return out
        }

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    val roomToken: ByteArray = hkdf(key, "room-token")
    val roomId: String = hex(MessageDigest.getInstance("SHA-256").digest(roomToken))
    private val control = SecretKeySpec(hkdf(key, "control"), "ChaCha20")
    private val audio = SecretKeySpec(hkdf(key, "audio"), "ChaCha20")

    fun sealControl(plain: ByteArray) = seal(control, plain, H2C)
    fun openControl(sealed: ByteArray) = open(control, sealed, C2H)
    fun sealAudio(plain: ByteArray, len: Int = plain.size) = seal(audio, plain.copyOf(len), H2C)
    fun openAudio(sealed: ByteArray, len: Int = sealed.size) = open(audio, sealed.copyOf(len), C2H)

    /** Control plaintext = [u64 BE unix ms][envelope]. */
    fun sealEnvelope(env: ByteArray): ByteArray =
        sealControl(ByteBuffer.allocate(8 + env.size).putLong(System.currentTimeMillis()).put(env).array())

    private fun seal(k: SecretKeySpec, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.ENCRYPT_MODE, k, IvParameterSpec(nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** null if it doesn't authenticate (wrong key, tampered, or our own direction reflected back). */
    private fun open(k: SecretKeySpec, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < 12 + 16) return null
        return runCatching {
            val c = Cipher.getInstance("ChaCha20-Poly1305")
            c.init(Cipher.DECRYPT_MODE, k, IvParameterSpec(sealed, 0, 12))
            c.updateAAD(aad)
            c.doFinal(sealed, 12, sealed.size - 12)
        }.getOrNull()
    }
}

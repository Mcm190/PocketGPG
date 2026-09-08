package com.pocketgpg.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on-device because `KeyStore.getInstance("AndroidKeyStore")` has no provider on a plain
 * JVM: the whole point of [StagingCipher] is to keep the key inside the real Android Keystore,
 * so it can only be verified against the real Keystore.
 */
@RunWith(AndroidJUnit4::class)
class StagingCipherTest {

    private fun encrypt(cipher: StagingCipher, plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.wrapForWriting(out).use { it.write(plaintext) }
        return out.toByteArray()
    }

    private fun decrypt(cipher: StagingCipher, ciphertext: ByteArray): ByteArray =
        cipher.readingCipher().doFinal(ciphertext)

    @Test
    fun roundTripsExactlyWhatWasWritten() {
        val plaintext = "the quick brown fox".repeat(1000).toByteArray()
        val cipher = StagingCipher.create()

        val ciphertext = encrypt(cipher, plaintext)
        assertFalse(
            "ciphertext must not contain the plaintext in the clear",
            String(ciphertext, Charsets.ISO_8859_1).contains("quick brown fox"),
        )

        val decrypted = decrypt(cipher, ciphertext)
        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test
    fun destroyingTheKeyMakesTheCiphertextPermanentlyUnreadable() {
        val plaintext = ByteArray(4096) { it.toByte() }
        val cipher = StagingCipher.create()
        val ciphertext = encrypt(cipher, plaintext)

        cipher.destroy()

        assertThrows(IllegalStateException::class.java) {
            decrypt(cipher, ciphertext)
        }
    }

    @Test
    fun aTamperedCiphertextFailsTheAuthenticationTagInsteadOfReturningAlteredPlaintext() {
        val plaintext = ByteArray(4096) { it.toByte() }
        val cipher = StagingCipher.create()
        val ciphertext = encrypt(cipher, plaintext)
        ciphertext[ciphertext.size / 2] = (ciphertext[ciphertext.size / 2] + 1).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            decrypt(cipher, ciphertext)
        }
        cipher.destroy()
    }

    @Test
    fun twoInstancesNeverReuseAKey() {
        val plaintext = "same plaintext both times".toByteArray()
        val a = StagingCipher.create()
        val b = StagingCipher.create()

        val ciphertextA = encrypt(a, plaintext)
        val ciphertextB = encrypt(b, plaintext)

        assertFalse(ciphertextA.contentEquals(ciphertextB))
        assertThrows(GeneralSecurityException::class.java) {
            // b's key/IV against a's ciphertext must not decrypt cleanly.
            decrypt(b, ciphertextA)
        }
        a.destroy()
        b.destroy()
    }

    @Test
    fun destroyOrphansClearsAKeyThatWasNeverExplicitlyDestroyed() {
        val cipher = StagingCipher.create()
        val ciphertext = encrypt(cipher, "orphaned".toByteArray())

        StagingCipher.destroyOrphans()

        assertThrows(IllegalStateException::class.java) {
            decrypt(cipher, ciphertext)
        }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val leftover = keyStore.aliases().toList().filter { it.startsWith("pocketgpg-staging-") }
        assertEquals("destroyOrphans should leave no staging aliases behind", emptyList<String>(), leftover)
    }
}

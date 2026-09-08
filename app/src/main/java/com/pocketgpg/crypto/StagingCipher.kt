package com.pocketgpg.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the plaintext that decryption stages on disk in a one-time AES key that lives only in
 * the Android Keystore, so that erasing it means destroying a few bytes of key material rather
 * than hoping an overwrite reaches flash that wear levelling has already relocated elsewhere --
 * see [Shredder]'s doc comment for why that hope is misplaced. Once [destroy] runs, whatever
 * ciphertext a stale physical page keeps around is unreadable forever, regardless of whether or
 * when that page itself is ever erased.
 *
 * One instance is good for one write followed by one read, matching how the staging file is
 * used: written once during decrypt, read back once while copying to the chosen folder.
 *
 * The nonce is generated here rather than left to the provider: AndroidKeyStore only hands back
 * an auto-generated one once its lazily-started operation is actually under way, and by the time
 * [wrapForWriting]'s stream is closed that operation has already finished, which on this
 * provider means reading it back afterwards sees null again. Picking it ourselves sidesteps that
 * entirely. Reusing a fixed nonce would normally be a GCM nonce-reuse hazard, but it is not one
 * here: every instance's key is freshly generated and used for exactly one message, so there is
 * never a second encryption under the same (key, nonce) pair to collide with. `AndroidKeyStore`
 * rejects a caller-supplied IV unless the key explicitly opts out of randomized encryption.
 *
 * Deliberately no `setUnlockedDeviceRequired`: the key already never leaves the Keystore, and
 * requiring an unlocked device would fail an in-progress decrypt of a large file if the screen
 * happened to lock partway through, for a key that is destroyed within the same operation anyway.
 */
class StagingCipher private constructor(private val alias: String) {

    private val nonce: ByteArray = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)

    /** Wraps [destination] so plaintext written to the returned stream reaches it as ciphertext. */
    fun wrapForWriting(destination: OutputStream): OutputStream {
        val spec = GCMParameterSpec(TAG_BITS, nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), spec)
        return CipherOutputStream(destination, cipher)
    }

    /**
     * A decrypting [Cipher] matching what [wrapForWriting] used, for the caller to pump by hand
     * with [Cipher.update]/[Cipher.doFinal]. Deliberately not wrapped in a `CipherInputStream`:
     * that class is documented to swallow a failed GCM tag check inside `read()` instead of
     * throwing it, which would turn a corrupt staging file into silently truncated output.
     */
    fun readingCipher(): Cipher {
        val spec = GCMParameterSpec(TAG_BITS, nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec)
        return cipher
    }

    /**
     * Destroys the Keystore entry. From this instant the ciphertext [wrapForWriting] produced is
     * unrecoverable no matter how many stale physical copies flash wear levelling left behind.
     */
    fun destroy() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun secretKey(): SecretKey =
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: error("staging key $alias no longer exists")

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val ALIAS_PREFIX = "pocketgpg-staging-"

        /** Generates a fresh, non-exportable key that exists only in the Keystore. */
        fun create(): StagingCipher {
            val alias = ALIAS_PREFIX + UUID.randomUUID()
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // We supply the nonce ourselves; see the class doc for why that is safe here.
                .setRandomizedEncryptionRequired(false)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
                init(spec)
                generateKey()
            }
            return StagingCipher(alias)
        }

        /**
         * Deletes every staging key left behind by a process that died mid-decrypt. Safe to call
         * at startup before any [create] in this process: a fresh process has no in-flight
         * staging key yet, so every alias in this namespace at that point is necessarily orphaned.
         */
        fun destroyOrphans() {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            keyStore.aliases().asSequence().filter { it.startsWith(ALIAS_PREFIX) }.forEach {
                runCatching { keyStore.deleteEntry(it) }
            }
        }
    }
}

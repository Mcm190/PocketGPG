package com.pocketgpg.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPBEEncryptedData
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBEDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider

/**
 * Password-based OpenPGP (RFC 4880) encryption and decryption, equivalent to
 * `gpg --symmetric` / `gpg --decrypt`.
 *
 * Deliberately free of Android imports so the format work can be unit-tested on the JVM
 * against real GnuPG.
 */
object PgpCrypto {

    private const val BUFFER_SIZE = 1 shl 16
    private const val PROGRESS_STEP = 1L shl 20

    sealed class PgpError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class WrongPassphrase(cause: Throwable? = null) :
            PgpError("Wrong passphrase, or the file is damaged.", cause)

        class NotPasswordEncrypted :
            PgpError("This file is encrypted to a public key, not a passphrase. PocketGPG only handles passphrase-encrypted files.")

        class NotOpenPgp :
            PgpError("This does not look like an OpenPGP encrypted file.")

        class IntegrityFailure :
            PgpError("The file decrypted but failed its integrity check. It may be corrupt or tampered with.")

        class NoIntegrityProtection :
            PgpError("This file carries no integrity protection, so its contents cannot be trusted. GnuPG refuses these too.")
    }

    data class DecryptResult(
        val embeddedFileName: String?,
        val modifiedAt: Date?,
        val bytesWritten: Long,
        val integrityProtected: Boolean,
    )

    /**
     * Writes an OpenPGP message readable by `gpg -d`.
     *
     * [onProgress] receives running byte counts of the plaintext consumed; throwing from it
     * aborts the operation, which is how callers cancel.
     */
    fun encrypt(
        source: InputStream,
        destination: OutputStream,
        passphrase: CharArray,
        cipher: PgpCipher = PgpCipher.AES_256,
        compression: PgpCompression = PgpCompression.ZLIB,
        armor: Boolean = false,
        fileName: String = "",
        modifiedAt: Date = Date(),
        onProgress: (Long) -> Unit = {},
    ): Long {
        var written = 0L
        encryptTo(destination, passphrase, cipher, compression, armor, fileName, modifiedAt) { plaintext ->
            written = pump(source, plaintext, onProgress)
        }
        return written
    }

    /**
     * Opens an OpenPGP message and hands [writePlaintext] the stream that becomes its contents,
     * so callers can generate plaintext on the fly (a zip bundle, say) without ever staging it
     * on disk. The stream is closed for you; do not close it inside the block.
     */
    fun encryptTo(
        destination: OutputStream,
        passphrase: CharArray,
        cipher: PgpCipher = PgpCipher.AES_256,
        compression: PgpCompression = PgpCompression.ZLIB,
        armor: Boolean = false,
        fileName: String = "",
        modifiedAt: Date = Date(),
        writePlaintext: (OutputStream) -> Unit,
    ) {
        val random = SecureRandom()
        val encryptorBuilder = BcPGPDataEncryptorBuilder(cipher.tag)
            .setWithIntegrityPacket(true)
            .setSecureRandom(random)

        val encryptedDataGenerator = PGPEncryptedDataGenerator(encryptorBuilder)
        encryptedDataGenerator.addMethod(
            BcPBEKeyEncryptionMethodGenerator(
                passphrase,
                BcPGPDigestCalculatorProvider().get(S2K_DIGEST),
                S2K_ENCODED_COUNT,
            ).setSecureRandom(random)
        )

        val armoredOut = if (armor) ArmoredOutputStream(destination).apply { clearHeaders() } else null
        val outer: OutputStream = armoredOut ?: destination

        encryptedDataGenerator.open(outer, ByteArray(BUFFER_SIZE)).use { encryptedOut ->
            PGPCompressedDataGenerator(compression.tag)
                .open(encryptedOut, ByteArray(BUFFER_SIZE)).use { compressedOut ->
                    PGPLiteralDataGenerator().open(
                        compressedOut,
                        PGPLiteralData.BINARY,
                        sanitizeName(fileName),
                        modifiedAt,
                        ByteArray(BUFFER_SIZE),
                    ).use { literalOut ->
                        writePlaintext(literalOut)
                        literalOut.flush()
                    }
                }
        }
        armoredOut?.close()
        outer.flush()
    }

    /**
     * Reads an OpenPGP message produced by `gpg -c` (armored or binary) and writes the plaintext
     * to [destination]. Throws [PgpError.WrongPassphrase] for a bad passphrase.
     *
     * Note that [destination] receives plaintext that has not been authenticated yet: the
     * integrity check can only finish once the last byte has been read. Callers who hand the
     * result on to somewhere durable must write somewhere private and copy across only after
     * this returns, which is what [PgpError.IntegrityFailure] is there to prevent.
     */
    fun decrypt(
        source: InputStream,
        destination: OutputStream,
        passphrase: CharArray,
        onProgress: (Long) -> Unit = {},
    ): DecryptResult {
        val factory = PGPObjectFactory(PGPUtil.getDecoderStream(source), BcKeyFingerprintCalculator())

        var first = factory.nextObject() ?: throw PgpError.NotOpenPgp()
        if (first is PGPMarker) first = factory.nextObject() ?: throw PgpError.NotOpenPgp()
        val encryptedList = first as? PGPEncryptedDataList ?: throw PgpError.NotOpenPgp()

        val passphraseEncrypted = (0 until encryptedList.size())
            .map { encryptedList.get(it) }
            .filterIsInstance<PGPPBEEncryptedData>()
            .firstOrNull() ?: throw PgpError.NotPasswordEncrypted()

        // Checked before any plaintext exists. Without a modification detection code the
        // ciphertext is malleable, so what came out would be whatever an attacker chose rather
        // than what was encrypted. `gpg` fails the same file outright; so do we.
        if (!passphraseEncrypted.isIntegrityProtected) throw PgpError.NoIntegrityProtection()

        val decryptorFactory = BcPBEDataDecryptorFactory(passphrase, BcPGPDigestCalculatorProvider())
        val clearStream = try {
            passphraseEncrypted.getDataStream(decryptorFactory)
        } catch (e: PGPException) {
            throw PgpError.WrongPassphrase(e)
        }

        val literal: PGPLiteralData
        val written: Long
        try {
            literal = findLiteralData(clearStream)
            written = pump(literal.dataStream, destination, onProgress)
        } catch (e: PgpError) {
            throw e
        } catch (e: Exception) {
            // A correct passphrase essentially never yields a malformed inner stream; the
            // repeat-byte quick check just happened to pass on the wrong key.
            throw PgpError.WrongPassphrase(e)
        }

        if (!passphraseEncrypted.verify()) throw PgpError.IntegrityFailure()

        return DecryptResult(
            embeddedFileName = literal.fileName.takeIf { it.isNotEmpty() && it != PGPLiteralData.CONSOLE },
            modifiedAt = literal.modificationTime,
            bytesWritten = written,
            integrityProtected = true,
        )
    }

    enum class Recognition {
        /** Not an OpenPGP encrypted message: safe to encrypt, impossible to decrypt. */
        NotOpenPgp,
        PassphraseEncrypted,
        KeyEncrypted,
        /** Passphrase-encrypted, but with no modification detection code to check it against. */
        NotIntegrityProtected,
    }

    /** Reads only the first packet of [source] to work out what kind of file it is. */
    fun inspect(source: InputStream): Recognition = try {
        val factory = PGPObjectFactory(PGPUtil.getDecoderStream(source), BcKeyFingerprintCalculator())
        var first = factory.nextObject()
        if (first is PGPMarker) first = factory.nextObject()
        when (val list = first as? PGPEncryptedDataList) {
            null -> Recognition.NotOpenPgp
            else -> {
                val pbe = (0 until list.size())
                    .map { list.get(it) }
                    .filterIsInstance<PGPPBEEncryptedData>()
                    .firstOrNull()
                when {
                    pbe == null -> Recognition.KeyEncrypted
                    !pbe.isIntegrityProtected -> Recognition.NotIntegrityProtected
                    else -> Recognition.PassphraseEncrypted
                }
            }
        }
    } catch (_: Exception) {
        Recognition.NotOpenPgp
    }

    private fun findLiteralData(clearStream: InputStream): PGPLiteralData {
        var factory = PGPObjectFactory(clearStream, BcKeyFingerprintCalculator())
        var message = factory.nextObject()
        while (message != null) {
            when (message) {
                is PGPLiteralData -> return message
                is PGPCompressedData -> {
                    factory = PGPObjectFactory(message.dataStream, BcKeyFingerprintCalculator())
                    message = factory.nextObject()
                }
                else -> message = factory.nextObject()
            }
        }
        throw PgpError.NotOpenPgp()
    }

    private fun pump(source: InputStream, destination: OutputStream, onProgress: (Long) -> Unit): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var reported = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            destination.write(buffer, 0, read)
            total += read
            if (total - reported >= PROGRESS_STEP) {
                reported = total
                onProgress(total)
            }
        }
        destination.flush()
        onProgress(total)
        return total
    }

    private fun sanitizeName(name: String): String =
        name.substringAfterLast('/').take(200).ifEmpty { "data" }
}

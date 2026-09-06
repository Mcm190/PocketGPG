package com.pocketgpg.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PgpCryptoTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val passphrase = "correct horse battery staple".toCharArray()

    private fun plaintext(size: Int = 300_000): ByteArray =
        ByteArray(size).also { Random(42).nextBytes(it) }

    private fun encrypt(
        data: ByteArray,
        cipher: PgpCipher = PgpCipher.AES_256,
        compression: PgpCompression = PgpCompression.ZLIB,
        armor: Boolean = false,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        PgpCrypto.encrypt(
            source = ByteArrayInputStream(data),
            destination = out,
            passphrase = passphrase,
            cipher = cipher,
            compression = compression,
            armor = armor,
            fileName = "secret.bin",
        )
    }.toByteArray()

    @Test
    fun `round trips every cipher`() {
        val data = plaintext(50_000)
        for (cipher in PgpCipher.entries) {
            val out = ByteArrayOutputStream()
            val result = PgpCrypto.decrypt(ByteArrayInputStream(encrypt(data, cipher = cipher)), out, passphrase)
            assertArrayEquals("cipher ${cipher.label}", data, out.toByteArray())
            assertEquals("secret.bin", result.embeddedFileName)
            assertTrue(result.integrityProtected)
        }
    }

    @Test
    fun `round trips every compression setting`() {
        val data = plaintext(50_000)
        for (compression in PgpCompression.entries) {
            val out = ByteArrayOutputStream()
            PgpCrypto.decrypt(ByteArrayInputStream(encrypt(data, compression = compression)), out, passphrase)
            assertArrayEquals("compression ${compression.label}", data, out.toByteArray())
        }
    }

    @Test
    fun `round trips ascii armored output`() {
        val data = plaintext(20_000)
        val armored = encrypt(data, armor = true)
        assertTrue(String(armored).startsWith("-----BEGIN PGP MESSAGE-----"))
        val out = ByteArrayOutputStream()
        PgpCrypto.decrypt(ByteArrayInputStream(armored), out, passphrase)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `wrong passphrase is reported as such`() {
        val encrypted = encrypt(plaintext(10_000))
        val error = runCatching {
            PgpCrypto.decrypt(ByteArrayInputStream(encrypted), ByteArrayOutputStream(), "wrong".toCharArray())
        }.exceptionOrNull()
        assertTrue("was $error", error is PgpCrypto.PgpError.WrongPassphrase)
    }

    @Test
    fun `non-pgp input is rejected`() {
        val error = runCatching {
            PgpCrypto.decrypt(ByteArrayInputStream("hello world".toByteArray()), ByteArrayOutputStream(), passphrase)
        }.exceptionOrNull()
        assertTrue("was $error", error is PgpCrypto.PgpError)
    }

    @Test
    fun `inspect tells encrypted files apart from ordinary ones`() {
        assertEquals(
            PgpCrypto.Recognition.PassphraseEncrypted,
            PgpCrypto.inspect(ByteArrayInputStream(encrypt(plaintext(1_000)))),
        )
        assertEquals(
            PgpCrypto.Recognition.PassphraseEncrypted,
            PgpCrypto.inspect(ByteArrayInputStream(encrypt(plaintext(1_000), armor = true))),
        )
        assertEquals(
            PgpCrypto.Recognition.NotOpenPgp,
            PgpCrypto.inspect(ByteArrayInputStream("not pgp at all".toByteArray())),
        )
        assertEquals(
            PgpCrypto.Recognition.NotOpenPgp,
            PgpCrypto.inspect(ByteArrayInputStream(plaintext(4_000))),
        )
    }

    @Test
    fun `inspect flags key-encrypted files as needing a private key`() {
        assumeTrue(gpgAvailable())
        val home = File(temp.root, "keyhome").apply { mkdirs() }
        val batch = File(temp.root, "keyparams").apply {
            writeText(
                """
                %no-protection
                Key-Type: RSA
                Key-Length: 2048
                Name-Real: PocketGPG Test
                Name-Email: test@example.invalid
                %commit
                """.trimIndent()
            )
        }
        val generated = gpgIn(home, "--generate-key", batch.path)
        assumeTrue(generated.first == 0)

        val source = temp.newFile("for-key.bin").apply { writeBytes(plaintext(2_000)) }
        val encrypted = File(temp.root, "for-key.bin.gpg")
        val result = gpgIn(
            home, "--trust-model", "always", "--recipient", "test@example.invalid",
            "--encrypt", "--output", encrypted.path, source.path,
        )
        assertEquals(result.second, 0, result.first)

        assertEquals(
            PgpCrypto.Recognition.KeyEncrypted,
            PgpCrypto.inspect(encrypted.inputStream()),
        )
        val error = runCatching {
            PgpCrypto.decrypt(encrypted.inputStream(), ByteArrayOutputStream(), passphrase)
        }.exceptionOrNull()
        assertTrue("was $error", error is PgpCrypto.PgpError.NotPasswordEncrypted)
    }

    @Test
    fun `gnupg can decrypt what we produce`() {
        assumeTrue(gpgAvailable())
        val data = plaintext(200_000)
        for (armor in listOf(false, true)) {
            val encrypted = temp.newFile("ours-$armor.gpg").apply { writeBytes(encrypt(data, armor = armor)) }
            val decrypted = File(temp.root, "ours-$armor.out")
            val result = gpg("--output", decrypted.path, "--decrypt", encrypted.path)
            assertEquals("gpg failed (armor=$armor): ${result.second}", 0, result.first)
            assertArrayEquals(data, decrypted.readBytes())
        }
    }

    @Test
    fun `we can decrypt what gnupg produces`() {
        assumeTrue(gpgAvailable())
        val data = plaintext(200_000)
        val source = temp.newFile("theirs.bin").apply { writeBytes(data) }
        var covered = 0
        for (algo in listOf("AES256", "AES192", "AES", "TWOFISH", "CAMELLIA256", "CAST5", "3DES")) {
            for (armor in listOf(false, true)) {
                val encrypted = File(temp.root, "theirs-$algo-$armor.gpg")
                val args = mutableListOf("--symmetric", "--cipher-algo", algo, "--output", encrypted.path)
                if (armor) args.add("--armor")
                args.add(source.path)
                val result = gpg(*args.toTypedArray())
                // Distributions compile out older ciphers; skip those rather than fail on them.
                if (result.first != 0) {
                    assertTrue(
                        "gpg failed for $algo for an unexpected reason: ${result.second}",
                        result.second.contains("may not be used") || result.second.contains("Invalid cipher"),
                    )
                    continue
                }

                val out = ByteArrayOutputStream()
                PgpCrypto.decrypt(encrypted.inputStream(), out, passphrase)
                assertArrayEquals("$algo armor=$armor", data, out.toByteArray())
                covered++
            }
        }
        assertTrue("no gpg cipher was exercised", covered >= 4)
    }

    @Test
    fun `our string-to-key hardening survives a gnupg round trip`() {
        assumeTrue(gpgAvailable())
        val encrypted = temp.newFile("s2k.gpg").apply { writeBytes(encrypt(plaintext(1_000))) }
        val packets = gpg("--list-packets", encrypted.path)
        assertEquals(packets.second, 0, packets.first)
        // s2k 3 is salted-and-iterated; cipher 9 is AES-256; hash 8 is SHA-256.
        for (expected in listOf("s2k 3", "count $S2K_ITERATION_COUNT", "cipher 9", "hash 8", "mdc_method")) {
            assertTrue("expected '$expected' in:\n${packets.second}", packets.second.contains(expected))
        }
    }

    private fun gpgAvailable(): Boolean =
        runCatching { ProcessBuilder("gpg", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun gpg(vararg args: String): Pair<Int, String> =
        gpgIn(File(temp.root, "gnupghome"), *args)

    private fun gpgIn(home: File, vararg args: String): Pair<Int, String> {
        home.apply { mkdirs(); setReadable(false, false); setReadable(true, true) }
        val command = listOf(
            "gpg", "--batch", "--yes", "--quiet", "--no-tty",
            "--homedir", home.path,
            "--pinentry-mode", "loopback",
            "--passphrase", String(passphrase),
        ) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return process.exitValue() to output
    }
}

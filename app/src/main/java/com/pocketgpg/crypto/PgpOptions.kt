package com.pocketgpg.crypto

import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags

enum class PgpCipher(val tag: Int, val label: String, val detail: String) {
    AES_256(SymmetricKeyAlgorithmTags.AES_256, "AES-256", "Default. Strongest widely supported cipher."),
    AES_192(SymmetricKeyAlgorithmTags.AES_192, "AES-192", "AES with a 192-bit key."),
    AES_128(SymmetricKeyAlgorithmTags.AES_128, "AES-128", "Fastest AES variant, still strong."),
    CAMELLIA_256(SymmetricKeyAlgorithmTags.CAMELLIA_256, "Camellia-256", "AES alternative, common in Japan."),
    CAMELLIA_128(SymmetricKeyAlgorithmTags.CAMELLIA_128, "Camellia-128", "128-bit Camellia."),
    TWOFISH(SymmetricKeyAlgorithmTags.TWOFISH, "Twofish", "AES finalist, 256-bit key."),
    BLOWFISH(SymmetricKeyAlgorithmTags.BLOWFISH, "Blowfish", "Legacy. 64-bit block; avoid for large files."),
    TRIPLE_DES(SymmetricKeyAlgorithmTags.TRIPLE_DES, "3DES", "Legacy compatibility only. Slow and weak."),
    ;

    val isLegacy: Boolean get() = this == BLOWFISH || this == TRIPLE_DES
}

enum class PgpCompression(val tag: Int, val label: String, val detail: String) {
    ZLIB(CompressionAlgorithmTags.ZLIB, "ZLIB", "Default. Good ratio, universally supported."),
    ZIP(CompressionAlgorithmTags.ZIP, "ZIP", "Deflate. Slightly smaller header."),
    BZIP2(CompressionAlgorithmTags.BZIP2, "BZip2", "Smallest output, noticeably slower."),
    NONE(CompressionAlgorithmTags.UNCOMPRESSED, "None", "Best for already-compressed files (zip, jpg, mp4)."),
}

/**
 * OpenPGP encodes the string-to-key iteration count as a single octet. 0xFF is the largest
 * it can express, matching `gpg --s2k-count 65011712`, and costs a few hundred milliseconds
 * once per file rather than per block.
 */
const val S2K_ENCODED_COUNT: Int = 0xFF

/** The decoded form of [S2K_ENCODED_COUNT], as GnuPG reports it in `--list-packets`. */
const val S2K_ITERATION_COUNT: Int = 65011712

const val S2K_DIGEST: Int = HashAlgorithmTags.SHA256

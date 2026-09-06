package com.pocketgpg.crypto

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * Overwrites a document's bytes in place before deleting it, so the contents are not left
 * sitting in a filesystem block waiting to be undeleted.
 *
 * On flash storage this is best-effort by nature: the controller's wear levelling may map a
 * write to a fresh physical page and leave the original one intact until it is garbage
 * collected. The UI says so rather than promising more than this can deliver.
 */
object Shredder {

    private const val CHUNK = 1 shl 20

    sealed interface Outcome {
        data object Shredded : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun shred(
        context: Context,
        uri: Uri,
        passes: Int,
        onProgress: (Float) -> Unit = {},
    ): Outcome {
        val resolver = context.contentResolver
        val document = DocumentFile.fromSingleUri(context, uri)
            ?: return Outcome.Failed("no longer reachable")
        if (!document.canWrite()) return Outcome.Failed("no write permission for this location")

        val length = document.length()
        val random = SecureRandom()

        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: return Outcome.Failed("could not open for writing")
            descriptor.use {
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    val buffer = ByteArray(CHUNK)
                    val totalWork = (passes * length).coerceAtLeast(1)
                    var done = 0L
                    for (pass in 0 until passes) {
                        val zeroPass = pass == passes - 1
                        if (zeroPass) buffer.fill(0)
                        output.channel.position(0)
                        var remaining = length
                        while (remaining > 0) {
                            if (!zeroPass) random.nextBytes(buffer)
                            val take = minOf(remaining, buffer.size.toLong()).toInt()
                            output.write(buffer, 0, take)
                            remaining -= take
                            done += take
                            onProgress(done.toFloat() / totalWork)
                        }
                        output.flush()
                        descriptor.fileDescriptor.sync()
                    }
                    output.channel.truncate(0)
                    descriptor.fileDescriptor.sync()
                }
            }
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: "overwrite failed")
        }

        // Deliberately no rename-before-delete: a picked file carries a grant for that exact
        // document URI, so renaming produces a URI we may not delete, stranding an empty husk.
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .getOrElse { document.delete() }

        return if (deleted) Outcome.Shredded else Outcome.Failed("overwritten, but the file could not be deleted")
    }
}

package com.pocketgpg.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import androidx.documentfile.provider.DocumentFile

data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val writable: Boolean,
)

/**
 * `ActivityResultContracts.OpenMultipleDocuments` asks for read access only, which would leave
 * shredding impossible. This asks for write as well so the picked originals can be overwritten.
 */
class OpenWritableDocuments : ActivityResultContract<Array<String>, List<Uri>>() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (input.size == 1) input[0] else "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        intent.clipData?.let { clip ->
            return (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        }
        return listOfNotNull(intent.data)
    }
}

object Documents {

    fun describe(context: Context, uri: Uri): PickedFile {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { size = cursor.getLong(it) }
            }
        }
        val writable = runCatching { DocumentFile.fromSingleUri(context, uri)?.canWrite() }.getOrNull() ?: false
        return PickedFile(uri, name.ifEmpty { "file" }, size, writable)
    }

    /**
     * Android refuses to hand out a tree grant for Download or the storage root, so open the
     * picker already inside Download where creating a subfolder is one tap away.
     */
    fun downloadsHint(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Download",
    )

    fun folderLabel(context: Context, treeUri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()
            ?: DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').ifEmpty { "chosen folder" }

    fun canWriteTo(context: Context, treeUri: Uri): Boolean =
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.canWrite() }.getOrNull() ?: false

    /** Creates [displayName] inside [treeUri], returning null if the provider refuses. */
    fun createOutput(context: Context, treeUri: Uri, displayName: String): Uri? {
        val parent = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        // Octet-stream keeps the provider from rewriting the extension we asked for.
        return parent.createFile("application/octet-stream", displayName)?.uri
    }

    fun encryptedName(source: String, armored: Boolean): String =
        source + if (armored) ".asc" else ".gpg"

    fun decryptedName(source: String): String {
        val stripped = ENCRYPTED_SUFFIXES.firstOrNull { source.endsWith(it, ignoreCase = true) }
            ?.let { source.dropLast(it.length) }
        return stripped?.takeIf { it.isNotBlank() } ?: "$source.decrypted"
    }

    fun looksEncrypted(name: String): Boolean =
        ENCRYPTED_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    private val ENCRYPTED_SUFFIXES = listOf(".gpg", ".pgp", ".asc")
}

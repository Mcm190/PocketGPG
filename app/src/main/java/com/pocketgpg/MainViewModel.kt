package com.pocketgpg

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgpg.crypto.PgpCipher
import com.pocketgpg.crypto.PgpCompression
import com.pocketgpg.crypto.PgpCrypto
import com.pocketgpg.crypto.Shredder
import com.pocketgpg.data.Documents
import com.pocketgpg.data.PickedFile
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Mode { Encrypt, Decrypt }

data class Progress(
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String,
    val phase: String,
    val fraction: Float?,
)

data class FileOutcome(
    val sourceName: String,
    val outputName: String? = null,
    val outputUri: Uri? = null,
    val error: String? = null,
    val shredNote: String? = null,
) {
    val succeeded: Boolean get() = error == null
}

data class UiState(
    val mode: Mode = Mode.Encrypt,
    val files: List<PickedFile> = emptyList(),
    val passphrase: String = "",
    val confirmation: String = "",
    val cipher: PgpCipher = PgpCipher.AES_256,
    val compression: PgpCompression = PgpCompression.ZLIB,
    val armor: Boolean = false,
    val shredSource: Boolean = false,
    val shredPasses: Int = 3,
    val destination: Uri? = null,
    val destinationLabel: String? = null,
    val bundleAsZip: Boolean = false,
    val bundleName: String = defaultBundleName(),
    val bundleChoiceMade: Boolean = false,
    val bundlePromptVisible: Boolean = false,
    val progress: Progress? = null,
    val outcomes: List<FileOutcome> = emptyList(),
    val message: String? = null,
) {
    val running: Boolean get() = progress != null

    /** The zip question only makes sense when several files are on their way into one archive. */
    val canBundle: Boolean get() = mode == Mode.Encrypt && files.size > 1

    val blocker: String?
        get() = when {
            files.isEmpty() -> "Choose at least one file"
            destination == null -> "Choose where results are saved"
            passphrase.isEmpty() -> "Enter a passphrase"
            mode == Mode.Encrypt && passphrase != confirmation -> "Passphrases do not match"
            canBundle && bundleAsZip && bundleName.isBlank() -> "Name the archive"
            else -> null
        }
}

private fun defaultBundleName(): String =
    "PocketGPG-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("pocketgpg", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    private fun restoreState(): UiState {
        val saved = prefs.getString(KEY_DESTINATION, null)?.let(Uri::parse)
        val stillGranted = saved != null && getApplication<Application>().contentResolver
            .persistedUriPermissions.any { it.uri == saved && it.isWritePermission }
        val destination = saved?.takeIf { stillGranted }
        return UiState(
            cipher = runCatching { PgpCipher.valueOf(prefs.getString(KEY_CIPHER, "")!!) }
                .getOrDefault(PgpCipher.AES_256),
            compression = runCatching { PgpCompression.valueOf(prefs.getString(KEY_COMPRESSION, "")!!) }
                .getOrDefault(PgpCompression.ZLIB),
            armor = prefs.getBoolean(KEY_ARMOR, false),
            shredSource = prefs.getBoolean(KEY_SHRED, false),
            shredPasses = prefs.getInt(KEY_PASSES, 3),
            destination = destination,
            destinationLabel = destination?.let { Documents.folderLabel(getApplication(), it) },
        )
    }

    fun setMode(mode: Mode) = _state.update {
        if (it.running || it.mode == mode) it
        else it.copy(
            mode = mode,
            files = emptyList(),
            outcomes = emptyList(),
            message = null,
            bundleAsZip = false,
            bundleChoiceMade = false,
        )
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val mode = _state.value.mode
        viewModelScope.launch {
            val (accepted, rejected) = withContext(Dispatchers.IO) { screen(uris, mode) }
            _state.update { current ->
                val existing = current.files.map(PickedFile::uri).toSet()
                current.copy(
                    files = current.files + accepted.filterNot { it.uri in existing },
                    outcomes = emptyList(),
                    bundleChoiceMade = false,
                    message = when {
                        rejected.isEmpty() -> null
                        rejected.size == 1 -> rejected.first()
                        else -> "Skipped ${rejected.size} files — ${rejected.first()}"
                    },
                )
            }
        }
    }

    /**
     * Sniffs each pick so an already-encrypted file cannot be encrypted a second time, and a file
     * that is not an OpenPGP message cannot be queued for decryption. The extension is not
     * trusted — only the packet header is.
     */
    private fun screen(uris: List<Uri>, mode: Mode): Pair<List<PickedFile>, List<String>> {
        val context = getApplication<Application>()
        val accepted = mutableListOf<PickedFile>()
        val rejected = mutableListOf<String>()

        uris.forEach { uri ->
            val file = Documents.describe(context, uri)
            val recognition = runCatching {
                context.contentResolver.openInputStream(uri)?.use { PgpCrypto.inspect(it) }
            }.getOrNull() ?: PgpCrypto.Recognition.NotOpenPgp

            when {
                mode == Mode.Encrypt && recognition != PgpCrypto.Recognition.NotOpenPgp ->
                    rejected += "${file.name} is already encrypted"
                mode == Mode.Decrypt && recognition == PgpCrypto.Recognition.NotOpenPgp ->
                    rejected += "${file.name} is not an encrypted OpenPGP file"
                mode == Mode.Decrypt && recognition == PgpCrypto.Recognition.KeyEncrypted ->
                    rejected += "${file.name} needs a private key, not a passphrase"
                else -> accepted += file
            }
        }
        return accepted to rejected
    }

    fun removeFile(file: PickedFile) =
        _state.update { it.copy(files = it.files - file, bundleChoiceMade = false) }

    fun setBundleAsZip(value: Boolean) =
        _state.update { it.copy(bundleAsZip = value, bundleChoiceMade = true) }

    fun setBundleName(value: String) =
        _state.update { it.copy(bundleName = value.trim().removeSuffix(".zip")) }

    fun dismissBundlePrompt() = _state.update { it.copy(bundlePromptVisible = false) }

    /** Answers the "one archive or one file each?" question and gets straight on with the job. */
    fun answerBundlePrompt(bundle: Boolean) {
        _state.update { it.copy(bundleAsZip = bundle, bundleChoiceMade = true, bundlePromptVisible = false) }
        start()
    }

    fun setDestination(treeUri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        if (!Documents.canWriteTo(context, treeUri)) {
            _state.update { it.copy(message = "That folder is read-only. Try another one.") }
            return
        }
        prefs.edit().putString(KEY_DESTINATION, treeUri.toString()).apply()
        _state.update { it.copy(destination = treeUri, destinationLabel = Documents.folderLabel(context, treeUri)) }
    }

    fun setPassphrase(value: String) = _state.update { it.copy(passphrase = value, message = null) }

    fun setConfirmation(value: String) = _state.update { it.copy(confirmation = value, message = null) }

    fun generatePassphrase(): String {
        val random = SecureRandom()
        val generated = (0 until 6).joinToString("-") {
            (0 until 4).map { PASSPHRASE_ALPHABET[random.nextInt(PASSPHRASE_ALPHABET.length)] }.joinToString("")
        }
        _state.update { it.copy(passphrase = generated, confirmation = generated, message = null) }
        return generated
    }

    fun setCipher(value: PgpCipher) {
        prefs.edit().putString(KEY_CIPHER, value.name).apply()
        _state.update { it.copy(cipher = value) }
    }

    fun setCompression(value: PgpCompression) {
        prefs.edit().putString(KEY_COMPRESSION, value.name).apply()
        _state.update { it.copy(compression = value) }
    }

    fun setArmor(value: Boolean) {
        prefs.edit().putBoolean(KEY_ARMOR, value).apply()
        _state.update { it.copy(armor = value) }
    }

    fun setShredSource(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHRED, value).apply()
        _state.update { it.copy(shredSource = value) }
    }

    fun setShredPasses(value: Int) {
        prefs.edit().putInt(KEY_PASSES, value).apply()
        _state.update { it.copy(shredPasses = value) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun cancel() {
        job?.cancel()
    }

    fun start() {
        val snapshot = _state.value
        if (snapshot.running || snapshot.blocker != null) return

        if (snapshot.canBundle && !snapshot.bundleChoiceMade) {
            _state.update { it.copy(bundlePromptVisible = true) }
            return
        }

        _state.update { it.copy(outcomes = emptyList(), message = null) }
        job = viewModelScope.launch {
            val results = try {
                withContext(Dispatchers.IO) {
                    if (snapshot.canBundle && snapshot.bundleAsZip) bundle(snapshot) else process(snapshot)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(progress = null, message = "Cancelled.") }
                throw e
            }
            val failures = results.count { !it.succeeded }
            val cleared = failures == 0
            _state.update {
                it.copy(
                    progress = null,
                    outcomes = results,
                    files = if (cleared) emptyList() else it.files,
                    passphrase = if (cleared) "" else it.passphrase,
                    confirmation = if (cleared) "" else it.confirmation,
                    message = when {
                        failures > 0 -> "$failures of ${results.size} file${plural(results.size)} failed."
                        snapshot.canBundle && snapshot.bundleAsZip ->
                            "Encrypted ${snapshot.files.size} files into one archive."
                        snapshot.mode == Mode.Encrypt ->
                            "Encrypted ${results.size} file${plural(results.size)}."
                        else -> "Decrypted ${results.size} file${plural(results.size)}."
                    },
                )
            }
            job = null
        }
    }

    private suspend fun process(snapshot: UiState): List<FileOutcome> {
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        val destination = snapshot.destination ?: return emptyList()
        val passphrase = snapshot.passphrase.toCharArray()
        val encrypting = snapshot.mode == Mode.Encrypt
        val verb = if (encrypting) "Encrypting" else "Decrypting"
        val work = currentCoroutineContext()
        val results = mutableListOf<FileOutcome>()

        try {
            snapshot.files.forEachIndexed { index, file ->
                work.ensureActive()
                val outputName =
                    if (encrypting) Documents.encryptedName(file.name, snapshot.armor)
                    else Documents.decryptedName(file.name)

                report(index, snapshot.files.size, file.name, verb, 0f)

                var created: Uri? = null
                try {
                    val output = Documents.createOutput(context, destination, outputName)
                        ?: throw IllegalStateException("could not create $outputName in the chosen folder")
                    created = output

                    val onProgress: (Long) -> Unit = { processed ->
                        work.ensureActive()
                        report(
                            index, snapshot.files.size, file.name, verb,
                            if (file.size > 0) (processed.toFloat() / file.size).coerceIn(0f, 1f) else null,
                        )
                    }

                    var embeddedName: String? = null
                    val source = resolver.openInputStream(file.uri)
                        ?: throw IllegalStateException("could not read ${file.name}")
                    source.use { input ->
                        val sink = resolver.openOutputStream(output, "wt")
                            ?: throw IllegalStateException("could not write $outputName")
                        sink.use { target ->
                            if (encrypting) {
                                PgpCrypto.encrypt(
                                    source = input,
                                    destination = target,
                                    passphrase = passphrase,
                                    cipher = snapshot.cipher,
                                    compression = snapshot.compression,
                                    armor = snapshot.armor,
                                    fileName = file.name,
                                    onProgress = onProgress,
                                )
                            } else {
                                embeddedName =
                                    PgpCrypto.decrypt(input, target, passphrase, onProgress).embeddedFileName
                            }
                        }
                    }

                    var finalUri = output
                    val finalName = embeddedName
                        ?.takeIf { it != outputName && it.isNotBlank() && !it.contains('/') }
                        ?.let { wanted ->
                            runCatching { DocumentsContract.renameDocument(resolver, output, wanted) }
                                .getOrNull()
                                ?.also { finalUri = it }
                                ?.let { wanted }
                        }
                        ?: outputName

                    val shredNote = if (snapshot.shredSource) {
                        report(index, snapshot.files.size, file.name, "Shredding", null)
                        val outcome = Shredder.shred(context, file.uri, snapshot.shredPasses) { fraction ->
                            report(index, snapshot.files.size, file.name, "Shredding", fraction)
                        }
                        when (outcome) {
                            is Shredder.Outcome.Shredded ->
                                "Original shredded, ${snapshot.shredPasses} pass${plural(snapshot.shredPasses, "es")}"
                            is Shredder.Outcome.Failed -> "Original kept: ${outcome.reason}"
                        }
                    } else {
                        null
                    }

                    results += FileOutcome(file.name, finalName, finalUri, null, shredNote)
                } catch (e: CancellationException) {
                    created?.let { partial -> runCatching { DocumentsContract.deleteDocument(resolver, partial) } }
                    throw e
                } catch (e: Exception) {
                    created?.let { partial -> runCatching { DocumentsContract.deleteDocument(resolver, partial) } }
                    results += FileOutcome(file.name, error = e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            passphrase.fill(' ')
        }
        return results
    }

    /**
     * Streams every chosen file into one zip and encrypts that as a single message. The zip is
     * built straight into the OpenPGP stream, so the combined plaintext never touches storage.
     */
    private suspend fun bundle(snapshot: UiState): List<FileOutcome> {
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        val destination = snapshot.destination ?: return emptyList()
        val passphrase = snapshot.passphrase.toCharArray()
        val work = currentCoroutineContext()
        val archiveName = "${snapshot.bundleName}.zip"
        val outputName = Documents.encryptedName(archiveName, snapshot.armor)
        val label = "${snapshot.files.size} files"
        val totalBytes = snapshot.files.sumOf { it.size }.coerceAtLeast(1)

        var created: Uri? = null
        try {
            val output = Documents.createOutput(context, destination, outputName)
                ?: throw IllegalStateException("could not create $outputName in the chosen folder")
            created = output

            val sink = resolver.openOutputStream(output, "wt")
                ?: throw IllegalStateException("could not write $outputName")
            sink.use { target ->
                PgpCrypto.encryptTo(
                    destination = target,
                    passphrase = passphrase,
                    cipher = snapshot.cipher,
                    // The zip already deflates; a second pass would only cost time.
                    compression = PgpCompression.NONE,
                    armor = snapshot.armor,
                    fileName = archiveName,
                ) { plaintext ->
                    writeZip(snapshot, plaintext, work, totalBytes)
                }
            }

            val shredNote = if (snapshot.shredSource) shredAll(snapshot, work) else null
            return listOf(FileOutcome(label, outputName, output, null, shredNote))
        } catch (e: CancellationException) {
            created?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw e
        } catch (e: Exception) {
            created?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            return listOf(FileOutcome(label, error = e.message ?: e.javaClass.simpleName))
        } finally {
            passphrase.fill(' ')
        }
    }

    private fun writeZip(
        snapshot: UiState,
        plaintext: OutputStream,
        work: kotlin.coroutines.CoroutineContext,
        totalBytes: Long,
    ) {
        val resolver = getApplication<Application>().contentResolver
        val zip = ZipOutputStream(plaintext)
        val taken = mutableSetOf<String>()
        val buffer = ByteArray(1 shl 16)
        var done = 0L

        snapshot.files.forEachIndexed { index, file ->
            work.ensureActive()
            zip.putNextEntry(ZipEntry(uniqueEntryName(file.name, taken)))
            val input = resolver.openInputStream(file.uri)
                ?: throw IllegalStateException("could not read ${file.name}")
            input.use { source ->
                while (true) {
                    work.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    zip.write(buffer, 0, read)
                    done += read
                    report(
                        index, snapshot.files.size, file.name, "Adding to archive",
                        (done.toFloat() / totalBytes).coerceIn(0f, 1f),
                    )
                }
            }
            zip.closeEntry()
        }
        // finish() writes the central directory but leaves closing the OpenPGP chain to PgpCrypto.
        zip.finish()
    }

    private fun shredAll(snapshot: UiState, work: kotlin.coroutines.CoroutineContext): String {
        val context = getApplication<Application>()
        var shredded = 0
        val problems = mutableListOf<String>()
        snapshot.files.forEachIndexed { index, file ->
            work.ensureActive()
            report(index, snapshot.files.size, file.name, "Shredding", null)
            when (val outcome = Shredder.shred(context, file.uri, snapshot.shredPasses) { fraction ->
                report(index, snapshot.files.size, file.name, "Shredding", fraction)
            }) {
                is Shredder.Outcome.Shredded -> shredded++
                is Shredder.Outcome.Failed -> problems += "${file.name}: ${outcome.reason}"
            }
        }
        return when {
            problems.isEmpty() -> "$shredded original${plural(shredded)} shredded"
            shredded == 0 -> "Originals kept: ${problems.first()}"
            else -> "$shredded shredded, ${problems.size} kept (${problems.first()})"
        }
    }

    private fun uniqueEntryName(name: String, taken: MutableSet<String>): String {
        val safe = name.substringAfterLast('/').ifEmpty { "file" }
        if (taken.add(safe)) return safe
        val stem = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var counter = 2
        while (!taken.add("$stem ($counter)$extension")) counter++
        return "$stem ($counter)$extension"
    }

    private fun report(index: Int, count: Int, name: String, phase: String, fraction: Float?) =
        _state.update { it.copy(progress = Progress(index, count, name, phase, fraction)) }

    private fun plural(count: Int, suffix: String = "s") = if (count == 1) "" else suffix

    private companion object {
        const val KEY_DESTINATION = "destination"
        const val KEY_CIPHER = "cipher"
        const val KEY_COMPRESSION = "compression"
        const val KEY_ARMOR = "armor"
        const val KEY_SHRED = "shred"
        const val KEY_PASSES = "passes"
        const val PASSPHRASE_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}

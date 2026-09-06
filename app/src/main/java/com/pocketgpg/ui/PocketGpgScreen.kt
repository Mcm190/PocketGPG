package com.pocketgpg.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketgpg.FileOutcome
import com.pocketgpg.MainViewModel
import com.pocketgpg.Mode
import com.pocketgpg.crypto.PgpCipher
import com.pocketgpg.crypto.PgpCompression
import com.pocketgpg.data.Documents
import com.pocketgpg.data.OpenWritableDocuments
import kotlin.math.ln
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketGpgScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    var showAbout by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(OpenWritableDocuments()) { viewModel.addFiles(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setDestination)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    if (showAbout) AboutSheet(onDismiss = { showAbout = false })

    if (state.bundlePromptVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissBundlePrompt,
            icon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
            title = { Text("Combine into one archive?") },
            text = {
                Text(
                    "You picked ${state.files.size} files. PocketGPG can zip them together and encrypt " +
                        "the archive as a single file, or encrypt each file on its own.\n\n" +
                        "Decrypting an archive gives back the .zip, which you open yourself.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.answerBundlePrompt(true) }) { Text("One .zip.gpg") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerBundlePrompt(false) }) { Text("Separate files") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PocketGPG", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        bottomBar = { ActionBar(viewModel) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeSelector(state.mode, state.running) { viewModel.setMode(it) }

            FilesCard(viewModel, onAdd = { pickFiles.launch(arrayOf("*/*")) })

            PassphraseCard(viewModel)

            if (state.mode == Mode.Encrypt) {
                SectionCard("Encryption", Icons.Default.Tune) {
                    OptionDropdown(
                        label = "Cipher",
                        options = PgpCipher.entries,
                        selected = state.cipher,
                        labelOf = { it.label },
                        detailOf = { it.detail },
                        enabled = !state.running,
                        onSelect = viewModel::setCipher,
                    )
                    OptionDropdown(
                        label = "Compression",
                        options = PgpCompression.entries,
                        selected = state.compression,
                        labelOf = { it.label },
                        detailOf = { it.detail },
                        enabled = !state.running,
                        onSelect = viewModel::setCompression,
                    )
                    SwitchRow(
                        title = "ASCII armour",
                        subtitle = if (state.armor) {
                            "Output is text you can paste into a message (.asc)"
                        } else {
                            "Output is compact binary (.gpg)"
                        },
                        checked = state.armor,
                        enabled = !state.running,
                        onCheckedChange = viewModel::setArmor,
                    )
                }
            }

            SectionCard("Save results to", Icons.Default.Folder) {
                Text(
                    state.destinationLabel ?: "No folder chosen yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.destinationLabel == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (state.destination == null) {
                    Text(
                        "Android will not hand out the whole Download folder, so pick a subfolder. " +
                            "Tap Create new folder in the picker and call it PocketGPG. " +
                            "It is remembered after that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { pickFolder.launch(Documents.downloadsHint()) },
                    enabled = !state.running,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.destination == null) "Choose folder" else "Change folder")
                }
            }

            ShredCard(viewModel)

            if (state.outcomes.isNotEmpty()) {
                ResultsCard(state.outcomes) { outcome ->
                    outcome.outputUri?.let { share(context, it, outcome.outputName.orEmpty()) }
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: Mode, running: Boolean, onSelect: (Mode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == Mode.Encrypt,
            onClick = { onSelect(Mode.Encrypt) },
            enabled = !running,
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) {
            Text("Encrypt")
        }
        SegmentedButton(
            selected = mode == Mode.Decrypt,
            onClick = { onSelect(Mode.Decrypt) },
            enabled = !running,
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = { Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) {
            Text("Decrypt")
        }
    }
}

@Composable
private fun FilesCard(viewModel: MainViewModel, onAdd: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode = state.mode
    val files = state.files
    val enabled = !state.running
    val onRemove = viewModel::removeFile

    SectionCard(
        title = if (mode == Mode.Encrypt) "Files to encrypt" else "Files to decrypt",
        icon = Icons.Default.Description,
        trailing = {
            if (files.isNotEmpty()) {
                Text(
                    "${files.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) {
        if (files.isEmpty()) {
            Text(
                if (mode == Mode.Encrypt) {
                    "Pick any files from your phone. Each one becomes its own .gpg file."
                } else {
                    "Pick the .gpg or .asc files you were sent."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            files.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Text(
                            formatBytes(file.size) + if (!file.writable) " · read-only" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(file) }, enabled = enabled) {
                        Icon(Icons.Default.Close, contentDescription = "Remove ${file.name}")
                    }
                }
            }
        }
        OutlinedButton(onClick = onAdd, enabled = enabled) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (files.isEmpty()) "Choose files" else "Add more")
        }

        if (state.canBundle) {
            HorizontalDivider()
            SwitchRow(
                title = "Combine into one .zip",
                subtitle = if (state.bundleAsZip) {
                    "One encrypted archive. Decrypting gives back the .zip for you to open."
                } else {
                    "Each file becomes its own .gpg"
                },
                checked = state.bundleAsZip,
                enabled = enabled,
                onCheckedChange = viewModel::setBundleAsZip,
            )
            AnimatedVisibility(state.bundleAsZip) {
                OutlinedTextField(
                    value = state.bundleName,
                    onValueChange = viewModel::setBundleName,
                    label = { Text("Archive name") },
                    singleLine = true,
                    enabled = enabled,
                    suffix = { Text(if (state.armor) ".zip.asc" else ".zip.gpg") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PassphraseCard(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    val encrypting = state.mode == Mode.Encrypt

    SectionCard("Passphrase", Icons.Default.Password) {
        OutlinedTextField(
            value = state.passphrase,
            onValueChange = viewModel::setPassphrase,
            label = { Text("Passphrase") },
            singleLine = true,
            enabled = !state.running,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "Hide passphrase" else "Show passphrase",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (encrypting) {
            OutlinedTextField(
                value = state.confirmation,
                onValueChange = viewModel::setConfirmation,
                label = { Text("Confirm passphrase") },
                singleLine = true,
                enabled = !state.running,
                isError = state.confirmation.isNotEmpty() && state.confirmation != state.passphrase,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            val (fraction, label, tint) = passphraseStrength(state.passphrase)
            if (state.passphrase.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        color = tint,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                    Text(
                        "Rough strength: $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TextButton(onClick = { viewModel.generatePassphrase(); visible = true }, enabled = !state.running) {
                Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Generate a strong one")
            }
            Text(
                "Write it down before you encrypt. Nothing here can recover a lost passphrase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShredCard(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val originals = if (state.mode == Mode.Encrypt) "original files" else "encrypted files"

    SectionCard("Shred after finishing", Icons.Default.DeleteForever) {
        SwitchRow(
            title = "Shred the $originals",
            subtitle = "Overwrite the contents, then delete, not just a plain delete",
            checked = state.shredSource,
            enabled = !state.running,
            onCheckedChange = viewModel::setShredSource,
        )
        AnimatedVisibility(state.shredSource) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Overwrite passes", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(1, 3, 7).forEachIndexed { index, passes ->
                        SegmentedButton(
                            selected = state.shredPasses == passes,
                            onClick = { viewModel.setShredPasses(passes) },
                            enabled = !state.running,
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                        ) {
                            Text("$passes")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "On flash storage, wear levelling can leave the old cells readable. This makes " +
                            "casual recovery hard; it is not a guarantee.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsCard(outcomes: List<FileOutcome>, onShare: (FileOutcome) -> Unit) {
    SectionCard("Results", Icons.Default.CheckCircle) {
        outcomes.forEachIndexed { index, outcome ->
            if (index > 0) HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (outcome.succeeded) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (outcome.succeeded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        outcome.outputName ?: outcome.sourceName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    val detail = outcome.error?.let { "Failed: $it" }
                        ?: listOfNotNull("from ${outcome.sourceName}", outcome.shredNote).joinToString(" · ")
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outcome.succeeded) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (outcome.succeeded && outcome.outputUri != null) {
                    IconButton(onClick = { onShare(outcome) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share ${outcome.outputName}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionBar(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress = state.progress

    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress != null) {
                Text(
                    "${progress.phase} ${progress.fileName}  (${progress.fileIndex + 1}/${progress.fileCount})",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (progress.fraction != null) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = viewModel::cancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            } else {
                state.blocker?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = viewModel::start,
                    enabled = state.blocker == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        if (state.mode == Mode.Encrypt) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.mode == Mode.Encrypt) "Encrypt" else "Decrypt",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun passphraseStrength(value: String): Triple<Float, String, Color> {
    if (value.isEmpty()) return Triple(0f, "", MaterialTheme.colorScheme.outline)
    var pool = 0
    if (value.any { it.isLowerCase() }) pool += 26
    if (value.any { it.isUpperCase() }) pool += 26
    if (value.any { it.isDigit() }) pool += 10
    if (value.any { !it.isLetterOrDigit() }) pool += 33
    val bits = value.length * ln(pool.coerceAtLeast(2).toDouble()) / ln(2.0)
    val fraction = min(1.0, bits / 128.0).toFloat()
    return when {
        bits < 45 -> Triple(fraction, "weak", MaterialTheme.colorScheme.error)
        bits < 70 -> Triple(fraction, "fair", Color(0xFFE58C00))
        bits < 100 -> Triple(fraction, "strong", MaterialTheme.colorScheme.primary)
        else -> Triple(fraction, "very strong", MaterialTheme.colorScheme.primary)
    }
}

private fun share(context: Context, uri: Uri, name: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, if (name.isEmpty()) "Share file" else "Share $name"))
}

package com.pocketgpg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketgpg.R
import com.pocketgpg.ui.theme.BrandBlue

private const val COFFEE_URL = "https://buymeacoffee.com/1900xd"
private const val SOURCE_URL = "https://github.com/Mcm190/PocketGPG"
private const val PRIVACY_URL = "https://mcm190.github.io/pocketgpg-privacy/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "1.0"
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The launcher mipmap is an adaptive-icon XML, which painterResource cannot load,
                // so compose the same mark over a brand circle instead.
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(BrandBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("PocketGPG", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Version $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AboutParagraph(
                "Standard OpenPGP",
                "Files are encrypted with your passphrase into ordinary OpenPGP messages. " +
                    "Anyone can open them with `gpg -d` on a desktop, or with any OpenPGP tool — " +
                    "nothing here is a proprietary format. Encryption is done by Bouncy Castle's OpenPGP " +
                    "implementation, the same library behind OpenKeychain.",
            )

            AboutParagraph(
                "Your passphrase never leaves the phone",
                "There is no account, no network access and no telemetry. The key is derived from your " +
                    "passphrase on the device with a fully iterated salted SHA-256 function, so a stolen " +
                    ".gpg file is only as strong as the passphrase you chose.",
            )

            AboutParagraph(
                "About shredding",
                "Shredding overwrites the file's bytes before deleting it. On phone flash storage " +
                    "the controller may quietly write those passes to fresh cells and leave the originals " +
                    "readable until they are garbage collected, so treat it as making casual recovery hard " +
                    "rather than as a guarantee against a forensic lab.",
            )

            AboutParagraph(
                "Getting files off the phone",
                "Encrypted files land in the folder you pick, so plugging into a computer over USB " +
                    "(or opening that folder in any file manager) is enough to send them on. " +
                    "Share hands the file to whichever app you choose — WhatsApp, email, Bluetooth — " +
                    "and that app does the sending. PocketGPG holds no internet permission at all, " +
                    "so it cannot transmit anything on its own.",
            )

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(onClick = { uriHandler.openUri(SOURCE_URL) }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Source code")
                }
                TextButton(onClick = { uriHandler.openUri(PRIVACY_URL) }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Privacy")
                }
            }

            Button(
                onClick = { uriHandler.openUri(COFFEE_URL) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFDD00),
                    contentColor = Color(0xFF0D0C22),
                ),
            ) {
                Icon(Icons.Default.Coffee, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Buy me a coffee", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AboutParagraph(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

package dev.vela.app

import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- identity -------------------------------------------------------------

private val Amber = Color(0xFFE3A13C)
private val Ink = Color(0xFF241A06)
private val NearBlack = Color(0xFF141010)
private val Surface = Color(0xFF1B1614)
private val Line = Color(0xFF2E2521)
private val Text = Color(0xFFF0EAE3)
private val Muted = Color(0xFF9A8B80)

private val VelaColors = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    background = NearBlack,
    onBackground = Text,
    surface = Surface,
    onSurface = Text,
    onSurfaceVariant = Muted,
)

private enum class Tab(val label: String) { Versions("Versions"), Mods("Mods"), Launch("Launch") }

// ---- entry ----------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VelaApp() }
    }
}

@Composable
private fun VelaApp() {
    MaterialTheme(colorScheme = VelaColors) {
        var tab by remember { mutableStateOf(Tab.Versions) }

        Scaffold(
            containerColor = NearBlack,
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().background(Surface).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Tab.entries.forEach { entry ->
                        TextButton(onClick = { tab = entry }) {
                            Text(
                                entry.label,
                                color = if (tab == entry) Amber else Muted,
                                fontWeight = if (tab == entry) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(20.dp)) {
                    when (tab) {
                        Tab.Versions -> VersionsScreen()
                        Tab.Mods -> ModsScreen()
                        Tab.Launch -> LaunchScreen()
                    }
                }
            }
        }
    }
}

// ---- versions -------------------------------------------------------------

@Composable
private fun VersionsScreen() {
    val context = LocalContext.current
    var versions by remember { mutableStateOf(VersionStore.all(context)) }
    var status by remember { mutableStateOf("") }

    val pickApk = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val imported = try {
            VersionStore.import(context, uri)
        } catch (t: Throwable) {
            null
        }
        versions = VersionStore.all(context)
        status = if (imported != null) {
            "Imported ${imported.versionName}"
        } else {
            "That file could not be read as a Minecraft APK"
        }
        Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
    }

    Header("Versions", if (versions.isEmpty()) "Nothing imported" else "${versions.size} imported")

    OutlinedButton(
        onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) { Text("Import Minecraft APK", color = Amber) }

    if (status.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(status, color = Muted, fontSize = 13.sp)
    }

    Spacer(Modifier.height(16.dp))

    if (versions.isEmpty()) {
        EmptyState(
            "No versions yet",
            "Import a Minecraft APK and it will be listed here. Nothing is installed " +
                "or modified; the file is copied into this app's own storage.",
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(versions) { version ->
            Card {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(version.packageName, fontWeight = FontWeight.Medium, color = Text)
                        Text(
                            "${version.versionName} · ${version.sizeBytes / (1024 * 1024)} MB · " +
                                SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(version.importedAt)),
                            color = Muted,
                            fontSize = 13.sp,
                        )
                    }
                    TextButton(onClick = {
                        VersionStore.delete(context, version.id)
                        versions = VersionStore.all(context)
                    }) { Text("Remove", color = Muted) }
                }
            }
        }
    }
}

// ---- mods -----------------------------------------------------------------

@Composable
private fun ModsScreen() {
    Header("Mods", "Not available yet")
    EmptyState(
        "No mod support in this build",
        "Mod loading is not part of this milestone. Nothing here is a placeholder " +
            "switch that pretends to work: when mod support exists, it will appear " +
            "on this screen with the mods it can actually load.",
    )
}

// ---- launch ---------------------------------------------------------------

@Composable
private fun LaunchScreen() {
    val context = LocalContext.current
    val gamePackage = "com.mojang.minecraftpe"
    var message by remember { mutableStateOf("") }

    val installed = remember {
        try {
            context.packageManager.getPackageInfo(gamePackage, 0) != null
        } catch (t: Throwable) {
            false
        }
    }

    Header("Launch", if (installed) "Minecraft is installed" else "Minecraft is not installed")

    if (!installed) {
        EmptyState(
            "Nothing to launch",
            "This opens the Minecraft already installed on this device from the Play " +
                "Store. It is not installed, so there is nothing to open yet.",
        )
        return
    }

    Text(
        "This opens the Minecraft installed on this device. It does not modify it, " +
            "and no imported APK is used here.",
        color = Muted,
        fontSize = 14.sp,
    )

    Spacer(Modifier.height(14.dp))

    Button(
        onClick = {
            val intent = context.packageManager.getLaunchIntentForPackage(gamePackage)
            if (intent == null) {
                message = "Minecraft has no launchable activity on this device."
            } else {
                try {
                    context.startActivity(intent)
                    message = "Opened Minecraft"
                } catch (t: Throwable) {
                    message = "Could not open Minecraft: ${t.message}"
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
    ) { Text("Open Minecraft", fontWeight = FontWeight.SemiBold) }

    if (message.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(message, color = Muted, fontSize = 13.sp)
    }
}

// ---- pieces ---------------------------------------------------------------

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Text)
        Text(subtitle, fontSize = 14.sp, color = Muted)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(14.dp),
    ) { content() }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Card {
        Column {
            Text(title, fontWeight = FontWeight.Medium, color = Text)
            Spacer(Modifier.height(6.dp))
            Text(body, color = Muted, fontSize = 13.sp)
        }
    }
}

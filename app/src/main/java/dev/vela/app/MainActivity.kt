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
import java.io.File
import androidx.compose.material3.Switch
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

private enum class Tab(val label: String) { Versions("Versions"), Packs("Packs"), Launch("Launch") }

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
                        Tab.Packs -> PacksScreen()
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
    var log by remember { mutableStateOf(BoundaryLog.read(context.filesDir)) }
    var running by remember { mutableStateOf(false) }

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

    // No import needed: this loads a library out of the installed game's own
    // nativeLibraryDir, which is a directory the platform already manages.
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        enabled = !running,
        onClick = {
            running = true
            log = "running on the installed game..."
            val pm = context.packageManager
            Thread {
                BoundaryProbe.runInstalledGameCheck(context.filesDir, pm)
                log = BoundaryLog.read(context.filesDir)
                running = false
            }.start()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) { Text(if (running) "Running..." else "Run boundary check (installed game)", color = Amber) }

    if (status.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(status, color = Muted, fontSize = 13.sp)
    }

    if (log.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Boundary log", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Vela boundary log", log)
                )
                Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy", color = Amber) }
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp)
                .background(Surface, RoundedCornerShape(10.dp)).padding(12.dp),
        ) {
            Text(
                log,
                color = Text,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
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
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = {
                            VersionStore.delete(context, version.id)
                            versions = VersionStore.all(context)
                        }) { Text("Remove", color = Muted) }
                        TextButton(
                            enabled = !running,
                            onClick = {
                                running = true
                                log = "running..."
                                val apk = version.apk(context)
                                val libs = File(File(context.filesDir, "versions"), "${version.id}/libs")
                                Thread {
                                    BoundaryProbe.runPhaseA(context.filesDir, apk, version.id, libs)
                                    log = BoundaryLog.read(context.filesDir)
                                    running = false
                                }.start()
                            },
                        ) { Text("Run boundary check", color = Amber) }
                    }
                }
            }
        }
    }
}

// ---- mods -----------------------------------------------------------------

@Composable
private fun PacksScreen() {
    val context = LocalContext.current
    val profiles = remember { ProfileRepository(File(context.filesDir, "profiles")) }
    val repo = remember { PackRepository(profiles) }

    // The app needs somewhere to put packs, so a default profile is created on
    // first use rather than showing an empty screen with no way forward.
    val profile = remember {
        profiles.active() ?: profiles.create("Main")
    }

    var packs by remember { mutableStateOf(repo.list(profile.id)) }
    var status by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<InstalledPack?>(null) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun refresh() {
        packs = repo.list(profile.id)
    }

    val pickPack = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "Importing..."
        val work = File(context.cacheDir, "pack-import").also { it.mkdirs() }
        val incoming = File(work, "incoming.mcpack")
        val result = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                incoming.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            PackImporter.import(incoming, repo.packsDir(profile.id), work)
        } catch (t: Throwable) {
            PackImportResult.Rejected("The import failed: ${t.message}")
        } finally {
            incoming.delete()
        }

        status = when (result) {
            is PackImportResult.Imported -> "Imported ${result.manifest.name}"
            is PackImportResult.Rejected -> result.reason
        }
        refresh()
    }

    Header("Packs", if (packs.isEmpty()) "No packs installed" else "${packs.size} installed")

    OutlinedButton(
        onClick = { pickPack.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) { Text("Import a pack (.mcpack or .zip)", color = Amber) }

    if (status.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(status, color = Muted, fontSize = 13.sp)
    }

    Spacer(Modifier.height(14.dp))

    if (packs.isEmpty()) {
        EmptyState(
            "Nothing installed yet",
            "Import a .mcpack or .zip. Vela reads its manifest and shows what the " +
                "pack declares, so you can see whether it is what you expected.",
        )
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(packs) { pack ->
                Card {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(pack.displayName, color = Text, fontWeight = FontWeight.Medium)
                                Text(
                                    pack.versionLabel + " · " +
                                        (pack.types.joinToString(", ").ifEmpty { "no modules" }),
                                    color = Muted,
                                    fontSize = 13.sp,
                                )
                            }
                            Switch(
                                checked = pack.enabled,
                                onCheckedChange = { on ->
                                    repo.setEnabled(profile.id, pack.uuid, on)
                                    refresh()
                                },
                            )
                        }

                        if (pack.problem != null) {
                            Text(pack.problem, color = Color(0xFFE06C5A), fontSize = 13.sp)
                        } else if (pack.newerFormat) {
                            Text(
                                "This pack declares a newer format than this build understands.",
                                color = Amber,
                                fontSize = 13.sp,
                            )
                        }

                        Row(Modifier.padding(top = 6.dp)) {
                            TextButton(onClick = {
                                expanded = if (pack.uuid in expanded) expanded - pack.uuid
                                else expanded + pack.uuid
                            }) {
                                Text(
                                    if (pack.uuid in expanded) "Hide contents" else "What's inside",
                                    color = Amber,
                                )
                            }
                            TextButton(onClick = { pendingDelete = pack }) {
                                Text("Delete", color = Muted)
                            }
                            Text(
                                if (pack.enabled) "enabled" else "disabled",
                                color = Muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 12.dp, start = 8.dp),
                            )
                        }

                        if (pack.uuid in expanded) {
                            val report = remember(pack.uuid, pack.directory.path) {
                                PackIntegrity.report(pack.directory, pack.manifest, pack.problem)
                            }
                            Column(Modifier.padding(top = 6.dp)) {
                                ReportLine("Modules", report.moduleTypes.joinToString(", ").ifEmpty { "none" })
                                ReportLine(
                                    "Textures",
                                    when (report.textureCount) {
                                        null -> "no textures/ folder"
                                        0 -> "textures/ is empty"
                                        else -> "${report.textureCount} files"
                                    },
                                )
                                ReportLine("Files", "${report.fileCount} · ${report.sizeLabel()}")
                                ReportLine("Version", report.version)
                                for (note in report.notes) {
                                    Text(
                                        "· $note",
                                        color = Muted,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${toDelete.displayName}?") },
            text = {
                Text(
                    "This removes the pack from this profile, including the copy kept " +
                        "while it is disabled. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(profile.id, toDelete.uuid)
                    pendingDelete = null
                    refresh()
                    status = "Deleted ${toDelete.displayName}"
                }) { Text("Delete", color = Color(0xFFE06C5A)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel", color = Muted) }
            },
        )
    }
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
private fun ReportLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Text, fontSize = 13.sp)
    }
}

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

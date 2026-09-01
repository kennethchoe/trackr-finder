package dev.kchoe.trackrfinder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var scanner: TrackrScanner
    private lateinit var ringer: Ringer
    private lateinit var prefs: Prefs

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = TrackrScanner(this)
        ringer = Ringer(this)
        prefs = Prefs(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    @Composable
    private fun Screen() {
        var granted by remember { mutableStateOf(hasPermissions()) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted = hasPermissions() }

        val sightings by scanner.sightings.collectAsState()
        var status by remember { mutableStateOf<String?>(null) }
        var ringing by remember { mutableStateOf<String?>(null) }
        var batteries by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
        var watched by remember { mutableStateOf(prefs.watchedAddress) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var nicknames by remember { mutableStateOf(prefs.allNicknames()) }
        var renaming by remember { mutableStateOf<Sighting?>(null) }
        var lastSeenAt by remember { mutableLongStateOf(prefs.lastSeenAt) }
        var lastLoc by remember {
            mutableStateOf(if (prefs.hasLocation) prefs.lastLat to prefs.lastLon else null)
        }

        // Foreground discovery scan, live only while this screen is up.
        DisposableEffect(granted) {
            if (granted) scanner.start()
            onDispose { scanner.stop() }
        }
        LaunchedEffect(granted) {
            while (granted) {
                delay(1000)
                scanner.expireOlderThan(20_000)
                now = System.currentTimeMillis()
                lastSeenAt = prefs.lastSeenAt
                lastLoc = if (prefs.hasLocation) prefs.lastLat to prefs.lastLon else null
            }
        }

        // targetSdk 35+ draws edge-to-edge, so without this the title sits
        // under the status bar clock.
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 20.dp)
        ) {
            val found = sightings.size
            Header(
                title = "TrackR Finder",
                status = when {
                    !scanner.bluetoothEnabled -> "Bluetooth is off"
                    !granted -> "Permissions needed"
                    found == 0 -> "Scanning…"
                    found == 1 -> "Scanning · 1 nearby"
                    else -> "Scanning · $found nearby"
                },
                scanning = granted && scanner.bluetoothEnabled,
                foundCount = found,
            )
            Spacer(Modifier.height(20.dp))

            if (!granted) {
                Button(onClick = { launcher.launch(requiredPermissions()) }) {
                    Text("Grant Bluetooth & location")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Location is used only to record where the tracker was last "
                        + "seen. Nothing leaves the phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            status?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }

            // When the watched tracker goes quiet it drops out of the scan list
            // entirely -- which is exactly when its last known position matters.
            val w = watched
            if (w != null && w !in sightings) {
                LastSeenPanel(
                    label = nicknames[w] ?: prefs.watchedName ?: w,
                    lastSeenAt = lastSeenAt,
                    location = lastLoc,
                    onOpenMap = {
                        lastLoc?.let { (lat, lon) ->
                            val name = Uri.encode(nicknames[w] ?: prefs.watchedName ?: "Tracker")
                            runCatching {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("geo:$lat,$lon?q=$lat,$lon($name)"),
                                    )
                                )
                            }.onFailure { status = "No map app installed" }
                        }
                    },
                    onUnwatch = {
                        prefs.watchedAddress = null
                        watched = null
                        WatchService.stop(this@MainActivity)
                        status = "Alerts off"
                    },
                )
                Spacer(Modifier.height(16.dp))
            }

            val list = sightings.values.sortedByDescending { it.rssi }
            if (list.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Nothing yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A Pixel that has sat unused for years is most likely out of "
                        + "battery — it takes a CR2016. Press its button to wake it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.address }) { s ->
                    DeviceCard(
                        sighting = s,
                        label = nicknames[s.address] ?: s.name,
                        battery = batteries[s.address],
                        isWatched = watched == s.address,
                        onRename = { renaming = s },
                        isRinging = ringing == s.address,
                        now = now,
                        onRing = {
                            ringing = s.address
                            status = "Connecting…"
                            ringer.ring(s.address) { result ->
                                ringing = null
                                status = when (result) {
                                    is RingResult.Success -> {
                                        result.batteryPct?.let {
                                            batteries = batteries + (s.address to it)
                                        }
                                        "Ringing ${s.name}"
                                    }
                                    is RingResult.Failure -> result.reason
                                }
                            }
                        },
                        onStopRing = {
                            ringer.stopRinging(s.address) { status = "Alert off" }
                        },
                        onWatch = {
                            if (watched == s.address) {
                                prefs.watchedAddress = null
                                watched = null
                                WatchService.stop(this@MainActivity)
                                status = "Alerts off"
                            } else {
                                prefs.watchedAddress = s.address
                                prefs.watchedName = nicknames[s.address] ?: s.name
                                watched = s.address
                                WatchService.start(this@MainActivity)
                                status = "Will alert if left behind"
                            }
                        },
                    )
                }
            }
        }

        renaming?.let { target ->
            RenameDialog(
                current = nicknames[target.address] ?: "",
                advertised = target.name,
                address = target.address,
                onDismiss = { renaming = null },
                onSave = { text ->
                    prefs.setNickname(target.address, text)
                    nicknames = prefs.allNicknames()
                    if (prefs.watchedAddress == target.address) {
                        prefs.watchedName = nicknames[target.address] ?: target.name
                    }
                    renaming = null
                },
            )
        }
    }
}

/**
 * The out-of-range state. Deliberately explicit that the coordinate is where
 * THIS PHONE was, not where the tracker is -- the difference decides whether
 * the number is useful or misleading.
 */
@Composable
private fun LastSeenPanel(
    label: String,
    lastSeenAt: Long,
    location: Pair<Double, Double>?,
    onOpenMap: () -> Unit,
    onUnwatch: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
    )) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "$label — out of range",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))

            val ago = if (lastSeenAt == 0L) null else {
                val mins = (System.currentTimeMillis() - lastSeenAt) / 60_000
                when {
                    mins < 1L -> "less than a minute ago"
                    mins < 60L -> "$mins min ago"
                    else -> "${mins / 60} h ${mins % 60} min ago"
                }
            }
            Text(
                if (ago == null) "Not seen since watching began"
                else "Last heard $ago",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            if (location != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "%.5f, %.5f".format(location.first, location.second),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Where this phone was standing when it last heard the "
                        + "tracker — so the tracker was within about 10-30 m of "
                        + "here at that moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "No position recorded. Grant location \"Allow all the time\" "
                        + "so a fix can be taken while the screen is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (location != null) {
                    Button(onClick = onOpenMap, modifier = Modifier.weight(1f)) {
                        Text("Open in Maps", maxLines = 1)
                    }
                }
                OutlinedButton(onClick = onUnwatch, modifier = Modifier.weight(1f)) {
                    Text("Stop alerting", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    current: String,
    advertised: String,
    address: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename tracker") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Nickname") },
                    placeholder = { Text("Keys, backpack, …") },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Leave blank to go back to the advertised name.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$advertised  ·  $address",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This label is stored on the phone. The tracker's own name "
                        + "is read-only and is not changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeviceCard(
    sighting: Sighting,
    label: String,
    battery: Int?,
    isWatched: Boolean,
    isRinging: Boolean,
    now: Long,
    onRing: () -> Unit,
    onStopRing: () -> Unit,
    onWatch: () -> Unit,
    onRename: () -> Unit,
) {
    val renamed = label != sighting.name
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = if (renamed) FontFamily.Default else FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                battery?.let { Text("$it%", style = MaterialTheme.typography.labelLarge) }
            }
            if (renamed) {
                Text(
                    sighting.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { sighting.closeness },
                modifier = Modifier.fillMaxWidth().height(10.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "≈%.1f m  ·  %d dBm  ·  %s".format(
                    sighting.approxMeters,
                    sighting.rssi,
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sighting.seenAt)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                sighting.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            // Two rows: four controls do not fit on one line at larger font
            // scales, and a wrapped button label looks broken.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onRing,
                    enabled = !isRinging,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (isRinging) "Connecting…" else "Ring it",
                        maxLines = 1,
                    )
                }
                OutlinedButton(onClick = onStopRing, modifier = Modifier.weight(1f)) {
                    Text("Stop", maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            // A whole sentence with a switch beats a two-word button: the
            // feature needs explaining, and the on/off state shows itself
            // rather than hiding in a verb.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Switch(checked = isWatched, onCheckedChange = { onWatch() })
                Spacer(Modifier.width(12.dp))
                Text(
                    "Alert me if I leave this behind",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onRename) { Text("Rename", maxLines = 1) }
            }
        }
    }
}

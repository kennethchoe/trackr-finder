package com.agilesalt.trackrfinder

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

/** Grace period after a scan starts before absence means anything. */
private const val SCAN_SETTLE_MS = 8_000L

class MainActivity : ComponentActivity() {

    private lateinit var scanner: TrackrScanner
    private lateinit var ringer: Ringer
    private lateinit var prefs: Prefs

    /**
     * What the app cannot work without. Location is deliberately absent: with
     * neverForLocation asserted, scanning, ringing and the leave-behind alert
     * all work without it. Requiring it would have made a coordinate-stamping
     * nicety a precondition for the app's actual purpose.
     */
    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Pre-12 there is no BLUETOOTH_SCAN, and scanning is gated on location.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /** Adds the last-seen coordinate. Declining costs only that. */
    private fun optionalPermissions(): Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

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

    override fun onStop() {
        super.onStop()
        // Deferred, not immediate: switching away and back within the grace
        // period keeps the existing registration instead of spending another
        // scan start. Android refuses the sixth start in 30 seconds silently.
        scanner.stopSoon()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) scanner.start()
        // If a watch is armed, restart it from a visible context. A service
        // restarted at boot may have been denied location; starting it while
        // the app is on screen restores that without needing the
        // ACCESS_BACKGROUND_LOCATION permission.
        if (prefs.watchedAddress != null) {
            // Visible app, so the location service type is permitted here even
            // though it was refused at boot. This is what restores the ability
            // to record a coordinate after a restart.
            runCatching { WatchService.start(this, withLocation = true) }
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
        var alertsOn by remember { mutableStateOf(prefs.watchEnabled) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var nicknames by remember { mutableStateOf(prefs.allNicknames()) }
        var renaming by remember { mutableStateOf<Sighting?>(null) }
        var showAll by remember { mutableStateOf(prefs.showAll) }
        var ringSupport by remember { mutableStateOf(prefs.allRingSupport()) }
        var lastSeenAt by remember { mutableLongStateOf(prefs.lastSeenAt) }
        var lastLoc by remember {
            mutableStateOf(if (prefs.hasLocation) prefs.lastLat to prefs.lastLon else null)
        }

        // Foreground discovery scan, live only while this screen is up.
        DisposableEffect(granted) {
            scanner.showAll = showAll
            if (granted) scanner.start()
            // No stop here: the activity lifecycle owns that. Stopping on every
            // recomposition of this effect cost a scan start on the way back,
            // and Android only allows five per 30 seconds.
            onDispose { }
        }
        LaunchedEffect(granted) {
            while (granted) {
                delay(1000)
                scanner.expireOlderThan(60_000)
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
                    // Android refuses the start silently, so without this the
                    // header would claim to be scanning while nothing runs.
                    scanner.looksThrottled -> "Android is throttling scans — hold on"
                    found == 0 -> "Scanning…"
                    found == 1 -> "Scanning · 1 nearby"
                    else -> "Scanning · $found nearby"
                },
                scanning = granted && scanner.bluetoothEnabled,
                foundCount = found,
            )
            Spacer(Modifier.height(14.dp))

            if (granted) {
                FilterChip(
                    selected = showAll,
                    onClick = {
                        showAll = !showAll
                        prefs.showAll = showAll
                        scanner.showAll = showAll
                    },
                    label = {
                        Text(
                            if (showAll) "Showing all Bluetooth devices"
                            else "Show all Bluetooth devices",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
                Spacer(Modifier.height(14.dp))
            }

            if (!granted) {
                Button(onClick = {
                    launcher.launch(requiredPermissions() + optionalPermissions())
                }) {
                    Text("Grant permissions")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bluetooth is required. Location is optional and only records "
                        + "where a tag was last heard — decline it and everything "
                        + "else still works. Nothing leaves the phone either way.",
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
            val heardMillisAgo = minOf(
                sightings[w]?.ageMillis ?: Long.MAX_VALUE,
                if (lastSeenAt > 0) now - lastSeenAt else Long.MAX_VALUE,
            )
            // Do not cry "out of range" before the scan has had a chance to
            // look. On a cold start the stored last-sighting is stale and the
            // scanner has not found anything yet, which flashed the red panel
            // for a second or two on a tag that was sitting right there.
            val hadTimeToLook = scanner.scanActiveMillis > SCAN_SETTLE_MS
            if (w != null && hadTimeToLook && heardMillisAgo > WatchService.OUT_OF_RANGE_MS) {
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
                    alertsOn = alertsOn,
                    onToggleAlerts = {
                        if (alertsOn) {
                            prefs.watchEnabled = false
                            alertsOn = false
                            WatchService.stop(this@MainActivity)
                        } else {
                            prefs.watchEnabled = true
                            alertsOn = true
                            WatchService.start(this@MainActivity, withLocation = true)
                        }
                        status = null
                    },
                    onForget = {
                        prefs.forgetWatch()
                        watched = null
                        alertsOn = false
                        WatchService.stop(this@MainActivity)
                        status = null
                    },
                )
                Spacer(Modifier.height(16.dp))
            }

            // Ringing doubles as the capability probe: the advertisement cannot
            // tell us whether a device speaks Immediate Alert, but a real GATT
            // service discovery can, so we record what we learn.
            val doRing: (Sighting) -> Unit = { s ->
                ringing = s.address
                status = "Connecting…"
                ringer.ring(s.address) { result ->
                    ringing = null
                    when (result) {
                        is RingResult.Success -> {
                            prefs.setRingSupport(s.address, true)
                            ringSupport = prefs.allRingSupport()
                            result.batteryPct?.let {
                                batteries = batteries + (s.address to it)
                            }
                            status = "Ringing ${nicknames[s.address] ?: s.name}"
                        }
                        RingResult.Unsupported -> {
                            prefs.setRingSupport(s.address, false)
                            ringSupport = prefs.allRingSupport()
                            status = "${s.name} has no Immediate Alert — cannot be rung"
                        }
                        is RingResult.Failure -> status = result.reason
                    }
                }
            }

            val all = sightings.values
            // A device is a "tag" if it advertised Immediate Alert, has a known
            // tag name, or we proved it ringable by connecting once.
            fun isTag(s: Sighting) = s.ringable || ringSupport[s.address] == true
            // Tags: stable order. There are only ever a few, so keeping a card
            // under the user's thumb matters more than ranking them by signal.
            val list = all.filter(::isTag).sortedBy { it.firstSeen }
            // Others: discovery order, never signal. Any signal-derived ordering
            // churns -- with two dozen devices clustered within 10 dB, even a
            // bucketed sort reshuffles constantly as readings drift across
            // boundaries. Position carries no information here; the dBm figure
            // on each row does.
            val others = all.filterNot(::isTag).sortedBy { it.firstSeen }
            if (list.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Nothing yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (showAll)
                        "No Bluetooth devices are advertising nearby."
                    else
                        "No tags found. A Pixel that has sat unused for years is "
                            + "most likely out of battery — it takes a CR2016. "
                            + "Press its button to wake it, or show all Bluetooth "
                            + "devices to look for other tags.",
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
                        isWatched = alertsOn && watched == s.address,
                        onRename = { renaming = s },
                        onTestAlert = {
                            WatchService.testAlert(this@MainActivity)
                            status = "Test alert sent — lock the phone and try again"
                        },
                        isRinging = ringing == s.address,
                        now = now,
                        onRing = { doRing(s) },
                        onStopRing = {
                            ringer.stopRinging(s.address) { status = "Alert off" }
                        },
                        onWatch = {
                            if (alertsOn && watched == s.address) {
                                prefs.watchEnabled = false
                                alertsOn = false
                                WatchService.stop(this@MainActivity)
                            } else {
                                prefs.watchedAddress = s.address
                                prefs.watchedName = nicknames[s.address] ?: s.name
                                prefs.watchEnabled = true
                                watched = s.address
                                alertsOn = true
                                WatchService.start(this@MainActivity, withLocation = true)
                            }
                            status = null
                        },
                    )
                }

                if (others.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Other Bluetooth devices",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Not known to be tags. Ringing works on anything that "
                                + "implements Immediate Alert, which can only be "
                                + "confirmed by connecting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    items(others, key = { it.address }) { s ->
                        CompactRow(
                            sighting = s,
                            probed = ringSupport[s.address],
                            busy = ringing == s.address,
                            onTryRing = { doRing(s) },
                        )
                    }
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
/**
 * A device we have no reason to believe is a tag. Deliberately minimal: no
 * rename, no leave-behind switch. Offering to alert you when you walk away
 * from someone else's soundbar is not a feature.
 */
@Composable
private fun CompactRow(
    sighting: Sighting,
    probed: Boolean?,
    busy: Boolean,
    onTryRing: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                sighting.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                "%d dBm  ·  %s".format(sighting.displayRssi, sighting.address),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (probed == false) {
                Text(
                    "No Immediate Alert — cannot be rung",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (probed != false) {
            TextButton(onClick = onTryRing, enabled = !busy) {
                Text(if (busy) "…" else "Try ringing", maxLines = 1)
            }
        }
    }
}

@Composable
private fun LastSeenPanel(
    label: String,
    lastSeenAt: Long,
    location: Pair<Double, Double>?,
    alertsOn: Boolean,
    onOpenMap: () -> Unit,
    onToggleAlerts: () -> Unit,
    onForget: () -> Unit,
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

            if (!alertsOn) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Alerts are off. This is still the last place it was heard.",
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
                OutlinedButton(onClick = onToggleAlerts, modifier = Modifier.weight(1f)) {
                    Text(if (alertsOn) "Stop alerting" else "Resume alerts", maxLines = 1)
                }
            }
            // Forget is the only destructive action here and is deliberately
            // the quietest: stopping alerts must not throw away the last known
            // position, which is what the panel exists to show.
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onForget) { Text("Forget this tag", maxLines = 1) }
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
    onTestAlert: () -> Unit,
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
                    sighting.displayRssi,
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
                // Only useful while armed, and worth having: whether an alert is
                // actually noticeable when the phone is locked is not something
                // you want to discover by losing your keys.
                if (isWatched) {
                    TextButton(onClick = onTestAlert) { Text("Test alert", maxLines = 1) }
                }
                TextButton(onClick = onRename) { Text("Rename", maxLines = 1) }
            }
        }
    }
}

package com.agilesalt.trackrfinder

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun AlertOptions(
    phoneEnabled: Boolean,
    onPhoneToggle: () -> Unit,
    tracker: TrackerAlertState?,
    otherTrackerActive: Boolean,
    enabled: Boolean,
    onTrackerToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onTestPhoneAlert: () -> Unit = {},
) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("When we get separated", style = MaterialTheme.typography.titleSmall)
    Text("Use either alert, or both.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Phone alert", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Switch(modifier = Modifier.semantics { contentDescription = "Phone alert" },
            checked = phoneEnabled, onCheckedChange = { onPhoneToggle() }, enabled = enabled)
    }
    Text("Notify this phone if the tracker stays out of range.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (phoneEnabled) {
        TextButton(onClick = onTestPhoneAlert, enabled = enabled) { Text("Test phone alert") }
    } else {
        Spacer(Modifier.height(12.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tracker alarm", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Switch(modifier = Modifier.semantics { contentDescription = "Tracker alarm" },
            checked = tracker?.enabled == true, onCheckedChange = onTrackerToggle,
            enabled = enabled && !otherTrackerActive)
    }
    Text("Make the tracker beep when its connection to this phone is lost.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            otherTrackerActive -> "Turn off the other tracker’s alarm before enabling this one."
            tracker == null -> "Requires pairing and a continuous Bluetooth connection."
            else -> "Turning Bluetooth off can also trigger the tracker."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (tracker != null) {
        Spacer(Modifier.height(12.dp))
        val error = tracker.phase == TrackerAlertPhase.ERROR
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    when (tracker.phase) {
                        TrackerAlertPhase.OFF -> "Off"
                        TrackerAlertPhase.CONNECTING -> "Connecting"
                        TrackerAlertPhase.PAIRING -> "Pairing"
                        TrackerAlertPhase.ARMING -> "Enabling alarm"
                        TrackerAlertPhase.READY -> "Ready"
                        TrackerAlertPhase.RECONNECTING -> "Reconnecting"
                        TrackerAlertPhase.DISARMING -> "Turning off"
                        TrackerAlertPhase.ERROR -> if (tracker.enabled) "Needs attention" else "Off not confirmed"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(if (tracker.phase == TrackerAlertPhase.READY) "Connected to this phone." else tracker.message,
                    style = MaterialTheme.typography.bodySmall)
                if (!tracker.enabled && tracker.phase != TrackerAlertPhase.OFF) {
                    Spacer(Modifier.height(6.dp))
                    Text("The tracker may still beep until Off is confirmed. Bring it nearby to finish.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (error) {
                    TextButton(onClick = onRetry, enabled = enabled) { Text("Retry") }
                }
            }
        }
    }
}

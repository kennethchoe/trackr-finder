package com.agilesalt.trackrfinder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun DeviceDetailsDialog(
    label: String,
    address: String,
    details: DeviceDetails?,
    busy: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device details") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading device details…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                details?.let { data ->
                    if (!data.complete) {
                        Text("The connection ended before every field could be read. You can refresh to try again.")
                        Spacer(Modifier.height(8.dp))
                    }
                    DetailField.entries.forEach { field ->
                        Text(field.label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(data.values[field] ?: "Unavailable", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                    }
                    Text("These values are reported by the tracker. Transmit power and connection timings are read-only.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onRename, enabled = data.canRename && !busy) {
                        Text("Rename device")
                    }
                    Text(
                        if (data.canRename) "The tracker advertises name writes, but may still reject a change."
                        else "This tracker does not expose a readable, writable device name.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") } },
    )
}

@Composable
internal fun HardwareRenameDialog(
    current: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current) }
    val validation = HardwareName.error(text.trim())
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Rename device", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("This writes the tracker's own Bluetooth name. Your phone nickname stays separate.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Device name") },
                        singleLine = true,
                        enabled = !busy,
                        isError = validation != null,
                    )
                    Text(validation ?: "Keep it short; emoji and accented characters take extra space.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (validation != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Some trackers refuse name changes or reset them after a restart. The app reads the name back to verify it.",
                        style = MaterialTheme.typography.bodySmall)
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Writing and verifying…")
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    TextButton(onClick = { onSave(text.trim()) },
                        enabled = !busy && validation == null && text.trim() != current) {
                        Text("Write name")
                    }
                }
            }
        }
    }
}

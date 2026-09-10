package com.gmdparser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.gmdparser.ui.theme.*
import com.gmdparser.util.ScanStats
import com.gmdparser.util.SmsInboxScanner
import kotlinx.coroutines.launch

@Composable
fun SmsScanDialog(
    onDismiss: () -> Unit,
    onScanComplete: (ScanStats) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val options = listOf(
        Pair("Today / Last 24 hours (Daily)", 1),
        Pair("Last 7 days", 7),
        Pair("Last 14 days", 14),
        Pair("Last 30 days (Recommended)", 30),
        Pair("Last 90 days", 90),
        Pair("All time (Full history)", null)
    )

    var selectedIndex by remember { mutableIntStateOf(0) } // default Today / Daily
    var isScanning by remember { mutableStateOf(false) }
    var resultStats by remember { mutableStateOf<ScanStats?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isScanning) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (resultStats == null) "Scan SMS Inbox" else "Scan Completed",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isScanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Scanning SMS messages...",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                            Text(
                                "Deduplicating against your records",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else if (resultStats != null) {
                    val stats = resultStats!!
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan Summary", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("• SMS scanned: ${stats.totalSmsScanned}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Text("• M-PESA messages identified: ${stats.mpesaFound}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Text("• Newly added to review queue: ${stats.newlyQueued}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("• Already recorded/skipped: ${stats.alreadyTracked}", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                        }
                    }
                } else {
                    Text(
                        text = "Scan your device SMS inbox for M-PESA messages to catch up on unrecorded transactions. Messages already pending or confirmed will not be duplicated.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Select Time Range:", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    options.forEachIndexed { index, (label, _) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedIndex == index,
                                onClick = { selectedIndex = index },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.outline
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                color = if (selectedIndex == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (resultStats != null) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Done", color = MaterialTheme.colorScheme.onPrimary)
                }
            } else if (!isScanning) {
                Button(
                    onClick = {
                        scope.launch {
                            isScanning = true
                            val daysBack = options[selectedIndex].second
                            val stats = SmsInboxScanner.scanInbox(context, daysBack)
                            isScanning = false
                            resultStats = stats
                            onScanComplete(stats)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Start Scan", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
        dismissButton = {
            if (!isScanning && resultStats == null) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    )
}

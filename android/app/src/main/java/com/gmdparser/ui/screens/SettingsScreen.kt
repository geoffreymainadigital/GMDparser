package com.gmdparser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.BuildConfig
import com.gmdparser.data.network.NetworkClient
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.theme.*
import kotlinx.coroutines.launch

import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.platform.LocalContext
import com.gmdparser.ui.components.SmsScanDialog
import com.gmdparser.util.ReminderCadence
import com.gmdparser.util.ReviewReminderManager

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrlText by remember { mutableStateOf(NetworkClient.baseUrl) }
    var healthStatusMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingHealth by remember { mutableStateOf(false) }

    var currentCadence by remember { mutableStateOf(ReviewReminderManager.getCadence(context)) }
    var showScanDialog by remember { mutableStateOf(false) }

    if (showScanDialog) {
        SmsScanDialog(
            onDismiss = { showScanDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("System Settings", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Review cadence, SMS catch-up, and gateway configuration", color = TextSecondary, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))

        // Review Cadence & Reminders Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Review Reminder Cadence", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Receive notification alerts when unreviewed transactions are waiting in your queue.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReminderCadence.values().forEach { cadence ->
                        val isSelected = currentCadence == cadence
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                currentCadence = cadence
                                ReviewReminderManager.setCadence(context, cadence)
                            },
                            label = { Text(cadence.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = AccentCyan,
                                containerColor = DarkSurfaceCard,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = DarkSurfaceBorder,
                                selectedBorderColor = AccentCyan
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (currentCadence) {
                        ReminderCadence.OFF -> "Reminders are disabled. You can review pending items anytime."
                        ReminderCadence.DAILY -> "Daily reminders scheduled for 8:00 PM when queue is non-empty."
                        ReminderCadence.WEEKLY -> "Weekly reminders scheduled every 7 days when queue is non-empty."
                        ReminderCadence.MONTHLY -> "Monthly reminders scheduled every 30 days when queue is non-empty."
                    },
                    color = if (currentCadence == ReminderCadence.OFF) TextMuted else AccentCyan,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SMS Catch-Up Scanner Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MpesaGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SMS Inbox Catch-Up Scanner", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Re-scan device SMS messages across custom time ranges to queue any missed M-PESA transactions.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { showScanDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MpesaGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = DarkBackground)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan SMS Inbox", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Vercel API Gateway Endpoint
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Vercel Public API Gateway", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Public HTTPS proxy routing traffic to Apps Script", color = TextSecondary, fontSize = 11.sp)

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = baseUrlText,
                    onValueChange = {
                        baseUrlText = it
                        NetworkClient.setCustomBaseUrl(it)
                    },
                    label = { Text("Base URL") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MpesaGreen,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        scope.launch {
                            isCheckingHealth = true
                            healthStatusMessage = null
                            try {
                                val res = NetworkClient.apiService.getHealth()
                                if (res.isSuccessful) {
                                    val body = res.body()
                                    healthStatusMessage = "✓ Gateway Online (Status: ${body?.status}, Service: ${body?.service})"
                                } else {
                                    healthStatusMessage = "⚠ Gateway returned HTTP ${res.code()}"
                                }
                            } catch (e: Exception) {
                                healthStatusMessage = "✗ Health probe failed: ${e.localizedMessage ?: e.message}"
                            } finally {
                                isCheckingHealth = false
                            }
                        }
                    },
                    enabled = !isCheckingHealth,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCheckingHealth) {
                        CircularProgressIndicator(color = DarkBackground, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Gateway Health (/api/health)", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }

                if (healthStatusMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = healthStatusMessage!!,
                        color = if (healthStatusMessage!!.startsWith("✓")) MpesaGreen else AccentAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Metadata
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GMDParser Android Client", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Version: 1.0.0 (Clean-Slate Architecture)", color = TextSecondary, fontSize = 12.sp)
                Text("Target SDK: 34 (Android 14)", color = TextSecondary, fontSize = 12.sp)
                Text("Spreadsheet Write Boundary: C:D, G:H, J:L", color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

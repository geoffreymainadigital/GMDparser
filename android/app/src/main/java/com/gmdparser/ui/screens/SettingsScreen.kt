package com.gmdparser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.gmdparser.util.AppPreferences
import com.gmdparser.util.AppTheme
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

    // PIN state
    var pinEnabled by remember { mutableStateOf(AppPreferences.isPinEnabled) }
    var pinScreenMode by remember { mutableStateOf<PinScreenMode?>(null) }
    var pendingSetPin by remember { mutableStateOf<String?>(null) }

    // Theme state
    val currentTheme by ThemeManager.currentTheme.collectAsState()

    // Full-screen PIN flow overlay
    when (pinScreenMode) {
        PinScreenMode.SET -> {
            PinLockScreen(
                mode = PinScreenMode.SET,
                onPinSet = { pin ->
                    pendingSetPin = pin
                    pinScreenMode = PinScreenMode.CONFIRM
                },
                onCancel = { pinScreenMode = null }
            )
            return
        }
        PinScreenMode.CONFIRM -> {
            PinLockScreen(
                mode = PinScreenMode.CONFIRM,
                pendingPin = pendingSetPin,
                onUnlocked = {
                    pinEnabled = true
                    pinScreenMode = null
                    pendingSetPin = null
                },
                onCancel = { pinScreenMode = null; pendingSetPin = null }
            )
            return
        }
        else -> { /* show main settings */ }
    }

    if (showScanDialog) {
        SmsScanDialog(onDismiss = { showScanDialog = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("System Settings", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Security, appearance, reminders, and gateway configuration", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))

        // ── PIN Lock ────────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PIN Lock", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Require a 6-digit PIN each time the app is opened.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (pinEnabled) {
                        OutlinedButton(
                            onClick = {
                                AppPreferences.clearPin()
                                pinEnabled = false
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disable PIN", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { pinScreenMode = PinScreenMode.SET },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentAmber.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Change PIN", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = { pinScreenMode = PinScreenMode.SET },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Set PIN", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (pinEnabled) "✓ PIN lock is active" else "PIN lock is disabled",
                    color = if (pinEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Theme Selector ───────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("App Theme", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("Choose a colour scheme for the app interface.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.values().forEach { theme ->
                        val isSelected = currentTheme == theme
                        val accentColor = when (theme) {
                            AppTheme.DARK     -> AccentBlue
                            AppTheme.MIDNIGHT -> AccentPurple
                            AppTheme.FOREST   -> MpesaGreen
                            AppTheme.LIGHT    -> AccentAmber
                        }
                        val themeIcon = when (theme) {
                            AppTheme.DARK     -> Icons.Default.DarkMode
                            AppTheme.MIDNIGHT -> Icons.Default.NightlightRound
                            AppTheme.FOREST   -> Icons.Default.Eco
                            AppTheme.LIGHT    -> Icons.Default.LightMode
                        }
                        Surface(
                            onClick = { ThemeManager.setTheme(theme) },
                            color = if (isSelected) accentColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isSelected) accentColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(10.dp)
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { ThemeManager.setTheme(theme) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = accentColor,
                                        unselectedColor = MaterialTheme.colorScheme.outline
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(themeIcon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = theme.label,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Review Cadence & Reminders ───────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Review Reminder Cadence", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Receive notification alerts when unreviewed transactions are waiting in your queue.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (currentCadence) {
                        ReminderCadence.OFF     -> "Reminders are disabled. You can review pending items anytime."
                        ReminderCadence.DAILY   -> "Daily reminders scheduled for 8:00 PM when queue is non-empty."
                        ReminderCadence.WEEKLY  -> "Weekly reminders scheduled every 7 days when queue is non-empty."
                        ReminderCadence.MONTHLY -> "Monthly reminders scheduled every 30 days when queue is non-empty."
                    },
                    color = if (currentCadence == ReminderCadence.OFF) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Advanced Inbox History Scan ───────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Advanced Inbox History Scan", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Perform deep custom-range scans across your SMS inbox history (7d, 14d, 30d, 90d, or All time) to recover and deduplicate older records.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { showScanDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Launch Custom Range Scanner", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Vercel API Gateway Endpoint ──────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Vercel Public API Gateway", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Public HTTPS proxy routing traffic to Apps Script", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = baseUrlText,
                    onValueChange = {
                        baseUrlText = it
                        NetworkClient.setCustomBaseUrl(it)
                    },
                    label = { Text("Base URL") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCheckingHealth) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Gateway Health (/api/health)", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                if (healthStatusMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = healthStatusMessage!!,
                        color = if (healthStatusMessage!!.startsWith("✓")) MaterialTheme.colorScheme.primary else AccentAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── App Metadata ──────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GMDParser Android Client", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Version: 1.0.0 (Clean-Slate Architecture)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("Target SDK: 34 (Android 14)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("Spreadsheet Write Boundary: C:D, G:H, J:L", color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
            }
        }
    }
}

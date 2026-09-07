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

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    var baseUrlText by remember { mutableStateOf(NetworkClient.baseUrl) }
    var healthStatusMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingHealth by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("System Settings", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("API gateway configuration and security constraints", color = TextSecondary, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))

        // AutoSync Invariant Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, MpesaGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MpesaGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("AutoSync Invariant", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Status: ", color = TextSecondary, fontSize = 13.sp)
                    Surface(
                        color = AccentRed.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "LOCKED FALSE",
                            color = AccentRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "isAutoSync() is permanently hardcoded to false. All detected transactions require active manual user confirmation.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
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

package com.gmdparser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.BuildConfig
import com.gmdparser.data.network.NetworkClient
import com.gmdparser.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SavingsGoal(
    val category: String,
    val goal: Double,
    val saved: Double,
    val remaining: Double,
    val progress: Double
)

@Composable
fun SavingsScreen() {
    var isLoading by remember { mutableStateOf(true) }
    var tabName by remember { mutableStateOf("Savings Dashboard") }
    var goals by remember { mutableStateOf<List<SavingsGoal>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                val baseUrl = NetworkClient.baseUrl
                val request = Request.Builder()
                    .url("$baseUrl/api/savings")
                    .get()
                    .build()

                val res = client.newCall(request).execute()
                val bodyStr = res.body?.string()

                if (res.isSuccessful && !bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    if (json.optBoolean("success", false)) {
                        val dataObj = json.getJSONObject("data")
                        tabName = dataObj.optString("tab", "Savings Dashboard")
                        
                        val goalsArray = dataObj.optJSONArray("goals")
                        val loadedGoals = mutableListOf<SavingsGoal>()
                        if (goalsArray != null) {
                            for (i in 0 until goalsArray.length()) {
                                val item = goalsArray.getJSONObject(i)
                                loadedGoals.add(
                                    SavingsGoal(
                                        category = item.optString("category", ""),
                                        goal = item.optDouble("goal", 0.0),
                                        saved = item.optDouble("saved", 0.0),
                                        remaining = item.optDouble("remaining", 0.0),
                                        progress = item.optDouble("progress", 0.0)
                                    )
                                )
                            }
                        }
                        goals = loadedGoals
                    } else {
                        errorMessage = json.optString("error", "Unknown error")
                    }
                } else {
                    errorMessage = "Failed to fetch: ${res.code}"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Network error"
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Savings Goals",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Goal tracking across all investment channels",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Surface(
                color = AccentCyan.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "SHEET: $tabName",
                    color = AccentCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isLoading) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Loading savings goals from spreadsheet...",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 13.sp
                    )
                }
            }
        } else if (errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(goals) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.category,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                val progressPct = (item.progress * 100).toInt()
                                val progressColor = if (progressPct >= 100) AccentCyan else MaterialTheme.colorScheme.primary
                                Text(
                                    text = "$progressPct%",
                                    color = progressColor,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            // Progress Bar
                            LinearProgressIndicator(
                                progress = item.progress.toFloat().coerceIn(0f, 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = if (item.progress >= 1.0) AccentCyan else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Goal", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    Text("Ksh ${String.format("%,.0f", item.goal)}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Saved", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    Text("Ksh ${String.format("%,.0f", item.saved)}", color = AccentCyan, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Remaining", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    Text("Ksh ${String.format("%,.0f", item.remaining)}", color = AccentAmber, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

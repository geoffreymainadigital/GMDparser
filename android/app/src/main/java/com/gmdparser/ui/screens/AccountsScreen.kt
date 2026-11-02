package com.gmdparser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.theme.*
import com.gmdparser.util.AppPreferences
import kotlinx.coroutines.launch

data class AccountItem(
    val name: String,
    val type: String,
    val icon: ImageVector,
    val isDefault: Boolean = false
)

@Composable
fun AccountsScreen() {
    val isPrivacyOn by AppPreferences.isPrivacyBlurFlow.collectAsState()
    val dashboardResponse by TransactionRepository.dashboardData.collectAsState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        TransactionRepository.fetchDashboard()
    }

    val rawAccounts = dashboardResponse?.accounts
    val totalAcct = rawAccounts?.firstOrNull { it.accountName.trim().equals("TOTAL", ignoreCase = true) }
    val liveAccounts = rawAccounts?.filterNot {
        val lower = it.accountName.trim().lowercase()
        lower == "total" || lower == "all accounts" || lower.startsWith("total ") || lower.endsWith(" total")
    }

    val sortedAccounts = remember(liveAccounts) {
        val predefined = listOf(
            AccountItem("Equity Bank", "Bank Account", Icons.Default.AccountBalance),
            AccountItem("I&M Bank", "Bank Account", Icons.Default.AccountBalance),
            AccountItem("Cash", "Physical Cash", Icons.Default.AccountBalance),
            AccountItem("Mpesa", "Mobile Money", Icons.Default.PhoneAndroid, isDefault = true),
            AccountItem("Till Number", "Merchant Account", Icons.Default.PhoneAndroid),
            AccountItem("Tower Sacco", "SACCO Account", Icons.Default.Savings),
            AccountItem("Airtime", "Telecom", Icons.Default.PhoneAndroid),
            AccountItem("Coop Bank", "Bank Account", Icons.Default.AccountBalance)
        )
        val predefinedNames = predefined.map { it.name.lowercase() }.toSet()
        val extraLive = (liveAccounts ?: emptyList())
            .filter {
                val lower = it.accountName.trim().lowercase()
                lower !in predefinedNames && lower != "total" && lower != "all accounts" && !lower.startsWith("total ") && !lower.endsWith(" total")
            }
            .map { AccountItem(it.accountName, "Bank Account", Icons.Default.AccountBalance) }
        val all = (predefined + extraLive).filterNot {
            val lower = it.name.trim().lowercase()
            lower == "total" || lower == "all accounts" || lower.startsWith("total ") || lower.endsWith(" total")
        }

        all.sortedWith(
            compareByDescending<AccountItem> { acc ->
                val bal = liveAccounts?.firstOrNull { it.accountName.equals(acc.name, ignoreCase = true) }?.currentBalance ?: 0.0
                when {
                    bal > 0.0 -> 2   // Top: Accounts with money
                    bal == 0.0 -> 1  // Middle: Zeros
                    else -> 0        // Bottom: Negatives / Overdrawn
                }
            }.thenByDescending { acc ->
                liveAccounts?.firstOrNull { it.accountName.equals(acc.name, ignoreCase = true) }?.currentBalance ?: 0.0
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Financial Accounts",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Live balances from Accounts tab in Google Sheets",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                IconButton(
                    enabled = !isRefreshing,
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            val result = TransactionRepository.fetchDashboard()
                            isRefreshing = false
                            if (result.isSuccess) {
                                snackbarHostState.showSnackbar("Accounts data updated live from Sheets")
                            } else {
                                val err = result.exceptionOrNull()?.localizedMessage ?: "Refresh failed"
                                snackbarHostState.showSnackbar("Failed to sync accounts: $err")
                            }
                        }
                    }
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AccentCyan,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
                    }
                }
            }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sortedAccounts, key = { it.name }) { acc ->
                val liveAcct = liveAccounts?.firstOrNull { it.accountName.equals(acc.name, ignoreCase = true) }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = AccentCyan.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = acc.icon,
                                            contentDescription = acc.name,
                                            tint = AccentCyan,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = acc.name,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = acc.type,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            if (acc.isDefault) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "DEFAULT",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(10.dp))

                        val curBal = liveAcct?.currentBalance ?: 0.0
                        val dep = liveAcct?.deposits ?: 0.0
                        val wth = liveAcct?.withdrawals ?: 0.0

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Current Balance", color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
                                Text(
                                    text = "Ksh ${String.format("%,.2f", curBal)}",
                                    color = if (curBal >= 0) MaterialTheme.colorScheme.onSurface else AccentRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.blur(if (isPrivacyOn) 10.dp else 0.dp)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Deposits (+)", color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
                                Text(
                                    text = "+Ksh ${String.format("%,.2f", dep)}",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    modifier = Modifier.blur(if (isPrivacyOn) 10.dp else 0.dp)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Withdrawals (-)", color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
                                Text(
                                    text = "Ksh ${String.format("%,.2f", wth)}",
                                    color = AccentAmber,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    modifier = Modifier.blur(if (isPrivacyOn) 10.dp else 0.dp)
                                )
                            }
                        }
                    }
                }
            }

            // TOTAL NET ACCOUNTS Row pinned at bottom
            if (liveAccounts != null && liveAccounts.isNotEmpty()) {
                val netBalance = totalAcct?.currentBalance ?: liveAccounts.sumOf { it.currentBalance }
                val netDeposits = totalAcct?.deposits ?: liveAccounts.sumOf { it.deposits }
                val netWithdrawals = totalAcct?.withdrawals ?: liveAccounts.sumOf { it.withdrawals }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("TOTAL NET ACCOUNTS", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    text = "Ksh ${String.format("%,.2f", netBalance)}",
                                    color = if (netBalance >= 0) MaterialTheme.colorScheme.primary else AccentRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.blur(if (isPrivacyOn) 10.dp else 0.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Deposits: +Ksh ${String.format("%,.2f", netDeposits)}",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.blur(if (isPrivacyOn) 10.dp else 0.dp)
                                )
                                Text(
                                    text = "Withdrawals: Ksh ${String.format("%,.2f", netWithdrawals)}",
                                    color = AccentAmber,
                                    fontSize = 11.sp,
                                    modifier = Modifier.blur(if (isPrivacyOn) 10.dp else 0.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

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
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.theme.*

data class AccountItem(
    val name: String,
    val type: String,
    val icon: ImageVector,
    val isDefault: Boolean = false
)

@Composable
fun AccountsScreen() {
    val dashboardResponse by TransactionRepository.dashboardData.collectAsState()

    LaunchedEffect(Unit) {
        TransactionRepository.fetchDashboard()
    }

    val liveAccounts = dashboardResponse?.accounts

    val staticAccounts = listOf(
        AccountItem("Mpesa", "Mobile Money", Icons.Default.PhoneAndroid, isDefault = true),
        AccountItem("Equity Bank", "Bank Account", Icons.Default.AccountBalance),
        AccountItem("I&M Bank", "Bank Account", Icons.Default.AccountBalance),
        AccountItem("Cash", "Physical Cash", Icons.Default.AccountBalance),
        AccountItem("Till Number", "Merchant Account", Icons.Default.PhoneAndroid),
        AccountItem("Tower Sacco", "SACCO Account", Icons.Default.Savings),
        AccountItem("Airtime", "Telecom", Icons.Default.PhoneAndroid)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text(
            text = "Financial Accounts",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Funding and destination accounts configured in production spreadsheet",
            color = TextSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(staticAccounts) { acc ->
                val liveAcct = liveAccounts?.firstOrNull { it.accountName.equals(acc.name, ignoreCase = true) }

                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
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
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = acc.type,
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            if (acc.isDefault) {
                                Surface(
                                    color = MpesaGreen.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "DEFAULT",
                                        color = MpesaGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        if (liveAcct != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Divider(color = DarkSurfaceBorder, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Current Balance", color = TextMuted, fontSize = 11.sp)
                                    Text(
                                        text = "Ksh ${String.format("%,.2f", liveAcct.currentBalance)}",
                                        color = if (liveAcct.currentBalance >= 0) TextPrimary else AccentRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Deposits (+)", color = TextMuted, fontSize = 11.sp)
                                    Text(
                                        text = "+Ksh ${String.format("%,.2f", liveAcct.deposits)}",
                                        color = MpesaGreen,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Withdrawals (-)", color = TextMuted, fontSize = 11.sp)
                                    Text(
                                        text = "Ksh ${String.format("%,.2f", liveAcct.withdrawals)}",
                                        color = AccentAmber,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
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

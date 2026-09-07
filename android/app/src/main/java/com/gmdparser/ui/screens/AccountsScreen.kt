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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.ui.theme.*

data class AccountItem(
    val name: String,
    val type: String,
    val balance: Double,
    val icon: ImageVector,
    val isDefault: Boolean = false
)

@Composable
fun AccountsScreen() {
    val accounts = listOf(
        AccountItem("M-PESA", "Mobile Money", 12450.00, Icons.Default.PhoneAndroid, isDefault = true),
        AccountItem("NCBA Loop", "Bank Account", 45200.00, Icons.Default.AccountBalance),
        AccountItem("Equity Bank", "Bank Account", 18500.00, Icons.Default.AccountBalance),
        AccountItem("Stima Sacco", "SACCO Account", 120000.00, Icons.Default.Savings),
        AccountItem("CIC Money Market Fund", "MMF / Liquid", 85000.00, Icons.Default.Savings),
        AccountItem("Cash Wallet", "Physical Cash", 3200.00, Icons.Default.AccountBalance)
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
            text = "Asset stores and funding accounts defined in financial model",
            color = TextSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(accounts) { acc ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(acc.icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(acc.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(acc.type, color = TextMuted, fontSize = 11.sp)
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Ksh ${String.format("%,.2f", acc.balance)}",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (acc.isDefault) {
                                Text("Primary Capture", color = MpesaGreen, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

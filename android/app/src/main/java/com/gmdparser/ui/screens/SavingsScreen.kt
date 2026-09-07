package com.gmdparser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.ui.theme.*

data class SavingsFund(val name: String, val institution: String, val balance: Double, val target: Double)

@Composable
fun SavingsScreen() {
    val funds = listOf(
        SavingsFund("Emergency Reserve", "CIC Money Market Fund", 85000.00, 150000.00),
        SavingsFund("SACCO Shares & Deposits", "Stima Sacco", 120000.00, 200000.00),
        SavingsFund("Holiday / Travel Fund", "NCBA Loop Goal", 25000.00, 50000.00)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text("Savings & Investments", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Capital reserves tracked through M-PESA deposits", color = TextSecondary, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(funds) { f ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(f.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(f.institution, color = TextMuted, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Current: Ksh ${String.format("%,.0f", f.balance)}", color = MpesaGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Target: Ksh ${String.format("%,.0f", f.target)}", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

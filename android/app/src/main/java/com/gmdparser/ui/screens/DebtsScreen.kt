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

data class DebtTracker(val creditor: String, val category: String, val balance: Double, val dueDate: String)

@Composable
fun DebtsScreen() {
    val debts = listOf(
        DebtTracker("Bank Loan (Development)", "Bank Loan Repayment", 85000.00, "15th monthly"),
        DebtTracker("Credit Card", "Credit Card", 12400.00, "20th monthly"),
        DebtTracker("M-Shwari / Fuliza", "Mobile Loan", 0.00, "Cleared")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text("Debts & Obligations", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Loan servicing and credit repayments monitored in sheet", color = TextSecondary, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(debts) { d ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(d.creditor, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                if (d.balance > 0) "Ksh ${String.format("%,.0f", d.balance)}" else "CLEARED",
                                color = if (d.balance > 0) AccentRed else MpesaGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(d.category, color = TextMuted, fontSize = 11.sp)
                            Text("Due: ${d.dueDate}", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

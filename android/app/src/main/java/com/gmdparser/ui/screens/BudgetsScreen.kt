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

data class BudgetItem(val category: String, val spent: Double, val limit: Double)

@Composable
fun BudgetsScreen() {
    val budgets = listOf(
        BudgetItem("Groceries", 14500.00, 20000.00),
        BudgetItem("Transport & Fuel", 8200.00, 10000.00),
        BudgetItem("Dining Out / Takeout", 4500.00, 6000.00),
        BudgetItem("Electricity / KPLC", 3500.00, 4000.00),
        BudgetItem("Internet / WiFi", 3000.00, 3000.00),
        BudgetItem("Shopping & Clothing", 5500.00, 5000.00) // Exceeded
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text(text = "Monthly Budgets", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text = "Category spending thresholds defined in the financial model", color = TextSecondary, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(budgets) { b ->
                val ratio = (b.spent / b.limit).toFloat().coerceIn(0f, 1f)
                val isExceeded = b.spent > b.limit

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
                            Text(b.category, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "Ksh ${String.format("%,.0f", b.spent)} / Ksh ${String.format("%,.0f", b.limit)}",
                                color = if (isExceeded) AccentRed else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = if (isExceeded) AccentRed else MpesaGreen,
                            trackColor = DarkSurfaceCard
                        )
                    }
                }
            }
        }
    }
}

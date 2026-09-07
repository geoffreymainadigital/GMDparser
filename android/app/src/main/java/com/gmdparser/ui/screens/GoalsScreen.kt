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

data class FinancialGoal(val title: String, val current: Double, val target: Double, val targetYear: String)

@Composable
fun GoalsScreen() {
    val goals = listOf(
        FinancialGoal("Emergency Reserve Fund", 85000.00, 150000.00, "2026 Q4"),
        FinancialGoal("Land Investment Project", 120000.00, 500000.00, "2027"),
        FinancialGoal("Tech Equipment Upgrade", 45000.00, 60000.00, "2026 Q3")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text("Financial Goals", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Strategic wealth accumulation targets tied to ledger savings", color = TextSecondary, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(goals) { g ->
                val progress = (g.current / g.target).toFloat().coerceIn(0f, 1f)
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
                            Text(g.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(g.targetYear, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Ksh ${String.format("%,.0f", g.current)}", color = MpesaGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Target: Ksh ${String.format("%,.0f", g.target)}", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = AccentCyan,
                            trackColor = DarkSurfaceCard
                        )
                    }
                }
            }
        }
    }
}

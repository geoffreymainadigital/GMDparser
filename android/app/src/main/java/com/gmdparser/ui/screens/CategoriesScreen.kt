package com.gmdparser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.data.model.CategoryItem
import com.gmdparser.data.repository.TaxonomyRepository
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.theme.*

@Composable
fun CategoriesScreen() {
    val dashboardResponse by TransactionRepository.dashboardData.collectAsState()
    val categoryTaxonomy by TaxonomyRepository.categoriesByType.collectAsState()

    var selectedSection by remember { mutableStateOf("Income") }
    val sections = listOf("Income", "Bills", "Debt", "Expenses", "Savings")

    LaunchedEffect(Unit) {
        TransactionRepository.fetchDashboard()
    }

    val monthData = dashboardResponse?.month
    val monthTitle = monthData?.month ?: "Active Month"
    val itemsForSection: List<CategoryItem> = monthData?.tables?.get(selectedSection) ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Category Tracking ($monthTitle)",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Monthly Goal vs Actual breakdown from spreadsheet",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Surface(
                color = AccentCyan.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "SHEET: $monthTitle",
                    color = AccentCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section Filter Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sections) { sec ->
                val isSelected = sec == selectedSection
                Surface(
                    color = if (isSelected) AccentCyan else DarkSurface,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isSelected) AccentCyan else DarkSurfaceBorder,
                            RoundedCornerShape(20.dp)
                        )
                        .clickable { selectedSection = sec }
                ) {
                    Text(
                        text = sec,
                        color = if (isSelected) DarkBackground else TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (itemsForSection.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Loading $selectedSection categories from spreadsheet...",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                items(itemsForSection) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.category,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                val diffColor = when {
                                    item.diff < 0 -> AccentAmber
                                    item.diff > 0 -> MpesaGreen
                                    else -> TextMuted
                                }
                                Text(
                                    text = "Diff: Ksh ${String.format("%,.0f", item.diff)}",
                                    color = diffColor,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Goal: Ksh ${String.format("%,.0f", item.goal)}",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Actual: Ksh ${String.format("%,.0f", item.actual)}",
                                    color = AccentCyan,
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

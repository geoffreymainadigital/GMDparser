package com.gmdparser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.data.model.TransactionStatus
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.parser.MpesaParser
import com.gmdparser.ui.theme.*

@Composable
fun DashboardScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToCategories: () -> Unit
) {
    val pendingList by TransactionRepository.pendingTransactions.collectAsState()
    val confirmedList by TransactionRepository.confirmedTransactions.collectAsState()

    // Data is prefetched at startup in MainAppHost; no fetch needed here.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Balance / Status Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "M-PESA OPERATING LEDGER",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                val totalSyncedAmount = confirmedList
                    .filter { it.status == TransactionStatus.SYNCED }
                    .sumOf { it.amount }

                Text(
                    text = "Ksh ${String.format("%,.2f", totalSyncedAmount)}",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "● Google Sheets Synchronized",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${confirmedList.size} transactions recorded",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Monthly/Annual Budget Sheet — data is prefetched at startup for instant toggle.
        var selectedPeriod by remember { mutableStateOf("monthly") }

        val monthlyData by TransactionRepository.monthlyDashboard.collectAsState()
        val annualData by TransactionRepository.annualDashboard.collectAsState()

        // Active data: instantly switches between the two independently cached flows.
        val activeData = if (selectedPeriod == "annual") annualData else monthlyData
        val showLoading = activeData == null

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val titleText = if (showLoading) {
                        if (selectedPeriod == "annual") "Annual Dashboard (Loading...)" else "Monthly Budget (Loading...)"
                    } else if (selectedPeriod == "annual") {
                        val tabName = activeData?.tab ?: "Annual"
                        "Annual Dashboard ($tabName)"
                    } else {
                        val monthName = activeData?.month ?: activeData?.tab ?: "Current"
                        "Monthly Budget ($monthName)"
                    }
                    Text(
                        text = titleText,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = if (showLoading) AccentAmber.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (showLoading) "LOADING" else "LIVE",
                            color = if (showLoading) AccentAmber else MaterialTheme.colorScheme.secondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                
                // Period Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    val isMonthly = selectedPeriod == "monthly"
                    Surface(
                        color = if (isMonthly) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedPeriod = "monthly" }
                    ) {
                        Text(
                            text = "Monthly",
                            color = if (isMonthly) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 6.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    Surface(
                        color = if (!isMonthly) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedPeriod = "annual" }
                    ) {
                        Text(
                            text = "Annual",
                            color = if (!isMonthly) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 6.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                val tiles = activeData?.summaryTiles
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BudgetSummaryTile("Income", tiles?.totalIncome?.actual ?: 0.0, tiles?.totalIncome?.goal, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    BudgetSummaryTile("Bills", tiles?.totalBills?.actual ?: 0.0, tiles?.totalBills?.goal, AccentAmber, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BudgetSummaryTile("Debt", tiles?.totalDebtPayoff?.actual ?: 0.0, tiles?.totalDebtPayoff?.goal, AccentBlue, Modifier.weight(1f))
                    BudgetSummaryTile("Expenses", tiles?.totalExpenses?.actual ?: 0.0, tiles?.totalExpenses?.goal, AccentRed, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BudgetSummaryTile("Savings", tiles?.totalSavings?.actual ?: 0.0, tiles?.totalSavings?.goal, AccentCyan, Modifier.weight(1f))
                    BudgetSummaryTile("Unallocated", tiles?.unallocatedIncome?.actual ?: 0.0, null, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Pending Reviews Call-To-Action Banner (if any pending)
        if (pendingList.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AccentAmber.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AccentAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable { onNavigateToReview() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = AccentAmber)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "${pendingList.size} Transaction${if (pendingList.size > 1) "s" else ""} Awaiting Confirmation",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Tap to review classification and record to sheet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AccentAmber)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Quick Feature Metric Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricQuickCard(
                title = "Accounts",
                value = "M-PESA / Banks",
                icon = Icons.Default.AccountBalance,
                color = AccentCyan,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToAccounts
            )
            MetricQuickCard(
                title = "Categories",
                value = "Taxonomy",
                icon = Icons.Default.Category,
                color = AccentPurple,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToCategories
            )
        }

    }
}

@Composable
fun MetricQuickCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
            Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BudgetSummaryTile(
    label: String,
    actual: Double,
    goal: Double?,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = label, color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Ksh ${String.format("%,.0f", actual)}",
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            if (goal != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Goal: Ksh ${String.format("%,.0f", goal)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}

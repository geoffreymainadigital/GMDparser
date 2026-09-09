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
    val dashboardResponse by TransactionRepository.dashboardData.collectAsState()

    LaunchedEffect(Unit) {
        TransactionRepository.fetchDashboard()
    }

    var testSmsText by remember { mutableStateOf("") }
    var parseFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Balance / Status Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "M-PESA OPERATING LEDGER",
                    color = TextMuted,
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
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MpesaGreen.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "● Google Sheets Synchronized",
                            color = MpesaGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${confirmedList.size} transactions recorded",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Monthly Budget Sheet Live Card (if loaded)
        val monthData = dashboardResponse?.month
        if (monthData != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Monthly Budget (${monthData.month})",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Surface(
                            color = AccentCyan.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "LIVE",
                                color = AccentCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    val tiles = monthData.summaryTiles
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BudgetSummaryTile("Income", tiles.totalIncome?.actual ?: 0.0, tiles.totalIncome?.goal, MpesaGreen, Modifier.weight(1f))
                        BudgetSummaryTile("Bills", tiles.totalBills?.actual ?: 0.0, tiles.totalBills?.goal, AccentAmber, Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BudgetSummaryTile("Debt", tiles.totalDebtPayoff?.actual ?: 0.0, tiles.totalDebtPayoff?.goal, AccentBlue, Modifier.weight(1f))
                        BudgetSummaryTile("Expenses", tiles.totalExpenses?.actual ?: 0.0, tiles.totalExpenses?.goal, AccentRed, Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BudgetSummaryTile("Savings", tiles.totalSavings?.actual ?: 0.0, tiles.totalSavings?.goal, AccentCyan, Modifier.weight(1f))
                        BudgetSummaryTile("Unallocated", tiles.unallocatedIncome?.actual ?: 0.0, null, TextSecondary, Modifier.weight(1f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

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
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Tap to review classification and record to sheet",
                                color = TextSecondary,
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

        Spacer(modifier = Modifier.height(16.dp))

        // Live Test SMS Parser (clean manual tester for real verification)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SMS Detection & Parser Simulator",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Paste any Kenyan M-PESA SMS to test parser and launch review flow.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = testSmsText,
                    onValueChange = { testSmsText = it },
                    placeholder = { Text("Paste M-PESA SMS text here...", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MpesaGreen,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Pre-canned test chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SuggestionChip(
                        onClick = {
                            testSmsText = "TD47XYZ123 Confirmed. Ksh3,500.00 sent to Kenya Power and Lighting Company for account 12345678 on 7/9/26 at 8:15 PM. New M-PESA balance is Ksh12,450.00. Transaction cost, Ksh23.00."
                        },
                        label = { Text("KPLC Bill", fontSize = 11.sp) }
                    )
                    SuggestionChip(
                        onClick = {
                            testSmsText = "TD48ABC456 Confirmed. Ksh1,250.00 paid to NAIVAS SUPERMARKET. on 7/9/26 at 2:30 PM. New M-PESA balance is Ksh11,200.00. Transaction cost, Ksh0.00."
                        },
                        label = { Text("Naivas Till", fontSize = 11.sp) }
                    )
                    SuggestionChip(
                        onClick = {
                            testSmsText = "TD51JKL345 Confirmed. Ksh10,000.00 sent to NCBA LOOP for account 0123456789 on 7/9/26 at 1:15 PM. New M-PESA balance is Ksh49,200.00."
                        },
                        label = { Text("Bank Transfer", fontSize = 11.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val parsed = MpesaParser.parse(testSmsText)
                        if (parsed != null && parsed.transactionCode.isNotBlank()) {
                            TransactionRepository.addPendingTransaction(parsed)
                            parseFeedback = "✓ Parsed code ${parsed.transactionCode} (${parsed.type}) added to review queue!"
                            onNavigateToReview()
                        } else {
                            parseFeedback = "⚠ Could not parse a valid M-PESA confirmed message."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MpesaGreen),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Parse & Open Confirmation")
                }

                if (parseFeedback != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = parseFeedback!!, color = AccentCyan, fontSize = 12.sp)
                }
            }
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
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, color = TextMuted, fontSize = 11.sp)
            Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
        color = DarkBackground,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = label, color = TextMuted, fontSize = 11.sp)
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
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        }
    }
}

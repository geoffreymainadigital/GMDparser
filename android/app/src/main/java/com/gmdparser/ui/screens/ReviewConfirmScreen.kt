package com.gmdparser.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.data.model.DuplicateTransactionException
import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionStatus
import com.gmdparser.data.repository.TaxonomyRepository
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.components.SmsScanDialog
import com.gmdparser.ui.theme.*
import kotlinx.coroutines.launch

enum class FeedbackStyle {
    NONE,
    SUCCESS,
    DUPLICATE,
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewConfirmScreen(
    onNavigateBack: () -> Unit = {}
) {
    val pendingList by TransactionRepository.pendingTransactions.collectAsState()
    val scope = rememberCoroutineScope()

    var submittingTxCode by remember { mutableStateOf<String?>(null) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackStyle by remember { mutableStateOf(FeedbackStyle.NONE) }
    var expandedTxCode by remember { mutableStateOf<String?>(null) }
    var showScanDialog by remember { mutableStateOf(false) }
    var batchSubmitting by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableStateOf("") }

    // Batch Review requirement: oldest first
    val orderedPending = remember(pendingList) {
        pendingList.reversed()
    }

    if (showScanDialog) {
        SmsScanDialog(
            onDismiss = { showScanDialog = false },
            onScanComplete = { stats ->
                feedbackStyle = FeedbackStyle.SUCCESS
                feedbackMessage = "Scan complete: Found ${stats.mpesaFound} M-PESA SMS. Queued ${stats.newlyQueued} new transaction(s)."
            }
        )
    }

    fun submitTransaction(tx: Transaction, shouldRecordFee: Boolean) {
        scope.launch {
            submittingTxCode = tx.transactionCode
            feedbackMessage = null
            feedbackStyle = FeedbackStyle.NONE

            val result = TransactionRepository.confirmAndSubmitTransaction(tx)
            result.fold(
                onSuccess = { res ->
                    val mainRow = res.data?.row
                    if (shouldRecordFee && tx.cost != null && tx.cost > 0.0) {
                        kotlinx.coroutines.delay(1000)
                        val feeTx = Transaction(
                            transactionCode = "${tx.transactionCode}-FEE",
                            amount = tx.cost,
                            type = "Expenses",
                            category = "Transaction Cost",
                            description = "Transaction Cost: ${tx.description}",
                            account = "Mpesa",
                            date = tx.date,
                            time = tx.time,
                            status = TransactionStatus.CONFIRMED
                        )
                        val feeResult = TransactionRepository.confirmAndSubmitTransaction(feeTx)
                        submittingTxCode = null
                        feeResult.fold(
                            onSuccess = { feeRes ->
                                feedbackStyle = FeedbackStyle.SUCCESS
                                feedbackMessage = "✓ ${tx.transactionCode} recorded (row ${mainRow ?: "sheet"}) + Fee recorded (row ${feeRes.data?.row ?: "sheet"})"
                            },
                            onFailure = { feeErr ->
                                if (feeErr is DuplicateTransactionException) {
                                    feedbackStyle = FeedbackStyle.DUPLICATE
                                    val feeRow = if (feeErr.existingRow != null) " at row ${feeErr.existingRow}" else ""
                                    feedbackMessage = "✓ ${tx.transactionCode} recorded (row ${mainRow ?: "sheet"}), but Fee already recorded$feeRow — skipped duplicate fee"
                                } else {
                                    feedbackStyle = FeedbackStyle.ERROR
                                    feedbackMessage = "✓ ${tx.transactionCode} recorded (row ${mainRow ?: "sheet"}), but Fee failed: ${feeErr.message ?: "Unknown error"}"
                                }
                            }
                        )
                    } else {
                        submittingTxCode = null
                        feedbackStyle = FeedbackStyle.SUCCESS
                        feedbackMessage = "✓ Transaction ${tx.transactionCode} recorded in row ${mainRow ?: "sheet"}"
                    }
                },
                onFailure = { err ->
                    submittingTxCode = null
                    if (err is DuplicateTransactionException) {
                        feedbackStyle = FeedbackStyle.DUPLICATE
                        val rowInfo = if (err.existingRow != null) " at row ${err.existingRow}" else ""
                        feedbackMessage = "⚠ Already recorded in sheet$rowInfo — skipped duplicate (${err.transactionCode})"
                    } else {
                        feedbackStyle = FeedbackStyle.ERROR
                        feedbackMessage = "✗ Failed to record transaction: ${err.message ?: "Unknown error"}"
                    }
                }
            )
        }
    }

    /**
     * Batch-submit all currently pending transactions using batchCreateTransactions.
     * A single round-trip replaces N separate round-trips, eliminating the per-transaction
     * flush() recalculation that caused timeouts during SMS inbox catch-up sessions.
     */
    fun submitBatch(txList: List<Transaction>) {
        if (txList.isEmpty()) return
        scope.launch {
            batchSubmitting = true
            batchProgress = "Sending ${txList.size} transaction(s)…"
            feedbackMessage = null
            feedbackStyle = FeedbackStyle.NONE

            val results = try {
                TransactionRepository.confirmAndSubmitBatch(txList)
            } catch (e: Exception) {
                batchSubmitting = false
                batchProgress = ""
                feedbackStyle = FeedbackStyle.ERROR
                feedbackMessage = "✗ Batch failed: ${e.message ?: "Unknown error"}"
                return@launch
            }

            batchSubmitting = false
            batchProgress = ""

            val created = results.count { it.status == "CREATED" }
            val dups    = results.count { it.status == "DUPLICATE" }
            val errors  = results.count { it.status == "VALIDATION_ERROR" || it.status == "NETWORK_ERROR" }

            feedbackStyle = when {
                errors > 0  -> FeedbackStyle.ERROR
                dups > 0 && created == 0 -> FeedbackStyle.DUPLICATE
                dups > 0    -> FeedbackStyle.DUPLICATE
                else        -> FeedbackStyle.SUCCESS
            }
            feedbackMessage = buildString {
                if (created > 0) append("✓ $created recorded")
                if (dups    > 0) {
                    if (created > 0) append("  •  ")
                    append("⚠ $dups duplicate(s) skipped")
                }
                if (errors  > 0) {
                    if (created + dups > 0) append("  •  ")
                    append("✗ $errors failed")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Action & Status Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Review Queue", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Verify and confirm before writing to spreadsheet", color = TextSecondary, fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { showScanDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MpesaGreen),
                border = androidx.compose.foundation.BorderStroke(1.dp, MpesaGreen.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Inbox", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Mandatory Confirmation Policy Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MpesaGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Confirmation Policy",
                    tint = MpesaGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Explicit Confirmation Enforced",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "AutoSync is locked off. No transaction is recorded without your review.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Feedback Banner
        if (feedbackMessage != null) {
            val (containerColor, textColor) = when (feedbackStyle) {
                FeedbackStyle.SUCCESS -> Pair(MpesaGreen.copy(alpha = 0.18f), MpesaGreen)
                FeedbackStyle.DUPLICATE -> Pair(AccentAmber.copy(alpha = 0.22f), AccentAmber)
                FeedbackStyle.ERROR -> Pair(AccentRed.copy(alpha = 0.2f), AccentRed)
                FeedbackStyle.NONE -> Pair(DarkSurfaceCard, TextPrimary)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = containerColor),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = feedbackMessage!!,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Empty state vs Batch List
        if (orderedPending.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 36.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MpesaGreen,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "All Caught Up!",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No pending M-PESA transactions waiting for review.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = { showScanDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MpesaGreen),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan SMS Inbox to Catch Up", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pending Items (${orderedPending.size}) — Oldest First",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                // Confirm All — uses batch endpoint; one round-trip for all pending items.
                // Only shown when there are ≥ 2 items and nothing is currently being sent.
                if (orderedPending.size >= 2 && !batchSubmitting && submittingTxCode == null) {
                    Button(
                        onClick = { submitBatch(orderedPending) },
                        colors = ButtonDefaults.buttonColors(containerColor = MpesaGreen),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Confirm All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Batch progress indicator
            if (batchSubmitting) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MpesaGreen.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MpesaGreen,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = batchProgress,
                            color = MpesaGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Batch list items
            orderedPending.forEach { tx ->
                val isExpanded = expandedTxCode == tx.transactionCode
                val isSubmittingThis = submittingTxCode == tx.transactionCode

                BatchTransactionItemCard(
                    transaction = tx,
                    isExpanded = isExpanded,
                    isSubmitting = isSubmittingThis,
                    onToggleExpand = {
                        expandedTxCode = if (isExpanded) null else tx.transactionCode
                    },
                    onQuickConfirm = {
                        val recordFee = tx.cost != null && tx.cost > 0.0
                        submitTransaction(tx, recordFee)
                    },
                    onCustomConfirm = { editedTx, recordFee ->
                        submitTransaction(editedTx, recordFee)
                    },
                    onDismiss = {
                        TransactionRepository.removePendingTransaction(tx.transactionCode)
                        feedbackStyle = FeedbackStyle.NONE
                        feedbackMessage = "Transaction ${tx.transactionCode} dismissed."
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchTransactionItemCard(
    transaction: Transaction,
    isExpanded: Boolean,
    isSubmitting: Boolean,
    onToggleExpand: () -> Unit,
    onQuickConfirm: () -> Unit,
    onCustomConfirm: (Transaction, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Editable form state for when expanded
    var amountText by remember(transaction) { mutableStateOf(transaction.amount.toString()) }
    var selectedType by remember(transaction) { mutableStateOf(transaction.type) }
    var selectedCategory by remember(transaction) { mutableStateOf(transaction.category) }
    var descriptionText by remember(transaction) { mutableStateOf(transaction.description) }
    var selectedAccount by remember(transaction) { mutableStateOf(transaction.account) }
    var destinationAccountText by remember(transaction) { mutableStateOf(transaction.destinationAccount ?: "") }
    var recordFee by remember(transaction) { mutableStateOf(transaction.cost != null && transaction.cost > 0.0) }

    val liveTypes by TaxonomyRepository.types.collectAsState()
    val liveCategoriesByType by TaxonomyRepository.categoriesByType.collectAsState()
    val liveAccounts by TaxonomyRepository.accounts.collectAsState()

    val availableCategories = liveCategoriesByType[selectedType] ?: emptyList()

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isExpanded) MpesaGreen.copy(alpha = 0.6f) else DarkSurfaceBorder, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Code, Time, Dismiss button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = AccentCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = transaction.transactionCode,
                        color = AccentCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${transaction.date} ${transaction.time}".trim(),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Amount and Description Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ksh ${String.format(java.util.Locale.ROOT, "%,.2f", transaction.amount)}",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = transaction.description,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = if (isExpanded) 3 else 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Suggestions Chips: Type, Category, Fee
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = DarkSurfaceCard,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = transaction.type,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (transaction.category.isNotBlank()) {
                    Surface(
                        color = MpesaGreen.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = transaction.category,
                            color = MpesaGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                if (transaction.cost != null && transaction.cost > 0.0) {
                    Surface(
                        color = AccentAmber.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "+Fee Ksh ${String.format(java.util.Locale.ROOT, "%.2f", transaction.cost)}",
                            color = AccentAmber,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Expandable Inline Editor Section
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = DarkSurfaceBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Amount Override
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (Ksh)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MpesaGreen,
                            unfocusedBorderColor = DarkSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Type Chips
                    Text("Type", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        liveTypes.take(3).forEach { t ->
                            FilterChip(
                                selected = selectedType == t,
                                onClick = {
                                    selectedType = t
                                    if (t == "Balance" && destinationAccountText.isBlank()) destinationAccountText = "Mpesa"
                                    if (t == "Balance") selectedCategory = ""
                                },
                                label = { Text(t, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MpesaGreen,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                    if (liveTypes.size > 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            liveTypes.drop(3).forEach { t ->
                                FilterChip(
                                    selected = selectedType == t,
                                    onClick = {
                                        selectedType = t
                                        if (t == "Balance" && destinationAccountText.isBlank()) destinationAccountText = "Mpesa"
                                        if (t == "Balance") selectedCategory = ""
                                    },
                                    label = { Text(t, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MpesaGreen,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Category Input & Suggestions
                    OutlinedTextField(
                        value = selectedCategory,
                        onValueChange = { selectedCategory = it },
                        label = { Text("Category") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MpesaGreen,
                            unfocusedBorderColor = DarkSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (availableCategories.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableCategories.forEach { cat ->
                                AssistChip(
                                    onClick = { selectedCategory = cat },
                                    label = { Text(cat, fontSize = 10.sp) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (selectedCategory == cat) MpesaGreen.copy(alpha = 0.3f) else DarkSurfaceCard
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Description Override
                    OutlinedTextField(
                        value = descriptionText,
                        onValueChange = { descriptionText = it },
                        label = { Text("Description / Payee") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MpesaGreen,
                            unfocusedBorderColor = DarkSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Account Override
                    OutlinedTextField(
                        value = selectedAccount,
                        onValueChange = { selectedAccount = it },
                        label = { Text("Account") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MpesaGreen,
                            unfocusedBorderColor = DarkSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (selectedType == "Balance" || selectedType == "Transfer") {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = destinationAccountText,
                            onValueChange = { destinationAccountText = it },
                            label = { Text("Destination Account") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    // Fee Toggle in Expanded
                    if (transaction.cost != null && transaction.cost > 0.0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = if (recordFee) MpesaGreen.copy(alpha = 0.12f) else DarkSurfaceCard,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { recordFee = !recordFee }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = recordFee,
                                    onCheckedChange = { recordFee = it },
                                    colors = CheckboxDefaults.colors(checkedColor = MpesaGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "Record Fee: Ksh ${String.format(java.util.Locale.ROOT, "%.2f", transaction.cost)}",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Will record fee as Expenses → Transaction Cost",
                                        color = TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons Row: 1-Tap Quick Confirm vs Edit/Expand
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onToggleExpand,
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isExpanded) "Collapse" else "Edit", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        if (isExpanded) {
                            val parsedAmt = amountText.toDoubleOrNull() ?: transaction.amount
                            val editedTx = transaction.copy(
                                amount = parsedAmt,
                                type = selectedType,
                                category = selectedCategory,
                                description = descriptionText,
                                account = selectedAccount,
                                destinationAccount = if (selectedType == "Balance" || selectedType == "Transfer") destinationAccountText.trim().ifEmpty { null } else null
                            )
                            onCustomConfirm(editedTx, recordFee)
                        } else {
                            onQuickConfirm()
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = MpesaGreen),
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isExpanded) "Save Edited" else "1-Tap Confirm", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

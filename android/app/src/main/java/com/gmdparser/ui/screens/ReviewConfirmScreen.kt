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
import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionStatus
import com.gmdparser.data.repository.TaxonomyRepository
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.parser.MpesaParser
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
        if ((tx.type == "Balance" || tx.type == "Transfer") && tx.destinationAccount.isNullOrBlank()) {
            feedbackStyle = FeedbackStyle.ERROR
            feedbackMessage = "✗ Destination Account is required for Balance transfers."
            return
        }
        scope.launch {
            submittingTxCode = tx.transactionCode
            feedbackMessage = null
            feedbackStyle = FeedbackStyle.NONE

            val isTransfer = (tx.type == "Balance" || tx.type == "Transfer") && !tx.destinationAccount.isNullOrBlank()
            if (isTransfer) {
                // Submit main transaction (which triggers handleCreateTransferPair in Apps Script to write both legs)
                val result = TransactionRepository.confirmAndSubmitTransaction(tx)
                result.fold(
                    onSuccess = { res ->
                        submittingTxCode = null
                        val isDupOk = res.status == "DUPLICATE_OK"
                        feedbackStyle = FeedbackStyle.SUCCESS
                        feedbackMessage = if (isDupOk) {
                            "✓ Transfer ${tx.transactionCode} (-OUT & -IN) already in sheet."
                        } else {
                            "✓ Transfer recorded: -Ksh ${tx.amount} (${tx.account}) and +Ksh ${tx.amount} (${tx.destinationAccount})."
                        }
                    },
                    onFailure = { err ->
                        submittingTxCode = null
                        feedbackStyle = FeedbackStyle.ERROR
                        feedbackMessage = "✗ Transfer failed: ${err.message ?: "Unknown error"}"
                    }
                )
            } else {
                val result = TransactionRepository.confirmAndSubmitTransaction(tx)
                result.fold(
                    onSuccess = { res ->
                        val mainRow = res.data?.row
                        val isDupOk = res.status == "DUPLICATE_OK"
                        val mainLabel = if (isDupOk) {
                            "✓ ${tx.transactionCode} already in sheet (row ${mainRow ?: "?"})"
                        } else {
                            "✓ ${tx.transactionCode} recorded (row ${mainRow ?: "sheet"})"
                        }
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
                                    val feeRow = feeRes.data?.row
                                    val feeDupOk = feeRes.status == "DUPLICATE_OK"
                                    feedbackStyle = FeedbackStyle.SUCCESS
                                    feedbackMessage = if (feeDupOk) {
                                        "$mainLabel + Fee already in sheet (row ${feeRow ?: "?"})"
                                    } else {
                                        "$mainLabel + Fee recorded (row ${feeRow ?: "sheet"})"
                                    }
                                },
                                onFailure = { feeErr ->
                                    feedbackStyle = FeedbackStyle.ERROR
                                    feedbackMessage = "$mainLabel, but Fee failed: ${feeErr.message ?: "Unknown error"}"
                                }
                            )
                        } else {
                            submittingTxCode = null
                            feedbackStyle = FeedbackStyle.SUCCESS
                            feedbackMessage = mainLabel
                        }
                    },
                    onFailure = { err ->
                        submittingTxCode = null
                        feedbackStyle = FeedbackStyle.ERROR
                        feedbackMessage = "✗ Failed to record transaction: ${err.message ?: "Unknown error"}"
                    }
                )
            }
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
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Action & Status Bar
        var showManualTxDialog by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Review Queue", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Verify and confirm before writing to spreadsheet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showManualTxDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("+ Manual Tx", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = { showScanDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan Inbox", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (showManualTxDialog) {
            ManualTransactionDialog(
                onDismiss = { showManualTxDialog = false },
                onStage = { stagedTx ->
                    TransactionRepository.addPendingTransaction(stagedTx)
                    showManualTxDialog = false
                    feedbackStyle = FeedbackStyle.SUCCESS
                    feedbackMessage = "✓ Staged ${stagedTx.transactionCode} (${stagedTx.type} - Ksh ${stagedTx.amount}) to Review Queue"
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Feedback Banner
        if (feedbackMessage != null) {
            val (containerColor, textColor) = when (feedbackStyle) {
                FeedbackStyle.SUCCESS -> Pair(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), MaterialTheme.colorScheme.primary)
                FeedbackStyle.DUPLICATE -> Pair(AccentAmber.copy(alpha = 0.22f), AccentAmber)
                FeedbackStyle.ERROR -> Pair(AccentRed.copy(alpha = 0.2f), AccentRed)
                FeedbackStyle.NONE -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
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

    var manualSmsText by remember { mutableStateOf("") }
    var manualParseFeedback by remember { mutableStateOf<String?>(null) }

    // Empty state vs Batch List
    if (orderedPending.isEmpty()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "All Caught Up!",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No pending M-PESA transactions waiting for review.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // SMS Detection & Parser Simulator
            SmsManualSimulatorCard(
                smsText = manualSmsText,
                onSmsTextChange = { manualSmsText = it },
                feedback = manualParseFeedback,
                onParseAndAdd = { text ->
                    val parsed = MpesaParser.parse(text)
                    if (parsed != null && parsed.transactionCode.isNotBlank()) {
                        TransactionRepository.addPendingTransaction(parsed)
                        manualParseFeedback = "✓ Parsed code ${parsed.transactionCode} (${parsed.type}) added to queue!"
                        manualSmsText = ""
                    } else {
                        manualParseFeedback = "⚠ Could not parse a valid M-PESA confirmed message."
                    }
                }
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pending Items (${orderedPending.size}) — Oldest First",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            // Confirm All — uses batch endpoint; one round-trip for all pending items.
            // Only shown when there are ≥ 2 items and nothing is currently being sent.
            if (orderedPending.size >= 2 && !batchSubmitting && submittingTxCode == null) {
                Button(
                    onClick = { submitBatch(orderedPending) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
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

        Spacer(modifier = Modifier.height(8.dp))
        SmsManualSimulatorCard(
            smsText = manualSmsText,
            onSmsTextChange = { manualSmsText = it },
            feedback = manualParseFeedback,
            onParseAndAdd = { text ->
                val parsed = MpesaParser.parse(text)
                if (parsed != null && parsed.transactionCode.isNotBlank()) {
                    TransactionRepository.addPendingTransaction(parsed)
                    manualParseFeedback = "✓ Parsed code ${parsed.transactionCode} (${parsed.type}) added to queue!"
                    manualSmsText = ""
                } else {
                    manualParseFeedback = "⚠ Could not parse a valid M-PESA confirmed message."
                }
            }
        )
        Spacer(modifier = Modifier.height(24.dp))
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
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
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
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
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = transaction.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = transaction.type,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (transaction.category.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = transaction.category,
                            color = MaterialTheme.colorScheme.primary,
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
                    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Amount Override
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (Ksh)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Type Chips
                    Text("Type", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
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
                                    if (t == "Balance" || t == "Transfer") {
                                        selectedCategory = ""
                                        if (destinationAccountText.isBlank()) destinationAccountText = "Mpesa"
                                    } else if (selectedCategory.isBlank()) {
                                        selectedCategory = availableCategories.firstOrNull() ?: "General"
                                    }
                                },
                                label = { Text(t, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        if (t == "Balance" || t == "Transfer") {
                                            selectedCategory = ""
                                            if (destinationAccountText.isBlank()) destinationAccountText = "Mpesa"
                                        } else if (selectedCategory.isBlank()) {
                                            selectedCategory = availableCategories.firstOrNull() ?: "General"
                                        }
                                    },
                                    label = { Text(t, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }

                    if (selectedType != "Balance" && selectedType != "Transfer") {
                        Spacer(modifier = Modifier.height(10.dp))

                        // Category Input & Suggestions
                        OutlinedTextField(
                            value = selectedCategory,
                            onValueChange = { selectedCategory = it },
                            label = { Text("Category") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
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
                                            containerColor = if (selectedCategory == cat) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                }
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
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Account Override Dropdown
                    var accountExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = accountExpanded,
                        onExpandedChange = { accountExpanded = !accountExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedAccount,
                            onValueChange = { selectedAccount = it },
                            readOnly = true,
                            label = { Text("Account") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = accountExpanded,
                            onDismissRequest = { accountExpanded = false }
                        ) {
                            liveAccounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = { Text(acc) },
                                    onClick = {
                                        selectedAccount = acc
                                        accountExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedType == "Balance" || selectedType == "Transfer") {
                        Spacer(modifier = Modifier.height(10.dp))
                        var destAccountExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = destAccountExpanded,
                            onExpandedChange = { destAccountExpanded = !destAccountExpanded }
                        ) {
                            OutlinedTextField(
                                value = destinationAccountText,
                                onValueChange = { destinationAccountText = it },
                                readOnly = true,
                                label = { Text("Destination Account") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destAccountExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = destAccountExpanded,
                                onDismissRequest = { destAccountExpanded = false }
                            ) {
                                liveAccounts.forEach { acc ->
                                    DropdownMenuItem(
                                        text = { Text(acc) },
                                        onClick = {
                                            destinationAccountText = acc
                                            destAccountExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Fee Toggle in Expanded
                    if (transaction.cost != null && transaction.cost > 0.0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = if (recordFee) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
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
                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "Record Fee: Ksh ${String.format(java.util.Locale.ROOT, "%.2f", transaction.cost)}",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Will record fee as Expenses → Transaction Cost",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

                // Part C restriction logic: Balance and Transfer types intentionally have empty category
                val currentType = if (isExpanded) selectedType else transaction.type
                val isUncategorized = currentType != "Balance" && currentType != "Transfer" && (if (isExpanded) selectedCategory else transaction.category).isBlank()

                if (isUncategorized && !isExpanded) {
                    Text(
                        text = "⚠️ Needs categorization",
                        color = AccentAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    )
                }

                // Action Buttons Row: 1-Tap Quick Confirm vs Edit/Expand
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onToggleExpand,
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                        enabled = !isSubmitting && !isUncategorized,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(2f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isExpanded) "Save Edited" else "1-Tap Confirm", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
        }
    }
}

@Composable
fun SmsManualSimulatorCard(
    smsText: String,
    onSmsTextChange: (String) -> Unit,
    feedback: String?,
    onParseAndAdd: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SMS Detection & Parser Simulator",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Paste any Kenyan M-PESA SMS to test parser and add to review queue.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = smsText,
                onValueChange = onSmsTextChange,
                placeholder = { Text("Paste M-PESA SMS text here...", color = MaterialTheme.colorScheme.outline) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Sample quick chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SuggestionChip(
                    onClick = {
                        onSmsTextChange("TD47XYZ123 Confirmed. Ksh3,500.00 sent to Kenya Power and Lighting Company for account 12345678 on 7/9/26 at 8:15 PM. New M-PESA balance is Ksh12,450.00. Transaction cost, Ksh23.00.")
                    },
                    label = { Text("KPLC Bill", fontSize = 11.sp) }
                )
                SuggestionChip(
                    onClick = {
                        onSmsTextChange("TD48ABC456 Confirmed. Ksh1,250.00 paid to NAIVAS SUPERMARKET. on 7/9/26 at 2:30 PM. New M-PESA balance is Ksh11,200.00. Transaction cost, Ksh0.00.")
                    },
                    label = { Text("Naivas Till", fontSize = 11.sp) }
                )
                SuggestionChip(
                    onClick = {
                        onSmsTextChange("TD51JKL345 Confirmed. Ksh10,000.00 sent to NCBA LOOP for account 0123456789 on 7/9/26 at 1:15 PM. New M-PESA balance is Ksh49,200.00.")
                    },
                    label = { Text("Bank Transfer", fontSize = 11.sp) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = { onParseAndAdd(smsText) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Parse & Stage in Queue", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            if (!feedback.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = feedback, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualTransactionDialog(
    onDismiss: () -> Unit,
    onStage: (Transaction) -> Unit
) {
    val liveTypes by TaxonomyRepository.types.collectAsState()
    val liveCategoriesByType by TaxonomyRepository.categoriesByType.collectAsState()
    val liveAccounts by TaxonomyRepository.accounts.collectAsState()

    var amountText by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("Income") }
    var selectedCategory by remember { mutableStateOf("Salary") }
    var descriptionText by remember { mutableStateOf("") }
    var selectedAccount by remember { mutableStateOf(liveAccounts.firstOrNull { it.contains("Equity", true) } ?: liveAccounts.firstOrNull() ?: "Equity Bank") }
    var destinationAccountText by remember { mutableStateOf(liveAccounts.firstOrNull { it.contains("I&M", true) } ?: "I&M Bank") }
    var dateText by remember { mutableStateOf(java.time.LocalDate.now().toString()) }

    val availableCategories = liveCategoriesByType[selectedType] ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.toDoubleOrNull() ?: 0.0
                    val code = "MN" + System.currentTimeMillis().toString().takeLast(8)
                    val tx = Transaction(
                        transactionCode = code,
                        date = dateText.ifBlank { java.time.LocalDate.now().toString() },
                        time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a")),
                        type = selectedType,
                        category = if (selectedType == "Balance" || selectedType == "Transfer") "" else selectedCategory,
                        description = descriptionText.ifBlank { if (selectedType == "Income") "Direct deposit to $selectedAccount" else "Manual transaction" },
                        amount = amt,
                        account = selectedAccount,
                        destinationAccount = if (selectedType == "Balance" || selectedType == "Transfer") destinationAccountText else null,
                        status = TransactionStatus.PENDING_REVIEW,
                        rawText = "Manual transaction entry: Ksh $amt ($selectedType)"
                    )
                    onStage(tx)
                },
                enabled = amountText.toDoubleOrNull() != null && (amountText.toDoubleOrNull() ?: 0.0) > 0.0 &&
                        (selectedType != "Balance" && selectedType != "Transfer" || 
                         (selectedAccount.isNotBlank() && destinationAccountText.isNotBlank() && !selectedAccount.equals(destinationAccountText, ignoreCase = true))),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(if (selectedType == "Balance" || selectedType == "Transfer") "Stage Transfer (2 Rows)" else "Stage Transaction")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text("Add Manual / Bank Transaction", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Record non-SMS salary, side-hustle inflows, or balance transfers.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Type selector chips
                Text("Transaction Type", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    liveTypes.forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = {
                                selectedType = t
                                if (t == "Balance" || t == "Transfer") {
                                    selectedCategory = ""
                                } else if (selectedCategory.isBlank()) {
                                    selectedCategory = availableCategories.firstOrNull() ?: "General"
                                }
                            },
                            label = { Text(t, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                // Amount Input
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (Ksh)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Category selector dropdown / input (if not Balance)
                if (selectedType != "Balance" && selectedType != "Transfer") {
                    var catExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = catExpanded,
                        onExpandedChange = { catExpanded = !catExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedCategory,
                            onValueChange = { selectedCategory = it },
                            label = { Text("Category") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = catExpanded,
                            onDismissRequest = { catExpanded = false }
                        ) {
                            availableCategories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat) },
                                    onClick = {
                                        selectedCategory = cat
                                        catExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Source Account Dropdown
                var accExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = accExpanded,
                    onExpandedChange = { accExpanded = !accExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedAccount,
                        onValueChange = { selectedAccount = it },
                        readOnly = true,
                        label = { Text(if (selectedType == "Balance" || selectedType == "Transfer") "Source Account" else "Account") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = accExpanded,
                        onDismissRequest = { accExpanded = false }
                    ) {
                        liveAccounts.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text(acc) },
                                onClick = {
                                    selectedAccount = acc
                                    accExpanded = false
                                }
                            )
                        }
                    }
                }

                // Destination Account Dropdown (if Balance/Transfer)
                if (selectedType == "Balance" || selectedType == "Transfer") {
                    var destExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = destExpanded,
                        onExpandedChange = { destExpanded = !destExpanded }
                    ) {
                        OutlinedTextField(
                            value = destinationAccountText,
                            onValueChange = { destinationAccountText = it },
                            readOnly = true,
                            label = { Text("Destination Account") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = destExpanded,
                            onDismissRequest = { destExpanded = false }
                        ) {
                            liveAccounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = { Text(acc) },
                                    onClick = {
                                        destinationAccountText = acc
                                        destExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Description
                OutlinedTextField(
                    value = descriptionText,
                    onValueChange = { descriptionText = it },
                    label = { Text("Description / Payee") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Date
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Date (yyyy-MM-dd)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

package com.gmdparser.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.data.model.TaxonomyDefaults
import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionStatus
import com.gmdparser.data.repository.TaxonomyRepository
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewConfirmScreen(
    onNavigateBack: () -> Unit = {}
) {
    val pendingList by TransactionRepository.pendingTransactions.collectAsState()
    val scope = rememberCoroutineScope()

    var isSubmitting by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Mandatory Confirmation Policy Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MpesaGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Confirmation Policy",
                    tint = MpesaGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Explicit Confirmation Enforced",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "AutoSync is locked to false. Transactions are never silently written to Google Sheets without review.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (feedbackMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isError) AccentRed.copy(alpha = 0.2f) else MpesaGreen.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = feedbackMessage!!,
                    color = if (isError) AccentRed else MpesaGreen,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (pendingList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No Pending Transactions",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "New M-PESA SMS messages will appear here for review.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            Text(
                text = "Pending Review (${pendingList.size})",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Display current transaction to review
            val activeTx = pendingList.first()
            TransactionReviewCard(
                transaction = activeTx,
                isSubmitting = isSubmitting,
                onConfirm = { editedTx, shouldRecordFee ->
                    scope.launch {
                        isSubmitting = true
                        feedbackMessage = null
                        val result = TransactionRepository.confirmAndSubmitTransaction(editedTx)
                        if (result.isSuccess && shouldRecordFee && editedTx.cost != null && editedTx.cost > 0.0) {
                            val feeTx = Transaction(
                                transactionCode = "${editedTx.transactionCode}-FEE",
                                amount = editedTx.cost,
                                type = "Expenses",
                                category = "Transaction Cost",
                                description = "Transaction Cost: ${editedTx.description}",
                                account = "Mpesa",
                                date = editedTx.date,
                                time = editedTx.time,
                                status = TransactionStatus.CONFIRMED
                            )
                            val feeResult = TransactionRepository.confirmAndSubmitTransaction(feeTx)
                            isSubmitting = false
                            val feeRow = feeResult.getOrNull()?.data?.row
                            val mainRow = result.getOrNull()?.data?.row
                            feedbackMessage = "✓ Transaction recorded (row ${mainRow ?: ""}) + Fee recorded (row ${feeRow ?: ""})"
                            isError = false
                        } else {
                            isSubmitting = false
                            result.fold(
                                onSuccess = { res ->
                                    isError = false
                                    feedbackMessage = "✓ Transaction ${editedTx.transactionCode} recorded in row ${res.data?.row ?: "sheet"}"
                                },
                                onFailure = { err ->
                                    isError = true
                                    feedbackMessage = err.message ?: "Failed to record transaction"
                                }
                            )
                        }
                    }
                },
                onDismiss = {
                    TransactionRepository.removePendingTransaction(activeTx.transactionCode)
                    feedbackMessage = "Transaction dismissed without recording."
                    isError = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionReviewCard(
    transaction: Transaction,
    isSubmitting: Boolean,
    onConfirm: (Transaction, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
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

    val typesList = liveTypes
    val availableCategories = liveCategoriesByType[selectedType] ?: emptyList()
    val availableAccounts = liveAccounts

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row: Code & Time
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
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "${transaction.date} ${transaction.time}".trim(),
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Amount Input
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

            Spacer(modifier = Modifier.height(12.dp))

            // Type Selection
            Text("Transaction Type (from Spreadsheet)", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                typesList.take(3).forEach { t ->
                    FilterChip(
                        selected = selectedType == t,
                        onClick = {
                            selectedType = t
                            if (t == "Balance" && destinationAccountText.isBlank()) {
                                destinationAccountText = "Mpesa"
                            }
                            if (t == "Balance") {
                                selectedCategory = ""
                            }
                        },
                        label = { Text(t, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MpesaGreen,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                typesList.drop(3).forEach { t ->
                    FilterChip(
                        selected = selectedType == t,
                        onClick = {
                            selectedType = t
                            if (t == "Balance" && destinationAccountText.isBlank()) {
                                destinationAccountText = "Mpesa"
                            }
                            if (t == "Balance") {
                                selectedCategory = ""
                            }
                        },
                        label = { Text(t, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MpesaGreen,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Category Input & Chips
            Text("Category", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = selectedCategory,
                onValueChange = { selectedCategory = it },
                label = { Text(if (selectedType == "Balance") "Category (Leave blank for Balance)" else "Category") },
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
                            label = { Text(cat, fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selectedCategory == cat) MpesaGreen.copy(alpha = 0.3f) else DarkSurfaceCard
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Description Input
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

            Spacer(modifier = Modifier.height(12.dp))

            // Account Input & Chips
            Text("Funding Account", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
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

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                availableAccounts.forEach { acc ->
                    AssistChip(
                        onClick = { selectedAccount = acc },
                        label = { Text(acc, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selectedAccount == acc) MpesaGreen.copy(alpha = 0.3f) else DarkSurfaceCard
                        )
                    )
                }
            }

            // If Balance or Transfer, show destination account field
            if (selectedType == "Balance" || selectedType == "Transfer") {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = destinationAccountText,
                    onValueChange = { destinationAccountText = it },
                    label = { Text("Destination Account (for Balance transfers)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableAccounts.forEach { acc ->
                        AssistChip(
                            onClick = { destinationAccountText = acc },
                            label = { Text(acc, fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (destinationAccountText == acc) AccentCyan.copy(alpha = 0.3f) else DarkSurfaceCard
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Raw SMS expander / preview
            if (transaction.rawText.isNotBlank()) {
                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = transaction.rawText,
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Transaction Fee Card / Toggle
            if (transaction.cost != null && transaction.cost > 0.0) {
                Surface(
                    color = if (recordFee) MpesaGreen.copy(alpha = 0.15f) else DarkSurfaceCard,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (recordFee) MpesaGreen.copy(alpha = 0.5f) else DarkSurfaceBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { recordFee = !recordFee }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = recordFee,
                            onCheckedChange = { recordFee = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MpesaGreen,
                                uncheckedColor = TextSecondary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Record Transaction Cost: Ksh ${String.format(java.util.Locale.ROOT, "%.2f", transaction.cost)}",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Appends entry under Expenses → Transaction Cost",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Action Buttons: Explicit Confirmation vs Dismiss
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Dismiss")
                }

                Button(
                    onClick = {
                        val parsedAmt = amountText.toDoubleOrNull() ?: transaction.amount
                        val confirmedTx = transaction.copy(
                            amount = parsedAmt,
                            type = selectedType,
                            category = selectedCategory,
                            description = descriptionText,
                            account = selectedAccount,
                            destinationAccount = if (selectedType == "Balance" || selectedType == "Transfer") destinationAccountText.trim().ifEmpty { null } else null
                        )
                        onConfirm(confirmedTx, recordFee)
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = MpesaGreen),
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = "Confirm", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Confirm & Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

package com.example.truesplit.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.min

// Represents a specific transaction needed to settle debts.
private data class SettlementTransaction(
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val amount: Double
)

// Holds the complete state of the settlement screen.
private data class SettleUpState(
    val debtsOwedToUser: List<SettlementTransaction> = emptyList(),
    val debtsUserOwes: List<SettlementTransaction> = emptyList(),
    val isLoading: Boolean = true
)

// Helper class to hold UI details for a settlement item.
private data class SettlementActionDetails(
    val text: String,
    val color: Color,
    val buttonText: String,
    val buttonIcon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettleUpScreen(
    groupId: String,
    navBack: () -> Unit
) {
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val context = LocalContext.current
    val currentUserId = auth.currentUser?.uid ?: return

    var members by remember { mutableStateOf<List<Map<String, String>>?>(null) }
    var expenses by remember { mutableStateOf<List<Map<String, Any>>?>(null) }

    // State for which dialog to show
    var transactionToRemind by remember { mutableStateOf<SettlementTransaction?>(null) }
    var transactionToPay by remember { mutableStateOf<SettlementTransaction?>(null) }

    LaunchedEffect(groupId) {
        // Use SnapshotListeners to get real-time updates
        db.collection("groups").document(groupId).collection("expenses")
            .addSnapshotListener { snapshot, _ ->
                expenses = snapshot?.documents?.mapNotNull { it.data }
            }

        db.collection("groups").document(groupId)
            .addSnapshotListener { snapshot, _ ->
                val rawMembers = snapshot?.get("members")
                members = if (rawMembers is List<*>) {
                    rawMembers.filterIsInstance<Map<String, String>>()
                } else {
                    emptyList()
                }
            }
    }

    val settleUpState = remember(members, expenses) {
        val allMembers = members
        val allExpenses = expenses

        if (allMembers == null || allExpenses == null) {
            return@remember SettleUpState(isLoading = true)
        }
        if (allMembers.size < 2) {
            return@remember SettleUpState(isLoading = false)
        }

        val finalBalances = mutableMapOf<String, Double>()
        allMembers.forEach { member ->
            finalBalances[member["id"]!!] = 0.0
        }

        allExpenses.forEach { expense ->
            val amount = (expense["amount"] as? Number)?.toDouble() ?: 0.0
            val paidBy = expense["paidBy"] as? String
            val receivedBy = expense["receivedBy"] as? String
            val type = expense["type"] as? String ?: "expense"

            if (type == "settle") {
                if (paidBy != null && receivedBy != null) {
                    finalBalances[paidBy] = (finalBalances[paidBy] ?: 0.0) - amount
                    finalBalances[receivedBy] = (finalBalances[receivedBy] ?: 0.0) + amount
                }
            } else {
                if (paidBy != null) {
                    val share = amount / allMembers.size
                    finalBalances[paidBy] = (finalBalances[paidBy] ?: 0.0) + (amount - share)
                    allMembers.forEach { member ->
                        if (member["id"] != paidBy) {
                            finalBalances[member["id"]!!] = (finalBalances[member["id"]!!] ?: 0.0) - share
                        }
                    }
                }
            }
        }

        val debtors = finalBalances.filter { it.value < -0.01 }.toMutableMap()
        val creditors = finalBalances.filter { it.value > 0.01 }.toMutableMap()
        val allSettlements = mutableListOf<SettlementTransaction>()

        while (debtors.isNotEmpty() && creditors.isNotEmpty()) {
            val (debtorId, debtorAmount) = debtors.entries.first()
            val (creditorId, creditorAmount) = creditors.entries.first()

            val transferAmount = min(creditorAmount, debtorAmount.absoluteValue)

            val debtorName = allMembers.find { it["id"] == debtorId }?.get("name") ?: "Unknown"
            val creditorName = allMembers.find { it["id"] == creditorId }?.get("name") ?: "Unknown"

            allSettlements.add(SettlementTransaction(debtorId, debtorName, creditorId, creditorName, transferAmount))

            debtors[debtorId] = debtorAmount + transferAmount
            creditors[creditorId] = creditorAmount - transferAmount

            if ((debtors[debtorId] ?: 0.0).absoluteValue < 0.01) debtors.remove(debtorId)
            if ((creditors[creditorId] ?: 0.0).absoluteValue < 0.01) creditors.remove(creditorId)
        }

        SettleUpState(
            debtsOwedToUser = allSettlements.filter { it.toId == currentUserId },
            debtsUserOwes = allSettlements.filter { it.fromId == currentUserId },
            isLoading = false
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settle Up Balances") },
                navigationIcon = {
                    IconButton(onClick = navBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (settleUpState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            var selectedTabIndex by remember { mutableStateOf(0) }
            val tabs = listOf("You are Owed", "You Owe")

            Column(modifier = Modifier.padding(padding)) {
                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
                    }
                }

                when (selectedTabIndex) {
                    0 -> SettlementList(
                        transactions = settleUpState.debtsOwedToUser,
                        isOwedToUser = true,
                        onActionClick = { transactionToRemind = it }
                    )
                    1 -> SettlementList(
                        transactions = settleUpState.debtsUserOwes,
                        isOwedToUser = false,
                        onActionClick = { transactionToPay = it }
                    )
                }
            }
        }
    }

    // Dialog for sending a "Remind" request
    transactionToRemind?.let { transaction ->
        ConfirmActionDialog(
            onDismiss = { transactionToRemind = null },
            onConfirm = {
                val settleRequest = mapOf(
                    "groupId" to groupId,
                    "from" to transaction.fromId,
                    "to" to currentUserId,
                    "amount" to transaction.amount,
                    "status" to "pending",
                    "requestType" to "reminder",
                    "timestamp" to Timestamp.now()
                )
                db.collection("settle_requests").add(settleRequest)
                    .addOnSuccessListener {
                        Toast.makeText(context, "Reminder sent to ${transaction.fromName}", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, "Failed to send reminder", Toast.LENGTH_SHORT).show()
                    }
            },
            title = "Send Reminder?",
            text = "This will send a settle-up reminder to ${transaction.fromName} for ${NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(transaction.amount)}."
        )
    }

    // New dialog for sending a "Pay" request
    transactionToPay?.let { transaction ->
        RequestPaymentDialog(
            transaction = transaction,
            onDismiss = { transactionToPay = null },
            onConfirm = { amountToPay ->
                val payRequest = mapOf(
                    "groupId" to groupId,
                    "from" to currentUserId, // You are paying
                    "to" to transaction.toId, // You are paying this person
                    "amount" to amountToPay,
                    "status" to "pending",
                    "requestType" to "payment_confirmation",
                    "timestamp" to Timestamp.now()
                )
                db.collection("settle_requests").add(payRequest)
                    .addOnSuccessListener {
                        Toast.makeText(context, "Payment request sent to ${transaction.toName}", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, "Failed to send request", Toast.LENGTH_SHORT).show()
                    }
            }
        )
    }
}

@Composable
private fun SettlementList(
    transactions: List<SettlementTransaction>,
    isOwedToUser: Boolean,
    onActionClick: (SettlementTransaction) -> Unit
) {
    if (transactions.isEmpty()) {
        EmptyState(isOwedToUser = isOwedToUser)
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(transactions, key = { if(isOwedToUser) it.fromId else it.toId }) { transaction ->
                SettlementListItem(
                    transaction = transaction,
                    isOwedToUser = isOwedToUser,
                    onActionClick = { onActionClick(transaction) }
                )
            }
        }
    }
}

@Composable
private fun SettlementListItem(
    transaction: SettlementTransaction,
    isOwedToUser: Boolean,
    onActionClick: () -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    val personName = if (isOwedToUser) transaction.fromName else transaction.toName

    val details = if (isOwedToUser) {
        SettlementActionDetails("Owes you ${currencyFormat.format(transaction.amount)}", Color(0xFF2E7D32), "Remind", Icons.Default.Send)
    } else {
        SettlementActionDetails("You owe ${currencyFormat.format(transaction.amount)}", MaterialTheme.colorScheme.error, "Pay", Icons.Default.Payment)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(text = personName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(personName, style = MaterialTheme.typography.titleMedium)
                Text(details.text, style = MaterialTheme.typography.bodyMedium, color = details.color, fontWeight = FontWeight.SemiBold)
            }
            Button(onClick = onActionClick) {
                Icon(details.buttonIcon, contentDescription = details.buttonText, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(details.buttonText)
            }
        }
    }
}

@Composable
private fun EmptyState(isOwedToUser: Boolean) {
    val message = if (isOwedToUser) {
        "No one owes you money in this group right now."
    } else {
        "You don't owe anyone money in this group."
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "All Settled Up", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("All Settled Up!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun RequestPaymentDialog(
    transaction: SettlementTransaction,
    onDismiss: () -> Unit,
    onConfirm: (amountToPay: Double) -> Unit
) {
    var amountStr by remember { mutableStateOf(String.format("%.2f", transaction.amount)) }
    var isError by remember { mutableStateOf(false) }

    val amountToPay = amountStr.toDoubleOrNull()
    isError = amountToPay == null || amountToPay <= 0 || amountToPay > transaction.amount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request to Pay ${transaction.toName}") },
        text = {
            Column {
                Text("Enter the amount you wish to pay. They will need to confirm this payment.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Amount to Pay") },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = isError,
                    supportingText = {
                        if(isError) Text("Enter a valid amount up to ${String.format("%.2f", transaction.amount)}")
                    },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    amountToPay?.let { onConfirm(it) }
                    onDismiss()
                },
                enabled = !isError
            ) {
                Text("Send Request")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ConfirmActionDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, title: String, text: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = { onConfirm(); onDismiss() }) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
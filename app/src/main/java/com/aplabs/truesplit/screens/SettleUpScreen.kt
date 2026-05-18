@file:OptIn(ExperimentalMaterial3Api::class)

package com.aplabs.truesplit.screens

import android.content.Intent
import android.util.Log
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
import com.aplabs.truesplit.model.SettleRequest
import com.aplabs.truesplit.notification.NotificationHelper
import com.aplabs.truesplit.utils.ExpenseUtils
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.min

private data class SettlementTransaction(
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val amount: Double
)

private data class SettleUpState(
    val debtsOwedToUser: List<SettlementTransaction> = emptyList(),
    val debtsUserOwes: List<SettlementTransaction> = emptyList(),
    val isLoading: Boolean = true
)

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
    groupName: String,
    navBack: () -> Unit
) {

    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val context = LocalContext.current

    val currentUserId = auth.currentUser?.uid ?: return

    var members by remember { mutableStateOf<List<Map<String, String>>?>(null) }
    var expenses by remember { mutableStateOf<List<Map<String, Any>>?>(null) }

    var transactionToRemind by remember {
        mutableStateOf<SettlementTransaction?>(null)
    }

    var transactionToPay by remember {
        mutableStateOf<SettlementTransaction?>(null)
    }

    var requestToConfirm by remember {
        mutableStateOf<SettleRequest?>(null)
    }

    val memberNames = remember {
        mutableStateMapOf<String, String>()
    }

    var namesLoaded by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(members) {

        members?.let { memberList ->

            if (memberList.isEmpty()) {
                namesLoaded = true
                return@let
            }

            if (!namesLoaded || memberNames.size < memberList.size) {

                namesLoaded = false

                var completedFetches = 0

                memberList.forEach { member ->

                    val userId = member["id"] ?: ""

                    if (userId.isBlank()) {
                        completedFetches++

                        if (completedFetches == memberList.size) {
                            namesLoaded = true
                        }

                        return@forEach
                    }

                    db.collection("users")
                        .document(userId)
                        .get()
                        .addOnCompleteListener { task ->

                            completedFetches++

                            var addedName = false

                            if (task.isSuccessful && task.result?.exists() == true) {

                                val profileName =
                                    task.result?.getString("name")

                                if (!profileName.isNullOrBlank()) {
                                    memberNames[userId] = profileName
                                    addedName = true
                                }
                            }

                            if (!addedName) {

                                if (task.exception != null) {
                                    Log.w(
                                        "SettleUpScreen",
                                        "Failed to fetch user name for $userId",
                                        task.exception
                                    )
                                }

                                memberNames[userId] =
                                    member["name"] ?: "Unknown"
                            }

                            if (completedFetches == memberList.size) {
                                namesLoaded = true
                            }
                        }
                }
            }
        }
    }

    DisposableEffect(groupId, currentUserId, members) {

        val requestsListener = db.collection("settle_requests")
            .whereEqualTo("groupId", groupId)
            .whereEqualTo("toId", currentUserId)
            .whereEqualTo("status", "pending")
            .whereEqualTo("requestType", "payment_confirmation")
            .addSnapshotListener { snapshot, error ->

                error?.let {
                    Log.e(
                        "SettleUpScreen",
                        "Payment confirmation listener error",
                        it
                    )
                    return@addSnapshotListener
                }

                snapshot?.documents?.firstOrNull()?.let { doc ->

                    val request =
                        doc.toObject<SettleRequest>()?.copy(id = doc.id)

                    requestToConfirm = request

                } ?: run {
                    requestToConfirm = null
                }
            }

        onDispose {
            requestsListener.remove()
        }
    }

    LaunchedEffect(groupId) {

        db.collection("groups")
            .document(groupId)
            .collection("expenses")
            .addSnapshotListener { snapshot, error ->

                error?.let {
                    Log.e(
                        "SettleUpScreen",
                        "Expenses listener error",
                        it
                    )
                    return@addSnapshotListener
                }

                expenses = snapshot?.documents?.mapNotNull { d ->

                    val data =
                        d.data ?: return@mapNotNull null

                    mutableMapOf<String, Any>().apply {

                        // =====================================
                        // BASIC FIELDS
                        // =====================================

                        put(
                            "amount",
                            (data["amount"] as? Number)
                                ?.toDouble() ?: 0.0
                        )

                        put(
                            "type",
                            data["type"] as? String
                                ?: "expense"
                        )

                        put(
                            "paidBy",
                            data["paidBy"] as? String
                                ?: ""
                        )

                        put(
                            "receivedBy",
                            data["receivedBy"] as? String
                                ?: ""
                        )

                        // =====================================
                        // IMPORTANT FIX
                        // =====================================
                        // ExpenseUtils ONLY reads:
                        //
                        // tx["participants"]
                        //
                        // Your firestore stores:
                        // splitBetween / splitWith
                        //
                        // So convert them properly.
                        // =====================================

                        val participants =
                            when {

                                data["participants"] is List<*> -> {

                                    (data["participants"] as List<*>)
                                        .mapNotNull {
                                            it as? String
                                        }
                                }

                                data["splitBetween"] is List<*> -> {

                                    (data["splitBetween"] as List<*>)
                                        .mapNotNull {
                                            it as? String
                                        }
                                }

                                data["splitWith"] is List<*> -> {

                                    (data["splitWith"] as List<*>)
                                        .mapNotNull {
                                            it as? String
                                        }
                                }

                                else -> emptyList()
                            }

                        put(
                            "participants",
                            participants.distinct()
                        )

                        // =====================================
                        // SPLITS
                        // =====================================

                        val rawSplits =
                            data["splits"]

                        if (rawSplits is Map<*, *>) {

                            val cleanedSplits =
                                mutableMapOf<String, Double>()

                            rawSplits.forEach { (key, value) ->

                                val userId =
                                    key as? String
                                        ?: return@forEach

                                val amount =
                                    when (value) {

                                        is Number ->
                                            value.toDouble()

                                        is String ->
                                            value.toDoubleOrNull()

                                        else -> null
                                    }

                                if (
                                    amount != null &&
                                    amount > 0.0
                                ) {

                                    cleanedSplits[userId] =
                                        amount
                                }
                            }

                            if (cleanedSplits.isNotEmpty()) {

                                put(
                                    "splits",
                                    cleanedSplits
                                )
                            }
                        }

                        // =====================================
                        // DEBUG LOG
                        // =====================================

                        Log.d(
                            "SettleUpScreen",
                            """
                        Expense Parsed:
                        amount=${data["amount"]}
                        paidBy=${data["paidBy"]}
                        participants=$participants
                        splits=${data["splits"]}
                        """.trimIndent()
                        )
                    }
                }
            }

        db.collection("groups")
            .document(groupId)
            .addSnapshotListener { snapshot, error ->

                error?.let {
                    Log.e(
                        "SettleUpScreen",
                        "Members listener error",
                        it
                    )
                    return@addSnapshotListener
                }

                val rawMembers =
                    snapshot?.get("members")

                members =
                    if (rawMembers is List<*>) {

                        rawMembers.filterIsInstance<Map<String, String>>()

                    } else {

                        emptyList()
                    }
            }
    }

    val settleUpState = remember(
        members,
        expenses,
        memberNames,
        namesLoaded
    ) {

        val allMembers = members
        val allExpenses = expenses

        if (
            allMembers == null ||
            allExpenses == null ||
            !namesLoaded
        ) {
            return@remember SettleUpState(isLoading = true)
        }

        if (allMembers.size < 2) {
            return@remember SettleUpState(isLoading = false)
        }

        val simplifiedSettlements =
            ExpenseUtils.buildPairwiseSettlements(
                allExpenses
            )


        val allSettlements =
            simplifiedSettlements.mapNotNull { settlement ->

                val fromName =
                    memberNames[settlement.fromUserId]
                        ?: "Unknown"

                val toName =
                    memberNames[settlement.toUserId]
                        ?: "Unknown"

                SettlementTransaction(
                    fromId = settlement.fromUserId,
                    fromName = fromName,
                    toId = settlement.toUserId,
                    toName = toName,
                    amount = settlement.amount
                )
            }


        SettleUpState(
            debtsOwedToUser =
                allSettlements.filter {
                    it.toId == currentUserId
                },

            debtsUserOwes =
                allSettlements.filter {
                    it.fromId == currentUserId
                },

            isLoading = false
        )
    }

    Scaffold(
        topBar = {

            TopAppBar(
                title = {
                    Text("Settle Up Balances")
                },

                navigationIcon = {

                    IconButton(
                        onClick = navBack
                    ) {

                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->

        if (settleUpState.isLoading) {

            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

        } else {

            var selectedTabIndex by remember {
                mutableStateOf(0)
            }

            val tabs =
                listOf("You are Owed", "You Owe")

            Column(
                modifier = Modifier.padding(padding)
            ) {

                TabRow(
                    selectedTabIndex = selectedTabIndex
                ) {

                    tabs.forEachIndexed { index, title ->

                        Tab(
                            selected = selectedTabIndex == index,

                            onClick = {
                                selectedTabIndex = index
                            },

                            text = {
                                Text(title)
                            }
                        )
                    }
                }

                when (selectedTabIndex) {

                    0 -> {
                        SettlementList(
                            transactions =
                                settleUpState.debtsOwedToUser,

                            isOwedToUser = true,

                            onActionClick = {
                                transactionToRemind = it
                            }
                        )
                    }

                    1 -> {
                        SettlementList(
                            transactions =
                                settleUpState.debtsUserOwes,

                            isOwedToUser = false,

                            onActionClick = {
                                transactionToPay = it
                            }
                        )
                    }
                }
            }
        }
    }

    // ================= REMINDER =================

    transactionToRemind?.let { transaction ->

        val shareMessage =
            "${transaction.fromName}, you owe me ${formatCurrency(transaction.amount)} in the group '$groupName'. " +
                    "\nPlease settle up using this link:\n https://truesplit.airbridge.io/reminder?groupId=$groupId"

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareMessage)
            type = "text/plain"
        }

        AlertDialog(

            onDismissRequest = {
                transactionToRemind = null
            },

            title = {
                Text("Send Reminder")
            },

            text = {

                Column {

                    Text("You'll be sharing this message:")

                    Spacer(Modifier.height(8.dp))

                    Text(
                        shareMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },

            confirmButton = {

                Button(
                    onClick = {

                        // LOCAL NOTIFICATION
                        NotificationHelper.showNotification(
                            context = context,
                            title = "Reminder Sent 🔔",
                            message = "Reminder sent to ${transaction.fromName} for ${formatCurrency(transaction.amount)}"
                        )

                        context.startActivity(
                            Intent.createChooser(
                                sendIntent,
                                "Share reminder via"
                            )
                        )

                        transactionToRemind = null
                    }
                ) {
                    Text("Share")
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        transactionToRemind = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // ================= PAYMENT REQUEST =================

    transactionToPay?.let { transaction ->

        RequestPaymentDialog(
            transaction = transaction,
            groupName = groupName,

            onDismiss = {
                transactionToPay = null
            },

            onConfirm = { amountToPay ->

                val currentUserName =
                    memberNames[currentUserId] ?: "You"

                val payRequest = SettleRequest(
                    groupId = groupId,
                    fromId = currentUserId,
                    fromName = currentUserName,
                    toId = transaction.toId,
                    toName = transaction.toName,
                    amount = amountToPay,
                    requestType = "payment_confirmation",
                    status = "pending",
                    timestamp = Timestamp.now()
                )

                db.collection("settle_requests")
                    .add(payRequest)

                    .addOnSuccessListener {

                        Toast.makeText(
                            context,
                            "Payment request sent to ${transaction.toName}",
                            Toast.LENGTH_SHORT
                        ).show()

                        // LOCAL NOTIFICATION
                        NotificationHelper.showNotification(
                            context = context,
                            title = "Settlement Request Sent 💸",
                            message = "Requested ${transaction.toName} to confirm payment of ${formatCurrency(amountToPay)}"
                        )

                        val activity = hashMapOf(

                            "type" to "SETTLE_REQUEST",

                            "actorId" to currentUserId,

                            "actorName" to currentUserName,

                            "groupId" to groupId,

                            "groupName" to groupName,

                            "amount" to
                                    (amountToPay * 100).toLong(),

                            "currencyCode" to "INR",

                            "timestampMillis" to
                                    System.currentTimeMillis(),

                            "participants" to
                                    listOf(
                                        currentUserId,
                                        transaction.toId
                                    ),

                            "status" to "pending"
                        )

                        db.collection("activities")
                            .add(activity)

                            .addOnFailureListener { e ->

                                Log.w(
                                    "SettleUpScreen",
                                    "Failed to create activity",
                                    e
                                )
                            }

                        transactionToPay = null
                    }

                    .addOnFailureListener { e ->

                        Log.e(
                            "SettleUpScreen",
                            "Error sending payment request",
                            e
                        )

                        Toast.makeText(
                            context,
                            "Failed to send payment request",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        )
    }

    // ================= PAYMENT CONFIRMATION =================

    requestToConfirm?.let { request ->

        AlertDialog(

            onDismissRequest = {

                db.collection("settle_requests")
                    .document(request.id)
                    .update("status", "rejected")

                requestToConfirm = null
            },

            title = {
                Text("Confirm Payment")
            },

            text = {

                Text(
                    "Did you receive ${formatCurrency(request.amount)} from ${request.fromName} in the group '$groupName'?"
                )
            },

            confirmButton = {

                Button(

                    onClick = {

                        val settlement = mapOf(
                            "title" to
                                    "Settlement from ${request.fromName}",

                            "amount" to request.amount,

                            "paidBy" to request.fromId,

                            "receivedBy" to currentUserId,

                            "type" to "settle",

                            "timestamp" to Timestamp.now()
                        )

                        db.collection("groups")
                            .document(groupId)
                            .collection("expenses")
                            .add(settlement)

                            .addOnSuccessListener {

                                db.collection("settle_requests")
                                    .document(request.id)
                                    .update("status", "confirmed")

                                    .addOnSuccessListener {

                                        Toast.makeText(
                                            context,
                                            "Payment confirmed!",
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        // LOCAL NOTIFICATION
                                        NotificationHelper.showNotification(
                                            context = context,
                                            title = "Settlement Completed ✅",
                                            message = "${request.fromName} settled ${formatCurrency(request.amount)} in $groupName"
                                        )

                                        val currentUserName =
                                            memberNames[currentUserId]
                                                ?: "You"

                                        val activity = hashMapOf(

                                            "type" to "SETTLE_APPROVED",

                                            "actorId" to currentUserId,

                                            "actorName" to currentUserName,

                                            "groupId" to request.groupId,

                                            "groupName" to groupName,

                                            "amount" to
                                                    (request.amount * 100).toLong(),

                                            "currencyCode" to "INR",

                                            "timestampMillis" to
                                                    System.currentTimeMillis(),

                                            "participants" to
                                                    listOf(
                                                        request.fromId,
                                                        request.toId
                                                    ),

                                            "originalRequesterId" to
                                                    request.fromId,

                                            "originalRequesterName" to
                                                    request.fromName
                                        )

                                        db.collection("activities")
                                            .add(activity)

                                            .addOnFailureListener { e ->

                                                Log.e(
                                                    "SettleUpScreen",
                                                    "Failed to create settle activity",
                                                    e
                                                )
                                            }
                                    }

                                requestToConfirm = null
                            }

                            .addOnFailureListener { e ->

                                Log.e(
                                    "SettleUpScreen",
                                    "Error recording payment",
                                    e
                                )

                                Toast.makeText(
                                    context,
                                    "Failed to confirm payment",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                ) {
                    Text("Confirm Received")
                }
            },

            dismissButton = {

                TextButton(

                    onClick = {

                        db.collection("settle_requests")
                            .document(request.id)
                            .update("status", "rejected")

                        requestToConfirm = null
                    }

                ) {
                    Text("Cancel")
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

        EmptyState(
            isOwedToUser = isOwedToUser
        )

    } else {

        LazyColumn(
            contentPadding = PaddingValues(16.dp),

            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            items(
                transactions,
                key = {
                    "${it.fromId}_${it.toId}_${it.amount}"
                }

            ) { transaction ->

                SettlementListItem(
                    transaction = transaction,
                    isOwedToUser = isOwedToUser,
                    onActionClick = {
                        onActionClick(transaction)
                    }
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

    val personName =
        if (isOwedToUser) {
            transaction.fromName
        } else {
            transaction.toName
        }

    val details =
        if (isOwedToUser) {

            SettlementActionDetails(
                "Owes you ${formatCurrency(transaction.amount)}",
                Color(0xFF2E7D32),
                "Remind",
                Icons.Default.Send
            )

        } else {

            SettlementActionDetails(
                "You owe ${formatCurrency(transaction.amount)}",
                MaterialTheme.colorScheme.error,
                "Pay",
                Icons.Default.Payment
            )
        }

    Card(
        modifier = Modifier.fillMaxWidth(),

        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        ),

        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        )
    ) {

        Row(
            modifier = Modifier.padding(16.dp),

            verticalAlignment = Alignment.CenterVertically,

            horizontalArrangement =
                Arrangement.spacedBy(16.dp)
        ) {

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer
                    ),

                contentAlignment = Alignment.Center
            ) {

                Text(
                    text =
                        personName.take(1).uppercase(),

                    style =
                        MaterialTheme.typography.titleMedium,

                    color =
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    personName,
                    style =
                        MaterialTheme.typography.titleMedium
                )

                Text(
                    details.text,

                    style =
                        MaterialTheme.typography.bodyMedium,

                    color = details.color,

                    fontWeight = FontWeight.SemiBold
                )
            }

            Button(
                onClick = onActionClick
            ) {

                Icon(
                    details.buttonIcon,
                    contentDescription = details.buttonText,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(Modifier.width(8.dp))

                Text(details.buttonText)
            }
        }
    }
}

@Composable
private fun EmptyState(
    isOwedToUser: Boolean
) {

    val message =
        if (isOwedToUser) {
            "No one owes you money in this group right now."
        } else {
            "You don't owe anyone money in this group."
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),

        contentAlignment = Alignment.Center
    ) {

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "All Settled Up",

                modifier = Modifier.size(64.dp),

                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "All Settled Up!",

                style =
                    MaterialTheme.typography.headlineSmall,

                fontWeight = FontWeight.Bold
            )

            Text(
                message,

                style =
                    MaterialTheme.typography.bodyMedium,

                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,

                textAlign = TextAlign.Center,

                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun RequestPaymentDialog(
    transaction: SettlementTransaction,
    groupName: String,
    onDismiss: () -> Unit,
    onConfirm: (amountToPay: Double) -> Unit
) {

    var amountStr by remember {
        mutableStateOf(
            String.format("%.2f", transaction.amount)
        )
    }

    var isError by remember {
        mutableStateOf(false)
    }

    val amountToPay =
        amountStr.toDoubleOrNull() ?: 0.0

    isError =
        amountStr.isBlank() ||
                amountToPay <= 0 ||
                amountToPay > (transaction.amount + 0.01)

    AlertDialog(

        onDismissRequest = onDismiss,

        title = {
            Text("Request to Pay ${transaction.toName}")
        },

        text = {

            Column {

                Text(
                    "Group: $groupName",

                    style =
                        MaterialTheme.typography.bodyMedium,

                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Enter the amount you wish to pay. They will need to confirm this payment."
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = amountStr,

                    onValueChange = {
                        amountStr = it
                    },

                    label = {
                        Text("Amount to Pay")
                    },

                    prefix = {
                        Text("₹")
                    },

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Number
                        ),

                    isError = isError,

                    supportingText = {

                        if (isError) {

                            Text(
                                "Enter valid amount up to ${
                                    NumberFormat.getCurrencyInstance(
                                        Locale("en", "IN")
                                    ).format(transaction.amount)
                                }"
                            )
                        }
                    },

                    singleLine = true
                )
            }
        },

        confirmButton = {

            Button(

                onClick = {

                    if (!isError) {
                        onConfirm(amountToPay)
                        onDismiss()
                    }
                },

                enabled = !isError
            ) {
                Text("Send Request")
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}

private fun formatCurrency(
    amount: Double
): String {

    return NumberFormat
        .getCurrencyInstance(Locale("en", "IN"))
        .format(amount)
}
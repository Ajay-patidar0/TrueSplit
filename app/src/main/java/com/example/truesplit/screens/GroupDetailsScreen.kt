package com.example.truesplit.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupDetailScreen(
    groupId: String,
    navController: NavController,
    navBack: () -> Unit
) {
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val context = LocalContext.current

    val currentUser = auth.currentUser
    val currentUserId = currentUser?.uid ?: return

    var groupName by remember { mutableStateOf("") }
    var groupColor by remember { mutableStateOf(Color.Gray) }
    var members by remember { mutableStateOf(listOf<Map<String, String>>()) }

    var allTransactions by remember { mutableStateOf(listOf<Map<String, Any>>()) }
    val expensesOnly = remember(allTransactions) {
        allTransactions.filter { it["type"] != "settle" }
    }
    val settlementsOnly = remember(allTransactions) {
        allTransactions.filter { it["type"] == "settle" }
    }

    var createdBy by remember { mutableStateOf("") }

    var showInviteDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableStateOf(0) }

    LaunchedEffect(groupId) {
        db.collection("groups").document(groupId).addSnapshotListener { snapshot, _ ->
            snapshot?.let {
                groupName = it.getString("name") ?: "Group"
                createdBy = it.getString("createdBy") ?: ""
                val colorString = it.getString("color") ?: "#6200EE"
                try {
                    groupColor = Color(android.graphics.Color.parseColor(colorString))
                } catch (e: IllegalArgumentException) {
                    groupColor = Color.Gray
                }
                val rawMembers = it.get("members")
                members = when (rawMembers) {
                    is List<*> -> rawMembers.filterIsInstance<Map<String, String>>()
                    else -> emptyList()
                }
            }
        }

        db.collection("groups").document(groupId)
            .collection("expenses")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                allTransactions = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
            }
    }

    // This calculation is now used for both the user's balance and the group deletion check.
    val groupBalances = remember(allTransactions, members) {
        val balances = mutableMapOf<String, Double>()
        if (members.isEmpty()) return@remember balances

        members.forEach { member ->
            balances[member["id"]!!] = 0.0
        }

        allTransactions.forEach { transaction ->
            val amount = (transaction["amount"] as? Number)?.toDouble() ?: 0.0
            val type = transaction["type"] as? String ?: "expense"
            val paidBy = transaction["paidBy"] as? String
            val receivedBy = transaction["receivedBy"] as? String

            if (type == "settle") {
                if (paidBy != null && receivedBy != null && balances.containsKey(paidBy) && balances.containsKey(receivedBy)) {
                    balances[paidBy] = balances.getValue(paidBy) + amount
                    balances[receivedBy] = balances.getValue(receivedBy) - amount
                }
            } else {
                if (paidBy != null && balances.containsKey(paidBy)) {
                    val share = amount / members.size
                    balances[paidBy] = balances.getValue(paidBy) + (amount - share)
                    members.forEach { member ->
                        if (member["id"] != paidBy) {
                            balances[member["id"]!!] = balances.getValue(member["id"]!!) - share
                        }
                    }
                }
            }
        }
        balances
    }

    val userBalance = groupBalances[currentUserId] ?: 0.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(groupName) },
                navigationIcon = {
                    IconButton(onClick = navBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showInviteDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Invite Member")
                    }
                    if (currentUser.email == createdBy) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Group")
                        }
                    } else if (userBalance.absoluteValue < 0.01) {
                        IconButton(onClick = { showLeaveConfirm = true }) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Leave Group")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = groupColor,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            GroupDetailFABs(
                balance = userBalance,
                onAddExpenseClick = { navController.navigate("addExpense/$groupId") },
                onSettleUpClick = { navController.navigate("settle_up/$groupId") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                OverallBalanceCard(balance = userBalance)
            }

            item {
                MembersCard(members = members)
            }

            stickyHeader {
                val tabs = listOf("Expenses", "Settlements")
                Surface(shadowElevation = 2.dp) {
                    TabRow(selectedTabIndex = selectedTabIndex) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = { Text(title) }
                            )
                        }
                    }
                }
            }

            val listToDisplay = if (selectedTabIndex == 0) expensesOnly else settlementsOnly
            val emptyMessage = if (selectedTabIndex == 0) "No expenses yet. Add one to get started!" else "No settlements have been recorded."

            if (listToDisplay.isEmpty()) {
                item {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                items(listToDisplay, key = { it["timestamp"].toString() }) { transaction ->
                    TransactionListItem(
                        transaction = transaction,
                        members = members,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmActionDialog(
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                // THIS IS THE CORRECTED, SAFE LOGIC FOR DELETING A GROUP.
                val isAllSettled = groupBalances.values.all { it.absoluteValue < 0.01 }
                if (isAllSettled) {
                    db.collection("groups").document(groupId).delete()
                        .addOnSuccessListener {
                            Toast.makeText(context, "Group deleted", Toast.LENGTH_SHORT).show()
                            navBack()
                        }
                } else {
                    Toast.makeText(context, "Cannot delete group. All members must settle up first.", Toast.LENGTH_LONG).show()
                }
            },
            title = "Delete Group",
            text = "Are you sure you want to delete this group? This can only be done if all balances are settled."
        )
    }

    if (showLeaveConfirm) {
        ConfirmActionDialog(
            onDismiss = { showLeaveConfirm = false },
            onConfirm = {
                val updatedMembers = members.filter { it["id"] != currentUserId }
                val task = if (updatedMembers.isEmpty()) {
                    db.collection("groups").document(groupId).delete()
                } else {
                    db.collection("groups").document(groupId).update("members", updatedMembers)
                }
                task.addOnSuccessListener {
                    Toast.makeText(context, "You left the group", Toast.LENGTH_SHORT).show()
                    navBack()
                }
            },
            title = "Leave Group",
            text = "Are you sure you want to leave this group?"
        )
    }

    if (showInviteDialog) {
        val encodedGroupName = Uri.encode(groupName)
        val inviteLink = "https://truesplit.airbridge.io/join?groupId=$groupId&groupName=$encodedGroupName"
        ShareInviteLinkDialog(
            onDismiss = { showInviteDialog = false },
            groupName = groupName,
            inviteLink = inviteLink
        )
    }
}

@Composable
fun OverallBalanceCard(balance: Double) {
    val (balanceText, balanceColor) = when {
        balance > 0.01 -> "You are owed" to Color(0xFF2E7D32)
        balance < -0.01 -> "You owe" to MaterialTheme.colorScheme.error
        else -> "You are all settled up" to MaterialTheme.colorScheme.onSurface
    }
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = balanceText, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currencyFormat.format(balance.absoluteValue),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = balanceColor
            )
        }
    }
}

@Composable
fun MembersCard(members: List<Map<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Text(
                text = "Members",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(members, key = { it["id"]!! }) { member ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(64.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (member["name"] ?: "?").take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = member["name"] ?: "Unknown", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionListItem(
    transaction: Map<String, Any>,
    members: List<Map<String, String>>,
    modifier: Modifier = Modifier
) {
    val title = transaction["title"] as? String ?: "Untitled"
    val amount = (transaction["amount"] as? Number)?.toDouble() ?: 0.0
    val paidById = transaction["paidBy"] as? String ?: ""
    val paidByName = members.find { it["id"] == paidById }?.get("name") ?: "Unknown"
    val type = transaction["type"] as? String ?: "expense"
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    val (icon, subtitle, amountColor) = if (type == "settle") {
        val receivedById = transaction["receivedBy"] as? String ?: ""
        val receivedByName = members.find { it["id"] == receivedById }?.get("name") ?: "Unknown"
        Triple(Icons.Outlined.CheckCircle, "$paidByName paid $receivedByName", Color(0xFF2E7D32))
    } else {
        val categoryIcon = when {
            title.contains("food", true) || title.contains("pizza", true) -> Icons.Outlined.Fastfood
            title.contains("travel", true) || title.contains("uber", true) -> Icons.Outlined.Commute
            title.contains("shop", true) -> Icons.Outlined.ShoppingCart
            title.contains("movie", true) -> Icons.Outlined.Theaters
            else -> Icons.AutoMirrored.Outlined.ReceiptLong
        }
        Triple(categoryIcon, "Paid by $paidByName", MaterialTheme.colorScheme.onSurface)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Category",
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = currencyFormat.format(amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = amountColor
            )
        }
    }
}

@Composable
private fun GroupDetailFABs(
    balance: Double,
    onAddExpenseClick: () -> Unit,
    onSettleUpClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (balance.absoluteValue > 0.01) {
            ExtendedFloatingActionButton(
                onClick = onSettleUpClick,
                icon = { Icon(Icons.Default.Check, "Settle Up") },
                text = { Text("Settle Up") }
            )
        }
        FloatingActionButton(onClick = onAddExpenseClick) {
            Icon(Icons.Default.Add, "Add Expense")
        }
    }
}

@Composable
private fun ConfirmActionDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, title: String, text: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = { onConfirm(); onDismiss() }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ShareInviteLinkDialog(
    onDismiss: () -> Unit,
    groupName: String,
    inviteLink: String
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite via Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Share this link with anyone you want to invite to the '$groupName' group.")
                OutlinedTextField(
                    value = inviteLink,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Group Invite Link") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Join my group '$groupName' on TrueSplit using this link:\n$inviteLink")
                }
                context.startActivity(Intent.createChooser(intent, "Share Invite Link"))
                onDismiss()
            }) {
                Text("Share Link")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
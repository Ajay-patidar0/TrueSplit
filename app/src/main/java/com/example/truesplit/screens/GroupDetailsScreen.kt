package com.example.truesplit.screens

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
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
    var expenses by remember { mutableStateOf(listOf<Map<String, Any>>()) }
    var createdBy by remember { mutableStateOf("") }

    var showInviteDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showSettleDialog by remember { mutableStateOf(false) }

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
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                expenses = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
            }
    }

    val userBalance = remember(expenses, members, currentUserId) {
        var balance = 0.0
        val totalMembers = members.size.takeIf { it > 0 } ?: 1
        expenses.forEach { expense ->
            val amount = (expense["amount"] as? Number)?.toDouble() ?: 0.0
            val type = expense["type"] as? String ?: "expense"
            val paidBy = expense["paidBy"] as? String
            val receivedBy = expense["receivedBy"] as? String

            if (type == "settle") {
                if (paidBy == currentUserId) balance -= amount
                else if (receivedBy == currentUserId) balance += amount
            } else if (totalMembers > 0) {
                val share = amount / totalMembers
                if (paidBy == currentUserId) {
                    balance += amount - share
                } else if (members.any { it["id"] == currentUserId }) {
                    balance -= share
                }
            }
        }
        balance
    }

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
                onSettleUpClick = { showSettleDialog = true }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
        ) {
            OverallBalanceCard(balance = userBalance)
            MembersCard(members = members)

            ExpenseSectionHeader()
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                if (expenses.isEmpty()) {
                    item {
                        Text(
                            "No expenses yet. Add one to get started!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                        )
                    }
                } else {
                    items(expenses, key = { it["timestamp"].toString() }) { expense ->
                        ExpenseListItem(
                            expense = expense,
                            members = members,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }

    if (showSettleDialog) {
        SettleUpDialog(
            currentUserId = currentUserId,
            expenses = expenses,
            members = members,
            onDismiss = { showSettleDialog = false },
            onSettleRequest = { selectedId, amount ->
                val data = mapOf(
                    "groupId" to groupId,
                    "from" to currentUserId,
                    "to" to selectedId,
                    "amount" to amount,
                    "status" to "pending",
                    "timestamp" to Timestamp.now()
                )
                db.collection("settle_requests").add(data)
                    .addOnSuccessListener {
                        Toast.makeText(context, "Request sent", Toast.LENGTH_SHORT).show()
                        showSettleDialog = false
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, "Failed to send request", Toast.LENGTH_SHORT).show()
                    }
            }
        )
    }

    if (showDeleteConfirm) {
        ConfirmActionDialog(
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                db.collection("groups").document(groupId).delete()
                    .addOnSuccessListener {
                        Toast.makeText(context, "Group deleted", Toast.LENGTH_SHORT).show()
                        navBack()
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, "Failed to delete group", Toast.LENGTH_SHORT).show()
                    }
            },
            title = "Delete Group",
            text = "Are you sure you want to delete this group? This action cannot be undone."
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
                }.addOnFailureListener {
                    Toast.makeText(context, "Failed to leave group", Toast.LENGTH_SHORT).show()
                }
            },
            title = "Leave Group",
            text = "Are you sure you want to leave this group?"
        )
    }

    if (showInviteDialog) {
        InviteMemberDialog(
            onDismiss = { showInviteDialog = false }
        ) { email ->
            if (members.none { it["email"] == email }) {
                val encodedGroupName = Uri.encode(groupName)
                val inviteLink = "https://truesplit.airbridge.io/join?groupId=$groupId&groupName=$encodedGroupName"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, "You're invited to join '$groupName' on TrueSplit")
                    putExtra(Intent.EXTRA_TEXT, "Join the group in TrueSplit using this link:\n$inviteLink")
                }
                context.startActivity(Intent.createChooser(intent, "Send Invitation"))
                showInviteDialog = false
            } else {
                Toast.makeText(context, "This user is already in the group.", Toast.LENGTH_SHORT).show()
            }
        }
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
            modifier = Modifier.padding(16.dp),
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
fun ExpenseSectionHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(all = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "Expense History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ExpenseListItem(
    expense: Map<String, Any>,
    members: List<Map<String, String>>,
    modifier: Modifier = Modifier
) {
    val title = expense["title"] as? String ?: "Untitled"
    val amount = (expense["amount"] as? Number)?.toDouble() ?: 0.0
    val paidById = expense["paidBy"] as? String ?: ""
    val paidByName = members.find { it["id"] == paidById }?.get("name") ?: "Unknown"
    val type = expense["type"] as? String ?: "expense"
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    val (icon, amountColor) = if (type == "settle") {
        Icons.Outlined.CheckCircle to Color(0xFF2E7D32)
    } else {
        val categoryIcon = when {
            title.contains("food", true) || title.contains("pizza", true) -> Icons.Outlined.Fastfood
            title.contains("travel", true) || title.contains("uber", true) -> Icons.Outlined.Commute
            title.contains("shop", true) -> Icons.Outlined.ShoppingCart
            title.contains("movie", true) -> Icons.Outlined.Theaters
            else -> Icons.AutoMirrored.Outlined.ReceiptLong
        }
        categoryIcon to MaterialTheme.colorScheme.onSurface
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
                Text("Paid by $paidByName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                text = { Text("Settle Up") },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        FloatingActionButton(onClick = onAddExpenseClick) {
            Icon(Icons.Default.Add, "Add Expense")
        }
    }
}

@Composable
private fun SettleUpDialog(
    currentUserId: String,
    expenses: List<Map<String, Any>>,
    members: List<Map<String, String>>,
    onDismiss: () -> Unit,
    onSettleRequest: (selectedId: String, amount: Double) -> Unit
) {
    var selectedId by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }

    val owedToYouMap = remember(expenses, members, currentUserId) {
        val map = mutableMapOf<String, Double>()
        val totalMembers = members.size.takeIf { it > 0 } ?: 1
        expenses.forEach { expense ->
            val amount = (expense["amount"] as? Number)?.toDouble() ?: 0.0
            val type = expense["type"] as? String ?: "expense"
            val paidBy = expense["paidBy"] as? String
            val receivedBy = expense["receivedBy"] as? String
            if (type == "settle") {
                if (paidBy == currentUserId && receivedBy != null) map[receivedBy] = (map[receivedBy] ?: 0.0) - amount
                else if (receivedBy == currentUserId && paidBy != null) map[paidBy] = (map[paidBy] ?: 0.0) + amount
            } else if (totalMembers > 0) {
                val share = amount / totalMembers
                if (paidBy == currentUserId) {
                    members.filter { it["id"] != currentUserId }.forEach { other ->
                        val otherId = other["id"] ?: return@forEach
                        map[otherId] = (map[otherId] ?: 0.0) + share
                    }
                } else if (paidBy != null && members.any { it["id"] == currentUserId }) {
                    map[paidBy] = (map[paidBy] ?: 0.0) - share
                }
            }
        }
        map.filterValues { it > 0.01 }
    }

    val membersYouCanSettleWith = members.filter { owedToYouMap.containsKey(it["id"]) }
    val maxAmount = owedToYouMap[selectedId] ?: 0.0

    LaunchedEffect(selectedId) {
        amountStr = if (maxAmount > 0) String.format("%.2f", maxAmount) else ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settle Up") },
        text = {
            Column {
                if (membersYouCanSettleWith.isEmpty()) {
                    Text("No one owes you money right now.")
                } else {
                    DropdownMenuSelector(items = membersYouCanSettleWith, selectedId = selectedId, onSelect = { selectedId = it })
                    Spacer(Modifier.height(12.dp))
                    if (selectedId.isNotEmpty()) {
                        OutlinedTextField(
                            value = amountStr,
                            onValueChange = { newValue ->
                                val num = newValue.toDoubleOrNull()
                                if (newValue.isEmpty() || (num != null && num >= 0 && num <= maxAmount)) {
                                    amountStr = newValue
                                }
                            },
                            label = { Text("Amount") },
                            suffix = { Text(String.format("(Max: ₹%.2f)", maxAmount)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountStr.toDoubleOrNull() ?: 0.0
                    if (selectedId.isNotBlank() && amount > 0) onSettleRequest(selectedId, amount)
                },
                enabled = selectedId.isNotBlank() && (amountStr.toDoubleOrNull() ?: 0.0) > 0
            ) { Text("Send Request") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownMenuSelector(items: List<Map<String, String>>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = items.find { it["id"] == selectedId }?.get("name") ?: "Select a member"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Settle with") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { member ->
                val memberId = member["id"]; val memberName = member["name"]
                if (memberId != null && memberName != null) {
                    DropdownMenuItem(text = { Text(memberName) }, onClick = { onSelect(memberId); expanded = false })
                }
            }
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
private fun InviteMemberDialog(onDismiss: () -> Unit, onInvite: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    val isEmailValid = remember(email) { Patterns.EMAIL_ADDRESS.matcher(email).matches() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite Member") },
        text = {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("User's Email") },
                singleLine = true,
                isError = email.isNotBlank() && !isEmailValid,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { onInvite(email) }, enabled = isEmailValid) { Text("Send Invite") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

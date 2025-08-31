package com.example.truesplit.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Commute
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlin.math.max

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
    var groupColor by remember { mutableStateOf(Color(0xFF6750A4)) }
    var members by remember { mutableStateOf(listOf<Map<String, String>>()) }

    // userId -> name (pulled from /users to ensure profile name appears)
    val memberNames = remember { mutableStateMapOf<String, String>() }

    var allTransactions by remember { mutableStateOf(listOf<Map<String, Any>>()) }
    val expensesOnly = remember(allTransactions) { allTransactions.filter { it["type"] != "settle" } }
    val settlementsOnly = remember(allTransactions) { allTransactions.filter { it["type"] == "settle" } }

    var createdBy by remember { mutableStateOf("") }

    var showInviteDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableStateOf(0) }

    // Pull group & members
    LaunchedEffect(groupId) {
        db.collection("groups").document(groupId).addSnapshotListener { snapshot, _ ->
            snapshot?.let {
                groupName = it.getString("name") ?: "Group"
                createdBy = it.getString("createdBy") ?: ""
                val colorString = it.getString("color") ?: "#6750A4"
                groupColor = try { Color(android.graphics.Color.parseColor(colorString)) } catch (_: IllegalArgumentException) { Color(0xFF6750A4) }

                val rawMembers = it.get("members")
                members = if (rawMembers is List<*>) rawMembers.filterIsInstance<Map<String, String>>() else emptyList()
            }
        }

        db.collection("groups").document(groupId)
            .collection("expenses")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                allTransactions = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
            }
    }

    // Fetch display names from /users
    LaunchedEffect(members) {
        val firestore = FirebaseFirestore.getInstance()
        members.forEach { m ->
            val uid = m["id"] ?: return@forEach
            if (uid !in memberNames) {
                firestore.collection("users").document(uid).get()
                    .addOnSuccessListener { doc ->
                        val n = doc.getString("name") ?: m["name"] ?: "Unknown"
                        memberNames[uid] = n
                    }
            }
        }
    }

    // Calculate balances accurately based on selected participants only
    val groupBalances = remember(allTransactions, members) {
        calculateGroupBalancesAccurate(members, allTransactions)
    }
    val userBalance = groupBalances[currentUserId] ?: 0.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(groupName, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = navBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                },
                actions = {
                    IconButton(onClick = { showInviteDialog = true }) { Icon(Icons.Filled.PersonAdd, contentDescription = "Invite", tint = Color.White) }
                    if (currentUser?.email == createdBy) {
                        IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White) }
                    } else {
                        if (userBalance.absoluteValue < 0.01) {
                            // Leave group only when settled
                            // (icon not auto-mirrored exit to keep UI minimal)
                            IconButton(onClick = { showLeaveConfirm = true }) {
                                // Use simple icon to keep minimalistic
                                Icon(Icons.Filled.Delete, contentDescription = "Leave Group", tint = Color.White)
                            }
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
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item { OverallBalanceCard(balance = userBalance) }
            item { MembersCard(members = members, memberNames = memberNames) }

            stickyHeader {
                val tabs = listOf("Expenses", "Settlements")
                Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emptyMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(listToDisplay, key = { it["timestamp"].toString() }) { transaction ->
                    TransactionListItem(
                        transaction = transaction,
                        memberNames = memberNames,
                        currentUserId = currentUserId,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clickable {
                                // TODO: Navigate to detail screen later, pass doc id when you wire it
                                // navController.navigate("expenseDetail/<docId>")
                            }
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmActionDialog(
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
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
                if (userBalance.absoluteValue < 0.01) {
                    val updatedMembers = members.filter { it["id"] != currentUserId }
                    val updatedMemberIds = members.mapNotNull { it["id"] }.filter { it != currentUserId }

                    val task = if (updatedMembers.isEmpty()) {
                        db.collection("groups").document(groupId).delete()
                    } else {
                        db.collection("groups").document(groupId).update(
                            mapOf(
                                "members" to updatedMembers,
                                "memberIds" to updatedMemberIds
                            )
                        )
                    }
                    task.addOnSuccessListener {
                        Toast.makeText(context, "You left the group", Toast.LENGTH_SHORT).show()
                        navBack()
                    }
                } else {
                    Toast.makeText(context, "Cannot leave group until your balance is settled.", Toast.LENGTH_LONG).show()
                }
            },
            title = "Leave Group",
            text = "You can only leave this group if your balance is zero. Are you sure you want to leave?"
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

/* ------------------------- UI CARDS ------------------------- */

@Composable
fun OverallBalanceCard(balance: Double) {
    val (balanceText, balanceColor, bg) = when {
        balance > 0.01 -> Triple("You are owed", Color(0xFF1B5E20), Color(0x331B5E20))
        balance < -0.01 -> Triple("You owe", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
        else -> Triple("You are all settled", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
    }
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    balanceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    currencyFormat.format(balance.absoluteValue),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = balanceColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun MembersCard(members: List<Map<String, String>>, memberNames: Map<String, String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Text(
                text = "Members",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
            LazyRow(
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(members, key = { it["id"] ?: "" }) { member ->
                    val id = member["id"] ?: ""
                    val name = memberNames[id] ?: member["name"] ?: "Unknown"
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(64.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = name.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/* ------------------------- LIST ITEM ------------------------- */

@Composable
private fun TransactionListItem(
    transaction: Map<String, Any>,
    memberNames: Map<String, String>,
    currentUserId: String,
    modifier: Modifier = Modifier
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    val type = (transaction["type"] as? String) ?: "expense"
    val title = (transaction["title"] as? String)?.ifBlank { "Untitled" } ?: "Untitled"
    val amount = (transaction["amount"] as? Number)?.toDouble() ?: 0.0
    val paidById = (transaction["paidBy"] as? String) ?: ""
    val paidByName = memberNames[paidById] ?: "Unknown"

    // Build icon and subtitle
    val (icon, subtitle) = if (type == "settle") {
        val receivedById = transaction["receivedBy"] as? String ?: ""
        val receivedByName = memberNames[receivedById] ?: "Unknown"
        Icons.Outlined.CheckCircle to "$paidByName paid $receivedByName"
    } else {
        val categoryIcon: ImageVector = when {
            title.contains("food", true) || title.contains("pizza", true) -> Icons.Outlined.Fastfood
            title.contains("travel", true) || title.contains("uber", true) -> Icons.Outlined.Commute
            title.contains("shop", true) -> Icons.Outlined.ShoppingCart
            title.contains("movie", true) -> Icons.Outlined.Theaters
            else -> Icons.AutoMirrored.Outlined.ReceiptLong
        }
        categoryIcon to "Paid by $paidByName"
    }

    // Compute per-user amount and status (only for expenses)
    val (statusText, statusColor, statusBg, userAmountText, amountColorRight) =
        if (type == "settle") {
            Quint("Settlement", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                currencyFormat.format(amount), MaterialTheme.colorScheme.onSurface)
        } else {
            val shares = computeSharesForTransaction(transaction)
            val participants = shares.keys

            // If no participants recorded, treat as payer lent full amount; others not involved.
            val userIsPayer = currentUserId == paidById
            val userIsParticipant = currentUserId in participants

            if (userIsPayer) {
                val payerShare = shares[currentUserId] ?: 0.0
                val lent = max(0.0, amount - payerShare) // what others owe you
                Quint(
                    "You lent",
                    Color(0xFF1B5E20),
                    Color(0x331B5E20),
                    currencyFormat.format(lent),
                    Color(0xFF1B5E20)
                )
            } else if (userIsParticipant) {
                val borrowed = shares[currentUserId] ?: 0.0
                Quint(
                    "You borrowed",
                    MaterialTheme.colorScheme.error,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    currencyFormat.format(borrowed),
                    MaterialTheme.colorScheme.error
                )
            } else {
                Quint(
                    "Not involved",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    "—",
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Status chip (only meaningful for expenses; settlement shows "Settlement")
                StatusChip(text = statusText, fg = statusColor, bg = statusBg, modifier = Modifier.padding(top = 6.dp))
            }
            // Amount relevant TO THE USER (lent/borrowed) — minimal and color aligned
            Text(
                text = userAmountText,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = amountColorRight
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

/* ------------------------- FABs & DIALOGS ------------------------- */

@Composable
private fun GroupDetailFABs(
    balance: Double,
    onAddExpenseClick: () -> Unit,
    onSettleUpClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 8.dp, end = 8.dp)
    ) {
        // Smaller, minimal Extended FAB
        if (balance.absoluteValue > 0.01) {
            ExtendedFloatingActionButton(
                onClick = onSettleUpClick,
                icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = "Settle") },
                text = { Text("Settle up") },
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(40.dp)
            )
        }
        FloatingActionButton(
            onClick = onAddExpenseClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add")
        }
    }
}

@Composable
private fun ConfirmActionDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, title: String, text: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(onClick = { onConfirm(); onDismiss() }, shape = RoundedCornerShape(8.dp)) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(16.dp)
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
        title = { Text("Invite via Link", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Share this link with anyone you want to invite to \"$groupName\".", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = inviteLink,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Group Invite Link") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Join my group \"$groupName\" on TrueSplit using this link:\n$inviteLink")
                }
                context.startActivity(Intent.createChooser(intent, "Share Invite Link"))
                onDismiss()
            }, shape = RoundedCornerShape(8.dp)) { Text("Share Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) { Text("Close") } },
        shape = RoundedCornerShape(16.dp)
    )
}

/* ------------------------- CALCULATION HELPERS ------------------------- */

/**
 * Returns a per-user share map (userId -> share amount) for an expense transaction.
 * Supports:
 *  - "splits": Map<userId, Number|Boolean>  (numbers treated as explicit amounts; booleans as inclusion flags)
 *  - "splitBetween": List<userId>
 *  - "splitWith": List<Map<String, Any>> containing "userId" and optional "amount"/"included"
 */
private fun computeSharesForTransaction(tx: Map<String, Any>): Map<String, Double> {
    val amount = (tx["amount"] as? Number)?.toDouble() ?: 0.0
    if (amount <= 0.0) return emptyMap()

    // 1) splits map
    val splitsRaw = tx["splits"]
    if (splitsRaw is Map<*, *>) {
        // Check if numeric amounts
        val numericEntries = splitsRaw.entries.mapNotNull { (k, v) ->
            val userId = k as? String ?: return@mapNotNull null
            when (v) {
                is Number -> userId to v.toDouble()
                is String -> v.toDoubleOrNull()?.let { userId to it }
                is Boolean -> if (v) userId to 1.0 else null
                else -> null
            }
        }

        if (numericEntries.isNotEmpty()) {
            val asMap = numericEntries.toMap()
            val sum = asMap.values.sum()
            return if (sum > 0.0) {
                // If provided shares don’t sum to amount, scale proportionally
                val scale = amount / sum
                asMap.mapValues { (_, v) -> v * scale }
            } else {
                // All zeros -> fallback to equal split among "true" flags only (handled above as 1.0s)
                val included = numericEntries.filter { it.second > 0 }.map { it.first }
                if (included.isNotEmpty()) {
                    val share = amount / included.size
                    included.associateWith { share }
                } else emptyMap()
            }
        } else {
            // Boolean-only map: take all `true` as participants
            val participants = splitsRaw.entries.mapNotNull { (k, v) ->
                val userId = k as? String ?: return@mapNotNull null
                if (v == true) userId else null
            }
            if (participants.isNotEmpty()) {
                val share = amount / participants.size
                return participants.associateWith { share }
            }
        }
    }

    // 2) splitBetween: List<userId>
    val splitBetween = tx["splitBetween"]
    if (splitBetween is List<*>) {
        val participants = splitBetween.mapNotNull { it as? String }
        if (participants.isNotEmpty()) {
            val share = amount / participants.size
            return participants.associateWith { share }
        }
    }

    // 3) splitWith: List<Map<...>>
    val splitWith = tx["splitWith"]
    if (splitWith is List<*>) {
        val entries = splitWith.mapNotNull { it as? Map<*, *> }
        // Try explicit amounts first
        val explicit = entries.mapNotNull { m ->
            val uid = m["userId"] as? String ?: return@mapNotNull null
            when (val v = m["amount"]) {
                is Number -> uid to v.toDouble()
                is String -> v.toDoubleOrNull()?.let { uid to it }
                else -> null
            }
        }
        if (explicit.isNotEmpty()) {
            val sum = explicit.sumOf { it.second }
            return if (sum > 0) {
                val scale = amount / sum
                explicit.associate { it.first to it.second * scale }
            } else emptyMap()
        }
        // Fallback to inclusion flags
        val participants = entries.mapNotNull { m ->
            val uid = m["userId"] as? String ?: return@mapNotNull null
            val included = when (val inc = m["included"]) {
                is Boolean -> inc
                is Number -> inc.toInt() != 0
                is String -> inc.equals("true", true) || inc == "1"
                else -> true // presence implies included
            }
            if (included) uid else null
        }
        if (participants.isNotEmpty()) {
            val share = amount / participants.size
            return participants.associateWith { share }
        }
    }

    // If nothing defined, return empty (means no one selected). Payer will be treated as lent full.
    return emptyMap()
}

/**
 * Accurate group balances:
 *  - For each expense: participants only; per-user shares; payer credited with total paid.
 *  - For settlements: payer credited (+), receiver debited (-).
 * Positive balance => others owe this user.
 */
private fun calculateGroupBalancesAccurate(
    members: List<Map<String, String>>,
    transactions: List<Map<String, Any>>
): Map<String, Double> {
    val balances = mutableMapOf<String, Double>().apply {
        members.forEach { m -> this[m["id"] ?: ""] = 0.0 }
    }

    transactions.forEach { tx ->
        val type = (tx["type"] as? String) ?: "expense"
        val amount = (tx["amount"] as? Number)?.toDouble() ?: 0.0
        if (amount <= 0) return@forEach

        if (type == "settle") {
            val paidBy = tx["paidBy"] as? String
            val receivedBy = tx["receivedBy"] as? String
            if (paidBy != null && receivedBy != null && balances.containsKey(paidBy) && balances.containsKey(receivedBy)) {
                balances[paidBy] = balances.getValue(paidBy) + amount
                balances[receivedBy] = balances.getValue(receivedBy) - amount
            }
        } else {
            val paidBy = tx["paidBy"] as? String ?: return@forEach
            if (!balances.containsKey(paidBy)) return@forEach

            val shares = computeSharesForTransaction(tx) // userId -> share amount
            if (shares.isEmpty()) {
                // No participants selected: payer lent full amount; no one owes except "others" unknown.
                balances[paidBy] = balances.getValue(paidBy) + amount
            } else {
                // debit each participant by their share
                shares.forEach { (uid, share) ->
                    if (balances.containsKey(uid)) balances[uid] = balances.getValue(uid) - share
                }
                // credit payer with total paid
                balances[paidBy] = balances.getValue(paidBy) + amount
            }
        }
    }
    return balances
}

/* Utility quintuple for compact returns */
private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
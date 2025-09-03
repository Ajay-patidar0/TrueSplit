package com.example.truesplit.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseDetailScreen(
    expenseId: String,
    groupId: String,
    navController: NavController,
    navBack: () -> Unit
) {
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val context = LocalContext.current
    val currentUser = auth.currentUser
    val currentUserId = currentUser?.uid ?: return

    var expense by remember { mutableStateOf<Map<String, Any>?>(null) }
    var groupMembers by remember { mutableStateOf(listOf<Map<String, String>>()) }
    val memberNames = remember { mutableStateMapOf<String, String>() }

// Fetch expense details
    LaunchedEffect(expenseId) {
        db.collection("groups").document(groupId)
            .collection("expenses").document(expenseId)
            .addSnapshotListener { snapshot, _ ->
                expense = snapshot?.data
            }

        db.collection("groups").document(groupId).addSnapshotListener { snapshot, _ ->
            val rawMembers = snapshot?.get("members")
            groupMembers = if (rawMembers is List<*>) {
                rawMembers.filterIsInstance<Map<String, String>>()
            } else {
                emptyList()
            }
        }
    }

// Fetch member names
    LaunchedEffect(groupMembers) {
        groupMembers.forEach { member ->
            val uid = member["id"] ?: return@forEach
            if (uid !in memberNames) {
                db.collection("users").document(uid).get()
                    .addOnSuccessListener { doc ->
                        memberNames[uid] = doc.getString("name") ?: member["name"] ?: "Unknown"
                    }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expense Details", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = navBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (expense?.get("paidBy") == currentUserId) {
                        IconButton(
                            onClick = {
                                db.collection("groups").document(groupId)
                                    .collection("expenses").document(expenseId)
                                    .delete()
                                    .addOnSuccessListener {
                                        Toast.makeText(context, "Expense deleted", Toast.LENGTH_SHORT).show()
                                        navBack()
                                    }
                            }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete Expense")
                        }
                    }
                }
            )
        }
    ) { padding ->
        expense?.let { exp ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    ExpenseHeader(expense = exp, memberNames = memberNames)
                    Spacer(modifier = Modifier.height(24.dp))
                }

                item {
                    Text(
                        "Split Details",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                items(computeSharesForTransaction(exp).toList()) { (userId, amount) ->
                    val name = memberNames[userId] ?: "Unknown"
                    val paidByName = memberNames[exp["paidBy"] as? String ?: ""] ?: "Unknown"

                    ParticipantRow(
                        name = if (userId == exp["paidBy"]) "$name (paid)" else name,
                        amount = amount,
                        isPayer = userId == exp["paidBy"],
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        } ?: run {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun ExpenseHeader(expense: Map<String, Any>, memberNames: Map<String, String>) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    val amount = (expense["amount"] as? Number)?.toDouble() ?: 0.0
    val paidById = expense["paidBy"] as? String ?: ""
    val paidByName = memberNames[paidById] ?: "Unknown"
    val timestamp = (expense["timestamp"] as? Long) ?: 0L
    val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                expense["title"] as? String ?: "Untitled",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                currencyFormat.format(amount),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Paid by $paidByName",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                dateFormat.format(Date(timestamp)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ParticipantRow(name: String, amount: Double, isPayer: Boolean, modifier: Modifier = Modifier) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = if (isPayer) currencyFormat.format(amount) else "-${currencyFormat.format(amount)}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isPayer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

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
                // If provided shares don't sum to amount, scale proportionally
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
package com.example.truesplit.screens

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

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

    var groupName by remember { mutableStateOf("") }
    var groupColor by remember { mutableStateOf("#6CB4C9") }
    var members by remember { mutableStateOf(listOf<Map<String, String>>()) }
    var expenses by remember { mutableStateOf(listOf<Map<String, Any>>()) }
    var showInviteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.collection("groups").document(groupId).addSnapshotListener { snapshot, _ ->
            snapshot?.let {
                groupName = it.getString("name") ?: ""
                groupColor = it.getString("color") ?: "#6CB4C9"

                val rawMembers = it.get("members")
                members = when (rawMembers) {
                    is List<*> -> {
                        if (rawMembers.all { it is Map<*, *> }) {
                            @Suppress("UNCHECKED_CAST")
                            rawMembers as List<Map<String, String>>
                        } else {
                            listOf()
                        }
                    }
                    else -> listOf()
                }
            }
        }

        db.collection("groups").document(groupId)
            .collection("expenses")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                expenses = snapshot?.documents?.mapNotNull { it.data } ?: listOf()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(groupName, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = navBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(android.graphics.Color.parseColor(groupColor))
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    navController.navigate("addExpense/$groupId")
                },
                containerColor = Color(0xFF2C5A8C)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Expense", tint = Color.White)
            }
        },
        containerColor = Color(0xFFF8F9FB)
    ) { padding ->

        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Members (${members.size})",
                    color = Color(0xFF3A3A3A),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                OutlinedButton(
                    onClick = {
                        val encodedGroupName = Uri.encode(groupName)
                        val inviteLink =
                            "https://truesplit.airbridge.io/join?groupId=$groupId&groupName=$encodedGroupName"
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Join our TrueSplit group \"$groupName\" using this link:\n$inviteLink"
                            )
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                    },
                    border = BorderStroke(1.dp, Color(0xFF2C5A8C)),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2C5A8C))
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Invite",
                        tint = Color(0xFF2C5A8C),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Invite")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                members.forEach { member ->
                    val displayName = member["name"] ?: member["id"] ?: "?"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(groupColor)))
                    ) {
                        Text(displayName.firstOrNull()?.uppercase() ?: "?", color = Color.White)
                    }
                }
            }

            Divider()

            Spacer(Modifier.height(8.dp))

            Text(
                "Expenses:",
                color = Color(0xFF3A3A3A),
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(expenses) { expense ->
                    ExpenseItem(expense, members)
                }
            }
        }
    }

    if (showInviteDialog) {
        InviteMemberDialog(onDismiss = { showInviteDialog = false }) { email ->
            if (email.isNotBlank() &&
                Patterns.EMAIL_ADDRESS.matcher(email).matches() &&
                members.none { it["id"] == email }
            ) {
                val encodedGroupName = Uri.encode(groupName)
                val inviteLink =
                    "https://truesplit.airbridge.io/join?groupId=$groupId&groupName=$encodedGroupName"

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, "You're invited to join $groupName")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Join your group in TrueSplit using this link:\n$inviteLink"
                    )
                }
                context.startActivity(Intent.createChooser(intent, "Send Invitation"))
            }
        }
    }
}

@Composable
fun ExpenseItem(
    expense: Map<String, Any>,
    members: List<Map<String, String>>
) {
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val paidById = expense["paidBy"] as? String ?: ""
    val paidByMember = members.find { it["id"] == paidById }
    val paidByName = paidByMember?.get("name") ?: paidById
    val paidByEmail = paidByMember?.get("email") ?: ""

    val title = (expense["title"] as? String)?.trim() ?: "Untitled"
    val totalAmount = (expense["amount"] as? Number)?.toDouble() ?: 0.0
    val memberCount = members.size.takeIf { it > 0 } ?: 1
    val sharePerPerson = totalAmount / memberCount

    // Auto-detect category from title
    val category = when {
        title.contains("domino", true) || title.contains("pizza", true) || title.contains("food", true) -> "food"
        title.contains("uber", true) || title.contains("ola", true) || title.contains("bus", true) -> "travel"
        title.contains("amazon", true) || title.contains("flipkart", true) -> "shopping"
        title.contains("movie", true) || title.contains("netflix", true) -> "entertainment"
        else -> "other"
    }

    val icon = when (category) {
        "food" -> Icons.Outlined.Restaurant
        "travel" -> Icons.Outlined.DirectionsCar
        "shopping" -> Icons.Outlined.ShoppingCart
        "entertainment" -> Icons.Outlined.Movie
        else -> Icons.Outlined.ReceiptLong
    }

    val owedSummary = when {
        currentUserId == paidById -> {
            members.filter { it["id"] != currentUserId }.joinToString("\n") {
                "🟢 ${it["name"] ?: "Unknown"} owes you ₹${String.format("%.2f", sharePerPerson)}"
            }
        }
        members.any { it["id"] == currentUserId } -> {
            "🔴 You owe $paidByName ₹${String.format("%.2f", sharePerPerson)}"
        }
        else -> ""
    }

    var isExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {

                // Expense Icon with better visibility
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF1F1F1)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "Category Icon",
                        tint = Color(0xFF2C5A8C),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black
                    )
                    Text(
                        text = "₹${String.format("%.2f", totalAmount)} • Paid by $paidByName",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }

                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Color.Gray
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(
                            color = if (currentUserId == paidById) Color(0xFFEDF7ED) else Color(0xFFFFF2F2),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = owedSummary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (currentUserId == paidById) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                    )
                }
            }
        }
    }
}


@Composable
fun InviteMemberDialog(onDismiss: () -> Unit, onInvite: (String) -> Unit) {
    var email by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite Member") },
        text = {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                if (email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    onInvite(email)
                    onDismiss()
                }
            }) { Text("Invite") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

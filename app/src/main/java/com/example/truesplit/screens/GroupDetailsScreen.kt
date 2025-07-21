package com.example.truesplit.screens

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    val totalAmount = (expense["amount"] as? Number)?.toDouble() ?: 0.0
    val memberCount = members.size.takeIf { it > 0 } ?: 1
    val sharePerPerson = totalAmount / memberCount

    val owedSummary = when {
        currentUserId == paidById -> {
            // Show who owes you
            val others = members.filter { it["id"] != currentUserId }
            if (others.isEmpty()) "" else
                others.joinToString("\n") {
                    val name = it["name"] ?: "Unknown"
                    "🟢 $name owes you ₹${String.format("%.2f", sharePerPerson)}"
                }
        }

        members.any { it["id"] == currentUserId } -> {
            // Show you owe to the payer
            "🔴 You owe $paidByName ₹${String.format("%.2f", sharePerPerson)}"
        }

        else -> ""
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, Color(0xFFE0E0E0), shape = RoundedCornerShape(16.dp))
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Expense Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = expense["title"] as? String ?: "Untitled",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color(0xFF222222)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "₹${String.format("%.2f", totalAmount)} • Paid by $paidByName",
                        color = Color(0xFF666666),
                        fontSize = 13.sp
                    )
                }
                if (paidByEmail.isNotBlank()) {
                    Text(
                        text = paidByEmail,
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }

            if (owedSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0xFFF0F0F0))
                Spacer(modifier = Modifier.height(12.dp))

                // Owed Summary Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (currentUserId == paidById) Color(0xFFEDF7ED) else Color(0xFFFFF3F3),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = owedSummary,
                        color = if (currentUserId == paidById) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp
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

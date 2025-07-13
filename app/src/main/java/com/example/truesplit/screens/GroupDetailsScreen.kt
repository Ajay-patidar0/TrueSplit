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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(groupId: String, navBack: () -> Unit) {

    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val userEmail = auth.currentUser?.email ?: ""
    val context = LocalContext.current

    var groupName by remember { mutableStateOf("") }
    var groupColor by remember { mutableStateOf("#6CB4C9") }
    var members by remember { mutableStateOf(listOf<String>()) }
    var expenses by remember { mutableStateOf(listOf<Map<String, Any>>()) }
    var showAddExpense by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.collection("groups").document(groupId).addSnapshotListener { snapshot, _ ->
            snapshot?.let {
                groupName = it.getString("name") ?: ""
                groupColor = it.getString("color") ?: "#6CB4C9"
                members = it.get("members") as? List<String> ?: listOf()
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(android.graphics.Color.parseColor(groupColor)))
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddExpense = true },
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
                        val inviteLink = "https://truesplit.airbridge.io/join?groupId=$groupId&groupName=$encodedGroupName"
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
                modifier = Modifier.padding(16.dp).horizontalScroll(rememberScrollState())
            ) {
                members.forEach { member ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(groupColor)))
                    ) {
                        Text(member.firstOrNull()?.uppercase() ?: "?", color = Color.White)
                    }
                }
            }

            Divider()

            Spacer(Modifier.height(8.dp))

            Text("Expenses:", color = Color(0xFF3A3A3A), fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(expenses) { expense ->
                    ExpenseItem(expense)
                }
            }
        }
    }

    if (showAddExpense) {
        AddExpenseDialog(onDismiss = { showAddExpense = false }) { desc, amt ->
            val expense = hashMapOf(
                "description" to desc,
                "amount" to amt.toDoubleOrNull(),
                "paidBy" to userEmail,
                "splitBetween" to members,
                "timestamp" to com.google.firebase.Timestamp.now()
            )
            db.collection("groups").document(groupId).collection("expenses").add(expense)
        }
    }

    if (showInviteDialog) {
        InviteMemberDialog(onDismiss = { showInviteDialog = false }) { email ->
            if (email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email).matches() && !members.contains(email)) {

                val encodedGroupName = Uri.encode(groupName)
                val inviteLink = "https://truesplit.airbridge.io/join?groupId=$groupId&groupName=$encodedGroupName"

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, "You're invited to join $groupName")
                    putExtra(Intent.EXTRA_TEXT, "Join your group in TrueSplit using this link:\n$inviteLink")
                }
                context.startActivity(Intent.createChooser(intent, "Send Invitation"))
            }
        }
    }
}

@Composable
fun ExpenseItem(expense: Map<String, Any>) {
    Card(
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(expense["description"] as? String ?: "Expense", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Amount: ₹${expense["amount"]}", color = Color(0xFF3A3A3A))
            Spacer(Modifier.height(2.dp))
            Text("Paid by: ${expense["paidBy"]}", color = Color(0xFF7D7D7D), fontSize = 13.sp)
        }
    }
}

@Composable
fun AddExpenseDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var desc by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Expense") },
        text = {
            Column {
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                if (desc.isNotBlank() && amount.isNotBlank()) {
                    onAdd(desc, amount)
                    onDismiss()
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun InviteMemberDialog(onDismiss: () -> Unit, onInvite: (String) -> Unit) {
    var email by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite Member") },
        text = {
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = {
                if (email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    onInvite(email)
                    onDismiss()
                }
            }) { Text("Invite") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

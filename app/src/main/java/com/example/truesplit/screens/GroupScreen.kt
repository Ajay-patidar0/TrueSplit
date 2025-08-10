package com.example.truesplit.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.truesplit.R
import com.example.truesplit.utils.ExpenseUtils
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

data class GroupData(
    val id: String = "",
    val name: String = "",
    val color: String = "#6CB4C9",
    val balance: Double = 0.0,
    val members: List<Map<String, String>> = emptyList()
)

@Composable
fun GroupScreen(
    auth: FirebaseAuth,
    navToDetails: (String) -> Unit
) {
    val db = FirebaseFirestore.getInstance()
    val userId = auth.currentUser?.uid ?: return
    val userEmail = auth.currentUser?.email ?: return
    val userName = auth.currentUser?.displayName?.takeIf { it.isNotBlank() }
        ?: userEmail.substringBefore("@")

    var showDialog by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<GroupData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(userId) {
        val groupsRef = db.collection("groups").whereArrayContains("memberIds", userId)
        val groupListeners = mutableListOf<ListenerRegistration>()

        val mainListener = groupsRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                isLoading = false
                return@addSnapshotListener
            }
            if (snapshot == null) {
                isLoading = false
                return@addSnapshotListener
            }

            isLoading = true

            // Remove old expense listeners when group list changes
            groupListeners.forEach { it.remove() }
            groupListeners.clear()

            val fetchedGroups = snapshot.documents.mapNotNull { doc ->
                val group = doc.toObject(GroupData::class.java)?.copy(id = doc.id)
                val members = doc.get("members") as? List<Map<String, String>> ?: emptyList()
                group?.copy(members = members)
            }

            // Set up listeners for each group's expenses
            fetchedGroups.forEach { group ->
                val expenseListener = db.collection("groups")
                    .document(group.id)
                    .collection("expenses")
                    .addSnapshotListener { expenseSnap, _ ->
                        if (expenseSnap == null) return@addSnapshotListener

                        val transactions = expenseSnap.documents.map { doc ->
                            mapOf(
                                "amount" to (doc.getDouble("amount") ?: 0.0),
                                "type" to (doc.getString("type") ?: "expense"),
                                "paidBy" to (doc.getString("paidBy") ?: ""),
                                "receivedBy" to (doc.getString("receivedBy") ?: "")
                            )
                        }

                        val balances = ExpenseUtils.calculateBalances(group.members, transactions)
                        val userBalance = balances[userId] ?: 0.0

                        groups = fetchedGroups.map {
                            if (it.id == group.id) it.copy(balance = userBalance) else it
                        }.sortedBy { it.name }

                        isLoading = false
                    }
                groupListeners.add(expenseListener)
            }
        }

        onDispose {
            mainListener.remove()
            groupListeners.forEach { it.remove() }
        }
    }


    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        color = Color(0xFF2C5A8C),
                        shape = RoundedCornerShape(bottomStart = 25.dp, bottomEnd = 25.dp)
                    )
            ) {
                Text(
                    text = "Your Groups",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                )
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 40.dp, y = 20.dp)
                        .graphicsLayer { alpha = 0.1f }
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showDialog = true },
                containerColor = Color(0xFF2C5A8C),
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Group",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
        },
        containerColor = Color(0xFFF8F9FB)
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(top = 24.dp, bottom = 80.dp)
            ) {
                if (groups.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.bg_img),
                                contentDescription = "No groups",
                                modifier = Modifier.size(150.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("No groups yet", fontSize = 20.sp, color = Color(0xFF3A3A3A))
                            Spacer(Modifier.height(8.dp))
                            Text("Tap + to create your first group", color = Color(0xFF7D7D7D))
                        }
                    }
                } else {
                    items(groups, key = { it.id }) { group ->
                        GroupCard(group = group, onCardClick = { navToDetails(group.id) })
                    }
                }
            }
        }
    }

    if (showDialog) {
        CreateGroupDialog(
            onDismiss = { showDialog = false },
            onCreate = { name, color ->
                val group = hashMapOf(
                    "name" to name,
                    "color" to color,
                    "createdBy" to userEmail,
                    "timestamp" to Timestamp.now(),
                    "members" to listOf(
                        mapOf("id" to userId, "name" to userName, "email" to userEmail)
                    ),
                    "memberIds" to listOf(userId)
                )
                db.collection("groups").add(group)
                showDialog = false
            }
        )
    }
}

@Composable
private fun GroupCard(
    group: GroupData,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormat = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }

    Card(
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onCardClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(group.color)))
            ) {
                Text(
                    text = group.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    fontSize = 18.sp,
                    color = Color(0xFF3A3A3A),
                    fontWeight = FontWeight.SemiBold
                )
                Row {
                    val balance = group.balance
                    if (balance.absoluteValue > 0.01) {
                        val (text, color) = if (balance > 0) {
                            "You are owed ${currencyFormat.format(balance)}" to Color(0xFF34A853)
                        } else {
                            "You owe ${currencyFormat.format(balance.absoluteValue)}" to Color(0xFFEA4335)
                        }
                        Text(text = text, fontSize = 13.sp, color = color)
                    }
                }
            }

            Icon(
                painter = painterResource(id = R.drawable.ic_right),
                contentDescription = "Go",
                tint = Color(0xFF7D7D7D),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf("#6CB4C9") }

    val colors = listOf("#6CB4C9", "#2C5A8C", "#FBBC05", "#EA4335", "#34A853")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Group", color = Color(0xFF2C5A8C)) },
        text = {
            Column {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("Select Icon Color", color = Color(0xFF3A3A3A))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { colorCode ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(colorCode)))
                                .clickable { selectedColor = colorCode }
                                .border(
                                    width = if (selectedColor == colorCode) 2.dp else 0.dp,
                                    color = Color(0xFF2C5A8C),
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onCreate(groupName.trim(), selectedColor)
                    }
                },
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C5A8C))
            ) {
                Text("Create", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF2C5A8C))
            }
        },
        shape = RoundedCornerShape(25.dp),
        containerColor = Color.White
    )
}
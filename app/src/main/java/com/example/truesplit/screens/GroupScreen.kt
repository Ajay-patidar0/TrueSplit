package com.example.truesplit.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.truesplit.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

data class GroupData(
    val name: String = "",
    val color: String = "#6CB4C9",
    val id: String = ""
)

@Composable
fun GroupScreen(
    auth: FirebaseAuth,
    navToDetails: (String) -> Unit,
    refreshTrigger: Boolean
) {
    val db = FirebaseFirestore.getInstance()
    val userEmail = auth.currentUser?.email

    var showDialog by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(listOf<GroupData>()) }

    // New trigger to refresh after group creation
    var shouldRefresh by remember { mutableStateOf(false) }

    fun fetchGroups() {
        if (!userEmail.isNullOrEmpty()) {
            db.collection("groups")
                .whereArrayContains("members", userEmail)
                .get()
                .addOnSuccessListener { snapshot ->
                    groups = snapshot.documents.map {
                        GroupData(
                            name = it.getString("name") ?: "",
                            color = it.getString("color") ?: "#6CB4C9",
                            id = it.id
                        )
                    }
                }
        }
    }

    LaunchedEffect(refreshTrigger) {
        fetchGroups()
    }

    LaunchedEffect(shouldRefresh) {
        if (shouldRefresh) {
            fetchGroups()
            shouldRefresh = false
        }
    }

    LaunchedEffect(userEmail) {
        fetchGroups()
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
            IconButton(
                onClick = { showDialog = true },
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFF2C5A8C), CircleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = "Add Group",
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        containerColor = Color(0xFFF8F9FB)
    ) { padding ->

        LazyColumn(
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(LayoutDirection.Ltr),
                top = padding.calculateTopPadding() + 24.dp,
                end = padding.calculateEndPadding(LayoutDirection.Ltr),
                bottom = padding.calculateBottomPadding() + 80.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
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
                items(groups) { group ->
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { navToDetails(group.id) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(16.dp)
                                .background(Color(0xFFF8F9FB))
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
                            Text(
                                text = group.name,
                                fontSize = 18.sp,
                                color = Color(0xFF3A3A3A),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                painter = painterResource(id = R.drawable.ic_right),
                                contentDescription = "Go",
                                tint = Color(0xFF7D7D7D),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        CreateGroupDialog(
            onDismiss = { showDialog = false },
            onCreate = { name, color ->
                if (!userEmail.isNullOrEmpty()) {
                    val group = hashMapOf(
                        "name" to name,
                        "color" to color,
                        "createdBy" to userEmail,
                        "timestamp" to com.google.firebase.Timestamp.now(),
                        "members" to listOf(userEmail)
                    )
                    db.collection("groups").add(group)
                        .addOnSuccessListener {
                            // ✅ trigger UI refresh
                            shouldRefresh = true
                        }
                    showDialog = false
                }
            }
        )
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

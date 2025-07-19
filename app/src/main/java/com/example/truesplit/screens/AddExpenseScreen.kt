@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.truesplit.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun AddExpenseScreen(
    navController: NavController,
    groupId: String,
    groupMembers: List<Triple<String, String, String>> // (userId, name, email)
) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val coroutineScope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }

    val currentUserId = auth.currentUser?.uid ?: ""
    val currentUser = groupMembers.find { it.first == currentUserId }

    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var paidBy by remember { mutableStateOf(currentUserId) }
    var splitType by remember { mutableStateOf("equal") }
    val selectedMembers = remember { mutableStateListOf<String>() }
    val unequalAmounts = remember { mutableStateMapOf<String, String>() }
    var dropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        selectedMembers.clear()
        selectedMembers.addAll(groupMembers.map { it.first })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Expense") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val totalAmount = amount.toDoubleOrNull()
                if (title.isBlank()) {
                    coroutineScope.launch { snackbarHostState.showSnackbar("Please enter a title.") }
                    return@FloatingActionButton
                }
                if (totalAmount == null || totalAmount <= 0) {
                    coroutineScope.launch { snackbarHostState.showSnackbar("Please enter a valid amount.") }
                    return@FloatingActionButton
                }
                if (selectedMembers.isEmpty()) {
                    coroutineScope.launch { snackbarHostState.showSnackbar("Select at least one member.") }
                    return@FloatingActionButton
                }

                val participants = selectedMembers.toList()

                val splits: Map<String, Double> = when (splitType) {
                    "equal" -> {
                        val perPerson = totalAmount / participants.size
                        participants.associateWith { "%.2f".format(perPerson).toDouble() }
                    }
                    "unequal" -> {
                        val map = mutableMapOf<String, Double>()
                        var sum = 0.0
                        for (id in participants) {
                            val amt = unequalAmounts[id]?.toDoubleOrNull()
                            if (amt == null || amt < 0) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Enter valid amount for all selected members.")
                                }
                                return@FloatingActionButton
                            }
                            sum += amt
                            map[id] = "%.2f".format(amt).toDouble()
                        }
                        if ("%.2f".format(sum) != "%.2f".format(totalAmount)) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Total unequal split must equal ₹$totalAmount")
                            }
                            return@FloatingActionButton
                        }
                        map
                    }
                    else -> emptyMap()
                }

                val expense = mapOf(
                    "title" to title.trim(),
                    "amount" to "%.2f".format(totalAmount).toDouble(),
                    "paidBy" to paidBy,
                    "splitType" to splitType,
                    "splits" to splits,
                    "timestamp" to System.currentTimeMillis()
                )

                coroutineScope.launch {
                    db.collection("groups").document(groupId)
                        .collection("expenses")
                        .add(expense)
                        .addOnSuccessListener {
                            navController.popBackStack()
                        }
                        .addOnFailureListener {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Failed to add expense.")
                            }
                        }
                }
            }) {
                Icon(Icons.Filled.Check, contentDescription = "Save")
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Expense Title") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Total Amount") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            // Paid By Dropdown
            val paidByDisplayName = if (paidBy == currentUserId) "Me"
            else groupMembers.find { it.first == paidBy }?.second ?: "Select"

            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = paidByDisplayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Paid By") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                    },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Me") },
                        onClick = {
                            paidBy = currentUserId
                            dropdownExpanded = false
                        }
                    )
                    groupMembers
                        .filter { it.first != currentUserId }
                        .forEach { (id, name, _) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    paidBy = id
                                    dropdownExpanded = false
                                }
                            )
                        }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Split Type
            Text("Split Type", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = splitType == "equal",
                    onClick = { splitType = "equal" }
                )
                Text("Equal")
                Spacer(Modifier.width(16.dp))
                RadioButton(
                    selected = splitType == "unequal",
                    onClick = { splitType = "unequal" }
                )
                Text("Unequal")
            }

            Spacer(Modifier.height(16.dp))

            // Members List
            Text("Select Members Involved", fontWeight = FontWeight.SemiBold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groupMembers) { (id, name, email) ->
                    val isChecked = selectedMembers.contains(id)
                    val total = amount.toDoubleOrNull() ?: 0.0
                    val perPerson = if (splitType == "equal" && selectedMembers.isNotEmpty()) {
                        total / selectedMembers.size
                    } else null

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F6FA)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = isChecked,
                                onValueChange = {
                                    if (it) selectedMembers.add(id) else selectedMembers.remove(id)
                                }
                            )
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2C5A8C)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(name.first().toString(), color = Color.White)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(name, fontWeight = FontWeight.Bold)
                                    Text(email, fontSize = 12.sp, color = Color.Gray)
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null
                                )
                            }

                            if (splitType == "equal" && isChecked && perPerson != null) {
                                Text(
                                    "₹ %.2f will be split".format(perPerson),
                                    fontSize = 13.sp,
                                    color = Color(0xFF2C5A8C),
                                    modifier = Modifier.padding(start = 60.dp, bottom = 8.dp)
                                )
                            }

                            if (splitType == "unequal" && isChecked) {
                                OutlinedTextField(
                                    value = unequalAmounts[id] ?: "",
                                    onValueChange = { unequalAmounts[id] = it },
                                    label = { Text("₹ Amount for $name") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

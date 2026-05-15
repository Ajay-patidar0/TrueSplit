@file:OptIn(ExperimentalMaterial3Api::class)

package com.aplabs.truesplit.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
    groupMembers: List<Triple<String, String, String>>
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
    val userProfileNames = remember { mutableStateMapOf<String, String>() }

    var groupName by remember { mutableStateOf("a group") }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        selectedMembers.clear()
        selectedMembers.addAll(groupMembers.map { it.first }.distinct())

        db.collection("groups").document(groupId).get()
            .addOnSuccessListener { document ->
                groupName = document.getString("name") ?: "a group"
            }
            .addOnFailureListener {
                Log.w("AddExpenseScreen", "Failed to fetch group name", it)
            }

        groupMembers.forEach { (userId, defaultName, _) ->
            db.collection("users").document(userId).get()
                .addOnSuccessListener { document ->
                    userProfileNames[userId] =
                        document.getString("name") ?: defaultName
                }
        }
    }

    fun sanitizeDecimalInput(value: String): String {
        val filtered = value.filter { it.isDigit() || it == '.' }
        val firstDot = filtered.indexOf('.')

        return if (firstDot >= 0) {
            val before = filtered.substring(0, firstDot + 1)
            val after = filtered.substring(firstDot + 1).replace(".", "")
            before + after
        } else {
            filtered
        }
    }

    fun toggleMember(id: String, checked: Boolean) {
        if (checked) {
            if (!selectedMembers.contains(id)) {
                selectedMembers.add(id)
            }
        } else {
            selectedMembers.remove(id)
            unequalAmounts.remove(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Expense") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isSaving) navController.popBackStack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },

        floatingActionButton = {
            FloatingActionButton(
                onClick = {

                    if (isSaving) return@FloatingActionButton

                    val totalAmount = amount.toDoubleOrNull()

                    if (title.isBlank()) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Please enter a title.")
                        }
                        return@FloatingActionButton
                    }

                    if (totalAmount == null || totalAmount <= 0.0) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Please enter a valid amount.")
                        }
                        return@FloatingActionButton
                    }

                    val participants = selectedMembers.distinct()

                    if (participants.isEmpty()) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Select at least one member.")
                        }
                        return@FloatingActionButton
                    }

                    val splits: Map<String, Double> = when (splitType) {

                        "equal" -> {
                            val perPerson = totalAmount / participants.size
                            participants.associateWith {
                                String.format("%.2f", perPerson).toDouble()
                            }
                        }

                        "unequal" -> {
                            val map = mutableMapOf<String, Double>()
                            var sum = 0.0

                            for (id in participants) {
                                val entered =
                                    unequalAmounts[id]?.trim()?.toDoubleOrNull()

                                if (entered == null || entered < 0.0) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Enter valid amount for all selected members."
                                        )
                                    }
                                    return@FloatingActionButton
                                }

                                map[id] = entered
                                sum += entered
                            }

                            val formattedSum =
                                String.format("%.2f", sum).toDouble()
                            val formattedTotal =
                                String.format("%.2f", totalAmount).toDouble()

                            if (formattedSum != formattedTotal) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Total unequal split must equal ₹$formattedTotal"
                                    )
                                }
                                return@FloatingActionButton
                            }

                            map
                        }

                        else -> emptyMap()
                    }

                    val expense = hashMapOf(
                        "title" to title.trim(),
                        "amount" to String.format("%.2f", totalAmount).toDouble(),
                        "paidBy" to paidBy,
                        "splitType" to splitType,
                        "splits" to splits,
                        "timestamp" to System.currentTimeMillis()
                    )

                    isSaving = true

                    db.collection("groups")
                        .document(groupId)
                        .collection("expenses")
                        .add(expense)
                        .addOnSuccessListener { expenseRef ->

                            val newExpenseId = expenseRef.id
                            val actorName =
                                userProfileNames[currentUserId]
                                    ?: currentUser?.second
                                    ?: "Someone"

                            val allGroupMemberIds =
                                groupMembers.map { it.first }.distinct()

                            val totalAmountInSubunits =
                                (totalAmount * 100).toLong()

                            val activity = hashMapOf(
                                "type" to "EXPENSE_ADDED",
                                "actorId" to currentUserId,
                                "actorName" to actorName,
                                "groupId" to groupId,
                                "groupName" to groupName,
                                "relatedExpenseId" to newExpenseId,
                                "relatedExpenseTitle" to title.trim(),
                                "amount" to totalAmountInSubunits,
                                "currencyCode" to "INR",
                                "timestampMillis" to System.currentTimeMillis(),
                                "participants" to allGroupMemberIds
                            )

                            db.collection("activities")
                                .add(activity)
                                .addOnCompleteListener {
                                    isSaving = false
                                    navController.popBackStack()
                                }
                        }
                        .addOnFailureListener {
                            isSaving = false
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    "Failed to add expense."
                                )
                            }
                        }
                }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Save"
                    )
                }
            }
        },

        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }

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
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = amount,
                onValueChange = {
                    amount = sanitizeDecimalInput(it)
                },
                label = { Text("Total Amount") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
            )

            Spacer(Modifier.height(16.dp))

            val paidByDisplayName =
                if (paidBy == currentUserId) {
                    "Me"
                } else {
                    userProfileNames[paidBy]
                        ?: groupMembers.find { it.first == paidBy }?.second
                        ?: "Select"
                }

            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = {
                    if (!isSaving) {
                        dropdownExpanded = !dropdownExpanded
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {

                OutlinedTextField(
                    value = paidByDisplayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Paid By") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = dropdownExpanded
                        )
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving
                )

                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = {
                        dropdownExpanded = false
                    }
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
                        .forEach { member ->

                            val profileName =
                                userProfileNames[member.first]
                                    ?: member.second

                            DropdownMenuItem(
                                text = { Text(profileName) },
                                onClick = {
                                    paidBy = member.first
                                    dropdownExpanded = false
                                }
                            )
                        }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Split Type",
                fontWeight = FontWeight.SemiBold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                RadioButton(
                    selected = splitType == "equal",
                    onClick = {
                        if (!isSaving) splitType = "equal"
                    }
                )
                Text("Equal")

                Spacer(Modifier.width(16.dp))

                RadioButton(
                    selected = splitType == "unequal",
                    onClick = {
                        if (!isSaving) splitType = "unequal"
                    }
                )
                Text("Unequal")
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Select Members Involved",
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                items(groupMembers) { (id, defaultName, email) ->

                    val profileName =
                        userProfileNames[id] ?: defaultName

                    val isChecked =
                        selectedMembers.contains(id)

                    val total =
                        amount.toDoubleOrNull() ?: 0.0

                    val perPerson =
                        if (
                            splitType == "equal" &&
                            selectedMembers.isNotEmpty()
                        ) {
                            total / selectedMembers.distinct().size
                        } else null

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF4F6FA)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = isChecked,
                                enabled = !isSaving,
                                onValueChange = {
                                    toggleMember(id, it)
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
                                    Text(
                                        text = profileName.first().toString(),
                                        color = Color.White
                                    )
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        profileName,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        email,
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }

                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null
                                )
                            }

                            if (
                                splitType == "equal" &&
                                isChecked &&
                                perPerson != null
                            ) {
                                Text(
                                    text = "₹ %.2f will be split".format(perPerson),
                                    fontSize = 13.sp,
                                    color = Color(0xFF2C5A8C),
                                    modifier = Modifier.padding(
                                        start = 60.dp,
                                        bottom = 8.dp
                                    )
                                )
                            }

                            if (
                                splitType == "unequal" &&
                                isChecked
                            ) {
                                OutlinedTextField(
                                    value = unequalAmounts[id] ?: "",
                                    onValueChange = {
                                        unequalAmounts[id] =
                                            sanitizeDecimalInput(it)
                                    },
                                    label = {
                                        Text("₹ Amount for $profileName")
                                    },
                                    singleLine = true,
                                    enabled = !isSaving,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Decimal
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = 16.dp,
                                            vertical = 8.dp
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
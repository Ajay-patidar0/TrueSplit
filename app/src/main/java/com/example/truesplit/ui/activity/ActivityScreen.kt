// ActivityScreen.kt
package com.example.truesplit.ui.activity

import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.ktx.firestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.callbackFlow

// ---------- Activity types ----------
enum class ActivityType {
    EXPENSE_ADDED,
    EXPENSE_EDITED,
    EXPENSE_DELETED,
    SETTLE_REQUEST,
    SETTLE_APPROVED,
    GROUP_MEMBER_ADDED,
    GROUP_MEMBER_REMOVED,
    GROUP_RENAMED;

    companion object {
        fun fromString(s: String?): ActivityType {
            if (s == null) return EXPENSE_ADDED
            return try {
                valueOf(s)
            } catch (e: Exception) {
                EXPENSE_ADDED
            }
        }
    }
}

// ---------- Data model (Parcelable implemented) ----------
data class ActivityItem(
    val id: String,
    val type: ActivityType,
    val actorName: String,
    val actorId: String?,
    val groupId: String?,
    val groupName: String?,
    val relatedExpenseId: String?,
    val relatedExpenseTitle: String?,
    val amount: Long?,           // stored as subunits (paisa/cents). nullable.
    val currencyCode: String,
    val message: String?,
    val timestampMillis: Long,
    val participants: List<String> = emptyList(),
    val status: String? = null, // e.g., "pending", "approved"
    val originalRequesterName: String? = null // For SETTLE_APPROVED
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        type = ActivityType.fromString(parcel.readString()),
        actorName = parcel.readString() ?: "",
        actorId = parcel.readString(),
        groupId = parcel.readString(),
        groupName = parcel.readString(),
        relatedExpenseId = parcel.readString(),
        relatedExpenseTitle = parcel.readString(),
        amount = parcel.readValue(Long::class.java.classLoader) as? Long,
        currencyCode = parcel.readString() ?: "INR",
        message = parcel.readString(),
        timestampMillis = parcel.readLong(),
        participants = mutableListOf<String>().apply { parcel.readStringList(this) },
        status = parcel.readString(),
        originalRequesterName = parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(type.name)
        parcel.writeString(actorName)
        parcel.writeString(actorId)
        parcel.writeString(groupId)
        parcel.writeString(groupName)
        parcel.writeString(relatedExpenseId)
        parcel.writeString(relatedExpenseTitle)
        parcel.writeValue(amount)
        parcel.writeString(currencyCode)
        parcel.writeString(message)
        parcel.writeLong(timestampMillis)
        parcel.writeStringList(participants)
        parcel.writeString(status)
        parcel.writeString(originalRequesterName)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ActivityItem> {
        override fun createFromParcel(parcel: Parcel): ActivityItem = ActivityItem(parcel)
        override fun newArray(size: Int): Array<ActivityItem?> = arrayOfNulls(size)
    }
}

// ---------- Filters & UI state ----------
enum class ActivityFilter { ALL, EXPENSES, SETTLEMENTS, GROUPS }

data class ActivityUiState(
    val filter: ActivityFilter = ActivityFilter.ALL,
    val query: String = ""
)

// ---------- Firestore Repository ----------
class ActivityRepository(
    private val firestore: FirebaseFirestore = Firebase.firestore,
    private val auth: FirebaseAuth = Firebase.auth
) {

    // Listen to activities where current user is a participant (array-contains)
    fun activitiesFlowForCurrentUser(): Flow<List<ActivityItem>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val col = firestore.collection("activities")
            .whereArrayContains("participants", uid)
            .orderBy("timestampMillis", Query.Direction.DESCENDING)

        val listener = col.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w("ActivityRepository", "Listen error", error)
                return@addSnapshotListener
            }
            val items = snapshot?.documents?.mapNotNull { doc ->
                try {
                    val id = doc.id
                    val typeStr = doc.getString("type")
                    val type = ActivityType.fromString(typeStr)
                    val actorName = doc.getString("actorName") ?: doc.getString("actor") ?: "Unknown"
                    val actorId = doc.getString("actorId")
                    val groupId = doc.getString("groupId")
                    val groupName = doc.getString("groupName")
                    val relatedExpenseId = doc.getString("relatedExpenseId")
                    val relatedExpenseTitle = doc.getString("relatedExpenseTitle")
                    val amount = when {
                        doc.contains("amount") -> {
                            val v = doc.get("amount")
                            when (v) {
                                is Long -> v
                                is Double -> v.toLong()
                                is Int -> v.toLong()
                                is String -> v.toLongOrNull()
                                else -> null
                            }
                        }
                        else -> null
                    }
                    val currencyCode = doc.getString("currencyCode") ?: "INR"
                    val message = doc.getString("message")
                    val tsMillis = when {
                        doc.contains("timestampMillis") -> {
                            val t = doc.getLong("timestampMillis")
                            t ?: (doc.getTimestamp("timestamp")?.toDate()?.time ?: System.currentTimeMillis())
                        }
                        doc.getTimestamp("timestamp") != null -> doc.getTimestamp("timestamp")?.toDate()?.time ?: System.currentTimeMillis()
                        else -> System.currentTimeMillis()
                    }
                    val participants = doc.get("participants") as? List<*>
                    val participantStrings = participants?.filterIsInstance<String>() ?: emptyList()
                    val status = doc.getString("status")
                    val originalRequesterName = doc.getString("originalRequesterName")

                    ActivityItem(
                        id = id,
                        type = type,
                        actorName = actorName,
                        actorId = actorId,
                        groupId = groupId,
                        groupName = groupName,
                        relatedExpenseId = relatedExpenseId,
                        relatedExpenseTitle = relatedExpenseTitle,
                        amount = amount,
                        currencyCode = currencyCode,
                        message = message,
                        timestampMillis = tsMillis,
                        participants = participantStrings,
                        status = status,
                        originalRequesterName = originalRequesterName
                    )
                } catch (e: Exception) {
                    Log.e("ActivityRepository", "Error parsing activity doc: ${doc.id}", e)
                    null
                }
            } ?: emptyList()
            trySend(items)
        }

        awaitClose { listener.remove() }
    }

    // Last seen stored per user document
    fun lastSeenFlowForCurrentUser(): Flow<Long> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(0L)
            close()
            return@callbackFlow
        }
        val docRef = firestore.collection("users").document(uid)
        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w("ActivityRepository", "Listen error for lastSeen", error)
                trySend(0L)
                return@addSnapshotListener
            }
            val millis = snapshot?.getLong("lastSeenActivityMillis") ?: 0L
            trySend(millis)
        }
        awaitClose { listener.remove() }
    }

    // Mark last seen to now
    suspend fun markLastSeenNow() {
        val uid = auth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        firestore.collection("users").document(uid)
            .set(mapOf("lastSeenActivityMillis" to now), SetOptions.merge())
            .await()
    }

    // Approve settle request: update the existing activity doc (set status) and create a new SETTLE_APPROVED activity
    suspend fun approveSettleRequest(activityId: String) {
        val uid = auth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val activityRef = firestore.collection("activities").document(activityId)

        val snapshot = activityRef.get().await()
        val original = snapshot.data
        if (original == null || original["status"] == "approved") {
            Log.w("ActivityRepository", "Settle request $activityId already approved or not found.")
            return
        }

        val updates = mapOf(
            "status" to "approved",
            "approvedBy" to uid,
            "approvedAtMillis" to now
        )
        activityRef.update(updates).await()

        val participants = original["participants"] as? List<*> ?: listOf(uid)
        val actorName = auth.currentUser?.displayName ?: "Unknown"
        val originalRequesterId = original["actorId"]
        val originalRequesterName = original["actorName"]

        val approvedDoc = mapOf(
            "type" to ActivityType.SETTLE_APPROVED.name,
            "actorName" to actorName,
            "actorId" to uid,
            "groupId" to original["groupId"],
            "groupName" to original["groupName"],
            "relatedExpenseId" to original["relatedExpenseId"],
            "relatedExpenseTitle" to original["relatedExpenseTitle"],
            "amount" to original["amount"],
            "currencyCode" to (original["currencyCode"] ?: "INR"),
            "timestampMillis" to now,
            "participants" to participants,
            "originalRequesterId" to originalRequesterId,
            "originalRequesterName" to originalRequesterName
        )
        firestore.collection("activities").add(approvedDoc).await()
    }
}

// ---------- ViewModel ----------
class ActivityViewModel(
    private val repo: ActivityRepository = ActivityRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    private val activitiesFlow = repo.activitiesFlowForCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastSeenFlow: StateFlow<Long> = repo.lastSeenFlowForCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val filteredActivitiesFlow: Flow<List<ActivityItem>> =
        combine(activitiesFlow, uiState) { activities, state ->
            val q = state.query.trim().lowercase(Locale.getDefault())
            activities.filter { act ->
                val matchesFilter = when (state.filter) {
                    ActivityFilter.ALL -> true
                    ActivityFilter.EXPENSES -> act.type in setOf(ActivityType.EXPENSE_ADDED, ActivityType.EXPENSE_EDITED, ActivityType.EXPENSE_DELETED)
                    ActivityFilter.SETTLEMENTS -> act.type in setOf(ActivityType.SETTLE_REQUEST, ActivityType.SETTLE_APPROVED)
                    ActivityFilter.GROUPS -> act.type in setOf(ActivityType.GROUP_MEMBER_ADDED, ActivityType.GROUP_MEMBER_REMOVED, ActivityType.GROUP_RENAMED)
                }
                val matchesQuery = q.isEmpty() || listOfNotNull(
                    act.actorName,
                    act.groupName,
                    act.relatedExpenseTitle,
                    act.message
                ).any { it.lowercase(Locale.getDefault()).contains(q) }
                matchesFilter && matchesQuery
            }
        }

    fun setFilter(filter: ActivityFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun markAllRead() {
        viewModelScope.launch {
            try {
                repo.markLastSeenNow()
            } catch (e: Exception) {
                Log.e("ActivityViewModel", "Failed to mark all read", e)
            }
        }
    }

    fun approveSettle(activityId: String) {
        viewModelScope.launch {
            try {
                repo.approveSettleRequest(activityId)
            } catch (e: Exception) {
                Log.e("ActivityViewModel", "Failed to approve settle request", e)
            }
        }
    }
}

// ---------- Utilities for date grouping and formatting ----------
private fun startOfDayMillis(millis: Long, tz: TimeZone = TimeZone.getDefault()): Long {
    val cal = Calendar.getInstance(tz).apply { timeInMillis = millis }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun daysBetween(startMillis: Long, endMillis: Long, tz: TimeZone = TimeZone.getDefault()): Long {
    val s = startOfDayMillis(startMillis, tz)
    val e = startOfDayMillis(endMillis, tz)
    val diff = e - s
    return TimeUnit.MILLISECONDS.toDays(diff)
}

private fun humanReadableDateGroup(tsMillis: Long): String {
    val now = System.currentTimeMillis()
    val daysDiff = daysBetween(tsMillis, now)
    return when {
        daysDiff == 0L -> "Today"
        daysDiff == 1L -> "Yesterday"
        daysDiff <= 7L -> "Last 7 days"
        else -> "Older"
    }
}

private fun niceTimestamp(tsMillis: Long): String {
    val now = System.currentTimeMillis()
    val calThen = Calendar.getInstance().apply { timeInMillis = tsMillis }
    val calNow = Calendar.getInstance().apply { timeInMillis = now }
    return if (calThen.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
        calThen.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)
    ) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(tsMillis))
    } else {
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(tsMillis))
    }
}

private fun formatCurrency(amountSubunits: Long?, currencyCode: String): String {
    if (amountSubunits == null) return ""
    val major = amountSubunits.toDouble() / 100.0
    return try {
        val nf = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        nf.currency = Currency.getInstance(currencyCode.uppercase(Locale.getDefault()))
        nf.format(major)
    } catch (e: Exception) {
        String.format(Locale.getDefault(), "%.2f %s", major, currencyCode)
    }
}

// ---------- Composables ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel = viewModel(),
    onOpenExpense: (String) -> Unit = {},
    onOpenGroup: (String) -> Unit = {},
    onOpenSettle: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val activities by viewModel.filteredActivitiesFlow.collectAsState(initial = emptyList())
    val lastSeen by viewModel.lastSeenFlow.collectAsState()
    val currentUserId = Firebase.auth.currentUser?.uid

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Activity",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (activities.any { it.timestampMillis > lastSeen }) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        ) {
                            IconButton(
                                onClick = { viewModel.markAllRead() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Mark all as read",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Search and Filter Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Search Bar
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = { viewModel.setQuery(it) },
                        placeholder = { Text("Search activities...") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (uiState.query.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.setQuery("") }
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Filter Chips
                    Text(
                        text = "Filter by:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(listOf(
                            ActivityFilter.ALL to "All",
                            ActivityFilter.EXPENSES to "Expenses",
                            ActivityFilter.SETTLEMENTS to "Settlements",
                            ActivityFilter.GROUPS to "Groups"
                        )) { (filter, label) ->
                            FilterChip(
                                selected = uiState.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                                label = {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    selected = uiState.filter == filter,
                                    enabled = true,
                                    borderColor = if (uiState.filter == filter) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.outline
                                ),
                                enabled = true
                            )
                        }
                    }
                }
            }

            // Activities List
            if (activities.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = if (uiState.query.isNotBlank()) "No activities found" else "No activities yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (uiState.query.isNotBlank()) {
                            Text(
                                text = "Try adjusting your search or filters",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                val grouped = activities.groupBy { humanReadableDateGroup(it.timestampMillis) }
                    .toSortedMap(compareByDescending { key ->
                        when (key) {
                            "Today" -> 3; "Yesterday" -> 2; "Last 7 days" -> 1; else -> 0
                        }
                    })

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    grouped.forEach { (groupLabel, items) ->
                        item {
                            Text(
                                text = groupLabel,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp)
                            )
                        }

                        items(items, key = { it.id }) { activity ->
                            val isUnread = activity.timestampMillis > lastSeen
                            ActivityCard(
                                activityItem = activity,
                                isUnread = isUnread,
                                currentUserId = currentUserId,
                                onClick = {
                                    when (activity.type) {
                                        ActivityType.EXPENSE_ADDED,
                                        ActivityType.EXPENSE_EDITED,
                                        ActivityType.EXPENSE_DELETED -> activity.relatedExpenseId?.let { onOpenExpense(it) }
                                        ActivityType.SETTLE_REQUEST,
                                        ActivityType.SETTLE_APPROVED -> onOpenSettle(activity.id)
                                        ActivityType.GROUP_MEMBER_ADDED,
                                        ActivityType.GROUP_MEMBER_REMOVED,
                                        ActivityType.GROUP_RENAMED -> activity.groupId?.let { onOpenGroup(it) }
                                    }
                                },
                                onApproveSettle = { viewModel.approveSettle(activity.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activityItem: ActivityItem,
    isUnread: Boolean,
    currentUserId: String?,
    onClick: () -> Unit,
    onApproveSettle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Activity Icon with Status Indicator
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                // Unread Indicator
                if (isUnread) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }

                // Icon Container
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(getActivityColor(activityItem.type).copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getActivityIcon(activityItem.type),
                        contentDescription = null,
                        tint = getActivityColor(activityItem.type),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Header with Title and Time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = getActivityTitle(activityItem),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = niceTimestamp(activityItem.timestampMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Description
                Text(
                    text = buildActivityDescription(activityItem, currentUserId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Settle Request Actions
                // Settlement UI (Request + Approved both show amount)
                // Settlement UI (Request + Approved)
                if (activityItem.type == ActivityType.SETTLE_REQUEST ||
                    activityItem.type == ActivityType.SETTLE_APPROVED) {

                    val isRequester = activityItem.actorId == currentUserId

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Amount
                        Text(
                            text = formatCurrency(activityItem.amount, activityItem.currencyCode),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )

                        // -------- SETTLE REQUEST --------
                        if (activityItem.type == ActivityType.SETTLE_REQUEST) {

                            when {
                                activityItem.status == "approved" -> {
                                    Text(
                                        "Approved",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }

                                // ✅ ONLY RECEIVER CAN APPROVE
                                !isRequester -> {
                                    FilledTonalButton(
                                        onClick = { onApproveSettle() },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Approve", style = MaterialTheme.typography.labelMedium)
                                    }
                                }

                                // ❌ REQUESTER VIEW → show "Pending"
                                else -> {
                                    Text(
                                        "Pending",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    )
                                }
                            }
                        }

                        // -------- SETTLE APPROVED --------
                        else {
                            Text(
                                "Settled",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper functions for activity styling
@Composable
private fun getActivityColor(type: ActivityType): Color {
    return when (type) {
        ActivityType.EXPENSE_ADDED -> Color(0xFF10B981) // Emerald
        ActivityType.EXPENSE_EDITED -> Color(0xFFF59E0B) // Amber
        ActivityType.EXPENSE_DELETED -> Color(0xFFEF4444) // Red
        ActivityType.SETTLE_REQUEST -> Color(0xFF8B5CF6) // Violet
        ActivityType.SETTLE_APPROVED -> Color(0xFF06B6D4) // Cyan
        ActivityType.GROUP_MEMBER_ADDED -> Color(0xFF84CC16) // Lime
        ActivityType.GROUP_MEMBER_REMOVED -> Color(0xFFF97316) // Orange
        ActivityType.GROUP_RENAMED -> Color(0xFF6366F1) // Indigo
    }
}

private fun getActivityIcon(type: ActivityType): ImageVector {
    return when (type) {
        ActivityType.EXPENSE_ADDED -> Icons.Default.Check
        ActivityType.EXPENSE_EDITED -> Icons.Default.Edit
        ActivityType.EXPENSE_DELETED -> Icons.Default.Clear
        ActivityType.SETTLE_REQUEST -> Icons.Default.AttachMoney
        ActivityType.SETTLE_APPROVED -> Icons.Default.Verified
        ActivityType.GROUP_MEMBER_ADDED -> Icons.Default.PersonAdd
        ActivityType.GROUP_MEMBER_REMOVED -> Icons.Default.PersonRemove
        ActivityType.GROUP_RENAMED -> Icons.Default.DriveFileRenameOutline
    }
}

private fun getActivityTitle(activityItem: ActivityItem): String {
    return when (activityItem.type) {
        ActivityType.EXPENSE_ADDED -> "Expense Added"
        ActivityType.EXPENSE_EDITED -> "Expense Updated"
        ActivityType.EXPENSE_DELETED -> "Expense Deleted"
        ActivityType.SETTLE_REQUEST -> "Settle Request"
        ActivityType.SETTLE_APPROVED -> "Settle Approved"
        ActivityType.GROUP_MEMBER_ADDED -> "Member Added"
        ActivityType.GROUP_MEMBER_REMOVED -> "Member Removed"
        ActivityType.GROUP_RENAMED -> "Group Renamed"
    }
}

private fun buildActivityDescription(act: ActivityItem, currentUserId: String?): String {
    val isMe = act.actorId != null && act.actorId == currentUserId
    val actor = if (isMe) "You" else act.actorName

    if (act.message != null && act.message.isNotBlank() && act.type !in listOf(
            ActivityType.SETTLE_REQUEST,
            ActivityType.SETTLE_APPROVED,
            ActivityType.EXPENSE_ADDED
        )
    ) {
        return act.message
    }

    return when (act.type) {
        ActivityType.EXPENSE_ADDED -> {
            val amount = formatCurrency(act.amount, act.currencyCode)
            "$actor added $amount for ${act.relatedExpenseTitle ?: "an expense"}" + (act.groupName?.let { " in $it" } ?: "")
        }
        ActivityType.EXPENSE_EDITED -> {
            "$actor updated the expense '${act.relatedExpenseTitle ?: ""}'" + (act.groupName?.let { " in $it" } ?: "")
        }
        ActivityType.EXPENSE_DELETED -> {
            "$actor deleted the expense '${act.relatedExpenseTitle ?: ""}'" + (act.groupName?.let { " in $it" } ?: "")
        }
        ActivityType.SETTLE_REQUEST -> {
            val amount = formatCurrency(act.amount, act.currencyCode)
            if (isMe) {
                "You sent a settle-up request of $amount"
            } else {
                "$actor sent you a settle-up request of $amount"
            }
        }
        ActivityType.SETTLE_APPROVED -> {
            val amount = formatCurrency(act.amount, act.currencyCode)
            if (isMe) {
                "You settled $amount with ${act.originalRequesterName ?: "someone"}"
            } else {
                "$actor settled $amount with you"
            }
        }
        ActivityType.GROUP_MEMBER_ADDED -> {
            act.message ?: "$actor joined ${act.groupName ?: "a group"}"
        }
        ActivityType.GROUP_MEMBER_REMOVED -> {
            act.message ?: "$actor left ${act.groupName ?: "a group"}"
        }
        ActivityType.GROUP_RENAMED -> {
            act.message ?: "$actor renamed the group to ${act.groupName ?: "a new name"}"
        }
    }
}
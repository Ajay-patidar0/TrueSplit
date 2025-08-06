package com.example.truesplit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.truesplit.screens.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth
    private val db = FirebaseFirestore.getInstance()

    private var groupInviteInfo by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        auth = FirebaseAuth.getInstance()

        handleIntent(intent)

        setContent {
            MaterialTheme {
                MainApp(
                    initialInviteInfo = groupInviteInfo,
                    onInviteHandled = { groupInviteInfo = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val groupId = intent?.getStringExtra("groupId")
        val groupName = intent?.getStringExtra("groupName") ?: "this group"
        if (!groupId.isNullOrEmpty()) {
            this.groupInviteInfo = Pair(groupId, groupName)
            intent?.removeExtra("groupId")
            intent?.removeExtra("groupName")
        }
    }

    @Composable
    fun MainApp(
        initialInviteInfo: Pair<String, String>?,
        onInviteHandled: () -> Unit
    ) {
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }
        val coroutineScope = rememberCoroutineScope()

        var isProfileLoaded by remember { mutableStateOf(false) }
        var isProfileComplete by remember { mutableStateOf(false) }

        var inviteInfo by remember { mutableStateOf(initialInviteInfo) }
        LaunchedEffect(initialInviteInfo) {
            inviteInfo = initialInviteInfo
        }

        LaunchedEffect(auth.currentUser) {
            val user = auth.currentUser
            if (user != null) {
                val doc = db.collection("users").document(user.uid).get().await()
                isProfileComplete = doc.exists() && doc.getString("name") != null
            }
            isProfileLoaded = true
        }

        Surface {
            if (isProfileLoaded) {
                NavHost(
                    navController = navController,
                    startDestination = when {
                        auth.currentUser == null -> "login"
                        !isProfileComplete -> "profileSetup"
                        else -> "groups"
                    }
                ) {
                    composable("login") { LoginScreen(navController, auth) }
                    composable("signup") { SignupScreen(navController, auth) }
                    composable("profileSetup") {
                        ProfileSetupScreen(
                            navToHome = {
                                navController.navigate("groups") { popUpTo("profileSetup") { inclusive = true } }
                            },
                            auth = auth
                        )
                    }
                    composable("groups") {
                        // CORRECTED: The 'refreshTrigger' parameter has been removed as it's no longer needed.
                        GroupScreen(
                            auth = auth,
                            navToDetails = { groupId -> navController.navigate("groupDetail/$groupId") }
                        )
                    }
                    composable(
                        "groupDetail/{groupId}",
                        arguments = listOf(navArgument("groupId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                        GroupDetailScreen(
                            groupId = groupId,
                            navController = navController,
                            navBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        "addExpense/{groupId}",
                        arguments = listOf(navArgument("groupId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                        var groupMembers by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
                        var isLoading by remember { mutableStateOf(true) }

                        LaunchedEffect(groupId) {
                            isLoading = true
                            try {
                                val snapshot = db.collection("groups").document(groupId).get().await()
                                val rawMembers = snapshot.get("members")
                                groupMembers = when (rawMembers) {
                                    is List<*> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        (rawMembers as List<Map<String, String>>).map {
                                            Triple(it["id"] ?: "", it["name"] ?: "", it["email"] ?: "")
                                        }
                                    }
                                    else -> emptyList()
                                }
                            } catch (e: Exception) {
                                // Handle potential error fetching data
                            } finally {
                                isLoading = false
                            }
                        }

                        if (isLoading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            AddExpenseScreen(
                                groupId = groupId,
                                navController = navController,
                                groupMembers = groupMembers
                            )
                        }
                    }
                    composable(
                        "settle_up/{groupId}",
                        arguments = listOf(navArgument("groupId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                        SettleUpScreen(
                            groupId = groupId,
                            navBack = { navController.popBackStack() }
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom).padding(bottom = 16.dp)
            )

            inviteInfo?.let { (groupId, groupName) ->
                JoinGroupDialog(
                    groupName = groupName,
                    onDismiss = {
                        inviteInfo = null
                        onInviteHandled()
                    },
                    onConfirm = {
                        // The dialog closes instantly.
                        inviteInfo = null
                        onInviteHandled()

                        coroutineScope.launch {
                            val canNavigate = joinGroup(groupId, groupName, snackbarHostState)
                            if (canNavigate) {
                                // This navigation logic forces a refresh of the group list screen.
                                navController.navigate("groupDetail/$groupId") {
                                    popUpTo("groups") { inclusive = true }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private suspend fun joinGroup(groupId: String, groupName: String, snackbarHostState: SnackbarHostState): Boolean {
        val user = auth.currentUser
        if (user == null) {
            snackbarHostState.showSnackbar("You must be logged in to join a group.")
            return false
        }

        val groupDocRef = db.collection("groups").document(groupId)

        try {
            val snapshot = groupDocRef.get().await()
            if (!snapshot.exists()) {
                snackbarHostState.showSnackbar("This group no longer exists.")
                return false
            }

            val members = snapshot.get("members") as? List<Map<String, String>> ?: emptyList()
            val memberIds = snapshot.get("memberIds") as? List<String> ?: emptyList()

            if (members.any { it["id"] == user.uid }) {
                snackbarHostState.showSnackbar("You are already a member of '$groupName'.")
                return true // Allow navigation.
            }

            val userDoc = db.collection("users").document(user.uid).get().await()
            val userName = userDoc.getString("name") ?: user.email?.substringBefore('@') ?: "Unknown"

            val newMember = mapOf("id" to user.uid, "name" to userName, "email" to user.email)
            val updatedMembers = members + newMember
            val updatedMemberIds = memberIds + user.uid

            groupDocRef.update(
                mapOf(
                    "members" to updatedMembers,
                    "memberIds" to updatedMemberIds
                )
            ).await()

            snackbarHostState.showSnackbar("Successfully joined '$groupName'!")
            return true

        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Failed to join group: ${e.message}")
            return false
        }
    }


    @Composable
    fun JoinGroupDialog(groupName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Group Invitation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Do you want to join \"$groupName\"?",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onConfirm) { Text("Join") }
                    }
                }
            }
        }
    }
}
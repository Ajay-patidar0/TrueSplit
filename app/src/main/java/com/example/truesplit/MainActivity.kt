package com.example.truesplit

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.truesplit.screens.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import co.ab180.airbridge.Airbridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        auth = FirebaseAuth.getInstance()

        setContent {
            MaterialTheme {
                Surface {
                    val navController = rememberNavController()
                    val currentUser = auth.currentUser
                    val snackbarHostState = remember { SnackbarHostState() }
                    val coroutineScope = rememberCoroutineScope()

                    // Group Join Dialog and Navigation States
                    var showJoinDialog by remember { mutableStateOf(false) }
                    var groupName by remember { mutableStateOf("this group") }
                    var groupIdToJoin by remember { mutableStateOf<String?>(null) }
                    var joinedSuccessfully by remember { mutableStateOf(false) }
                    var hasHandledDeepLink by remember { mutableStateOf(false) }

                    // Handle deep links and intent only once
                    LaunchedEffect(Unit) {
                        if (!hasHandledDeepLink) {
                            hasHandledDeepLink = true

                            // Handle Airbridge deep link
                            Airbridge.handleDeeplink(intent) { uri: Uri? ->
                                uri?.let {
                                    val groupId = it.getQueryParameter("groupId")
                                    val name = it.getQueryParameter("groupName") ?: "this group"
                                    if (!groupId.isNullOrEmpty()) {
                                        groupIdToJoin = groupId
                                        groupName = name
                                        showJoinDialog = true
                                    }
                                }
                            }

                            // Fallback intent (external share)
                            val intentGroupId = intent.getStringExtra("groupId")
                            val intentGroupName = intent.getStringExtra("groupName") ?: "this group"
                            if (!intentGroupId.isNullOrEmpty()) {
                                groupIdToJoin = intentGroupId
                                groupName = intentGroupName
                                showJoinDialog = true
                            }
                        }
                    }

                    // Navigation Graph
                    NavHost(
                        navController = navController,
                        startDestination = if (currentUser == null) "login" else "groups"
                    ) {
                        composable("login") {
                            LoginScreen(navController, auth)
                        }
                        composable("signup") {
                            SignupScreen(navController, auth)
                        }
                        composable("groups") {
                            GroupScreen(
                                auth = auth,
                                navToDetails = { groupId ->
                                    navController.navigate("groupDetail/$groupId")
                                },
                                refreshTrigger = joinedSuccessfully.also { joinedSuccessfully = false }
                            )
                        }
                        composable(
                            "groupDetail/{groupId}",
                            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
                            deepLinks = listOf(
                                navDeepLink { uriPattern = "https://truesplit.page.link/join?groupId={groupId}" },
                                navDeepLink { uriPattern = "truesplit://join?groupId={groupId}" }
                            )
                        ) { backStackEntry ->
                            val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                            GroupDetailScreen(groupId = groupId) {
                                navController.popBackStack()
                            }
                        }
                    }

                    // Snackbar Host
                    SnackbarHost(hostState = snackbarHostState)

                    // Join Group Dialog
                    if (showJoinDialog && groupIdToJoin != null) {
                        AlertDialog(
                            onDismissRequest = {
                                showJoinDialog = false
                                groupIdToJoin = null
                            },
                            title = { Text(text = "Group Invitation") },
                            text = { Text(text = "Do you want to join \"$groupName\"?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    val userEmail = auth.currentUser?.email ?: return@TextButton
                                    val groupId = groupIdToJoin ?: return@TextButton
                                    val groupDoc = db.collection("groups").document(groupId)

                                    groupDoc.get().addOnSuccessListener { snapshot ->
                                        val members = snapshot.get("members") as? List<String> ?: listOf()

                                        if (!members.contains(userEmail)) {
                                            groupDoc.update("members", members + userEmail)
                                                .addOnSuccessListener {
                                                    showJoinDialog = false
                                                    groupIdToJoin = null

                                                    coroutineScope.launch {
                                                        delay(300)
                                                        navController.navigate("groupDetail/$groupId")
                                                        snackbarHostState.showSnackbar("Successfully joined the group")
                                                    }

                                                    joinedSuccessfully = true
                                                }
                                        } else {
                                            showJoinDialog = false
                                            groupIdToJoin = null

                                            coroutineScope.launch {
                                                delay(300)
                                                navController.navigate("groupDetail/$groupId")
                                                snackbarHostState.showSnackbar("Already a member of this group")
                                            }
                                        }
                                    }.addOnFailureListener {
                                        showJoinDialog = false
                                        groupIdToJoin = null
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Failed to join group")
                                        }
                                    }
                                }) {
                                    Text("Join")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showJoinDialog = false
                                    groupIdToJoin = null
                                }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

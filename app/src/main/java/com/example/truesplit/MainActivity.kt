package com.example.truesplit

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import co.ab180.airbridge.Airbridge
import com.example.truesplit.screens.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
                    val snackbarHostState = remember { SnackbarHostState() }
                    val coroutineScope = rememberCoroutineScope()

                    var showJoinDialog by remember { mutableStateOf(false) }
                    var groupName by remember { mutableStateOf("this group") }
                    var groupIdToJoin by remember { mutableStateOf<String?>(null) }
                    var joinedSuccessfully by remember { mutableStateOf(false) }
                    var hasHandledDeepLink by remember { mutableStateOf(false) }

                    var isProfileLoaded by remember { mutableStateOf(false) }
                    var profileComplete by remember { mutableStateOf(false) }

                    LaunchedEffect(auth.currentUser) {
                        val user = auth.currentUser
                        if (user != null) {
                            val doc = db.collection("users").document(user.uid).get().await()
                            profileComplete = doc.exists() && doc.getString("name") != null
                        }
                        isProfileLoaded = true
                    }

                    LaunchedEffect(Unit) {
                        if (!hasHandledDeepLink) {
                            hasHandledDeepLink = true

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

                            val intentGroupId = intent.getStringExtra("groupId")
                            val intentGroupName = intent.getStringExtra("groupName") ?: "this group"
                            if (!intentGroupId.isNullOrEmpty()) {
                                groupIdToJoin = intentGroupId
                                groupName = intentGroupName
                                showJoinDialog = true
                            }
                        }
                    }

                    if (isProfileLoaded) {
                        NavHost(
                            navController = navController,
                            startDestination = when {
                                auth.currentUser == null -> "login"
                                !profileComplete -> "profileSetup"
                                else -> "groups"
                            }
                        ) {
                            composable("login") {
                                LoginScreen(navController, auth)
                            }
                            composable("signup") {
                                SignupScreen(navController, auth)
                            }
                            composable("profileSetup") {
                                ProfileSetupScreen(
                                    navToHome = {
                                        navController.navigate("groups") {
                                            popUpTo("profileSetup") { inclusive = true }
                                        }
                                    },
                                    auth = auth
                                )
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
                                    val snapshot = FirebaseFirestore.getInstance().collection("groups")
                                        .document(groupId).get().await()

                                    val members = snapshot.get("members") as? List<String> ?: listOf()

                                    groupMembers = members.map { email ->
                                        Triple(email, email.substringBefore("@"), email)
                                    }

                                    isLoading = false
                                }

                                if (!isLoading) {
                                    AddExpenseScreen(
                                        groupId = groupId,
                                        navController = navController,
                                        groupMembers = groupMembers
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }


                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )

                        if (showJoinDialog && groupIdToJoin != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .clickable(enabled = false) {}
                            )

                            Dialog(onDismissRequest = {
                                showJoinDialog = false
                                groupIdToJoin = null
                            }) {
                                Card(
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(20.dp)
                                            .fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Group Invitation",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                color = Color(0xFF2C5A8C),
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "Do you want to join \"${groupName}\"?",
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF3A3A3A)
                                            ),
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    showJoinDialog = false
                                                    groupIdToJoin = null
                                                }
                                            ) {
                                                Text(
                                                    text = "Cancel",
                                                    color = Color(0xFF2C5A8C),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }

                                            Button(
                                                onClick = {
                                                    val user = auth.currentUser ?: return@Button
                                                    val userId = user.uid
                                                    val userEmail = user.email ?: return@Button
                                                    val userName =
                                                        user.displayName ?: userEmail.substringBefore("@")
                                                    val groupId = groupIdToJoin ?: return@Button
                                                    val groupDoc = db.collection("groups").document(groupId)

                                                    groupDoc.get().addOnSuccessListener { snapshot ->
                                                        val members =
                                                            snapshot.get("members") as? List<Map<String, String>> ?: listOf()

                                                        if (members.none { it["id"] == userId }) {
                                                            val newMember = mapOf("id" to userId, "name" to userName)
                                                            groupDoc.update("members", members + newMember)
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
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C5A8C)),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("Join", color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

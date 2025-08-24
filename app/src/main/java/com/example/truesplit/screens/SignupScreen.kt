package com.example.truesplit.screens

import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.truesplit.R
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun SignupScreen(navController: NavController, auth: FirebaseAuth) {

    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()

    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    var googleSignInError by remember { mutableStateOf("") }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener { authTask ->
                if (authTask.isSuccessful) {
                    val userId = auth.currentUser?.uid ?: return@addOnCompleteListener
                    // Check if user document exists in Firestore
                    db.collection("users").document(userId).get()
                        .addOnSuccessListener { document ->
                            if (document.exists()) {
                                // User exists, navigate to home and clear the entire auth stack
                                navController.navigate("home") {
                                    popUpTo("auth") { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                // New user, navigate to profile setup and clear the entire auth stack
                                navController.navigate("profileSetup") {
                                    popUpTo("auth") { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                        .addOnFailureListener {
                            // If there's an error checking the document, still navigate to profile setup
                            navController.navigate("profileSetup") {
                                popUpTo("auth") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                } else {
                    googleSignInError = "Google sign-in failed. Please try again."
                }
            }
        } catch (e: ApiException) {
            googleSignInError = "Google sign-in failed. Please try again."
        }
    }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FB))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Image(
            painter = painterResource(id = R.drawable.bg_img),
            contentDescription = "Signup Image",
            modifier = Modifier.size(140.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Create your account", color = Color(0xFF2C5A8C), fontSize = 26.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Join your friends and split expenses easily", color = Color(0xFF7D7D7D), fontSize = 15.sp)

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email", color = Color(0xFF7D7D7D)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (min 8 characters)", color = Color(0xFF7D7D7D)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirm Password", color = Color(0xFF7D7D7D)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                error = ""
                when {
                    email.isBlank() -> error = "Please enter your email."
                    !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> error = "Invalid email address."
                    password.length < 8 -> error = "Password must be at least 8 characters."
                    password != confirmPassword -> error = "Passwords do not match."
                    else -> {
                        isLoading = true
                        auth.createUserWithEmailAndPassword(email.trim(), password)
                            .addOnCompleteListener { task ->
                                isLoading = false
                                if (task.isSuccessful) {
                                    // Navigate to profile setup and clear the entire auth stack
                                    navController.navigate("profileSetup") {
                                        popUpTo("auth") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    val msg = task.exception?.localizedMessage ?: ""
                                    error = if (msg.contains("email address is already in use", true)) {
                                        "This email is already registered. Please login."
                                    } else {
                                        "Sign up failed. Please try again."
                                    }
                                }
                            }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C5A8C)),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Text("Sign Up", color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedButton(
            onClick = {
                googleSignInError = ""
                val signInIntent = googleSignInClient.signInIntent
                googleLauncher.launch(signInIntent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2C5A8C))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.google_logo),
                    contentDescription = "Google Logo",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Continue with Google")
            }
        }

        if (error.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = error,
                color = Color.Red,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (googleSignInError.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = googleSignInError,
                color = Color.Red,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(onClick = {
            navController.navigate("login") {
                // Replace the current signup screen with login screen
                popUpTo("signup") { inclusive = true }
                launchSingleTop = true
            }
        }) {
            Text("Already have an account? Login", color = Color(0xFF2C5A8C))
        }
    }
}
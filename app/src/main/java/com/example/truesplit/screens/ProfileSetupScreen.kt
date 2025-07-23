package com.example.truesplit.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun ProfileSetupScreen(
    navToHome: () -> Unit,
    auth: FirebaseAuth
) {
    val db = FirebaseFirestore.getInstance()
    val userId = auth.currentUser?.uid ?: return

    var name by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }

    // Predefined color palette
    val colorPalette = listOf(
        "#6CB4C9", "#2C5A8C", "#FBBC05", "#EA4335", "#34A853"
    )
    val randomColor by remember { mutableStateOf(colorPalette.random()) }

    // Check if profile already exists
    LaunchedEffect(Unit) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    navToHome() // ✅ Already set up → Go to home
                } else {
                    isChecking = false // Show setup form
                }
            }
            .addOnFailureListener {
                isChecking = false // If error, allow form anyway
            }
    }

    if (isChecking) {
        // Show a loading indicator
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF2C5A8C))
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Setup your profile", fontSize = 24.sp, color = Color(0xFF2C5A8C))
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Enter your name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        isSaving = true
                        val userProfile = hashMapOf(
                            "name" to name.trim(),
                            "color" to randomColor
                        )
                        db.collection("users").document(userId).set(userProfile)
                            .addOnSuccessListener {
                                navToHome()
                            }
                    }
                },
                enabled = name.isNotBlank() && !isSaving,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C5A8C))
            ) {
                Text("Continue", color = Color.White)
            }
        }
    }
}

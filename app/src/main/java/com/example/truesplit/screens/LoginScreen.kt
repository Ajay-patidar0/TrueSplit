package com.example.truesplit.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.truesplit.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun LoginScreen(
    navController: NavController,
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {

    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var clickedTag by remember { mutableStateOf<String?>(null) }

    val urlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {}

    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->

        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)

        try {

            val account = task.getResult(ApiException::class.java)!!
            val idToken = account.idToken!!
            val credential = GoogleAuthProvider.getCredential(idToken, null)

            coroutineScope.launch {

                isLoading = true

                try {

                    auth.signInWithCredential(credential).await()

                    val user = auth.currentUser!!
                    val userDocRef = db.collection("users").document(user.uid)
                    val userDoc = userDocRef.get().await()

                    if (userDoc.exists() &&
                        userDoc.contains("name") &&
                        userDoc.getString("name")?.isNotEmpty() == true
                    ) {

                        navController.navigate("groups") {
                            popUpTo("login") { inclusive = true }
                        }

                    } else {

                        navController.navigate("profileSetup") {
                            popUpTo("login") { inclusive = true }
                        }

                    }

                } catch (e: Exception) {

                    Toast.makeText(
                        context,
                        "Authentication failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()

                } finally {

                    isLoading = false

                }
            }

        } catch (e: ApiException) {

            Toast.makeText(
                context,
                "Google Sign-In failed. Please try again.",
                Toast.LENGTH_SHORT
            ).show()

            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Image(
                painter = painterResource(id = R.drawable.bg_img),
                contentDescription = "TrueSplit Logo",
                modifier = Modifier.size(150.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Welcome to TrueSplit",
                color = Color(0xFF2C5A8C),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Split expenses with friends quickly and easily",
                color = Color(0xFF666666),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                BenefitItem("✓ Easy expense splitting")
                BenefitItem("✓ Real-time balance tracking")
                BenefitItem("✓ Secure and private")
            }

            Spacer(modifier = Modifier.height(64.dp))

            Button(
                onClick = {
                    if (!isLoading) {
                        val signInIntent = googleSignInClient.signInIntent
                        googleLauncher.launch(signInIntent)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF757575)
                )
            ) {

                if (isLoading) {

                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = Color(0xFF757575)
                    )

                } else {

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Image(
                            painter = painterResource(id = R.drawable.google_logo),
                            contentDescription = "Google Logo",
                            modifier = Modifier.size(20.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            "Continue with Google",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            val annotatedText = buildAnnotatedString {

                append("By continuing, you agree to our ")

                pushStringAnnotation("PRIVACY", "https://yoursite.com/privacy")

                withStyle(
                    style = SpanStyle(
                        color = Color(0xFF2C5A8C),
                        fontWeight = FontWeight.Medium,
                        background = if (clickedTag == "PRIVACY")
                            Color(0xFF2C5A8C).copy(alpha = 0.2f)
                        else Color.Transparent
                    )
                ) { append("Privacy Policy") }

                pop()

                append(" and ")

                pushStringAnnotation("TERMS", "https://yoursite.com/terms")

                withStyle(
                    style = SpanStyle(
                        color = Color(0xFF2C5A8C),
                        fontWeight = FontWeight.Medium,
                        background = if (clickedTag == "TERMS")
                            Color(0xFF2C5A8C).copy(alpha = 0.2f)
                        else Color.Transparent
                    )
                ) { append("Terms of Service") }

                pop()
            }

            ClickableText(
                text = annotatedText,
                style = LocalTextStyle.current.copy(
                    color = Color(0xFF666666),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                onClick = { offset ->

                    annotatedText.getStringAnnotations(offset, offset)
                        .firstOrNull()?.let { annotation ->

                            coroutineScope.launch {

                                clickedTag = annotation.tag
                                delay(150)
                                clickedTag = null

                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(annotation.item)
                                )

                                urlLauncher.launch(intent)
                            }
                        }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {

                CircularProgressIndicator(
                    color = Color(0xFF2C5A8C),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

@Composable
fun BenefitItem(text: String) {

    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = text,
            color = Color(0xFF444444),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
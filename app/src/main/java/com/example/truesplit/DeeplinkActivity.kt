package com.example.truesplit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import co.ab180.airbridge.Airbridge

class DeeplinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle Airbridge deeplink
        Airbridge.handleDeeplink(intent) { uri: Uri? ->
            uri?.let {
                handleDeeplink(it)
            } ?: run {
                finish()
            }
        }

        // Fallback if Airbridge fails
        intent.data?.let {
            handleDeeplink(it)
        } ?: run {
            finish()
        }
    }

    private fun handleDeeplink(uri: Uri) {
        val groupId = uri.getQueryParameter("groupId")
        val groupName = uri.getQueryParameter("groupName") ?: "this group"

        if (!groupId.isNullOrEmpty()) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                // This avoids opening multiple MainActivity instances
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("groupId", groupId)
                putExtra("groupName", groupName)
            }
            startActivity(mainIntent)
        }

        // Always finish this lightweight activity
        finish()
    }
}

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
            uri?.let { handleDeeplink(it) } ?: finish()
        }

        // Fallback if Airbridge fails
        intent.data?.let { handleDeeplink(it) } ?: finish()
    }

    private fun handleDeeplink(uri: Uri) {
        val host = uri.host ?: return
        val path = uri.path ?: ""

        if (host == "truesplit.airbridge.io") {
            when {
                // ✅ Invite Member case
                path.startsWith("/join") -> {
                    openGroupFromLink(
                        uri.getQueryParameter("groupId"),
                        uri.getQueryParameter("groupName") ?: "this group"
                    )
                }

                // ✅ Reminder case
                path.startsWith("/reminder") -> {
                    openGroupFromLink(
                        uri.getQueryParameter("groupId"),
                        uri.getQueryParameter("groupName") ?: "this group",
                        fromReminder = true
                    )
                }
            }
        }

        // Always finish this lightweight activity
        finish()
    }

    private fun openGroupFromLink(groupId: String?, groupName: String, fromReminder: Boolean = false) {
        if (!groupId.isNullOrEmpty()) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("groupId", groupId)
                putExtra("groupName", groupName)
                putExtra("source", if (fromReminder) "reminder" else "invite")
            }
            startActivity(mainIntent)
        }
    }
}

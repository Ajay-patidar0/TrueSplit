package com.example.truesplit.model

import com.google.firebase.Timestamp

data class SettleRequest(
    val id: String = "",
    val fromId: String = "",
    val fromName: String = "",
    val toId: String = "",
    val toName: String = "",
    val groupId: String = "",
    val amount: Double = 0.0,
    val requestType: String = "", // "reminder" or "payment_confirmation"
    val status: String = "pending",
    val timestamp: Timestamp = Timestamp.now(),
)

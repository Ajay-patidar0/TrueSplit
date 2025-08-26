package com.example.truesplit.utils

object ExpenseUtils {

    fun calculateBalances(
        members: List<Map<String, String>>,
        transactions: List<Map<String, Any>>
    ): Map<String, Double> {
        val balances = mutableMapOf<String, Double>()
        if (members.isEmpty()) return balances

        // Initialize all members' balances to 0
        members.forEach { member ->
            balances[member["id"]!!] = 0.0
        }

        transactions.forEach { transaction ->
            val amount = (transaction["amount"] as? Number)?.toDouble() ?: 0.0
            val type = transaction["type"] as? String ?: "expense"
            val paidBy = transaction["paidBy"] as? String
            val receivedBy = transaction["receivedBy"] as? String

            if (type == "settle") {
                if (paidBy != null && receivedBy != null &&
                    balances.containsKey(paidBy) && balances.containsKey(receivedBy)) {
                    balances[paidBy] = balances.getValue(paidBy) + amount
                    balances[receivedBy] = balances.getValue(receivedBy) - amount
                }
            } else {
                if (paidBy != null && balances.containsKey(paidBy)) {
                    // Get the splits from the transaction
                    val splits = transaction["splits"] as? Map<String, Any> ?: emptyMap()

                    // The payer pays the full amount
                    balances[paidBy] = balances.getValue(paidBy) + amount

                    // Each member pays their share according to the splits
                    splits.forEach { (memberId, shareAmount) ->
                        val share = (shareAmount as? Number)?.toDouble() ?: 0.0
                        if (balances.containsKey(memberId)) {
                            balances[memberId] = balances.getValue(memberId) - share
                        }
                    }
                }
            }
        }

        return balances
    }
}
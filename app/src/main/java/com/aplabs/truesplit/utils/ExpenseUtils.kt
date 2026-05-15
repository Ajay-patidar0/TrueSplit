package com.aplabs.truesplit.utils

object ExpenseUtils {

    /**
     * Calculate net balances for all group members based on all transactions.
     * @param members List of member maps, each containing an "id" key.
     * @param transactions List of expense/settlement transactions.
     * @return Map of user ID → net balance (positive = user is owed, negative = user owes).
     */
    fun calculateBalances(
        members: List<Map<String, String>>,
        transactions: List<Map<String, Any>>
    ): Map<String, Double> {
        val balances = mutableMapOf<String, Double>()
        // Initialize all members' balances to 0
        members.forEach { member ->
            val id = member["id"] ?: return@forEach
            balances[id] = 0.0
        }

        transactions.forEach { tx ->
            val amount = (tx["amount"] as? Number)?.toDouble() ?: 0.0
            if (amount <= 0.0) return@forEach

            val type = tx["type"] as? String ?: "expense"

            if (type == "settle") {
                val paidBy = tx["paidBy"] as? String
                val receivedBy = tx["receivedBy"] as? String
                if (paidBy != null && receivedBy != null &&
                    balances.containsKey(paidBy) && balances.containsKey(receivedBy)
                ) {
                    balances[paidBy] = balances.getValue(paidBy) + amount
                    balances[receivedBy] = balances.getValue(receivedBy) - amount
                }
            } else { // expense
                val paidBy = tx["paidBy"] as? String ?: return@forEach
                if (!balances.containsKey(paidBy)) return@forEach

                // Use the robust share computation (supports splits, splitBetween, splitWith)
                val shares = computeSharesForTransaction(tx)

                if (shares.isEmpty()) {
                    // No participants selected: treat as personal expense – no net balance change.
                    // (The payer pays for themselves only.)
                    return@forEach
                }

                // Debit each participant by their share
                shares.forEach { (userId, share) ->
                    if (balances.containsKey(userId)) {
                        balances[userId] = balances.getValue(userId) - share
                    }
                }
                // Credit the payer with the full amount
                balances[paidBy] = balances.getValue(paidBy) + amount
            }
        }

        return balances
    }

    /**
     * Parse split information from an expense transaction.
     * Supports:
     * - "splits": Map<userId, Number|Boolean>
     * - "splitBetween": List<userId>
     * - "splitWith": List<Map<String, Any>> containing "userId" and optional "amount"/"included"
     *
     * @return Map of user ID → share amount (already scaled to match the total expense amount).
     */
    private fun computeSharesForTransaction(tx: Map<String, Any>): Map<String, Double> {
        val amount = (tx["amount"] as? Number)?.toDouble() ?: 0.0
        if (amount <= 0.0) return emptyMap()

        // 1) splits map (key = userId, value = Number or Boolean)
        val splitsRaw = tx["splits"]
        if (splitsRaw is Map<*, *>) {
            // Try numeric amounts first
            val numericEntries = splitsRaw.entries.mapNotNull { (k, v) ->
                val userId = k as? String ?: return@mapNotNull null
                when (v) {
                    is Number -> userId to v.toDouble()
                    is String -> v.toDoubleOrNull()?.let { userId to it }
                    is Boolean -> if (v) userId to 1.0 else null
                    else -> null
                }
            }
            if (numericEntries.isNotEmpty()) {
                val asMap = numericEntries.toMap()
                val sum = asMap.values.sum()
                return if (sum > 0.0) {
                    val scale = amount / sum
                    asMap.mapValues { (_, v) -> v * scale }
                } else {
                    // All zeros → equal split among "true" flags
                    val included = numericEntries.filter { it.second > 0 }.map { it.first }
                    if (included.isNotEmpty()) {
                        val share = amount / included.size
                        included.associateWith { share }
                    } else emptyMap()
                }
            } else {
                // Boolean-only map: take all `true` as participants
                val participants = splitsRaw.entries.mapNotNull { (k, v) ->
                    if (v == true) k as? String else null
                }
                if (participants.isNotEmpty()) {
                    val share = amount / participants.size
                    return participants.associateWith { share }
                }
            }
        }

        // 2) splitBetween: List<userId>
        val splitBetween = tx["splitBetween"]
        if (splitBetween is List<*>) {
            val participants = splitBetween.mapNotNull { it as? String }
            if (participants.isNotEmpty()) {
                val share = amount / participants.size
                return participants.associateWith { share }
            }
        }

        // 3) splitWith: List<Map<...>>
        val splitWith = tx["splitWith"]
        if (splitWith is List<*>) {
            val entries = splitWith.mapNotNull { it as? Map<*, *> }
            // Try explicit amounts first
            val explicit = entries.mapNotNull { m ->
                val uid = m["userId"] as? String ?: return@mapNotNull null
                when (val v = m["amount"]) {
                    is Number -> uid to v.toDouble()
                    is String -> v.toDoubleOrNull()?.let { uid to it }
                    else -> null
                }
            }
            if (explicit.isNotEmpty()) {
                val sum = explicit.sumOf { it.second }
                return if (sum > 0) {
                    val scale = amount / sum
                    explicit.associate { it.first to it.second * scale }
                } else emptyMap()
            }
            // Fallback to inclusion flags
            val participants = entries.mapNotNull { m ->
                val uid = m["userId"] as? String ?: return@mapNotNull null
                val included = when (val inc = m["included"]) {
                    is Boolean -> inc
                    is Number -> inc.toInt() != 0
                    is String -> inc.equals("true", true) || inc == "1"
                    else -> true // presence implies included
                }
                if (included) uid else null
            }
            if (participants.isNotEmpty()) {
                val share = amount / participants.size
                return participants.associateWith { share }
            }
        }

        // No participants defined → treat as personal expense (no one else owes anything)
        return emptyMap()
    }
}
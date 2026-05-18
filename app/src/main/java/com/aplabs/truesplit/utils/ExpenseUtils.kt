package com.aplabs.truesplit.utils

import kotlin.math.abs
import kotlin.math.round

data class SimplifiedSettlement(
    val fromUserId: String,
    val toUserId: String,
    val amount: Double
)

object ExpenseUtils {

    /**
     * Returns all direct debts (no netting across different people).
     * Example: User1 owes Aditya 1500, Ajay owes User1 100.
     */
    fun buildPairwiseSettlements(
        transactions: List<Map<String, Any>>
    ): List<SimplifiedSettlement> {

        // (debtor, creditor) -> amount
        val debts = mutableMapOf<Pair<String, String>, Double>()

        for (tx in transactions) {
            val type = tx["type"] as? String ?: "expense"
            val amount = (tx["amount"] as? Number)?.toDouble() ?: 0.0
            if (amount <= 0.0) continue

            when (type) {
                "settle" -> {
                    val from = tx["paidBy"] as? String ?: continue
                    val to = tx["receivedBy"] as? String ?: continue
                    reduceDebt(debts, from, to, amount)
                }
                else -> { // normal expense
                    val paidBy = tx["paidBy"] as? String ?: continue
                    val shares = computeShares(tx, amount, paidBy)
                    for ((participantId, share) in shares) {
                        if (share <= 0.0) continue
                        if (participantId == paidBy) continue // payer doesn't owe himself
                        addDebt(debts, participantId, paidBy, share)
                    }
                }
            }
        }

        return debts
            .filter { it.value > 0.01 }
            .map { (pair, amt) -> SimplifiedSettlement(pair.first, pair.second, roundTo2(amt)) }
            .sortedByDescending { it.amount }
    }

    /**
     * Returns net balance for each user.
     * Positive = net receiver, Negative = net payer.
     * Use this only for global summaries, NOT for "you are owed" list.
     */
    fun calculateBalances(
        members: List<Map<String, String>>,
        transactions: List<Map<String, Any>>
    ): Map<String, Double> {
        val balances = members.associate { it["id"]!! to 0.0 }.toMutableMap()
        buildPairwiseSettlements(transactions).forEach { settlement ->
            balances[settlement.fromUserId] = roundTo2(balances[settlement.fromUserId]!! - settlement.amount)
            balances[settlement.toUserId] = roundTo2(balances[settlement.toUserId]!! + settlement.amount)
        }
        return balances
    }

    /**
     * Returns list of people who owe money to the given user.
     * (Used for "You are owed" screen)
     */
    fun getIncomingDebts(userId: String, transactions: List<Map<String, Any>>): List<SimplifiedSettlement> {
        return buildPairwiseSettlements(transactions)
            .filter { it.toUserId == userId && it.amount > 0.01 }
    }

    /**
     * Returns list of people to whom the given user owes money.
     * (Used for "You owe" screen)
     */
    fun getOutgoingDebts(userId: String, transactions: List<Map<String, Any>>): List<SimplifiedSettlement> {
        return buildPairwiseSettlements(transactions)
            .filter { it.fromUserId == userId && it.amount > 0.01 }
    }

    // ---------- PRIVATE HELPERS ----------

    private fun addDebt(
        debts: MutableMap<Pair<String, String>, Double>,
        debtor: String,
        creditor: String,
        amount: Double
    ) {
        if (debtor == creditor || amount <= 0.01) return
        val direct = debtor to creditor
        val reverse = creditor to debtor
        val reverseAmount = debts[reverse] ?: 0.0

        if (reverseAmount > 0.0) {
            // Cancel opposite debt (same pair only)
            if (reverseAmount > amount) {
                debts[reverse] = roundTo2(reverseAmount - amount)
            } else {
                debts.remove(reverse)
                val remaining = amount - reverseAmount
                if (remaining > 0.01) {
                    debts[direct] = roundTo2((debts[direct] ?: 0.0) + remaining)
                }
            }
        } else {
            debts[direct] = roundTo2((debts[direct] ?: 0.0) + amount)
        }
    }

    private fun reduceDebt(
        debts: MutableMap<Pair<String, String>, Double>,
        from: String, // debtor who pays
        to: String,   // creditor who receives
        amount: Double
    ) {
        val direct = from to to
        val existing = debts[direct] ?: 0.0
        if (existing > 0.01) {
            val remaining = existing - amount
            if (remaining <= 0.01) debts.remove(direct)
            else debts[direct] = roundTo2(remaining)
        }
        // No direct debt → nothing to reduce (settlement should only happen from debtor to creditor)
    }

    private fun computeShares(
        tx: Map<String, Any>,
        totalAmount: Double,
        paidBy: String
    ): Map<String, Double> {
        val splits = tx["splits"]

        // ----- CUSTOM SPLITS -----
        if (splits is Map<*, *>) {
            val shares = mutableMapOf<String, Double>()
            var sum = 0.0
            for ((key, value) in splits) {
                val userId = key as? String ?: continue
                val share = when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull()
                    else -> null
                } ?: continue
                if (share > 0.0) {
                    val rounded = roundTo2(share)
                    shares[userId] = rounded
                    sum += rounded
                }
            }
            if (shares.isEmpty()) return emptyMap()

            // Fix rounding difference – never add to the payer (would cause missing debt)
            val diff = roundTo2(totalAmount - sum)
            if (abs(diff) > 0.01) {
                val firstNonPayer = shares.keys.firstOrNull { it != paidBy }
                if (firstNonPayer != null) {
                    shares[firstNonPayer] = roundTo2(shares[firstNonPayer]!! + diff)
                } else {
                    // All participants are the payer – no debt will be created anyway
                    val firstKey = shares.keys.first()
                    shares[firstKey] = roundTo2(shares[firstKey]!! + diff)
                }
            }
            return shares
        }

        // ----- EQUAL SPLIT -----
        val participants = (tx["participants"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.distinct()
            ?: emptyList()
        if (participants.isEmpty()) return emptyMap()

        val totalPaise = (totalAmount * 100).toLong()
        val count = participants.size
        val base = totalPaise / count
        val remainder = (totalPaise % count).toInt()

        return participants.mapIndexed { idx, userId ->
            val sharePaise = if (idx < remainder) base + 1 else base
            userId to roundTo2(sharePaise / 100.0)
        }.toMap()
    }

    private fun roundTo2(value: Double): Double {
        val rounded = round(value * 100) / 100.0
        return if (abs(rounded) < 0.01) 0.0 else rounded
    }
}
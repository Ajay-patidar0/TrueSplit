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
     * OLD BUG:
     *
     * You were calculating ONLY FINAL NET BALANCES.
     *
     * Example:
     *
     * Aditya paid → Ajay owes Aditya ₹333
     * Ajay paid → Aditya owes Ajay ₹666
     *
     * Netting logic converted this into:
     * Aditya owes Ajay ₹333
     *
     * BUT User1 separately still owes BOTH people.
     *
     * Real apps like Splitwise preserve pairwise relationships.
     *
     * This implementation fixes that.
     */

    fun calculateBalances(
        members: List<Map<String, String>>,
        transactions: List<Map<String, Any>>
    ): Map<String, Double> {

        val balances = mutableMapOf<String, Double>()

        members.forEach { member ->

            val id =
                member["id"] ?: return@forEach

            balances[id] = 0.0
        }

        val settlements =
            buildPairwiseSettlements(transactions)

        settlements.forEach { settlement ->

            balances[settlement.fromUserId] =
                balances.getOrDefault(
                    settlement.fromUserId,
                    0.0
                ) - settlement.amount

            balances[settlement.toUserId] =
                balances.getOrDefault(
                    settlement.toUserId,
                    0.0
                ) + settlement.amount
        }

        return balances.mapValues { (_, value) ->

            val rounded =
                round(value * 100) / 100.0

            if (abs(rounded) < 0.01) {
                0.0
            } else {
                rounded
            }
        }
    }

    /**
     * IMPORTANT:
     *
     * We NO LONGER simplify using net balances.
     *
     * Instead we preserve REAL PERSON-TO-PERSON debts.
     *
     * Example:
     * User1 owes Aditya ₹333
     * User1 owes Ajay ₹666
     *
     * BOTH entries remain.
     */
    fun simplifyDebts(
        balances: Map<String, Double>
    ): List<SimplifiedSettlement> {

        // DO NOT USE THIS ANYMORE.
        // Returning empty intentionally.

        return emptyList()
    }

    /**
     * THIS IS THE REAL FIX.
     *
     * Build actual pairwise debts directly from transactions.
     */
    fun buildPairwiseSettlements(
        transactions: List<Map<String, Any>>
    ): List<SimplifiedSettlement> {

        val pairwiseDebts =
            mutableMapOf<String, Double>()

        transactions.forEach { tx ->

            val type =
                tx["type"] as? String ?: "expense"

            val totalAmount =
                (tx["amount"] as? Number)
                    ?.toDouble()
                    ?: return@forEach

            if (totalAmount <= 0.0) {
                return@forEach
            }

            when (type) {

                // =====================================
                // NORMAL EXPENSE
                // =====================================

                "expense" -> {

                    val paidBy =
                        tx["paidBy"] as? String
                            ?: return@forEach

                    val shares =
                        computeShares(
                            tx = tx,
                            totalAmount = totalAmount
                        )

                    shares.forEach { (userId, shareAmount) ->

                        // Skip self debt
                        if (userId == paidBy) {
                            return@forEach
                        }

                        addDebt(
                            pairwiseDebts = pairwiseDebts,
                            debtorId = userId,
                            creditorId = paidBy,
                            amount = shareAmount
                        )
                    }
                }

                // =====================================
                // SETTLEMENT PAYMENT
                // =====================================

                "settle" -> {

                    val paidBy =
                        tx["paidBy"] as? String
                            ?: return@forEach

                    val receivedBy =
                        tx["receivedBy"] as? String
                            ?: return@forEach

                    reduceDebt(
                        pairwiseDebts = pairwiseDebts,
                        debtorId = paidBy,
                        creditorId = receivedBy,
                        amount = totalAmount
                    )
                }
            }
        }

        return pairwiseDebts
            .mapNotNull { (key, amount) ->

                if (amount <= 0.01) {
                    return@mapNotNull null
                }

                val parts = key.split("_")

                if (parts.size != 2) {
                    return@mapNotNull null
                }

                SimplifiedSettlement(
                    fromUserId = parts[0],
                    toUserId = parts[1],
                    amount = round(amount * 100) / 100.0
                )
            }
    }

    private fun addDebt(
        pairwiseDebts: MutableMap<String, Double>,
        debtorId: String,
        creditorId: String,
        amount: Double
    ) {

        if (amount <= 0.0) {
            return
        }

        val directKey =
            "${debtorId}_${creditorId}"

        val reverseKey =
            "${creditorId}_${debtorId}"

        val reverseDebt =
            pairwiseDebts[reverseKey] ?: 0.0

        // OFFSET OPPOSITE DIRECTION DEBT
        if (reverseDebt > 0.0) {

            if (reverseDebt > amount) {

                pairwiseDebts[reverseKey] =
                    round((reverseDebt - amount) * 100) / 100.0

                return
            }

            pairwiseDebts.remove(reverseKey)

            val remaining =
                amount - reverseDebt

            if (remaining > 0.01) {

                pairwiseDebts[directKey] =
                    round(
                        (
                                pairwiseDebts.getOrDefault(
                                    directKey,
                                    0.0
                                ) + remaining
                                ) * 100
                    ) / 100.0
            }

            return
        }

        pairwiseDebts[directKey] =
            round(
                (
                        pairwiseDebts.getOrDefault(
                            directKey,
                            0.0
                        ) + amount
                        ) * 100
            ) / 100.0
    }

    private fun reduceDebt(
        pairwiseDebts: MutableMap<String, Double>,
        debtorId: String,
        creditorId: String,
        amount: Double
    ) {

        val key =
            "${debtorId}_${creditorId}"

        val existing =
            pairwiseDebts[key] ?: return

        val updated =
            round((existing - amount) * 100) / 100.0

        if (updated <= 0.01) {
            pairwiseDebts.remove(key)
        } else {
            pairwiseDebts[key] = updated
        }
    }

    private fun computeShares(
        tx: Map<String, Any>,
        totalAmount: Double
    ): Map<String, Double> {

        val splits =
            tx["splits"]

        // =====================================
        // CUSTOM SPLITS
        // =====================================

        if (splits is Map<*, *>) {

            val cleaned =
                mutableMapOf<String, Double>()

            splits.forEach { (key, value) ->

                val userId =
                    key as? String
                        ?: return@forEach

                val amount =
                    when (value) {

                        is Number -> value.toDouble()

                        is String -> value.toDoubleOrNull()

                        else -> null
                    }

                if (
                    amount != null &&
                    amount >= 0.0
                ) {
                    cleaned[userId] = amount
                }
            }

            if (cleaned.isNotEmpty()) {
                return cleaned
            }
        }

        // =====================================
        // splitBetween
        // =====================================

        val splitBetween =
            tx["splitBetween"]

        if (splitBetween is List<*>) {

            val users =
                splitBetween
                    .mapNotNull {
                        it as? String
                    }
                    .distinct()

            if (users.isNotEmpty()) {

                val share =
                    totalAmount / users.size

                return users.associateWith {
                    round(share * 100) / 100.0
                }
            }
        }

        // =====================================
        // splitWith
        // =====================================

        val splitWith =
            tx["splitWith"]

        if (splitWith is List<*>) {

            val users =
                splitWith.mapNotNull {

                    val map =
                        it as? Map<*, *>
                            ?: return@mapNotNull null

                    map["userId"] as? String
                }.distinct()

            if (users.isNotEmpty()) {

                val share =
                    totalAmount / users.size

                return users.associateWith {
                    round(share * 100) / 100.0
                }
            }
        }

        return emptyMap()
    }
}

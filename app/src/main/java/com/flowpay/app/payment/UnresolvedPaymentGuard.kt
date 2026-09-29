// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.payment

import com.flowpay.app.data.Transaction
import com.flowpay.app.data.TransactionStatus

/** Match the payment the user is about to repeat, not unrelated equal-amount payments. */
object UnresolvedPaymentGuard {
    fun matches(existing: Transaction, recipientUpiId: String, amount: String): Boolean {
        val unresolved = existing.status in setOf(
            TransactionStatus.PENDING,
            TransactionStatus.UNVERIFIED,
            TransactionStatus.NEEDS_REVIEW,
        )
        if (!unresolved || !existing.upiId.equals(recipientUpiId, ignoreCase = true)) return false
        val existingAmount = existing.amount.toBigDecimalOrNull()
        val requestedAmount = amount.toBigDecimalOrNull()
        return existingAmount != null && requestedAmount != null &&
            existingAmount.compareTo(requestedAmount) == 0
    }
}

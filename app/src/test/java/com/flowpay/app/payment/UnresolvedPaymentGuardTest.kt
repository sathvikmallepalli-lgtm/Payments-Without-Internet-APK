// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.payment

import com.flowpay.app.data.Transaction
import com.flowpay.app.data.TransactionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnresolvedPaymentGuardTest {
    private fun attempt(status: String, upiId: String, amount: String) = Transaction(
        transactionId = "attempt",
        amount = amount,
        status = status,
        bankName = "",
        smsExcerpt = "",
        upiId = upiId,
    )

    @Test fun blocksTheSameUnresolvedPayeeAndNumericAmount() {
        val previous = attempt(TransactionStatus.UNVERIFIED, "Seller@Bank", "80.00")
        assertTrue(UnresolvedPaymentGuard.matches(previous, "seller@bank", "80"))
    }

    @Test fun unrelatedOrResolvedPaymentsDoNotBlock() {
        val pending = attempt(TransactionStatus.PENDING, "seller@bank", "80")
        val completed = pending.copy(status = TransactionStatus.SUCCESS)
        assertFalse(UnresolvedPaymentGuard.matches(pending, "other@bank", "80"))
        assertFalse(UnresolvedPaymentGuard.matches(pending, "seller@bank", "81"))
        assertFalse(UnresolvedPaymentGuard.matches(completed, "seller@bank", "80"))
    }
}

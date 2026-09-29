// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.payment

import com.flowpay.app.data.TransactionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentVerificationPolicyTest {
    @Test
    fun `late SMS after an uncertain payment always requires review`() {
        for (smsStatus in listOf(TransactionStatus.SUCCESS, TransactionStatus.FAILED)) {
            assertEquals(
                TransactionStatus.NEEDS_REVIEW,
                PaymentVerificationPolicy.statusForEvidence(TransactionStatus.UNVERIFIED, smsStatus)
            )
        }
    }

    @Test
    fun `SMS during the active payment keeps its parsed outcome`() {
        assertEquals(
            TransactionStatus.SUCCESS,
            PaymentVerificationPolicy.statusForEvidence(TransactionStatus.PENDING, TransactionStatus.SUCCESS)
        )
        assertEquals(
            TransactionStatus.FAILED,
            PaymentVerificationPolicy.statusForEvidence(TransactionStatus.PENDING, TransactionStatus.FAILED)
        )
    }
}

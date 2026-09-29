// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.payment

import com.flowpay.app.data.TransactionStatus

/**
 * An SMS received after the user left or the deadline passed is evidence,
 * but its amount alone cannot identify which payment caused it.
 */
object PaymentVerificationPolicy {
    fun statusForEvidence(previousStatus: String, bankSmsStatus: String): String =
        if (previousStatus == TransactionStatus.UNVERIFIED) {
            TransactionStatus.NEEDS_REVIEW
        } else {
            bankSmsStatus
        }
}

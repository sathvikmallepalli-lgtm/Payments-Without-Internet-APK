// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.data

/**
 * Canonical lifecycle states for a transaction row.
 *
 * PENDING      — payment initiated, dial in progress or awaiting bank SMS
 * SUCCESS      — bank SMS confirmed the debit
 * FAILED       — bank SMS reported a failure/decline
 * CANCELLED    — dial failed or the user left before dialing
 * UNVERIFIED   — payment may have been submitted but no bank SMS has arrived;
 *                the money may or may not have moved — the user must check
 *                with their bank
 * NEEDS_REVIEW — bank SMS evidence cannot be safely linked to this attempt;
 *                the user must verify
 *                against their bank statement before trusting the outcome
 */
object TransactionStatus {
    const val PENDING = "PENDING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val UNVERIFIED = "UNVERIFIED"
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
}

/** Where a transaction record originated. */
object TransactionSource {
    const val SMS = "SMS"
    const val NOTIFICATION = "NOTIFICATION"
    const val MANUAL = "MANUAL"
    const val QR = "QR"
    const val UPI_HANDOFF = "UPI_HANDOFF"
}

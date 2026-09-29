// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.payment

import android.content.Context
import android.util.Log
import com.flowpay.app.helpers.TransactionDetector
import com.flowpay.app.states.PaymentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Closes the SMS operation window when the payment call never started.
 *
 * The window is [TransactionDetector]'s SharedPreferences-backed record that
 * a payment is in flight; while it is open, an arriving bank SMS may be
 * claimed as this payment's confirmation. Cancelling used to end the
 * *session* without closing the *window*, leaving it armed for the rest of
 * its 10.5-minute life — so a later same-amount debit (the user re-paying
 * through another app, say) was adopted onto the cancelled row and flipped
 * it to SUCCESS.
 *
 * A dial failure or cancellation before dial emits [PaymentState.Cancelled].
 * Closing a flow after the dialer launched emits VerificationPending instead;
 * its window remains open because the bank may still complete a transfer.
 * [PaymentSessionManager] stays independent from [TransactionDetector].
 *
 * Deliberately *not* triggered by [PaymentState.Timeout]: the window
 * outlives the verification deadline by 30 seconds on purpose, so a
 * slow-but-genuine confirmation still lands. Closing it at the deadline
 * would discard an SMS that did arrive. It expires on its own.
 *
 * The state→action decision is the pure, unit-tested [shouldDisarmSmsWindow];
 * only the disarm itself touches Android.
 */
class PaymentWindowObserver(
    private val appContext: Context,
    private val paymentState: StateFlow<PaymentState>,
    private val scope: CoroutineScope,
    private val onDisarm: () -> Unit = { defaultDisarm(appContext) }
) {
    fun start() {
        scope.launch {
            paymentState.collect { state ->
                if (state.shouldDisarmSmsWindow()) onDisarm()
            }
        }
    }

    companion object {
        private const val TAG = "PaymentWindowObserver"

        private fun defaultDisarm(context: Context) {
            TransactionDetector.getInstance(context).stopOperation()
            Log.d(TAG, "Pre-call payment cancelled - SMS operation window closed")
        }
    }
}

/**
 * Pure mapping: only a pre-call cancelled session closes the SMS window.
 * Success and failure consume the window in the ingestion path, and a
 * timeout leaves it to expire on its own (see the class KDoc).
 */
fun PaymentState.shouldDisarmSmsWindow(): Boolean = this is PaymentState.Cancelled

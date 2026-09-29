// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.receivers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.flowpay.app.FlowpayApplication
import com.flowpay.app.data.TransactionStatus
import com.flowpay.app.helpers.TransactionDetector
import com.flowpay.app.payment.PaymentVerificationPolicy
import com.flowpay.app.payment.sms.SimpleTransaction
import com.flowpay.app.repository.TransactionRepository
import com.flowpay.app.ui.activities.PaymentResultActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The live SMS -> transaction pipeline, shared by every ingestion entry
 * point: [SimpleSMSReceiver] (the real broadcast receiver) and, in debug
 * builds only, `DebugSmsInjectionReceiver`. Keeping this in one place means
 * the debug injection tool exercises exactly the same code a real bank SMS
 * would go through — parsing, session confirmation, persistence, and the
 * result-screen launch — not a parallel reimplementation of it.
 */
object SmsIngestionPipeline {

    private const val TAG = "SmsIngestionPipeline"

    @Suppress("ReturnCount") // Non-transaction, credit and rejected orphan SMS must exit before UI effects.
    suspend fun ingest(context: Context, detector: TransactionDetector, sender: String, body: String) {
        // Captured BEFORE processSMS: a consuming match clears the operation
        // window (including this id).
        val windowTxnId = detector.getSessionTxnId()

        val transaction = detector.processSMS(sender, body)
        if (transaction == null) {
            Log.d(TAG, "Not a transaction SMS")
            return
        }

        // An active payment session owns its row — update it in place. With
        // no live session (e.g. the process died mid-payment), try to
        // reattach the evidence to the PENDING or UNVERIFIED row recorded at
        // begin(); only insert a fresh row when neither applies. Without the
        // reattach, a payment whose process died would end up as TWO rows:
        // a fresh SUCCESS plus the original row. A confirmation arriving
        // after the deadline is attached as NEEDS_REVIEW, so the user can
        // compare it with the bank app without a duplicate row.
        val sessionManager = FlowpayApplication.from(context)?.paymentSessionManager
        val sessionClaim = sessionManager?.onSmsEvidence(transaction)
        var adoptedStatus: String? = null
        var alreadyOwnedOrRejected = false
        val recordTxnId = resolveOwnerTxnId(
            sessionTxnId = sessionClaim?.transactionId,
            windowTxnId = windowTxnId,
            parsed = transaction
        ) { orphanId ->
            val result = adoptOrphanedRow(context, orphanId, transaction)
            if (result.updated > 0) adoptedStatus = result.status
            alreadyOwnedOrRejected = result.suppressStandalone
            result.updated
        }
        // The session writes off-thread. Wait before showing a result so the
        // history row cannot still read UNVERIFIED when the screen opens.
        sessionClaim?.persisted?.await()
        val outcomeStatus = sessionClaim?.status ?: adoptedStatus ?: transaction.status

        // A window referring to an existing settled or cancelled attempt
        // must not turn another same-amount alert into a standalone SUCCESS.
        if (alreadyOwnedOrRejected) return

        // A stray incoming CREDIT that no payment claimed is an unrelated bank
        // alert (salary, refund, someone paying you) that merely arrived inside
        // the operation window — it is never a confirmation of our outgoing
        // DEBIT. Surfacing it would flash a false "Payment successful" result
        // screen for the credit's amount, post a success notification, and save
        // a phantom row, all while the real payment is still pending. Ignore it;
        // processSMS deliberately left the window open for the genuine debit.
        if (isUnrelatedIncomingCredit(recordTxnId, transaction)) {
            Log.d(TAG, "Unrelated incoming credit during payment window - ignored")
            return
        }

        if (recordTxnId == null) {
            TransactionRepository.getInstance(context).saveTransaction(transaction)
            Log.d(TAG, "Transaction saved (no session, no adoptable row)")
        }

        withContext(Dispatchers.Main) {
            // Dismiss the USSD overlay (QR flow) and notify listening screens.
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent("DISMISS_OVERLAY"))
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent("com.flowpay.app.SMS_RECEIVED"))

            val successIntent = Intent(context, PaymentResultActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("transaction_id", recordTxnId ?: transaction.transactionId)
                putExtra("amount", transaction.amount)
                putExtra("status", outcomeStatus)
                putExtra("bank_name", transaction.bankName)
                putExtra("message", transaction.smsExcerpt)
                putExtra("timestamp", transaction.timestamp)
                putExtra("upi_id", transaction.upiId)
                putExtra("transaction_type", transaction.transactionType)
                putExtra("recipient_name", transaction.recipientName)
                putExtra("phone_number", transaction.phoneNumber)
            }
            // Notification first (also serves as a receipt), then the direct
            // launch — which can be silently blocked without the overlay
            // permission; the notification is the guaranteed-reachable path.
            PaymentResultNotifier.notifyResult(context, successIntent)
            context.startActivity(successIntent)
        }
    }

    /**
     * Decides which existing row (if any) owns this confirmation:
     * - a live session claimed it -> that session's row;
     * - no live session but the operation window recorded a session txnId and
     *   this is an outgoing-debit confirmation -> adopt that row (returns the
     *   id when [adopt] actually updated a row);
     * - otherwise null -> caller inserts a standalone record.
     *
     * A CREDIT can never adopt: the recorded row is always an outgoing DEBIT.
     * Pure decision logic — unit-tested with a fake [adopt].
     */
    internal suspend fun resolveOwnerTxnId(
        sessionTxnId: String?,
        windowTxnId: String?,
        parsed: SimpleTransaction,
        adopt: suspend (String) -> Int
    ): String? = when {
        sessionTxnId != null -> sessionTxnId
        windowTxnId == null || parsed.transactionType == "CREDIT" -> null
        else -> {
            val updated = runCatching { adopt(windowTxnId) }
                .onFailure { Log.e(TAG, "Adopting orphaned row failed", it) }
                .getOrDefault(0)
            if (updated > 0) {
                Log.d(TAG, "Confirmation reattached to orphaned session row")
                windowTxnId
            } else {
                null
            }
        }
    }

    /**
     * True when this ingestion is an incoming CREDIT that no payment claimed —
     * an unrelated bank alert that only coincided with the operation window and
     * must not surface a result screen or be persisted as a payment.
     *
     * A CREDIT can never legitimately own a row here: [PaymentSessionManager]'s
     * onSmsConfirmed rejects a CREDIT (a session is always an outgoing DEBIT)
     * and [resolveOwnerTxnId] refuses to adopt one, so [recordTxnId] is always
     * null for a CREDIT. The null check is kept explicit so that if a future
     * owning path ever does claim one, it is surfaced rather than silently
     * dropped. Pure — unit-tested.
     */
    internal fun isUnrelatedIncomingCredit(recordTxnId: String?, parsed: SimpleTransaction): Boolean =
        recordTxnId == null && parsed.transactionType == "CREDIT"

    /** Fills bank-confirmed details into the orphaned session row. */
    private data class AdoptionResult(
        val updated: Int,
        val status: String,
        val suppressStandalone: Boolean
    )

    @Suppress("ReturnCount") // Missing and settled rows must be handled before the guarded update.
    private suspend fun adoptOrphanedRow(
        context: Context,
        txnId: String,
        parsed: SimpleTransaction
    ): AdoptionResult {
        val repository = TransactionRepository.getInstance(context)
        val row = repository.getTransactionById(txnId)
            ?: return AdoptionResult(0, parsed.status, suppressStandalone = false)
        val expectedStatus = row.status
        if (expectedStatus != TransactionStatus.PENDING && expectedStatus != TransactionStatus.UNVERIFIED) {
            return AdoptionResult(0, parsed.status, suppressStandalone = true)
        }
        val safeStatus = PaymentVerificationPolicy.statusForEvidence(expectedStatus, parsed.status)
        val updated = repository.confirmTransaction(
            transactionId = txnId,
            status = safeStatus,
            parsed = parsed,
            verifiedAt = System.currentTimeMillis(),
            expectedStatus = expectedStatus
        )
        if (updated > 0) return AdoptionResult(updated, safeStatus, suppressStandalone = false)

        // Startup reconciliation can change PENDING -> UNVERIFIED between
        // the read and guarded update. Retry against the fresh row as review.
        val fresh = repository.getTransactionById(txnId)
        if (expectedStatus == TransactionStatus.PENDING && fresh?.status == TransactionStatus.UNVERIFIED) {
            val review = repository.confirmTransaction(
                transactionId = txnId,
                status = TransactionStatus.NEEDS_REVIEW,
                parsed = parsed,
                verifiedAt = System.currentTimeMillis(),
                expectedStatus = TransactionStatus.UNVERIFIED
            )
            if (review > 0) {
                return AdoptionResult(review, TransactionStatus.NEEDS_REVIEW, suppressStandalone = false)
            }
        }
        return AdoptionResult(0, parsed.status, suppressStandalone = fresh != null)
    }
}

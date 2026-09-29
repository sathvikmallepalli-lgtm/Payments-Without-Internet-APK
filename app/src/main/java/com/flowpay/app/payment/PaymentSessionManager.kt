// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.payment

import android.util.Log
import com.flowpay.app.data.Transaction
import com.flowpay.app.data.TransactionSource
import com.flowpay.app.data.TransactionStatus
import com.flowpay.app.payment.sms.SimpleTransaction
import com.flowpay.app.states.PaymentState
import com.flowpay.app.states.TimeoutType
import com.flowpay.app.telephony.CallSessionEvent
import com.flowpay.app.telephony.CallStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Minimal persistence surface the session manager needs. Implemented by
 * TransactionRepository; faked in unit tests.
 */
interface PaymentTransactionStore {
    suspend fun insertPending(transaction: Transaction)

    /** Atomically move a row from [expectedStatus] to [newStatus]. Returns rows changed. */
    suspend fun transitionStatus(transactionId: String, expectedStatus: String, newStatus: String): Int

    /**
     * Fill in bank-confirmed details from [parsed] on the session row,
     * recording it as [status] at [verifiedAt]. Returns rows changed.
     */
    suspend fun confirmTransaction(
        transactionId: String,
        status: String,
        parsed: SimpleTransaction,
        verifiedAt: Long,
        expectedStatus: String = TransactionStatus.PENDING
    ): Int

    /** Moves stale PENDING rows to UNVERIFIED. Returns rows changed. */
    suspend fun deleteStalePending(now: Long): Int

    /** Legacy guarded delete for an attempt that never reached a call. */
    suspend fun deletePending(transactionId: String): Int
}

/**
 * The only writer of payment lifecycle state.
 *
 * A manual UPI 123Pay transfer flows through here:
 *  1. [begin] inserts a PENDING row *before* anything is dialled, so the
 *     database always knows a payment was attempted — even if the process
 *     dies mid-call.
 *  2. Call activity (from [CallStateCoordinator]) moves the in-memory
 *     [PaymentState]: OFFHOOK -> InProgress, short hang-up -> Cancelled,
 *     normal call end -> WaitingForVerification.
 *  3. A confirming bank SMS ([onSmsConfirmed]) is the only path to SUCCESS.
 *     Call duration is never treated as proof of payment.
 *  4. If no SMS arrives, the attempt stays UNVERIFIED so a debit that the bank
 *     never texted about can still be found and checked in the bank app.
 *
 * Stale PENDING rows from a killed process become UNVERIFIED lazily via
 * [reconcileStalePending] (app start, history open, next begin()).
 */
class PaymentSessionManager(
    private val store: PaymentTransactionStore,
    private val coordinator: CallStateSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val verificationDeadlineMs: Long = DEFAULT_VERIFICATION_DEADLINE_MS,
    private val minRealCallDurationMs: Long = DEFAULT_MIN_REAL_CALL_DURATION_MS
) {

    companion object {
        private const val TAG = "PaymentSessionManager"
        const val DEFAULT_VERIFICATION_DEADLINE_MS = 10 * 60 * 1000L // 10 minutes
        const val DEFAULT_MIN_REAL_CALL_DURATION_MS = 5_000L
        private const val COORDINATOR_TAG = "payment-session"
    }

    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentState: StateFlow<PaymentState> = _paymentState.asStateFlow()

    private val sessionLock = Any()

    // @Volatile, not plain vars: begin() publishes these AFTER releasing
    // sessionLock, while the SMS-ingestion thread reads them under it (or,
    // for pendingInsertJob, from a coroutine on Dispatchers.Default). Without
    // a happens-before edge a confirming SMS could observe pendingInsertJob
    // as null, skip the join(), and run confirmTransaction against a row
    // insertPending had not written yet — updating 0 rows and losing the
    // payment from history.
    @Volatile private var collectJob: Job? = null

    @Volatile private var watchdogJob: Job? = null

    @Volatile private var pendingInsertJob: Job? = null

    @Volatile private var verificationUpdateJob: Job? = null
    private var coordinatorAcquired = false

    /**
     * Starts a new payment session and records a PENDING row. Always succeeds.
     *
     * A new payment SUPERSEDES any session still in flight. This used to be
     * refused with "a payment is already in progress", which stranded the user:
     * a session the bank never confirmed stayed in progress for its full
     * verification deadline, and every attempt to pay in the meantime was
     * blocked — including a retry of the very payment that had failed.
     *
     * Superseding leaves the previous attempt UNVERIFIED for bank checking.
     *
     * [upiId] and [source] let the QR flow record what it actually knows (a
     * payee VPA, provenance QR) — the QR flow used to bypass the session
     * entirely, so an unconfirmed QR payment left no trace at all.
     */
    @Suppress("LongMethod") // The initial state, durable row and deadline form one atomic lifecycle boundary.
    fun begin(
        phoneNumber: String,
        amount: String,
        upiId: String? = null,
        source: String = TransactionSource.MANUAL
    ): String {
        val initiating: PaymentState.Initiating
        val supersededTxnId: String?
        synchronized(sessionLock) {
            val previous = _paymentState.value
            supersededTxnId = if (previous.isInProgress()) {
                Log.w(TAG, "begin() supersedes the session still in flight")
                cleanupLocked()
                previous.getTransactionIdValue()
            } else {
                null
            }
            initiating = PaymentState.Initiating(phoneNumber, amount)
            _paymentState.value = initiating
            if (!coordinatorAcquired) {
                coordinator.acquire(COORDINATOR_TAG)
                coordinatorAcquired = true
            }
        }

        val txnId = initiating.transactionId
        val now = clock()
        val deadlineAt = now + verificationDeadlineMs

        // Keep the superseded attempt visible. The status guard protects a
        // confirmation that landed just before the new payment began.
        if (supersededTxnId != null) {
            val previousInsert = pendingInsertJob
            val previousUpdate = verificationUpdateJob
            scope.launch {
                previousInsert?.join()
                previousUpdate?.join()
                store.transitionStatus(supersededTxnId, TransactionStatus.PENDING, TransactionStatus.UNVERIFIED)
            }
        }

        pendingInsertJob = scope.launch {
            // Older attempts remain available for a bank statement check.
            runCatching { store.deleteStalePending(now) }
            store.insertPending(
                Transaction(
                    transactionId = txnId,
                    amount = amount,
                    status = TransactionStatus.PENDING,
                    bankName = "",
                    smsExcerpt = "",
                    timestamp = now,
                    transactionType = "DEBIT",
                    phoneNumber = phoneNumber,
                    upiId = upiId,
                    deadlineAt = deadlineAt,
                    source = source
                )
            )
            Log.d(TAG, "PENDING row recorded for session")
        }

        collectJob = scope.launch {
            coordinator.sessionEvents.collect { event -> onCallSessionEvent(event) }
        }

        watchdogJob = scope.launch {
            delay(verificationDeadlineMs)
            onVerificationDeadline()
        }

        Log.d(TAG, "Payment session started")
        return txnId
    }

    /** Gate the dialer on a durable attempt row, without blocking the UI thread. */
    @Suppress("ReturnCount") // A missing, superseded or failed insert must never dial.
    suspend fun awaitPendingInsert(transactionId: String): Boolean {
        val insert = synchronized(sessionLock) {
            val state = _paymentState.value
            if (!state.isInProgress() || state.getTransactionIdValue() != transactionId) return false
            pendingInsertJob
        } ?: return false
        insert.join()
        return !insert.isCancelled && synchronized(sessionLock) {
            val state = _paymentState.value
            state.isInProgress() && state.getTransactionIdValue() == transactionId
        }
    }

    /** The dial intent could not be launched; the session never really started. */
    fun onDialFailed(reason: String) {
        finishSession(TransactionStatus.CANCELLED) { phone, amount, txnId ->
            PaymentState.Cancelled(phone, amount, txnId, reason)
        }
    }

    /** Stop the call/UI; an already-started payment remains uncertain. */
    @Suppress("ReturnCount") // The no-op and pre-dial cancel guards exit before any bank-verification state.
    fun onUserCancelled(paymentMayHaveBeenSubmitted: Boolean = false) {
        val txnId: String
        val updateJob: Job
        synchronized(sessionLock) {
            val state = _paymentState.value
            if (!state.isInProgress() || state is PaymentState.VerificationPending) return
            if (state is PaymentState.Initiating && !paymentMayHaveBeenSubmitted) {
                finishSession(TransactionStatus.CANCELLED) { phone, amount, id ->
                    PaymentState.Cancelled(phone, amount, id, "Cancelled before call")
                }
                return
            }
            txnId = state.getTransactionIdValue() ?: return
            val previousInsert = pendingInsertJob
            _paymentState.value = PaymentState.VerificationPending(
                state.getPhoneNumberValue() ?: "", state.getAmountValue() ?: "", txnId
            )
            collectJob?.cancel()
            collectJob = null
            if (coordinatorAcquired) {
                coordinator.release(COORDINATOR_TAG)
                coordinatorAcquired = false
            }
            updateJob = scope.launch(start = CoroutineStart.LAZY) {
                previousInsert?.join()
                store.transitionStatus(txnId, TransactionStatus.PENDING, TransactionStatus.UNVERIFIED)
            }
            verificationUpdateJob = updateJob
        }
        updateJob.start()
    }

    /**
     * The dial intent launched but Android never reported OFFHOOK. The bank
     * flow may still have completed, so keep the attempt for verification.
     */
    fun onCallNeverStarted() {
        synchronized(sessionLock) {
            if (_paymentState.value !is PaymentState.Initiating) return
        }
        onUserCancelled(paymentMayHaveBeenSubmitted = true)
    }

    /**
     * A parsed bank SMS arrived. If a session is active, its row is updated
     * in place and the session transaction id is returned; callers must NOT
     * insert a separate row in that case. Returns null when no session was
     * active (e.g. the QR flow), letting callers fall back to their own
     * persistence.
     */
    data class SmsClaim(val transactionId: String, val status: String, val persisted: Deferred<Int>)

    fun onSmsConfirmed(parsed: SimpleTransaction): String? = onSmsEvidence(parsed)?.transactionId

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    fun onSmsEvidence(parsed: SimpleTransaction): SmsClaim? {
        // Direction check: a manual session is always an outgoing DEBIT
        // (begin() inserts transactionType = "DEBIT"), so an incoming CREDIT
        // SMS can never confirm it. Returning null lets the caller fall back
        // to standalone persistence, exactly like the QR/no-session flow.
        if (parsed.transactionType == "CREDIT") {
            Log.d(TAG, "CREDIT SMS during DEBIT session - not a confirmation")
            return null
        }

        val parsedStatus = when {
            parsed.status.equals(TransactionStatus.FAILED, ignoreCase = true) ->
                TransactionStatus.FAILED
            parsed.status.equals(TransactionStatus.NEEDS_REVIEW, ignoreCase = true) ->
                TransactionStatus.NEEDS_REVIEW
            else -> TransactionStatus.SUCCESS
        }
        val verifiedAt = clock()

        // Check-and-claim must be one atomic step: with the check in one
        // lock and the terminal transition in a second, two near-simultaneous
        // confirming SMS (both pipelines, or a debug injection racing a real
        // message) could BOTH observe InProgress and both write a terminal
        // outcome, last-writer-wins. Claiming inside a single lock guarantees
        // exactly one caller ever owns the confirmation.
        val txnId: String
        val expectedStatus: String
        val previousInsert: Job?
        val previousUpdate: Job?
        val newStatus: String
        synchronized(sessionLock) {
            val current = _paymentState.value
            if (!current.isInProgress()) return null
            txnId = current.getTransactionIdValue() ?: return null
            expectedStatus = if (current is PaymentState.VerificationPending) {
                TransactionStatus.UNVERIFIED
            } else {
                TransactionStatus.PENDING
            }
            newStatus = PaymentVerificationPolicy.statusForEvidence(expectedStatus, parsedStatus)
            previousInsert = pendingInsertJob
            previousUpdate = verificationUpdateJob
            val phone = current.getPhoneNumberValue() ?: parsed.phoneNumber ?: ""
            val amount = current.getAmountValue() ?: parsed.amount

            _paymentState.value = when (newStatus) {
                TransactionStatus.FAILED -> PaymentState.Failed(
                    error = "Bank reported the payment as failed",
                    phoneNumber = phone,
                    amount = amount,
                    transactionId = txnId,
                    canRetry = true
                )
                TransactionStatus.NEEDS_REVIEW -> PaymentState.NeedsReview(
                    transactionId = txnId,
                    phoneNumber = phone,
                    amount = amount
                )
                else -> PaymentState.Success(
                    transactionId = txnId,
                    phoneNumber = phone,
                    amount = amount,
                    bankReference = parsed.transactionId,
                    timestamp = verifiedAt
                )
            }
            cleanupLocked()
        }

        val persisted = scope.async {
            previousInsert?.join()
            previousUpdate?.join()
            val updated = store.confirmTransaction(
                transactionId = txnId,
                status = newStatus,
                parsed = parsed,
                verifiedAt = verifiedAt,
                expectedStatus = expectedStatus
            )
            Log.d(TAG, "Session row confirmed as $newStatus (rows=$updated)")
            updated
        }
        return SmsClaim(txnId, newStatus, persisted)
    }

    /** UI acknowledges a terminal result and returns the session to Idle. */
    fun acknowledgeResult() {
        synchronized(sessionLock) {
            val state = _paymentState.value
            if (state.isTerminal() || state is PaymentState.Timeout) {
                _paymentState.value = PaymentState.Idle
            }
        }
    }

    /** Keep expired attempts visible for bank verification after process death. */
    fun reconcileStalePending() {
        scope.launch {
            val changed = runCatching { store.deleteStalePending(clock()) }.getOrDefault(0)
            if (changed > 0) {
                Log.d(TAG, "Marked $changed stale attempt(s) UNVERIFIED")
            }
        }
    }

    private fun onCallSessionEvent(event: CallSessionEvent) {
        when (event) {
            is CallSessionEvent.Started -> {
                synchronized(sessionLock) {
                    val state = _paymentState.value
                    if (state is PaymentState.Initiating) {
                        _paymentState.value = PaymentState.InProgress(
                            step = "On the line with the UPI service",
                            progress = 0.4f,
                            phoneNumber = state.phoneNumber,
                            amount = state.amount,
                            transactionId = state.transactionId
                        )
                    }
                }
            }
            is CallSessionEvent.Ended -> {
                val state = synchronized(sessionLock) { _paymentState.value }
                when {
                    state is PaymentState.InProgress && event.durationMs < minRealCallDurationMs -> {
                        onUserCancelled()
                    }
                    state is PaymentState.InProgress -> {
                        synchronized(sessionLock) {
                            // Re-check: an SMS may have confirmed while we were deciding.
                            val s = _paymentState.value
                            if (s is PaymentState.InProgress) {
                                _paymentState.value = PaymentState.WaitingForVerification(
                                    timeout = verificationDeadlineMs,
                                    phoneNumber = s.phoneNumber,
                                    amount = s.amount,
                                    transactionId = s.transactionId
                                )
                            }
                        }
                    }
                    else -> { /* Initiating without OFFHOOK, or already terminal - nothing to do */ }
                }
            }
        }
    }

    /**
     * Keep the attempt visible when no bank SMS arrives. A missing SMS does
     * not prove that the payment failed, as some banks do not send one.
     */
    private fun onVerificationDeadline() {
        // Claim FIRST, write second. Queueing the delete before the claim let a
        // confirming SMS win the lock (showing "Payment successful") while the
        // already-queued deletePending still ran, removing the very row that
        // confirmation had just landed on — success on screen, nothing in
        // history. Claiming inside the lock means only the winner writes.
        val txnId: String
        val priorStatus: String
        val previousInsert: Job?
        val previousUpdate: Job?
        synchronized(sessionLock) {
            val s = _paymentState.value
            if (!s.isInProgress()) return
            txnId = s.getTransactionIdValue() ?: return
            priorStatus = if (s is PaymentState.VerificationPending) {
                TransactionStatus.UNVERIFIED
            } else {
                TransactionStatus.PENDING
            }
            previousInsert = pendingInsertJob
            previousUpdate = verificationUpdateJob
            _paymentState.value = PaymentState.Timeout(
                timeoutType = TimeoutType.BANK_VERIFICATION,
                phoneNumber = s.getPhoneNumberValue() ?: "",
                amount = s.getAmountValue() ?: "",
                transactionId = txnId
            )
            cleanupLocked()
        }

        scope.launch {
            previousInsert?.join()
            previousUpdate?.join()
            if (priorStatus == TransactionStatus.PENDING) {
                store.transitionStatus(txnId, TransactionStatus.PENDING, TransactionStatus.UNVERIFIED)
            }
        }
        Log.w(TAG, "No bank SMS before deadline - attempt needs bank verification")
    }

    private fun finishSession(
        rowStatus: String,
        terminalState: (phone: String, amount: String, txnId: String) -> PaymentState
    ) {
        // Claim FIRST, write second — same invariant as onSmsConfirmed. When
        // the write was queued before the claim, a cancel landing at the same
        // instant as the bank's SMS could show "Payment successful" while the
        // already-queued PENDING->CANCELLED transition still ran, leaving
        // history contradicting the screen the user was looking at. Only the
        // caller that actually claims the terminal state may write.
        val txnId = synchronized(sessionLock) {
            val s = _paymentState.value
            if (!s.isInProgress()) return
            val id = s.getTransactionIdValue() ?: return
            _paymentState.value = terminalState(
                s.getPhoneNumberValue() ?: "",
                s.getAmountValue() ?: "",
                id
            )
            cleanupLocked()
            id
        }

        scope.launch {
            pendingInsertJob?.join()
            store.transitionStatus(txnId, TransactionStatus.PENDING, rowStatus)
        }
    }

    private fun cleanupLocked() {
        collectJob?.cancel()
        collectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        if (coordinatorAcquired) {
            coordinator.release(COORDINATOR_TAG)
            coordinatorAcquired = false
        }
    }
}

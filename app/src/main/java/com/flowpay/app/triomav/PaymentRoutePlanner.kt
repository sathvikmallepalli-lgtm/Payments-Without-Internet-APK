// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

/** The address the user actually reviewed, not an inferred account. */
enum class RecipientType { UPI_ID, PHONE_NUMBER }

enum class PaymentRoute {
    ONLINE_APP,
    FLOWPAY_USSD,
    FLOWPAY_IVR,
    UNAVAILABLE,
    RESOLVE_PREVIOUS,
}

data class PaymentRouteInputs(
    val recipientType: RecipientType,
    val internetValidated: Boolean,
    val phoneLineEligible: Boolean,
    val ussdTested: Boolean,
    val ivrTested: Boolean,
    val previousAttemptUnresolved: Boolean,
    val requiresQrReference: Boolean = false,
    val qrRequiresDirectScan: Boolean = false,
)

/**
 * Decide before any payment instruction is submitted. The caller must still
 * confirm the recipient and amount, check the provider, and ask the user to
 * authorize the chosen route. Connectivity never proves settlement.
 */
object PaymentRoutePlanner {
    fun choose(input: PaymentRouteInputs): PaymentRoute {
        if (input.previousAttemptUnresolved) return PaymentRoute.RESOLVE_PREVIOUS
        return when (input.recipientType) {
            RecipientType.UPI_ID -> when {
                input.internetValidated && !input.qrRequiresDirectScan -> PaymentRoute.ONLINE_APP
                input.phoneLineEligible && input.ussdTested && !input.requiresQrReference ->
                    PaymentRoute.FLOWPAY_USSD
                else -> PaymentRoute.UNAVAILABLE
            }
            RecipientType.PHONE_NUMBER -> when {
                input.phoneLineEligible && input.ivrTested -> PaymentRoute.FLOWPAY_IVR
                else -> PaymentRoute.UNAVAILABLE
            }
        }
    }

    /** Recheck immediately before starting a route; the connection may have changed on review. */
    fun canStart(selected: PaymentRoute, input: PaymentRouteInputs): Boolean {
        if (input.previousAttemptUnresolved) return false
        return when (selected) {
            PaymentRoute.ONLINE_APP ->
                input.recipientType == RecipientType.UPI_ID &&
                    input.internetValidated && !input.qrRequiresDirectScan
            PaymentRoute.FLOWPAY_USSD ->
                input.recipientType == RecipientType.UPI_ID &&
                    input.phoneLineEligible && input.ussdTested && !input.requiresQrReference
            PaymentRoute.FLOWPAY_IVR ->
                input.recipientType == RecipientType.PHONE_NUMBER &&
                    input.phoneLineEligible && input.ivrTested
            else -> false
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentRoutePlannerTest {
    @Suppress("LongParameterList")
    private fun inputs(
        online: Boolean = false,
        service: Boolean = false,
        ussd: Boolean = false,
        ivr: Boolean = false,
        unresolved: Boolean = false,
        type: RecipientType = RecipientType.UPI_ID,
    ) = PaymentRouteInputs(type, online, service, ussd, ivr, unresolved)

    @Test fun validatedInternetUsesOnlineUpiApp() {
        assertEquals(PaymentRoute.ONLINE_APP, PaymentRoutePlanner.choose(inputs(online = true)))
    }

    @Test fun phoneRouteRequiresServiceAndCompletedTest() {
        assertEquals(PaymentRoute.UNAVAILABLE, PaymentRoutePlanner.choose(inputs(service = true)))
        assertEquals(PaymentRoute.UNAVAILABLE, PaymentRoutePlanner.choose(inputs(ussd = true)))
        assertEquals(
            PaymentRoute.FLOWPAY_USSD,
            PaymentRoutePlanner.choose(inputs(service = true, ussd = true)),
        )
    }

    @Test fun phoneNumberNeverUsesUpiIdOrUssdRoute() {
        assertEquals(
            PaymentRoute.UNAVAILABLE,
            PaymentRoutePlanner.choose(
                inputs(online = true, service = true, ussd = true, type = RecipientType.PHONE_NUMBER),
            ),
        )
        assertEquals(
            PaymentRoute.FLOWPAY_IVR,
            PaymentRoutePlanner.choose(
                inputs(service = true, ivr = true, type = RecipientType.PHONE_NUMBER),
            ),
        )
    }

    @Test fun unresolvedAttemptBlocksEveryRoute() {
        assertEquals(
            PaymentRoute.RESOLVE_PREVIOUS,
            PaymentRoutePlanner.choose(
                inputs(online = true, service = true, ussd = true, unresolved = true),
            ),
        )
    }

    @Test fun freshRouteCheckRejectsLostConnectivityAndUntestedPhoneRoute() {
        assertEquals(
            false,
            PaymentRoutePlanner.canStart(PaymentRoute.ONLINE_APP, inputs()),
        )
        assertEquals(
            false,
            PaymentRoutePlanner.canStart(PaymentRoute.FLOWPAY_USSD, inputs(service = true)),
        )
        assertEquals(
            true,
            PaymentRoutePlanner.canStart(
                PaymentRoute.FLOWPAY_USSD,
                inputs(service = true, ussd = true),
            ),
        )
    }

    @Test fun merchantReferenceQrCannotFallBackToUssd() {
        val qr = inputs(service = true, ussd = true).copy(requiresQrReference = true)
        assertEquals(PaymentRoute.UNAVAILABLE, PaymentRoutePlanner.choose(qr))
        assertEquals(false, PaymentRoutePlanner.canStart(PaymentRoute.FLOWPAY_USSD, qr))
        assertEquals(PaymentRoute.ONLINE_APP, PaymentRoutePlanner.choose(qr.copy(internetValidated = true)))
    }

    @Test fun incompleteSignedQrNeedsDirectScanEvenWithInternet() {
        val qr = inputs(online = true, service = true, ussd = true).copy(
            requiresQrReference = true,
            qrRequiresDirectScan = true,
        )
        assertEquals(PaymentRoute.UNAVAILABLE, PaymentRoutePlanner.choose(qr))
        assertEquals(false, PaymentRoutePlanner.canStart(PaymentRoute.ONLINE_APP, qr))
        assertEquals(false, PaymentRoutePlanner.canStart(PaymentRoute.FLOWPAY_USSD, qr))
    }
}

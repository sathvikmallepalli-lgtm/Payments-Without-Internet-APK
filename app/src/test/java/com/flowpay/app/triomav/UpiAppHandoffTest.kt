// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

import com.flowpay.app.data.TransactionSource
import com.flowpay.app.data.TransactionStatus
import com.flowpay.app.data.UPIData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpiAppHandoffTest {
    @Test fun manualPaymentNeedsAnAddressAndExactDecimalAmount() {
        val valid = UPIData("seller@bank", "Store", "0.01", "", "INR")
        assertEquals("seller@bank", UpiAppHandoff.uri(valid).getQueryParameter("pa"))
        listOf(
            valid.copy(vpa = "upi://pay?pa=seller@bank"),
            valid.copy(amount = "1e3"),
            valid.copy(amount = "100000.01"),
            valid.copy(payeeName = ""),
        ).forEach { data ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                UpiAppHandoff.uri(data)
            }
        }
    }

    @Test fun signedQrPayloadIsPreservedExactlyWithoutAddingDefaultFields() {
        val raw = "upi://pay?pa=seller@bank&pn=Store&am=80.00&tr=order-42&sign=a%2Bb%3D"
        val data = UPIData("seller@bank", "Store", "80", "", "INR", raw)
        assertEquals(raw, UpiAppHandoff.uri(data).toString())
        assertEquals(false, UpiAppHandoff.requiresDirectScan(data))
    }

    @Test fun incompleteSignedQrCannotBeModifiedForAReviewedAmount() {
        val raw = "upi://pay?pa=seller@bank&pn=Store&sign=a%2Bb%3D"
        val data = UPIData("seller@bank", "Store", "80", "", "INR", raw)
        assertEquals(true, UpiAppHandoff.requiresDirectScan(data))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UpiAppHandoff.uri(data)
        }
    }

    @Test fun merchantQrKeepsReferenceAndAddsEnteredAmount() {
        val data = UPIData(
            vpa = "seller@bank",
            payeeName = "Store",
            amount = "80.50",
            transactionNote = "Order 42",
            currency = "INR",
            originalQrUri = "upi://pay?pa=seller@bank&pn=Store&tr=order-42&tn=Order%2042",
        )
        val uri = UpiAppHandoff.uri(data)
        assertEquals("order-42", uri.getQueryParameter("tr"))
        assertEquals("Order 42", uri.getQueryParameter("tn"))
        assertEquals("80.50", uri.getQueryParameter("am"))
        assertEquals("INR", uri.getQueryParameter("cu"))
        assertEquals(true, UpiAppHandoff.requiresOnlineRoute(data))
    }

    @Test fun fixedQrAmountCannotBeReplaced() {
        val data = UPIData(
            vpa = "seller@bank",
            payeeName = "Store",
            amount = "800",
            transactionNote = "",
            currency = "INR",
            originalQrUri = "upi://pay?pa=seller@bank&pn=Store&am=80",
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UpiAppHandoff.uri(data)
        }
    }

    @Test fun handoffRetainsReviewedDetailsWithoutClaimingSuccess() {
        val data = UPIData("seller@bank", "A & B Store", "80.50", "", "INR")
        val attempt = UpiAppHandoff.attempt(data)
        val uri = UpiAppHandoff.uri(data)

        assertEquals(TransactionStatus.UNVERIFIED, attempt.status)
        assertEquals(TransactionSource.UPI_HANDOFF, attempt.source)
        assertNull(attempt.bankRef)
        assertNull(attempt.verifiedAt)
        assertEquals("seller@bank", attempt.upiId)
        assertEquals("80.50", attempt.amount)
        assertEquals("upi", uri.scheme)
        assertEquals("pay", uri.authority)
        assertEquals("seller@bank", uri.getQueryParameter("pa"))
        assertEquals("A & B Store", uri.getQueryParameter("pn"))
        assertEquals("80.50", uri.getQueryParameter("am"))
        assertEquals("INR", uri.getQueryParameter("cu"))
    }
}

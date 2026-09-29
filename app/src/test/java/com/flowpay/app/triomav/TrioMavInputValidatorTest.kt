// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrioMavInputValidatorTest {
    @Test fun accountFieldsRejectPaymentLinksAndOtherQrContents() {
        assertTrue(TrioMavInputValidator.isValidUpiId(" seller@bank "))
        assertFalse(TrioMavInputValidator.isValidUpiId("upi://pay?pa=seller@bank&am=80"))
        assertFalse(TrioMavInputValidator.isValidUpiId("https://example.com/seller@bank"))
        assertFalse(TrioMavInputValidator.isValidUpiId("seller @bank"))
    }

    @Test fun amountPreservesPaiseAndNormalizesDecimalZeros() {
        assertEquals("80.5", TrioMavInputValidator.amount(" 080.50 "))
        assertEquals("0.01", TrioMavInputValidator.amount("0.01"))
        assertEquals("100000", TrioMavInputValidator.amount("100000.00"))
    }

    @Test fun amountRejectsUnsupportedFormsAndOutOfRangeValues() {
        listOf("", "0", "-80", "+80", "1e3", "10.005", "100000.01", "₹80", "1,000").forEach {
            assertNull("Unexpected amount accepted: $it", TrioMavInputValidator.amount(it))
        }
    }

    @Test fun recipientNameSupportsIndianScriptsButCannotBeBlankOrMultiline() {
        assertFalse(TrioMavInputValidator.isValidName(""))
        assertTrue(TrioMavInputValidator.isValidName("அம்மா Store"))
        assertFalse(TrioMavInputValidator.isValidName("Store\nAnother recipient"))
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

import com.flowpay.app.features.qr_scanner.domain.QRCodeParser
import java.math.BigDecimal

internal object TrioMavInputValidator {
    private const val MAX_NAME_LENGTH = 99
    private val AMOUNT_PATTERN = Regex("^[0-9]+(\\.[0-9]{1,2})?$")
    private val MAX_AMOUNT = BigDecimal("100000")

    fun isValidUpiId(value: String): Boolean = QRCodeParser.isBareVpa(value)

    fun isValidName(value: String): Boolean {
        val name = value.trim()
        return name.isNotBlank() && name.length <= MAX_NAME_LENGTH &&
            name.none { it.isISOControl() }
    }

    /** Decimal rupees only; no exponent, rounding, sign, or silently dropped paise. */
    fun amount(value: String): String? {
        val raw = value.trim()
        val amount = raw.takeIf { AMOUNT_PATTERN.matches(it) }?.toBigDecimalOrNull()
        return if (amount != null && amount > BigDecimal.ZERO && amount <= MAX_AMOUNT) {
            amount.stripTrailingZeros().toPlainString()
        } else {
            null
        }
    }
}

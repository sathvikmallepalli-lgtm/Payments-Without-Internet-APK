// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

import android.net.Uri
import com.flowpay.app.data.Transaction
import com.flowpay.app.data.TransactionSource
import com.flowpay.app.data.TransactionStatus
import com.flowpay.app.data.UPIData
import com.flowpay.app.features.qr_scanner.domain.QRCodeParser
import java.math.BigDecimal
import java.util.UUID

/** A handoff starts an attempt, never a confirmed payment. */
internal object UpiAppHandoff {
    fun attempt(data: UPIData): Transaction = Transaction(
        transactionId = UUID.randomUUID().toString(),
        amount = data.amount,
        status = TransactionStatus.UNVERIFIED,
        bankName = "UPI app",
        smsExcerpt = "",
        upiId = data.vpa,
        recipientName = data.payeeName,
        source = TransactionSource.UPI_HANDOFF,
    )

    fun uri(data: UPIData): Uri {
        require(TrioMavInputValidator.isValidUpiId(data.vpa))
        require(TrioMavInputValidator.amount(data.amount) != null)
        require(TrioMavInputValidator.isValidName(data.payeeName))
        val original = data.originalQrUri
        return if (original == null) {
            Uri.Builder()
                .scheme("upi")
                .authority("pay")
                .appendQueryParameter("pa", data.vpa)
                .appendQueryParameter("pn", data.payeeName)
                .appendQueryParameter("am", data.amount)
                .appendQueryParameter("cu", "INR")
                .build()
        } else {
            validatedOriginalUri(data, original)
        }
    }

    private fun validatedOriginalUri(data: UPIData, original: String): Uri {
        val parsed = QRCodeParser.parse(original) as? QRCodeParser.ParseResult.Valid
            ?: error("QR payload changed after review")
        require(parsed.data.vpa.equals(data.vpa, ignoreCase = true))
        val fixedAmount = parsed.data.amount.toBigDecimalOrNull()
        require(fixedAmount == null || fixedAmount.compareTo(BigDecimal(data.amount)) == 0)
        require(parsed.data.payeeName.isBlank() || parsed.data.payeeName == data.payeeName)

        val qrUri = Uri.parse(original)
        if (!qrUri.getQueryParameter("sign").isNullOrBlank()) {
            // The signature covers the original query. Appending even a default currency changes it.
            require(fixedAmount != null && parsed.data.payeeName.isNotBlank())
            return qrUri
        }
        return qrUri.buildUpon().apply {
            if (parsed.data.amount.isBlank()) appendQueryParameter("am", data.amount)
            if (parsed.data.payeeName.isBlank()) appendQueryParameter("pn", data.payeeName)
            if (qrUri.getQueryParameter("cu").isNullOrBlank()) appendQueryParameter("cu", "INR")
        }.build()
    }

    /** Phone USSD cannot carry an order-specific merchant reference or QR signature. */
    fun requiresOnlineRoute(data: UPIData): Boolean = QRCodeParser.requiresOnlineRoute(data)

    /** The generic handoff cannot inject reviewed fields into an incomplete signed request. */
    fun requiresDirectScan(data: UPIData): Boolean {
        val raw = data.originalQrUri ?: return false
        val uri = Uri.parse(raw)
        return if (uri.getQueryParameter("sign").isNullOrBlank()) {
            false
        } else {
            val parsed = (QRCodeParser.parse(raw) as? QRCodeParser.ParseResult.Valid)?.data
            parsed?.amount.isNullOrBlank() || parsed?.payeeName.isNullOrBlank()
        }
    }
}

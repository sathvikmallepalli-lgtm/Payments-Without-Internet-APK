// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

import android.content.Intent
import android.net.Uri
import com.flowpay.app.features.qr_scanner.domain.QRCodeParser
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiveQrTest {
    @Test fun sharedQrDecodesToTheSavedAccountWithoutAnAmount() {
        val bitmap = ReceiveQr.bitmap(" seller@bank ", "A & B Store")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        val parsed = QRCodeParser.parse(decoded) as QRCodeParser.ParseResult.Valid
        assertEquals("seller@bank", parsed.data.vpa)
        assertEquals("A & B Store", parsed.data.payeeName)
        assertEquals("", parsed.data.amount)
        assertNull(Uri.parse(decoded).getQueryParameter("am"))
    }

    @Test fun receiveQrRejectsPaymentUriInAddressField() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiveQr.uri("upi://pay?pa=seller@bank&am=80", "Store")
        }
    }

    @Test fun imageShareGrantsReadOnlyAccessToOneContentUri() {
        val uri = Uri.parse("content://com.flowpay.app.receiveqr/receive_qr/triomav-pay.png")
        val intent = ReceiveQr.shareIntent(uri, "seller@bank")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertThrows(IllegalArgumentException::class.java) {
            ReceiveQr.shareIntent(Uri.parse("file:///private/payment.db"), "")
        }
    }
}

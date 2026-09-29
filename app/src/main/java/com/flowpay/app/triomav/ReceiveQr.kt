// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.File

/** Receive QRs contain an existing account address and no preset payment amount. */
internal object ReceiveQr {
    private const val SIZE = 512
    private const val PNG_QUALITY = 100

    fun uri(vpa: String, name: String): Uri {
        require(TrioMavInputValidator.isValidUpiId(vpa))
        require(TrioMavInputValidator.isValidName(name))
        return Uri.Builder().scheme("upi").authority("pay")
            .appendQueryParameter("pa", vpa.trim())
            .appendQueryParameter("pn", name.trim())
            .appendQueryParameter("cu", "INR").build()
    }

    fun bitmap(vpa: String, name: String): Bitmap {
        val matrix = MultiFormatWriter().encode(uri(vpa, name).toString(), BarcodeFormat.QR_CODE, SIZE, SIZE)
        val pixels = IntArray(SIZE * SIZE) { index ->
            if (matrix[index % SIZE, index / SIZE]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** Only this public receive image is exposed; the provider cannot read payment records. */
    fun shareUri(context: Context, bitmap: Bitmap): Uri {
        val directory = File(context.cacheDir, "receive_qr").apply { mkdirs() }
        // An earlier share keeps its original payee even if the user changes the saved account.
        val image = File.createTempFile("triomav-pay-", ".png", directory)
        image.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output))
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.receiveqr", image)
    }

    fun shareIntent(image: Uri, caption: String): Intent {
        require(image.scheme == "content")
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, image)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri("Receive QR", image)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

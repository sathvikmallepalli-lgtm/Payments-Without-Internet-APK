// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Flowpay

package com.flowpay.app.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UPIData(
    var vpa: String,
    val payeeName: String,
    var amount: String,
    val transactionNote: String,
    val currency: String,
    /** Original validated QR payload, retained for merchant references and signatures. */
    val originalQrUri: String? = null
) : Parcelable

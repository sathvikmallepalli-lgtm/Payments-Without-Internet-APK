// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 TrioMav Tech

package com.flowpay.app.triomav

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.flowpay.app.MainActivity
import com.flowpay.app.R
import com.flowpay.app.constants.AppConstants
import com.flowpay.app.data.TestResultsManager
import com.flowpay.app.data.Transaction
import com.flowpay.app.data.TransactionSource
import com.flowpay.app.data.TransactionStatus
import com.flowpay.app.data.UPIData
import com.flowpay.app.features.qr_scanner.domain.QRCodeParser
import com.flowpay.app.features.qr_scanner.presentation.QRScannerActivity
import com.flowpay.app.helpers.SetupHelper
import com.flowpay.app.payment.UnresolvedPaymentGuard
import com.flowpay.app.repository.TransactionRepository
import com.flowpay.app.ui.theme.TrioMavBackground
import com.flowpay.app.ui.theme.TrioMavMuted
import com.flowpay.app.ui.theme.TrioMavSurface
import com.flowpay.app.ui.theme.TrioMavText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class Page { HOME, PAY, REVIEW, RECEIVE, ACTIVITY, DETAIL, HELP, PROFILE, HANDOFF }

@Suppress("TooManyFunctions", "LargeClass") // First integration keeps its screens with the payment state machine.
class TrioMavPayActivity : ComponentActivity() {
    private companion object {
        const val STATE_PAGE = "triomav_page"
        const val STATE_PAYMENT = "triomav_payment"
        const val STATE_VPA = "triomav_vpa"
        const val STATE_AMOUNT = "triomav_amount"
        const val STATE_NAME = "triomav_name"
        const val STATE_PHONE_HANDOFF = "triomav_phone_handoff"
        const val STATE_DRAFT_ACTIVE = "triomav_draft_active"
        const val STATE_OWN_VPA = "triomav_own_vpa"
        const val STATE_OWN_NAME = "triomav_own_name"
    }

    private var page by mutableStateOf(Page.HOME)
    private var payment by mutableStateOf<UPIData?>(null)
    private var vpaInput by mutableStateOf("")
    private var amountInput by mutableStateOf("")
    private var nameInput by mutableStateOf("")
    private var ownVpa by mutableStateOf("")
    private var ownName by mutableStateOf("")
    private var savedVpa by mutableStateOf("")
    private var savedName by mutableStateOf("")
    private var message by mutableStateOf<Int?>(null)
    private var records by mutableStateOf<List<Transaction>>(emptyList())
    private var selectedTransaction by mutableStateOf<Transaction?>(null)
    private var busy by mutableStateOf(false)
    private var allowNewAttempt by mutableStateOf(false)
    private var blockedDuplicate by mutableStateOf(false)
    private var handoffByPhone by mutableStateOf(false)
    private var draftActive by mutableStateOf(false)
    private var sharingQr by mutableStateOf(false)
    private var internetValidated by mutableStateOf(false)
    private var cellularServiceAvailable by mutableStateOf(false)
    private var ussdReady by mutableStateOf(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val scanned = result.data?.getParcelableExtra<UPIData>(QRScannerActivity.EXTRA_SCANNED_UPI)
            if (scanned != null) {
                payment = scanned
                draftActive = true
                vpaInput = scanned.vpa
                amountInput = scanned.amount
                nameInput = scanned.payeeName
                message = null
                page = Page.PAY
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = TrioMavBackground.toArgb()
        window.navigationBarColor = TrioMavBackground.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        val receivePrefs = getSharedPreferences("triomav_pay", MODE_PRIVATE)
        ownVpa = receivePrefs.getString("receive_vpa", "").orEmpty()
        ownName = receivePrefs.getString("receive_name", "").orEmpty()
        if (TrioMavInputValidator.isValidUpiId(ownVpa) &&
            TrioMavInputValidator.isValidName(ownName)
        ) {
            savedVpa = ownVpa
            savedName = ownName
        }
        restoreDraft(savedInstanceState)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { TransactionRepository.getInstance(applicationContext) }
                    .getAllTransactions().collectLatest { latest ->
                        records = latest
                        selectedTransaction?.let { selected ->
                            selectedTransaction = latest.firstOrNull {
                                it.transactionId == selected.transactionId
                            } ?: selected
                        }
                    }
            } catch (_: Exception) {
                message = R.string.triomav_activity_unavailable
            }
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = TrioMavText,
                    onPrimary = TrioMavBackground,
                    background = TrioMavBackground,
                    onBackground = TrioMavText,
                    surface = TrioMavSurface,
                    onSurface = TrioMavText,
                ),
            ) {
                TrioMavScreen()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PAGE, page.name)
        outState.putParcelable(STATE_PAYMENT, payment)
        outState.putString(STATE_VPA, vpaInput)
        outState.putString(STATE_AMOUNT, amountInput)
        outState.putString(STATE_NAME, nameInput)
        outState.putBoolean(STATE_PHONE_HANDOFF, handoffByPhone)
        outState.putBoolean(STATE_DRAFT_ACTIVE, draftActive)
        outState.putString(STATE_OWN_VPA, ownVpa)
        outState.putString(STATE_OWN_NAME, ownName)
        super.onSaveInstanceState(outState)
    }

    private fun restoreDraft(state: Bundle?) {
        if (state == null) return
        @Suppress("DEPRECATION")
        val restoredPayment = state.getParcelable<UPIData>(STATE_PAYMENT)
        payment = restoredPayment
        vpaInput = state.getString(STATE_VPA).orEmpty()
        amountInput = state.getString(STATE_AMOUNT).orEmpty()
        nameInput = state.getString(STATE_NAME).orEmpty()
        handoffByPhone = state.getBoolean(STATE_PHONE_HANDOFF)
        draftActive = state.getBoolean(STATE_DRAFT_ACTIVE)
        ownVpa = state.getString(STATE_OWN_VPA, ownVpa)
        ownName = state.getString(STATE_OWN_NAME, ownName)
        val restoredPage = Page.entries.firstOrNull { it.name == state.getString(STATE_PAGE) } ?: Page.HOME
        page = when {
            restoredPage == Page.DETAIL -> Page.ACTIVITY
            restoredPage == Page.REVIEW && restoredPayment == null -> Page.PAY
            else -> restoredPage
        }
    }

    override fun onStart() {
        super.onStart()
        refreshRoutes()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = runOnUiThread { refreshRoutes() }
            override fun onLost(network: android.net.Network) = runOnUiThread { refreshRoutes() }
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) =
                runOnUiThread { refreshRoutes() }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    override fun onResume() {
        super.onResume()
        if (page == Page.HANDOFF) busy = false
        refreshRoutes()
    }

    override fun onStop() {
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        networkCallback = null
        super.onStop()
    }

    private fun refreshRoutes() {
        internetValidated = hasValidatedInternet()
        cellularServiceAvailable = hasCellularService()
        ussdReady = isUssdReady()
    }

    @Composable
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun TrioMavScreen() {
        BackHandler(enabled = page != Page.HOME) {
            if (busy) {
                message = R.string.triomav_opening_route
            } else {
                page = Page.HOME
                message = null
            }
        }
        val online = internetValidated
        val phone = cellularServiceAvailable
        val current = payment
        val originalQr = remember(current?.originalQrUri) {
            current?.originalQrUri?.let { raw ->
                (QRCodeParser.parse(raw) as? QRCodeParser.ParseResult.Valid)?.data
            }
        }
        val route = if (current == null) {
            PaymentRoute.UNAVAILABLE
        } else {
            PaymentRoutePlanner.choose(
                PaymentRouteInputs(
                    recipientType = RecipientType.UPI_ID,
                    internetValidated = online,
                    phoneLineEligible = phone,
                    ussdTested = ussdReady,
                    ivrTested = false,
                    previousAttemptUnresolved = false,
                    requiresQrReference = UpiAppHandoff.requiresOnlineRoute(current),
                    qrRequiresDirectScan = UpiAppHandoff.requiresDirectScan(current),
                ),
            )
        }
        if (page == Page.ACTIVITY) {
            ActivityListScreen()
            return
        }
        Column(
            Modifier.fillMaxSize().background(TrioMavBackground)
                .safeDrawingPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (page != Page.HOME) {
                    TextButton(onClick = {
                        page = Page.HOME
                        message = null
                    }, enabled = !busy) {
                        Text(stringResource(R.string.triomav_home))
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            when (page) {
                Page.HOME -> {
                    Text(
                        stringResource(R.string.triomav_home_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.triomav_home_subtitle), color = TrioMavMuted)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(if (online) R.string.triomav_ready_online else R.string.triomav_ready_phone),
                        color = TrioMavMuted,
                    )
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = {
                            scanner.launch(
                                Intent(this@TrioMavPayActivity, QRScannerActivity::class.java)
                                    .putExtra(QRScannerActivity.EXTRA_PICK_ONLY, true),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TrioMavText),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(stringResource(R.string.triomav_scan_pay), style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (draftActive && vpaInput.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                page = Page.PAY
                                message = null
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) { Text(stringResource(R.string.triomav_resume_payment)) }
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            draftActive = true
                            payment = null
                            vpaInput = ""
                            amountInput = ""
                            nameInput = ""
                            page = Page.PAY
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.triomav_pay_someone))
                    }
                    TextButton(onClick = {
                        startActivity(
                            Intent(this@TrioMavPayActivity, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_PHONE_PAYMENT, true),
                        )
                    }) {
                        Text(stringResource(R.string.triomav_pay_phone_number))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Shortcut(R.string.triomav_receive, Modifier.weight(1f)) { page = Page.RECEIVE }
                        Shortcut(R.string.triomav_activity, Modifier.weight(1f)) { page = Page.ACTIVITY }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Shortcut(R.string.triomav_help, Modifier.weight(1f)) { page = Page.HELP }
                        Shortcut(R.string.triomav_profile, Modifier.weight(1f)) { page = Page.PROFILE }
                    }
                    Spacer(Modifier.height(28.dp))
                    if (records.any {
                            it.status in setOf(
                                TransactionStatus.PENDING,
                                TransactionStatus.UNVERIFIED, TransactionStatus.NEEDS_REVIEW,
                            )
                        }
                    ) {
                        Card(
                            onClick = { page = Page.ACTIVITY },
                            colors = CardDefaults.cardColors(containerColor = TrioMavSurface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.triomav_payment_needs_check),
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(stringResource(R.string.triomav_check_before_retry), color = TrioMavMuted)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    Text(stringResource(R.string.triomav_recent), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    if (records.isEmpty()) Text(stringResource(R.string.triomav_no_payments), color = TrioMavMuted)
                    records.take(3).forEach { TransactionRow(it) }
                }
                Page.PAY -> {
                    Title(R.string.triomav_pay_someone)
                    if (payment?.originalQrUri != null) {
                        TextButton(onClick = {
                            payment = null
                            vpaInput = ""
                            nameInput = ""
                            amountInput = ""
                            draftActive = true
                            message = null
                        }) { Text(stringResource(R.string.triomav_enter_manually)) }
                    }
                    TextField(vpaInput, {
                        vpaInput = it
                        payment = null
                    }, R.string.triomav_enter_upi_id, readOnly = payment?.originalQrUri != null)
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        nameInput,
                        { nameInput = it },
                        R.string.triomav_enter_name,
                        readOnly = !originalQr?.payeeName.isNullOrBlank(),
                    )
                    Text(
                        stringResource(R.string.triomav_enter_name_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TrioMavMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        amountInput,
                        { amountInput = it },
                        R.string.triomav_enter_amount,
                        KeyboardType.Decimal,
                        readOnly = !originalQr?.amount.isNullOrBlank(),
                    )
                    Text(
                        stringResource(R.string.triomav_amount_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TrioMavMuted,
                    )
                    if (!originalQr?.payeeName.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(requireNotNull(originalQr).payeeName, color = TrioMavMuted)
                        Text(
                            stringResource(R.string.triomav_qr_name_unverified),
                            style = MaterialTheme.typography.bodySmall,
                            color = TrioMavMuted,
                        )
                    }
                    Message()
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { prepareReview() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TrioMavText),
                    ) {
                        Text(stringResource(R.string.triomav_continue))
                    }
                }
                Page.REVIEW -> {
                    Title(R.string.triomav_review)
                    current?.let {
                        BigLine(stringResource(R.string.amount_rupees, it.amount))
                        Text(stringResource(R.string.triomav_recipient), color = TrioMavMuted)
                        Text(it.vpa, style = MaterialTheme.typography.titleLarge)
                        if (it.payeeName.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(it.payeeName)
                            Text(
                                stringResource(
                                    if (originalQr?.payeeName.isNullOrBlank()) {
                                        R.string.triomav_enter_name_hint
                                    } else {
                                        R.string.triomav_qr_name_unverified
                                    },
                                ),
                                color = TrioMavMuted,
                            )
                        }
                        if (it.transactionNote.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.triomav_payment_note), color = TrioMavMuted)
                            Text(it.transactionNote)
                        }
                        val qrReference = it.originalQrUri?.let { raw ->
                            val qrUri = Uri.parse(raw)
                            qrUri.getQueryParameter("tr") ?: qrUri.getQueryParameter("tid")
                        }
                        if (!qrReference.isNullOrBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.triomav_merchant_reference), color = TrioMavMuted)
                            Text(qrReference.take(80))
                        }
                        Spacer(Modifier.height(24.dp))
                        val duplicate = blockedDuplicate || records.any { row ->
                            UnresolvedPaymentGuard.matches(row, it.vpa, it.amount)
                        }
                        if (duplicate && !allowNewAttempt) {
                            Text(stringResource(R.string.triomav_repeat_warning), color = TrioMavText)
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.triomav_check_before_retry), color = TrioMavMuted)
                            TextButton(onClick = { allowNewAttempt = true }) {
                                Text(stringResource(R.string.triomav_checked_make_new))
                            }
                        } else {
                            val routeLabel = when (route) {
                                PaymentRoute.ONLINE_APP -> R.string.triomav_pay_in_upi_app
                                PaymentRoute.FLOWPAY_USSD -> R.string.triomav_pay_by_phone
                                else -> R.string.triomav_route_unavailable
                            }
                            val routeNote = when {
                                UpiAppHandoff.requiresDirectScan(it) -> R.string.triomav_qr_needs_direct_scan
                                route == PaymentRoute.ONLINE_APP -> R.string.triomav_upi_route_note
                                UpiAppHandoff.requiresOnlineRoute(it) ->
                                    R.string.triomav_qr_reference_requires_online
                                else -> R.string.triomav_phone_route_note
                            }
                            Text(stringResource(routeNote), color = TrioMavMuted)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { startPayment(it, route) },
                                enabled = !busy && route != PaymentRoute.UNAVAILABLE,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TrioMavText),
                            ) {
                                Text(stringResource(if (busy) R.string.triomav_opening_route else routeLabel))
                            }
                            val phoneAlternative = phone && ussdReady && !UpiAppHandoff.requiresOnlineRoute(it)
                            if (route == PaymentRoute.ONLINE_APP && phoneAlternative) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { startPayment(it, PaymentRoute.FLOWPAY_USSD) },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                ) {
                                    Text(stringResource(R.string.triomav_pay_by_phone))
                                }
                            }
                            if (route == PaymentRoute.UNAVAILABLE && !UpiAppHandoff.requiresOnlineRoute(it)) {
                                TextButton(onClick = { page = Page.PROFILE }) {
                                    Text(stringResource(R.string.triomav_profile_flowpay))
                                }
                            }
                        }
                    }
                    Message()
                    TextButton(onClick = { page = Page.PAY }, enabled = !busy) {
                        Text(stringResource(R.string.triomav_back))
                    }
                }
                Page.RECEIVE -> {
                    Title(R.string.triomav_receive_title)
                    Text(stringResource(R.string.triomav_receive_hint), color = TrioMavMuted)
                    Spacer(Modifier.height(16.dp))
                    TextField(ownVpa, { ownVpa = it }, R.string.triomav_your_upi_id)
                    Spacer(Modifier.height(10.dp))
                    TextField(ownName, { ownName = it }, R.string.triomav_your_name)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { saveOwnVpa() }) { Text(stringResource(R.string.triomav_save_upi_id)) }
                    Message()
                    if (savedVpa.isNotBlank() && savedName.isNotBlank()) {
                        val qrVpa = savedVpa
                        val qrName = savedName
                        var bitmap by remember(qrVpa, qrName) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(qrVpa, qrName) {
                            bitmap = try {
                                withContext(Dispatchers.Default) { ReceiveQr.bitmap(qrVpa, qrName) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                message = R.string.triomav_qr_unavailable
                                null
                            }
                        }
                        bitmap?.let {
                            Image(
                                it.asImageBitmap(),
                                contentDescription = stringResource(R.string.triomav_receive_title),
                                modifier = Modifier.fillMaxWidth().height(280.dp).padding(18.dp),
                            )
                            Text(qrName, fontWeight = FontWeight.Bold)
                            Text(qrVpa, color = TrioMavMuted)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { shareReceiveQr(it, qrVpa) },
                                enabled = !sharingQr,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                            ) { Text(stringResource(R.string.triomav_share_qr)) }
                            TextButton(onClick = {
                                copyText(getString(R.string.triomav_your_upi_id), qrVpa)
                            }) { Text(stringResource(R.string.triomav_copy_upi_id)) }
                        }
                        if (ownVpa.trim() != savedVpa || ownName.trim() != savedName) {
                            Text(stringResource(R.string.triomav_qr_unsaved_changes), color = TrioMavMuted)
                        }
                        Text(stringResource(R.string.triomav_share_this_qr), color = TrioMavMuted)
                    }
                }
                Page.ACTIVITY -> Unit
                Page.DETAIL -> {
                    selectedTransaction?.let { row ->
                        Title(R.string.triomav_payment_details)
                        BigLine(stringResource(R.string.amount_rupees, row.amount))
                        Text(
                            row.upiId ?: row.phoneNumber ?: row.bankName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(statusLabel(row)), fontWeight = FontWeight.Bold)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(row.timestamp)),
                            color = TrioMavMuted,
                        )
                        if (row.bankRef != null) {
                            Spacer(Modifier.height(14.dp))
                            Text(stringResource(R.string.triomav_reference), color = TrioMavMuted)
                            Text(row.bankRef)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(detailExplanation(row)), color = TrioMavMuted)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { copyPaymentDetails(row) }) {
                            Text(stringResource(R.string.triomav_copy_payment_details))
                        }
                        Message()
                        TextButton(onClick = { page = Page.HELP }) { Text(stringResource(R.string.triomav_help)) }
                    }
                }
                Page.HELP -> {
                    Title(R.string.triomav_help_title)
                    listOf(
                        R.string.triomav_help_step_one,
                        R.string.triomav_help_step_two,
                        R.string.triomav_help_step_three,
                    ).forEach { id ->
                        Text(stringResource(id), modifier = Modifier.padding(bottom = 20.dp))
                    }
                }
                Page.PROFILE -> {
                    Title(R.string.triomav_profile_title)
                    Text(stringResource(R.string.triomav_profile_flowpay_detail), color = TrioMavMuted)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { startActivity(Intent(this@TrioMavPayActivity, MainActivity::class.java)) }) {
                        Text(stringResource(R.string.triomav_profile_flowpay))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            ),
                        )
                    }) { Text(stringResource(R.string.triomav_app_settings)) }
                }
                Page.HANDOFF -> {
                    Title(R.string.triomav_handoff_unknown)
                    val detailId = if (handoffByPhone) {
                        R.string.triomav_phone_unknown_detail
                    } else {
                        R.string.triomav_handoff_unknown_detail
                    }
                    Text(stringResource(detailId), color = TrioMavMuted)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { page = Page.ACTIVITY }) { Text(stringResource(R.string.triomav_activity)) }
                }
            }
        }
    }

    @Composable private fun ActivityListScreen() {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(TrioMavBackground).safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { page = Page.HOME }) {
                        Text(stringResource(R.string.triomav_home))
                    }
                }
                Spacer(Modifier.height(28.dp))
                Title(R.string.triomav_activity)
                if (records.isEmpty()) {
                    Text(stringResource(R.string.triomav_no_payments), color = TrioMavMuted)
                }
            }
            items(records, key = { it.transactionId }) { TransactionRow(it) }
        }
    }

    @Composable private fun Title(id: Int) {
        Text(
            stringResource(id),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(22.dp))
    }

    @Composable private fun BigLine(value: String) {
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
    }

    @Composable private fun Shortcut(id: Int, modifier: Modifier, onClick: () -> Unit) {
        OutlinedButton(onClick, modifier.heightIn(min = 58.dp)) { Text(stringResource(id)) }
    }

    @Composable private fun TextField(
        value: String,
        onValue: (String) -> Unit,
        label: Int,
        keyboard: KeyboardType = KeyboardType.Text,
        readOnly: Boolean = false,
    ) {
        OutlinedTextField(
            value,
            onValue,
            label = { Text(stringResource(label)) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            readOnly = readOnly,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable private fun Message() {
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(it),
                color = TrioMavText,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    @Composable private fun TransactionRow(row: Transaction) {
        Card(
            onClick = {
                selectedTransaction = row
                page = Page.DETAIL
            },
            colors = CardDefaults.cardColors(containerColor = TrioMavSurface),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.amount_rupees, row.amount), fontWeight = FontWeight.Bold)
                Text(row.upiId ?: row.phoneNumber ?: row.bankName, color = TrioMavMuted)
                Text(stringResource(statusLabel(row)), color = TrioMavMuted)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(row.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TrioMavMuted,
                )
            }
        }
    }

    private fun statusLabel(row: Transaction): Int = when (row.status) {
        TransactionStatus.SUCCESS -> R.string.triomav_status_sms_match
        TransactionStatus.FAILED -> R.string.triomav_status_failed
        TransactionStatus.CANCELLED -> R.string.triomav_status_not_started
        else -> R.string.triomav_status_check
    }

    private fun detailExplanation(row: Transaction): Int = when {
        row.source == TransactionSource.UPI_HANDOFF && row.status == TransactionStatus.CANCELLED ->
            R.string.triomav_detail_not_started
        row.source == TransactionSource.UPI_HANDOFF -> R.string.triomav_handoff_unknown_detail
        row.status == TransactionStatus.SUCCESS -> R.string.triomav_detail_sms_match
        row.status == TransactionStatus.FAILED -> R.string.triomav_detail_failed
        else -> R.string.triomav_check_before_retry
    }

    private fun prepareReview() {
        val name = nameInput.trim()
        val amount = TrioMavInputValidator.amount(amountInput)
        val error = when {
            !TrioMavInputValidator.isValidUpiId(vpaInput) -> R.string.triomav_invalid_upi_id
            !TrioMavInputValidator.isValidName(name) -> R.string.triomav_invalid_name
            amount == null -> R.string.triomav_invalid_amount
            else -> null
        }
        if (error != null) {
            message = error
            return
        }
        val manualPayment = UPIData(vpaInput.trim(), name, requireNotNull(amount), "", "INR")
        payment = (payment ?: manualPayment).copy(
            vpa = vpaInput.trim(),
            payeeName = name,
            amount = requireNotNull(amount),
        )
        draftActive = true
        allowNewAttempt = false
        blockedDuplicate = false
        message = null
        page = Page.REVIEW
    }

    private fun startPayment(data: UPIData, route: PaymentRoute) {
        if (busy) return
        if (!PaymentRoutePlanner.canStart(route, freshUpiRouteInputs(data))) {
            refreshRoutes()
            message = R.string.triomav_route_changed
            return
        }
        when (route) {
            PaymentRoute.ONLINE_APP -> startOnlinePayment(data)
            PaymentRoute.FLOWPAY_USSD -> startPhonePayment(data)
            else -> message = R.string.triomav_route_unavailable
        }
    }

    private fun startPhonePayment(data: UPIData) {
        busy = true
        lifecycleScope.launch {
            val duplicate = try {
                withContext(Dispatchers.IO) {
                    TransactionRepository.getInstance(applicationContext)
                        .hasUnresolvedUpiPayment(data.vpa, data.amount)
                }
            } catch (_: Exception) {
                message = R.string.triomav_activity_unavailable
                busy = false
                return@launch
            }
            if (duplicate && !allowNewAttempt) {
                blockedDuplicate = true
                message = R.string.triomav_repeat_warning
                busy = false
                return@launch
            }
            if (!PaymentRoutePlanner.canStart(PaymentRoute.FLOWPAY_USSD, freshUpiRouteInputs(data))) {
                refreshRoutes()
                message = R.string.triomav_route_changed
                busy = false
                return@launch
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                message = R.string.error_payment_not_started
                busy = false
                return@launch
            }
            try {
                startActivity(
                    Intent(this@TrioMavPayActivity, QRScannerActivity::class.java)
                        .putExtra(QRScannerActivity.EXTRA_REVIEWED_UPI, data),
                )
                handoffByPhone = true
                draftActive = false
                page = Page.HANDOFF
            } catch (_: RuntimeException) {
                message = R.string.error_payment_not_started
                busy = false
            }
        }
    }

    private fun startOnlinePayment(data: UPIData) {
        val handoffUri = try {
            UpiAppHandoff.uri(data)
        } catch (_: RuntimeException) {
            message = R.string.triomav_invalid_payment_details
            return
        }
        busy = true
        lifecycleScope.launch {
            val transaction = UpiAppHandoff.attempt(data)
            val inserted = try {
                withContext(Dispatchers.IO) {
                    TransactionRepository.getInstance(applicationContext)
                        .insertUpiHandoffIfSafe(transaction, allowNewAttempt)
                }
            } catch (_: Exception) {
                message = R.string.triomav_payment_not_saved
                busy = false
                return@launch
            }
            if (!inserted) {
                blockedDuplicate = true
                message = R.string.triomav_repeat_warning
                busy = false
                return@launch
            }
            if (!PaymentRoutePlanner.canStart(PaymentRoute.ONLINE_APP, freshUpiRouteInputs(data))) {
                recordNotStarted(transaction, R.string.triomav_route_changed)
                refreshRoutes()
                return@launch
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                recordNotStarted(transaction, R.string.error_payment_not_started)
                return@launch
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, handoffUri))
                handoffByPhone = false
                draftActive = false
                page = Page.HANDOFF
            } catch (_: RuntimeException) {
                recordNotStarted(transaction, R.string.triomav_no_upi_app)
            }
        }
    }

    /** Only used when the app has definitely not opened a payment route. */
    private suspend fun recordNotStarted(transaction: Transaction, error: Int) {
        try {
            withContext(Dispatchers.IO) {
                TransactionRepository.getInstance(applicationContext)
                    .updateTransaction(transaction.copy(status = TransactionStatus.CANCELLED))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { /* Preserve the original UNVERIFIED row if its update fails. */ }
        message = error
        busy = false
    }

    private fun freshUpiRouteInputs(data: UPIData): PaymentRouteInputs = PaymentRouteInputs(
        recipientType = RecipientType.UPI_ID,
        internetValidated = hasValidatedInternet(),
        phoneLineEligible = hasCellularService(),
        ussdTested = isUssdReady(),
        ivrTested = false,
        previousAttemptUnresolved = false,
        requiresQrReference = UpiAppHandoff.requiresOnlineRoute(data),
        qrRequiresDirectScan = UpiAppHandoff.requiresDirectScan(data),
    )

    private fun saveOwnVpa() {
        if (!TrioMavInputValidator.isValidUpiId(ownVpa)) {
            message = R.string.triomav_invalid_upi_id
            return
        }
        if (!TrioMavInputValidator.isValidName(ownName)) {
            message = R.string.triomav_receive_name_required
            return
        }
        ownVpa = ownVpa.trim()
        ownName = ownName.trim()
        savedVpa = ownVpa
        savedName = ownName
        getSharedPreferences("triomav_pay", MODE_PRIVATE).edit()
            .putString("receive_vpa", savedVpa).putString("receive_name", savedName).apply()
        message = R.string.triomav_upi_id_saved
    }

    private fun hasValidatedInternet(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasCellularService(): Boolean {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        // A ready SIM and voice capability permit an attempt; they do not prove
        // live coverage or that a particular carrier accepts this USSD code.
        return tm.isVoiceCapable && tm.simState == TelephonyManager.SIM_STATE_READY
    }

    private fun isUssdReady(): Boolean {
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        val setupReady = prefs.getBoolean(AppConstants.KEY_SETUP_COMPLETED, false) &&
            prefs.getBoolean(AppConstants.KEY_TEST_COMPLETED, false)
        val ussdTested = TestResultsManager(this).getTestResults()?.ussdEnabled == true
        val permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        return setupReady && ussdTested && permitted && SetupHelper.isScanToPayUssdAvailable(this)
    }

    private fun copyText(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        message = R.string.triomav_copied
    }

    private fun copyPaymentDetails(row: Transaction) {
        val summary = getString(
            R.string.triomav_support_summary,
            row.upiId ?: row.phoneNumber ?: row.bankName,
            row.amount,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(row.timestamp)),
            getString(statusLabel(row)),
            row.bankRef ?: getString(R.string.triomav_reference_unavailable),
            row.transactionId,
            getString(detailExplanation(row)),
        )
        copyText(getString(R.string.triomav_payment_details), summary)
    }

    private fun shareReceiveQr(bitmap: Bitmap, vpa: String) {
        if (sharingQr) return
        sharingQr = true
        lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { ReceiveQr.shareUri(this@TrioMavPayActivity, bitmap) }
                val intent = ReceiveQr.shareIntent(image, getString(R.string.triomav_receive_share_caption, vpa))
                startActivity(Intent.createChooser(intent, getString(R.string.triomav_share_qr)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = R.string.triomav_qr_share_failed
            } finally {
                sharingQr = false
            }
        }
    }
}

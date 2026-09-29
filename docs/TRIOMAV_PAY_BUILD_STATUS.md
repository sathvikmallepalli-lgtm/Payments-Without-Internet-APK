# TrioMav Pay build status

TrioMav Pay is now the Android launcher and payment review layer above the existing FlowPay phone-payment components. This is a working, buildable **partner-independent app foundation**, not a live native UPI participant or a nationally ready payment service.

## Implemented in this repository

- A single consumer home with Scan & Pay, pay by UPI ID, pay by mobile number, Receive, Activity, Help and phone-payment setup.
- A scan-only use of FlowPay's QR scanner: the QR supplies payment details but cannot start a call until the person reviews the recipient and amount.
- Merchant QR order details are preserved in the online UPI handoff. The review shows QR notes and references, and a scanned QR with a fixed payee or amount keeps those fields locked. A signed QR with its own amount and name is passed unchanged, including its original encoding and missing optional fields. A signed QR missing required reviewed fields is directed to a UPI app for direct scanning; TrioMav cannot append those fields without changing the signed payload. QRs with order references, notes, signatures or merchant identifiers cannot use the USSD route, including through the older FlowPay scanner; the phone route would lose details needed to reconcile the order.
- Online route selection when Android reports validated internet. The app hands the reviewed payment to an installed UPI app through a generic `upi://pay` intent. The UPI app authorizes and executes the payment.
- FlowPay USSD fallback for reviewed UPI IDs when the phone has a ready voice SIM and the **specific USSD test** passed. The route is checked again just before starting. The carrier and bank can still reject the call; a ready SIM is not proof of coverage.
- The existing FlowPay UPI 123Pay IVR screen for mobile-number payments, opened directly through its existing setup and permission gates. This remains a separate screen within the same APK while its UI is integrated into the TrioMav design.
- A locally generated receive QR for an **existing** UPI ID entered by the user. TrioMav does not create, register or verify that ID, and the merchant must confirm incoming funds in their bank.
- Receive QRs can be shared as PNG images through Android's share sheet; the displayed UPI ID can also be copied. The sharing provider exposes only the receive-image cache, with access granted to the selected image. Each share keeps an immutable file so changing the saved account cannot change an earlier shared QR. Unsaved account edits are labelled beside the displayed QR.
- Local transaction history with lazy rendering, an explicit unresolved-payment warning, and a decimal-precise duplicate-attempt pause for the same UPI ID and amount. Online handoff check-and-insert is atomic in Room; the phone route rechecks local history before launching. Online handoffs remain `UNVERIFIED`; returning from another app is never recorded as payment success.
- In-progress payee and amount review survives Android Activity recreation. An open payment detail follows later database status updates.
- Home can resume an unfinished payment review. Returning from review keeps user-entered QR amounts editable while preserving amounts fixed by the original QR. The recipient name required by the UPI linking contract is labelled by its source; user-entered and QR-supplied names are never described as verified bank names.
- Manual address fields accept bare UPI IDs, not complete payment links. Amount validation uses exact decimal rupees and rejects exponent notation, extra paise and out-of-range values. The online handoff validates the payment before recording an attempt and rechecks connectivity immediately before launch. A failed connectivity or foreground preflight prevents launch and marks the attempt not started when that update can be saved. If saving that update fails or the process ends mid-operation, the durable attempt remains uncertain. Returning from an opened route retains an uncertain result.
- Payment details can be copied for support with their status, evidence explanation, bank reference if available, and separate app attempt ID. Profile opens Android's app-permission controls.
- The consumer screens account for system bars and the keyboard; action buttons can grow with larger text. Headings and inline feedback have screen-reader semantics. Camera and autofocus are optional installation features; manual entry and Receive remain usable without them.
- No backend account, custodial wallet, UPI PIN collection, automatic retry, or speculative “Paid” receipt.

The latest local verification passed `:app:detekt`, `:app:lintDebug`, `:app:testDebugUnitTest` (182 tests, no failures or skips), `:app:assembleDebug`, and `:app:assembleRelease -PallowUnsigned`. The QR tests decode the actual generated bitmap and verify its account, name and absence of a preset amount; share-intent tests verify content-URI read grants. The packaged manifest was checked for the launcher, keyboard resize setting, optional camera features and non-exported QR provider. The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`; the release APK is unsigned and needs release signing before distribution.

## Device checks for this build

- Scan a QR with no amount, enter an amount, review it, and go back. The entered amount must remain editable. Repeat with a fixed-amount merchant QR; its amount must remain locked.
- Save a receive ID, share its QR, then edit the account without saving. The QR, its displayed ID and its copy/share actions must still identify the saved account. After saving the new account, an earlier shared image must still identify the old account.
- Use a small screen with large text and the keyboard open. Check that the payment fields, navigation and action labels remain visible and that screen readers announce headings and errors.
- Lose internet or background the app while it saves an online attempt, before the UPI app opens. It must not open an unavailable or background payment route, and the unstarted attempt must be distinguished from an uncertain submitted payment.

## Required before a public payment launch

1. Decide whether TrioMav remains an app-to-app handoff product or becomes a native UPI third-party app. Native bank linking, payer VPA creation, in-app PIN authorization, and authoritative UPI status require a PSP bank and the applicable NPCI onboarding/certification process. The current code has no such credentials or integration.
2. Replace the generic handoff's unknown outcome with an approved provider status/reconciliation interface if one becomes available. An intent response or app return is not independent payment evidence.
3. Run controlled device, SIM, operator, bank, and low-connectivity tests of the USSD and IVR paths, including call failure, late SMS, duplicate amounts and no-SMS outcomes. Automated tests cannot prove a live bank or carrier flow.
4. Complete accessibility, language, support, privacy, security, distribution-permission, and incident-response reviews. Validate the brand and all claims with users and the payment partner.
5. Integrate the remaining legacy FlowPay phone screens into one TrioMav design, then run a supervised pilot before wider distribution.

The build and automated checks do not replace live testing. This workspace currently has no connected Android device or emulator, so QR camera, UPI-app interoperability, bank SMS and carrier calls have not been exercised end to end here.

The design and rollout contract is in [the product blueprint](TRIOMAV_PAY_PRODUCT_BLUEPRINT.md). The safety and rail limitations are in [the offline roadmap](TRIOMAV_OFFLINE_ROADMAP.md).

Official references for the external dependencies: [NPCI UPI FAQ](https://www.npci.org.in/what-we-do/upi/faqs), [NPCI UPI product booklet](https://www.npci.org.in/PDF/npci/upi/Product-Booklet.pdf), and [Google Pay for India intent guidance](https://developers.google.com/pay/india/api/android/in-app-payments). The Google guidance requires provider-side verification of payment status even after an apparent success response; TrioMav currently lacks that provider-side status.

The name and signature handling also follows the [NPCI UPI Linking Specification v1.6](https://www.labnol.org/files/linking.pdf), an older NPCI document mirrored at Labnol. It defines payee name as mandatory and describes signing the original request string. This informs conservative payload handling; it does not certify interoperability with current UPI apps or replace the current partner integration contract.

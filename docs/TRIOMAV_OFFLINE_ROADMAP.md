# TrioMav Tech: Flowpay offline payment roadmap

## What this app can do today

Flowpay is a local Android front end for `*99#` USSD QR/VPA payments and UPI
123Pay voice payments. The bank and UPI network execute the transfer; Flowpay
starts the call, guides the user, and listens for a bank SMS. The app has no
internet permission, PSP bank integration, settlement API, or access to the
UPI PIN. It needs usable cellular voice/signalling service even when mobile
data is unavailable. No cellular service means the current rails cannot send
a payment.

The code paths are in `QRScannerActivity`, `MainActivityHelper`,
`PaymentSessionManager`, `TransactionDetector`, and `SmsIngestionPipeline`.
Carrier capability gating is in `SetupHelper`; local transaction data uses
Room with SQLCipher. The SMS parser intentionally accepts several bank
formats, so a matching amount is evidence, not an authoritative settlement
query.

## First priority: never lose an uncertain payment

An observed Canara Bank QR transfer completed after the user entered the UPI
PIN and then pressed Cancel; the bank sent no SMS. Previously the QR cancel
path closed the SMS window and marked the attempt CANCELLED. A timeout or app
restart could also delete an unconfirmed attempt. This left no record to
check and invited a duplicate payment.

The current change makes this lifecycle explicit:

1. Write the attempt locally before opening the dialer.
2. Once a payment call may have started, closing the screen records
   `UNVERIFIED` and keeps the SMS window open until its deadline.
3. If no SMS arrives, keep the attempt visible in History > Check payments.
4. If a debit SMS arrives after the user closed the flow, attach it as
   `NEEDS_REVIEW`, not automatic success. The amount alone cannot establish
   that this debit belongs to this attempt.
5. Only a payment that never launched the call is marked `CANCELLED`.

The user checks the bank app or statement for amount, payee, time and bank
reference before retrying. Without a bank message or PSP bank access,
Flowpay cannot independently declare settlement. A user-entered outcome
could be added later, clearly labeled as user reported rather than bank
verified.

## Next product increments

| Order | Increment | Why it matters | Dependency |
| --- | --- | --- | --- |
| 1 | Test the cancel-after-PIN and no-SMS case on Airtel + Canara and other carrier/bank pairs | Validates the exact failure on a device; unit tests cannot emulate the bank | Test SIM, small live transactions, consenting testers |
| 2 | A guided review flow with a reminder and optional bank reference entry | Helps users resolve `UNVERIFIED` without mislabeling it paid | UX research; preserve user-reported provenance |
| 3 | Carrier/rail health checks before payment | Weak voice/signalling can fail even when the data indicator has bars | Device and carrier test matrix |
| 4 | Improve QR payee confirmation and retry protection | Reduces wrong-payee and duplicate attempts | Real merchant QR corpus and bank result samples |
| 5 | Local language, accessibility, and low-end device testing | Makes offline payments usable under the conditions they target | Field research |
| 6 | PSP bank integration for transaction-status queries and reconciliation | Enables authoritative status when SMS is missing | Bank/NPCI partnership, approved APIs and compliance work |

For Google Play distribution, `RECEIVE_SMS` is a restricted permission. The
app's SMS-based financial transaction use case must be declared and reviewed
in Play Console; approval is not implied by the code working on a device.

The product should distinguish **no mobile data**, **weak cellular service**,
and **no cellular service** in its UX. The first can use the present USSD/IVR
rails; the last cannot. NFC-based offline options are a separate product and
partner track, not a toggle on this dialer app. NPCI describes UPI LITE Tap
& Pay as NFC-based, and RBI's offline framework addresses authorised payment
system participants.

## TypeSafe AI fit

The TypeSafe AI skill recommends typed judgments for ambiguous natural
language while keeping rules and actions in code. A future online support
tool could use it to classify **unrecognized, redacted** bank message formats
for human review, or route support requests. Its typed answer and confidence
cannot prove that money settled. The offline payment path must work without
that API and must never promote a transaction to `SUCCESS` from an AI score.

## Source references

- [NPCI UPI FAQ: bank app transaction status and grievances](https://www.npci.org.in/what-we-do/upi/faqs)
- [NPCI UPI ecosystem: PSP bank role](https://www.npci.org.in/what-we-do/upi/upi-ecosystem-statistics)
- [NPCI UPI LITE Tap & Pay overview](https://www.npci.org.in/what-we-do/upi-lite/upi-tap-and-pay/product-overview)
- [RBI framework for offline small-value digital payments](https://www.rbi.org.in/scripts/RTGS_Notification.aspx?Id=12215)
- [TypeSafe AI confidence guidance](https://docs.typesafe.ai/confidence)
- [Google Play SMS permission policy](https://support.google.com/googleplay/android-developer/answer/10208820)

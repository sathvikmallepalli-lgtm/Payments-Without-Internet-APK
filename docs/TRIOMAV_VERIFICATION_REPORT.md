# TrioMav Tech — Flowpay verification report

Date: 26 September 2026

Branch: `triomav/payment-verification`

## Incident and cause

On Airtel, a Canara Bank QR payment was authorized with a UPI PIN. The user
then closed the payment flow. Canara Bank completed the debit but sent no SMS
to that phone. In the previous code, `QRScannerActivity.terminateUSSDProcess`
closed the SMS window and `PaymentSessionManager.onUserCancelled` marked the
attempt `CANCELLED`. An attempt with no SMS could also be deleted at the
deadline or after a process restart. History therefore showed no usable
record of a bank debit.

## Changes made for future payments

- A payment attempt is stored before the dialer opens.
- After the dialer opens, exiting, a short call, or missing Android OFFHOOK
  signal leaves an `UNVERIFIED` attempt. A missing SMS no longer deletes it.
- History has a **Check payments** filter and tells the user to compare the
  payee, amount, time and bank reference with their bank app or statement.
- The SMS window remains open after a post-dial exit. A late SMS attaches to
  the attempt as `NEEDS_REVIEW`; it cannot turn that uncertain attempt into an
  automatic success. A startup race is retried against the preserved row to
  avoid a duplicate transaction.
- A dial failure or exit before dialing remains `CANCELLED`.
- User-facing copy no longer claims that pressing Cancel proves no money moved.

The earlier incident cannot be reconstructed automatically because the old
version did not retain its attempt and no bank SMS arrived. Check that
transaction directly in the Canara Bank app or statement.

## Checks completed

- Android debug build compiled.
- 159 debug unit tests passed; Android lint, Detekt and Kover verification
  passed.
- No physical Android device was attached to this workspace. The Airtel and
  Canara Bank flow still needs a small real payment test before release.

## Device checks to run

1. On the intended Airtel SIM and Canara account, make a small QR payment.
   After entering the PIN, close the Flowpay screen. Confirm a History >
   Check payments row appears as `UNVERIFIED` immediately.
2. If the bank sends no SMS, compare the bank app/statement with the attempt.
   Wait past the ten-minute deadline; the row must remain `UNVERIFIED`.
3. Repeat a controlled payment where a bank debit SMS does arrive after the
   Flowpay screen closes. Confirm the same row becomes `NEEDS_REVIEW`, with
   no second row and no green success claim.
4. Test an ordinary uninterrupted successful payment and a dial failure.
   These should respectively show the SMS result and `CANCELLED`.
5. Test on weak cellular service and on each supported carrier. Do not retry
   an uncertain payment until the bank's own status is checked.

## Product boundary

The current app works without mobile data when cellular voice/signalling is
available. It cannot send a payment without cellular service, or query bank
settlement without a PSP bank integration. TypeSafe AI can help classify
ambiguous support text later, but its confidence cannot verify money movement.
The phased product recommendations are in
[TRIOMAV_OFFLINE_ROADMAP.md](TRIOMAV_OFFLINE_ROADMAP.md).

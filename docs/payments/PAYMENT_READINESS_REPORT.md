# Payment readiness report

**Date:** 2026-10-05. **Scope:** what the backend can take payment for today, what online payment needs, and which decisions block it.
**Verdict:** **Cash on Delivery works end to end.** **Online (prepaid) payment is not implemented**, deliberately: payment was sequenced last, and every provider-facing step needs external decisions and credentials that do not exist yet. Nothing in this report is a verified deployment gate.

## 1. COD today: evidence
| Claim | Proof (`main` unless noted) |
|---|---|
| A customer can browse, sign in by OTP, save an address, fill a cart, take a quote, place a COD order, read it back, refresh and log out, all over HTTP | `CustomerJourneyE2EIT` (this PR) |
| Placement is one transaction: order `CONFIRMED` + reservation `CONSUMED` + stock moved once + cart cleared, or none of it | `OrderPlaceCodIT` (48 tests, forced driver retries, real concurrency, failure injection at every step) |
| Placement is idempotent per quote; a replay returns the stored order and its stored money | `OrderPlaceCodIT`, `OrderHttpIT` |
| Money is integer paise, computed server-side at placement from canonical prices and benefits, never from the client | `OrderMoneySnapshotTest`, `OrderBenefitsPlacementIT` |
| Errors are generic and leak nothing (no Mongo text, routing, PII) | `OrderHttpIT` |
| Order history, customer cancel with a one-shot restock (#64); staff out-for-delivery → delivered and staff cancel (#68); delivery slot hold (#63) | the respective PRs (open, CI green) |

`ConfirmedPaymentCondition.COD_DUE` records that the customer owes cash on delivery. It is a historical fact, not settlement: **COD collection is not recorded anywhere yet.**

## 2. Seams already in place for prepaid
- **`OrderStatus.CREATED`** (version 1) is produced by the internal `OrderService.createOrder`. It is paired with a `RESERVED` (not consumed) inventory reservation that carries a TTL and an expiry worker. A prepaid flow composes this: CREATED → (payment captured) → CONFIRMED (version 2, CAS 1→2) with the reservation consumed in the same transaction.
- **`PaymentMethod`** has only `COD`. Adding a constant is deliberately tied to adding its producer.
- **Supporting pieces:**
  - quotes expire;
  - the money snapshot is immutable;
  - the transactional notification outbox (N2) can carry "payment received" and "payment failed" messages;
  - the staff order console (#68) shows status.

## 3. What online payment requires (design, not built)
1. **Provider decision (external):** Razorpay, Cashfree, PayU or Juspay. It needs a merchant account, KYC, API keys, a webhook secret, settlement bank details and test/live mode separation.
2. **Payment domain:**
   - `payments` collection: one attempt per provider order, holding provider ids, amount, currency, status and timestamps. **No card, UPI VPA or bank data** (hosted checkout keeps PCI-DSS scope at SAQ-A).
   - `payment_webhook_events`, deduplicated by provider event id, with a TTL.
   - A `PaymentProvider` port with a disabled default, as with OTP, media and notifications.
3. **Flow:**
   - place → `CREATED` + `RESERVED` (reservation TTL ≥ payment window);
   - create the provider order server-to-server for the snapshot's payable amount (`receipt` = order id, idempotency key);
   - client SDK checkout;
   - **webhook** with HMAC signature over the raw body, idempotent, replay-safe, as the source of truth;
   - the client callback is only a hint and is re-verified server-side;
   - on capture: CAS `CREATED → CONFIRMED` and consume the reservation in one transaction;
   - on failure or timeout: release the reservation and mark the order `PAYMENT_FAILED` / `EXPIRED`.
4. **Refunds:** a cancel after capture (#64/#68 paths) creates a refund intent, reconciled by webhook. Refund turnaround follows RBI timelines.
5. **Reconciliation:** a daily provider settlement report compared against `payments`, with mismatch alerting.
6. **COD settlement:** a `COD_COLLECTED` event when a delivery is marked delivered (#68), plus per-rider cash reconciliation. This is a separate settlement domain that never rewrites `COD_DUE`.
7. **Security:**
   - keys and webhook secret held in the secret store;
   - amounts and currency never taken from the client;
   - logs never include payment tokens, VPAs or card fragments (the log-safety guard (#U) extends to payment field names);
   - the webhook endpoint is excluded from customer auth but signature-verified and rate-limited.
8. **Tax and invoicing:** GST invoice numbering and generation is a separate concern that prepaid and COD both need. It is a product and accounting decision.

## 4. Suggested PR sequence once unblocked
P1 payment domain + port + disabled provider + `CREATED→CONFIRMED` capture path (fake provider in tests) → P2 provider adapter + webhook → P3 refunds → P4 reconciliation import → P5 COD collection/settlement.

## 5. Blocking decisions and gates: all UNVERIFIED
- [ ] Payment provider chosen; merchant account and KYC complete
- [ ] Test and live keys plus webhook secret provisioned in the secret store
- [ ] Payment window and reservation TTL agreed (product)
- [ ] Refund policy (full or partial, who approves) agreed (product and finance)
- [ ] GST invoicing approach agreed (finance)
- [ ] COD cash-handling and reconciliation process agreed (operations)
- [ ] Legal: terms, refund policy page, grievance officer (RBI/consumer-protection requirements)

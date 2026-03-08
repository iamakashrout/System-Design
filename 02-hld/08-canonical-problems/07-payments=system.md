# Canonical Problem 7: Payments System

Design a system that processes online payments for an e-commerce platform.

**Example scenario:**

User buys an item → pays with card → system charges payment → confirms order.

---

## Step 1 — Clarify Requirements

### Functional Requirements

System should support:

- Accept payments (credit/debit cards, UPI, wallets)
- Authorize payment
- Capture payment
- Handle refunds
- Handle failed transactions
- Prevent duplicate charges

**Optional:**

- Subscriptions
- Invoices
- Fraud detection

### Non-Functional Requirements

Payments systems must be:

- Highly reliable
- Strongly consistent
- Secure
- Idempotent

**Latency requirement:**

- Payment confirmation < 2–5 seconds

**Important rule:**

Never lose or double charge money.

---

## Step 2 — Core Entities

### Payment

| Field | Description |
|---|---|
| `payment_id` | Unique identifier for the payment |
| `user_id` | Associated user |
| `order_id` | Associated order |
| `amount` | Payment amount |
| `currency` | Currency code |
| `status` | Current status |
| `created_at` | Timestamp |

### Transaction

Tracks interaction with payment provider.

| Field | Description |
|---|---|
| `transaction_id` | Unique identifier |
| `payment_id` | Associated payment |
| `provider` | Payment provider name |
| `provider_reference` | Provider's reference ID |
| `status` | Transaction status |
| `timestamp` | Timestamp |

### Refund

| Field | Description |
|---|---|
| `refund_id` | Unique identifier |
| `payment_id` | Associated payment |
| `amount` | Refund amount |
| `status` | Refund status |
| `created_at` | Timestamp |

---

## Step 3 — High-Level Architecture

```
Client
  |
API Gateway
  |
Payments Service
  |
Payment Provider
  |
Bank/Card Network
```

**Example providers:**

- Stripe
- Razorpay

---

## Step 4 — Payment Flow (Card Payment)

Typical flow:

```
User → Checkout → Payment Gateway → Bank → Confirmation
```

**Step-by-step:**

1. User clicks Pay
2. Frontend calls Payment API
3. Payment service creates payment record
4. Request sent to payment provider
5. Bank authorizes payment
6. Provider sends response
7. System marks payment SUCCESS

---

## Step 5 — Authorization vs Capture

Payments often use two-step processing.

### Authorization

Bank confirms funds are available. Funds are reserved.

**Example:**

> Rs. 1000 authorized

### Capture

Money is actually transferred.

**Example flow:**

```
Authorize → Ship order → Capture payment
```

**Benefits:**

- Prevents charging cancelled orders

---

## Step 6 — Idempotency (Critical)

Users may click Pay multiple times.

**Examples:**

- Double-click payment button
- Network retry
- Timeout retry

**Without protection:**

- User charged twice

**Solution:**

Idempotency keys.

**Example request:**

```
POST /payments
Idempotency-Key: 123abc
```

**Server behavior:**

- If key exists → return previous result

This prevents duplicates.

---

## Step 7 — Webhooks

Payment providers send asynchronous updates.

**Example:**

Payment success notification.

**Flow:**

```
Payment Provider
  |
Webhook
  |
Payments Service
```

**Example:**

```
POST /webhook/payment-success
```

**Webhooks handle:**

- Success
- Failure
- Refunds
- Disputes

---

## Step 8 — Database Design

Payments require strong consistency.

**Typical choice:**

Relational DB.

**Examples:**

- PostgreSQL
- MySQL

**Why?**

Transactions guarantee correctness.

**Example SQL transaction:**

```sql
BEGIN;
  -- Create payment record
  INSERT INTO payments (...) VALUES (...);

  -- Create transaction record
  INSERT INTO transactions (...) VALUES (...);
COMMIT;
```

---

## Step 9 — Failure Scenarios

Payments fail frequently.

### Common cases

**Network timeout — payment status unknown**

Solution: Check provider status API.

**Provider success but system failure**

Solution: Webhooks reconcile state.

**Duplicate webhook**

Solution: Idempotent webhook processing.

---

## Step 10 — Refund Flow

```
User requests refund
  |
Payments Service
  |
Provider Refund API
  |
Bank processes refund
```

Refunds may take 2–7 days. Status stored in DB.

---

## Step 11 — Fraud Detection

Large systems use fraud checks.

**Signals include:**

- Unusual location
- High amount
- Suspicious IP
- Velocity checks

**Companies use tools like:**

- Sift

Fraud detection runs before payment authorization.

---

## Step 12 — Ledger System (Important Concept)

Payments systems often maintain an internal ledger.

**Example balances tracked:**

- User balance
- Merchant balance
- Platform balance

**Ledger entry types:**

- Debit
- Credit

**Example:**

```
User     → -$100
Merchant → +$100
```

Ledgers ensure financial auditability.

---

## Step 13 — Security Requirements

Payments must follow strict security rules.

**Example standard:**

Payment Card Industry Security Standards Council (PCI-DSS).

**PCI-DSS ensures:**

- Card data encryption
- Secure storage
- Strict access control

Most companies never store raw card numbers. Instead they store tokenized card IDs.

---

## Step 14 — Scaling Strategy

Payments traffic is moderate compared to feeds.

**Example scale:**

100K–1M payments/day.

**Scaling approaches:**

- Horizontally scale API servers
- Queue non-critical tasks
- Use DB replicas for reads

Critical path remains simple.

---

## Step 15 — Observability

**Important metrics:**

- Payment success rate
- Payment latency
- Fraud rate
- Refund rate
- Provider error rate

Alerts are essential.

---

## Step 16 — Example End-to-End Flow

```
Client
  |
Checkout API
  |
Payments Service
  |
Create Payment Record
  |
Provider API
  |
Bank Authorization
  |
Webhook Confirmation
  |
Update Payment Status
  |
Order Service Notified
```

---

## Step 17 — Senior-Level Interview Summary

Strong interview answer:

> "I would design the payments service around a strongly consistent relational database, ensuring idempotency using idempotency keys. Payment authorization and capture are handled through a payment provider like Stripe. Webhooks reconcile asynchronous updates, and a ledger system records financial transactions for auditing. Strong security controls and PCI compliance are required to protect payment data."

### Key Interview Insight

The most important payments concepts:

- Idempotency
- Webhooks
- Ledger
- Authorization vs Capture
- Strong consistency

If you mention those, interviewers immediately know you understand payments.

---

## Architecture Diagram

```
                        +-------------------+
                        |      Client       |
                        |  (Browser / App)  |
                        +--------+----------+
                                 |
                                 | HTTPS
                                 v
                        +--------+----------+
                        |    API Gateway    |
                        | (Auth, Rate Limit)|
                        +--------+----------+
                                 |
                                 v
              +------------------+------------------+
              |                                     |
              v                                     v
   +----------+---------+               +----------+---------+
   |   Payments Service |               |   Order Service    |
   |                    |               |                    |
   | - Create Payment   |               | - Confirm Order    |
   | - Idempotency Key  | <-----------> | - Update Status    |
   | - Fraud Check      |   (Event /    |                    |
   | - Auth / Capture   |    Message)   +--------------------+
   | - Refund Handler   |
   +----+----------+----+
        |          |
        |          +---------------------------+
        v                                      v
+-------+----------+               +-----------+---------+
|  Relational DB   |               |  Payment Provider   |
|  (PostgreSQL)    |               |  (Stripe/Razorpay)  |
|                  |               |                     |
| - payments       |               | - Authorize         |
| - transactions   |               | - Capture           |
| - refunds        |               | - Refund API        |
| - ledger         |               | - Webhooks          |
+------------------+               +-----------+---------+
                                               |
                                               | Webhook Callback
                                               v
                                   +-----------+---------+
                                   |   Webhook Handler   |
                                   |                     |
                                   | - Idempotent        |
                                   | - Update DB         |
                                   | - Notify Services   |
                                   +---------------------+
                                               |
                                               v
                                   +-----------+---------+
                                   |   Bank / Card       |
                                   |   Network           |
                                   |                     |
                                   | - Authorization     |
                                   | - Settlement        |
                                   +---------------------+
```

### Component Summary

| Component | Role |
|---|---|
| API Gateway | Entry point; handles auth and rate limiting |
| Payments Service | Core logic: create, authorize, capture, refund |
| Relational DB | Strongly consistent storage for all payment state |
| Payment Provider | Third-party gateway (Stripe, Razorpay) |
| Webhook Handler | Reconciles async provider callbacks idempotently |
| Order Service | Notified on payment success to confirm the order |
| Bank / Card Network | Underlying financial infrastructure |

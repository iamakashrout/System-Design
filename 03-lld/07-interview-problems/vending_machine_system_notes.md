# Vending Machine System — Design Notes

**Phase 6, Problem 12 (final)** | Companion file: `VendingMachineSystem.java`

---

## 1. Problem Intuition

A vending machine looks like a trivial state machine — insert money, press
button, get product. Two things make it genuinely interesting:

**The dual-validation problem.** Selecting a product requires two simultaneous
conditions to hold: the slot must be in stock *and* sufficient money must have
been inserted. Either failure has a different recovery path:
- Out of stock → the customer might try another slot (money stays in).
- Insufficient funds → insert more money (machine waits).
- In both cases, the session continues — the machine doesn't eject the money.

**The change-before-dispensing constraint.** Unlike an ATM that can refuse a
withdrawal after the fact, a vending machine must verify it can return exact
change *before* dispensing the product. Dispensing happens first; if the
machine then discovered it couldn't make change, it would have already
given away the product. The check must be a dry-run before the point of no
return.

---

## 2. Core Modeling Decisions

### 2.1 Product vs VendingSlot — same split as Book vs BookCopy

`Product` is a catalog entry: id, name, price, category. It has no count.
`VendingSlot` is a physical slot: it holds one Product type and a quantity.

This mirrors the `Book` / `BookCopy` split from Problem 9:

| Library (Problem 9) | Vending Machine (Problem 12) |
|---|---|
| `Book` (catalog identity) | `Product` (catalog identity) |
| `BookCopy` (physical instance, with state) | `VendingSlot` (physical slot, with count) |
| `CopyStatus` state machine | `quantity` counter |
| `Library.borrowBook()` calls `copy.tryTransition(BORROWED)` | `vm.selectProduct()` calls `slot.dispense()` |

The split is the right call whenever the catalog concept and the physical
inventory concept have different responsibilities. Product knows what the thing
*is*; VendingSlot knows how many are *available*.

### 2.2 State machine

```
IDLE             → MONEY_INSERTED   (any valid money inserted)
MONEY_INSERTED   → MONEY_INSERTED   (more money inserted — self-loop)
MONEY_INSERTED   → PRODUCT_SELECTED (slot in stock, enough funds, change feasible)
MONEY_INSERTED   → IDLE             (refund requested)
PRODUCT_SELECTED → DISPENSING       (auto — product dispensed)
DISPENSING       → CHANGE_DISPENSED (auto — change returned, if any)
DISPENSING       → IDLE             (auto — exact payment, no change needed)
CHANGE_DISPENSED → IDLE             (auto)
```

`DISPENSING` and `CHANGE_DISPENSED` are transient states. They exist for the
same reason as in the ATM (Problem 11): auditability, explicit lifecycle
documentation, and correctness if any future concurrency concern arises (e.g.
a "dispenser jammed" interrupt). The auto-transitions happen synchronously in
`ProductSelectedState.dispenseProduct()` for simplicity.

### 2.3 Default methods on VendingState — identical pattern to ATMState

The interface carries default `throw` for every operation. Concrete states
only override what is valid in their state. `IdleState` overrides
`insertMoney` and (no-op) `refund`. `MoneyInsertedState` overrides all three.
`ProductSelectedState`, `DispensingState`, and `ChangeDispensedState` override
nothing (or only internal helpers) — all user operations correctly throw by
default.

---

## 3. Pattern Decisions

### 3.1 Strategy axis 1 — PaymentStrategy

`PaymentStrategy.acceptMoney(cents)` validates an insertion attempt.
Throws `InvalidCoinException` if unacceptable.

- `CoinPaymentStrategy` — only accepts amounts matching a known `Coin`
  denomination (5, 10, 25, 50, 100, 200 cents). Rejects 3¢ or 7¢. Models
  a physical coin acceptor with a mechanical validator.
- `CashPaymentStrategy` — accepts any positive integer of cents. Models a
  bill validator or a stored-value card reader.

The machine doesn't care which payment type it has — it calls `acceptMoney`
and either proceeds or catches `InvalidCoinException`. New payment types
(NFC tap, QR code, loyalty points) plug in without touching the state machine.

### 3.2 Strategy axis 2 — ChangeDispensingStrategy

`ChangeDispensingStrategy.calculateChange(amountCents, available)` returns
a coin breakdown or throws `ExactChangeUnavailableException`.

- `GreedyChangeStrategy` — largest coin first, O(D) where D = denomination
  types. Simple, correct for standard coin sets (200, 100, 50, 25, 10, 5).
- `ExactChangeStrategy` — bounded 0-1 knapsack DP, same algorithm as
  Problem 11's `MinNotesDispensingStrategy`. Each physical coin is a
  separate 0-1 item; backward scan enforces supply constraints.

**The key difference from ATM's cash dispensing:** Change is *mandatory*.
The ATM's `MinNotesDispensingStrategy` was a "nice to have" — use fewer
bills. The vending machine's `ExactChangeStrategy` is about *correctness*:
if you cannot make exact change, the sale must be refused before the product
leaves the slot.

### 3.3 Factory — ProductFactory

`ProductFactory.create(name, priceCents, category)` generates a `Product`
with a category-prefixed id (SN001, DR002, CA003…). Clients ask for a
product by description, not by id. The id generation convention and category
prefixes are encapsulated here.

---

## 4. The change-before-dispensing check — critical ordering

The sequence in `MoneyInsertedState.selectProduct()` is:

```
1. findSlot(slotCode)         — slot exists?
2. slot.isInStock()           — in stock?
3. insertedCents >= price     — enough money?
4. changeStrategy.calculateChange(changeDue, bin) — can make change?  ← dry-run
5. setState(PRODUCT_SELECTED)
6. slot.dispense()            — point of no return
7. coinBin.remove(change)     — pay out change
8. resetSession(), setState(IDLE)
```

Step 4 is a **dry-run**: it computes the change allocation against the
current bin snapshot but does not remove any coins. If it throws, the
session aborts at step 4 — the product is never dispensed, money is
returned via `refund()`. Only if step 4 succeeds does the sale commit.

This is the vending machine equivalent of the TOCTOU concern in the Hotel
Booking System (Problem 10): the gap between "checking" and "acting" must
be closed. Here, the "act" (dispensing) is irreversible, so the check must
precede it with no gap. Since `VendingMachine` is `synchronized`, there is
no concurrent interleaving between the dry-run and the actual removal — the
guarantee holds in a single-session machine.

---

## 5. Concurrency model

`VendingMachine` public methods are `synchronized` — same rationale as the
ATM. A vending machine serves one customer at a time. The synchronization
prevents a hypothetical concurrent access scenario (e.g. a network-connected
machine receiving a remote vend request while a customer is mid-session).

`CoinBin` operations are `synchronized` on the bin object for internal
consistency. Since `VendingMachine` methods are already synchronized, no
thread can concurrently modify the bin through two different sessions — but
the bin's own synchronization is defensive for any future direct access.

No deadlock is possible: only one lock is ever held at a time (either
VendingMachine's intrinsic lock or CoinBin's).

---

## 6. Complexity Analysis

| Operation | Time | Notes |
|---|---|---|
| `insertMoney()` | O(1) | Coin validation + bin update |
| `selectProduct()` — stock + funds check | O(1) | Map lookup + comparison |
| `GreedyChangeStrategy` | O(D) | D = coin denominations = 6, effectively O(1) |
| `ExactChangeStrategy` | O(A × C) | A = amountCents / 5, C = total coin count in bin |
| `ProductFactory.create()` | O(1) | Static counter + string format |
| `ProductCatalog.findSlot()` | O(1) avg | HashMap lookup by slot code |
| `refund()` | O(D) | Greedy over the inserted amount |

---

## 7. Trade-offs and Explicit Non-Decisions

- **Inserted coins go into the float immediately.** When a customer inserts a
  quarter, it's added to the `CoinBin` right away. This means the machine
  could theoretically use the customer's own just-inserted coin to make their
  change. In practice this is fine (and real vending machines work this way),
  but it means refunds are paid from the float — if the float is empty, exact
  refund fails. Documented as an intentional design choice.
- **Refund is best-effort.** `MoneyInsertedState.refund()` catches
  `ExactChangeUnavailableException` and prints a warning rather than crashing.
  Real machines would call a service tech if they can't make change for a
  refund. This fallback is noted as a simplification.
- **No partial refund.** If the machine can't return the full inserted amount
  in exact coins, the current implementation warns and does nothing — real
  machines would return the largest amount possible in available coins.
- **Single machine, no network.** No remote vend request, no mobile payment
  confirmation round-trip. Adding these would introduce async state transitions
  (same concern as ATM network authorization).
- **No maintenance mode.** Real machines have a service mode (MAINTENANCE
  state) for restocking and coin collection. Adding it as a sixth state
  would follow the same pattern — one new class, no changes to existing states.

---

## 8. Extension Possibilities

- **MAINTENANCE state.** A `MaintenanceState` that only a technician can enter
  (via a physical key or admin code). Allows restocking (`slot.restock()`) and
  coin bin refill (`coinBin.add()`). Transitions: any state → MAINTENANCE
  (technician key); MAINTENANCE → IDLE (key removed).
- **Partial refund.** Extend `MoneyInsertedState.refund()` to greedily return
  as many coins as possible when the exact amount can't be made, and log
  the underpaid remainder as a "debt" for the next service visit.
- **Multiple selection.** Allow a customer to select products until their
  balance is exhausted, then request change at the end. Requires a
  "shopping cart" in the session state.
- **Pluggable product display.** Add a `DisplayObserver` (Problem 7's Observer
  pattern) that subscribes to inventory events and updates an LED panel when
  a slot goes out of stock.
- **Loyalty / discount strategy.** Add a `DiscountStrategy` as a third Strategy
  axis: `applyDiscount(product, insertedCents) → int effectivePrice`. Composed
  into `selectProduct()` alongside payment and change strategies.

---

## 9. Phase Connections

| Phase | Concept | Where it appears here |
|---|---|---|
| 1 — OOP & SOLID | SRP, OCP, DIP | `Product` knows what; `VendingSlot` knows how many; `VendingMachine` depends on `VendingState`, `PaymentStrategy`, `ChangeDispensingStrategy` interfaces. |
| 2 — Object modeling | Catalog vs inventory split | `Product` / `VendingSlot` mirrors `Book` / `BookCopy` from Problem 9 — the catalog-identity vs physical-inventory modeling pattern generalises across domains. |
| 3 — Design patterns | State, Strategy ×2, Factory | `VendingState` + 5 states (State); `PaymentStrategy` (Strategy); `ChangeDispensingStrategy` (Strategy); `ProductFactory` (Factory). |
| 3.5 — Enums & state machines | Rich enum for coins | `Coin` enum carries `valueCents` as per-constant data, with `descending()` and `fromCents()` static helpers — config-carrying enum pattern from Problems 10–11 applied to coins. |
| 4 — Concurrency | Synchronized context, defensive CoinBin | Same single-session synchronization rationale as ATM (Problem 11). |
| 5 — Algorithms | Bounded 0-1 knapsack DP | `ExactChangeStrategy` is the same algorithm as `MinNotesDispensingStrategy` in Problem 11, confirming it as a reusable primitive for exact-change problems. |
| 6 — Generalizable idioms | Change-before-dispensing ordering | The "dry-run before point of no return" pattern generalises: whenever an action is irreversible, validate all downstream steps *before* committing. |
| 7 — Best practices (upcoming) | DI | All three strategies plus the catalog injected into `VendingMachine` at construction — all seams ready for Phase 7's testability focus. |

---

## 10. Demo Scenarios (in `main()`)

1. **Exact payment** — Kit Kat at $1.00, insert $1.00 → no change dispensed.
2. **Overpayment with change** — Lays at $1.50, insert $2.00 → 50¢ change
   via `GreedyChangeStrategy`.
3. **Insufficient funds then top-up** — try Coke at $2.00 with only $1.00;
   `InsufficientFundsException` thrown; insert another $1.00; sale proceeds.
4. **Out of stock** — Kit Kat already sold in scenario 1; `OutOfStockException`
   thrown; refund the waiting amount.
5. **Refund mid-session** — insert 50¢ then request refund; coins returned.
6. **Invalid coin** — insert 3¢; `CoinPaymentStrategy` throws `InvalidCoinException`.
7. **Invalid operation in IDLE** — `selectProduct()` before any money inserted;
   `VendingState` default method throws `InvalidOperationException`.
8. **ExactChangeStrategy (DP)** — separate machine with no quarters; 25¢
   change needed; DP finds dime + dime + nickel = 25¢.
9. **Exact change unavailable → sale refused** — machine with empty coin bin
   cannot make 50¢ change; dry-run throws; product is never dispensed;
   customer refunded.

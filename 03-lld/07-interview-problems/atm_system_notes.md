# ATM System — Design Notes

**Phase 6, Problem 11** | Companion file: `ATMSystem.java`

---

## 1. Problem Intuition

The ATM is the textbook GoF State pattern example — and for good reason.
The machine's behavior changes *completely* depending on where it is in its
lifecycle:

- **IDLE**: only inserting a card makes sense.
- **CARD_INSERTED**: only entering a PIN or cancelling makes sense.
- **PIN_ENTERED**: only selecting a transaction or cancelling makes sense.
- **TRANSACTION / DISPENSING**: no user input is accepted.

Without State, every public method (`insertCard`, `enterPin`, `requestWithdrawal`,
`cancel`, …) would carry the same giant `if (state == IDLE) { ... } else if
(state == CARD_INSERTED) { ... }` guard. Each new state means editing every
method. That is both fragile (easy to miss a case) and closed to extension
(adding a state forces touching the whole class).

With State, adding a new state means adding one class. No existing code changes.

---

## 2. The Key Design Choice — Full GoF State Classes vs Rich Enums

Problems 7–10 used a **rich enum** for state machines: enum constants
overriding `canTransitionTo(Target)`, and a single `transitionTo()` method on
the entity. That pattern is right when:

- The only meaningful behavior per state is "is this transition legal?"
- The state machine's purpose is to guard writes, not to execute complex logic.

The ATM crosses a different threshold: each state has *substantial, distinct
behavior*. `CardInsertedState` tracks PIN retry counts. `DispensingState`
blocks all user input and owns the "dispensing complete" lifecycle hook.
`PinEnteredState` creates a `TransactionCommand` and hands it to `ATMContext`
for execution. These behaviors cannot be cleanly encoded as enum constants
overriding one method — they need classes.

**The practical test:** if each "state" would need more than one override, or if
the overrides contain significant logic rather than just returning true/false,
class-per-state is the right call.

---

## 3. Key Design Decisions

### 3.1 ATMState default methods — turning the anti-pattern around

The naive implementation has each concrete state repeat the same boilerplate
for every *invalid* operation:

```java
// Naive — repeated in every state:
@Override
public void requestWithdrawal(ATMContext atm, double amount) {
    throw new InvalidOperationException("Cannot withdraw in " + getStateName());
}
```

With Java interface default methods, the rejection logic lives *once* on the
interface. Concrete states only override what is *valid* in their state.
`IdleState` overrides `insertCard` and nothing else. The interface provides
all the rejections for free.

This is not just cleaner — it's more correct. New operations added to the
interface automatically reject in all states that don't override them, with
no chance of a state forgetting to add a guard.

### 3.2 The transient states (TRANSACTION, DISPENSING)

`TransactionState` and `DispensingState` are brief: the user never types
anything while in either one. They exist as *real states* because:

- **Auditability**: the log can record "transaction began at T₁, dispensed at T₂,
  card ejected at T₃" — timestamps across distinct states.
- **Correctness under concurrent pressure**: if (hypothetically) another thread
  could call `cancel()` while the machine is mechanically dispensing, it should
  be rejected. `DispensingState` does that by default.
- **Explicitness**: the state machine's contract is self-documenting. A maintainer
  reading the code sees exactly which states exist and what transitions are legal,
  without having to reverse-engineer a flag-based flow.

`DispensingState` has one extra method — `dispensingComplete()` — called by
`ATMContext` once the mechanical step finishes. This is not on the `ATMState`
interface (it's not a user operation), so it's a concrete method on the class.
`ATMContext.executeCommand()` upcasts to `DispensingState` to call it — acceptable
because ATMContext itself created the `DispensingState` object one line earlier.

### 3.3 Command — TransactionCommand

`TransactionCommand.execute(account, cashBin, strategy)` returns a
`TransactionRecord` or throws. Three implementations:

- `WithdrawalCommand` — debits account, calls dispensing strategy, removes bills
  from the bin, returns a record with the denomination breakdown.
- `DepositCommand` — credits account, returns a record.
- `BalanceInquiryCommand` — pure read, returns a record with current balance.

`requiresDispensing()` is a boolean on the interface. `ATMContext.executeCommand()`
uses it to decide whether to pass through `DispensingState` or go straight to
`IDLE`. This keeps dispensing-vs-no-dispensing logic in the context, not scattered
across callers.

`WithdrawalCommand` debits the account *before* calling `cashBin.remove()`.
This is intentional: if the bin check fails after a successful debit, the account
has been charged but no cash dispensed. A real system would wrap this in a
transaction and rollback the debit on bin failure. For this model, the failure
path logs the error and the session ends. This is an explicitly documented
trade-off, not an oversight.

### 3.4 Strategy — CashDispensingStrategy

Two implementations with meaningfully different algorithms:

**`GreedyDispensingStrategy`** — largest denomination first, as many as possible,
then move down. O(D) where D = number of denomination types. Simple, fast, and
optimal for standard ATM denominations (200, 100, 50, 20, 10) because the greedy
choice equals the minimum-bills choice for any multiple-of-10 amount with this
specific denomination set.

**`MinNotesDispensingStrategy`** — bounded coin-change DP. Correct even when the
bin has an unusual mix that defeats greedy (e.g., only $20s and $10s available
for a $70 withdrawal: greedy gives {$20×3, $10×1}, which happens to be optimal
here, but for denomination sets like {$30, $20} the gap is real). Complexity:
O(amount × total_bill_count). Acceptable for ATM withdrawal amounts (< 10,000
in practice) and typical bin sizes.

**Why bounded, not unbounded?** Greedy ignores supply limits and just takes as
many of each denomination as it wants. MinNotes uses a **bounded 0-1 knapsack**:
each physical bill is treated as a separate item. A backward scan per bill
ensures the same bill is never counted twice, correctly modeling finite supply.

```
For each denomination, for each bill of that denomination (count passes):
    Backward scan dp[amount..val]:
        dp[i] = min(dp[i], dp[i - val] + 1)
        from[i] = denomination used
```

The `from[]` array enables O(amount) reconstruction: trace back from
`from[amount]`, subtract the denomination's value, repeat until 0.

### 3.5 CashBin concurrency

`CashBin` uses `synchronized` methods throughout. Two operations need
atomicity:

- `getAvailableCounts()` returns a defensive copy (a snapshot) — this is what
  `CashDispensingStrategy` operates on. The strategy computes an allocation plan
  against the snapshot; the plan is then applied via `remove()`.
- `remove()` does a two-phase check-then-apply under the same lock: first verify
  all denominations have sufficient supply, then deduct. This prevents a race
  where two withdrawals concurrently drain the same denomination below zero.

The gap between `getAvailableCounts()` and `remove()` (bridged by the strategy
calculation) is acceptable: the strategy cannot reserve bills. If the bin changes
between snapshot and removal, `remove()` catches the shortfall and throws.
In a real system, the lock would cover both steps — but that would force the
dispensing algorithm to run inside the synchronized block, which is undesirable
for a CPU-heavy DP. The current design is correct for single-session ATMs and
a reasonable trade-off documented here.

### 3.6 PIN retry tracking

`pinAttempts` is a field on `ATMContext`, not on `CardInsertedState`. This
matters because `CardInsertedState` is a fresh object every time a card is
inserted — storing attempts in it would reset them between re-entries to that
state. `ATMContext` is the durable session holder; per-session counters belong
there. `resetPinAttempts()` is called explicitly when a new card is inserted
and when a PIN is accepted.

---

## 4. Concurrency Model

ATM operations are `synchronized` on `ATMContext`. This is appropriate because:

- An ATM is a single-session device. "Concurrent" users are not a meaningful
  concept — only one card is in the machine at a time.
- The synchronized methods prevent race conditions if, hypothetically, a
  network callback (e.g. bank authorization response) and a user button press
  arrived simultaneously.

Two subsidiary synchronization concerns are independent:

| Concern | Mechanism | Scope |
|---|---|---|
| Account balance | `synchronized` on `BankAccount` | Protects `debit` / `credit` from concurrent bank-side updates |
| Cash bin counts | `synchronized` on `CashBin` | Protects denomination counts; `remove()` is a check-then-act under one lock |

These two locks are never acquired simultaneously (no method holds both),
so deadlock is structurally impossible.

---

## 5. Complexity Analysis

| Operation | Time | Notes |
|---|---|---|
| State transitions | O(1) | Field assignment on ATMContext |
| Greedy dispensing | O(D) | D = denomination types = 5, effectively O(1) |
| MinNotes dispensing | O(A × C) | A = amount / 10 (units), C = total bill count in bin |
| MinNotes reconstruction | O(A) | Trace-back via `from[]` array |
| CashBin.remove() | O(D) | Scan + deduct for each denomination in the allocation |
| Transaction log | O(1) add, O(n) print | n = total log entries |
| Bank account lookup | O(1) avg | HashMap by card number |

---

## 6. Trade-offs and Explicit Non-Decisions

- **Debit-before-bin-check in WithdrawalCommand.** If the bin is short after the
  debit succeeds, the account has been charged and the session ends in error.
  A production system wraps both operations in a transaction (or applies the bin
  check first). Documented explicitly — this is a deliberate model simplification.
- **No card network / bank authorization round-trip.** PIN is validated locally
  against the stored PIN. Real ATMs call an external authorization host (Visa/MC
  network) which adds an asynchronous step and retry logic — a system design
  concern beyond LLD scope.
- **PIN stored in plain text.** The model stores PIN directly on `BankAccount`
  for readability. Production systems hash with bcrypt/Argon2 and never store
  cleartext PINs.
- **No card de-skimming check, no card-present verification.** Physical security
  concerns are out of scope for an LLD model.
- **`DispensingState` completes immediately.** In reality, dispensing has a
  mechanical duration (≈ 4–8 seconds). A real system would have an async callback
  from the dispenser hardware; the DISPENSING state would block until that event
  arrives. Modeled as instantaneous for the demo.
- **Single cash bin.** Real ATMs have multiple cassettes per denomination, with
  individual counts. `CashBin` is a flat map — adding cassette granularity would
  not change the interface, only the internals of `load` and `remove`.

---

## 7. Extension Possibilities

- **Network authorization.** Add an `AuthorizationService` interface with a
  `requestAuthorization(account, amount) → AuthorizationResult` method. Call it
  from `WithdrawalCommand.execute()` before the debit. `DispensingState` would
  need to become async (e.g., `CompletableFuture`-based).
- **Multiple sessions / session timeout.** Add a `SessionTimeoutState` that
  auto-cancels after N seconds of inactivity. A `ScheduledExecutorService`
  could call `atm.cancel()` on timeout.
- **Denomination preference per withdrawal.** Add a
  `PreferSmallBillsStrategy` that inverts the greedy order — useful when high-
  denomination cassettes are nearly empty and the operator wants to drain small
  bills first.
- **Audit trail persistence.** Add a `TransactionRepository` interface
  (database-backed in production, in-memory in tests) — another DI seam for
  Phase 7's testability focus.
- **Card swallow on multiple retain events.** Track retain count per card
  number; after N retains, flag as permanently blocked at the bank level.

---

## 8. Phase Connections

| Phase | Concept | Where it appears here |
|---|---|---|
| 1 — OOP & SOLID | SRP, OCP | Each state class has one responsibility; adding a new state doesn't touch existing classes; `ATMContext` depends on `ATMState` and `CashDispensingStrategy` interfaces. |
| 2 — Object modeling | Composition | `ATMContext` holds `ATMState`, `CashBin`, `Bank`, `TransactionLog` — all by composition. The "current state" reference is swapped at runtime, which is the defining characteristic of the GoF Context. |
| 3 — Design patterns | State, Command, Strategy, Facade | `ATMState` + 5 classes (State); `TransactionCommand` + 3 classes (Command); `CashDispensingStrategy` + 2 classes (Strategy); `ATMContext` as the coordinating Facade. First problem combining all three behavioral patterns simultaneously. |
| 3.5 — Enums & state machines | Rich enum vs class-per-state | Explicit contrast drawn: Problems 7–10 used rich enums; Problem 11 uses class-per-state because state behavior is too complex for a single method override. Both approaches are now understood with clear selection criteria. |
| 4 — Concurrency | Synchronized context, independent locks | `ATMContext.synchronized` for session serialization; separate `synchronized` on `BankAccount` and `CashBin`; structural guarantee of no deadlock. |
| 5 — Algorithms | Bounded 0-1 knapsack DP | `MinNotesDispensingStrategy` is the first non-trivial algorithm in this series: backward-scan per bill to enforce supply constraints, `from[]` array for O(amount) reconstruction. |
| 7 — Best practices (upcoming) | DI | `CashDispensingStrategy` injected into `ATMContext`; `Bank` and `TransactionLog` injected — all seams ready for Phase 7 testability focus. |

---

## 9. Demo Scenarios (in `main()`)

1. **Normal withdrawal** — insert, PIN, withdraw $250, denomination breakdown logged.
2. **Balance inquiry** — insert, PIN, balance inquiry, no cash dispensed, card ejected.
3. **Deposit** — insert, PIN, deposit $300, balance updated.
4. **Wrong PIN then correct PIN** — one wrong attempt, then correct; proceeds to withdrawal.
5. **Card retention** — three consecutive wrong PINs; card flagged as retained; subsequent insert of the same card is rejected.
6. **Insufficient funds** — Bob's balance too low for a $1000 withdrawal; session ends cleanly.
7. **Invalid operations in IDLE** — `enterPin()` and `requestWithdrawal()` both throw `InvalidOperationException` via ATMState's default methods.
8. **Cancel mid-session** — card inserted, PIN entered, then cancel; ATM returns to IDLE.
9. **MinNotes strategy** — separate ATM instance with a limited bin ($20×3, $10×5); withdraws $70 via DP allocation.

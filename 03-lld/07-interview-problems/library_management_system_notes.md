# Library Management System — Design Notes

**Phase 6, Problem 9** | Companion file: `LibraryManagementSystem.java`

---

## 1. Problem Intuition

Strip the surface complexity of a library away and two hard problems remain:

> **Problem A — Concurrent copy claiming.** When five members all search for
> the last available copy of a popular book at the same moment, exactly one
> should succeed — without a global lock freezing every other borrow in the
> system.
>
> **Problem B — Reservation hand-off.** When a borrowed copy is returned and
> a member is waiting, the copy must atomically move from the borrower to the
> next person in the queue — never surfacing as "generally available" in the
> gap, even if two copies of the same book are returned concurrently with only
> one reservation pending.

Everything else — search indices, fine calculation, state machine — is
important but not *hard*. The design is built outward from these two
concurrency constraints, and both are solved by the same two primitives that
appeared in the Ride Sharing System (Problem 8): `AtomicReference.compareAndSet`
for individual resource claiming, and `ConcurrentLinkedQueue.poll()` for
atomic hand-off.

---

## 2. Core Modeling Decision — Book vs BookCopy split

This is the most important structural choice in the problem.

A naive model makes `Book` the borrowable thing. That immediately breaks down:
a library owns three copies of *Designing Data-Intensive Applications* — the
same book, three independently borrowable objects with independent lifecycle
states. The catalog entry (title, author, ISBN) and the physical item (this
specific spine on this specific shelf) are fundamentally different things.

| Responsibility | Class |
|---|---|
| Catalog identity — isbn, title, author | `Book` |
| Reservation queue for a title | `Book` |
| Physical lifecycle — AVAILABLE/BORROWED/RESERVED/LOST | `BookCopy` |
| Concurrently claimed status field | `BookCopy.status` (AtomicReference) |

`Book` owns a `List<BookCopy>` (synchronized) and a
`ConcurrentLinkedQueue<String>` for reservation member IDs. `BookCopy` owns
its `AtomicReference<CopyStatus>` and a `volatile String reservedForMemberId`.

This split maps directly onto how real library software thinks: the ILS (Integrated
Library System) has a bib record (the Book) and item records (the BookCopies).

---

## 3. Key Design Decisions

### 3.1 State — `CopyStatus` as a rich enum

```
AVAILABLE → BORROWED, LOST
BORROWED  → AVAILABLE, RESERVED, LOST
RESERVED  → BORROWED, AVAILABLE, RESERVED   (self-transition: hold advances to next member)
LOST      → AVAILABLE                        (librarian's "found it" override)
```

Each enum constant overrides `canTransitionTo(CopyStatus target)`. `BookCopy.tryTransition()`
reads the current status, validates legality, then CASes. This is the same approach used
for `RideStatus` in Problem 8 and `NotificationStatus` in Problem 7 — the transition
table *is* the state machine. No separate validator class to drift out of sync with the
enum.

**RESERVED → RESERVED** is worth calling out: when a pickup hold expires and the next
member in the queue picks it up, the copy moves from "held for Alice" to "held for Bob"
without ever becoming AVAILABLE. Permitting the self-transition means `returnBook()` can
call `tryTransition(RESERVED)` on an already-RESERVED copy when advancing the hold —
no special case needed.

**BORROWED → LOST** allows a librarian to mark a currently-on-loan copy lost. The active
loan is closed, the member is charged a replacement fee, and the copy leaves circulation —
without first forcing a return.

### 3.2 Strategy — fine calculation

`FineCalculationStrategy` is a single-method interface: `calculateFine(long daysOverdue)
→ double`. Two implementations:

- `FlatRateFineStrategy` — `ratePerDay × daysOverdue`. Simple, predictable.
- `TieredFineStrategy` — first `weekThreshold` overdue days at `earlyRate`, every day
  after at `escalatedRate`. The constructor makes the policy explicit:
  `new TieredFineStrategy(7, 0.25, 1.00)` reads "seven days at 25 cents, then a dollar a
  day." This is not a decoration of another strategy (unlike `SurgePricingFareStrategy`
  in Problem 8) — it is an independent algorithm with its own parameters.

The strategy is injected into `LibraryService` at construction, which means swapping from
flat-rate to tiered (or any future `WeekendSurchargeStrategy`) requires no change to the
facade — Dependency Inversion in action.

### 3.3 Observer — reservation notifications, scoped deliberately narrowly

`ReservationObserver` has three hooks:

```java
void onReservationCreated(Member member, Book book);
void onBookAvailableForPickup(Member member, Book book, BookCopy copy);
void onReservationExpired(Member member, Book book);
```

Two implementations:

- `MemberNotificationObserver` — prints pickup alerts to the member.
- `LibrarianAuditObserver` — logs the same reservation events *through the interface*,
  plus two direct methods (`logCopyLost`, `logOverdueReturn`) called straight from
  `LibraryService` via a type-cast helper (`auditDirect`).

**Why not add `onCopyLost` and `onOverdueReturn` to the interface?**
Because those are librarian audit concerns, not reservation notification events —
they have nothing to do with the reservation lifecycle the interface is named for.
Stretching the interface to cover them would violate both ISP (forcing
`MemberNotificationObserver` to implement methods it doesn't use) and SRP (the
interface would now be about two unrelated concerns). The deliberate trade-off is
accepting a direct call to a concrete subtype for non-reservation audit events,
documented explicitly, rather than polluting a clean interface.

### 3.4 Catalog — read-heavy index design

```java
ConcurrentHashMap<String, Book>                       isbnIndex       // 1:1
ConcurrentHashMap<String, CopyOnWriteArrayList<Book>> titleIndex      // 1:N
ConcurrentHashMap<String, CopyOnWriteArrayList<Book>> authorIndex     // 1:N
```

The value type for title/author indices is `CopyOnWriteArrayList<Book>`, not
`ArrayList`. The access pattern makes this the right call: searches (reads)
happen many times per second, while new books (writes) are added rarely by
librarians. `CopyOnWriteArrayList` gives lock-free reads at the cost of
copy-on-write overhead on every add — exactly the right trade-off when the
read/write ratio is heavily skewed toward reads.

### 3.5 Facade — `LibraryService`

Single entry point for all client actions. Holds five `ConcurrentHashMap` registries
(members, librarians, books via Catalog, copy index, active-loan-by-copy-id) plus the
injected fine strategy and observer list (`CopyOnWriteArrayList<ReservationObserver>`).

The copy index (`ConcurrentMap<String, BookCopy>`) is `LibraryService`-owned, not
`Book`-owned, because operations like `returnBook(copyId)` start with just a copyId —
they need an O(1) path from copyId to both the `BookCopy` and its parent `Book`
(via the copy's isbn field). Without the index, every return would scan all books.

---

## 4. Concurrency — the core engineering decisions

| Concern | Mechanism | Why |
|---|---|---|
| Concurrent borrow (copy claim) | `AtomicReference<CopyStatus>` + `compareAndSet(AVAILABLE, BORROWED)` | Lock-free, per-copy granularity. A CAS loss on one copy triggers "try the next candidate copy" — not a block, not an exception. |
| Reservation hand-off on return | `ConcurrentLinkedQueue.poll()` — inherently atomic | When two copies of the same book are returned concurrently with one reservation pending, exactly one `poll()` wins the non-null member ID. The other sees null and releases to AVAILABLE. No extra synchronization needed. |
| Fine accumulation | `synchronized` methods on `Member` (`addFine`, `payFine`, `getOutstandingFines`) | Fine updates are low-frequency (one per return or loss event). A monitor lock is right-sized here — unlike the high-frequency revenue counter in the Ride Sharing System (Problem 8), which justified `DoubleAdder`. Using `DoubleAdder` for fines would be over-engineering with no throughput benefit. |
| Active loan registry | `ConcurrentHashMap<String, Loan>` (`activeLoanByCopyId`) | `remove(copyId)` on return is atomic — the loan either belongs to this thread or it doesn't, with no race between lookup and removal. |
| Observer list | `CopyOnWriteArrayList<ReservationObserver>` | Observers are registered once at startup and fired many times during operation — same read-heavy pattern as the catalog indices. |

**The CAS claim loop, applied for the second time:** In `borrowBook()`, the
per-copy claim pattern is:

```
for each candidate copy with status == AVAILABLE:
    if copy.tryTransition(BORROWED):   // CAS
        return createLoan(copy, member)
    // CAS lost — another thread took this copy in the gap. Try the next one.
throw NoCopyAvailableException
```

This is the same idiom as driver claiming in Problem 8 (`AVAILABLE → EN_ROUTE_TO_PICKUP`),
now applied at the `BookCopy` level. The insight generalises: *any time multiple
concurrent actors race to claim one of a pool of independently lockable resources, this
pattern gives you exactly-once claiming without a global lock.*

The pattern works because the two primitives compose cleanly:
- The **read** (`status == AVAILABLE`) is an optimistic pre-check — cheap, unguarded.
- The **CAS** is the actual atomic claim — either wins the resource or doesn't.
- A lost CAS means "retry on the next candidate," not "block and wait."

**Why not synchronize on the `Book` during claiming?** It would work, but would
serialize all borrow attempts for a given title — 50 members trying to borrow copies of
the same popular book would form a queue behind one lock, even though each of them is
contending for a *different* physical copy. CAS lets them race in parallel and only
serializes at the level that actually matters: one specific copy's status field.

---

## 5. Complexity Analysis

| Operation | Time | Notes |
|---|---|---|
| `borrowBook()` | O(k) where k = copies of this isbn | Scans the copy list twice: once for a RESERVED-for-me copy, once for any AVAILABLE. k is small in practice (most titles have 1–5 copies). |
| `returnBook()` | O(1) | Loan lookup via `activeLoanByCopyId.remove(copyId)`, then a single `queue.poll()`. |
| `reserveBook()` | O(k) + O(1) | `hasAvailableCopy()` scans copies; `enqueueReservation()` is O(1). |
| `markCopyLost()` | O(1) | Single CAS + map remove. |
| `searchByIsbn()` | O(1) avg | `ConcurrentHashMap` lookup. |
| `searchByTitle()` / `searchByAuthor()` | O(1) avg for the index lookup, O(m) for the returned list copy | m = number of titles matching the query (usually small). |

---

## 6. Trade-offs and Explicit Non-Decisions

- **No expiry of holds.** The reservation queue is FIFO and permanent until claimed.
  A real system needs a scheduled executor to expire holds after N days and advance
  the queue — flagged as the first extension point.
- **No per-member copy limit.** A member can borrow arbitrarily many copies. Real
  systems cap this (e.g. 5 books per member). Straightforward to add:
  `if (member.getActiveLoans().size() >= MAX_LOANS) throw new LoanLimitExceededException(...)`.
- **No partial-match / fuzzy search.** Title and author search are exact (after
  lowercasing). Real systems use inverted indices or full-text search (Elasticsearch,
  Lucene) for substring and fuzzy matching.
- **`BORROWED → LOST` closes the loan immediately.** A real system might want to keep
  the loan open (for insurance/dispute purposes) with a separate LOST flag rather than
  closing it and zeroing the due date. Simplification kept intentional for clarity.
- **Single branch.** No concept of multiple library branches with per-branch copy
  pools. Each isbn's copies form a single shared pool.

---

## 7. Extension Possibilities

- **Hold expiry via `ScheduledExecutorService`**: a background thread scans
  `reservedForMemberId` copies and fires `onReservationExpired` when the pickup window
  closes, then advances the hold to the next member or releases to AVAILABLE.
- **Per-member loan limit**: guard in `borrowBook()` before the claim loop.
- **Fuzzy/partial title and author search**: replace `CopyOnWriteArrayList<Book>` indices
  with a full-text inverted index or delegate to a search engine.
- **Multi-branch support**: add a `Branch` entity; copies belong to a branch; borrow
  and return are branch-scoped; reservation queue becomes branch-aware.
- **Pluggable cancellation fee strategy** (mirrors `FineCalculationStrategy`): a
  `ReservationCancellationPolicy` interface with implementations for "no fee" vs
  "fee after N cancellations."
- **Renewal support**: extend `Loan` with a `renewals` counter; add
  `renewLoan(copyId)` to the facade that resets the due date (up to a cap), guarded
  by "no reservation pending for this isbn."

---

## 8. Phase Connections

| Phase | Concept | Where it appears here |
|---|---|---|
| 1 — OOP & SOLID | SRP, ISP, DIP | `ReservationObserver` stays narrowly scoped (ISP); `LibraryService` depends on `FineCalculationStrategy` interface, not a concrete class (DIP); `Book` owns catalog identity, `BookCopy` owns lifecycle (SRP). |
| 2 — Object modeling | Composition, abstraction | `Book` owns `List<BookCopy>` and `Queue<String>` — composition over inheritance. `LibraryUser` is an abstract base for `Member` and `Librarian`. |
| 3 — Design patterns | State, Strategy, Observer, Facade | `CopyStatus` (State); `FineCalculationStrategy` (Strategy); `ReservationObserver` (Observer); `LibraryService` (Facade). |
| 3.5 — Enums & state machines | Rich enum with explicit transition table | `CopyStatus.canTransitionTo()` — same pattern as `RideStatus` and `NotificationStatus`. Fourth application of this idiom across Phase 6. |
| 4 — Concurrency | CAS, `ConcurrentLinkedQueue`, `CopyOnWriteArrayList`, `synchronized` vs `DoubleAdder` | `BookCopy.tryTransition()` + claim loop; `queue.poll()` for reservation hand-off; `CopyOnWriteArrayList` for read-heavy indices; `synchronized` for low-frequency fine updates. |
| 6 — Generalizable idioms | Lock-free resource claiming at copy granularity | The CAS-claim loop is now confirmed as a generalizable pattern: driver claiming (Problem 8) → copy claiming (Problem 9). The same primitive handles any "multiple actors racing for one of a pool of independently lockable resources" scenario. |
| 7 — Best practices (upcoming) | DI | `FineCalculationStrategy` injected via constructor into `LibraryService`; observers registered post-construction. Both are ready for Phase 7's testability focus without any refactoring. |

---

## 9. Demo Scenarios (in `main()`)

1. **Search and on-time return** — `searchByAuthor`, `searchByIsbn`, borrow and return
   before the due date, confirming zero fine.
2. **Concurrent CAS race** — Alice borrows one of two copies, leaving one AVAILABLE.
   Bob and Carol race via `ExecutorService` + `CountDownLatch`; exactly one wins
   the last copy, the other receives `NoCopyAvailableException`.
3. **Overdue return with tiered fine** — Carol returns 10 days late against
   `TieredFineStrategy(7, 0.25, 1.00)`: 7 × 0.25 + 3 × 1.00 = **$4.75**.
   Demonstrated via the `returnBook(copyId, LocalDate)` overload for
   deterministic overdue simulation without real clock manipulation.
4. **Reservation hand-off** — `IllegalReservationException` when copies are available
   (must borrow, not reserve); Bob takes the only copy; Alice fails to borrow and
   reserves instead; Bob returns; copy hands off to RESERVED-for-Alice with observer
   notification; Alice successfully claims her held copy.
5. **Lost copy and recovery** — a fresh third copy is added for determinism; Bob borrows
   it; librarian marks it LOST (replacement fee charged); attempting to return it throws
   `EntityNotFoundException` (loan already closed); `markCopyFound()` restores it to
   AVAILABLE.

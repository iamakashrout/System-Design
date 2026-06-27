# Hotel Booking System — Design Notes

**Phase 6, Problem 10** | Companion file: `HotelBookingSystem.java`

---

## 1. Problem Intuition

A hotel booking system looks simple — a calendar, a room list, a form. The
engineering challenge surfaces the moment you add concurrency:

> **Two guests simultaneously booking the last available room for overlapping
> dates must not both succeed.** The naive implementation — check availability,
> then create the booking as two separate steps — is a classic TOCTOU
> (Time-of-Check-to-Time-of-Use) race. Thread A checks and sees the room
> available. Thread B checks and also sees it available. Both proceed to book.
> Now both hold a confirmed booking for the same room and the same nights.

The fix is to make check-and-book *atomic per room*. Everything else in the
system (pricing, refunds, state machine, room types) is important but not
inherently concurrent — the design builds outward from this single constraint.

---

## 2. Core Modeling Decisions

### 2.1 Room owns its bookings and its lock

`Room` holds:
- A `List<Booking> bookings` — the ground truth of what dates are occupied.
- A `ReentrantLock bookingLock` — the gate that makes overlap-check +
  booking-creation atomic.

This is deliberate co-location: the lock protects the list it sits next to.
Nothing else in the system ever needs to acquire a room's lock — it is a
purely internal implementation detail of `Room`.

### 2.2 Overlap detection — strict less-than semantics

Two date intervals `[checkIn₁, checkOut₁)` and `[checkIn₂, checkOut₂)` conflict
iff:

```
checkIn₁ < checkOut₂  &&  checkIn₂ < checkOut₁
```

*Strict* less-than (`isBefore`, not `isEqual`) means a guest checking out on
day N and another checking in on day N is **not** a conflict. This is standard
hotel semantics: the departing guest vacates by noon, the arriving guest checks
in at 3 pm — housekeeping has the gap. Getting the boundary condition wrong
(using `<=` instead of `<`) would block back-to-back bookings unnecessarily.

### 2.3 BookingStatus — rich enum state machine

```
CONFIRMED  → CHECKED_IN  (guest arrives)
CONFIRMED  → CANCELLED   (pre-arrival cancellation)
CHECKED_IN → COMPLETED   (check-out)
CHECKED_IN → CANCELLED   (mid-stay cancellation, with fee)
COMPLETED  → (terminal)
CANCELLED  → (terminal)
```

Same pattern as `RideStatus`, `NotificationStatus`, `CopyStatus` across prior
problems. The transition table lives in the enum; `Booking.transitionTo()` asks
"is this legal?" before mutating. No external validator to drift out of sync.

### 2.4 RoomType — enum carrying config

`RoomType` is not just a label — it carries `capacity`, `baseRatePerNight`,
and `description` as per-constant fields. The pricing strategy reads
`room.getType().getBaseRatePerNight()` rather than maintaining a separate
room-type-to-rate map. The enum *is* the configuration.

---

## 3. Design Pattern Decisions

### 3.1 Strategy — PricingStrategy

`PricingStrategy.calculatePrice(Room, checkIn, checkOut) → double`.
Two implementations:

- `StandardPricingStrategy` — `baseRate × nights`. Simple, predictable.
- `SeasonalPricingStrategy` — iterates night by night; peak months (June–August,
  December) apply a `peakMultiplier` to the base rate; off-peak nights use base
  rate unchanged.

**Why iterate night-by-night rather than computing a single block multiplier?**
A stay that straddles a season boundary (e.g. June 28 – July 5) should be
priced at the correct rate for each night individually. A single aggregate
multiplier over the whole stay would either under-charge (if it uses off-peak
rate) or over-charge (if it uses peak rate) for the mixed period. Night-by-night
iteration is O(nights) — small constant in practice — and gives exact pricing.

**Why is `SeasonalPricingStrategy` not a decorator wrapping `StandardPricingStrategy`?**
Unlike `SurgePricingFareStrategy` in Problem 8 (which *augmented* a delegate's
output), `SeasonalPricingStrategy` replaces the rate computation entirely with
per-night logic. Wrapping `StandardPricingStrategy` here would be awkward — you'd
compute the standard total, then try to undo and redo it per night, which defeats
the purpose of delegation. Two independent algorithms, not a decorator stack.

### 3.2 Strategy — CancellationPolicy

`CancellationPolicy.calculateRefund(Booking, cancelDate) → double`.
Two implementations:

- `FreeCancellationPolicy(freeCancellationDays)` — full refund if cancelled ≥ N
  days before check-in; 50% refund if cancelled closer than N days but still
  before check-in; zero refund after check-in has occurred.
- `NonRefundablePolicy` — always zero, regardless of timing.

**Why a second independent Strategy axis?** Pricing and cancellation policy are
orthogonal dimensions. A booking uses a `SeasonalPricingStrategy` *and* a
`FreeCancellationPolicy` (or `NonRefundablePolicy`) — every combination is
independently valid. Merging them into one object (e.g. a `BookingPolicy` class
with both `calculatePrice` and `calculateRefund`) would couple two unrelated
concerns and force changes to the pricing code whenever refund rules change.

### 3.3 Factory — RoomFactory

`RoomFactory.createRoom(RoomType, roomNumber) → Room`. Currently a thin
one-liner, but the Factory is the *right* place to grow:

- Validation: "SUITE rooms must have numbers in the 300s."
- Feature flags: "SUPERSUITEs get a minibar inventory attached."
- Future types: add `PENTHOUSE` — only `RoomFactory` changes.

Callers (`HotelService.addRoom`) ask for a type, not a configuration. The
Factory owns the construction contract.

### 3.4 Facade — HotelService

Single entry point. Holds `ConcurrentHashMap` registries for rooms, guests,
and bookings. Owns the injected `PricingStrategy` and `CancellationPolicy` —
swapping from seasonal to standard pricing or from free to non-refundable
cancellation requires no change to the facade's internal logic.

---

## 4. Concurrency — the core engineering decision

| Concern | Mechanism | Why |
|---|---|---|
| Atomic check-and-book per room | `ReentrantLock` per `Room`, held across availability check + booking creation | Compound check-then-act — CAS alone can't atomise two operations. A lock is the right tool when atomicity spans more than one memory word. |
| Cross-room independence | No shared booking lock | Two guests booking different rooms proceed in parallel. A global lock would serialise all bookings for no reason. |
| Booking state transitions | `synchronized(this)` on `Booking.transitionTo()` | Single field write + validation — simple monitor lock, no compound operation across multiple objects. |
| Registries (rooms, guests, bookings) | `ConcurrentHashMap` | Lock-free reads, fine-grained-locked writes — standard for a shared registry under concurrent insert/lookup. |
| Availability search | Acquires each room's lock briefly per room | Consistent snapshot of each room's bookings without holding any lock longer than one room's check. |

**Why `ReentrantLock` rather than `synchronized(room)`?**
Both would work here. `ReentrantLock` is chosen for two reasons:
1. It exposes `tryLock(timeout)`, which would allow a timed retry if another
   thread holds the room's lock (useful extension for non-blocking booking attempts).
2. It separates the lock lifecycle from the object's monitor — the lock is a
   field, not an implicit property of the Room instance, which makes the
   concurrency intent explicit and easier to audit.

**The TOCTOU fix, illustrated:**
```
// WRONG — check and book are separate, non-atomic steps:
if (room.isAvailable(checkIn, checkOut)) {    // Thread A reads: available
    // Thread B also reads: available          // (interleave)
    createBooking(...);                        // Both threads proceed
}

// RIGHT — check and book under the same lock:
room.getBookingLock().lock();
try {
    if (!room.isAvailable(checkIn, checkOut)) throw RoomNotAvailableException;
    createBooking(...);  // only reached by one thread at a time, per room
} finally {
    room.getBookingLock().unlock();
}
```

---

## 5. Complexity Analysis

| Operation | Time | Notes |
|---|---|---|
| `bookRoom()` | O(b) where b = existing bookings for this room | `isAvailable()` scans the room's booking list. b is small in practice (a hotel room has ~365 nights/year, and most stays are 1–7 nights). |
| `cancelBooking()` | O(1) | Status transition + refund calculation. |
| `checkIn()` / `checkOut()` | O(1) | Single synchronized status transition. |
| `searchAvailableRooms()` | O(R × b) | R = total rooms, b = bookings per room. Lock is held per-room, not globally. |
| Registry lookups | O(1) avg | `ConcurrentHashMap`. |
| `SeasonalPricingStrategy` | O(nights) | Night-by-night iteration; nights is a small constant in practice (< 30 typical). |

---

## 6. Trade-offs and Explicit Non-Decisions

- **No overbooking model.** Real hotels sometimes deliberately overbook (expecting
  cancellations). This model enforces strict availability — flagged as an
  intentional simplification, not an oversight.
- **Single cancellation policy per hotel instance.** Injected at construction.
  A more flexible model would attach a `CancellationPolicy` to each `Booking`
  at creation time (per-booking or per-room-type policy). This is the first
  natural extension.
- **No payment processing.** Refund amounts are computed but not applied to any
  ledger. A real system integrates with a payment gateway and records transactions.
- **No room features beyond type.** A real system has amenities (pool view,
  accessible room, king vs twin) that filter availability search. Straightforward
  to add as fields on `Room` and predicates on `searchAvailableRooms`.
- **In-memory booking list per room.** A production system stores bookings in a
  database with an index on `(room_id, check_in, check_out)` to make the overlap
  query efficient at scale without scanning every booking.

---

## 7. Extension Possibilities

- **Per-booking cancellation policy.** Attach `CancellationPolicy` to `Booking`
  at creation time (e.g. non-refundable rate is cheaper; flexible rate is more
  expensive). Requires passing the policy into `bookRoom()` or selecting it by
  rate tier.
- **`tryLock(timeout)` for booking retries.** Replace `lock()` with
  `tryLock(200, TimeUnit.MILLISECONDS)` to allow the caller to retry or get a
  more informative "system busy, retry" response rather than blocking.
- **Room features and preference search.** Add `Set<Feature> features` to `Room`;
  add a feature predicate to `searchAvailableRooms()`.
- **Database-backed availability.** Replace `List<Booking> bookings` with a
  database query: `SELECT count(*) FROM bookings WHERE room_id = ? AND check_in < ? AND check_out > ?`.
  The overlap condition is identical — just expressed in SQL. The per-room lock
  would be replaced by a database-level advisory lock or optimistic locking with
  a unique constraint.
- **Booking modification.** Add `modifyDates(bookingId, newCheckIn, newCheckOut)` —
  requires acquiring the room lock, checking availability excluding the current
  booking, updating dates and recomputing price atomically.
- **Multi-property support.** Add a `Hotel` entity above `Room`; `HotelService`
  becomes a multi-property aggregator; each hotel has its own pricing and
  cancellation config.

---

## 8. Phase Connections

| Phase | Concept | Where it appears here |
|---|---|---|
| 1 — OOP & SOLID | SRP, OCP, DIP | `PricingStrategy` and `CancellationPolicy` are separate concerns (SRP); new policies extend without modifying `HotelService` (OCP); `HotelService` depends on interfaces, not concrete classes (DIP). |
| 2 — Object modeling | Composition | `Room` composes a `ReentrantLock` and a `List<Booking>` — lock and data co-located deliberately. `Booking` references `Room` and `Guest` by composition. |
| 3 — Design patterns | State, Strategy ×2, Factory, Facade | `BookingStatus` (State); `PricingStrategy` (Strategy); `CancellationPolicy` (Strategy); `RoomFactory` (Factory); `HotelService` (Facade). First problem to combine *two* Strategy axes and a Factory with the other patterns. |
| 3.5 — Enums & state machines | Rich enum with transition table, config-carrying enum | `BookingStatus.canTransitionTo()` (fifth application of the idiom); `RoomType` with `baseRatePerNight` and `capacity` as per-constant fields (config-carrying pattern). |
| 4 — Concurrency | Per-resource `ReentrantLock`, TOCTOU pattern | Per-room `ReentrantLock` makes check-then-book atomic without a global lock; `synchronized` for single-field Booking transitions; `ConcurrentHashMap` for registries. |
| 6 — Generalizable idioms | Right-sized locking granularity | Per-room lock (not per-hotel, not global) — the same principle as per-ride lock in Problem 8 and per-copy CAS in Problem 9. The unit of the lock should match the unit of the contention. |
| 7 — Best practices (upcoming) | DI | `PricingStrategy` and `CancellationPolicy` both injected via `HotelService` constructor — zero production-code changes needed to swap implementations in tests. |

---

## 9. Demo Scenarios (in `main()`)

1. **Standard lifecycle** — book a double room, check in, check out. Confirms
   the full `CONFIRMED → CHECKED_IN → COMPLETED` state machine.
2. **Peak-season pricing** — book a suite for 7 nights in July (peak month) with
   `SeasonalPricingStrategy(1.5)`. Demonstrates the 1.5× multiplier applied per
   night against the suite's base rate.
3. **Conflict detection** — Bob holds room 301 for Jul 1–8; Carol tries Jul 5–10
   and gets `RoomNotAvailableException`. Immediately after, a Jul 8–12 booking
   succeeds — confirming the strict less-than boundary (check-out day is not a
   conflict with the next check-in).
4. **Concurrent booking race** — Bob and Carol race via `ExecutorService` +
   `CountDownLatch` for room 101 (Feb 1–5); exactly one wins, the other gets
   `RoomNotAvailableException`.
5. **Cancellation and refund policy** — room 102 booked for March 1–7 at flat
   rate; cancelled 10 days before check-in → full refund; re-booked and cancelled
   3 days before → 50% refund. Demonstrates `FreeCancellationPolicy(7)` boundary.
6. **Availability search** — after the race (one room 101 booking survives) and
   both March cancellations, `searchAvailableRooms(Feb 1-5, SINGLE)` shows only
   the room that won the race is occupied; the other appears available.
7. **Invalid state transition guard** — complete a booking, then attempt a second
   `checkOut()` on the same booking. `InvalidBookingStateException` is thrown
   (`COMPLETED → COMPLETED` is not in the transition table).

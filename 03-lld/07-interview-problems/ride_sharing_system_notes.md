# Ride Sharing System — Design Notes

**Phase 6, Problem 8** | Companion file: `RideSharingSystem.java`

---

## 1. Problem Intuition

Strip away the surface complexity of Uber/Ola/Lyft and the core engineering problem is:

> **Many riders are racing to claim a small pool of available drivers, and exactly
> one rider should win each driver — without a global lock.**

Everything else in the system (fare calculation, lifecycle states, notifications) is
*important* but not *hard*. The hard part is concurrent matching correctness. So the
design optimizes around one question: **how do we let matching happen in parallel
across different drivers, while still guaranteeing atomicity for any single driver?**

The answer is to push the unit of synchronization down to the smallest possible
scope — a single driver's status field — using CAS instead of a coarse lock. This is
the same intuition behind lock-free data structures: contention is only a problem
when two threads actually want the *same* resource, so don't pessimistically lock
resources nobody is contending for.

---

## 2. Key Design Decisions

### 2.1 State — `RideStatus` as a rich enum

```
REQUESTED → ACCEPTED → IN_PROGRESS → COMPLETED
REQUESTED → CANCELLED
ACCEPTED  → CANCELLED
```

Each enum constant overrides `canTransitionTo(RideStatus)`, and `Ride.transitionTo()`
consults it before mutating state, throwing `InvalidRideStateException` otherwise.
This is the same pattern used for `NotificationStatus` in Problem 7 and reinforces
Phase 3.5: the transition table *is* the state machine — there's no separate
"validator" class to keep in sync with the enum.

**Deliberate simplification:** cancellation is only legal pre-trip (`REQUESTED` or
`ACCEPTED`). A driver mid-trip cannot be cancelled in this model — that's flagged as
an extension point (see §6) rather than silently allowed, because real systems
*do* support mid-trip incidents (driver emergency, accident) and pretending
otherwise would understate the problem.

### 2.2 Strategy — fare calculation

`FareCalculationStrategy` is a single-method interface (`calculate(distanceKm,
durationMin)` → `Fare`). Two implementations:

- `StandardFareStrategy` — base fare + per-km + per-min, the metered taxi model.
- `SurgePricingFareStrategy` — **does not duplicate fare math.** It holds a
  delegate `FareCalculationStrategy` and a surge multiplier, computes the
  delegate's `Fare` first, then scales it. This is composition over
  reimplementation — surge pricing is a *decoration* of a base strategy, not a
  competing algorithm. (It reads like the Decorator pattern more than classic
  Strategy, and that overlap is intentional and worth noticing: Strategy and
  Decorator both wrap behavior, but Strategy picks *which* algorithm runs while
  Decorator *augments* whichever one was picked. Here we get both — `Strategy`
  picks the base, and surge decorates it.)

### 2.3 Strategy — driver matching

`DriverMatchingStrategy.findBestMatch(pickup, candidates)` → `Optional<Driver>`.

- `NearestDriverMatchingStrategy` — pure Haversine distance minimization.
- `HighestRatedNearbyDriverMatchingStrategy` — best `rating` among drivers within
  a radius, falling back to "no match" if none are in range.

Both strategies are **pure functions over an immutable snapshot of candidates** —
they never touch driver state. This separation is what makes the concurrency
story tractable: matching logic answers "who is best?", and a *separate*,
narrowly-scoped CAS step answers "did I actually get them?". Conflating the two
(e.g. having the strategy itself claim the driver) would smear a concurrency
concern into an algorithm-selection concern — a SOLID/SRP violation as much as a
concurrency one.

### 2.4 Observer — decoupled notification

`RideObserver` has lifecycle hooks (`onRideAccepted`, `onRideStarted`,
`onRideCompleted`, `onRideCancelled`). Three observers are registered on the
facade: `RiderNotificationObserver`, `DriverNotificationObserver`, and
`RideAnalyticsObserver` (tracks completed/cancelled counts and revenue via
`DoubleAdder`, chosen over `synchronized long` for high-contention add-only
counters — see §3).

The `Ride` entity holds the observer list and fires events on every transition.
Notification logic has zero knowledge of fare calculation or matching — same
decoupling principle as Problem 7's notification system.

### 2.5 Facade — `RideSharingService`

Single entry point: `requestRide()`, `acceptRide()` (internal, called during
matching), `startRide()`, `completeRide()`, `cancelRide()`. Holds
`ConcurrentHashMap` registries for riders, drivers, and rides, and owns the
default fare strategy + matching strategy + observer list, injected at
construction (Dependency Injection — a Phase 7 concern surfacing early because
it's the only sane way to make fare/matching strategies swappable in tests).

---

## 3. Concurrency — the core engineering decision

| Concern | Mechanism | Why |
|---|---|---|
| Driver claim race | `AtomicReference<DriverStatus>` + `compareAndSet(AVAILABLE, EN_ROUTE_TO_PICKUP)` | Lock-free, per-driver granularity. No thread ever blocks another thread matching a *different* driver. |
| Lost CAS race | Drop the candidate, retry matching strategy on the remaining pool | Treats a lost race as expected, not exceptional — no exception thrown, no retry storm, just "try the next-best driver." |
| Ride state transitions | `synchronized (stateLock)` — a private `Object` per `Ride` | Compound check-then-act ("is this transition legal? if so, mutate") needs atomicity, but only *within* one ride. A `Ride`-scoped lock means thousands of concurrent rides never contend with each other. |
| Cross-thread field visibility | `volatile` on `Ride.driver`, `.status`, `.fare`, timestamps | Observers (notification, analytics) may read these fields from a different thread than the one that performed the transition. `volatile` guarantees the read sees the write — cheaper than re-acquiring `stateLock` just to read. |
| Registries (riders/drivers/rides) | `ConcurrentHashMap` | Lock-free reads, fine-grained-locked writes; standard choice over a single coarse `synchronized` map for a registry under concurrent insert/lookup. |
| Analytics revenue counter | `DoubleAdder` over `synchronized double` or `AtomicLong`-as-bits | Write-heavy, read-rarely counter — `DoubleAdder` stripes the value across cache lines to avoid contention, then sums on read. Right tool because analytics is updated on *every* ride completion but read only on demand. |

**Why CAS instead of a lock per driver, or one lock for the whole registry?**
A single registry-wide lock serializes all matching globally — if 1,000 riders
request rides simultaneously against 1,000 different available drivers, a coarse
lock would force them through one at a time for no reason, since none of them
actually conflict. A lock-per-driver would avoid that, but locks have to be
acquired and released even on the *uncontended* path, and a thread holding a
driver's lock while doing unrelated work (e.g. a slow notification call) would
block every other thread matching against that same driver for longer than
necessary. CAS gives the uncontended path a single atomic instruction with no
blocking at all, and the contended path degrades to "lose, move to next
candidate" rather than "wait."

This mirrors a real distributed-systems pattern Akash will recognize from HLD:
optimistic concurrency control (compare-and-swap) outperforms pessimistic locking
when conflicts are rare relative to total operations — which is exactly the
shape of ride matching at scale (most riders don't actually compete for the same
driver).

---

## 4. Complexity Analysis

| Operation | Time | Notes |
|---|---|---|
| `requestRide()` (matching) | O(D) | D = available drivers scanned by the matching strategy. No index/geo-partitioning in this simplified model — flagged as an extension. |
| `acceptRide()` CAS | O(1) | Single atomic instruction. |
| `startRide()` / `completeRide()` / `cancelRide()` | O(1) + O(k) | O(1) for the state transition itself; O(k) for firing k observer callbacks. |
| Registry lookups (`getRider`, `getDriver`, `getRide`) | O(1) avg | `ConcurrentHashMap`. |
| Distance computation (Haversine) | O(1) | Constant-time trig, called once per candidate during matching → O(D) total per match. |

---

## 5. Trade-offs and Explicit Non-Decisions

- **No `NoDriverAvailableException`.** When matching finds nobody, the ride
  simply stays in `REQUESTED`. This was a deliberate rejection of an
  exception-based "failure" model — in a real system, no-match-yet isn't an
  error, it's a normal state the dispatcher will retry. Throwing here would
  force every caller into try/catch for what is actually the common case during
  high demand.
- **No geo-indexing (quadtree / geohash grid).** Matching scans all `AVAILABLE`
  drivers linearly. Fine for an interview-scale model; a real system would
  partition drivers spatially so matching is O(drivers-near-pickup), not
  O(all-available-drivers). Called out explicitly rather than silently assumed
  away.
- **No mid-trip cancellation / no `EMERGENCY_STOPPED` state.** Keeps the state
  machine small and the transition table easy to verify by inspection — at the
  cost of not modeling a real (and important) edge case. See extensions below.

---

## 6. Extension Possibilities

- Add an `EMERGENCY_STOPPED` or `IN_PROGRESS → CANCELLED` (with partial fare)
  transition for mid-trip incidents.
- Replace linear driver scan with a geohash-bucketed index so matching is
  bounded by local driver density, not citywide driver count.
- Make matching asynchronous: a dispatcher thread pool pulls from a queue of
  `REQUESTED` rides and retries periodically, rather than matching synchronously
  inside `requestRide()`.
- Real routing distance/ETA (road network, traffic) instead of Haversine
  straight-line distance.
- Multi-driver bidding / rider-visible driver list before commit, rather than
  system-auto-assign.
- Pluggable cancellation-fee strategy (Strategy pattern again, mirroring
  `FareCalculationStrategy`).

---

## 7. Phase Connections

| Phase | Concept | Where it shows up here |
|---|---|---|
| 1 — OOP & SOLID | SRP, DIP | `RideSharingService` depends on `FareCalculationStrategy`/`DriverMatchingStrategy` interfaces, not concrete classes; matching logic and CAS-claiming are separated. |
| 2 — Object modeling | Composition, abstraction | `Rider`/`Driver` extend `User`; `Ride` composes `Location`, `Fare`, `Vehicle` rather than inheriting from them. |
| 3 — Design patterns | Strategy, Observer, Facade | Two independent Strategy axes (fare, matching); Observer for notifications/analytics; Facade as the single client entry point. |
| 3.5 — Enums & state machines | Rich enum with transition table | `RideStatus.canTransitionTo()` — same approach as Problem 7's `NotificationStatus`. |
| 4 — Concurrency | CAS, fine-grained locking, visibility | `AtomicReference` + `compareAndSet` for driver claiming; per-ride `synchronized` lock; `volatile` fields; `ConcurrentHashMap`; `DoubleAdder`. |
| 5 — (carried forward) | — | Concurrency primitives from Phase 5 applied directly rather than re-derived. |
| 6 — This problem | Optimistic concurrency at scale | The central lesson: lock granularity should match actual contention, not data structure boundaries. |
| 7 — Best practices (upcoming) | DI | Strategies and observers are injected via constructor, not hardcoded — sets up cleanly for Phase 7's testability focus. |

---

## 8. Demo Scenarios (in `main()`)

1. **Straightforward lifecycle** — request → accept → start → complete, with
   fare breakdown printed.
2. **Concurrent CAS race** — two threads request rides simultaneously against a
   single forced-`AVAILABLE` driver via `ExecutorService`, deterministically
   demonstrating exactly one match succeeds and the other falls back to
   `REQUESTED` with no driver.
3. **Cancellation + illegal transition guard** — cancel a `REQUESTED` ride, then
   attempt `startRide()` on it and catch the resulting
   `InvalidRideStateException`.
4. **Surge pricing** — `SurgePricingFareStrategy` wrapping `StandardFareStrategy`,
   showing the decoration in action end-to-end.

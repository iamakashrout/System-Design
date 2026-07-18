# Topic 6: Defensive Programming and API Design

## 1. Intuition

Every public method you write is a promise: "give me inputs shaped like *this*, and I'll behave correctly." The question this topic asks is: **what happens the moment someone breaks that promise?** Do they find out immediately, at the exact call site, with a clear message — or does the violation silently slide three layers deep into the system and surface as a confusing `NullPointerException` in code that had nothing to do with the actual mistake?

Defensive API design is the discipline of making that promise **impossible to violate silently.** It's the natural convergence point of everything in this phase: DI made dependencies explicit (Topic 1), testable design made behavior verifiable (Topic 2), exception design gave failures a clear vocabulary (Topic 3), clean architecture gave business rules a home that enforces itself (Topic 4), and logging made failures observable after the fact (Topic 5). Defensive API design is what stops the failure from ever needing all that machinery in the first place — by making the *shape* of a correct call the only shape the API accepts.

## 2. Core Concepts

### 2.1 Validating inputs at system boundaries

Validation should happen **once, at the boundary**, not scattered as defensive `if (x != null)` checks throughout the internals. The boundary is wherever untrusted or external input enters your system — a public API method, a constructor, a deserialization point. Once input has passed boundary validation, internal code should be able to *trust* its shape completely and never re-check it — re-validating internally, over and over, is a sign the boundary itself isn't doing its job.

This is the same idea as a country's border control versus every building's front desk independently re-checking your passport. Validate once, thoroughly, at the actual boundary; trust it everywhere after that.

### 2.2 Fail-fast, revisited

Topic 3 introduced fail-fast for method-level validation. API design extends the same idea to **construction itself**: an object's constructor (or a builder's `build()`) should make it *impossible* to end up holding an invalid instance. If `DateRange(checkIn, checkOut)` throws immediately when `checkOut` isn't after `checkIn`, then every single piece of code anywhere in the system that later receives a `DateRange` can simply trust it's valid — the invariant is enforced exactly once, at the one place objects of that type come into existence.

### 2.3 Null safety

`null` is famously "the billion-dollar mistake" (Tony Hoare's own description of inventing it) precisely because a `null` return gives the caller no compile-time signal that "nothing" was a possible outcome — it's discovered at runtime, usually via a `NullPointerException` far from the actual cause.

- **`Optional<T>` as a return type** makes "this might not have a value" part of the method's signature itself — the caller is forced to explicitly handle the absent case (`.orElseThrow()`, `.map()`, `.orElse(default)`) rather than being able to forget a null check.
- **Avoid `null` as a public API return value** wherever the "absence" case is a normal, expected outcome (e.g., "find a room by ID that doesn't exist") — return `Optional<T>` instead.
- **The Null Object pattern**: for collaborators (not simple value lookups), sometimes the cleanest fix isn't `Optional` but a *real object that does nothing* — a `NoOpNotificationSender` that implements the full `NotificationSender` interface but simply doesn't send anything. Calling code never needs a null check at all; it just calls `.send(...)` unconditionally, and the no-op object quietly absorbs the call. This is the right tool specifically when the "absent" case still needs to support the full interface's behavior (even if that behavior is "do nothing"), rather than a query for a value that may or may not exist.

### 2.4 Designing a clean public API: minimal surface area, clear contracts

- **Minimal surface area**: expose only the methods callers actually need. Every public method, field, or constructor is a permanent promise to every caller — the more you expose, the more ways there are for callers to misuse the class or depend on details you'd like to change later. Prefer `private`/package-private by default, and only widen visibility when there's a genuine need.
- **Clear contracts through types, not documentation alone**: a method signature `createBooking(DateRange range, RoomId roomId)` prevents an entire category of bugs (accidentally swapping two `String` arguments that both happen to be strings) that `createBooking(String checkIn, String checkOut, String roomId)` cannot — this is sometimes called avoiding "primitive obsession." The type system enforces the contract; a Javadoc comment merely describes it and can't stop a caller from ignoring it.
- **Defensive copying for anything mutable that crosses the API boundary**: if a method returns an internal `List<Booking>` directly, a caller can mutate your internal state through that reference without your class ever knowing. Return an unmodifiable view or a defensive copy instead.
- **Good naming carries part of the contract**: `cancel()` should not silently do nothing if the booking is already cancelled — its name promises an action; if it can't perform that action, it should say so (an exception, per Topic 3), not fail silently.

### 2.5 Immutability as a defensive tool

An immutable object (all fields `final`, no setters, defensive copies of any mutable fields taken at construction) cannot be corrupted after creation — by a caller holding a reference, by a bug in another thread, or by code three layers away that wasn't supposed to touch it. Immutability doesn't just help with concurrency (Phase 4) — it's a defensive API design tool in its own right: once you've validated an object's invariants at construction (Section 2.2), immutability *guarantees* those invariants can never be violated later by anyone, anywhere, because there is no code path that can change the object at all.

## 3. Real-World Analogy

Think of airport security versus a building's internal hallways. All the actual checking — ID verification, bag scanning, boarding pass validation — happens once, at a small number of clearly marked checkpoints (the boundary). Once you're through, you can walk freely between gates without being re-checked at every door (trusting validated input internally). Now imagine if, instead, every single door inside the terminal independently demanded your ID again — that's what scattering `if (x == null)` checks through internal code looks like: redundant, easy to get subtly wrong in one specific spot, and a sign the actual boundary isn't doing its job properly in the first place.

## 4. Implementation

See `DefensiveApiDesign.java`. It hardens the public API of the Hotel Booking System (continued from Topics 4–5) across every dimension above:

1. **`DateRange`** — an immutable value object replacing two raw `LocalDate` parameters, validating `checkOut.isAfter(checkIn)` in its constructor (fail-fast at construction) and owning its own `overlaps()` logic — avoiding "primitive obsession" and preventing an entire class of argument-order bugs.
2. **Null safety, before/after**: a `BadRoomLookup` returning `null` on a missing room, contrasted with `RoomRepository.findById(...)` returning `Optional<Room>` — plus a **Null Object pattern** example (`NotificationSender` / `NoOpNotificationSender`) for a collaborator whose absence should be silently handled, not null-checked.
3. **Minimal API surface**: `BookingService` exposes only `createBooking(...)`, `cancelBooking(...)`, and `getBookingsForRoom(...)` — internal collections are never returned directly; `getBookingsForRoom` returns an unmodifiable view.
4. **Fail-fast construction via a builder**: `BookingRequest.Builder` validates all required fields are present before `build()` succeeds, so a `BookingRequest` instance is guaranteed complete and valid the moment it exists.
5. Explicit, clearly labeled **anti-pattern demonstrations**: returning `null` from a public method, leaking a mutable internal list, and primitive obsession causing a swapped-argument bug that compiles cleanly but is wrong at runtime.

## 5. Anti-Patterns

- **Returning `null` for an expected "not found" case**: forces every caller to remember a null check, and the one caller who forgets discovers it as a `NullPointerException` far from the actual missing-data event. Use `Optional<T>` instead.
- **Leaking mutable internal state**: `return this.bookings;` where `bookings` is a mutable `List` lets any caller silently corrupt the service's internal data. Return `Collections.unmodifiableList(...)` or a defensive copy.
- **Primitive obsession**: `createBooking(String roomId, LocalDate checkIn, LocalDate checkOut)` compiles just as happily if a caller accidentally swaps `checkIn` and `checkOut` — the compiler cannot catch it because both are the same type. A `DateRange` value object makes that specific mistake structurally harder to make.
- **Validating the same input repeatedly at every layer**: if input is thoroughly validated once at the boundary (e.g., inside `DateRange`'s constructor), re-checking `checkOut.isAfter(checkIn)` again three layers deeper is redundant defensive clutter — trust your own validated types.
- **Overly wide public API surface**: making every field and helper method `public` "just in case it's useful later" means callers can (and eventually will) depend on internal details you wanted the freedom to change — every public member is a permanent commitment.
- **Silent no-ops disguised as success**: a `cancel()` method that quietly does nothing when called on an already-cancelled booking, without any signal to the caller, violates the promise its name makes — Topic 3's `InvalidBookingOperationException` (Topic 4's `Booking.cancel()`) is the correct response, not silence.

## 6. Trade-offs

- **Value objects add classes**: introducing `DateRange` instead of two raw `LocalDate` parameters means one more class to maintain. This pays off specifically when the same pair of values is passed around in multiple places and validated the same way each time — for a single, one-off use, a plain parameter pair may genuinely be simpler.
- **`Optional` isn't free**: `Optional<T>` as a field type or a method parameter is generally discouraged (it was designed specifically as a return type) — using it everywhere indiscriminately adds unwrapping ceremony without benefit. The right target is specifically "the return type of a query that might reasonably have no result."
- **Immutability has a real allocation cost**: creating a new instance for every "change" (rather than mutating in place) is more object churn. For most application-level domain objects, this cost is negligible next to the safety it buys; it's a genuine consideration for extremely hot, allocation-sensitive paths.
- **Minimal API surface can feel restrictive during active development**: while iterating quickly, it's tempting to make everything public "for now." The discipline pays for itself specifically once other code starts depending on the class — the earlier the surface is minimized, the fewer breaking changes later.

## 7. Key Takeaways

1. Validate thoroughly once, at the boundary — internal code should trust validated types completely rather than re-checking the same invariant at every layer.
2. Prefer `Optional<T>` over `null` for any return value where "no result" is a normal, expected outcome; use the Null Object pattern for collaborators whose absence should be silently and safely handled.
3. Let types carry part of your API's contract — a `DateRange` or `RoomId` value object prevents entire categories of mistakes that same-typed primitive parameters cannot.
4. Keep your public API surface minimal and defensively copy any mutable state that crosses it — every public member is a permanent promise to every caller.
5. Immutability, enforced at construction, guarantees an object's invariants can never be violated later by any code path, anywhere — it's a defensive tool, not just a concurrency one.

---

**This is the final topic of Phase 6 — Advanced Engineering Best Practices.** Together, Topics 1–6 form a deliberate progression: DI made dependencies explicit → Testable Design exploited that to make behavior verifiable → Exception Design gave failures a clear vocabulary → Clean Architecture gave business rules a self-enforcing home → Logging and Observability made runtime behavior visible after the fact → Defensive API Design closes the loop by making entire categories of failure structurally impossible to trigger in the first place.

# Topic 4: Clean Architecture Principles

## 1. Intuition

Every one of the Phase 5 systems works correctly. But "correct" and "structured so it can survive change" are different properties. Clean Architecture is about answering one question honestly:

> **If the database, the UI, or a third-party API changed tomorrow, how much of my business logic would I have to touch?**

If the answer is "a lot," it usually means business rules got tangled up with *how* data happens to be stored or *how* the outside world happens to be reached today. Clean Architecture's entire purpose is to draw a boundary so that the **rules of the business** (a room can't be double-booked, a booking can't be cancelled after check-in) are written in a layer that has *zero* awareness of databases, frameworks, or transport protocols. That layer should be able to survive a rewrite of literally everything else in the system without a single line of it changing.

This is the same instinct you already have from distributed systems — a well-designed service exposes a stable API contract while its internal storage, scaling strategy, or even language can change freely underneath it. Clean Architecture applies that exact idea *inside* a single service, between its internal layers.

## 2. Core Concepts

### 2.1 Separation of concerns across layers

Three layers, from innermost (most stable, most protected) to outermost (most volatile, most replaceable):

- **Domain layer**: entities and business rules. `Booking`, `Room`, `BookingStatus`. This layer has no imports from any framework, no database code, no HTTP code — nothing but plain language constructs and the rules of the business itself.
- **Application layer**: orchestrates domain objects to fulfill a use case. `BookingService.createBooking(...)` — it coordinates *calls* to domain objects and repositories, but the actual business rule enforcement (can this booking be made? can this booking be cancelled?) lives inside the domain objects themselves, not in this layer.
- **Infrastructure layer**: the outermost layer, where the actual mechanics of talking to the outside world live — a database, an in-memory store, a payment gateway client, a REST controller. This is the layer that's expected to change most often and most drastically over a system's life.

### 2.2 The Dependency Rule

> **Source code dependencies must point only inward. Nothing in an inner layer can know anything about an outer layer.**

This is the single non-negotiable rule of Clean Architecture, and it's stricter than it sounds. It means:

- The **domain layer** cannot import anything from infrastructure. `Booking` has never heard of a database.
- The **application layer** can depend on the domain layer, but it must depend on infrastructure only through *interfaces defined in the domain layer* — never on a concrete infrastructure class directly.
- The **infrastructure layer** depends inward, implementing interfaces the domain layer declares (this is exactly the Dependency Inversion Principle from Topic 1, applied at the architectural level instead of the class level).

The practical trick that makes this possible: **repository interfaces are defined in the domain layer, and implemented in the infrastructure layer.** The domain says "I need something that can find a room by ID" (an interface) without knowing or caring whether that's backed by Postgres, an in-memory map, or a REST call to another microservice. This is DI (Topic 1) at the scale of an entire architectural seam.

### 2.3 Anemic vs. rich domain model

- **Anemic domain model**: entities are just data bags — getters, setters, no behavior. All business logic lives in service classes that pull data out of entities, apply rules externally, and push the result back in. This is easy to write quickly, but it means business rules are scattered across every service that happens to touch that entity, and nothing stops a bug from constructing an entity in an invalid state (e.g., a `Booking` with a `CANCELLED` status but a `checkOutDate` still in the future).
- **Rich domain model**: entities own their invariants. `Booking.cancel()` is a method *on* `Booking` that checks its own current state and throws if the cancellation isn't valid, rather than a `BookingService` method that inspects a getter and mutates a setter from outside. The entity is now impossible to put into an invalid state through its own public API — it enforces the rule itself, everywhere, permanently, not just in the one service method someone remembered to add a check to.

The trade-off isn't "rich is always correct" — see Trade-offs below — but for any domain where business rules matter (which is most real systems), rich models concentrate the rules in one place instead of scattering them.

### 2.4 The Repository pattern

A repository is an interface, defined in the domain layer, that abstracts away *how* entities are persisted or retrieved — `RoomRepository.findById(roomId)`, `BookingRepository.save(booking)`. The domain and application layers only ever see this interface. The infrastructure layer provides a concrete implementation (`InMemoryRoomRepository`, or in a real system, `JpaRoomRepository`).

This buys the same benefits DI bought in Topic 1, applied specifically to persistence: the application layer is testable without a real database (inject a fake repository), and the actual storage technology can be swapped without touching a single line of business logic.

### 2.5 Service layer responsibilities: orchestration, not business logic

A common mistake is letting the application/service layer *become* the business logic — "if status is X and date is before Y, then..." scattered across `BookingService` methods. In Clean Architecture, the service layer's job is narrower and more mechanical:

- Fetch entities via repositories.
- Call the appropriate domain methods, letting the entity enforce its own rules.
- Persist the result.
- Translate domain exceptions into whatever the outer world needs (an HTTP response, a message, etc.) — connecting directly back to Topic 3's exception translation.

If you can look at a service method and see `if` statements checking business conditions (rather than just checking whether an operation *succeeded*), that logic likely belongs inside the domain entity instead.

## 3. Real-World Analogy

Think of a company's core legal contracts (the domain layer) versus the office building it operates out of (infrastructure). The rules governing what makes a contract valid don't change because the company moves offices, switches its email provider, or replaces its filing cabinets with a cloud storage system. The legal department (application layer) *coordinates* using those contracts and whatever storage the company currently has, but the contracts themselves — the actual rules — don't care where they're physically kept. If moving offices required rewriting every contract, something would be deeply wrong with how the company organized itself. That's precisely the smell Clean Architecture prevents: business rules that "moved" (broke) because an unrelated technical detail changed.

## 4. Implementation

See `CleanArchitecture.java`. It restructures the Hotel Booking System (Phase 5, Problem 10) into three explicit layers (marked with comment banners simulating package boundaries, since this file uses static nested classes for portability):

1. **Domain layer**: `Room`, `Booking` (rich model — `confirm()`, `cancel()` enforce their own invariants and throw domain exceptions), `BookingStatus`, plus the `RoomRepository` and `BookingRepository` **interfaces** — defined here, not in infrastructure, per the Dependency Rule.
2. **Application layer**: `BookingService` — pure orchestration: fetch room via repository, ask the room/booking to validate and perform the state change, persist, translate exceptions. No business rule conditionals live here.
3. **Infrastructure layer**: `InMemoryRoomRepository`, `InMemoryBookingRepository` — concrete implementations of the domain-defined interfaces, using simple in-memory maps standing in for a real database.
4. A **before/after contrast**: an `AnemicBookingExample` showing the same cancellation logic implemented the anemic way (validation living in the service, entity as a dumb data bag), so the difference is visible side by side rather than asserted abstractly.

## 5. Anti-Patterns

- **Domain entities importing infrastructure**: a `Booking` class that imports a JDBC driver or a specific ORM annotation ties your core business rules to a specific persistence technology — violates the Dependency Rule directly.
- **Fat service layer with embedded business logic**: `if (booking.getStatus() == CONFIRMED && daysBefore(checkIn) < 1) { ... }` written inside `BookingService` instead of inside `Booking.cancel()` scatters the actual business rule across every place that happens to need it, and nothing prevents a different service method from getting the condition slightly wrong.
- **Repository interfaces defined in the infrastructure layer**: if `RoomRepository` is defined alongside `InMemoryRoomRepository` in the infrastructure layer, the domain/application layers must import from infrastructure to use it — this inverts the Dependency Rule even though it "looks" like a repository pattern is in use.
- **Leaking infrastructure types through the domain API**: a `RoomRepository.findById(...)` that returns a database-specific `ResultSet` or ORM entity instead of the domain's own `Room` type leaks infrastructure concerns straight into application code.
- **Treating "layers" as just package names with no enforcement**: creating `domain`, `application`, `infrastructure` packages but allowing imports to flow in every direction anyway gives the *appearance* of clean architecture without any of its actual guarantees. The boundary only has value if dependencies are genuinely one-directional.

## 6. Trade-offs

- **More upfront structure for small systems**: for a small CRUD script, three layers plus repository interfaces is real overhead compared to one class doing everything. This investment pays off as a system accumulates business rules, has more than one team touching it, or is expected to swap infrastructure (new database, new external API) over its lifetime.
- **Rich domain models aren't always the right call**: for genuinely simple data — a `Config` object with no real invariants — an anemic model is completely fine and adding behavior methods would be needless ceremony. Reach for rich models specifically where *invariants and business rules* exist to be enforced.
- **Strict layering can feel like indirection for indirection's sake** at first, especially for engineers used to writing directly against a database. The payoff (testability via fake repositories, and true independence from infrastructure choices) is realized over the system's maintenance lifetime, not on day one — same character of trade-off as Topics 1 and 2.
- **Repository pattern doesn't remove the need to think about real persistence concerns**: transactions, N+1 query problems, and consistency guarantees still exist underneath the abstraction — the pattern hides *which* technology is used, not the underlying complexity of persistence itself.

## 7. Key Takeaways

1. The Dependency Rule — source code dependencies point only inward, domain layer has zero outward knowledge — is the one rule that makes everything else in Clean Architecture work.
2. Repository interfaces belong in the domain layer; only their implementations belong in infrastructure. This is Dependency Inversion (Topic 1) applied at the architectural boundary.
3. Prefer a rich domain model wherever real business invariants exist — let entities enforce their own rules so no service method can accidentally bypass them.
4. The service/application layer should orchestrate (fetch, delegate, persist, translate exceptions) — not contain `if`-statement business logic itself.
5. Layering is only real if imports are genuinely one-directional — package names alone don't enforce anything without discipline (or, in real projects, build-tool module boundaries).

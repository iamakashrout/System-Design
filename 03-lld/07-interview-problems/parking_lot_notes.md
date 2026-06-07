# Parking Lot System — LLD Notes

## 1. Problem Intuition

A parking lot is a classic LLD problem because it compresses several real-world design challenges into one system:

- **Physical hierarchy**: A building has floors, floors have spots. This is a tree of objects, not a flat list.
- **Type diversity**: Bikes, cars, and trucks need different spot sizes. Mapping vehicle types to spot types is a rule that will change.
- **Transactional flow**: A parking session has a clear lifecycle — entry (find spot, generate ticket) → occupancy → exit (compute fee, release spot).
- **Pricing volatility**: How much to charge will change more often than the core parking flow. This demands decoupling.
- **Concurrency**: Multiple vehicles arrive and leave simultaneously. Spot allocation must be atomic — two threads cannot assign the same spot.

The interesting design challenge is not any single piece, but how these pieces interact while staying decoupled.

---

## 2. Requirements

### Functional (in scope)
- Support a multi-level parking lot with configurable floors and spots per floor
- Three vehicle types: `BIKE`, `CAR`, `TRUCK`
- Three spot types: `COMPACT` (for bikes), `REGULAR` (for cars), `LARGE` (for trucks)
- `parkVehicle(vehicle)` → finds an appropriate spot, generates a `Ticket`
- `exitVehicle(ticket)` → calculates fee, releases the spot, returns fee amount
- Fee calculation: hourly rate, different per spot type
- Thread-safe under concurrent entry/exit requests

### Out of scope
- Payment gateway or cash handling
- Advance reservations
- Physical gate/barrier simulation
- REST API or UI layer
- Multi-tenant (multiple separate lots)

---

## 3. Identifying Entities

| Entity | Responsibility |
|---|---|
| `ParkingLot` | Top-level aggregate — holds floors, entry/exit methods |
| `ParkingFloor` | Manages a list of spots on one floor; finds available spots |
| `ParkingSpot` | Tracks its own type and occupancy; synchronized occupy/release |
| `Vehicle` (abstract) | Base for `Bike`, `Car`, `Truck`; carries license plate and vehicle type |
| `Ticket` | Immutable record of a parking session (vehicle, spot, entry time) |
| `PricingStrategy` | Interface: `calculateFee(Ticket)` |
| `HourlyPricingStrategy` | Concrete pricing: rate × hours parked, per spot type |
| `SpotAllocationStrategy` | Interface: maps a `VehicleType` to the required `SpotType` |
| `DefaultAllocationStrategy` | Bike→COMPACT, Car→REGULAR, Truck→LARGE |
| `ParkingLotManager` | Singleton orchestrator: wires everything together |

### Key relationships
- `ParkingLot` **composes** `ParkingFloor` (1 to many)
- `ParkingFloor` **composes** `ParkingSpot` (1 to many)
- `Ticket` **associates** `Vehicle` + `ParkingSpot` (holds references, doesn't own them)
- `ParkingLotManager` **uses** `PricingStrategy` and `SpotAllocationStrategy` (injected, not coupled to implementations)

---

## 4. Design Decisions

### Strategy pattern for pricing
Fee calculation is volatile business logic. Using `PricingStrategy` means:
- A `WeekendSurgePricingStrategy` can be swapped in without touching `ParkingLotManager`
- Multiple strategies can coexist and be selected at runtime
- Tests can inject a `FlatRatePricingStrategy` without mocking anything

### Strategy pattern for spot allocation
The rule "bikes go in compact spots" sounds stable, but it can change (premium bikes in regular spots, oversized cars needing large spots). Injecting `SpotAllocationStrategy` into `ParkingLotManager` means the allocation rule is independently testable and swappable.

### Singleton for ParkingLotManager
There is exactly one parking lot system. Singleton ensures all threads share the same state. We use **double-checked locking** with `volatile` (from Phase 4) for safe lazy initialization.

### Concurrency design
Two-level approach:
1. `ParkingLot.parkVehicle()` iterates floors. Per-floor, a `ReentrantLock` guards the "find + occupy" compound operation — ensuring no two threads assign the same spot.
2. `ParkingSpot.occupy()` and `.release()` are `synchronized` for safe individual state transitions.

We deliberately do NOT lock the entire lot — that would serialize all parking operations. Locking per floor allows parallel parking on different floors.

### Ticket as a value object
`Ticket` is set at entry and never mutated. Exit time is recorded in a separate field when `exitVehicle()` is called. This makes tickets safe to log, audit, and pass across threads.

### Abstract Vehicle vs interface
`Vehicle` is abstract (not an interface) because all vehicles share real state (`licensePlate`, `vehicleType`) and a common constructor. There is no diamond inheritance risk, so abstract class is the right choice.

---

## 5. Class Diagram (text UML)

```
ParkingLotManager (Singleton)
├── uses → PricingStrategy (interface)
│           └── HourlyPricingStrategy
├── uses → SpotAllocationStrategy (interface)
│           └── DefaultAllocationStrategy
├── composes → ParkingLot
│               └── composes → ParkingFloor [1..*]
│                               └── composes → ParkingSpot [1..*]
│                                               ├── spotId: String
│                                               ├── spotType: SpotType (COMPACT|REGULAR|LARGE)
│                                               └── isOccupied: boolean
└── creates → Ticket
              ├── vehicle: Vehicle
              ├── spot: ParkingSpot
              └── entryTime: LocalDateTime

Vehicle (abstract)
├── licensePlate: String
├── vehicleType: VehicleType
└── subclasses: Bike, Car, Truck

Enums:
  VehicleType: BIKE, CAR, TRUCK
  SpotType:    COMPACT, REGULAR, LARGE
```

---

## 6. Pattern Usage Summary

| Pattern | Where applied | Why |
|---|---|---|
| **Strategy** | `PricingStrategy`, `SpotAllocationStrategy` | Decouples volatile rules from stable orchestration logic |
| **Singleton** | `ParkingLotManager` | One global system state; safe lazy init via double-checked locking |
| **Factory Method** | `ParkingLotFactory` (static builder) | Constructs the lot with floors and spots, isolates wiring from logic |
| **Template Method** (light) | `Vehicle` abstract class | Shared state in base, type-specific identity in subclasses |

---

## 7. Concurrency Summary

| Resource | Lock type | Scope |
|---|---|---|
| Spot allocation per floor | `ReentrantLock` per `ParkingFloor` | Protects "find available + mark occupied" atomic step |
| Individual spot state | `synchronized` method | Protects single `isOccupied` field transitions |
| Singleton initialization | `volatile` + double-checked lock | Safe lazy init under concurrent first-access |

---

## 8. Possible Extensions

| Extension | Where to change |
|---|---|
| Add motorcycle (needs COMPACT but smaller than bike) | New `VehicleType` enum value + update `DefaultAllocationStrategy` |
| Peak-hour surcharge pricing | New `PeakHourPricingStrategy` implements `PricingStrategy` |
| EV charging spots | New `SpotType.EV_CHARGING`, new allocation strategy rule |
| Spot reservation (advance booking) | Add `ReservationService` composing `ParkingLot`; spot gets `RESERVED` state |
| Multi-lot (chain of lots) | `ParkingLotManager` holds list of `ParkingLot`s; tries each |
| Flat-rate vs hourly toggle | Inject different `PricingStrategy` at startup |

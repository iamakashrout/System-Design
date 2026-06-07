# Elevator System — LLD Notes

## 1. Problem Intuition

The elevator system is a rich LLD problem for three reasons:

**State complexity per entity**: Each elevator is a little state machine. It isn't enough to track "current floor" — you need to know whether the elevator is moving, which direction, whether the doors are open, and what the next action is. Hard-coding `if (state == MOVING_UP)` everywhere quickly becomes a maintenance nightmare. This is the canonical use case for the State pattern.

**Dispatch as volatile strategy**: When someone presses the UP button on floor 7, picking *which* elevator to send involves an algorithm that will evolve — nearest idle, SCAN sweep, load-balancing, etc. This decision must be isolated from the elevator and request classes. It belongs in a DispatchStrategy.

**Request duality**: There are two kinds of requests — a hall button press ("I'm on floor 3, heading up") and an inside button press ("take me to floor 11"). They look similar but differ fundamentally: the first needs dispatch, the second goes directly to a known elevator. Modeling them as the same class or handling them with the same logic is a design smell.

---

## 2. Requirements

### Functional (in scope)
- Multiple elevators in a building (configurable)
- External requests: floor + direction (hall button presses)
- Internal requests: target floor from inside an elevator
- Dispatch: assign the best elevator to an external request via pluggable strategy
- Per-elevator state machine: IDLE → MOVING_UP / MOVING_DOWN → DOOR_OPEN → back
- DOOR_OPEN continues in the same direction if more stops remain; returns to IDLE if none
- Stop scheduling: elevator services stops in sorted floor order (SCAN-like)
- Observer: external components can subscribe to elevator state change events
- Thread-safe controller and elevator state under concurrent requests

### Out of scope
- Physical door timers (modeled as a single `step()` tick)
- Weight sensors, emergency stop, maintenance mode
- REST API or UI layer
- Persistence or replay

---

## 3. Identifying Entities

| Entity | Responsibility |
|---|---|
| `ElevatorController` | Singleton; receives requests, dispatches via strategy, drives simulation |
| `Elevator` | Physical elevator: current floor, state, stop queue, observer notification |
| `ElevatorState` | Interface for state-pattern behavior; transitions on `onFloorReached()`, `onDoorClosed()` |
| `IdleState` | Accepts new direction assignments; waits for work |
| `MovingUpState` | Services the next stop above; transitions to DOOR_OPEN on arrival |
| `MovingDownState` | Services the next stop below; transitions to DOOR_OPEN on arrival |
| `DoorOpenState` | Doors are open; on close, continues direction or returns to IDLE |
| `ExternalRequest` | Floor + direction from a hall button; routed through dispatch |
| `InternalRequest` | Target floor + elevator ID from inside button; goes directly to elevator |
| `DispatchStrategy` | Interface: picks the best elevator for an external request |
| `NearestElevatorStrategy` | Scores by distance and direction alignment; returns best elevator |
| `ElevatorObserver` | Interface for event subscribers (display boards, monitoring) |
| `Direction` | Enum: UP, DOWN |

---

## 4. Design Decisions

### State pattern for per-elevator behavior
Without State pattern, `Elevator.step()` would be a giant `switch` on `currentState`, with each branch doing different things. With State pattern:
- Each state class encapsulates what to do in that state
- `Elevator` just delegates to its current state: `currentState.onFloorReached(this)`
- Adding a new state (e.g., `MaintenanceState`) only requires a new class, no edits to `Elevator`
- State transitions live in the state class itself — IdleState knows to transition to MovingUpState when an upward stop is added

### Strategy pattern for dispatch
The dispatch algorithm is the most volatile part of the system. Real buildings use SCAN, LOOK, or nearest-car algorithms. Using `DispatchStrategy`:
- `NearestElevatorStrategy` can be replaced by `ScanDispatchStrategy` at runtime
- The algorithm is independently testable
- `ElevatorController` doesn't know which algorithm it's using

### Observer pattern for status notifications
Display boards in a real building show which elevator is coming. Rather than coupling `Elevator` to a display class, `ElevatorObserver` lets any component register interest. The elevator fires `notifyObservers(event)` without knowing who's listening.

### `step()` simulation model
A real elevator is driven by hardware timers. For a clean, testable design we use a discrete tick model: each call to `controller.step()` advances all elevators by one tick. One tick = one floor of movement, or one door-open/close cycle. This makes behavior fully deterministic and testable.

### Stop queue as TreeSet
Each elevator maintains a `TreeSet<Integer>` of target floors. TreeSet keeps floors sorted, so we can efficiently find the next floor above (`ceiling()`) or below (`floor()`) the current position. This directly supports SCAN behavior: always service the next stop in the current direction.

### Concurrency design
- `ElevatorController` methods: `synchronized` (one dispatcher thread at a time)
- Per-elevator stop queue: `synchronized` on the `Elevator` instance (stop additions from dispatch and step() must not race)
- Observer notifications: fire outside locks to prevent deadlock

---

## 5. Class Diagram (text UML)

```
ElevatorController (Singleton)
├── uses → DispatchStrategy (interface)
│           └── NearestElevatorStrategy
├── manages → Elevator [1..*]
│              ├── currentFloor: int
│              ├── currentState: ElevatorState
│              ├── stops: TreeSet<Integer>
│              ├── observers: List<ElevatorObserver>
│              └── state machine transitions via ElevatorState
└── processes → ExternalRequest, InternalRequest

ElevatorState (interface)
├── onFloorReached(elevator)
├── onDoorClosed(elevator)
└── implementations: IdleState, MovingUpState, MovingDownState, DoorOpenState

ElevatorObserver (interface)
└── onElevatorEvent(elevatorId, event, floor)

Enums:
  Direction:     UP, DOWN
  ElevatorEvent: DEPARTED, ARRIVED, DOOR_OPENED, DOOR_CLOSED, IDLE
```

---

## 6. Pattern Usage Summary

| Pattern | Where applied | Why |
|---|---|---|
| **State** | `ElevatorState` + 4 concrete states | Eliminates switch-on-state; each state manages its own transitions |
| **Strategy** | `DispatchStrategy` + `NearestElevatorStrategy` | Dispatch algorithm is volatile; must be independently swappable |
| **Singleton** | `ElevatorController` | One controller per building; shared state for all elevators |
| **Observer** | `ElevatorObserver` | Decouples elevator from display boards and monitoring systems |
| **Factory** (light) | `ElevatorController.initialize()` | Builds elevator fleet with configurable count and floor range |

---

## 7. Concurrency Summary

| Resource | Lock type | Scope |
|---|---|---|
| Controller dispatch | `synchronized` on controller | One dispatch decision at a time |
| Per-elevator stop set | `synchronized` on elevator instance | Add-stop and step() don't race |
| Observer notification | Outside locks | Prevents deadlock from observer callbacks |

---

## 8. Possible Extensions

| Extension | Where to change |
|---|---|
| SCAN dispatch algorithm | New `ScanDispatchStrategy` implements `DispatchStrategy` |
| Emergency/maintenance state | New `MaintenanceState` implements `ElevatorState` |
| Priority requests (VIP floors) | Extend `ExternalRequest` with priority; update strategy scoring |
| Real-time display board | New class implementing `ElevatorObserver` |
| Capacity enforcement | Add `currentLoad` to `Elevator`; strategy skips elevators at capacity |
| Multiple buildings | `ElevatorController` manages a list of `Building`s, each with its own fleet |

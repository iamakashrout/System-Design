# Enums and State Machines

> **Phase 3.5 — Between Design Patterns and Concurrency**
> This phase bridges the State pattern you learned with how real production systems model domain lifecycles. It covers rich enums, transition tables, and when to use each approach.

---

## Table of Contents

1. [The Big Picture](#the-big-picture)
2. [What Is a State Machine?](#what-is-a-state-machine)
3. [Step 1 — Modeling States with Enums](#step-1--modeling-states-with-enums)
4. [Step 2 — Building the State Machine](#step-2--building-the-state-machine)
5. [Step 3 — The Full Order Domain Model](#step-3--the-full-order-domain-model)
6. [Step 4 — Running the State Machine](#step-4--running-the-state-machine)
7. [Step 5 — State Pattern vs Enum State Machine](#step-5--state-pattern-vs-enum-state-machine)
8. [The Combined Approach](#the-combined-approach)
9. [Full Summary & Quick Reference](#full-summary--quick-reference)

---

## The Big Picture

You just learned the **State pattern** — objects that change behavior based on internal state. Phase 3.5 asks a more fundamental question:

> **How do you model state itself?**

In real systems, many domain objects have a **lifecycle**. An order isn't just data — it progresses through states: placed → confirmed → shipped → delivered. A ticket gets created, assigned, resolved, closed. An elevator moves, stops, opens doors.

The challenge is this: **state transitions are business rules**. Getting them wrong causes bugs that are hard to catch:

- An order that gets shipped before being confirmed
- A ticket that gets closed before being resolved
- A refund issued on a cancelled order

The goal of a state machine is to make **illegal transitions impossible by design**, not just by convention.

---

## What Is a State Machine?

A state machine is a model with four parts:

| Part | What it represents |
|---|---|
| **States** | The distinct modes an object can be in |
| **Events / Triggers** | The actions that cause transitions |
| **Transitions** | Which state follows which event from which state |
| **Guards** | Conditions that must hold for a transition to fire |

A state machine forces you to think about **all valid transitions explicitly**. Anything not modeled is implicitly invalid. That's the power — you're encoding business rules into the structure itself, not scattering them across `if-else` blocks.

### The Order Lifecycle State Diagram

```
                  ┌────────────────────────────────────────┐
                  │              CANCEL                    │
                  ▼                                        │
┌─────────┐  CONFIRM   ┌───────────┐  START_PROCESSING  ┌────────────┐
│ PENDING │──────────► │ CONFIRMED │──────────────────►  │ PROCESSING │
└─────────┘            └───────────┘                     └────────────┘
     │                      │                                  │
     │ CANCEL               │ CANCEL                           │ SHIP
     ▼                      ▼                                  ▼
┌───────────┐          ┌───────────┐                     ┌──────────┐
│ CANCELLED │          │ CANCELLED │                     │ SHIPPED  │
│ (terminal)│          │ (terminal)│                     └──────────┘
└───────────┘          └───────────┘                          │
                                                               │ DELIVER
                                                               ▼
                                                         ┌───────────┐
                                                         │ DELIVERED │
                                                         └───────────┘
                                                               │
                                                               │ REFUND
                                                               ▼
                                                         ┌──────────┐
                                                         │ REFUNDED │
                                                         │(terminal)│
                                                         └──────────┘
```

Reading the diagram: an arrow labeled `SHIP` from `PROCESSING` to `SHIPPED` means: "when the SHIP event fires, and the current state is PROCESSING, transition to SHIPPED." There is no arrow from `SHIPPED` with a `CANCEL` label — that transition is intentionally absent (you can't cancel a shipped order).

---

## Step 1 — Modeling States with Enums

Java enums are not just named constants. **They are full classes** — they can carry fields, methods, and implement interfaces. This makes them the natural fit for representing states with behavior.

### Basic Enum — Just Constants (not enough)

```java
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED
}
```

This is fine for data transfer, but it carries **no logic**. You still need external code to validate transitions:

```java
// Without rich enums — transition rules scattered in business logic
if (order.getStatus() == OrderStatus.PENDING) {
    order.setStatus(OrderStatus.CONFIRMED); // anyone can bypass this check
}
// Nothing stops: order.setStatus(OrderStatus.DELIVERED) from PENDING in one line
```

This is the problem. The rules are in whoever calls `setStatus()`. If any caller forgets to check, you get an invalid state.

### Rich Enum — State Carries Its Own Rules

The fix: make each state **know its own valid successors**. The enum becomes the single source of truth for what transitions are legal.

```java
public enum OrderStatus {

    PENDING {
        @Override
        public Set<OrderStatus> validNextStates() {
            return EnumSet.of(CONFIRMED, CANCELLED);
            // From PENDING: can only go to CONFIRMED or CANCELLED
        }

        @Override
        public String getDescription() { return "Order placed, awaiting confirmation"; }
    },

    CONFIRMED {
        @Override
        public Set<OrderStatus> validNextStates() {
            return EnumSet.of(PROCESSING, CANCELLED);
        }

        @Override
        public String getDescription() { return "Order confirmed, awaiting processing"; }
    },

    PROCESSING {
        @Override
        public Set<OrderStatus> validNextStates() {
            return EnumSet.of(SHIPPED, CANCELLED);
        }

        @Override
        public String getDescription() { return "Order being processed"; }
    },

    SHIPPED {
        @Override
        public Set<OrderStatus> validNextStates() {
            return EnumSet.of(DELIVERED);
            // NO CANCELLED here — this is a business rule encoded in the state itself
            // Once shipped, you cannot cancel
        }

        @Override
        public String getDescription() { return "Order in transit"; }
    },

    DELIVERED {
        @Override
        public Set<OrderStatus> validNextStates() {
            return EnumSet.of(REFUNDED);
        }

        @Override
        public String getDescription() { return "Order delivered to customer"; }
    },

    CANCELLED {
        @Override
        public Set<OrderStatus> validNextStates() {
            return EnumSet.noneOf(OrderStatus.class); // terminal — no exits
        }

        @Override
        public String getDescription() { return "Order cancelled"; }
    },

    REFUNDED {
        @Override
        public Set<OrderStatus> validNextStates() {
            return EnumSet.noneOf(OrderStatus.class); // terminal — no exits
        }

        @Override
        public String getDescription() { return "Order refunded"; }
    };

    // Every state must declare its valid transitions
    public abstract Set<OrderStatus> validNextStates();
    public abstract String getDescription();

    // Transition validation lives here — not scattered in service classes
    public boolean canTransitionTo(OrderStatus next) {
        return validNextStates().contains(next);
    }
}
```

Now you can check validity anywhere without importing a service or a validator:

```java
OrderStatus current = OrderStatus.SHIPPED;
System.out.println(current.canTransitionTo(OrderStatus.CANCELLED)); // false — business rule!
System.out.println(current.canTransitionTo(OrderStatus.DELIVERED)); // true
```

### Why `EnumSet`?

`EnumSet` is a specialized `Set` implementation for enum values. It's backed by a bit vector — extremely fast (`O(1)` contains) and memory-efficient. Always prefer `EnumSet` over `HashSet` when your elements are enum constants.

```java
// Prefer this
return EnumSet.of(CONFIRMED, CANCELLED);

// Over this
Set<OrderStatus> set = new HashSet<>();
set.add(CONFIRMED);
set.add(CANCELLED);
return set;
```

---

## Step 2 — Building the State Machine

With the enum carrying transition rules, the state machine becomes clean **orchestration**: it defines which events map to which transitions, then enforces them.

### Events — What Triggers Transitions

```java
public enum OrderEvent {
    CONFIRM,            // merchant confirms the order
    START_PROCESSING,   // warehouse picks the order
    SHIP,               // order is dispatched
    DELIVER,            // delivery confirmed
    CANCEL,             // order is cancelled
    REFUND              // refund is issued
}
```

Events are the *language* of the state machine. They represent business actions, not technical operations. `SHIP` is clearer than `setStatusToShipped`.

### The Transition Table — The Heart of the State Machine

The transition table is a `Map<State, Map<Event, NextState>>`. It maps every legal `(currentState, event)` pair to the state it should transition to.

```java
public class OrderStateMachine {

    // (currentState, event) → nextState
    // Everything NOT in this table is an illegal transition
    private static final Map<OrderStatus, Map<OrderEvent, OrderStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(OrderStatus.class);

        TRANSITIONS.put(OrderStatus.PENDING, buildTransitions(
            OrderEvent.CONFIRM,           OrderStatus.CONFIRMED,
            OrderEvent.CANCEL,            OrderStatus.CANCELLED
        ));

        TRANSITIONS.put(OrderStatus.CONFIRMED, buildTransitions(
            OrderEvent.START_PROCESSING,  OrderStatus.PROCESSING,
            OrderEvent.CANCEL,            OrderStatus.CANCELLED
        ));

        TRANSITIONS.put(OrderStatus.PROCESSING, buildTransitions(
            OrderEvent.SHIP,              OrderStatus.SHIPPED,
            OrderEvent.CANCEL,            OrderStatus.CANCELLED
        ));

        TRANSITIONS.put(OrderStatus.SHIPPED, buildTransitions(
            OrderEvent.DELIVER,           OrderStatus.DELIVERED
            // CANCEL is intentionally absent — cannot cancel after shipping
        ));

        TRANSITIONS.put(OrderStatus.DELIVERED, buildTransitions(
            OrderEvent.REFUND,            OrderStatus.REFUNDED
        ));

        // CANCELLED and REFUNDED are terminal states — no entries needed
    }

    // Core method: apply an event to a state, get the next state
    public OrderStatus transition(OrderStatus current, OrderEvent event) {
        Map<OrderEvent, OrderStatus> eventMap = TRANSITIONS.get(current);

        if (eventMap == null || !eventMap.containsKey(event)) {
            // Illegal transition — fail loudly, not silently
            throw new InvalidTransitionException(
                "Cannot apply event [" + event + "] in state [" + current + "]"
            );
        }

        return eventMap.get(event);
    }

    // Non-throwing check — useful for UI (show/hide buttons)
    public boolean canApply(OrderStatus current, OrderEvent event) {
        Map<OrderEvent, OrderStatus> eventMap = TRANSITIONS.get(current);
        return eventMap != null && eventMap.containsKey(event);
    }
}
```

### Why `EnumMap`?

Like `EnumSet`, `EnumMap` is a specialized `Map` for enum keys. Backed by an array indexed by enum ordinal — `O(1)` lookup, lower memory than `HashMap`. Always use `EnumMap` when your keys are enum constants.

### The Custom Exception

```java
public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(String message) {
        super(message);
    }
}
```

This is a **domain exception** — it represents a violated business rule, not a technical failure. It should bubble up to the API layer and become a `400 Bad Request`, not a `500 Internal Server Error`.

### Why a Separate Transition Table Instead of Using the Enum Alone?

You might wonder: the `OrderStatus` enum already has `validNextStates()`, so why build a separate transition table?

| Approach | What it knows | When to use |
|---|---|---|
| **Rich enum alone** | Valid successor states (the "what") | Quick validation: `canTransitionTo()` |
| **Transition table** | Valid (state, event) → nextState (the "how") | Full state machine with named events |

The transition table adds **events** to the picture. The enum says "SHIPPED can go to DELIVERED." The transition table says "SHIPPED + DELIVER event → DELIVERED." In complex systems, the same target state might be reachable via different events with different side effects.

---

## Step 3 — The Full Order Domain Model

With the state machine built, the `Order` class becomes clean and focused. It speaks in business language, delegates transition logic entirely to the state machine.

```java
public class Order {
    private final String            orderId;
    private final String            customerId;
    private       OrderStatus       status;
    private final List<String>      statusHistory;  // audit trail
    private final LocalDateTime     createdAt;
    private       LocalDateTime     updatedAt;

    // One shared state machine instance — it's stateless, safe to share
    private static final OrderStateMachine stateMachine = new OrderStateMachine();

    public Order(String orderId, String customerId) {
        this.orderId       = orderId;
        this.customerId    = customerId;
        this.status        = OrderStatus.PENDING; // always starts PENDING
        this.statusHistory = new ArrayList<>();
        this.createdAt     = LocalDateTime.now();
        this.updatedAt     = createdAt;
        statusHistory.add("START → PENDING | Order created");
    }
```

### Domain Methods — Business Language, Not Technical Operations

```java
    // Domain methods — each maps to one event in the state machine
    // The method name is the business action; the implementation is the transition

    public void confirm() {
        applyTransition(OrderEvent.CONFIRM, "Order confirmed by merchant");
    }

    public void startProcessing() {
        applyTransition(OrderEvent.START_PROCESSING, "Order picked for processing");
    }

    public void ship(String trackingNumber) {
        applyTransition(OrderEvent.SHIP, "Shipped with tracking: " + trackingNumber);
    }

    public void deliver() {
        applyTransition(OrderEvent.DELIVER, "Delivered to customer");
    }

    public void cancel(String reason) {
        applyTransition(OrderEvent.CANCEL, "Cancelled: " + reason);
    }

    public void refund(String reason) {
        applyTransition(OrderEvent.REFUND, "Refunded: " + reason);
    }
```

### The Core Transition Applier

```java
    // All domain methods funnel through here — one place where transitions happen
    private void applyTransition(OrderEvent event, String note) {
        OrderStatus previous = this.status;

        // state machine validates and returns the next state
        // throws InvalidTransitionException if the transition is illegal
        OrderStatus next = stateMachine.transition(this.status, event);

        this.status    = next;
        this.updatedAt = LocalDateTime.now();
        statusHistory.add(previous + " → " + next + " | " + note);

        System.out.println("[Order " + orderId + "] " + previous + " → " + next + " | " + note);
    }
```

### Query Methods — Safe Reads

```java
    // UI-friendly: can we show a "Cancel" button?
    public boolean canCancel() {
        return stateMachine.canApply(status, OrderEvent.CANCEL);
    }

    public boolean isTerminal() {
        return status == OrderStatus.CANCELLED || status == OrderStatus.REFUNDED;
    }

    public OrderStatus getStatus()         { return status; }
    public List<String> getStatusHistory() { return Collections.unmodifiableList(statusHistory); }
```

The key design decisions here:

- **`applyTransition()` is the single chokepoint** — every state change goes through it
- **Audit trail is automatic** — because every transition is recorded in `applyTransition()`
- **Domain methods use business language** — `confirm()`, `ship()`, not `setStatus(SHIPPED)`
- **Invalid transitions throw loudly** — never silently ignored or defaulted

---

## Step 4 — Running the State Machine

### Happy Path

```java
Order order = new Order("ORD-001", "USR-42");
order.confirm();
order.startProcessing();
order.ship("TRACK-XYZ-9876");
order.deliver();
```

Output:
```
[Order ORD-001] PENDING → CONFIRMED | Order confirmed by merchant
[Order ORD-001] CONFIRMED → PROCESSING | Order picked for processing
[Order ORD-001] PROCESSING → SHIPPED | Shipped with tracking: TRACK-XYZ-9876
[Order ORD-001] SHIPPED → DELIVERED | Delivered to customer
```

### Cancellation Path

```java
Order order = new Order("ORD-002", "USR-55");
order.confirm();
System.out.println("Can cancel? " + order.canCancel()); // true — CONFIRMED allows CANCEL
order.cancel("Customer requested cancellation");
System.out.println("Is terminal? " + order.isTerminal()); // true
```

Output:
```
[Order ORD-002] PENDING → CONFIRMED | Order confirmed by merchant
Can cancel? true
[Order ORD-002] CONFIRMED → CANCELLED | Cancelled: Customer requested cancellation
Is terminal? true
```

### Invalid Transition — Fails Loudly

```java
Order order = new Order("ORD-003", "USR-77");
order.confirm();
order.startProcessing();
order.ship("TRACK-ABC-1234");

System.out.println("Can cancel after shipping? " + order.canCancel()); // false

try {
    order.cancel("Trying to cancel after ship"); // should throw
} catch (InvalidTransitionException e) {
    System.out.println("Caught: " + e.getMessage());
}
```

Output:
```
[Order ORD-003] PROCESSING → SHIPPED | Shipped with tracking: TRACK-ABC-1234
Can cancel after shipping? false
Caught: Cannot apply event [CANCEL] in state [SHIPPED]
```

The illegal transition doesn't silently fail or corrupt state. It throws a domain exception immediately.

### Terminal State — No Exits

```java
Order order = new Order("ORD-004", "USR-99");
order.cancel("Out of stock");

try {
    order.cancel("Trying to cancel again"); // CANCELLED is terminal
} catch (InvalidTransitionException e) {
    System.out.println("Caught: " + e.getMessage());
}
```

Output:
```
[Order ORD-004] PENDING → CANCELLED | Cancelled: Out of stock
Caught: Cannot apply event [CANCEL] in state [CANCELLED]
```

### Automatic Audit Trail

```java
order.printHistory();
```

Output:
```
── History for Order ORD-001 ──
  START → PENDING | Order created
  PENDING → CONFIRMED | Order confirmed by merchant
  CONFIRMED → PROCESSING | Order picked for processing
  PROCESSING → SHIPPED | Shipped with tracking: TRACK-XYZ-9876
  SHIPPED → DELIVERED | Delivered to customer
```

The audit trail is a **free byproduct** of routing all transitions through `applyTransition()`. Every state change is recorded — no extra code needed in individual domain methods.

---

## Step 5 — State Pattern vs Enum State Machine

This is the design decision question you'll face in interviews and in production. The two approaches are often confused. Here's the honest breakdown.

### When to Use Enum-Based State Machine

States are **data-driven** — the variation is in *which state comes next*, not in *what the object does in each state*.

The transition table is the complexity. Per-state behavior is the same.

```
Order lifecycle, ticket workflow, payment status, booking states
→ SHIPPED.handle(event) and CONFIRMED.handle(event) don't DO different things
→ They just go to different next states
→ Enum + transition table is clear and maintainable
```

### When to Use State Pattern

Each state has **meaningfully different behavior** for the same operations. The object *does different things* in different states, not just *goes to different states*.

```
ATM machine, vending machine, TCP connection, elevator
→ insertCard() in IdleState: accept card and transition
→ insertCard() in CardInsertedState: reject with "already inserted"
→ The LOGIC is different, not just the routing
→ State pattern gives each state its own class with its own logic
```

### Side-by-Side Comparison

| | Enum State Machine | State Pattern |
|---|---|---|
| **Primary complexity** | Which state follows which event | What behavior is valid in each state |
| **Per-state behavior** | Same interface, different routing | Completely different logic per state |
| **Transition defined by** | Explicit transition table | State objects call `setState()` |
| **Adding a new state** | Add enum constant + rows in table | Add a new state class |
| **Best for** | Order lifecycle, ticket workflow | ATM, vending machine, editor modes |
| **Code shape** | Data: `Map<State, Map<Event, State>>` | Classes: `IdleState`, `CardInsertedState` |

### The Easy Test

Ask yourself: **"If I look at two different states, does the same operation do the same kind of thing or completely different things?"**

- `SHIPPED.cancel()` vs `CONFIRMED.cancel()` → both just say "go to CANCELLED" → **Enum table**
- `IdleState.insertCard()` vs `CardInsertedState.insertCard()` → completely different logic → **State pattern**

---

## The Combined Approach

In real production systems, you often use **both together**. The two patterns are not mutually exclusive — they solve different parts of the problem.

```java
// The enum owns the transition graph (WHAT transitions are valid)
public enum OrderStatus {
    PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED;

    public abstract Set<OrderStatus> validNextStates();
}

// State handler objects own the side effects (WHAT HAPPENS on transition)
public interface OrderStateHandler {
    void onEnter(Order order);   // called when entering this state
    void onExit(Order order);    // called when leaving this state
}

// Example: entering SHIPPED triggers notifications and analytics
public class ShippedStateHandler implements OrderStateHandler {
    @Override
    public void onEnter(Order order) {
        // Business logic specific to entering the SHIPPED state
        notificationService.sendShippingAlert(order.getCustomerId(), order.getTrackingNumber());
        analyticsService.recordShipment(order);
        inventoryService.decrementStock(order.getItems());
    }

    @Override
    public void onExit(Order order) {
        // Nothing special when leaving SHIPPED
    }
}
```

### How They Divide Responsibility

```
                    ORDER STATE MACHINE
                    ───────────────────
                         PENDING
                           │
                        CONFIRM        ← event
                           │
                         CONFIRMED
                           │
                    START_PROCESSING   ← event
                           │
                         PROCESSING
                           │
                          SHIP         ← event
                           │
       ┌───────────────► SHIPPED ◄────────────────────────────┐
       │                   │                                   │
 OrderStatus           DELIVER event                    ShippedStateHandler
 enum defines              │                            (onEnter) handles:
 valid transitions      DELIVERED                        - send SMS
                                                         - record analytics
                   Enum owns the GRAPH.          State handlers own the SIDE EFFECTS.
```

This separation is clean and scales well:
- Business analysts can reason about the transition graph from the enum
- Engineers can add/modify side effects in handlers without touching the graph
- New states can be added without disrupting existing handler classes

---

## Full Summary & Quick Reference

### Concept Summary

| Concept | Core idea |
|---|---|
| **State machine** | Explicit model of states, events, and transitions — illegal paths become impossible |
| **Rich enums** | Enums carry transition rules — the state knows its own valid successors |
| **`EnumSet`** | Specialized Set for enum values — use instead of `HashSet` for enum elements |
| **`EnumMap`** | Specialized Map for enum keys — use instead of `HashMap` for enum key maps |
| **Transition table** | Single source of truth for all legal `(state, event) → nextState` mappings |
| **`InvalidTransitionException`** | Illegal transitions throw at the domain layer — never silent failures |
| **Status history** | Audit trail is a natural byproduct of routing all transitions through one method |

### Pattern Selection Guide

```
Does the object have a lifecycle (states it progresses through)?
│
├── YES
│   │
│   ├── Does each state have completely different behavior for the same operations?
│   │       → STATE PATTERN
│   │         (IdleState.insertCard() vs CardInsertedState.insertCard() are totally different)
│   │
│   └── Is the complexity mainly in routing (which state follows which event)?
│           → ENUM STATE MACHINE
│             (OrderStatus.SHIPPED → DELIVERED when DELIVER fires)
│
│   Can both apply? Use COMBINED APPROACH:
│     Enum → owns the transition graph
│     State handlers → own the side effects (onEnter, onExit)
│
└── NO → You don't need a state machine
```

### Key Java Patterns Used

```java
// 1. Abstract methods in enums — each constant overrides
public enum OrderStatus {
    PENDING {
        @Override
        public Set<OrderStatus> validNextStates() { return EnumSet.of(CONFIRMED, CANCELLED); }
    };
    public abstract Set<OrderStatus> validNextStates();
}

// 2. EnumSet — efficient set of enum constants
EnumSet.of(CONFIRMED, CANCELLED)          // specific values
EnumSet.noneOf(OrderStatus.class)         // empty set (terminal state)
EnumSet.allOf(OrderStatus.class)          // all values

// 3. EnumMap — efficient map with enum keys
new EnumMap<>(OrderStatus.class)          // keyed by enum constants

// 4. Single chokepoint pattern
private void applyTransition(OrderEvent event, String note) {
    // validate → apply → record audit → notify
    // ALL transitions go through here — nothing bypasses it
}

// 5. canApply() for non-throwing checks (UI use)
public boolean canCancel() {
    return stateMachine.canApply(status, OrderEvent.CANCEL);
}
```

### Anti-Patterns to Avoid

| Anti-pattern | Better alternative |
|---|---|
| `order.setStatus(SHIPPED)` directly | `order.ship(trackingNumber)` — domain method that validates |
| `if (status == PENDING) status = CONFIRMED` scattered everywhere | Route through a single `applyTransition()` method |
| Using `HashSet` for enum sets | Use `EnumSet` — faster and clearer intent |
| Using `HashMap` for enum keys | Use `EnumMap` — designed for this |
| Catching `InvalidTransitionException` and ignoring it | Let it propagate to the API layer as a `400 Bad Request` |
| No status history | Audit trail is free — always keep one |
| Putting transition rules in service classes | Rules belong in the enum or the transition table — not scattered |

### Real-World Domains That Use This

| Domain | States | Terminal states |
|---|---|---|
| E-commerce order | PENDING → CONFIRMED → PROCESSING → SHIPPED → DELIVERED | CANCELLED, REFUNDED |
| Support ticket | OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED | CLOSED |
| Payment | INITIATED → AUTHORIZED → CAPTURED → SETTLED | FAILED, REFUNDED |
| Loan application | SUBMITTED → UNDER_REVIEW → APPROVED → DISBURSED | REJECTED, WITHDRAWN |
| CI/CD pipeline | QUEUED → RUNNING → PASSED | FAILED, CANCELLED |

---

*Next up → Phase 4: Concurrency and Thread Safety*

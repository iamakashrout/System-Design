# Behavioral Design Patterns

> **Phase 3 — Design Patterns | Part 3: Behavioral**
> These notes accompany runnable Java implementation files. All five patterns are covered with intuition, structure, code walkthrough, and trade-offs.

---

## Table of Contents

1. [Why Behavioral Patterns?](#why-behavioral-patterns)
2. [Pattern 1 — Strategy](#pattern-1--strategy)
3. [Pattern 2 — Observer](#pattern-2--observer)
4. [Pattern 3 — Command](#pattern-3--command)
5. [Pattern 4 — State](#pattern-4--state)
6. [Pattern 5 — Chain of Responsibility](#pattern-5--chain-of-responsibility)
7. [Full Summary & Quick Reference](#full-summary--quick-reference)

---

## Why Behavioral Patterns?

Creational patterns answered: *"How do we create objects?"*
Structural patterns answered: *"How do we compose objects into larger structures?"*

Behavioral patterns answer the hardest question: **"How do objects communicate, how is responsibility distributed, and how do algorithms stay flexible as requirements change?"**

The central tension behavioral patterns resolve is this:

> **Behavior that varies should not be hardcoded into the objects that use it.**

When you find yourself writing long `if-else` or `switch` chains to decide *what to do* or *who should handle something*, a behavioral pattern is usually the answer.

| Pattern | Core question it answers |
|---|---|
| **Strategy** | How do I vary an algorithm independently of the object that uses it? |
| **Observer** | How do objects get notified of changes without tight coupling? |
| **Command** | How do I treat an action as a first-class object — queue it, undo it, log it? |
| **State** | How does an object behave differently based on its current state? |
| **Chain of Responsibility** | How do I pass a request through a pipeline without the sender knowing who handles it? |

---

## Pattern 1 — Strategy

### The Problem

You have an object that needs to perform an operation, but the **algorithm varies**. The naive solution is a long `if-else` block inside the object:

```java
if (pricingType.equals("SEASONAL")) {
    price = applySeasonalDiscount(basePrice);
} else if (pricingType.equals("LOYALTY")) {
    price = applyLoyaltyDiscount(basePrice);
} else if (pricingType.equals("BULK")) {
    price = applyBulkDiscount(basePrice);
}
```

Problems with this:
- Adding a new pricing rule → modifying the existing class (violates OCP)
- Testing one algorithm requires setting up the entire object
- The class grows indefinitely as variants accumulate

### Real-World Analogy

A GPS navigation app has one job: get you from A to B. But *how* it does that varies — fastest route, shortest route, avoid tolls, avoid highways, walking, cycling. The destination logic doesn't change. Only the routing algorithm changes. Strategy lets you swap that algorithm independently.

### The Structure

```
PricingStrategy (interface)
+ calculatePrice(basePrice, quantity): double
+ getStrategyName(): String
          ▲
          │
  ┌───────┼────────┬──────────────┐
  │       │        │              │
Regular  Seasonal  Loyalty      Bulk
Pricing  Discount  Pricing     Pricing
         Pricing

OrderPricingService (context)
- strategy: PricingStrategy     ← holds a reference to the current strategy
+ setStrategy(strategy)         ← can be swapped at runtime
+ calculateOrderPrice(...)      ← delegates to strategy.calculatePrice()
```

Three ingredients:
1. **Strategy interface** — defines the algorithm's signature
2. **Concrete strategies** — each implements one variant of the algorithm
3. **Context** — holds a reference to a strategy, delegates to it, can swap it

### Implementation Highlights

```java
// Strategy interface — the contract all algorithms must follow
interface PricingStrategy {
    double calculatePrice(double basePrice, int quantity);
    String getStrategyName();
}

// One concrete strategy — knows its own calculation logic
class SeasonalDiscountPricing implements PricingStrategy {
    private final double discountPercent;

    public SeasonalDiscountPricing(double discountPercent) {
        this.discountPercent = discountPercent;
    }

    @Override
    public double calculatePrice(double basePrice, int quantity) {
        double total    = basePrice * quantity;
        double discount = total * (discountPercent / 100);
        return total - discount;
    }
}

// Context — knows NOTHING about which algorithm is used
class OrderPricingService {
    private PricingStrategy strategy;  // depends on the interface, not a class

    public OrderPricingService(PricingStrategy strategy) {
        this.strategy = strategy;
    }

    // Strategy can be swapped at any point — even mid-session
    public void setStrategy(PricingStrategy strategy) {
        this.strategy = strategy;
    }

    public double calculateOrderPrice(String product, double basePrice, int quantity) {
        // Pure delegation — context has no pricing logic of its own
        return strategy.calculatePrice(basePrice, quantity);
    }
}
```

### Usage — Runtime Swapping

```java
OrderPricingService service = new OrderPricingService(new RegularPricing());
service.calculateOrderPrice("Laptop", 80000, 1);        // regular price

service.setStrategy(new SeasonalDiscountPricing(20));   // swap strategy at runtime
service.calculateOrderPrice("Laptop", 80000, 1);        // 20% off applied

service.setStrategy(new LoyaltyPricing(500));           // swap again
service.calculateOrderPrice("Phone", 30000, 1);         // loyalty discount applied
```

### Demo Output

```
[PricingService] Product: Laptop | Strategy: REGULAR
[PricingService] Final price: ₹80000.0

[PricingService] Product: Laptop | Strategy: SEASONAL_20.0%_OFF
  [Seasonal] Discount applied: ₹16000.0
[PricingService] Final price: ₹64000.0

[PricingService] Product: Phone | Strategy: LOYALTY_500pts
  [Loyalty] Points discount: ₹50.0
[PricingService] Final price: ₹29950.0
```

The `OrderPricingService` code never changed. Only the strategy object swapped.

### Strategy vs If-Else — The Core Difference

```java
// Without Strategy — class grows forever, violates OCP
public double calculatePrice(String type, double base, int qty) {
    if (type.equals("SEASONAL")) { /* ... */ }
    else if (type.equals("LOYALTY")) { /* ... */ }
    else if (type.equals("BULK")) { /* ... */ }   // ← add new rule → modify this class
}

// With Strategy — add new rule → add new class, nothing else changes
public double calculatePrice(double base, int qty) {
    return strategy.calculatePrice(base, qty);     // ← closed to modification, open to extension
}
```

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Multiple variants of an algorithm exist | You only have one algorithm — abstraction adds no value |
| Algorithm should be swappable at runtime | Strategies need to share so much state they're tightly coupled anyway |
| New variants should be added without modifying the context | The variation is trivial — a simple flag or one-liner suffices |

---

## Pattern 2 — Observer

### The Problem

Object A changes state. Objects B, C, D need to know about it. If A holds direct references to B, C, D — it's tightly coupled to exactly those consumers. Adding a new consumer means modifying A. Removing one means the same. A shouldn't need to know who's interested in it.

### Real-World Analogy

Think of a **newspaper subscription**. The newspaper (publisher) doesn't know who specifically reads it. Subscribers sign up and get notified when a new edition is published. They can subscribe or unsubscribe at any time. The newspaper just publishes — it doesn't track what each subscriber does with the news.

This is the **publish-subscribe model**. Observer is its object-oriented implementation.

### The Structure

```
StockSubject (interface)              StockObserver (interface)
+ addObserver(observer)               + onPriceChanged(ticker, old, new)
+ removeObserver(observer)                      ▲
+ notifyObservers(old, new)                     │
          ▲                       ┌─────────────┼──────────────┐
          │                       │             │              │
        Stock              TradingAlgo    PriceAlert     AuditLogger
        - observers: List  (buy/sell)     (SMS alert)    (log entry)
        - price: double
        + updatePrice()    ← triggers notifyObservers()
```

Four ingredients:
1. **Subject interface** — add/remove/notify observers
2. **Observer interface** — the contract observers implement (`onPriceChanged`)
3. **Concrete subject** — holds observer list, notifies on state change
4. **Concrete observers** — each reacts differently to the same notification

### The Key Insight: Subject Doesn't Know Its Observers

```java
class Stock implements StockSubject {
    private final List<StockObserver> observers = new ArrayList<>();

    public void updatePrice(double newPrice) {
        double oldPrice = this.price;
        this.price      = newPrice;
        notifyObservers(oldPrice, newPrice); // broadcasts to all registered observers
    }

    @Override
    public void notifyObservers(double oldPrice, double newPrice) {
        // Stock doesn't know if it's notifying a trading algorithm, an SMS service,
        // or an audit logger — it just calls the interface method on everyone
        for (StockObserver observer : observers) {
            observer.onPriceChanged(ticker, oldPrice, newPrice);
        }
    }
}
```

The `Stock` class doesn't `import` TradingAlgorithm, PriceAlertService, or AuditLogger. It only knows `StockObserver`. This is the decoupling.

### Observers React Differently to the Same Event

```java
// Trading algorithm — compares price against thresholds
class TradingAlgorithm implements StockObserver {
    @Override
    public void onPriceChanged(String ticker, double oldPrice, double newPrice) {
        if (newPrice < buyThreshold)      System.out.println("BUY signal");
        else if (newPrice > sellThreshold) System.out.println("SELL signal");
        else                               System.out.println("HOLD");
    }
}

// Alert service — checks if a user's threshold was crossed
class PriceAlertService implements StockObserver {
    @Override
    public void onPriceChanged(String ticker, double oldPrice, double newPrice) {
        if (newPrice < alertThreshold) {
            System.out.println("SMS to user: " + ticker + " dropped below threshold");
        }
    }
}

// Audit logger — records every change regardless
class AuditLogger implements StockObserver {
    @Override
    public void onPriceChanged(String ticker, double oldPrice, double newPrice) {
        double pct = ((newPrice - oldPrice) / oldPrice) * 100;
        log.add(String.format("%s: ₹%.2f → ₹%.2f (%+.2f%%)", ticker, oldPrice, newPrice, pct));
    }
}
```

### Dynamic Subscribe / Unsubscribe

```java
infosys.addObserver(algo);         // register
infosys.addObserver(alert);
infosys.addObserver(auditLogger);

infosys.updatePrice(1430.0);       // all three notified

infosys.removeObserver(algo);      // unregister trading algo
infosys.updatePrice(1380.0);       // only alert + auditLogger notified
```

The subject doesn't change at all when observers are added or removed. That's the power of loose coupling.

### Demo Output

```
[Stock] INFY price changed: ₹1500.0 → ₹1550.0
  [MomentumAlgo] HOLD — price ₹1550.0 within range
  [AuditLog] INFY | ₹1500.00 → ₹1550.00 | +3.33%

[Stock] INFY price changed: ₹1550.0 → ₹1430.0
  [MomentumAlgo] BUY signal for INFY at ₹1430.0
  [AlertService] SMS to akash@email.com: INFY is BELOW ₹1450.0 — current: ₹1430.0
  [AuditLog] INFY | ₹1550.00 → ₹1430.00 | -7.74%

[Main] TradingAlgorithm unsubscribed

[Stock] INFY price changed: ₹1650.0 → ₹1380.0
  [AlertService] SMS to akash@email.com: INFY is BELOW ₹1450.0 — current: ₹1380.0
  [AuditLog] INFY | ₹1650.00 → ₹1380.00 | -16.36%
```

### Real-World Uses of Observer

| Use case | Subject | Observers |
|---|---|---|
| UI event handling | Button click | Multiple listeners |
| Java's `EventListener` | DOM element | Registered handlers |
| Database change streams | DB row update | Multiple downstream services |
| Message queues (Kafka) | Topic | Consumer groups |
| React state updates | Component state | Child components that re-render |

Java's own event system (`ActionListener`, `PropertyChangeListener`) is built on this pattern.

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| One object's state change should trigger reactions in others | The dependency chain is complex — cascading updates become hard to trace |
| The set of dependents is dynamic — can change at runtime | Observers have ordering dependencies — Observer doesn't guarantee order |
| You want to decouple producers from consumers | Memory leaks — forgotten observers that are never deregistered |

---

## Pattern 3 — Command

### The Problem

You need to **parameterize, queue, log, or undo operations**. With direct method calls, an operation is fire-and-forget — you can't store it, replay it, or reverse it. You need to treat an action as a first-class object.

### Real-World Analogy

Think of a **restaurant**. You (the client) tell a waiter (invoker) what you want. The waiter writes it on an **order slip** (command object) and hands it to the kitchen (receiver). The waiter doesn't cook. The kitchen doesn't know who ordered. The slip can be queued, cancelled, or replicated at any time. The command object *is* that order slip — it encapsulates everything needed to execute the action later.

### The Structure

```
EditorCommand (interface)
+ execute()
+ undo()
+ getDescription()
          ▲
          │
  ┌───────┼────────────┐
  │       │            │
Insert  Delete      Replace
Text    Text        Text
Command Command     Command
  │       │            │
  └───────┴────────────┘
          │ all hold reference to:
    TextDocument (receiver)
    — does the actual work

EditorHistory (invoker)
- undoStack: Deque<EditorCommand>
- redoStack: Deque<EditorCommand>
+ execute(command)    ← pushes to undoStack
+ undo()             ← pops undoStack, calls command.undo()
+ redo()             ← pops redoStack, calls command.execute()
```

Four ingredients:
1. **Command interface** — `execute()` and `undo()`
2. **Concrete commands** — encapsulate one action and its receiver
3. **Receiver** — the object that actually does the work (`TextDocument`)
4. **Invoker** — holds commands, triggers execute(), manages history stack

### The Key Insight: Commands Store State for Undo

```java
class DeleteTextCommand implements EditorCommand {
    private final TextDocument document;
    private final int position;
    private final int length;
    private String deletedText; // ← saved BEFORE deletion, needed for undo

    @Override
    public void execute() {
        // Save what will be deleted BEFORE deleting it
        deletedText = document.getContent().substring(position, position + length);
        document.delete(position, length);
    }

    @Override
    public void undo() {
        // Restoration is possible because we saved the deleted text
        document.insert(position, deletedText);
    }
}
```

The command object carries both the *instruction* and the *data needed to reverse it*. This is what makes undo possible — each command knows its own inverse.

### The Invoker Manages History

```java
class EditorHistory {
    private final Deque<EditorCommand> undoStack = new ArrayDeque<>();
    private final Deque<EditorCommand> redoStack = new ArrayDeque<>();

    public void execute(EditorCommand command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear();        // new action clears redo history (same as every real editor)
    }

    public void undo() {
        EditorCommand command = undoStack.pop();
        command.undo();           // each command knows how to reverse itself
        redoStack.push(command);  // move to redo stack in case user wants to redo
    }

    public void redo() {
        EditorCommand command = redoStack.pop();
        command.execute();        // re-execute the command
        undoStack.push(command);  // move back to undo stack
    }
}
```

### Demo Output

```
Initial: "Hello World"

[Editor] Executed: Insert ', Java' at position 5
[Editor] Content: "Hello, Java World"
[Editor] Executed: Delete 5 chars at position 0
[Editor] Content: ", Java World"
[Editor] Executed: Replace at 0 with 'Hi   '
[Editor] Content: "Hi    Java World"

--- Undo twice ---
[Editor] Undid: Replace at 0 with 'Hi   '
[Editor] Content: ", Java World"
[Editor] Undid: Delete 5 chars at position 0
[Editor] Content: "Hello, Java World"

--- Redo once ---
[Editor] Redid: Delete 5 chars at position 0
[Editor] Content: ", Java World"
```

### Beyond Undo: Other Uses of Command

The pattern's power extends beyond undo/redo. Once operations are objects, they can be used in many ways:

| Use case | How Command enables it |
|---|---|
| **Undo/Redo** | Store commands on a history stack; call `undo()` to reverse |
| **Task queues** | Serialize commands to a queue; workers dequeue and execute |
| **Audit logging** | Each command logs itself with timestamp and user |
| **Macro recording** | Record a list of commands; replay the entire sequence |
| **Delayed execution** | Store a command, execute it later on a schedule |
| **Retry logic** | If execution fails, re-execute the stored command |

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| You need undo/redo functionality | Operations have no meaningful reversal |
| Operations should be queued, scheduled, or logged | The added abstraction outweighs the benefit for simple one-off actions |
| You want to decouple the trigger from the executor | Command objects accumulate state — memory cost is real for large histories |

---

## Pattern 4 — State

### The Problem

An object behaves differently depending on its **current state**. The naive approach puts state-checking conditionals everywhere:

```java
public void insertCard() {
    if (state == IDLE)            { /* accept card */    }
    else if (state == CARD_IN)    { /* reject — already inserted */ }
    else if (state == PIN_VERIFIED) { /* reject — transaction in progress */ }
}

public void requestCash(double amount) {
    if (state == IDLE)            { /* reject */ }
    else if (state == CARD_IN)    { /* reject — enter PIN first */ }
    else if (state == PIN_VERIFIED) { /* dispense */ }
}
```

This `if (state == X)` logic is duplicated across every method, grows with every new state, and makes transitions hard to reason about.

### Real-World Analogy

Think of a **vending machine**. When idle, inserting a coin changes its state. When it has a coin, selecting a product triggers dispensing. When dispensing, no input is accepted. Each state has its own valid inputs and its own transitions. The machine isn't a pile of conditionals — it *is* a state, and that state handles each input appropriately.

### The Structure

```
ATMState (interface)
+ insertCard(atm)
+ enterPin(atm, pin)
+ requestCash(atm, amount)
+ ejectCard(atm)
          ▲
          │
  ┌───────┼────────────┐
  │       │            │
Idle   CardInserted  PinVerified
State  State         State
  │       │            │
  └───────┴────────────┘
        all accept ATMContext — can trigger state transitions

ATMContext (the ATM machine itself)
- currentState: ATMState   ← the current state object
+ insertCard()             ← delegates to currentState.insertCard(this)
+ enterPin()               ← delegates to currentState.enterPin(this, pin)
+ requestCash()            ← delegates to currentState.requestCash(this, amount)
+ setState(state)          ← called by state objects to transition
```

Three ingredients:
1. **State interface** — defines all actions the context can perform
2. **Concrete states** — each encapsulates behavior for one state
3. **Context** — holds the current state, delegates all behavior to it

### The Critical Mechanism: Context Delegates Everything

```java
class ATMContext {
    private ATMState currentState; // always points to the "active" state

    // Every public method delegates to the current state
    public void insertCard(String card) { currentState.insertCard(this); }
    public void enterPin(String pin)    { currentState.enterPin(this, pin); }
    public void requestCash(double amt) { currentState.requestCash(this, amt); }

    // States call this to trigger a transition
    public void setState(ATMState newState) {
        this.currentState = newState;
    }
}
```

The context itself has no `if-else` logic. It just says: "whatever state I'm currently in, let it handle this."

### State Objects Handle Their Own Transitions

```java
class CardInsertedState implements ATMState {
    @Override
    public void enterPin(ATMContext atm, String pin) {
        if (CORRECT_PIN.equals(pin)) {
            System.out.println("[CardInserted] PIN verified ✓");
            atm.setState(atm.getPinVerifiedState()); // ← state decides its own transition
        } else {
            System.out.println("[CardInserted] Incorrect PIN — try again");
            // stays in same state — no transition
        }
    }

    @Override
    public void requestCash(ATMContext atm, double amount) {
        // This action is invalid in this state — reject cleanly
        System.out.println("[CardInserted] Please verify your PIN first");
    }
}
```

Each state only implements what's valid for that state. Invalid actions are rejected with a clear message. No conditional logic required.

### Demo Output

```
=== Happy path ===
[Idle] Card inserted: 4111-1111-1111-1111
  [ATM] State: IdleState → CardInsertedState
[CardInserted] PIN verified ✓
  [ATM] State: CardInsertedState → PinVerifiedState
[PinVerified] Dispensing ₹3000.0 | Remaining balance: ₹7000.0
[PinVerified] Card ejected — session ended
  [ATM] State: PinVerifiedState → IdleState

=== Invalid operations (wrong state) ===
[Idle] Please insert a card first    ← clean rejection, no crash

=== Wrong PIN flow ===
[CardInserted] Please verify your PIN first
[CardInserted] Incorrect PIN — try again
[CardInserted] PIN verified ✓
[PinVerified] Insufficient funds. Available: ₹7000.0
[PinVerified] Dispensing ₹5000.0 | Remaining balance: ₹2000.0
```

### State vs Strategy — The Critical Distinction

These two patterns look structurally identical (both use an interface + concrete implementations + a context) but solve completely different problems:

| | Strategy | State |
|---|---|---|
| **Intent** | Vary an algorithm | Vary behavior based on internal state |
| **Who changes the behavior?** | Client swaps strategy explicitly | Object transitions states internally |
| **Awareness** | Strategies are independent — unaware of each other | States know about transitions to other states |
| **Lifecycle** | Context uses one strategy by choice | States change automatically as the object progresses |
| **Question it answers** | *How* to do something | *What can be done right now* |

A good test: if the client code says `service.setStrategy(new X())`, that's Strategy. If the object says `atm.setState(atm.getPinVerifiedState())` internally, that's State.

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| An object's behavior depends on its current state | Only two states — a simple boolean flag is simpler |
| You have `if (state == X)` repeated across many methods | State transitions are trivial and don't have complex per-state behavior |
| Adding new states should not modify existing state classes | The state explosion is worse than the conditional explosion |

---

## Pattern 5 — Chain of Responsibility

### The Problem

A request needs to be processed, but the exact handler isn't known in advance. Multiple handlers might be relevant, and you don't want the sender coupled to every possible handler. You want to build a **pipeline** where each handler decides whether to process the request or pass it along.

### Real-World Analogy

Think of a **customer support escalation system**. You call support. Level 1 tries to resolve it. Can't? Escalates to Level 2. Still can't? Goes to Level 3 specialist. You (the caller) don't know who ultimately resolves your issue — you just submit the request and the chain handles routing. Each level either handles or passes. The escalation path is transparent to you.

### The Structure

```
ExpenseHandler (interface)
+ setNext(handler)
+ handle(request)
          ▲
          │
BaseExpenseHandler (abstract)
- next: ExpenseHandler
+ setNext(next)       ← stores the next handler
+ passToNext(request) ← delegates to next if it exists
          ▲
          │
  ┌───────┼──────────┬──────────┐
  │       │          │          │
Manager  Director   VP        Board
Handler  Handler   Handler   Handler
≤₹10k    ≤₹50k    ≤₹200k    all else

Chain: Manager → Director → VP → Board
```

Four ingredients:
1. **Handler interface** — `handle(request)` and `setNext(handler)`
2. **Abstract handler** — stores `next` reference, provides `passToNext()` helper
3. **Concrete handlers** — each handles what it can; calls `passToNext()` otherwise
4. **Client** — sends to the first handler, unaware of the rest

### Building the Chain

```java
// Each handler just knows its limit
class ManagerHandler extends BaseExpenseHandler {
    private static final double LIMIT = 10_000;

    @Override
    public void handle(ExpenseRequest request) {
        if (request.getAmount() <= LIMIT) {
            System.out.println("[Manager] Approved ₹" + request.getAmount());
        } else {
            System.out.println("[Manager] Exceeds limit — escalating to Director");
            passToNext(request); // ← pass up the chain
        }
    }
}

// Wire the chain — order matters
ExpenseHandler manager  = new ManagerHandler("Priya");
ExpenseHandler director = new DirectorHandler("Rohit");
ExpenseHandler vp       = new VPHandler("Sunita");
ExpenseHandler board    = new BoardHandler();

manager.setNext(director);   // manager → director → vp → board
director.setNext(vp);
vp.setNext(board);

// Client always enters at the first handler
manager.handle(new ExpenseRequest("Akash", 5_000, "Team lunch"));    // handled by manager
manager.handle(new ExpenseRequest("Meera", 35_000, "Conference"));   // reaches director
manager.handle(new ExpenseRequest("Vikram", 120_000, "Hardware"));   // reaches VP
manager.handle(new ExpenseRequest("Priya", 500_000, "Renovation"));  // reaches board
```

### Demo Output

```
Request: Akash — ₹5000.0 for Team lunch
  [Manager: Priya] Approved ₹5000.0 for Akash

Request: Meera — ₹35000.0 for Conference tickets
  [Manager: Priya] ₹35000.0 exceeds limit ₹10000.0 — escalating to Director
  [Director: Rohit] Approved ₹35000.0 for Meera

Request: Vikram — ₹120000.0 for Hardware equipment
  [Manager: Priya] ₹120000.0 exceeds limit ₹10000.0 — escalating to Director
  [Director: Rohit] ₹120000.0 exceeds limit ₹50000.0 — escalating to VP
  [VP: Sunita] Approved ₹120000.0 for Vikram

Request: Priya — ₹500000.0 for Office renovation
  [Manager: Priya] → Director → VP → [Board] Reviewing — pending board vote
```

### What Makes This Powerful

```java
// Adding a new handler level = one new class + one setNext() call
// Nothing else changes

// Want to skip the Director for certain request types?
// Just rewire: manager.setNext(vp) for that flow

// Want to add an audit step at the end?
// board.setNext(new AuditHandler())

// The sender (client code) stays completely unchanged through all of this
```

The chain is **configurable at runtime**. You can insert, remove, or reorder handlers without touching the handlers themselves or the sender.

### Chain of Responsibility vs Other Patterns

| Pattern | Who handles the request? | Sender knows the handler? |
|---|---|---|
| **Chain of Responsibility** | One handler in the chain (or none) | No — passes through the chain |
| **Command** | One specific receiver, known at creation | Yes — command holds the receiver |
| **Observer** | All registered observers | No — but all of them, not just one |
| **Strategy** | The currently set strategy | Indirectly — context holds it |

### Real-World Uses of Chain of Responsibility

| Use case | Handlers in the chain |
|---|---|
| HTTP middleware (Spring filters) | Auth filter → Rate limit → CORS → Controller |
| Logging levels | DEBUG → INFO → WARN → ERROR |
| Java exception handling | Method → Caller → Parent caller → JVM |
| UI event propagation | Child element → Parent → Root |
| Spam filtering | Header check → Content check → ML model |

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Multiple handlers might process a request, and the set is dynamic | Every request must be handled — risk of silently falling off the chain |
| You want to decouple request sender from its handler | The chain is long and performance is critical — every handler is visited |
| Handler order or composition should be configurable at runtime | Debugging is hard — tracing which handler processed what requires effort |

---

## Full Summary & Quick Reference

### Pattern Comparison Table

| Pattern | Core problem | Key mechanism | Classic signals in code |
|---|---|---|---|
| **Strategy** | Algorithm varies, context stays stable | Interface + swap at runtime | `if-else` on type to pick behavior |
| **Observer** | State change should notify unknown consumers | Subscribe / notify list | "When X changes, Y and Z should react" |
| **Command** | Operations need to be first-class objects | Encapsulate action + receiver | Need undo, queue, or audit log |
| **State** | Behavior varies by internal state | Delegate to current state object | `if (state == X)` repeated across methods |
| **Chain of Responsibility** | Request passes through a pipeline | Linked handler chain | Escalation, middleware, filter pipelines |

---

### Pattern Selection Guide

```
Distributing behavior and communication...
│
├── Object does the same thing but differently based on a config or type?
│       → STRATEGY
│       (extract the varying algorithm into swappable strategy objects)
│
├── Multiple objects need to react when one object changes?
│       → OBSERVER
│       (publisher holds a list of subscribers, notifies all on change)
│
├── Need to record, queue, undo, or replay an action?
│       → COMMAND
│       (wrap the action in an object with execute() and undo())
│
├── Object behaves differently based on what state it's currently in?
│       → STATE
│       (each state is its own class; context delegates to current state)
│
└── Request needs to pass through multiple potential handlers?
        → CHAIN OF RESPONSIBILITY
        (link handlers together; each decides to handle or pass forward)
```

---

### Strategy vs State — Side by Side

These two are the most commonly confused behavioral patterns because they look structurally identical:

```java
// Strategy — client explicitly chooses
service.setStrategy(new SeasonalPricing(20));

// State — object transitions internally
atm.setState(atm.getPinVerifiedState());  // called from within a state object
```

| Question | Strategy | State |
|---|---|---|
| Who switches the behavior? | The client (externally) | The object itself (internally) |
| Do the implementations know about each other? | No — independent | Yes — states trigger transitions to other states |
| Does the object "progress" through variants? | No — freely swappable | Yes — follows a lifecycle |
| Real example | Sort algorithm, pricing rule | ATM, vending machine, order lifecycle |

---

### Key Java Reminders

| Topic | What to remember |
|---|---|
| Strategy | Context should accept the strategy interface, not a concrete class |
| Observer | Always `removeObserver()` when done — forgotten observers cause memory leaks |
| Observer | Java's `java.util.Observable` is deprecated — implement your own interface |
| Command | Save state needed for undo *before* executing (see `DeleteTextCommand`) |
| Command | `redoStack.clear()` on every new `execute()` — new action invalidates redo history |
| State | States receive the context as a parameter — that's how they trigger transitions |
| State | All state instances can be created once in the context and reused |
| Chain | The sender always enters at the first handler — never jumps into the middle |
| Chain | Always handle the "end of chain" case — log or throw if nothing handles the request |

---

### Anti-Patterns to Avoid

| Anti-pattern | Better alternative |
|---|---|
| Strategy with a single concrete strategy | No pattern needed — just implement it directly |
| Observer with ordering dependencies between observers | Use a more explicit sequencing mechanism |
| Storing all Command objects forever | Limit history size (e.g., max 50 undo steps) |
| State object accessing context fields directly | Provide clean getter/setter methods on the context |
| Chain where the end silently drops requests | Always have a terminal handler that logs or throws |
| Using State when only two states exist | A simple boolean flag is clearer and simpler |

---

### The Full Design Patterns Picture

You've now covered all three categories:

| Category | Patterns covered | Core question |
|---|---|---|
| **Creational** | Singleton, Factory Method, Abstract Factory, Builder, Prototype | *How do we create objects?* |
| **Structural** | Adapter, Decorator, Facade, Composite | *How do we compose objects?* |
| **Behavioral** | Strategy, Observer, Command, State, Chain of Responsibility | *How do objects communicate and distribute responsibility?* |

Every design problem maps to one of these three questions. When stuck, ask yourself:

- Is this a **creation** problem? → Look at Creational patterns
- Is this a **composition** problem? → Look at Structural patterns
- Is this a **communication / behavior** problem? → Look at Behavioral patterns

Then identify the specific pattern within that category based on the precise problem shape.

---

*Next up → Phase 3.5: Enums and State Machines*

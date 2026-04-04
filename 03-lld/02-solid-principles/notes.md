# SOLID Principles — Complete Notes

---

## What is SOLID?

SOLID is a set of five design principles that help you write software that is easy to understand, change, and test. Each letter stands for one principle.

| Letter | Principle | One-line summary |
|--------|-----------|-----------------|
| **S** | Single Responsibility | A class should have only one reason to change |
| **O** | Open/Closed | Open for extension, closed for modification |
| **L** | Liskov Substitution | Subtypes must be substitutable for their base types |
| **I** | Interface Segregation | Don't force classes to implement methods they don't need |
| **D** | Dependency Inversion | Depend on abstractions, not on concrete implementations |

These principles work together. SRP creates clean structure. OCP protects it over time. LSP ensures inheritance is honest. ISP keeps interfaces lean. DIP insulates business logic from infrastructure. Together, they produce code that is easy to extend, test, and maintain.

---

## S — Single Responsibility Principle (SRP)

### The Problem

Classes that do too many things are hard to change, test, and reason about.

### The Principle

> A class should have only one reason to change.

"Reason to change" means the *stakeholder* who would ask you to change it — not the number of methods.

### Violation

```java
public class Employee {
    public void saveToDatabase() { /* DB logic */ }
    public void generatePayslip() { /* PDF logic */ }
    public double calculatePay()  { /* Payroll logic */ }
}
```

Three different stakeholders can ask you to change this class:
- The **DBA** — "we switched databases"
- The **Finance team** — "payslip format changed"
- The **HR team** — "overtime rules changed"

One class, three reasons to change. Any edit risks breaking the other two.

### Fix

```java
// Holds employee data — changes only when the domain model changes
public class Employee {
    private final String id;
    private final String name;
    private final double hourlyRate;
}

// Changes only when storage layer changes
public class EmployeeRepository {
    public void save(Employee e) { /* DB logic */ }
    public Employee findById(String id) { /* DB logic */ }
}

// Changes only when payroll rules change
public class PayrollCalculator {
    public double calculatePay(Employee e, int hoursWorked) {
        return e.getHourlyRate() * hoursWorked;
    }
}

// Changes only when payslip format changes
public class PayslipGenerator {
    public void generate(Employee e, double payAmount) { /* PDF logic */ }
}
```

### Benefits

- **Testability** — test payroll logic without touching DB or PDF code
- **Parallel work** — two developers can edit `PayrollCalculator` and `PayslipGenerator` without Git conflicts
- **Safety** — a payslip format change cannot accidentally break payroll calculations

### Practical Signal

If you describe a class using **"and"** — "this class handles employees *and* saves them *and* generates reports" — it has too many responsibilities.

---

## O — Open/Closed Principle (OCP)

### The Problem

Adding new features keeps requiring changes to existing, working code — causing regression risk.

### The Principle

> Classes should be open for extension, closed for modification.

- **Open for extension** — add new behavior via new code
- **Closed for modification** — don't touch existing, tested code to do so

### Violation

```java
public class DiscountCalculator {
    public double calculate(Order order, String discountType) {
        if (discountType.equals("SEASONAL")) return order.getTotal() * 0.10;
        else if (discountType.equals("LOYALTY")) return order.getTotal() * 0.15;
        else if (discountType.equals("EMPLOYEE")) return order.getTotal() * 0.20;
        // Every new type = open this file and edit it
        return 0;
    }
}
```

Problems:
- The `if-else` chain grows forever
- Adding `STUDENT` discount risks breaking `LOYALTY` by accident
- String-based dispatch has no compile-time safety

### Fix — Strategy Pattern

```java
// Stable contract — never changes
public interface DiscountStrategy {
    double calculate(Order order);
}

// Each type is its own class — isolated and independently testable
public class SeasonalDiscount implements DiscountStrategy {
    public double calculate(Order order) { return order.getTotal() * 0.10; }
}

public class LoyaltyDiscount implements DiscountStrategy {
    public double calculate(Order order) { return order.getTotal() * 0.15; }
}

// Calculator has zero if-else — it just delegates
public class DiscountCalculator {
    private final DiscountStrategy strategy;

    public DiscountCalculator(DiscountStrategy strategy) {
        this.strategy = strategy;
    }

    public double calculate(Order order) {
        return strategy.calculate(order);
    }
}
```

Adding a new discount type:

```java
// New code — zero existing code touched
public class StudentDiscount implements DiscountStrategy {
    public double calculate(Order order) { return order.getTotal() * 0.25; }
}
```

### How It Works

OCP relies on **polymorphism** — a stable interface insulates the calculator from variation:

```
DiscountCalculator → depends on → DiscountStrategy (interface)
                                         ▲
                                 SeasonalDiscount
                                 LoyaltyDiscount
                                 StudentDiscount  ← added freely
```

### OCP vs. Strategy Pattern

| | What it is |
|---|---|
| **OCP** | The *principle* — prefer extension over modification |
| **Strategy Pattern** | A *pattern* — one concrete way to achieve OCP |

Other patterns that implement OCP: Decorator (add features without modifying), Template Method (vary steps of an algorithm), Plugin/Provider (framework-level extensibility).

### When to Apply

Don't apply OCP upfront everywhere — it adds indirection and more files.

> **Rule of Three:** The first time you change a class, just change it. The *second* time the same kind of change comes, *that's* when you refactor toward OCP.

---

## L — Liskov Substitution Principle (LSP)

### The Problem

Inheritance that breaks caller assumptions — subtypes that can't actually be used where base types are expected.

### The Principle

> Subtypes must be substitutable for their base types without changing program correctness.

This is about **behavioral contracts**, not just method signatures. Everything may compile, but substituting the subtype produces wrong results or exceptions at runtime.

### The Classic Violation — Rectangle/Square

```java
public class Rectangle {
    protected int width, height;
    public void setWidth(int w)  { this.width = w; }
    public void setHeight(int h) { this.height = h; }
    public int area() { return width * height; }
}

public class Square extends Rectangle {
    @Override
    public void setWidth(int w) {
        this.width = w;
        this.height = w; // silently changes height too!
    }
}

// Works for Rectangle. Breaks for Square.
public void testArea(Rectangle r) {
    r.setWidth(5);
    r.setHeight(4);
    assert r.area() == 20; // gets 16 when r is a Square
}
```

A Square *is-a* Rectangle in mathematics. But it **breaks the behavioral contract** of `setWidth` — which promises that calling it only changes the width.

### The Three Contract Rules

| Rule | What it means |
|------|---------------|
| **Preconditions** | Subtypes cannot be *more restrictive* about inputs |
| **Postconditions** | Subtypes cannot deliver *less* than what the base promised |
| **Invariants** | Subtypes must preserve the base class's always-true properties |

### Fix

Don't force inheritance where it doesn't behaviorally exist. Find the real shared abstraction.

```java
public interface Shape {
    int area();
}

// Immutable — no setters, no contract to break
public class Rectangle implements Shape {
    private final int width, height;
    public Rectangle(int width, int height) { this.width = width; this.height = height; }
    public int area() { return width * height; }
}

public class Square implements Shape {
    private final int side;
    public Square(int side) { this.side = side; }
    public int area() { return side * side; }
}
```

Both honor the `area()` contract completely. No surprises.

### Real-World Example — Birds

```java
// VIOLATION
public class Bird { public void fly() { } }
public class Penguin extends Bird {
    @Override public void fly() { throw new UnsupportedOperationException(); }
}

// FIX — honest interface hierarchy
public interface Bird { void eat(); }
public interface FlyingBird extends Bird { void fly(); }

public class Eagle implements FlyingBird { ... }   // can fly
public class Penguin implements Bird { ... }        // cannot — and doesn't pretend to
```

### Warning Signs of LSP Violations

```java
// 1. instanceof checks in calling code — caller can't trust the base type
if (shape instanceof Square) { ... }

// 2. Throwing UnsupportedOperationException
public void fly() { throw new UnsupportedOperationException(); }

// 3. Empty overrides — silent no-ops that lie to the caller
public void eat() { }
```

> **Key insight:** Mathematical IS-A ≠ Behavioral IS-A. Immutability naturally prevents many LSP violations — no setters means no contract-breaking side effects.

---

## I — Interface Segregation Principle (ISP)

### The Problem

Fat interfaces force classes to implement methods they don't need, creating dead code and misleading contracts.

### The Principle

> Clients should not be forced to depend on interfaces they don't use.

The key word is **client** — design interfaces around what the *caller* needs, not what the *implementor* can do.

### Violation

```java
public interface Worker {
    void work();
    void eat();
    void sleep();
}

// Robot is forced into a meaningless contract
public class Robot implements Worker {
    public void work()  { /* real logic */ }
    public void eat()   { /* ??? */ }   // What do you even put here?
    public void sleep() { /* ??? */ }
}
```

What can a developer put in `Robot.eat()`? Every option is bad:
- **Empty body** — silent ghost behavior, caller gets no indication anything is wrong
- **Throw exception** — runtime bomb, LSP violation
- **Log a warning** — noise in production logs

The interface forced an honest class into a dishonest contract.

### Fix

```java
public interface Workable { void work(); }
public interface Feedable  { void eat(); }
public interface Restable  { void sleep(); }

// Human honestly implements all three
public class HumanWorker implements Workable, Feedable, Restable {
    public void work()  { /* ... */ }
    public void eat()   { /* ... */ }
    public void sleep() { /* ... */ }
}

// Robot takes only what it genuinely supports
public class Robot implements Workable {
    public void work() { /* ... */ }
    // No eat(), no sleep() — honest about its capabilities
}
```

Now calling code can be type-safe:

```java
public void assignTask(Workable worker)   { worker.work(); }
public void lunchBreak(Feedable worker)   { worker.eat(); }
public void endShift(Restable worker)     { worker.sleep(); }

// This won't even compile — Robot doesn't implement Feedable
lunchBreak(new Robot()); // compile-time error
```

The bug that used to hide until runtime is now caught by the compiler.

### Real-World Example — Printers

```java
// Fat interface
public interface MultiFunctionDevice {
    void print(Document d); void scan(Document d); void fax(Document d);
}

// Basic printer forced into 3 runtime bombs
public class BasicPrinter implements MultiFunctionDevice {
    public void print(Document d) { /* real */ }
    public void scan(Document d)  { throw new UnsupportedOperationException(); }
    public void fax(Document d)   { throw new UnsupportedOperationException(); }
}
```

After ISP:

```java
public interface Printable { void print(Document d); }
public interface Scannable { void scan(Document d); }
public interface Faxable   { void fax(Document d); }

public class BasicPrinter implements Printable { ... }                       // honest
public class OfficePrinter implements Printable, Scannable, Faxable { ... }  // also honest
```

### ISP in the Real World

Java's Collections API is a masterclass in ISP:

```
Iterable     → only: iterator()
Collection   → adds: size(), add(), remove()
List         → adds: get(index), set(index)
RandomAccess → marker: signals O(1) index access
```

A method that takes `Iterable<T>` works with lists, queues, custom iterables — anything iterable — without caring about add/remove/size.

### ISP and LSP Are Deeply Connected

Almost every LSP violation is an ISP violation in disguise:

- Robot forced to implement `eat()` → stub that breaks → LSP violated
- Penguin forced into `Bird` with `fly()` → exception → LSP violated

**Fixing the ISP violation (split the interface) automatically prevents the LSP violation.**

### How Fine-Grained Should Interfaces Be?

ISP doesn't mean one method per interface. The right granularity: **group methods that are always used together by the same client**.

> Ask: *"Is there a realistic client that needs only a subset of these methods?"* If yes, split. If no, keep them together.

---

## D — Dependency Inversion Principle (DIP)

### The Problem

High-level business logic directly depending on low-level details (like a specific database or messaging system) makes it hard to swap implementations or test in isolation.

### The Principle

> High-level modules should not depend on low-level modules. Both should depend on abstractions.

```
HIGH-LEVEL MODULE   = business logic (OrderService, PaymentProcessor)
LOW-LEVEL MODULE    = infrastructure (MySQLRepository, StripeClient, KafkaPublisher)
ABSTRACTION         = interface between them (OrderRepository, PaymentGateway)
```

### Violation

```java
public class OrderService {
    // Two problems in one line:
    // 1. Field type is a concrete class — tight coupling
    // 2. `new` creates the dependency — no way to override it
    private MySQLOrderRepository repository = new MySQLOrderRepository();

    public void placeOrder(Order order) {
        repository.save(order);
    }
}
```

Consequences:
- Migrate from MySQL to PostgreSQL → must edit `OrderService`
- Want to test `OrderService` → need a real database
- Database driver changes → business logic is affected

Business logic changes for infrastructure reasons. That's wrong.

### Fix

```java
// Interface lives in the BUSINESS layer, not the infrastructure layer
public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(String id);
}

// Production implementation
public class MySQLOrderRepository implements OrderRepository {
    public void save(Order order) { /* MySQL logic */ }
    public Optional<Order> findById(String id) { /* SQL query */ }
}

// Test implementation — fast, no DB needed
public class InMemoryOrderRepository implements OrderRepository {
    private final Map<String, Order> store = new HashMap<>();
    public void save(Order order) { store.put(order.getId(), order); }
    public Optional<Order> findById(String id) { return Optional.ofNullable(store.get(id)); }
}

// Business logic depends only on the abstraction
public class OrderService {
    private final OrderRepository repository; // interface, not class

    public OrderService(OrderRepository repository) { // INJECTED, not created
        this.repository = repository;
    }

    public void placeOrder(Order order) {
        validateOrder(order);
        repository.save(order); // no MySQL knowledge here
    }
}
```

The dependency arrow is now **inverted**:

```
VIOLATION:                         CORRECT:

OrderService                       OrderService
     │ depends on                       │ depends on
     ▼                                  ▼
MySQLOrderRepository          OrderRepository (interface)
                                          ▲ implements
                               MySQLOrderRepository
```

### The Testability Payoff

```java
// Without DIP — need real MySQL to test business logic
// Slow, fragile, stateful, impossible in CI without a DB setup

// With DIP — inject InMemoryOrderRepository
@Test
public void testPlaceOrder_savesOrder() {
    InMemoryOrderRepository fakeRepo = new InMemoryOrderRepository();
    OrderService service = new OrderService(fakeRepo);

    service.placeOrder(new Order("ORD-001", 1500.00));

    assertTrue(fakeRepo.findById("ORD-001").isPresent());
}
// Fast (milliseconds), isolated, no database, deterministic
```

### Three Ways to Inject Dependencies

| Method | When to use |
|--------|-------------|
| **Constructor injection** | Mandatory dependencies — preferred, enables `final`, explicit |
| **Setter injection** | Optional dependencies or runtime swapping |
| **Interface injection** | Rarely used, frameworks handle this automatically |

### DIP in the Real World

**Spring's IoC Container** — you declare what you need (interface), Spring injects the right implementation at runtime. Your business logic never touches `new`.

**JDBC** — `Connection`, `PreparedStatement`, `ResultSet` are all interfaces. Behind them could be MySQL, PostgreSQL, or H2 for tests. Your SQL code never changes.

**Notification systems:**

```java
public interface NotificationSender {
    void send(String userId, String message);
}

// Switch from email to SMS to push — OrderService doesn't change
public class OrderService {
    private final NotificationSender notifier;

    public OrderService(NotificationSender notifier) {
        this.notifier = notifier;
    }

    public void placeOrder(Order order) {
        // business logic
        notifier.send(order.getUserId(), "Order confirmed!");
    }
}
```

### DIP vs. Dependency Injection

These are frequently confused:

| Term | What it is |
|------|------------|
| **DIP** | The *principle* — both layers depend on an abstraction |
| **Dependency Injection (DI)** | The *technique* — pass dependencies in from outside |
| **IoC Container** | The *tool* — Spring/Guice automates DI at scale |

DI is the most common way to achieve DIP, but DIP is the goal, not the mechanism.

### Practical Signals of DIP Violations

```java
private XYZRepository repo = new XYZRepository(); // new inside a service
private MySQLOrderRepository repository;           // concrete type as field
EmailService.sendEmail(user, message);             // static call = untestable
String dbUrl = "jdbc:mysql://prod-db/orders";     // infra config in business logic
```

Each of these is business logic reaching *down* into infrastructure — the wrong direction.

---

## How the Five Principles Work Together

```
SRP  → Each class has one job and one reason to change
         ↓ Clean separation makes the next step natural
OCP  → New behavior added via new classes, not edits to existing ones
         ↓ Extension only works if subtypes are honest
LSP  → Subtypes honor the behavioral contracts of their base types
         ↓ Honest subtypes need honest interfaces
ISP  → Interfaces are lean — clients depend only on what they use
         ↓ Lean interfaces set up the final step
DIP  → Business logic depends on abstractions, not on infrastructure details
```

> Without SRP, there's no clean boundary to invert.
> Without OCP, new implementations require existing code changes.
> Without LSP, substitution breaks at runtime.
> Without ISP, the abstraction itself is bloated.
> **DIP is the final payoff of getting all four right.**

---

## Quick Reference

| Principle | Violation smell | Fix |
|-----------|----------------|-----|
| **SRP** | Class described with "and" | Split into focused classes |
| **OCP** | if-else chains that grow with new types | Abstract the variation with interfaces |
| **LSP** | `instanceof` checks, `UnsupportedOperationException`, empty overrides | Find the honest shared abstraction |
| **ISP** | Classes implementing methods that throw or do nothing | Split the fat interface |
| **DIP** | `new ConcreteClass()` inside a service, concrete field types | Inject through interfaces |

# Creational Design Patterns

> **Phase 3 — Design Patterns | Part 1: Creational**
> These notes accompany runnable Java implementation files. All five patterns are covered with intuition, structure, code walkthrough, and trade-offs.

---

## Table of Contents

1. [Why Creational Patterns?](#why-creational-patterns)
2. [Pattern 1 — Singleton](#pattern-1--singleton)
3. [Pattern 2 — Factory Method](#pattern-2--factory-method)
4. [Pattern 3 — Abstract Factory](#pattern-3--abstract-factory)
5. [Pattern 4 — Builder](#pattern-4--builder)
6. [Pattern 5 — Prototype](#pattern-5--prototype)
7. [Full Summary & Quick Reference](#full-summary--quick-reference)

---

## Why Creational Patterns?

The naive approach to creating objects — writing `new ConcreteClass()` scattered all over your codebase — creates four problems over time:

| Problem | What goes wrong |
|---|---|
| **Tight coupling** | Your code is bound to a specific implementation, making it hard to swap |
| **No instance control** | You can accidentally create multiple instances of things that should be unique |
| **Polluted business logic** | Complex construction logic bleeds into classes that shouldn't care about it |
| **Hard to test** | You can't easily substitute a test double if creation is hardcoded |

Each creational pattern solves a specific variant of this problem. Think of them as different strategies for answering the question: *"Who is responsible for creating this object, and how?"*

---

## Pattern 1 — Singleton

### The Problem

Some resources in a system must exist as **exactly one instance**:
- A configuration manager
- A database connection pool
- A logger
- A thread pool

If multiple instances are created, you get inconsistent state, wasted resources, or incorrect behavior.

> A global variable is a naive fix — it works in simple cases but has no thread safety, no lazy initialization, and no encapsulation.

### Real-World Analogy

Think of the **President of a country**. There is exactly one at any point in time. Everyone who asks "who is the president?" gets a reference to the same person — not a new person each time.

### The Structure

```
+---------------------------+
|   ConfigurationManager    |
+---------------------------+
| - instance: static        |  ← lives at class level
| - config: Map             |
+---------------------------+
| - ConfigurationManager()  |  ← private constructor
| + getInstance(): static   |  ← the ONE way to get the instance
| + get(key): String        |
+---------------------------+
```

Three ingredients:
1. **Private constructor** — nobody outside can call `new`
2. **Static instance** — the single object lives at the class level
3. **Static accessor** — the only way to retrieve the instance

### Implementations (Worst → Best)

#### Version 1 — Naive (broken under multithreading)

```java
public class ConfigurationManager {
    private static ConfigurationManager instance; // not volatile — visibility problem

    private ConfigurationManager() { /* load config */ }

    // NOT thread-safe: two threads can both see instance == null and both create
    public static ConfigurationManager getInstance() {
        if (instance == null) {
            instance = new ConfigurationManager(); // ← race condition here
        }
        return instance;
    }
}
```

**Problem:** Two threads can simultaneously see `instance == null` and both call the constructor. Now you have two instances.

---

#### Version 2 — Synchronized Method (safe but slow)

```java
public static synchronized ConfigurationManager getInstance() {
    if (instance == null) {
        instance = new ConfigurationManager();
    }
    return instance;
}
```

**Problem:** Every call acquires a lock — even after the instance is already created. `getInstance()` is called very frequently; this becomes a bottleneck.

---

#### Version 3 — Double-Checked Locking (correct and efficient)

```java
public class ConfigurationManager {
    // volatile ensures that once instance is written, ALL threads see it immediately.
    // Without volatile: the JVM can reorder instructions — a thread might see a
    // non-null but partially-constructed object.
    private static volatile ConfigurationManager instance;

    private ConfigurationManager() { /* load config */ }

    public static ConfigurationManager getInstance() {
        if (instance == null) {                         // First check — no lock, fast path
            synchronized (ConfigurationManager.class) {
                if (instance == null) {                 // Second check — inside lock, safe
                    instance = new ConfigurationManager();
                }
            }
        }
        return instance;
    }
}
```

**Why two checks?**
- First check (outside lock): avoids acquiring a lock on every call after instance is created
- Second check (inside lock): handles the race condition — if two threads both passed the first check, only one constructs

---

#### Version 4 — Initialization-on-Demand Holder (cleanest)

```java
public class ConfigurationManager {
    private ConfigurationManager() { /* load config */ }

    // Inner class is only loaded when getInstance() is first called.
    // The JVM guarantees class loading is thread-safe — no synchronization needed.
    private static class Holder {
        private static final ConfigurationManager INSTANCE = new ConfigurationManager();
    }

    public static ConfigurationManager getInstance() {
        return Holder.INSTANCE;
    }
}
```

**Why is this the cleanest?**
- Lazy initialization (Holder class only loads when needed)
- Thread safety guaranteed by the JVM's class loading mechanism
- No `synchronized` keyword, no `volatile` — zero manual concurrency management

### Demo Output

```
[ConfigurationManager] Config loaded
Same instance? true
DB host: localhost
DB host via config2: prod-server   ← config2 IS config1, so it sees the change
```

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Exactly one shared resource is required (thread pool, config, registry) | You're using it for convenience, not correctness |
| State must be globally consistent | It makes testing harder (global state is hard to reset) |
| Expensive initialization should happen exactly once | Multiple instances would actually work fine |

> **Honest trade-off:** Singletons are often overused. They are essentially global state with a class wrapper. In modern production code, dependency injection frameworks (like Spring) manage single instances for you — you rarely write Singleton manually. But it appears in almost every LLD interview, and understanding why the naive version breaks is important.

---

## Pattern 2 — Factory Method

### The Problem

Your code needs to create objects, but the **exact type depends on runtime conditions**. If you hardcode `new ConcreteClass()`, you violate OCP — adding a new type forces you to modify existing code.

### Real-World Analogy

Think of a **logistics company**. It ships things. Early on, it only used trucks. Later it added ships, then drones. If "ship this package" was hardcoded to create a `Truck`, adding `Ship` requires rewriting core logic. Instead, the logistics company *delegates* the vehicle decision to a separate step — that's the factory.

### The Structure

```
NotificationService (abstract)           NotificationSender (interface)
+--------------------------------+        +---------------------------+
| + notifyUser(recipient, msg)   |        | + send(recipient, msg)    |
| # createSender(): abstract     |        | + getChannel(): String    |
+--------------------------------+        +---------------------------+
         ▲                                        ▲
         |                                        |
  ┌──────┴──────┐                    ┌────────────┼─────────────┐
  │             │                    │            │             │
EmailService  SmsService       EmailSender   SmsSender   PushSender
```

Four ingredients:
1. **Abstract creator** — defines the factory method as abstract
2. **Concrete creators** — subclasses that override the factory method with a specific product
3. **Product interface** — what all created objects must implement
4. **Concrete products** — the actual objects being created

### Implementation

```java
// Product interface
public interface NotificationSender {
    void send(String recipient, String message);
    String getChannel();
}

// Concrete products
public class EmailSender implements NotificationSender { ... }
public class SmsSender implements NotificationSender { ... }
public class PushNotificationSender implements NotificationSender { ... }

// Abstract creator — defines the factory method
public abstract class NotificationService {
    protected abstract NotificationSender createSender(); // ← the factory method

    // Template behavior — uses whatever the subclass provides
    public void notifyUser(String recipient, String message) {
        NotificationSender sender = createSender();
        sender.send(recipient, message);
    }
}

// Concrete creators — each decides which product to make
public class EmailNotificationService extends NotificationService {
    @Override
    protected NotificationSender createSender() {
        return new EmailSender(); // ← the decision is HERE, not in business logic
    }
}
```

### Simplified Variant: Static Factory (more common in practice)

The "pure" Factory Method using subclassing is less common in real code. You'll more often see a static factory method:

```java
public class NotificationSenderFactory {
    public static NotificationSender create(String channel) {
        switch (channel.toUpperCase()) {
            case "EMAIL": return new EmailSender();
            case "SMS":   return new SmsSender();
            case "PUSH":  return new PushNotificationSender();
            default: throw new IllegalArgumentException("Unknown channel: " + channel);
        }
    }
}

// Usage — caller never touches concrete classes
NotificationSender sender = NotificationSenderFactory.create("PUSH");
sender.send("device-token-xyz", "You have a new message");
```

**Key benefit:** The caller only depends on `NotificationSender` (the interface). Adding a new channel (`WhatsApp`) means adding a new class and one line in the factory — no existing code touched.

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Object type varies at runtime based on input or configuration | You only ever create one type — just use `new` directly |
| You want to isolate object creation from the code that uses it | The type decision is trivial |
| Adding new types should not require modifying existing code | — |

---

## Pattern 3 — Abstract Factory

### The Problem

Factory Method creates **one product**. What if you need to create **families of related products that must be used together**? Creating them independently risks mixing incompatible versions — like pairing a Windows-style button with a Mac-style dialog.

### Real-World Analogy

Think of a **furniture store**. If you're furnishing a room in "Modern" style, you want a Modern sofa, Modern table, and Modern chair — all from the same family. You don't want a Modern sofa paired with a Victorian chair. An Abstract Factory is the store's "collection selector" — you pick a style, and *everything* you get from it is consistent.

### The Structure

```
UIFactory (interface)                  
+---------------------------+          
| + createButton(): Button  |          
| + createTextField()       |          
| + createDialog()          |          
+---------------------------+          
         ▲                             
         |                             
  ┌──────┴──────┐                     
  │             │                     
WindowsUIFactory  MacUIFactory         
  │             │                     
  ▼             ▼                     
[Windows*]    [Mac*]                  
Button        Button                  
TextField     TextField               
Dialog        Dialog                  
```

Four ingredients:
1. **Abstract factory interface** — declares creation methods for each product type
2. **Concrete factories** — one per product family, implement all creation methods
3. **Abstract product interfaces** — one per product type (Button, TextField, Dialog)
4. **Concrete products** — the actual objects, grouped by family

### Implementation

```java
// Abstract products
public interface Button    { void render(); void onClick(); }
public interface TextField { void render(); String getValue(); }
public interface Dialog    { void render(); }

// Mac family — all consistent with each other
public class MacButton    implements Button    { ... } // rounded, fade effect
public class MacTextField implements TextField { ... } // rounded corners
public class MacDialog    implements Dialog    { ... } // sheet dialog

// Windows family — all consistent with each other
public class WindowsButton    implements Button    { ... } // flat, ripple effect
public class WindowsTextField implements TextField { ... }
public class WindowsDialog    implements Dialog    { ... }

// Abstract factory
public interface UIFactory {
    Button createButton();
    TextField createTextField();
    Dialog createDialog();
}

// Concrete factories — each produces one consistent family
public class MacUIFactory implements UIFactory {
    public Button createButton()       { return new MacButton(); }
    public TextField createTextField() { return new MacTextField(); }
    public Dialog createDialog()       { return new MacDialog(); }
}

// Client — only depends on UIFactory, never touches concrete classes
public class LoginScreen {
    private final Button loginButton;
    private final TextField usernameField;
    private final Dialog errorDialog;

    public LoginScreen(UIFactory factory) {
        // Guaranteed consistent family — no mismatching possible
        this.loginButton   = factory.createButton();
        this.usernameField = factory.createTextField();
        this.errorDialog   = factory.createDialog();
    }
}

// Wiring it all together
UIFactory factory = os.equals("MAC") ? new MacUIFactory() : new WindowsUIFactory();
LoginScreen screen = new LoginScreen(factory); // screen never knows which OS it's on
```

### Factory Method vs Abstract Factory — The Key Distinction

| | Factory Method | Abstract Factory |
|---|---|---|
| **Creates** | One product | A family of related products |
| **Mechanism** | Subclassing | Object composition |
| **Adding a new type** | Add a new subclass | Add a new concrete factory |
| **Use when** | One thing varies | Multiple things must stay consistent |

A simple way to remember:
- **Factory Method:** "Give me *a* vehicle"
- **Abstract Factory:** "Give me *everything* for a Mac UI" (button + textfield + dialog — all Mac)

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| You need products from the same family to be compatible | You only have one product type |
| You want to enforce consistency across a set of related objects | The family concept doesn't apply to your problem |
| You want to swap entire product families (e.g. theme change, OS change) | It adds more classes than the problem justifies |

---

## Pattern 4 — Builder

### The Problem

Some objects require many parameters to construct. **Telescoping constructors** (one constructor per combination of parameters) explode in count and become unreadable:

```java
// What does each null mean? What is true? What is false?
// You have to look at the constructor signature to understand this line.
new Order("ORD-001", "user-123", items, "CREDIT_CARD", null, true, false, null, "EXPRESS");
```

You also sometimes need to **validate the object as a whole** before constructing it — for example, an order must have at least one item. A constructor can't cleanly express that.

### Real-World Analogy

Think of **ordering a custom burger**. You tell the counter: "I want a brioche bun, beef patty, add cheese, add lettuce, no onions, BBQ sauce." You configure it piece by piece, then say "done — make it." The Builder pattern mirrors this: configure step by step, then call `build()` to get the final, validated object.

### The Structure

```
Order (final, immutable)              Order.Builder (static inner class)
+----------------------------+        +-----------------------------+
| - orderId: String          | ←──── | - orderId: String           |
| - customerId: String       |  build | - customerId: String        |
| - items: List<OrderItem>   |        | - items: List<OrderItem>    |
| - paymentMethod: String    |        | - expressDelivery: boolean  |
| - expressDelivery: boolean |        | - promoCode: String         |
| - promoCode: String        |        +-----------------------------+
+----------------------------+        | + addItem(): Builder        |
| + getters only (no setters)|        | + expressDelivery(): Builder|
+----------------------------+        | + promoCode(): Builder      |
                                      | + build(): Order            |
                                      +-----------------------------+
```

Four ingredients:
1. **Builder class** (static inner class) — holds all the fields while being configured
2. **Fluent setters** — each setter returns `this`, enabling chaining
3. **`build()` method** — validates and constructs the final object
4. **Private constructor** on the target class — forces everyone through the Builder

### Implementation

```java
public final class Order {
    // All fields are final — object is immutable after construction
    private final String orderId;
    private final String customerId;
    private final List<OrderItem> items;
    private final String paymentMethod;
    private final boolean expressDelivery;
    private final String promoCode;

    // Private constructor — ONLY the Builder can call this
    private Order(Builder builder) {
        this.orderId         = builder.orderId;
        this.customerId      = builder.customerId;
        this.items           = Collections.unmodifiableList(new ArrayList<>(builder.items));
        this.paymentMethod   = builder.paymentMethod;
        this.expressDelivery = builder.expressDelivery;
        this.promoCode       = builder.promoCode;
    }

    // Getters only — no setters
    public String getOrderId() { return orderId; }
    // ... other getters

    public static class Builder {
        // Required fields — passed in the constructor so you can't forget them
        private final String orderId;
        private final String customerId;
        private final String shippingAddress;
        private final String paymentMethod;

        // Optional fields — have sensible defaults
        private List<OrderItem> items = new ArrayList<>();
        private boolean expressDelivery = false;
        private String promoCode = null;

        public Builder(String orderId, String customerId, String shippingAddress, String paymentMethod) {
            // Fail fast — validate required fields immediately
            if (orderId == null || orderId.isBlank())
                throw new IllegalArgumentException("orderId is required");
            // ... validate others
            this.orderId = orderId;
            // ... assign others
        }

        // Fluent setters — each returns `this` to enable chaining
        public Builder addItem(OrderItem item) {
            this.items.add(item);
            return this;
        }

        public Builder expressDelivery(boolean express) {
            this.expressDelivery = express;
            return this;
        }

        public Builder promoCode(String code) {
            this.promoCode = code;
            return this;
        }

        // Final validation before constructing
        public Order build() {
            if (items.isEmpty()) {
                throw new IllegalStateException("Order must have at least one item");
            }
            return new Order(this);
        }
    }
}
```

### Usage — Clean, Self-Documenting

```java
// Each line is explicit about what it's setting — no mystery nulls
Order order = new Order.Builder("ORD-001", "USR-42", "Mumbai, India", "UPI")
        .addItem(new OrderItem("PROD-A", 2, 499.0))
        .addItem(new OrderItem("PROD-B", 1, 1299.0))
        .expressDelivery(true)
        .promoCode("SAVE20")
        .build();

// Missing items — builder throws before the broken object is ever created
try {
    Order bad = new Order.Builder("ORD-002", "USR-43", "Delhi, India", "CARD")
            .build(); // ← IllegalStateException: Order must have at least one item
} catch (IllegalStateException e) {
    System.out.println("Caught: " + e.getMessage());
}
```

### Demo Output

```
Order{id='ORD-001', customer='USR-42', items=2, payment='UPI', express=true, promo='SAVE20'}
Caught: Order must have at least one item
```

### Builder Design Decisions

| Decision | Rule of thumb |
|---|---|
| **Required fields** | Put them in the Builder's constructor — can't build without them |
| **Optional fields** | As fluent setter methods with sensible defaults |
| **Validation** | Validate required fields in Builder constructor; cross-field validation in `build()` |
| **Immutability** | Target object should be immutable — no setters, all fields `final` |

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Object has 4+ parameters, especially optional ones | Object only has 1–2 fields |
| Construction requires validation across multiple fields | All parameters are required — use a regular constructor |
| You want immutability with flexible construction | The step-by-step nature adds no real value |

> **In production:** Lombok's `@Builder` generates all this automatically. But understanding the manual version is essential for interviews and for knowing what Lombok is actually doing.

---

## Pattern 5 — Prototype

### The Problem

Creating objects from scratch is sometimes **expensive** — it requires DB calls, network requests, or complex initialization. If you need many similar objects, cloning an existing one is far cheaper than rebuilding each from scratch.

### Real-World Analogy

Think of a **document editor**. You have a complex document with formatting, styles, images, and embedded objects. You want to create a new document based on this one. You don't rebuild from scratch — you **duplicate it**, then modify the copy. That's prototyping.

### The Key Concept: Shallow vs Deep Copy

This is the most important thing to understand about Prototype:

```
Original Object
  ├── host: "api.example.com"    ← primitive/String — safely copied by value
  ├── port: 8080                 ← primitive — safely copied by value
  ├── allowedIPs: [ → List ]     ← reference type!
  └── settings:  { → Map }      ← reference type!

Shallow Copy                     Deep Copy
  ├── host: "api.example.com"     ├── host: "api.example.com"
  ├── port: 8080                  ├── port: 8080
  ├── allowedIPs: ──────────────→ ├── allowedIPs: [ copy of list ]
  └── settings: ───────────────→ └── settings: { copy of map }
       (SAME references!)              (NEW independent objects)
```

With a **shallow copy**, modifying the copy's list also modifies the original's list — they point to the same object. Almost always, you want a **deep copy**.

### Implementation

```java
public class ServerConfiguration implements Cloneable {
    private String host;
    private int port;
    private List<String> allowedIPs;      // mutable — needs deep copy
    private Map<String, String> settings;  // mutable — needs deep copy

    public ServerConfiguration(String host, int port) {
        this.host       = host;
        this.port       = port;
        this.allowedIPs = new ArrayList<>();
        this.settings   = new HashMap<>();
    }

    @Override
    public ServerConfiguration clone() {
        try {
            ServerConfiguration cloned = (ServerConfiguration) super.clone(); // shallow
            // Now manually deep-copy all mutable reference fields
            cloned.allowedIPs = new ArrayList<>(this.allowedIPs);
            cloned.settings   = new HashMap<>(this.settings);
            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone failed", e);
        }
    }
}
```

### Usage

```java
// Base config — imagine this required expensive DB/network calls to create
ServerConfiguration baseConfig = new ServerConfiguration("api.example.com", 8080);
baseConfig.addAllowedIP("10.0.0.1");
baseConfig.addAllowedIP("10.0.0.2");
baseConfig.addSetting("timeout", "30s");

// Clone for staging — cheap, immediate
ServerConfiguration stagingConfig = baseConfig.clone();
stagingConfig.setHost("staging.example.com");
stagingConfig.addAllowedIP("192.168.1.1"); // only in staging

// Deep copy — original is unaffected
System.out.println("Base IPs: " + baseConfig.getAllowedIPs());
// Output: Base IPs: [10.0.0.1, 10.0.0.2]  ← unchanged
```

### Demo Output

```
Base:    ServerConfig{host='api.example.com', port=8080, ips=[10.0.0.1, 10.0.0.2], ...}
Staging: ServerConfig{host='staging.example.com', port=8080, ips=[10.0.0.1, 10.0.0.2, 192.168.1.1], ...}

Base IPs unchanged: [10.0.0.1, 10.0.0.2]
```

### Real-World Uses of Prototype

| Scenario | Why clone instead of create |
|---|---|
| Game objects (enemies, tiles) | Many similar entities; creating each from scratch is wasteful |
| Config templates | One base config, many environment-specific variants |
| Undo/redo systems | Save a snapshot of state by cloning the current object |
| Object caching | Pre-create expensive objects, serve clones on request |

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Object creation is expensive (DB calls, complex init) | Object creation is cheap — just use `new` |
| Many similar objects are needed with small variations | The object has no mutable state — cloning adds no value |
| You want to create variants of a base configuration | Deep copying becomes complicated (e.g., cycles in object graph) |

---

## Full Summary & Quick Reference

### Pattern Comparison Table

| Pattern | Problem it solves | Key mechanism | Real-world trigger phrase |
|---|---|---|---|
| **Singleton** | Ensure exactly one instance exists | Private constructor + static accessor | "Config manager", "connection pool", "logger" |
| **Factory Method** | Decouple creation from usage; vary type at runtime | Subclass overrides a creation method | "Support multiple types of X" |
| **Abstract Factory** | Create consistent families of related objects | Factory interface per product family | "Cross-platform UI", "themed components" |
| **Builder** | Construct complex objects cleanly; validate before creation | Fluent API + `build()` method | "4+ params", "optional fields", "immutable object" |
| **Prototype** | Clone expensive objects instead of rebuilding | Deep copy via `clone()` | "Config template", "expensive init", "many similar objects" |

---

### Pattern Selection Guide

```
Need to create an object...
│
├── ...that must exist as exactly one instance everywhere?
│       → SINGLETON
│
├── ...but the exact type depends on runtime input?
│   ├── One varying type?
│   │       → FACTORY METHOD
│   └── A whole family of related types that must match?
│           → ABSTRACT FACTORY
│
├── ...with many parameters, some optional, needs validation?
│       → BUILDER
│
└── ...but creating from scratch is expensive — you have a base to copy?
        → PROTOTYPE
```

---

### Key Java Reminders

| Topic | What to remember |
|---|---|
| `volatile` in Singleton | Prevents instruction reordering; without it, a thread can see a non-null but partially constructed object |
| Double-checked locking | Two `null` checks: first outside lock (fast path), second inside lock (safe creation) |
| Initialization-on-demand Holder | Cleanest Singleton idiom; leverages JVM class loading guarantee |
| Builder `return this` | Enables fluent chaining; each setter must return `this` |
| Deep vs shallow copy | `super.clone()` is shallow; manually copy all `List`, `Map`, and mutable fields |
| `Cloneable` interface | Must implement to avoid `CloneNotSupportedException` from `Object.clone()` |

---

### Anti-Patterns to Avoid

| Anti-pattern | Better alternative |
|---|---|
| Overusing Singleton (global state everywhere) | Use dependency injection; let a framework manage scope |
| Telescoping constructors (one per param combo) | Builder pattern |
| Mixing creation logic in business logic | Factory Method or Abstract Factory |
| Shallow copy when deep copy is needed | Always explicitly deep-copy `List`, `Map`, and other mutable fields in `clone()` |
| Static factory with no interface | Return an interface type, not the concrete type |

---

*Next up → Phase 3, Part 2: Structural Patterns (Adapter, Decorator, Facade, Proxy, Composite)*

# Object-Oriented Programming (OOP) Principles

> Complete notes covering all four OOP principles — Encapsulation, Abstraction, Inheritance, and Polymorphism — with intuition, examples, comparisons, and key takeaways.

---

## Table of Contents

1. [Encapsulation](#1-encapsulation)
2. [Abstraction](#2-abstraction)
3. [Inheritance](#3-inheritance)
4. [Polymorphism](#4-polymorphism)
5. [Key Comparisons](#5-key-comparisons)
6. [How All Four Work Together](#6-how-all-four-work-together)

---

## 1. Encapsulation

### Intuition

Think of a **vending machine**. You put money in, press a button, get a snack. You don't reach inside and grab the snack yourself. You don't rewire the internal motor. The machine exposes only what you need — the slot and the buttons — and hides everything else.

This is exactly what encapsulation does for your objects. **Bundle the data and the operations on that data together, then control who can touch what.**

> **One-liner:** "Hide what doesn't need to be seen."

---

### What Encapsulation Actually Does

Most people think encapsulation is just about making fields `private`. That's only half of it.

1. **Data hiding** — restricting direct access to internal state
2. **Bundling** — keeping related data and behavior together in one place

---

### Part 1 — Data Hiding

#### Why raw public fields are dangerous

```java
public class UserProfile {
    public String email;
    public int age;
}
```

With this, any caller anywhere in your codebase can do:

```java
user.age = -500;
user.email = "not-an-email";
user.age = 0; // accidentally reset by a bug elsewhere
```

Your object has **no say** in what happens to its own data. Imagine this class is used in 50 different places — how do you enforce that age is always positive? You'd have to add validation in all 50 places. Miss one, and you have a bug.

#### The fix — make the object responsible for its own state

```java
public class UserProfile {
    private String email;
    private int age;

    public void setEmail(String email) {
        if (!email.contains("@")) {
            throw new IllegalArgumentException("Invalid email: " + email);
        }
        this.email = email;
    }

    public void setAge(int age) {
        if (age < 0 || age > 150) {
            throw new IllegalArgumentException("Age out of range: " + age);
        }
        this.age = age;
    }

    public String getEmail() { return email; }
    public int getAge() { return age; }
}
```

Validation logic now lives in **one place**. You change it once, it applies everywhere.

---

### Part 2 — Bundling Data and Behavior

```java
// BAD — data and behavior are separated (Anemic Domain Model anti-pattern)
public class Order {
    public List<Item> items;
    public double totalPrice;
}

// Somewhere else...
public class OrderService {
    public double calculateTotal(Order order) {
        double total = 0;
        for (Item item : order.items) total += item.price;
        return total;
    }
}
```

The `Order` class is a dumb data bag. Logic that *belongs to* an order lives somewhere else entirely.

```java
// GOOD — the order knows how to calculate its own total
public class Order {
    private List<Item> items = new ArrayList<>();

    public void addItem(Item item) {
        if (item == null) throw new IllegalArgumentException("Item cannot be null");
        items.add(item);
    }

    public double calculateTotal() {
        return items.stream().mapToDouble(Item::getPrice).sum();
    }

    public int itemCount() {
        return items.size();
    }
}
```

The order is no longer a passive container. **It knows things about itself and can answer questions about itself.**

---

### The BankAccount Example

```java
// BAD — internals are exposed, callers can corrupt state
public class BankAccount {
    public double balance;
}

// GOOD — state is protected, mutations go through controlled methods
public class BankAccount {
    private double balance;

    public BankAccount(double initialBalance) {
        if (initialBalance < 0) throw new IllegalArgumentException("Balance cannot be negative");
        this.balance = initialBalance;
    }

    public void deposit(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Deposit must be positive");
        this.balance += amount;
    }

    public void withdraw(double amount) {
        if (amount > balance) throw new IllegalStateException("Insufficient funds");
        this.balance -= amount;
    }

    public double getBalance() {
        return balance;
    }
}
```

---

### Real-World Example — Java's `ArrayList`

`ArrayList` is backed by a plain array internally. When the array gets full, it creates a new larger array and copies everything over. You never see the internal array. You never manage its size. `ArrayList` encapsulates all of that behind a clean interface — `add()`, `get()`, `remove()`.

One day the Java team could change the resizing strategy. **Your code doesn't change at all.** That's encapsulation enabling internal evolution without breaking callers.

---

### Access Modifiers

Access modifiers define the boundary between implementation and contract — they answer: **"Who is allowed to see and touch this?"**

| Modifier | Same Class | Subclass | Any Other Class | Use When |
|---|---|---|---|---|
| `private` | ✅ | ❌ | ❌ | Internal state and helper logic |
| `protected` | ✅ | ✅ | ❌ | Subclasses need it, not the world |
| `public` | ✅ | ✅ | ✅ | Intentional outward-facing interface |

#### `private` — Only This Class

```java
public class PasswordManager {
    private String hashedPassword;  // never expose raw hash

    private String hash(String raw) {  // internal utility, hide it
        return BCrypt.hashpw(raw, BCrypt.gensalt());
    }

    public boolean verify(String input) {
        return BCrypt.checkpw(input, hashedPassword);  // public behavior
    }
}
```

#### `protected` — This Class + Subclasses

```java
public class Animal {
    protected int energyLevel;  // subclasses can access this

    protected void breathe() {
        energyLevel--;
    }
}

public class Dog extends Animal {
    public void run() {
        energyLevel -= 10;  // ✅ allowed — Dog is a subclass
        breathe();           // ✅ allowed
    }
}

public class Main {
    public static void main(String[] args) {
        Dog dog = new Dog();
        dog.energyLevel = 999;  // ❌ not allowed — Main is unrelated
    }
}
```

#### The Golden Rule

```
Default to private. Promote only when there's a real reason.
private → protected → public
```

---

### What Encapsulation Buys You

- **Freedom to refactor internals** — Completely rewrite how a class works as long as the public interface stays the same.
- **Enforced invariants** — Rules like "balance can never be negative" are enforced in one place.
- **Easier debugging** — When `balance` is wrong, you know exactly where to look: `deposit()` and `withdraw()`.
- **Cleaner APIs** — Callers only see what's relevant, reducing cognitive load.

---

### Key Takeaways — Encapsulation

- Encapsulation = **data hiding + bundling data with behavior**
- Make fields `private` by default; expose only what callers genuinely need
- Put validation and business rules **inside the class**, not scattered across callers
- Every class is a **contract** — the public methods are what you promise the outside world
- The real payoff: internal implementation can change freely, callers are unaffected

---

## 2. Abstraction

### Intuition

When you use Google Maps to navigate, you type a destination and tap "Go." You don't think about which routing algorithm it uses, how it fetches live traffic data, how it renders map tiles, or how GPS coordinates are resolved. You only think about **what** it does — *get me from A to B*. The **how** is completely invisible.

This separation — **what something does vs. how it does it** — is abstraction. You **program against a concept**, not against a specific implementation.

> **One-liner:** "Expose what, hide how."

---

### The Problem It Solves

```java
// PROBLEM — tightly coupled to Stripe
public class OrderService {
    private StripePaymentProcessor stripe = new StripePaymentProcessor();

    public void checkout(Order order) {
        stripe.process(order.toPaymentRequest());
    }
}
```

Every new payment provider requires modifying `OrderService`. It now knows about Stripe internals, PayPal internals, Razorpay internals. **`OrderService`'s job is to manage orders, not to know how each payment provider works.**

Abstraction fixes this by giving `OrderService` something stable to depend on — a concept, not a specific thing.

---

### The Two Tools Java Gives You

#### 1. Interface — Pure Contract

An interface defines **what** must be done, with zero implementation.

```java
// Abstraction: define what a payment processor does, not how
public interface PaymentProcessor {
    PaymentResult process(PaymentRequest request);
    boolean refund(String transactionId, double amount);
}

// Concrete implementations hide the how
public class StripePaymentProcessor implements PaymentProcessor {
    @Override
    public PaymentResult process(PaymentRequest request) {
        // Stripe SDK calls, retry logic, error mapping
    }

    @Override
    public boolean refund(String transactionId, double amount) {
        // Stripe refund logic
    }
}

public class RazorpayPaymentProcessor implements PaymentProcessor {
    @Override
    public PaymentResult process(PaymentRequest request) {
        // Razorpay SDK — completely different internally
    }

    @Override
    public boolean refund(String transactionId, double amount) {
        // Razorpay refund flow
    }
}

// Caller only knows about the abstraction
public class OrderService {
    private final PaymentProcessor paymentProcessor;

    public OrderService(PaymentProcessor paymentProcessor) {
        this.paymentProcessor = paymentProcessor;
    }

    public void checkout(Order order) {
        PaymentResult result = paymentProcessor.process(order.toPaymentRequest());
        // OrderService has no idea if this is Stripe or Razorpay — and doesn't care
    }
}
```

#### 2. Abstract Class — Partial Implementation

Use it when multiple implementations share common logic that shouldn't be duplicated.

```java
public abstract class DataExporter {

    // Template method — defines the skeleton, subclasses fill in the steps
    public final void export(List<Record> records) {
        List<Record> validated = validate(records);   // shared logic
        String formatted = format(validated);          // subclass-specific
        compress(formatted);                           // shared logic
        deliver(formatted);                            // subclass-specific
    }

    private List<Record> validate(List<Record> records) {
        return records.stream()
                      .filter(r -> r != null && r.isComplete())
                      .collect(toList());
    }

    // Subclasses MUST implement these
    protected abstract String format(List<Record> records);
    protected abstract void deliver(String data);
}

public class CsvExporter extends DataExporter {
    @Override
    protected String format(List<Record> records) { /* CSV formatting */ }

    @Override
    protected void deliver(String data) { /* Write to file system */ }
}

public class JsonApiExporter extends DataExporter {
    @Override
    protected String format(List<Record> records) { /* JSON formatting */ }

    @Override
    protected void deliver(String data) { /* POST to external API */ }
}
```

This is the **Template Method Pattern** — one of the most natural expressions of abstraction.

---

### Interface vs Abstract Class

```
Ask yourself: "Is this a contract, or a base type?"
```

| Situation | Use |
|---|---|
| Multiple unrelated classes share a behavior contract | Interface |
| You want to share common implementation across subclasses | Abstract class |
| A class needs to conform to multiple contracts | Interface |
| You want to enforce a workflow with variable steps | Abstract class |
| Designing for external consumers / APIs | Interface |

**Practical rule:** Prefer interfaces for defining *what* something can do. Use abstract classes when you genuinely have shared *implementation* to offer subclasses.

---

### Abstraction in the Java Standard Library

```java
// List interface — same code works for all implementations
List<String> list = new ArrayList<>();   // backed by array
List<String> list = new LinkedList<>();  // backed by linked list
List<String> list = new CopyOnWriteArrayList<>();  // thread-safe variant

list.add("item");  // your code doesn't change
list.get(0);

// InputStream — same code regardless of where bytes come from
InputStream fromFile    = new FileInputStream("data.txt");
InputStream fromNetwork = socket.getInputStream();
InputStream fromMemory  = new ByteArrayInputStream(bytes);

public void process(InputStream in) {
    // doesn't know or care where the bytes come from
}
```

---

### The Signal to Introduce Abstraction

| Requirement phrase | What it signals |
|---|---|
| "Support multiple X" | Interface for X |
| "Should be pluggable / configurable" | Interface injected via constructor |
| "Different strategies for Y" | Strategy pattern — interface for Y |
| "New types may be added in future" | Abstract base or interface |
| "Works regardless of underlying Z" | Abstraction over Z |

---

### What Abstraction Buys You

- **Swap implementations freely** — Change Stripe to Razorpay without touching `OrderService`
- **Test in isolation** — Inject a mock `PaymentProcessor` in tests without hitting real APIs
- **Reason at the right level** — Don't think about Stripe's retry logic when reading `OrderService`
- **Extend without modifying** — New requirements satisfied by adding code, not changing existing code

---

### Key Takeaways — Abstraction

- Abstraction = separating **what** from **how**. Define the contract, hide the details.
- **Interfaces** for pure contracts. **Abstract classes** for shared implementation + contract.
- The signal: *"support multiple X"* or *"more types may be added"* → introduce an abstraction.
- Real payoff: callers depend on stable concepts, not fragile implementations.
- Study Java's standard library — `List`, `InputStream`, `Executor` — masterclasses in abstraction design.

> **Mental model:** Abstraction is a wall between "what I need" and "how it's done." The stronger that wall, the more freedom both sides have to evolve independently.

---

## 3. Inheritance

### Intuition

A dog *is an* animal. A car *is a* vehicle. A savings account *is a* bank account.

Because a dog *is an* animal, everything true about animals is automatically true about dogs. Animals breathe → dogs breathe. Animals eat → dogs eat. But dogs also do specific things — they bark, they fetch.

Inheritance in OOP mirrors this idea exactly. A subclass **automatically gets everything the parent has**, and can then **add or change** what it needs.

> **One-liner:** "Is-a relationships and shared contracts."

---

### What Actually Happens When You Inherit

```java
public abstract class BankAccount {
    protected String accountId;
    protected double balance;

    public BankAccount(String accountId, double initialBalance) {
        this.accountId = accountId;
        this.balance = initialBalance;
    }

    public void deposit(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Deposit must be positive");
        balance += amount;
    }

    public void withdraw(double amount) {
        if (amount > balance) throw new IllegalStateException("Insufficient funds");
        balance -= amount;
    }

    public abstract double calculateInterest();

    public double getBalance() { return balance; }
}

public class SavingsAccount extends BankAccount {
    private final double interestRate;

    public SavingsAccount(String accountId, double initialBalance, double interestRate) {
        super(accountId, initialBalance);
        this.interestRate = interestRate;
    }

    @Override
    public double calculateInterest() {
        return balance * interestRate;
    }
}
```

`SavingsAccount` wrote exactly one method. But it got `deposit()`, `withdraw()`, `getBalance()`, `accountId`, and `balance` for free.

---

### The Three Things a Subclass Can Do

#### 1. Inherit and use as-is

```java
public class SavingsAccount extends BankAccount {
    // deposit() and withdraw() are perfect as-is — no need to touch them
}
```

#### 2. Override — replace the parent's behavior

```java
public class CurrentAccount extends BankAccount {
    private double overdraftLimit;

    @Override
    public void withdraw(double amount) {
        // Different rule — can go below zero up to overdraft limit
        if (amount > balance + overdraftLimit) {
            throw new IllegalStateException("Exceeds overdraft limit");
        }
        balance -= amount;
    }

    @Override
    public double calculateInterest() {
        return 0; // Current accounts earn no interest
    }
}
```

#### 3. Extend — call parent's behavior, then add more

```java
public class AuditedAccount extends BankAccount {
    private List<String> auditLog = new ArrayList<>();

    @Override
    public void deposit(double amount) {
        super.deposit(amount);  // run parent's validation and logic first
        auditLog.add("DEPOSIT: " + amount + " at " + LocalDateTime.now());
    }

    @Override
    public void withdraw(double amount) {
        super.withdraw(amount);  // run parent's logic first
        auditLog.add("WITHDRAW: " + amount + " at " + LocalDateTime.now());
    }
}
```

`super.deposit()` calls the parent's deposit. Then the audit log entry is added on top. You're **extending** behavior, not replacing it.

---

### The `super` Keyword

`super` is how a subclass reaches back up to the parent.

```java
public class SavingsAccount extends BankAccount {
    public SavingsAccount(String accountId, double balance, double interestRate) {
        super(accountId, balance);  // MUST call parent constructor first
        this.interestRate = interestRate;
    }
}
```

- **In constructors** — `super(...)` must be the first line
- **In methods** — `super.methodName()` calls the parent's version of an overridden method

---

### Abstract Classes — Enforcing a Contract

An abstract class says: *"I'll give you shared behavior, but you MUST implement these specific things yourself."*

```java
public abstract class ReportGenerator {

    // Final — subclasses cannot override the overall workflow
    public final void generate() {
        List<Record> data = fetchData();        // shared
        validateData(data);                      // shared
        String content = formatContent(data);    // subclass-specific
        applyBranding(content);                  // shared
        deliver(content);                        // subclass-specific
    }

    private List<Record> fetchData() { /* common DB query */ }
    private void validateData(List<Record> data) { /* common validation */ }
    private void applyBranding(String content) { /* common header/footer */ }

    protected abstract String formatContent(List<Record> data);
    protected abstract void deliver(String content);
}

public class PdfReportGenerator extends ReportGenerator {
    @Override
    protected String formatContent(List<Record> data) { /* PDF formatting */ }

    @Override
    protected void deliver(String content) { /* Save as PDF */ }
}
```

This is the **Template Method Pattern** — define the skeleton, let subclasses fill in the steps.

---

### The Dangers of Inheritance

#### Problem 1 — Tight Coupling to Parent (Fragile Base Class Problem)

Every subclass is permanently coupled to its parent. When the parent changes, all subclasses are affected — even ones that don't care.

```java
public abstract class BankAccount {
    protected double balance;
    protected String currency = "INR"; // new field added

    public void deposit(double amount) {
        double converted = convertToCurrency(amount, currency); // behavior changed
        balance += converted;
    }
}
```

Suddenly all subclasses — `SavingsAccount`, `CurrentAccount`, `FixedDepositAccount` — are affected. Some might break silently.

#### Problem 2 — Inheritance for Reuse is Wrong

```java
// WRONG — Stack is NOT an ArrayList
// But someone did this to reuse ArrayList's methods
public class Stack extends ArrayList<Integer> {
    public void push(int value) { add(value); }
    public int pop() { return remove(size() - 1); }
}
```

Now `Stack` inherits `add()`, `remove(index)`, `set()` — operations that **break the stack contract**. Java's `java.util.Stack` actually made this mistake. It's considered a design error.

#### Problem 3 — Deep Hierarchies

```
Vehicle → MotorVehicle → Car → ElectricCar → ElectricSUV → LuxuryElectricSUV
```

Six levels deep. To understand `LuxuryElectricSUV`, you trace through six classes.

> **Rule of thumb:** If your hierarchy is more than 2-3 levels deep, something is probably wrong.

---

### Prefer Composition Over Inheritance

Instead of inheriting behavior, **hold a reference** to an object that has the behavior you need.

```java
// COMPOSITION approach — loosely coupled
public class NotificationService {
    private final MessageSender sender;

    public NotificationService(MessageSender sender) {
        this.sender = sender;
    }

    public void notify(String message) {
        sender.send(message);
    }
}

public class EmailSender implements MessageSender {
    public void send(String message) { /* email logic */ }
}

public class SmsSender implements MessageSender {
    public void send(String message) { /* SMS logic */ }
}
```

With composition you can swap `EmailSender` for `SmsSender` at runtime. With inheritance you cannot — the type is fixed at compile time.

#### The Decisive Question

```
IS-A  → consider inheritance
HAS-A → use composition
```

A `Dog` IS-A `Animal` → inheritance makes sense.
A `Car` HAS-A `Engine` → composition makes sense.
A `NotificationService` HAS-A `Sender` → composition makes sense.

---

### When Inheritance Is Genuinely the Right Tool

**1. Clear IS-A with stable base class**
```java
public class IllegalArgumentException extends RuntimeException { }
public class FileNotFoundException extends IOException { }
// Exception hierarchies — one of the cleanest uses of inheritance
```

**2. Template Method Pattern — enforcing a workflow**
```java
public abstract class DataMigration {
    public final void run() {
        backup();
        migrate();    // abstract
        validate();   // abstract
        cleanup();
    }
}
```

**3. Framework extension**

Spring, JUnit, Android — frameworks are deliberately designed with extension points via inheritance.

---

### `extends` vs `implements`

| | `extends` | `implements` |
|---|---|---|
| **Used for** | Class → Class, Interface → Interface | Class → Interface |
| **Gets behavior?** | Yes — inherits methods and fields | No — must implement everything itself |
| **How many?** | Only one | As many as needed |
| **Relationship** | IS-A | CAN-DO (fulfills a contract) |

```java
// A class can only extend one class
public class SavingsAccount extends BankAccount { }

// But implement as many interfaces as needed
public class StripeProcessor implements PaymentProcessor,
                                        Loggable,
                                        Auditable,
                                        HealthCheckable { }

// Can also combine both
public class StripeProcessor extends BasePaymentProcessor
                             implements PaymentProcessor, Auditable { }
```

> `extends` = **identity**. You are a more specific version of something.
> `implements` = **capability**. You can do something.

---

### Key Takeaways — Inheritance

- Inheritance models IS-A relationships — a subclass **is a** more specific version of the parent
- Three things a subclass can do: **use as-is**, **override** (replace), **extend** (add with `super`)
- Abstract classes enforce a contract — subclasses must implement abstract methods
- **The Fragile Base Class Problem** — changes to parent break subclasses unexpectedly
- **Prefer composition over inheritance** — unless there's a genuine IS-A relationship
- Deep hierarchies (3+ levels) are a design smell
- IS-A → inheritance. HAS-A → composition.

> **Mental model:** Inheritance is a strong claim. You're saying *"this thing fundamentally IS that other thing."* Make that claim carefully. When in doubt, compose instead.

---

## 4. Polymorphism

### Intuition

The word *polymorphism* comes from Greek — *poly* means many, *morph* means form. **One thing, many forms.**

Think about the `+` operator. You use it for `2 + 3` (adds numbers) and `"Hello" + " World"` (joins strings). Same operator, different behavior depending on what you apply it to.

In software, polymorphism means you write code against a general type, and the **right specific behavior fires automatically** based on the actual object at runtime.

> **One-liner:** "One interface, many behaviors."

---

### The Problem It Solves

Without polymorphism, you have to do this:

```java
for (BankAccount account : accounts) {
    if (account instanceof SavingsAccount) {
        SavingsAccount sa = (SavingsAccount) account;
        totalInterest += sa.calculateSavingsInterest();
    } else if (account instanceof CurrentAccount) {
        CurrentAccount ca = (CurrentAccount) account;
        totalInterest += ca.calculateCurrentInterest();
    } else if (account instanceof FixedDepositAccount) {
        FixedDepositAccount fd = (FixedDepositAccount) account;
        totalInterest += fd.calculateFdInterest();
    }
    // add new account type → modify this code again
}
```

Every time a new account type is added, you modify this loop. It knows about every concrete type. It's **fragile, repetitive, and coupled to implementation details**.

With polymorphism:

```java
for (BankAccount account : accounts) {
    totalInterest += account.calculateInterest(); // just this — never changes
}
```

---

### Two Types of Polymorphism

#### 1. Runtime Polymorphism — Method Overriding

The method to call is decided **at runtime** based on the actual object type. This is the powerful one.

```java
public abstract class Shape {
    public abstract double area();
}

public class Circle extends Shape {
    private double radius;
    public Circle(double radius) { this.radius = radius; }

    @Override
    public double area() {
        return Math.PI * radius * radius;
    }
}

public class Rectangle extends Shape {
    private double width, height;
    public Rectangle(double width, double height) {
        this.width = width; this.height = height;
    }

    @Override
    public double area() {
        return width * height;
    }
}

public class Triangle extends Shape {
    private double base, height;
    public Triangle(double base, double height) {
        this.base = base; this.height = height;
    }

    @Override
    public double area() {
        return 0.5 * base * height;
    }
}
```

```java
List<Shape> shapes = new ArrayList<>();
shapes.add(new Circle(5));
shapes.add(new Rectangle(4, 6));
shapes.add(new Triangle(3, 8));

for (Shape shape : shapes) {
    System.out.println(shape.area()); // different method fires each time
}
// Output:
// 78.53 (Circle's area)
// 24.0  (Rectangle's area)
// 12.0  (Triangle's area)
```

The variable type is `Shape`. But Java looks at the **actual object** at runtime and calls the right `area()`. This decision at runtime is called **dynamic dispatch**.

#### 2. Compile-Time Polymorphism — Method Overloading

The method to call is decided **at compile time** based on the method signature — number and types of parameters.

```java
public class PaymentService {

    public void processPayment(double amount) {
        System.out.println("Processing cash payment: " + amount);
    }

    public void processPayment(double amount, String currency) {
        System.out.println("Processing " + currency + " payment: " + amount);
    }

    public void processPayment(double amount, String currency, String cardNumber) {
        System.out.println("Processing card ending in "
            + cardNumber.substring(cardNumber.length() - 4));
    }
}
```

The compiler reads your arguments and picks the right method. No runtime decision needed.

> Runtime polymorphism is significantly more powerful for system design. Compile-time overloading is mostly a convenience feature.

---

### How Dynamic Dispatch Actually Works

When you write:

```java
Shape shape = new Circle(5);
shape.area();
```

The variable `shape` is of type `Shape`. But Java doesn't call `Shape.area()`. It looks at the actual object in memory — which is a `Circle` — and calls `Circle.area()`.

Java maintains a **vtable** (virtual method table) for each class. When a method is called, Java looks up the vtable of the actual object to find the right method to execute.

```
Shape reference → points to → Circle object in memory
                               Circle's vtable: {
                                 area()     → Circle.area()
                                 describe() → Shape.describe()
                               }
```

This lookup happens at runtime, every time.

---

### Polymorphism Through Interfaces

Polymorphism doesn't require inheritance. Interfaces give you the same power — and in real systems, this is more common.

```java
public interface Drawable {
    void draw();
    void resize(double factor);
}

public class Circle implements Drawable { ... }
public class Square implements Drawable { ... }
public class Image implements Drawable { ... }

List<Drawable> canvas = new ArrayList<>();
canvas.add(new Circle(10));
canvas.add(new Square(5));
canvas.add(new Image("photo.png"));

canvas.forEach(Drawable::draw);         // render everything
canvas.forEach(d -> d.resize(2.0));    // resize everything
```

`Circle`, `Square`, and `Image` have nothing to do with each other — no shared parent. Yet they work uniformly through the `Drawable` contract.

---

### Real-World System Design Example — Payment Processing

```java
public interface PaymentProcessor {
    PaymentResult process(PaymentRequest request);
    boolean refund(String transactionId, double amount);
    String getProviderName();
}

public class StripeProcessor implements PaymentProcessor { ... }
public class RazorpayProcessor implements PaymentProcessor { ... }
public class UpiProcessor implements PaymentProcessor { ... }

public class CheckoutService {
    private final PaymentProcessor processor;

    public CheckoutService(PaymentProcessor processor) {
        this.processor = processor;
    }

    public void checkout(Order order) {
        PaymentResult result = processor.process(order.toPaymentRequest());

        if (!result.isSuccess()) {
            throw new PaymentFailedException("Payment failed via "
                + processor.getProviderName());
        }

        order.markPaid();
    }
}
```

```java
// Inject whichever processor the user chose
CheckoutService service = new CheckoutService(new RazorpayProcessor());
service.checkout(order);

// Tomorrow, add PhonePe — CheckoutService doesn't change at all
CheckoutService service = new CheckoutService(new PhonePeProcessor());
service.checkout(order);
```

`CheckoutService` **never needs to change** when new payment providers are added. That's the engineering value of polymorphism.

---

### The `instanceof` Signal

Whenever you see this pattern — it's a **design smell**:

```java
if (account instanceof SavingsAccount) {
    // ...
} else if (account instanceof CurrentAccount) {
    // ...
}
```

This means polymorphism is missing. The logic that belongs inside each class has leaked out into the caller. The fix is always the same — push the behavior back into the classes and call it through a common interface:

```java
// Before — caller knows too much
if (notification instanceof EmailNotification) {
    emailService.send(((EmailNotification) notification).getEmail());
} else if (notification instanceof SmsNotification) {
    smsService.send(((SmsNotification) notification).getPhone());
}

// After — each type knows how to deliver itself
public interface Notification {
    void deliver();
}

notification.deliver(); // caller is now clean
```

---

### Key Takeaways — Polymorphism

- Polymorphism = one interface, many behaviors. The right behavior fires automatically.
- **Runtime polymorphism** (overriding) — decided at runtime via dynamic dispatch. The powerful one.
- **Compile-time polymorphism** (overloading) — decided at compile time. A convenience feature.
- `instanceof` chains are a signal that polymorphism is missing — push behavior back into the types.
- In real systems, polymorphism through interfaces is more common and more flexible than through inheritance.
- The engineering payoff: code that calls through an interface **never needs to change** when new implementations are added.

> **Mental model:** Polymorphism is what makes abstraction useful. Abstraction defines the contract. Polymorphism is what makes that contract powerful — one piece of code works correctly for every type that fulfills it, including types that don't exist yet.

---

## 5. Key Comparisons

### Encapsulation vs Abstraction

These feel similar on the surface but solve completely different problems.

| | Encapsulation | Abstraction |
|---|---|---|
| **Question it answers** | How do I protect my internal state? | How do I hide complexity from my callers? |
| **Problem it solves** | Preventing corruption of data | Reducing what the caller needs to think about |
| **Who benefits** | The class itself | The caller |
| **Tool in Java** | `private` fields + getters/setters | Interfaces and abstract classes |
| **Level** | Inside a single class | Between components in a system |

**The ATM analogy:**

- **Abstraction** is the ATM screen and buttons — simple operations that hide complex server calls, database transactions, and cash mechanisms behind them.
- **Encapsulation** is the locked steel box the ATM sits in — cash is protected, internal mechanisms are locked away, access only through controlled slots.

> Encapsulation protects **your data** from the outside world.
> Abstraction protects **the outside world** from your complexity.

---

### Abstraction vs Composition

These are not alternatives — they solve different problems and almost always work together.

| | Abstraction | Composition |
|---|---|---|
| **Core question** | What can this thing do? | What does this thing have? |
| **Problem solved** | Decouple callers from implementations | Build complex behavior without inheritance |
| **Tool in Java** | Interfaces, abstract classes | Object references as fields |
| **Relationship** | IS-A-KIND-OF contracts | HAS-A relationships |

**Abstraction** defines what the parts can do.
**Composition** defines how the parts are assembled.

In well-designed systems you almost always see both together — abstraction gives composition its flexibility. By depending on interfaces rather than concrete classes, composed objects become interchangeable.

---

### Inheritance vs Composition

This is one of the most important design decisions in software engineering.

| | Inheritance | Composition |
|---|---|---|
| **Relationship** | IS-A | HAS-A |
| **Coupling** | Tight — bound to parent permanently | Loose — coupled only to what you hold |
| **Flexibility** | Fixed at compile time | Swappable at runtime |
| **Reuse** | Automatic — get everything parent has | Explicit — use only what you need |
| **Risk** | Fragile base class problem | Slightly more setup code |
| **When parent changes** | All subclasses affected | No impact |
| **Right signal** | Genuine IS-A, stable base | HAS-A, or IS-A with volatile base |

> **When in doubt, compose. Reach for inheritance only when the IS-A relationship is real, obvious, and unlikely to become a liability.**

---

## 6. How All Four Work Together

The four principles are not independent — they form a system where each enables the next.

```
Abstraction   →   defines the contract (PaymentProcessor interface)
      ↓
Encapsulation →   each implementation protects its own internal state
      ↓
Inheritance   →   optional shared behavior via abstract base class
      ↓
Polymorphism  →   caller uses the contract, right behavior fires automatically
```

### In Practice — Payment System

```java
// ABSTRACTION — defines the contract, hides the how
public interface PaymentProcessor {
    PaymentResult process(PaymentRequest request);
    boolean refund(String transactionId, double amount);
}

// ENCAPSULATION — each implementation protects its own state
public class StripeProcessor implements PaymentProcessor {
    private final String apiKey;        // hidden — callers can't access
    private final RetryPolicy retry;    // hidden — internal concern

    public PaymentResult process(PaymentRequest request) {
        validateRequest(request);       // internal validation
        return callStripeApi(request);  // internal API call
    }

    private void validateRequest(PaymentRequest request) { ... }  // private helper
    private PaymentResult callStripeApi(PaymentRequest request) { ... }  // private helper
}

// INHERITANCE — shared behavior via abstract base
public abstract class BasePaymentProcessor implements PaymentProcessor {
    protected void logTransaction(String id) { ... }   // all processors need this
    protected void validateRequest(PaymentRequest r) { ... }  // common validation
}

public class StripeProcessor extends BasePaymentProcessor {
    @Override
    public PaymentResult process(PaymentRequest request) {
        validateRequest(request);  // from parent
        logTransaction("stripe");  // from parent
        // Stripe-specific logic
    }
}

// POLYMORPHISM — one interface, many behaviors, right one fires automatically
List<PaymentProcessor> processors = List.of(
    new StripeProcessor(),
    new RazorpayProcessor(),
    new UpiProcessor()
);

// This code works for all processors — today and in the future
for (PaymentProcessor processor : processors) {
    processor.process(request);  // right implementation fires automatically
}
```

### The Mental Models to Carry Forward

| Principle | Mental Model |
|---|---|
| **Encapsulation** | Every class is a contract. Public methods are promises. Internals are private business. |
| **Abstraction** | A wall between "what I need" and "how it's done." Stronger wall = more freedom on both sides. |
| **Inheritance** | A strong claim — "this thing IS that thing." Make it carefully. When in doubt, compose. |
| **Polymorphism** | What makes abstraction useful. One interface that works for every type that fulfills it — including types that don't exist yet. |

---

*These notes cover the four OOP principles as discussed — with intuition, code examples, real-world systems, trade-offs, and comparisons. Refer back to individual sections as needed when encountering these concepts in system design, code reviews, or LLD interviews.*

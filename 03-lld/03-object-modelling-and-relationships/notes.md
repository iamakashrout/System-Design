# Object Modeling and Relationships — Complete Notes

> **Phase 2 of the LLD Learning Roadmap**
> Language: Java | Goal: Interview-ready, production-quality design

---

## Table of Contents

1. [Step 1 — Identifying Entities and Responsibilities](#step-1--identifying-entities-and-responsibilities)
2. [Step 2 — The Four Object Relationships](#step-2--the-four-object-relationships)
3. [Step 3 — Inheritance vs Composition in Practice](#step-3--inheritance-vs-composition-in-practice)
4. [Step 4 — Designing Clean APIs](#step-4--designing-clean-apis)
5. [Full Example — Library Management System](#full-example--library-management-system)
6. [Quick Reference — Decision Rules](#quick-reference--decision-rules)

---

## Step 1 — Identifying Entities and Responsibilities

### The Big Picture

Before writing any code, you need to understand *what exists* in the problem and *who is responsible for what*. Getting this wrong leads to logic living in the wrong class — causing duplication, fragile code, and cascading changes every time requirements shift.

---

### The Noun-Verb Technique

Read the problem statement and label everything:

| What you find | What it becomes |
|---|---|
| **Nouns** | Candidate classes / entities |
| **Verbs** | Candidate methods / behaviors |
| **Adjectives / quantities** | Candidate fields |

**Example problem statement:**

> "A library has books. Members can borrow and return books. A book can have multiple copies. Each borrowing has a due date and may incur a fine if returned late."

**Extraction:**

- **Nouns →** Library, Book, Member, BookCopy, Borrowing, Fine
- **Verbs →** borrow, return, calculate (fine), incur (fine)
- **Properties →** due date, fine amount, copy count, availability

This gives you your first draft vocabulary. But raw extraction isn't enough — you also need to assign responsibilities.

---

### The Responsibility Assignment Question

For every entity, ask: **"What does this thing *know*, and what does this thing *do*?"**

| Entity | Knows | Does |
|---|---|---|
| `Book` | title, author, ISBN | — |
| `BookCopy` | its Book, availability | — |
| `Member` | name, ID, active borrowings | borrow, return |
| `Borrowing` | member, copy, borrow date, due date | calculate fine |
| `Library` | all books, all members | find book, register member |

**Key insight:** `Borrowing` knows the due date, so it is responsible for fine calculation — *not* `Member`, *not* `Library`. **Logic should live closest to the data it needs.**

This is called the **Information Expert Principle**.

---

### Classifying Each Entity

Not every noun becomes a class. After extraction, classify each one:

| Type | Description | Example |
|---|---|---|
| **Class / Entity** | Has identity, state, and behavior | `Member`, `Order` |
| **Enum** | Finite, known set of states | `DeliveryStatus`, `OrderStatus` |
| **Value Object** | Just structured data, no identity | `ISBN`, `Money`, `Penalty` |

**How to tell a value object from an entity:** Ask — "Do two objects with the same data represent the same *thing*?" If yes → value object (two `ISBN("9780134685991")` are the same). If no → entity (two members named "Akash" are different people).

---

### The "Unstated Noun" Smell

Problem statements often hide entities that your design still needs. Watch for it when:

> A single entity needs to track multiple independent states of the same thing.

**Example:** "A book can have multiple copies." The word `BookCopy` doesn't appear, but if you model just `Book` and try to track which copy is borrowed, you're immediately stuck. `BookCopy` is hiding there — you have to introduce it yourself.

Similarly, "an order contains items" hides `OrderLineItem` — because you need to store quantity and price-at-time-of-order (prices can change), not just a reference to the menu item.

---

### The Three-Pass Discipline

Step 1 is three passes, not one:

1. **Extract** — Mechanically label nouns, verbs, adjectives from the problem statement
2. **Classify** — For each noun: class, enum, or value object?
3. **Assign** — For each verb: which entity owns the data this behavior needs? That entity gets the method.

The output is a **vocabulary** and a **responsibility map** — not code yet. Everything that follows flows from getting this right.

---

## Step 2 — The Four Object Relationships

### The Big Picture

Once you have entities, you need to model how they connect. There are four levels of "closeness" between objects, ranging from loosest to tightest:

> **Dependency → Association → Aggregation → Composition**

---

### Relationship 1: Dependency — "I borrow, I don't keep"

**The loosest connection.** Class A uses class B only inside a method — as a parameter or local variable. After the method returns, A has no memory of B.

**Real-world analogy:** You borrow a pen to sign a document. Once done, you hand it back. You don't carry the pen everywhere.

```java
public class Invoice {
    private String content;

    // Invoice uses PdfExporter only during this method call
    // No field stored — purely temporary use
    public void export(PdfExporter exporter) {
        exporter.export(this.content);
    }
}
```

**Signal in code:** The external class appears only as a **method parameter or local variable** — never as a field.

**Signal in problem statement:** "A report generator *uses* a printer to print reports."

---

### Relationship 2: Association — "I know you, I keep your number"

**One step closer.** Class A holds a **field reference** to class B. This reference persists across method calls. But neither object owns the other — both exist independently and their lifecycles are unrelated.

**Real-world analogy:** A doctor has a list of patients. The doctor knows patients over time. If the doctor retires, the patients don't disappear. If a patient moves cities, the doctor doesn't disappear either.

```java
public class Doctor {
    private String name;
    private List<Patient> patients; // association — Doctor knows about Patients

    // Patients are passed in — Doctor doesn't create them
    public void addPatient(Patient patient) {
        this.patients.add(patient);
    }
}
```

**Signal in code:** A **field** holds the reference. Both objects are created independently. Neither creates the other.

**Dependency vs Association — the one clean rule:**
- Field-level reference → **Association** (long-term)
- Method parameter only → **Dependency** (short-term)

---

### Relationship 3: Aggregation — "You're part of my team, but you'll survive without me"

**Containment without lifecycle ownership.** Class A manages or collects objects of class B. But B was created outside A, can be shared, and survives if A is destroyed.

**Real-world analogy:** A football team has players. The team brings existing people together. If the team is disbanded, the players don't vanish — they join other teams.

```java
public class Team {
    private List<Player> players; // aggregation

    // Players are created OUTSIDE and passed IN
    public void addPlayer(Player player) {
        this.players.add(player);
    }

    public void removePlayer(Player player) {
        this.players.remove(player);
    }
}
```

**Signal in code:** Objects are **passed in from outside** via `add(X x)` methods. The container doesn't call `new X()` anywhere.

---

### Relationship 4: Composition — "You exist because I exist"

**The tightest containment.** Class A creates class B internally, owns it completely, and B has no meaningful existence outside A. When A is destroyed, B is destroyed.

**Real-world analogy:** A human body has a heart. The heart exists solely within the body. It makes no sense to have a heart "floating around" without a body.

```java
public class Order {
    private final List<OrderLineItem> lineItems; // composition

    public Order(String orderId) {
        this.lineItems = new ArrayList<>(); // Order creates the list itself
    }

    public void addItem(String productName, int qty, double price) {
        // OrderLineItem created INSIDE Order — nobody else creates it
        this.lineItems.add(new OrderLineItem(productName, qty, price));
    }
}

// Package-private constructor — signals it has no life outside Order
class OrderLineItem {
    OrderLineItem(String productName, int quantity, double price) { ... }
}
```

**Signal in code:** The contained object is created with `new X()` **inside** the container. Its constructor is often package-private or private to prevent external creation.

---

### Aggregation vs Composition — The One Test

> **"If I delete the parent, does the child logically stop existing?"**
>
> - **No** → Aggregation (Professor without Department still makes sense)
> - **Yes** → Composition (Room without House makes no sense)

| | Aggregation | Composition |
|---|---|---|
| Child lifecycle | Independent of parent | Dies with parent |
| Object creation | Passed in from outside | Created inside parent |
| Sharing | Child can belong to multiple parents | Child belongs to exactly one parent |
| Example | Department → Professor | House → Room, Order → OrderLineItem |

---

### The Four Relationships at a Glance

| Relationship | Connection level | Code signal | Example |
|---|---|---|---|
| **Dependency** | Loosest | Method parameter only | `Invoice.export(PdfExporter e)` |
| **Association** | Loose | Field reference, both independent | `Doctor` has `List<Patient>` |
| **Aggregation** | Medium | Field reference, child passed in | `Team` has `List<Player>` |
| **Composition** | Tightest | Child created inside parent | `Order` creates `OrderLineItem` |

---

## Step 3 — Inheritance vs Composition in Practice

### The Big Picture

Both are ways to reuse behavior. The question is which tool fits which job.

- **Inheritance** says: *"I am a kind of that thing."* You're extending an identity.
- **Composition** says: *"I have that capability."* You're assembling abilities.

The most expensive mistake: using inheritance as a code-reuse tool whenever you want to share behavior. Inheritance is the **tightest coupling in OOP** — you are permanently bound to every decision your parent class ever made.

---

### The IS-A Test — Applied Honestly

The inheritance question is really a question about **identity**, not behavior.

> **"Is this genuinely a more specific kind of that thing, in a way that will always be true?"**

| Claimed relationship | IS-A honest? | Right tool |
|---|---|---|
| `EmailNotification` IS-A `Notification` | ✅ Yes | Inheritance |
| `SavingsAccount` IS-A `BankAccount` | ✅ Yes | Inheritance |
| `OrderService` IS-A `Logger` | ❌ No | Composition |
| `Stack` IS-A `ArrayList` | ❌ No | Composition |
| `PremiumCustomer` IS-A `Customer` | ⚠️ Maybe | Depends — can a customer *become* premium later? |

**The `Stack` problem** is a famous real mistake in Java's standard library. Because `Stack extends Vector`, users can call `stack.get(2)` — randomly accessing the middle of a stack. This violates the entire concept of a stack. The inheritance brought in 20+ methods that have no business being on a Stack.

---

### When Inheritance Is Right

Use inheritance when the IS-A relationship is genuine, stable, and you need polymorphism.

```java
// EmailNotification IS genuinely a kind of Notification
public abstract class Notification {
    protected final String recipient;
    protected final String message;

    public abstract void send(); // subclasses must implement this

    public String getSummary() { // shared behavior — no duplication needed
        return "To: " + recipient + " | Message: " + message;
    }
}

public class EmailNotification extends Notification {
    private final String subject;

    @Override
    public void send() {
        System.out.println("Email → " + recipient + " | " + subject);
    }
}
```

The type hierarchy is real — you can pass `EmailNotification` anywhere a `Notification` is expected. Polymorphism works naturally.

---

### When Composition Is Better

Use composition when you want to reuse behavior but cannot honestly say IS-A.

```java
// WRONG — OrderService IS-A Logger makes no sense
public class OrderService extends Logger { ... }

// RIGHT — OrderService HAS logging capability, HAS persistence, HAS event publishing
public class OrderService {
    private final OrderRepository repository; // composed
    private final Logger logger;              // composed
    private final EventPublisher publisher;   // composed

    public OrderService(OrderRepository repo, Logger logger, EventPublisher publisher) {
        this.repository = repo;
        this.logger = logger;
        this.publisher = publisher;
    }

    public void placeOrder(Order order) {
        logger.info("Placing order: " + order.getId());
        repository.save(order);
        publisher.publish(new OrderPlacedEvent(order));
    }
}
```

**The composition advantage:** You can swap the logger, swap the publisher, swap the repository — all without touching `OrderService`. Each concern is independently testable and swappable.

```java
// In production
OrderService prod = new OrderService(
    new DatabaseRepository(), new ConsoleLogger(), new KafkaPublisher()
);

// In tests — swap every dependency, touch nothing in OrderService
OrderService test = new OrderService(
    new InMemoryRepository(), new NoOpLogger(), new FakePublisher()
);
```

---

### The Runtime Type Change Problem

This is the most common trap in interviews. If the problem says **"a customer can be upgraded to premium"** — that's a type change at runtime.

**Inheritance cannot model this:**

```java
Customer akash = new Customer("Akash");
// 6 months later, Akash upgrades...
// You CANNOT do: akash = new PremiumCustomer("Akash")
// You'd lose all of akash's order history, session, cart — everything
```

**Composition handles it cleanly:**

```java
public interface MembershipTier {
    double getDiscountRate();
    String getTierName();
}

public class StandardTier implements MembershipTier {
    public double getDiscountRate() { return 0.0; }
    public String getTierName() { return "Standard"; }
}

public class PremiumTier implements MembershipTier {
    public double getDiscountRate() { return 0.15; }
    public String getTierName() { return "Premium"; }
}

public class Customer {
    private MembershipTier tier = new StandardTier(); // starts standard

    public void upgradeToPremium() {
        this.tier = new PremiumTier(); // one line — no object recreation
    }

    public double applyDiscount(double price) {
        return price * (1 - tier.getDiscountRate());
    }
}
```

This is the **Strategy Pattern** — composition is what makes it work.

---

### The Class Explosion Problem

When you try to model capabilities as types using inheritance, you get an explosion of classes:

```java
// Trying to model Email + SMS + Push, each with Retry, Logging, RateLimit...
class UrgentEmailNotification extends EmailNotification { }
class LoggedEmailNotification extends EmailNotification { }
class RateLimitedEmailNotification extends EmailNotification { }
class UrgentLoggedEmailNotification extends EmailNotification { } // explosion!
```

Every new combination needs a new class. Composition solves this with **wrappers** (the Decorator Pattern):

```java
// One wrapper adds retry to ANY notification
public class RetryableNotification extends Notification {
    private final Notification wrapped;
    private final int maxAttempts;

    @Override
    public void send() {
        for (int i = 1; i <= maxAttempts; i++) {
            try { wrapped.send(); return; }
            catch (Exception e) { /* retry */ }
        }
    }
}
```

Now any combination works without new classes:

```java
// Email + retry + logging — compose freely
Notification n = new LoggedNotification(
    new RetryableNotification(
        new EmailNotification("akash@email.com", "Hello", "Subject"),
        3
    ),
    logger
);
```

---

### The Decision Framework

1. Is this genuinely an IS-A relationship? → **No** → Use Composition
2. Can the type/behavior change at runtime? → **Yes** → Use Composition
3. Do you need to pass it as the parent type (polymorphism)? → **Yes** → Inheritance is valid
4. When in doubt → **Default to Composition**

---

## Step 4 — Designing Clean APIs

### The Big Picture

Once you have classes and relationships, you face one more question: **what should the outside world be allowed to see and call?**

A clean API means callers don't need to know *how* something works internally. They just know *what* to ask it to do, and they get back something meaningful. Three rules consistently produce clean APIs.

---

### Rule 1 — Tell, Don't Ask

Instead of querying an object's state to make an external decision, ask the object to make the decision itself.

**The problem with "asking":**

```java
// BAD — caller reaches inside Order, makes a decision, pushes state back in
if (order.getStatus().equals("PENDING")) {
    order.setStatus("CONFIRMED");
}
```

Problems: This `if` check gets copy-pasted everywhere. If confirmation rules change, you must find every copy. `Order` becomes a dumb data bag with no self-protection.

**The right way — "telling":**

```java
public class Order {
    private OrderStatus status;

    // Order manages its OWN state transitions
    public void confirm() {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm order in status: " + status);
        }
        this.status = OrderStatus.CONFIRMED;
    }

    public void cancel() {
        if (status == OrderStatus.DELIVERED) {
            throw new IllegalStateException("Cannot cancel a delivered order");
        }
        this.status = OrderStatus.CANCELLED;
    }
}
```

Now the caller just says `order.confirm()`. The state transition logic lives in exactly one place. Changing the rules means changing `Order` — nothing else.

**Bank account example:**

```java
// WRONG — caller manipulates balance directly
if (from.getBalance() >= amount) {
    from.setBalance(from.getBalance() - amount);
    to.setBalance(to.getBalance() + amount);
}

// RIGHT — accounts enforce their own rules
public class BankAccount {
    public void debit(double amount) {
        if (balance - amount < minimumBalance)
            throw new InsufficientFundsException("...");
        this.balance -= amount;
    }

    public void credit(double amount) {
        this.balance += amount;
    }
}

// TransferService is now 2 lines — all rules live in the accounts
public void transfer(BankAccount from, BankAccount to, double amount) {
    from.debit(amount);
    to.credit(amount);
}
```

---

### Rule 2 — Return Rich Domain Objects, Not Primitives

When a method result means something, give it a type.

**The problem with primitives:**

```java
// BAD — what does false mean? Network error? Wrong card? Insufficient funds?
public boolean processPayment(Order order) { ... }

// BAD — null is invisible in the signature, causes NPE at runtime
public Order findOrder(String id) { ... }
```

**The right way:**

```java
public class PaymentResult {
    public enum Status { SUCCESS, INSUFFICIENT_FUNDS, CARD_DECLINED, NETWORK_ERROR }

    private final Status status;
    private final String transactionId;   // populated on success
    private final String failureReason;   // populated on failure
    private final boolean retryable;

    public static PaymentResult success(String transactionId) {
        return new PaymentResult(Status.SUCCESS, transactionId, null, false);
    }

    public static PaymentResult failed(Status status, String reason, boolean retryable) {
        return new PaymentResult(status, null, reason, retryable);
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }
}
```

Now the caller has everything they need:

```java
PaymentResult result = paymentService.processPayment(order);

if (result.isSuccess()) {
    System.out.println("Done. Transaction: " + result.getTransactionId());
} else if (result.isRetryable()) {
    scheduleRetry(order);
} else {
    notifyUser("Payment failed: " + result.getFailureReason());
}
```

**For lookups, use `Optional`:**

```java
// Forces the caller to consciously handle the "not found" case
public Optional<Order> findOrderById(String orderId) { ... }

// Usage
Order order = repository.findOrderById("O123")
    .orElseThrow(() -> new OrderNotFoundException("Order O123 not found"));
```

---

### Rule 3 — Fail Fast with Meaningful Exceptions

Validate inputs at the method boundary, immediately, before doing any real work.

**The problem with late validation:**

```java
// No validation — bad data travels deep, NPE appears 10 layers later
public void borrowBook(Member member, BookCopy copy) {
    Borrowing borrowing = new Borrowing(member, copy); // NPE if member is null
    borrowingRepo.save(borrowing);                      // or here, mysteriously
}
```

**The right way — validate at the boundary:**

```java
public Borrowing borrowBook(Member member, BookCopy copy) {

    // Layer 1: null checks — catch programming mistakes immediately
    Objects.requireNonNull(member, "Member cannot be null");
    Objects.requireNonNull(copy, "BookCopy cannot be null");

    // Layer 2: business rule checks — catch invalid domain state
    if (!copy.isAvailable()) {
        throw new BookNotAvailableException(
            "Copy " + copy.getId() + " is already borrowed"
        );
    }
    if (member.hasReachedBorrowLimit()) {
        throw new BorrowLimitExceededException(
            "Member " + member.getId() + " has reached the borrow limit"
        );
    }

    // Only after ALL validations pass — do the real work
    return new Borrowing(member, copy, LocalDate.now(), LocalDate.now().plusDays(14));
}
```

**Custom exceptions carry domain meaning:**

```java
public class BookNotAvailableException extends RuntimeException {
    public BookNotAvailableException(String message) { super(message); }
}

public class BorrowLimitExceededException extends RuntimeException {
    public BorrowLimitExceededException(String message) { super(message); }
}
```

Callers can now respond specifically to each failure:

```java
try {
    borrowingService.borrowBook(member, copy);
} catch (BookNotAvailableException e) {
    System.out.println("This copy isn't available: " + e.getMessage());
} catch (BorrowLimitExceededException e) {
    System.out.println("You've hit your borrow limit. Return a book first.");
}
```

---

## Full Example — Library Management System

This is where all four steps come together in one cohesive design.

---

### Layer 1: Value Object — `ISBN`

```java
public final class ISBN {
    private final String value;

    public ISBN(String value) {
        if (!value.matches("\\d{13}"))
            throw new IllegalArgumentException("Invalid ISBN: " + value);
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ISBN)) return false;
        return value.equals(((ISBN) o).value);
    }

    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return value; }
}
```

**Why a value object?**
- `final` class — cannot be subclassed
- All fields are `final` — immutable after construction
- Equality is by value, not identity — two `ISBN("9780134685991")` are equal
- Validation in the constructor = Fail Fast at the type level. Invalid ISBNs are rejected before they touch any business logic.

---

### Layer 2: Core Entities

#### `Book` and `BookCopy` — Composition

```java
public class Book {
    private final ISBN isbn;
    private final String title;
    private final String author;
    private final List<BookCopy> copies; // composition — copies belong to this book

    public Book(ISBN isbn, String title, String author) {
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.copies = new ArrayList<>();
    }

    public BookCopy addCopy(String copyId) {
        BookCopy copy = new BookCopy(copyId, this); // Book creates BookCopy
        copies.add(copy);
        return copy;
    }

    public List<BookCopy> getAvailableCopies() {
        return copies.stream()
                .filter(BookCopy::isAvailable)
                .collect(Collectors.toList());
    }
}
```

```java
public class BookCopy {
    private final String copyId;
    private final Book book;     // association back to its Book
    private boolean available;

    BookCopy(String copyId, Book book) { // package-private: created only by Book
        this.copyId = copyId;
        this.book = book;
        this.available = true;
    }

    // Tell Don't Ask — BookCopy manages its own availability state
    public void markBorrowed() {
        if (!available) throw new IllegalStateException("Copy already borrowed");
        this.available = false;
    }

    public void markReturned() {
        if (available) throw new IllegalStateException("Copy is not currently borrowed");
        this.available = true;
    }
}
```

**Design decisions explained:**
- `BookCopy` is created *inside* `Book.addCopy()` → **Composition**
- Constructor is package-private — language enforces that only `Book` can create copies
- `BookCopy` holds a back-reference to `Book` — this is **Association** (not composition; `BookCopy` doesn't own `Book`)
- `markBorrowed()` and `markReturned()` validate their own preconditions → **Tell Don't Ask**

---

#### `Member` — Composition with `Borrowing`

```java
public class Member {
    private static final int BORROW_LIMIT = 5;

    private final String memberId;
    private final String name;
    private final List<Borrowing> activeBorrowings; // composition

    public Member(String memberId, String name) {
        this.memberId = memberId;
        this.name = name;
        this.activeBorrowings = new ArrayList<>();
    }

    // Tell Don't Ask — member knows its own limit
    public boolean hasReachedBorrowLimit() {
        return activeBorrowings.size() >= BORROW_LIMIT;
    }

    public void addBorrowing(Borrowing borrowing) {
        activeBorrowings.add(borrowing);
    }

    // Defensive copy — callers can read, but cannot add to the list directly
    public List<Borrowing> getActiveBorrowings() {
        return Collections.unmodifiableList(activeBorrowings);
    }
}
```

**Why `unmodifiableList`?** It exposes borrowings for reading while preventing callers from doing `member.getActiveBorrowings().add(...)` — which would bypass `addBorrowing()` and all its future validation logic.

---

### Layer 3: Domain Record — `Borrowing`

```java
public class Borrowing {
    private static final double FINE_PER_DAY = 2.0;

    private final Member member;     // association
    private final BookCopy copy;     // association
    private final LocalDate borrowDate;
    private final LocalDate dueDate;
    private LocalDate returnDate;    // null until returned

    public double calculateFine() {
        // Works for both active borrowings (returnDate is null) and returned ones
        LocalDate effectiveDate = (returnDate != null) ? returnDate : LocalDate.now();
        long overdueDays = ChronoUnit.DAYS.between(dueDate, effectiveDate);
        return overdueDays > 0 ? overdueDays * FINE_PER_DAY : 0.0;
    }

    public void markReturned(LocalDate returnDate) {
        if (this.returnDate != null) throw new IllegalStateException("Already returned");
        this.returnDate = returnDate;
    }

    public boolean isOverdue() {
        return returnDate == null && LocalDate.now().isAfter(dueDate);
    }
}
```

**Why does `calculateFine()` live on `Borrowing`?**

Fine calculation needs `dueDate` and `returnDate`. Both live on `Borrowing`. `Borrowing` is the **Information Expert** — it has everything the calculation needs. If this logic lived on `Member`, `Member` would have to reach into `Borrowing` to get those dates — violating Tell Don't Ask.

---

### Layer 4: Service Layer — `BorrowingService`

```java
public class BorrowingService {
    private final Library library; // association — injected, not created here

    public BorrowingService(Library library) {
        this.library = library;
    }

    public Borrowing borrowBook(String memberId, ISBN isbn) {

        // Fail fast — validate existence at the boundary
        Member member = library.findMember(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found: " + memberId));

        // Business rule check — ask member to evaluate its own state (Tell Don't Ask)
        if (member.hasReachedBorrowLimit()) {
            throw new BorrowLimitExceededException("Member has reached borrow limit");
        }

        Book book = library.findBook(isbn)
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + isbn));

        List<BookCopy> availableCopies = book.getAvailableCopies();
        if (availableCopies.isEmpty()) {
            throw new BookNotAvailableException("No copies available for: " + book.getTitle());
        }

        // Orchestrate — tell each object to do its own part
        BookCopy copy = availableCopies.get(0);
        Borrowing borrowing = new Borrowing(member, copy, LocalDate.now(), LocalDate.now().plusDays(14));

        copy.markBorrowed();          // copy manages its own state
        member.addBorrowing(borrowing); // member records this borrowing

        return borrowing;
    }
}
```

**`BorrowingService` has one job: orchestration.** It coordinates the steps but delegates every domain decision to the domain objects. It never manipulates internal state directly — it *tells* objects what to do.

---

### Layer 5: Aggregate Root — `Library`

```java
public class Library {
    private final Map<ISBN, Book> books;       // composition
    private final Map<String, Member> members; // composition

    public Library() {
        this.books = new HashMap<>();
        this.members = new HashMap<>();
    }

    public Book addBook(ISBN isbn, String title, String author) {
        Book book = new Book(isbn, title, author); // Library creates Book
        books.put(isbn, book);
        return book;
    }

    public Member registerMember(String memberId, String name) {
        Member member = new Member(memberId, name);
        members.put(memberId, member);
        return member;
    }

    // Returns Optional — makes "not found" explicit in the contract
    public Optional<Book> findBook(ISBN isbn) {
        return Optional.ofNullable(books.get(isbn));
    }

    public Optional<Member> findMember(String memberId) {
        return Optional.ofNullable(members.get(memberId));
    }
}
```

`Library` is the **aggregate root** — the single entry point for the system. All access to books and members flows through it. Nothing bypasses it.

---

### The Complete Relationship Map

```
Library (aggregate root)
  │
  ├──[composition]──► Book (many)
  │                     │
  │                     └──[composition]──► BookCopy (many)
  │                                           │
  │                                           └──[association back]──► Book
  │
  ├──[composition]──► Member (many)
  │                     │
  │                     └──[composition]──► Borrowing (active ones)
  │                                           │
  │                                           ├──[association]──► Member
  │                                           └──[association]──► BookCopy
  │
  └── BorrowingService [association — uses Library, does not own it]
        │
        ├── Validates Member and Book exist
        ├── Checks Member's borrow limit
        ├── Tells BookCopy to mark itself borrowed
        └── Tells Member to record the Borrowing
```

---

### Every Design Decision Traced to a Principle

| Decision | Principle |
|---|---|
| `BookCopy` has a package-private constructor | **Composition** — if `Book` owns `BookCopy`, only `Book` should create it |
| `calculateFine()` lives on `Borrowing` | **Information Expert** — fine needs `dueDate` and `returnDate`, both on `Borrowing` |
| `member.hasReachedBorrowLimit()` instead of checking `.size() >= 5` externally | **Tell Don't Ask** — member knows its own limit |
| `findBook()` returns `Optional<Book>` | **Rich Return Types** — absence is meaningful information |
| `getActiveBorrowings()` returns `unmodifiableList` | **Encapsulation** — expose for reading, block external manipulation |
| `BorrowingService` receives `Library` via constructor | **Composition** — service doesn't own the library, works with whatever it receives |
| `ISBN` validates in constructor | **Fail Fast** — invalid ISBNs rejected at the type boundary before touching business logic |

---

### The Layered Mental Model

Clean object modeling naturally produces layers with clear responsibilities:

| Layer | Type | Description |
|---|---|---|
| **Value Objects** | `ISBN`, `Money` | Immutable, validated, no identity. Describe attributes. |
| **Entities** | `Book`, `Member`, `BookCopy` | Have identity, manage own state, enforce own invariants. They *are* something. |
| **Domain Records** | `Borrowing` | Capture a business event, own the logic for that event's consequences. They *happened*. |
| **Service Layer** | `BorrowingService` | Orchestrates and coordinates. Delegates domain work to domain objects. |
| **Aggregate Root** | `Library` | Single entry point. All access flows through it. Controls creation and lookup. |

---

## Quick Reference — Decision Rules

### Which relationship to use?

| Situation | Relationship |
|---|---|
| Class uses another only inside a method | Dependency |
| Class holds a reference, both live independently | Association |
| Class manages a collection, children passed in from outside | Aggregation |
| Class creates objects internally, children die with parent | Composition |

### Inheritance or Composition?

| Situation | Use |
|---|---|
| Genuine IS-A, stable type, need polymorphism | Inheritance |
| Just sharing behavior, no true IS-A | Composition |
| Type/behavior might change at runtime | Composition |
| Need multiple independent capabilities | Composition |
| When in doubt | Composition |

### Where does logic live?

> Logic lives on the class that owns the data the logic needs.
> (**Information Expert Principle**)

### API design checklist

- [ ] Does the caller reach into an object's state and make decisions? → Move logic into the object (**Tell Don't Ask**)
- [ ] Does a method return `boolean`, `null`, or a plain `String` where more information is needed? → Return a domain result object
- [ ] Are there null checks or state checks scattered through the codebase? → Move validation into the method boundary (**Fail Fast**)
- [ ] Can someone bypass your class's rules by calling `getList().add(...)`? → Return `unmodifiableList`

---

*These notes cover Phase 2 of the LLD Learning Roadmap. Next: Phase 3 — Design Patterns (Creational, Structural, Behavioral).*

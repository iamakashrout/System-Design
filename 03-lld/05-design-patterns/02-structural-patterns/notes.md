# Structural Design Patterns

> **Phase 3 — Design Patterns | Part 2: Structural**
> These notes accompany runnable Java implementation files. All four patterns are covered with intuition, structure, code walkthrough, and trade-offs.

---

## Table of Contents

1. [Why Structural Patterns?](#why-structural-patterns)
2. [Pattern 1 — Adapter](#pattern-1--adapter)
3. [Pattern 2 — Decorator](#pattern-2--decorator)
4. [Pattern 3 — Facade](#pattern-3--facade)
5. [Pattern 4 — Composite](#pattern-4--composite)
6. [Full Summary & Quick Reference](#full-summary--quick-reference)

---

## Why Structural Patterns?

Creational patterns answered: *"How do we create objects?"*

Structural patterns answer a different question: **"How do we compose objects and classes into larger, useful structures — especially when the pieces weren't designed to fit together?"**

The core insight is that **the relationships between objects matter as much as the objects themselves**. A well-structured system lets you add, wrap, or connect components without rewriting what already works.

Each structural pattern solves a specific composition problem:

| Pattern | Core problem | One-line solution |
|---|---|---|
| **Adapter** | Incompatible interfaces need to work together | Wrap one interface to look like another |
| **Decorator** | Add behavior to objects without changing their class | Wrap objects in layers that add functionality |
| **Facade** | A subsystem is too complex for clients to use directly | Provide a single simplified entry point |
| **Composite** | Clients need to treat single objects and groups uniformly | Give both the same interface |

---

## Pattern 1 — Adapter

### The Problem

You have existing code that works. You have a new component you want to use. Their interfaces don't match. You can't change either side — the existing code is in production, the new component is a third-party library. You need a **translator** between them.

### Real-World Analogy

You're traveling in Europe with an Indian power plug. The wall socket is a different shape. You don't rewire your laptop. You don't rewire the wall. You use a **plug adapter** — it sits between the two, translating one interface to the other. Neither side changes.

### The Structure

```
  Client                Target Interface            Adaptee
  ──────          ──────────────────────────    ────────────────────
  CheckoutService → PaymentProcessor            RazorpayGateway
                    + charge(...)               + initiatePayment(...)
                    + refund(...)               + cancelPayment(...)
                           ▲
                           │ implements
                    ───────────────────
                    RazorpayAdapter
                    + charge(...)       ← translates to → initiatePayment(...)
                    + refund(...)       ← translates to → cancelPayment(...)
```

Three ingredients:
1. **Target interface** — what your existing code expects (`PaymentProcessor`)
2. **Adaptee** — the new class with the incompatible interface (`RazorpayGateway`)
3. **Adapter** — wraps the Adaptee, implements the Target, translates all calls

### The Scenario

Your system uses a `PaymentProcessor` interface. You've been using Stripe. Now you want to add Razorpay — but Razorpay's SDK has a completely different API. You can't modify your system's interface, and you can't modify Razorpay's SDK.

### The Incompatibility

```java
// What YOUR system expects
interface PaymentProcessor {
    PaymentResult charge(String customerId, double amount, String currency);
    boolean refund(String transactionId, double amount);
}

// What Razorpay's SDK provides (completely different!)
class RazorpayGateway {
    RazorpayResponse initiatePayment(RazorpayRequest request); // different method name
    boolean cancelPayment(String razorpayPaymentId);           // different method name

    // Also different data: Razorpay works in PAISE, not rupees
    // RazorpayRequest needs an orderId, not a customerId
}
```

The two interfaces are incompatible in method names, parameter types, and data formats. The Adapter bridges all of this.

### The Adapter

```java
// The Adapter: implements Target, wraps Adaptee
public class RazorpayAdapter implements PaymentProcessor {
    private final RazorpayGateway razorpay; // holds the Adaptee

    public RazorpayAdapter(RazorpayGateway razorpay) {
        this.razorpay = razorpay;
    }

    @Override
    public PaymentResult charge(String customerId, double amount, String currency) {
        // Translation 1: customerId → orderId
        String orderId = "order-" + customerId + "-" + System.currentTimeMillis();

        // Translation 2: rupees → paise (Razorpay's unit)
        long amountInPaise = (long)(amount * 100);

        // Translation 3: our request type → Razorpay's request type
        RazorpayRequest request = new RazorpayRequest(orderId, amountInPaise);
        RazorpayResponse response = razorpay.initiatePayment(request);

        // Translation 4: Razorpay's response → our response type
        boolean success = "SUCCESS".equals(response.getStatus());
        return new PaymentResult(success, response.getPaymentId(), response.getDescription());
    }

    @Override
    public boolean refund(String transactionId, double amount) {
        // Translation: our transactionId = Razorpay's paymentId
        return razorpay.cancelPayment(transactionId);
    }
}
```

### The Client — Completely Unaware of Razorpay

```java
// Client only knows PaymentProcessor — never RazorpayGateway
public class CheckoutService {
    private final PaymentProcessor paymentProcessor;

    public CheckoutService(PaymentProcessor paymentProcessor) {
        this.paymentProcessor = paymentProcessor;
    }

    public void processOrder(String customerId, double amount) {
        PaymentResult result = paymentProcessor.charge(customerId, amount, "INR");
        // works identically whether it's Stripe or Razorpay underneath
    }
}

// Wiring it up
CheckoutService stripeCheckout    = new CheckoutService(new StripePaymentProcessor());
CheckoutService razorpayCheckout  = new CheckoutService(new RazorpayAdapter(new RazorpayGateway()));
// CheckoutService doesn't know (or care) which one it got
```

### Demo Output

```
[CheckoutService] Processing order for customer: user-101
[Stripe] Charging customer user-101: INR1499.0
[CheckoutService] Result: PaymentResult{success=true, txId='stripe-txn-...'}

[CheckoutService] Processing order for customer: user-202
[Razorpay SDK] Processing payment for order: order-user-202-..., amount: 299900 paise
[CheckoutService] Result: PaymentResult{success=true, txId='razorpay-pay-...'}
```

Same client code. Two completely different payment SDKs. Zero changes to `CheckoutService`.

### What the Adapter Does vs What It Doesn't Do

The Adapter only translates. It doesn't add logic, validate business rules, or cache results. If your translation logic becomes very complex, that's a signal that perhaps a more substantial integration layer (like a service) is needed — not a pattern change.

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Integrating third-party libraries with your own interface | You control both sides — just align the interface directly |
| Migrating legacy code incrementally | It's a lazy fix for a design you could actually clean up |
| Supporting multiple providers behind one interface | Translation logic is so complex it deserves its own service |

---

## Pattern 2 — Decorator

### The Problem

You want to add behavior to **individual objects** — not all objects of a class, just specific ones, in specific combinations.

Subclassing adds behavior to an entire class. Worse, it causes **class explosion** when combinations grow:

```
LoggedHandler
AuthenticatedHandler
RateLimitedHandler
LoggedAuthenticatedHandler
LoggedRateLimitedHandler
AuthenticatedRateLimitedHandler
LoggedAuthenticatedRateLimitedHandler   ← this is getting out of hand
```

You also need the combinations to be known at compile time. Decorator solves this by composing behavior **dynamically at runtime**.

### Real-World Analogy

A coffee order at a café. You start with a base espresso. Then you add milk — one layer. Add vanilla syrup — another layer. Add whipped cream — another. Each addition **wraps** the previous, adding to its cost and description. Nobody creates a separate `EspressoWithMilkAndVanillaAndWhip` class. You **compose** it by layering.

### The Critical Insight

A Decorator **IS** a component AND **HAS** a component. This is what enables stacking.

```
interface HttpRequestHandler {
    HttpResponse handle(HttpRequest request);
}

// Both of these are HttpRequestHandlers:
OrderRequestHandler          // concrete component (base)
LoggingDecorator             // IS-A handler, HAS-A handler inside it

// So you can wrap a decorator in another decorator:
new LoggingDecorator( new AuthDecorator( new OrderRequestHandler() ) )
```

### The Structure

```
HttpRequestHandler (interface)
         ▲
         │
    ┌────┴────────────────────┐
    │                         │
OrderRequestHandler    RequestHandlerDecorator (abstract)
(concrete component)   - wrapped: HttpRequestHandler
                       - handle() → delegates to wrapped
                              ▲
                    ┌─────────┼──────────┬─────────────┐
                    │         │          │              │
              Logging    Authentication  RateLimiting  Compression
              Decorator  Decorator      Decorator      Decorator
```

Four ingredients:
1. **Component interface** — the common contract (`HttpRequestHandler`)
2. **Concrete component** — the base object with core logic (`OrderRequestHandler`)
3. **Abstract decorator** — implements the interface, holds a reference to another component
4. **Concrete decorators** — each adds one specific behavior by wrapping the component

### The Abstract Decorator — The Structural Key

```java
// Abstract decorator: IS a handler (implements) AND HAS a handler (wraps)
public abstract class RequestHandlerDecorator implements HttpRequestHandler {
    protected final HttpRequestHandler wrapped; // the thing being decorated

    protected RequestHandlerDecorator(HttpRequestHandler wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        return wrapped.handle(request); // default: just delegate
    }
    // Concrete decorators override this to add behavior before/after delegation
}
```

### A Concrete Decorator — One Concern, One Class

```java
public class AuthenticationDecorator extends RequestHandlerDecorator {
    private final String validToken = "Bearer valid-token-123";

    public AuthenticationDecorator(HttpRequestHandler wrapped) {
        super(wrapped);
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        // ADD BEHAVIOR BEFORE: check auth
        String authHeader = request.getHeader("Authorization");
        if (!validToken.equals(authHeader)) {
            System.out.println("[Auth] Rejected — invalid or missing token");
            return new HttpResponse(401, "{\"error\": \"Unauthorized\"}");
        }
        System.out.println("[Auth] Token validated");

        // DELEGATE: if auth passes, continue down the chain
        return wrapped.handle(request);
    }
}
```

Each decorator is responsible for exactly **one concern**. Auth decorator does auth. Logging decorator does logging. They don't know about each other.

### Composing a Pipeline

```java
// Stack decorators like layers — outermost runs first
HttpRequestHandler pipeline =
    new LoggingDecorator(               // layer 4 (outermost — runs first)
      new RateLimitingDecorator(        // layer 3
        new AuthenticationDecorator(    // layer 2
          new CompressionDecorator(     // layer 1
            new OrderRequestHandler()   // core — runs last
          )
        ), 5                            // maxRequestsPerMinute = 5
      )
    );
```

When a request comes in, it flows through each layer in order:

```
Request IN  →  Logging  →  RateLimit  →  Auth  →  Compression  →  OrderHandler
Response OUT ← Logging  ←  RateLimit  ←  Auth  ←  Compression  ←  OrderHandler
```

### Demo Output

```
=== Request 1: Valid ===
[Logger] --> POST /api/orders
[RateLimit] Client client-A — request 1/5
[Auth] Token validated
[OrderHandler] Processing POST /api/orders
[Compression] Response compressed
[Logger] <-- Status: 200 (2ms)

=== Request 2: Missing auth token ===
[Logger] --> POST /api/orders
[RateLimit] Client client-B — request 1/5
[Auth] Rejected — invalid or missing token
[Logger] <-- Status: 401 (0ms)
```

Notice: when Auth rejects the request, it short-circuits and returns early. The `OrderHandler` and `CompressionDecorator` are never reached. Each decorator independently decides whether to delegate or abort.

### Real-World Uses of Decorator

| Use case | Example |
|---|---|
| Java I/O streams | `new BufferedReader(new InputStreamReader(new FileInputStream(...)))` |
| HTTP middleware | Logging, auth, rate limiting, CORS headers |
| UI rendering | Adding borders, scroll bars, shadows to base components |
| Caching layer | Wrap a service with a caching decorator |

Java's own I/O library is built entirely on this pattern — `BufferedInputStream`, `DataInputStream`, `GZIPOutputStream` are all decorators.

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Behaviors need to be combined in varying permutations | Fixed, small set of combinations — subclassing is simpler |
| You want to add/remove behavior at runtime | Decorator order is confusing and hard to debug |
| Extending via subclassing would cause class explosion | Too many layers makes stack traces unreadable |

---

## Pattern 3 — Facade

### The Problem

A subsystem is powerful but complex. It has many classes, many dependencies, and requires a specific **sequence of calls** to accomplish a common task. Clients who just want to "do the thing" are burdened with knowing all the internals. Every client ends up repeating the same complex coordination logic.

### Real-World Analogy

Starting a car used to mean: adjust the choke, check fuel, manually crank the engine, adjust timing. Today you push a button. That button is a **facade** — it hides the entire ignition subsystem behind one simple interface. You don't need to know how fuel injection, the starter motor, and spark timing coordinate. You just push.

### The Structure

```
  Client                          Facade                        Subsystem
  ──────                    ──────────────────────         ─────────────────────
                            VideoProcessingFacade           VideoValidator
  facade.processVideo() →   - validator                     MetadataExtractor
                            - metadataExtractor      ───→   VideoTranscoder
                            - transcoder                    ThumbnailGenerator
                            - thumbnailGenerator            VideoStorageService
                            - storageService                NotificationService
                            - notificationService
```

Three ingredients:
1. **Subsystem classes** — complex, do the real work, unaware of the Facade
2. **Facade** — knows the subsystem, provides a simplified API, orchestrates the calls
3. **Client** — only talks to the Facade, never to subsystem classes directly

### Without a Facade — Client Burden

Without `VideoProcessingFacade`, every client that wants to process a video must:

```java
// Every single caller must know this entire sequence
VideoValidator validator = new VideoValidator();
if (!validator.validate(filePath)) throw new IllegalArgumentException(...);

MetadataExtractor extractor = new MetadataExtractor();
Map<String, String> metadata = extractor.extract(filePath);

VideoTranscoder transcoder = new VideoTranscoder();
String transcoded = transcoder.transcode(filePath, format, metadata);

ThumbnailGenerator thumbGen = new ThumbnailGenerator();
String thumb = thumbGen.generate(transcoded);

VideoStorageService storage = new VideoStorageService();
String url = storage.store(transcoded, thumb);

NotificationService notifier = new NotificationService();
notifier.notifyReady(url, metadata);
```

This is 6 subsystem classes, a specific order, and shared data between steps. If the order changes, or a new step is added, every caller breaks.

### With a Facade — Clean and Simple

```java
// The Facade orchestrates all of it
public class VideoProcessingFacade {
    // Owns the subsystem — clients don't instantiate any of these
    private final VideoValidator validator;
    private final MetadataExtractor metadataExtractor;
    private final VideoTranscoder transcoder;
    private final ThumbnailGenerator thumbnailGenerator;
    private final VideoStorageService storageService;
    private final NotificationService notificationService;

    public VideoProcessingFacade() { /* initialize all subsystem components */ }

    // One method hides 6 subsystem calls and their coordination
    public String processVideo(String filePath, String targetFormat) {
        if (!validator.validate(filePath))
            throw new IllegalArgumentException("Invalid file: " + filePath);

        Map<String, String> metadata  = metadataExtractor.extract(filePath);
        String transcodedPath         = transcoder.transcode(filePath, targetFormat, metadata);
        String thumbnailPath          = thumbnailGenerator.generate(transcodedPath);
        String videoUrl               = storageService.store(transcodedPath, thumbnailPath);
        notificationService.notifyReady(videoUrl, metadata);

        return videoUrl;
    }
}

// Client call — one line, zero subsystem knowledge
String url = facade.processVideo("lecture.mp4", "webm");
```

If the pipeline changes (e.g., add a virus scan step), you update **one place** — the Facade. All clients automatically get the new behavior.

### Demo Output

```
[Facade] Starting video processing: lecture_recording.mp4
[Validator] Validating file: lecture_recording.mp4
[Validator] Valid: true
[Metadata] Extracting metadata from: lecture_recording.mp4
[Transcoder] Transcoding lecture_recording.mp4 to webm | resolution: 1920x1080
[Thumbnail] Generating thumbnail for: ...
[Storage] Storing video: ...
[Notification] Video ready at: https://cdn.example.com/videos/... | duration: 00:03:42
[Facade] Processing complete: https://cdn.example.com/videos/...
```

### Facade vs Adapter — The Common Confusion

These two are frequently mixed up. The intent is completely different:

| | Adapter | Facade |
|---|---|---|
| **Intent** | Make incompatible interfaces compatible | Simplify a complex subsystem |
| **What it wraps** | One class | An entire subsystem (many classes) |
| **Interface change** | Translates one interface to another | Creates a new, simpler interface |
| **Problem it solves** | "These two things can't talk to each other" | "This subsystem is too hard to use" |

A simple test: if you're translating between two existing interfaces → **Adapter**. If you're hiding complexity behind a new simpler interface → **Facade**.

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| A complex subsystem exposes too many details to clients | The subsystem is already simple |
| You want to define a clean entry point for a library or module | You need to expose full subsystem power — Facade would limit it |
| You want to layer your architecture (Controller → Service → Repository) | It becomes a "God object" — knowing too much, doing too much |

---

## Pattern 4 — Composite

### The Problem

You're building a **hierarchical structure** — a file system, a UI component tree, an org chart, a menu. You have two types of nodes:
- **Leaf nodes** — no children (a File, an Employee, a Button)
- **Composite nodes** — contain other nodes (a Folder, a Department, a Panel)

Without this pattern, clients write `if (isLeaf) ... else if (isComposite) ...` everywhere. As the tree grows, this logic spreads and becomes impossible to maintain. You want to treat both **uniformly**.

### Real-World Analogy

Think of a **file system**. A `File` is a leaf — it has a size, you can open it. A `Folder` is a composite — it contains files or other folders. When your OS calculates the size of a folder, it recursively sums everything inside. From your perspective, `getSize()` works the same whether you call it on a single file or a folder with 10,000 nested files. **That uniformity is Composite.**

### The Structure

```
OrganizationComponent (interface)
+ getName()
+ getSalaryCost()
+ display(depth)
        ▲
        │
  ┌─────┴─────┐
  │           │
Employee    Department
(leaf)      (composite)
            - members: List<OrganizationComponent>
            - getSalaryCost() → recursively sums all members
```

Three ingredients:
1. **Component interface** — common operations for both leaf and composite
2. **Leaf** — no children, implements operations directly (returns its own value)
3. **Composite** — holds children (which are `Component`s), implements operations by delegating recursively

### The Key Insight: Recursive Composition

The `Department` (composite) holds `List<OrganizationComponent>` — not `List<Employee>` and not `List<Department>`. It holds **components**. This means a Department can contain both Employees (leaves) and other Departments (composites). The tree can be arbitrarily deep.

```java
// Composite: delegates getSalaryCost() to all children recursively
@Override
public double getSalaryCost() {
    return members.stream()
                  .mapToDouble(OrganizationComponent::getSalaryCost) // works on both leaf + composite
                  .sum();
}

// Leaf: just returns its own salary — no recursion needed
@Override
public double getSalaryCost() {
    return salary;
}
```

The beauty: `getSalaryCost()` on the top-level `Department` recursively reaches every leaf in the entire tree. No type checking, no instanceof, no if-else.

### Building the Tree

```java
// Leaves
Employee alice = new Employee("Alice", "Backend Engineer",  120000);
Employee bob   = new Employee("Bob",   "Frontend Engineer", 110000);
Employee carol = new Employee("Carol", "ML Engineer",       130000);
Employee dave  = new Employee("Dave",  "QA Engineer",        90000);
Employee eve   = new Employee("Eve",   "Product Manager",   115000);
Employee frank = new Employee("Frank", "UX Designer",        95000);

// Sub-departments (composites containing leaves)
Department engineering = new Department("Engineering", "Engineering Lead");
engineering.add(alice); engineering.add(bob);
engineering.add(carol); engineering.add(dave);

Department product = new Department("Product", "Product Lead");
product.add(eve); product.add(frank);

// Top-level composite (composite containing composites)
Department company = new Department("Acme Corp", "CEO");
company.add(engineering); // a Department inside a Department — works seamlessly
company.add(product);
```

### Demo Output

```
=== Org Chart ===
🏢 Acme Corp (CEO) — Total cost: ₹660000.0
  🏢 Engineering (Engineering Lead) — Total cost: ₹450000.0
    👤 Alice (Backend Engineer) — ₹120000.0
    👤 Bob (Frontend Engineer) — ₹110000.0
    👤 Carol (ML Engineer) — ₹130000.0
    👤 Dave (QA Engineer) — ₹90000.0
  🏢 Product (Product Lead) — Total cost: ₹210000.0
    👤 Eve (Product Manager) — ₹115000.0
    👤 Frank (UX Designer) — ₹95000.0

=== Uniform Interface ===
Alice                cost: ₹120000     ← leaf
Engineering          cost: ₹450000     ← composite
Acme Corp            cost: ₹660000     ← composite of composites
```

The same `getSalaryCost()` call works uniformly on a single employee, a department, and the entire company.

### Real-World Uses of Composite

| Use case | Leaf | Composite |
|---|---|---|
| File system | File | Folder |
| UI component tree | Button, Label | Panel, Window |
| Organization chart | Individual contributor | Department, Division |
| Menu system | Menu item | Submenu |
| HTML DOM | Text node, `<img>` | `<div>`, `<section>` |

### When to Use / When to Avoid

| Use when | Avoid when |
|---|---|
| Structure is naturally hierarchical and recursive | The hierarchy is fixed and simple — no uniformity needed |
| Clients shouldn't need to distinguish leaf from composite | Leaf and composite operations are genuinely different |
| Operations need to work uniformly at any depth of the tree | Adding composite-only operations breaks the interface for leaves |

---

## Full Summary & Quick Reference

### Pattern Comparison Table

| Pattern | Wraps | Problem solved | Key relationship | Real-world trigger |
|---|---|---|---|---|
| **Adapter** | One class | Incompatible interfaces | Translates one interface to another | "Integrate this third-party library" |
| **Decorator** | One object, stackable | Add behavior without subclassing | IS-A and HAS-A the same interface | "Add logging/auth/rate-limiting to requests" |
| **Facade** | An entire subsystem | Subsystem too complex for clients | One simplified entry point | "Hide this 6-step pipeline behind one call" |
| **Composite** | A tree of objects | Treat leaves and groups uniformly | Recursive same-interface composition | "File system", "org chart", "menu tree" |

---

### Pattern Selection Guide

```
Composing objects and classes...
│
├── Two existing pieces can't communicate — interfaces don't match?
│       → ADAPTER
│       (you're translating between two existing interfaces)
│
├── Want to add behavior to specific objects, in varying combinations?
│       → DECORATOR
│       (wrap objects in layers, stack at runtime, no subclassing needed)
│
├── A subsystem has too many classes and callers need to know too much?
│       → FACADE
│       (create one clean entry point that hides all the internals)
│
└── Structure is hierarchical — leaves and groups need the same operations?
        → COMPOSITE
        (give both the same interface, let composites delegate recursively)
```

---

### Common Confusions Cleared Up

**Adapter vs Facade:**
- Adapter → one existing class, translating to a different interface
- Facade → many classes in a subsystem, creating a brand new simpler interface

**Decorator vs Inheritance:**
- Inheritance → adds behavior to ALL instances of a class, at compile time
- Decorator → adds behavior to SPECIFIC instances, dynamically at runtime

**Composite vs regular tree:**
- A regular tree uses `if (isLeaf) ... else ...` everywhere in client code
- Composite → client calls the same method on both leaf and composite, no type checking

---

### Key Java Reminders

| Topic | What to remember |
|---|---|
| Adapter | Return the target interface type, never the adaptee type |
| Decorator | `return this` in abstract decorator delegates to `wrapped.handle(...)` by default |
| Decorator stacking order | Outermost decorator runs first — design your pipeline accordingly |
| Facade | Facade can (and often should) be injected as a dependency, not instantiated by clients |
| Composite | `List<Component>` — not `List<Leaf>` or `List<Composite>` — is what enables uniform treatment |
| Composite recursion | Each composite calls the same method on its children — the recursion is natural, not forced |

---

### Anti-Patterns to Avoid

| Anti-pattern | Better alternative |
|---|---|
| Adding too many responsibilities to the Adapter (business logic) | Adapter should only translate — keep business logic in services |
| Decorator stack so deep it's unreadable | Consider a pipeline/chain-of-responsibility instead |
| Facade that becomes a "God object" knowing everything | Split into multiple focused facades per domain area |
| Using Composite when leaf and composite have genuinely different operations | Don't force the same interface — keep them separate |
| Using `instanceof` checks inside a Composite tree | If you need type checks, the interface isn't unified enough |

---

*Next up → Phase 3, Part 3: Behavioral Patterns (Strategy, Observer, Command, Template Method, Iterator, State)*

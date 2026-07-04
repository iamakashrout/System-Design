# Topic 1: Dependency Injection

## 1. Intuition

Every object you write needs *collaborators* — other objects it talks to in order to do its job. A `NotificationService` needs a way to send emails. An `OrderService` needs a way to persist orders. The question Dependency Injection (DI) answers is deceptively simple:

> **Who decides which collaborator an object gets, and when?**

There are two answers:

- **The object decides for itself** — it reaches out and constructs (`new EmailSender()`) or fetches (`ServiceLocator.get(...)`) its own dependency. The object is now *responsible for* its dependency, not just *dependent on* it.
- **Someone else decides, and hands it in** — the object simply declares "I need something that can send notifications," and the caller (or a framework) supplies a concrete instance.

The second approach is Dependency Injection. It sounds like a small shift, but it inverts a relationship that otherwise quietly calcifies your entire codebase. If `NotificationService` directly instantiates `EmailSender`, then:

- You cannot test `NotificationService` without actually sending an email.
- You cannot swap `EmailSender` for `SmsSender` without editing `NotificationService`'s source code.
- You cannot know, just by reading `NotificationService`'s constructor, what it actually depends on — the dependency is buried inside a method body.

DI solves a **coupling problem**, not a "best practice for its own sake" problem. Tight coupling is what makes codebases expensive to change six months from now. This is the same instinct behind loose coupling in distributed systems (services depending on contracts/APIs, not on each other's internals) — just applied at the object level instead of the service level.

## 2. Core Concepts

### 2.1 Depend on abstractions, not concretions

This is the "D" in SOLID (Dependency Inversion Principle), and DI is the *mechanism* that makes DIP practical. A class should declare its dependency as an interface (`NotificationChannel`), never as a concrete class (`EmailChannel`). The concrete choice is deferred to whoever wires the object graph together.

### 2.2 The three injection styles

| Style | How it works | When to prefer it |
|---|---|---|
| **Constructor Injection** | Dependencies passed as constructor parameters, stored in `final` fields | Default choice. Dependency is mandatory, object is immutable and always valid after construction. |
| **Setter Injection** | Dependencies set via public setter methods after construction | Dependency is optional, or needs to be reconfigured/swapped after construction (rare in practice). |
| **Field Injection** | Framework reaches into a field directly (e.g. Spring's `@Autowired` on a field) | Convenient with frameworks, but hides dependencies from the constructor signature — avoid in code you hand-write. |

Constructor injection is almost always the right default because it makes the dependency **visible and mandatory**: you cannot construct the object into an invalid half-wired state, and anyone reading the constructor immediately knows the full list of things this class needs to function.

### 2.3 Who does the wiring?

Something has to eventually call `new EmailChannel()`. DI doesn't eliminate `new` — it **relocates** it to one place: the *composition root* (often `main()`, or a framework's dependency container). Everywhere else in the codebase, code only ever sees interfaces.

- **Manual DI**: you write the wiring code yourself — a `main` method or a small `AppConfig` class that constructs the object graph.
- **Framework DI** (e.g. Spring): you declare which implementation satisfies which interface (via annotations or config), and a container builds the object graph for you at startup, resolving the dependency tree automatically. Conceptually this is the same manual wiring, just automated and cached in a container.

You don't need a framework to get the benefits of DI. The framework only removes the *manual labor* of wiring; the architectural benefit (loose coupling, testability) comes from depending on interfaces, which you get either way.

## 3. Real-World Analogy

Think of a restaurant kitchen. A chef (the class) needs ingredients (dependencies) to cook a dish. There are two ways this can work:

- **Bad**: the chef personally drives to a specific farm every morning to pick out one specific brand of tomato. If that farm shuts down, the chef's entire ability to cook is broken, and cooking now requires driving.
- **Good**: the chef says "I need tomatoes" and the kitchen's supply chain (someone else's job) delivers them. The chef doesn't care if today's tomatoes are from Farm A or Farm B — any tomato satisfying "ripe, red, edible" works. Swapping suppliers doesn't touch the chef's recipe at all.

The supply chain is your composition root. The interface `NotificationChannel` is "tomato — ripe, red, edible." `EmailChannel` and `SmsChannel` are Farm A and Farm B.

## 4. Implementation

See `DependencyInjection.java`. It walks through:

1. `BadNotificationService` — the tightly coupled version (constructs its own `EmailSender` internally).
2. `NotificationChannel` — the abstraction.
3. `EmailChannel`, `SmsChannel`, `PushChannel` — concrete implementations.
4. `NotificationService` — refactored to use **constructor injection**, accepting a `List<NotificationChannel>`.
5. A setter-injection variant showing an optional dependency (a `MetricsRecorder` that can be attached later).
6. `ServiceLocatorAntiPattern` — showing why the service locator pattern *looks* like DI but isn't.
7. A `main` method acting as a manual composition root, wiring the whole object graph by hand.

## 5. Anti-Patterns

- **`new` inside business logic**: Any time a class does `new ConcreteDependency()` inside a method (not in a factory whose entire job is construction), that class now owns a hidden, untestable, unswappable dependency.
- **Service Locator**: `NotificationService` calling `ServiceLocator.get(EmailSender.class)` *looks* decoupled because there's no `new`, but it isn't DI — the dependency is still fetched by the object itself, just from a different hiding place. The constructor signature lies about what the class needs; you have to read the method body to find out. It also makes unit testing awkward because you must configure a global locator before every test.
- **Static factories as dependencies**: Calling `EmailSenderFactory.getInstance()` inside a method has the identical problem as `new` — it's a compile-time-fixed dependency wearing a factory costume.
- **Field injection in hand-written code**: `@Autowired private EmailSender sender;` with no constructor hides the dependency from anyone reading the public API of the class, and allows constructing the object in a broken, half-initialized state before the framework populates the field.
- **"God" constructors as a symptom, not a DI problem**: If a class ends up with 12 injected dependencies, DI isn't the flaw — it's exposing a class that's doing too much (a Single Responsibility Principle violation the tight-coupled version was hiding).

## 6. Trade-offs

- **More upfront ceremony**: for a two-class toy program, manual DI is more boilerplate than just calling `new`. DI earns its cost in codebases that need to change, be tested, or scale in team size — not in throwaway scripts.
- **Framework DI has its own cost**: Spring's container makes wiring automatic but adds a layer of "magic" — errors surface at startup/runtime (missing bean, circular dependency) rather than at compile time, and new engineers need to learn the container's conventions.
- **Over-injection risk**: DI makes it *easy* to keep adding dependencies to a class, which can mask a design smell (a class that should be split) instead of forcing you to confront it.
- **Not a silver bullet for testability**: DI is necessary but not sufficient for testable design — you still need clean interfaces with well-defined contracts (Topic 2 builds on this directly).

## 7. Key Takeaways

1. DI is about **who owns the decision** of which concrete implementation an object uses — moving that decision out of the object itself and into a composition root.
2. Prefer **constructor injection** by default — it makes dependencies visible, mandatory, and produces immutable, always-valid objects.
3. DI is the practical mechanism for the **Dependency Inversion Principle**: depend on abstractions, not concretions.
4. A framework (Spring, etc.) automates *wiring*, but the architectural benefit — loose coupling and testability — comes from coding to interfaces, which manual DI already gives you.
5. Service locators and static factories are DI look-alikes that still hide the real dependency inside the method body — the constructor signature should never lie about what a class needs.

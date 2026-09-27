# 03. Low-Level Design (LLD)

HLD tells you what components a system needs. LLD tells you how to actually build one of those components: what classes exist, how they relate to each other, and how you write code that's correct, extensible, and doesn't fall apart the moment a new requirement shows up.

This is the biggest and most hands-on section of the repo. Every topic here pairs a `notes.md` file (or several) with runnable Java files, and every single Java file actually compiles and runs, they're not pseudocode or snippets, they're complete working programs with a `main` method you can execute to see the concept in action. Reading the notes tells you the theory. Running the code is what makes it stick.

## A note before you start

Almost every subfolder mixes `.java` files with markdown notes, but the file names on disk are usually alphabetical, not the order you should read them in. Where the reading order isn't obvious from the file names, this README tells you the actual order, because getting this wrong (for example, reading about deadlocks before you've learned what a lock is) makes the later material harder to follow than it needs to be.

## What's inside

### `01-oop-principles/`
The four pillars, covered with intuition first: Encapsulation (the vending machine analogy, hiding internals behind a controlled interface), Abstraction, Inheritance, and Polymorphism, with a `.java` file demonstrating each one and a `notes.md` that also compares the four principles against each other and shows how they work together in a single design. This is the true starting point of LLD, everything after this assumes you're comfortable with these four ideas.

### `02-solid-principles/`
The five SOLID principles, one Java file per letter (`S_SingleResponsibility.java` through `D_DependencyInversion.java`), plus a `notes.md` that explains not just what each principle says but what breaks when you violate it. The notes also explain how the five principles reinforce each other, SRP gives you clean structure, OCP protects that structure as the code grows, LSP keeps inheritance honest, ISP keeps interfaces lean, and DIP decouples business logic from infrastructure.

### `03-object-modelling-and-relationships/`
Where you go from "I know the principles" to "I can actually model a real system." Covers identifying entities and responsibilities from a problem statement (the noun-verb technique), the four object relationships (association, aggregation, composition, dependency, each with its own demo file), inheritance vs composition and when to pick one over the other, and designing clean APIs for your classes. Everything comes together in a full worked example, a Library Management System, built in `LibrarySystemDemo.java`.

### `04-uml/`
A single but thorough notes file on reading and drawing UML class diagrams: the anatomy of a class box, visibility symbols, how to represent abstract classes and interfaces, the five relationship arrows, multiplicity, and a step-by-step guide to drawing a diagram from scratch. Backed by four actual SVG diagrams of increasing complexity, from a simple vehicle class up to a full e-commerce model, so you can see the notation applied rather than just described. Worth going through right after `03`, since UML is really just a way of drawing what you just learned to model in code.

### `05-design-patterns/`
The classic Gang of Four patterns, split into three sub-folders by category, plus a fourth folder that extends the idea into how real systems model state. Each pattern sub-folder has its own `notes.md` that explains why that category of pattern exists before getting into the individual patterns.

- **`01-creational-patterns/`** — the problems that come from writing `new ConcreteClass()` all over your code (tight coupling, no instance control, construction logic leaking into business logic). Covers Singleton, Factory Method, Abstract Factory, Builder, and Prototype.
- **`02-structural-patterns/`** — how to compose objects into larger structures, especially when the pieces weren't originally designed to fit together. Covers Adapter, Decorator, Facade, and Composite.
- **`03-behavioral-patterns/`** — how objects communicate and how responsibility and behavior stay flexible instead of hardcoded. Covers Strategy, Observer, Command, State, and Chain of Responsibility.
- **`04-enums-and-state-machines/`** — a bridge folder that takes the State pattern you just learned and asks a more fundamental question: how do you model state itself? Covers modelling states with enums, building a transition table, a full order-lifecycle domain model, and a direct comparison of the State pattern vs an enum-based state machine.

Read the three pattern categories in the order listed above, creational, then structural, then behavioral, the notes files explicitly build on each other in that sequence, then finish with enums and state machines.

### `06-concurrency-and-thread-safety/`
Arguably the most technically dense folder in the repo, and the one where reading order matters most, since the file names on disk are alphabetical and don't match the intended sequence. The notes themselves are labelled by phase and chapter, and the actual order is:

1. **`concurrency_basics.md`** — race conditions, visibility, `volatile`, and `synchronized`. The opening chapter, start here.
2. **`reentrant_lock_and_atomics.md`** — explicit locking with `ReentrantLock` and lock-free programming with the `atomic` package.
3. **`thread_pools_and_concurrent_collections.md`** — the `ExecutorService` framework and the concurrent collection types that replace unsafe standard collections.
4. **`deadlock_and_double_checked_locking.md`** — the most dangerous concurrency failure mode, prevention strategies, and a deep dive into double-checked locking that ties `volatile`, instruction reordering, and the Java Memory Model together.
5. **`producer_consumer_and_thread_safe_design.md`** — the producer-consumer pattern and how to design thread-safe classes from scratch.
6. **`concurrency_summary_and_mental_model.md`** — closes the whole phase with a single table mapping every concurrency problem to the right tool (visibility → `volatile`, compound operations → `synchronized`, simple counters → `AtomicInteger`, and so on). Read this last, it only makes sense once you've seen all five tools it's summarizing, but it's the single best page to revisit before an interview.

Each numbered `.java` file (`ConcurrencyBasics.java`, `ReentrantLockAndAtomics.java`, etc.) pairs with the notes file of the same topic, match them up by name even though the notes file names use underscores and the Java files use CamelCase.

### `07-interview-problems/`
Twelve classic LLD interview problems, each with a working Java implementation and a companion notes file: Parking Lot, LRU Cache, Rate Limiter, Elevator System, Chess Game, File System, Notification System, Ride Sharing System, Library Management System, Hotel Booking System, ATM System, and Vending Machine System (explicitly marked as the final one in this set). The last five are explicitly numbered in their notes (Problem 8 through Problem 12), continuing a sequence, so treat the unlabelled seven as problems 1 through 7 covering the more foundational, single-concept problems (a cache, a rate limiter, a state-machine-heavy system like an elevator or vending machine) before the labelled five, which tend to combine more moving parts (booking systems, ride matching, ATMs) and lean harder on everything from `01` through `06`.

This is the folder to use for active interview practice: read the problem statement at the top of a notes file, close it, try to design and code your own solution, then compare against the repo's version and its notes.

### `08-engineering-best-practices/`
Not classic LLD theory, this is the layer above it: the habits that separate code that merely works from code a team can actually maintain. Like the concurrency folder, the notes are explicitly numbered by topic and the file names don't sort into that order, so here's the real sequence:

1. **`dependency_injection_notes.md`** — Dependency Injection
2. **`testable_design_notes.md`** — Testable Design
3. **`exception_design_notes.md`** — Exception Design
4. **`clean_architecture_notes.md`** — Clean Architecture Principles
5. **`logging_observability_notes.md`** — Logging and Observability
6. **`defensive_api_design_notes.md`** — Defensive Programming and API Design

Each has a matching `.java` file. This folder is best read after you've done at least a few problems in `07`, the practices here (why you inject dependencies instead of `new`-ing them inline, how to design exceptions that mean something, what makes a class testable in the first place) land a lot better once you've felt the pain of not doing them.

## How to go through this section

Follow the numbered folders in order for the most part, `01` through `05` build directly on each other: principles, then SOLID, then object modelling, then UML as the notation for what you just modelled, then design patterns as named, reusable solutions built out of everything before them. Don't skip ahead to patterns without a solid handle on `01` through `03`, most patterns are really just OOP principles applied to a specific recurring problem, and they're much easier to remember that way than as a list to memorize.

`06` (concurrency) stands a bit apart from the rest, it doesn't depend on `05`, so if you already know OOP, SOLID, and object modelling well, you can tackle it any time. But within the folder itself, follow the six-step order above, not the alphabetical file listing.

Once `01` through `06` feel solid, move to `07` and treat it as practice, not reading material. Work through the twelve problems roughly in the order suggested above (simpler, single-concept systems first, the numbered 8–12 combination-heavy ones last), attempting each yourself before checking the repo's implementation.

Save `08` for last, or dip into it gradually alongside `07`. It's less about learning new concepts and more about leveling up the code you're already writing, so it pairs naturally with active practice rather than being consumed in one sitting.
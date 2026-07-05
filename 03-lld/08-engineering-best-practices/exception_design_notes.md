# Topic 3: Exception Design

## 1. Intuition

Every program has two kinds of "wrongness": bugs (the programmer made a mistake) and exceptional-but-expected conditions (the world didn't cooperate — insufficient funds, a network timeout, a card that's blocked). Exception design is about building a vocabulary that lets your code communicate *which kind of wrong just happened*, precisely enough that the caller can make an intelligent decision about what to do next.

Badly designed exceptions fail in one of two directions:

- **Too generic**: everything is a `RuntimeException` with a string message. Callers can't distinguish "insufficient funds" (show the user a message, let them try a smaller amount) from "database connection lost" (retry, or escalate to an on-call engineer) — both look identical from the catch block.
- **Too granular / too checked**: every possible failure is its own checked exception that must be declared and handled at every call site, even by code three layers up that has no ability to do anything useful about it except forward the failure. This produces boilerplate that trains engineers to write `catch (Exception e) {}` just to make the compiler stop complaining — which is worse than not having exceptions at all.

Good exception design sits between these: a **hierarchy** that groups related failures, is unchecked by default so it doesn't pollute every signature up the call stack, and is specific enough that a caller who *can* meaningfully react to a particular failure has a type to catch.

## 2. Core Concepts

### 2.1 Checked vs. unchecked — the debate

- **Checked exceptions** (`extends Exception`) force the compiler to verify every caller either handles or declares the exception. Java's original intent: make recoverable, expected failure conditions impossible to silently ignore.
- **Unchecked exceptions** (`extends RuntimeException`) impose no compiler obligation. The failure propagates until *something* chooses to catch it.

In practice, checked exceptions have fallen out of favor for most application-level exception hierarchies, for a concrete reason: **most callers, at most layers, cannot do anything meaningful about most failures.** A service three layers above a database call cannot "handle" a connection timeout beyond logging it and failing the request — but a checked exception forces every single method signature between the failure and the one place that *can* meaningfully react (often just the top-level request handler) to either declare or wrap it. This is called "exception signature pollution," and it's the most common complaint about Java's checked-exception design.

The pragmatic modern guideline (used by most production Java codebases, and articulated in *Effective Java*):

- Use **checked exceptions** only when the failure is *expected as part of the normal operation of the API* **and** the caller can be reasonably expected to recover from it at the immediate call site (rare — e.g., `java.io.IOException` for retry-able I/O).
- Use **unchecked exceptions** for everything else, especially domain-specific business-rule violations. Let them propagate to a layer (often a top-level request handler / controller) that knows how to translate them into a user-facing response.

### 2.2 Designing a clean exception hierarchy

A hierarchy should mirror the *categories of failure* in your domain, not every individual failure mode as a sibling of everything else. A flat pile of forty unrelated exception classes is as unhelpful as one giant catch-all.

```
AtmException (abstract base, unchecked)
├── AuthenticationException
│   ├── InvalidPinException
│   └── CardBlockedException
├── TransactionException
│   ├── InsufficientFundsException
│   ├── DailyLimitExceededException
│   └── InsufficientCashInDispenserException
└── AtmSystemException          (infrastructure/hardware failures)
    └── CashDispenserHardwareException
```

This shape lets a caller catch at whatever granularity makes sense for that layer:

- Catch `InvalidPinException` specifically to increment a failed-attempt counter.
- Catch `AuthenticationException` broadly to redirect to a "please re-authenticate" screen, without caring about the specific subtype.
- Catch `AtmException` at the outermost boundary as a last-resort "something in our domain went wrong" handler — while still letting genuinely unrelated bugs (a `NullPointerException` from a real defect) propagate uncaught, because that's a different category of problem entirely (see Anti-Patterns).

### 2.3 Exception wrapping and translation across layers

Lower layers often throw exceptions in vocabulary that's meaningless to upper layers — a hardware driver throws `IOException`, a JDBC call throws `SQLException`. The layer that sits at the boundary should **translate** these into the domain's own exception vocabulary, while **preserving the original exception as the cause** (`new CashDispenserHardwareException("...", ioException)`). This gives you two things simultaneously:

- Upper layers only ever need to know about domain exceptions (`AtmException` and subtypes) — they're decoupled from the fact that cash dispensing happens to be implemented via a serial port today and might be a REST call to a different subsystem tomorrow.
- The full original stack trace is preserved via the exception's `cause` chain, so nothing is lost for debugging — `getCause()` still gets you to the original `IOException`.

### 2.4 Fail-fast vs. defensive programming

- **Fail-fast**: validate inputs and invariants as early as possible, and throw immediately and loudly the moment something is wrong, rather than letting a bad value travel deep into the system before it causes a confusing failure far from its root cause. `withdraw(-50)` should throw `IllegalArgumentException` at the API boundary, not silently underflow an account balance three layers down.
- **Defensive programming**, taken to an extreme, means wrapping everything in null-checks and try/catch "just in case," which often *masks* bugs instead of surfacing them (see Anti-Patterns: swallowing exceptions).

The right balance: fail fast and loud for programmer errors and invalid input (these should almost always be unchecked, e.g. `IllegalArgumentException`, `IllegalStateException`) — but design *expected* business failures (insufficient funds, card blocked) as first-class named exceptions in your hierarchy, not as generic `IllegalStateException`s, because callers legitimately need to distinguish and react to them differently.

## 3. Real-World Analogy

Think of a hospital's triage system. Not every patient walking into an ER gets the identical response — the categorization itself is the value. A sprained ankle, a heart attack, and a common cold all get *routed differently* based on a small, well-understood set of categories (immediate/urgent/non-urgent), not based on someone reading every patient's full medical history from scratch each time. A well-designed exception hierarchy is triage for failures: the *category* the exception falls into tells the catching code, at a glance, roughly how urgent and how recoverable this is — without needing to inspect a string message.

## 4. Implementation

See `ExceptionDesign.java`. It builds a full exception hierarchy for the ATM System (Phase 5, Problem 11) and demonstrates:

1. The `AtmException` hierarchy shown above — all unchecked, since none of these are conditions a random intermediate caller can usefully recover from except the specific handler designed for that category.
2. **Exception translation**: a `CashDispenserHardware` class simulating a real hardware driver that throws a *checked* `IOException` (representing a genuinely external, checked-appropriate failure), translated at the boundary into an unchecked `CashDispenserHardwareException` with the original exception preserved as `cause`.
3. **Fail-fast validation**: `AtmService.withdraw(...)` validates the amount is positive and a multiple of the minimum denomination *before* touching any state, throwing `IllegalArgumentException` immediately.
4. A `main` method exercising each category, showing how a caller can catch at different levels of specificity, and printing the full cause chain for the wrapped hardware exception.
5. Explicit **anti-pattern demonstrations** — swallowed exceptions, catching `Exception`, and exceptions used for control flow — each clearly labeled as what *not* to do, with commentary on why.

## 5. Anti-Patterns

- **Swallowing exceptions** (`catch (Exception e) {}`): the failure disappears silently. The system continues running in a state the code *believed* was impossible, which turns a loud, immediate, diagnosable failure into a much harder-to-trace bug discovered far later (or never).
- **Catching `Exception` or `Throwable` broadly**: this catches genuine programmer bugs (`NullPointerException`, `ArrayIndexOutOfBoundsException`) in the same breath as expected domain failures, treating "I forgot to check for insufficient funds" identically to "there's a null-pointer bug in my code." These deserve fundamentally different responses — the former is expected and recoverable, the latter should usually crash loudly (or be caught at a global handler that logs and alerts) rather than being quietly absorbed at some intermediate layer.
- **Using exceptions for control flow**: throwing an exception to break out of a loop, or to signal "not found" for an expected, common case (e.g., throwing `NotFoundException` for every cache miss in a hot path) is both a performance problem (stack trace construction is expensive) and a readability problem (exceptions should signal *exceptional*, not routine, outcomes — an `Optional<T>` or a boolean return is the right tool for an expected "not found").
- **Losing the cause chain**: catching a lower-level exception and re-throwing a new one *without* passing the original as `cause` destroys the original stack trace, making production debugging dramatically harder for no benefit.
- **Exceptions with vague, generic messages**: `throw new RuntimeException("error")` gives a future debugger (including you, at 2 AM, on-call) nothing to work with. Exception messages should include the specific values/context that caused the failure.

## 6. Trade-offs

- **Checked exceptions aren't universally wrong** — for a small, stable public API where callers genuinely need to be forced to handle a specific, recoverable condition (e.g., a parsing library's `ParseException`), checked exceptions can be the right call. The failure mode to avoid is checked exceptions for *every* possible failure in a large system with many layers.
- **Deep hierarchies vs. flat hierarchies**: too many hierarchy levels for a small system is over-engineering; a two-or-three-level hierarchy (as shown above) is usually the sweet spot for a bounded domain. Reassess hierarchy depth as the domain's failure modes actually grow, rather than designing for hypothetical future categories upfront.
- **Wrapping exceptions adds a small amount of indirection**: engineers unfamiliar with the codebase need to follow `getCause()` to find the original failure. This cost is consistently worth paying once a system has more than one or two layers, because the decoupling benefit (callers depend on your domain's vocabulary, not on whichever library or subsystem happens to be underneath today) compounds as the system grows.

## 7. Key Takeaways

1. Exceptions are a communication tool — a hierarchy should mirror the actual *categories* of failure in your domain, letting each layer catch at the level of specificity that layer can actually act on.
2. Default to unchecked exceptions for domain/business failures; reserve checked exceptions for the rare case where the immediate caller can and should recover right there.
3. Translate exceptions at layer boundaries into your domain's own vocabulary, always preserving the original as `cause` — never lose the chain.
4. Fail fast and loud on invalid input and programmer errors; model *expected* business failures as specific, named exceptions rather than generic ones.
5. Never swallow exceptions, never catch broad `Exception`/`Throwable` as a blanket habit, and never use exceptions to implement routine control flow.

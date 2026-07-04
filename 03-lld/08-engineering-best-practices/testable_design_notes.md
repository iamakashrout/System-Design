# Topic 2: Testable Design

## 1. Intuition

Testability isn't a property you bolt on after the fact by "writing more tests." It's a property of the **design** itself. Some code is trivial to test in five minutes; other code requires standing up a database, mocking three layers, and hoping the clock cooperates. The difference is almost never test-writing skill — it's whether the code was designed with a seam for tests to plug into.

The core question testable design asks is:

> **Can I exercise this unit of behavior in isolation, deterministically, without side effects I don't control?**

If the answer is no, it's usually because of one of a small number of root causes — and every one of them is a design decision made earlier, not a testing problem discovered later. This is why testability is really a *consequence* of good dependency management (Topic 1) rather than a separate discipline bolted on top.

## 2. Core Concepts

### 2.1 What makes code hard to test

- **Static/global dependencies** — a class calling `Database.getConnection()` or `ServiceLocator.get(...)` has a dependency a test cannot swap out.
- **`new` inside business logic** — same root cause as Topic 1: if a method constructs its own collaborator, a test cannot substitute a fake one.
- **Non-determinism** — `System.currentTimeMillis()`, `new Random()`, `UUID.randomUUID()` called directly inside logic makes the *same* input produce *different* outputs on different runs. A test cannot assert on an outcome that changes every time.
- **Hidden global/mutable state** — static fields that persist across test runs cause tests to pass or fail depending on execution order, which is one of the most time-wasting categories of test flakiness.
- **Side effects mixed into computation** — a method that both calculates a result *and* writes to a file/sends a network call/logs cannot be tested for the calculation without also triggering (or mocking away) the side effect.

### 2.2 Designing for mockability

The fix mirrors Topic 1 almost exactly: **depend on interfaces, and inject them.** If a class needs "the current time," it should depend on a `Clock` interface, not call `System.currentTimeMillis()` directly. In tests, you hand it a `FakeClock` you fully control. This single seam — an injected `Clock` — is often the difference between a rate limiter or token-expiry system that's trivially testable and one that requires `Thread.sleep()` calls in tests (slow, flaky, and still non-deterministic).

The general pattern: **any non-deterministic or external resource (time, randomness, network, disk, database) should be wrapped behind an interface your code depends on, injected at construction.**

### 2.3 Arrange → Act → Assert (AAA)

Every unit test should read as three clearly separated blocks:

1. **Arrange** — set up the object under test and its (fake/stub) dependencies.
2. **Act** — call the one method/behavior being tested.
3. **Assert** — check the outcome matches expectations.

Keeping these visually separated (even with blank lines or comments) makes tests self-documenting — a future reader (including you, in six months) can see the exact scenario and expected outcome without reading the implementation.

### 2.4 Mocking and stubbing (conceptual)

- **Stub**: a fake implementation that returns canned/fixed responses. Used when you just need *some* value back and don't care how it's used. (`FakeClock` returning a fixed time is a stub.)
- **Mock**: a fake implementation that additionally records *how it was called* so the test can assert on interactions (e.g., "was `send()` called exactly once, with these arguments?"). Frameworks like Mockito generate these automatically instead of you hand-writing a class per test.
- You don't need Mockito to benefit from this thinking — hand-writing a small `FakeClock` or `RecordingNotificationChannel` gets you 90% of the value for free, and understanding *why* it works is what makes using Mockito later feel obvious rather than magical.

### 2.5 Testing state machines

The state machines from Phase 3.5 (rich enum or GoF State) are naturally easy to test because state transitions are pure logic — no I/O, no time, no randomness. The right test shape is:

- For every **valid** transition: assert it succeeds and the resulting state is correct.
- For every **invalid** transition: assert it throws (e.g., `InvalidTransitionException`) rather than silently succeeding or corrupting state.

Because the state machine has no external dependencies, this is close to "free" testability — which is itself a strong argument for keeping state-transition logic isolated from I/O-heavy orchestration code (this connects directly to Topic 4, Clean Architecture).

### 2.6 Testing concurrent code

Concurrent code is the hardest category to test because race conditions are, by definition, non-deterministic — a bug might only manifest 1 time in 10,000 runs. Strategies:

- **Stress testing**: run the operation from many threads simultaneously (via `ExecutorService` + `CountDownLatch` to start them at the same instant) and assert the final shared state is exactly what's expected (e.g., a counter incremented by N threads M times each should equal `N * M` — if it's ever less, you've caught lost updates).
- **Deterministic unit tests for logic, separate from concurrency**: test the *business logic* of a thread-safe class through its public API single-threaded first (fast, deterministic), and reserve multi-threaded stress tests for verifying the *safety* property (no lost updates, no deadlock) separately.
- **Timeouts on tests**: concurrent bugs can manifest as deadlocks — a test that can hang forever should have an explicit timeout so a broken build fails fast instead of hanging CI.
- Full determinism is not achievable for concurrency bugs — stress tests increase confidence, they don't prove absence of race conditions the way a pure-logic unit test can prove correctness of an algorithm.

## 3. Real-World Analogy

Think of testable design like designing a car engine to be tested on a dynamometer (a stationary test rig) before it's ever bolted into a car. An engine that can *only* be evaluated by physically driving the finished car down a highway is an engine that was designed without test seams — you can't isolate "does the engine produce the right torque" from "does the whole car work." A dynamometer-testable engine has clear, isolated inputs (fuel, air, ignition timing) and measurable outputs (torque, RPM) with no hidden dependency on the rest of the car. Injected fakes are your dynamometer — they let you evaluate one component's behavior in complete isolation from everything around it.

## 4. Implementation

See `TestableDesign.java`. It's self-contained (no JUnit/Mockito dependency, so it compiles and runs standalone) and walks through four scenarios:

1. **Before/after refactor** of a Rate Limiter (Phase 5, Problem 3): `RateLimiterBad` calls `System.currentTimeMillis()` directly and is untestable without `Thread.sleep()`. `FixedWindowRateLimiter` takes an injected `Clock`, tested deterministically with a `FakeClock`.
2. A tiny hand-rolled **test harness** (`assertEquals`/`assertTrue` + pass/fail reporting) standing in for JUnit, so the AAA structure is visible without an external dependency.
3. **State machine testing** — valid and invalid `OrderState` transitions (Phase 3.5), asserting both the happy path and that `InvalidTransitionException` is thrown correctly.
4. **Concurrency stress test** — a thread-safe `Counter` hammered by multiple threads via `ExecutorService` + `CountDownLatch`, asserting the final value has no lost updates.

## 5. Anti-Patterns

- **Testing through `Thread.sleep()`**: if the only way to test time-based logic is to literally wait using real wall-clock time, the code has a testability defect — inject a `Clock` instead.
- **Asserting on logs or print statements**: tests that scrape console output instead of asserting on return values/state are fragile and usually indicate the method's real output isn't structured or accessible.
- **One giant test that exercises the whole system**: an "integration test" masquerading as a unit test — slow, hard to diagnose on failure (which of the twelve things it touched actually broke?), and often a sign the unit under test has too many hidden dependencies to isolate.
- **Mocking types you don't own carelessly**: over-mocking third-party library internals couples your tests to that library's implementation details rather than to your own contract — prefer wrapping the third-party dependency behind your own interface and mocking *that*.
- **Shared mutable test fixtures across test methods**: static/shared state between test methods causes order-dependent failures — every test should arrange its own fully independent state.

## 6. Trade-offs

- **Fakes/stubs vs. real dependencies**: fakes are fast and deterministic but can drift from real behavior over time (a `FakeClock` can't accidentally reveal a real clock's timezone bug). Some level of integration testing against real dependencies is still necessary — unit tests with fakes and integration tests are complementary, not substitutes for each other.
- **Stress tests are probabilistic, not proofs**: passing a concurrency stress test increases confidence but does not formally prove thread safety — for very high-stakes concurrent code, this is where the discipline from Phase 4 (understanding *why* the code is safe, e.g., via CAS/locking reasoning) is what actually gives you confidence, with the stress test as a supporting check.
- **Extra abstraction for testability has a real cost**: introducing a `Clock` interface for a single throwaway script is overkill. This investment pays off in code that will be maintained, extended, and depended on by others — the same judgment call as Topic 1's DI trade-off.

## 7. Key Takeaways

1. Testability is a design property, not a test-writing skill — it comes from removing static dependencies, hidden `new` calls, and non-determinism from the units you want to test in isolation.
2. Wrap non-deterministic or external resources (time, randomness, I/O) behind an interface and inject it — this single seam (e.g., `Clock`) is often the entire difference between testable and untestable code.
3. Structure every test as Arrange → Act → Assert so it documents the exact scenario and expectation.
4. State machines are naturally easy to test because they're pure logic; keep them isolated from I/O to preserve that property (a preview of Clean Architecture in Topic 4).
5. Concurrent code needs a different testing strategy (stress tests for safety) than pure logic (deterministic unit tests for correctness) — don't conflate the two.

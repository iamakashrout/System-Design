import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TOPIC 2: TESTABLE DESIGN
 *
 * Domain: Rate Limiter (Phase 5, Problem 3) + Order state machine (Phase 3.5)
 *         + a thread-safe Counter (Phase 4 concurrency).
 *
 * This file is intentionally dependency-free (no JUnit, no Mockito) so it
 * compiles and RUNS standalone in any plain JRE. In a real Maven/Gradle
 * project, replace the hand-rolled TinyTestHarness below with JUnit5, and
 * replace hand-written fakes like FakeClock with Mockito mocks where the
 * interaction (not just the return value) matters.
 */
public class TestableDesign {

    // =========================================================================
    // PART 0: A tiny, dependency-free test harness
    //
    // Stands in for JUnit's assertEquals/assertTrue + test runner so this
    // file has zero external dependencies. The AAA structure below would
    // look identical under real JUnit — only the annotations/imports change.
    // =========================================================================

    static final class TinyTestHarness {
        private int passed = 0;
        private int failed = 0;

        void assertEquals(String testName, Object expected, Object actual) {
            if (expected == null ? actual == null : expected.equals(actual)) {
                passed++;
                System.out.println("[PASS] " + testName);
            } else {
                failed++;
                System.out.println("[FAIL] " + testName
                        + " -- expected=" + expected + " actual=" + actual);
            }
        }

        void assertTrue(String testName, boolean condition, String detail) {
            if (condition) {
                passed++;
                System.out.println("[PASS] " + testName);
            } else {
                failed++;
                System.out.println("[FAIL] " + testName + " -- " + detail);
            }
        }

        void summarize() {
            System.out.println();
            System.out.println("Results: " + passed + " passed, " + failed + " failed");
        }
    }

    // =========================================================================
    // PART 1: THE UNTESTABLE VERSION — hard-coded, non-deterministic time
    // =========================================================================

    /**
     * ANTI-PATTERN: calls System.currentTimeMillis() directly inside the
     * business logic. To test the "window resets after N seconds" behavior,
     * a test would have to call Thread.sleep(N * 1000) and actually wait —
     * slow, flaky under CI load, and the test still can't test edge cases
     * like "exactly at the window boundary" deterministically.
     */
    static final class RateLimiterBad {
        private final int maxRequests;
        private final long windowMillis;
        private int requestCount = 0;
        private long windowStart = System.currentTimeMillis(); // <-- non-deterministic

        RateLimiterBad(int maxRequests, long windowMillis) {
            this.maxRequests = maxRequests;
            this.windowMillis = windowMillis;
        }

        boolean allowRequest() {
            long now = System.currentTimeMillis(); // <-- hidden dependency on real time
            if (now - windowStart >= windowMillis) {
                windowStart = now;
                requestCount = 0;
            }
            if (requestCount < maxRequests) {
                requestCount++;
                return true;
            }
            return false;
        }
    }

    // =========================================================================
    // PART 2: THE TESTABLE REFACTOR — inject the time source
    // =========================================================================

    /** The seam: an abstraction over "what time is it right now." */
    interface Clock {
        long currentTimeMillis();
    }

    /** Production implementation — the ONLY place that touches real time. */
    static final class SystemClock implements Clock {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }

    /**
     * Test double — a STUB the test fully controls. Time only advances when
     * the test explicitly calls advanceBy(...), making window-boundary
     * behavior fully deterministic and instant (no real waiting).
     */
    static final class FakeClock implements Clock {
        private long currentMillis;

        FakeClock(long startMillis) {
            this.currentMillis = startMillis;
        }

        @Override
        public long currentTimeMillis() {
            return currentMillis;
        }

        void advanceBy(long millis) {
            this.currentMillis += millis;
        }
    }

    /**
     * Refactored rate limiter — constructor-injected Clock (Topic 1's
     * pattern applied directly to solve a testability problem). Behavior
     * is identical to RateLimiterBad; only the time source changed.
     */
    static final class FixedWindowRateLimiter {
        private final int maxRequests;
        private final long windowMillis;
        private final Clock clock;
        private int requestCount = 0;
        private long windowStart;

        FixedWindowRateLimiter(int maxRequests, long windowMillis, Clock clock) {
            this.maxRequests = maxRequests;
            this.windowMillis = windowMillis;
            this.clock = clock;
            this.windowStart = clock.currentTimeMillis();
        }

        boolean allowRequest() {
            long now = clock.currentTimeMillis();
            if (now - windowStart >= windowMillis) {
                windowStart = now;
                requestCount = 0;
            }
            if (requestCount < maxRequests) {
                requestCount++;
                return true;
            }
            return false;
        }
    }

    static void testRateLimiter(TinyTestHarness t) {
        System.out.println("--- Rate Limiter tests (deterministic via FakeClock) ---");

        // Arrange
        FakeClock clock = new FakeClock(0L);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, 1000L, clock);

        // Act + Assert: first two requests within the window are allowed
        t.assertTrue("first request allowed", limiter.allowRequest(), "expected true");
        t.assertTrue("second request allowed", limiter.allowRequest(), "expected true");

        // Act + Assert: third request in the same window is rejected
        t.assertTrue("third request rejected (limit hit)", !limiter.allowRequest(), "expected false");

        // Arrange: advance time PAST the window boundary — no Thread.sleep needed
        clock.advanceBy(1000L);

        // Act + Assert: window has reset, request is allowed again
        t.assertTrue("request allowed after window reset", limiter.allowRequest(), "expected true");

        // Arrange: advance time to just BEFORE the boundary
        FakeClock boundaryClock = new FakeClock(0L);
        FixedWindowRateLimiter boundaryLimiter = new FixedWindowRateLimiter(1, 1000L, boundaryClock);
        boundaryLimiter.allowRequest(); // consume the single allowed slot
        boundaryClock.advanceBy(999L);

        // Act + Assert: one millisecond before the window resets, still blocked
        t.assertTrue("request rejected 1ms before window resets",
                !boundaryLimiter.allowRequest(), "expected false");
    }

    // =========================================================================
    // PART 3: TESTING A STATE MACHINE (Phase 3.5)
    // =========================================================================

    /** Simplified rich-enum state machine, same shape as the Phase 3.5 Order lifecycle. */
    enum OrderState {
        CREATED, PAID, SHIPPED, DELIVERED, CANCELLED;

        boolean canTransitionTo(OrderState target) {
            switch (this) {
                case CREATED:
                    return target == PAID || target == CANCELLED;
                case PAID:
                    return target == SHIPPED || target == CANCELLED;
                case SHIPPED:
                    return target == DELIVERED;
                case DELIVERED:
                case CANCELLED:
                    return false;
                default:
                    return false;
            }
        }
    }

    static final class InvalidTransitionException extends RuntimeException {
        InvalidTransitionException(OrderState from, OrderState to) {
            super("Cannot transition from " + from + " to " + to);
        }
    }

    static final class Order {
        private OrderState state = OrderState.CREATED;

        OrderState getState() {
            return state;
        }

        void transitionTo(OrderState target) {
            if (!state.canTransitionTo(target)) {
                throw new InvalidTransitionException(state, target);
            }
            state = target;
        }
    }

    static void testOrderStateMachine(TinyTestHarness t) {
        System.out.println();
        System.out.println("--- Order state machine tests ---");

        // Arrange + Act + Assert: valid transition path
        Order order = new Order();
        order.transitionTo(OrderState.PAID);
        t.assertEquals("CREATED -> PAID succeeds", OrderState.PAID, order.getState());

        order.transitionTo(OrderState.SHIPPED);
        t.assertEquals("PAID -> SHIPPED succeeds", OrderState.SHIPPED, order.getState());

        // Arrange: a fresh order, Act + Assert: invalid transition throws
        Order invalidOrder = new Order();
        boolean threw = false;
        try {
            invalidOrder.transitionTo(OrderState.DELIVERED); // cannot skip PAID/SHIPPED
        } catch (InvalidTransitionException e) {
            threw = true;
        }
        t.assertTrue("CREATED -> DELIVERED throws InvalidTransitionException", threw,
                "expected InvalidTransitionException to be thrown");

        // Assert state was NOT mutated by the failed transition attempt
        t.assertEquals("state unchanged after rejected transition",
                OrderState.CREATED, invalidOrder.getState());
    }

    // =========================================================================
    // PART 4: TESTING CONCURRENT CODE (Phase 4)
    // =========================================================================

    /** A thread-safe counter using an atomic, following Phase 4 concurrency patterns. */
    static final class Counter {
        private final AtomicInteger value = new AtomicInteger(0);

        void increment() {
            value.incrementAndGet();
        }

        int get() {
            return value.get();
        }
    }

    static void testCounterUnderConcurrency(TinyTestHarness t) throws InterruptedException {
        System.out.println();
        System.out.println("--- Concurrency stress test ---");

        // Arrange
        int threadCount = 20;
        int incrementsPerThread = 1000;
        Counter counter = new Counter();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1); // all threads start together
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        // Act: fire all threads at once to maximize chance of exposing races
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await(); // wait for the starting signal
                    for (int j = 0; j < incrementsPerThread; j++) {
                        counter.increment();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown(); // release all threads simultaneously
        boolean completedInTime = doneGate.await(5, TimeUnit.SECONDS); // timeout guard
        pool.shutdown();

        // Assert
        t.assertTrue("all threads completed within timeout", completedInTime,
                "possible deadlock or starvation");
        t.assertEquals("no lost updates under concurrent increments",
                threadCount * incrementsPerThread, counter.get());
    }

    // =========================================================================
    // MAIN — run every scenario through the tiny harness
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {
        TinyTestHarness harness = new TinyTestHarness();

        testRateLimiter(harness);
        testOrderStateMachine(harness);
        testCounterUnderConcurrency(harness);

        harness.summarize();
    }
}

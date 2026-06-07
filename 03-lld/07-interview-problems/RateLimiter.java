import java.util.*;
import java.util.concurrent.*;

/**
 * Rate Limiter — Phase 5, Problem 3
 *
 * Demonstrates: Strategy (three interchangeable algorithms), Factory (creates
 *               algorithm from config), Singleton (service), two-level locking
 *               (ConcurrentHashMap outer + synchronized inner), Value Object (config)
 *
 * Key architectural decision: locking lives ONLY in RateLimiterEntry.
 * Algorithm implementations are pure logic — no synchronized, no atomics.
 * This separates the "how to rate-limit" concern from the "how to make it safe" concern.
 */
public class RateLimiter {

    // =========================================================================
    // ENUMS + CONFIG
    // =========================================================================

    enum AlgorithmType {
        FIXED_WINDOW,
        SLIDING_WINDOW,
        TOKEN_BUCKET
    }

    /**
     * RateLimiterConfig: immutable value object holding all algorithm parameters.
     * A value object is safe to share across threads without defensive copying.
     *
     * Not all fields are used by every algorithm:
     * - Fixed/Sliding use: limit, windowMs
     * - Token Bucket uses: capacity, refillRatePerSecond
     * limit doubles as capacity for Token Bucket when capacity is not set explicitly.
     */
    static final class RateLimiterConfig {
        final AlgorithmType algorithmType;
        final int limit;                    // max requests per window, or bucket capacity
        final long windowMs;                // window size in milliseconds (Fixed + Sliding)
        final double refillRatePerSecond;   // tokens added per second (Token Bucket)

        RateLimiterConfig(AlgorithmType algorithmType, int limit, long windowMs,
                          double refillRatePerSecond) {
            this.algorithmType = algorithmType;
            this.limit = limit;
            this.windowMs = windowMs;
            this.refillRatePerSecond = refillRatePerSecond;
        }

        // Convenience factory methods for each algorithm type
        static RateLimiterConfig fixedWindow(int limit, long windowMs) {
            return new RateLimiterConfig(AlgorithmType.FIXED_WINDOW, limit, windowMs, 0);
        }

        static RateLimiterConfig slidingWindow(int limit, long windowMs) {
            return new RateLimiterConfig(AlgorithmType.SLIDING_WINDOW, limit, windowMs, 0);
        }

        static RateLimiterConfig tokenBucket(int capacity, double refillRatePerSecond) {
            return new RateLimiterConfig(AlgorithmType.TOKEN_BUCKET, capacity, 0, refillRatePerSecond);
        }

        @Override
        public String toString() {
            return switch (algorithmType) {
                case FIXED_WINDOW   -> "FixedWindow(limit=" + limit + ", windowMs=" + windowMs + ")";
                case SLIDING_WINDOW -> "SlidingWindow(limit=" + limit + ", windowMs=" + windowMs + ")";
                case TOKEN_BUCKET   -> "TokenBucket(capacity=" + limit + ", refill=" + refillRatePerSecond + "/s)";
            };
        }
    }

    // =========================================================================
    // STRATEGY: RateLimitAlgorithm interface + 3 implementations
    // =========================================================================

    /**
     * RateLimitAlgorithm: the Strategy interface.
     *
     * IMPORTANT: implementations are deliberately NOT thread-safe.
     * They contain pure rate-limiting logic only.
     * Thread safety is the responsibility of RateLimiterEntry (the caller).
     *
     * This design means:
     * 1. Algorithm authors can't accidentally forget to synchronize
     * 2. Adding a new algorithm requires zero knowledge of the concurrency model
     * 3. Algorithms are easily unit-testable without any threading setup
     */
    interface RateLimitAlgorithm {
        /**
         * Try to allow a request.
         * @return true if the request is within the rate limit; false if it should be rejected.
         *
         * NOT thread-safe — must be called from within a synchronized context.
         */
        boolean tryAcquire();

        /** Human-readable state for debugging and demo output. */
        String getState();
    }

    // -------------------------------------------------------------------------
    // Algorithm 1: Fixed Window
    // -------------------------------------------------------------------------

    /**
     * Fixed Window: divides time into equal-width buckets.
     * Maintains a counter and the start time of the current window.
     * When the window expires, counter resets to 0.
     *
     * O(1) time, O(1) memory — the cheapest algorithm.
     *
     * Vulnerability: clients can fire 2× their limit by straddling window boundaries.
     * Example (limit=3, 1s windows):
     *   t=0.9s: 3 requests → allowed (window 1: 3/3)
     *   t=1.0s: window resets
     *   t=1.1s: 3 more requests → allowed (window 2: 3/3)
     *   Net: 6 requests in 0.2 seconds = 2× the intended limit
     */
    static class FixedWindowAlgorithm implements RateLimitAlgorithm {
        private final int limit;
        private final long windowMs;
        private int counter;
        private long windowStart;

        FixedWindowAlgorithm(int limit, long windowMs) {
            this.limit = limit;
            this.windowMs = windowMs;
            this.counter = 0;
            this.windowStart = System.currentTimeMillis();
        }

        @Override
        public boolean tryAcquire() {
            long now = System.currentTimeMillis();

            // Check if the current window has expired
            if (now - windowStart >= windowMs) {
                // Reset: start a new window
                // Note: we jump to the current time rather than incrementing by windowMs
                // to avoid "catching up" windows during idle periods.
                windowStart = now;
                counter = 0;
            }

            if (counter < limit) {
                counter++;
                return true;
            }
            return false;
        }

        @Override
        public String getState() {
            long elapsed = System.currentTimeMillis() - windowStart;
            return String.format("FixedWindow[%d/%d, window_age=%dms]", counter, limit, elapsed);
        }
    }

    // -------------------------------------------------------------------------
    // Algorithm 2: Sliding Window
    // -------------------------------------------------------------------------

    /**
     * Sliding Window: tracks the exact timestamp of every recent request.
     * On each new request, evicts timestamps older than windowMs, then counts remaining.
     *
     * Fixes the boundary burst problem completely: the window always looks back
     * exactly windowMs milliseconds from "now", regardless of clock alignment.
     *
     * Memory cost: O(n) where n = requests in the window. Under high load with
     * a large window this can be significant. In practice, limit is often small
     * (e.g. 100 req/min) so the deque stays small.
     */
    static class SlidingWindowAlgorithm implements RateLimitAlgorithm {
        private final int limit;
        private final long windowMs;
        // ArrayDeque: O(1) add to tail, O(1) remove from head — perfect for a FIFO timestamp queue
        private final ArrayDeque<Long> timestamps;

        SlidingWindowAlgorithm(int limit, long windowMs) {
            this.limit = limit;
            this.windowMs = windowMs;
            this.timestamps = new ArrayDeque<>();
        }

        @Override
        public boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long windowStart = now - windowMs;

            // Evict timestamps older than the window
            // Since timestamps are added in order, we can stop at the first in-window timestamp
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() < limit) {
                timestamps.addLast(now);
                return true;
            }
            return false;
        }

        @Override
        public String getState() {
            return String.format("SlidingWindow[%d/%d in last %dms]",
                timestamps.size(), limit, windowMs);
        }
    }

    // -------------------------------------------------------------------------
    // Algorithm 3: Token Bucket
    // -------------------------------------------------------------------------

    /**
     * Token Bucket: each client has a bucket with capacity C tokens.
     * Tokens refill at rate R per second. Each request consumes 1 token.
     * If the bucket is empty, the request is denied.
     *
     * Why this is the real-world winner:
     * - A client who was idle accumulates tokens up to capacity → natural burst allowance
     * - Long-term average is enforced by the refill rate
     * - Stripe, GitHub, AWS API Gateway all use variants of Token Bucket
     *
     * Implementation note: we use "lazy refill" — tokens are not refilled on a
     * background timer. Instead, we compute how many tokens have accrued since the
     * last request when a new request arrives. This avoids a background thread entirely.
     *
     * Why nanoTime() instead of currentTimeMillis():
     * nanoTime() is monotonic — it never jumps backward. currentTimeMillis() can jump
     * backward on NTP clock corrections. A backward jump here would compute a negative
     * elapsed time, which when multiplied by refillRate gives negative new tokens,
     * which would steal tokens from the client. This is a real production bug class.
     */
    static class TokenBucketAlgorithm implements RateLimitAlgorithm {
        private final double capacity;
        private final double refillRatePerSecond;  // tokens per second
        private double tokens;
        private long lastRefillNs;  // System.nanoTime() of last refill calculation

        TokenBucketAlgorithm(double capacity, double refillRatePerSecond) {
            this.capacity = capacity;
            this.refillRatePerSecond = refillRatePerSecond;
            this.tokens = capacity;  // Start with a full bucket
            this.lastRefillNs = System.nanoTime();
        }

        @Override
        public boolean tryAcquire() {
            refillTokens();

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        /**
         * Compute tokens accrued since last request using wall-clock elapsed time.
         * Cap at capacity — tokens don't accumulate indefinitely.
         */
        private void refillTokens() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNs) / 1_000_000_000.0;
            double newTokens = elapsedSeconds * refillRatePerSecond;

            if (newTokens > 0) {
                tokens = Math.min(capacity, tokens + newTokens);
                lastRefillNs = now;
            }
        }

        @Override
        public String getState() {
            return String.format("TokenBucket[%.2f/%.0f tokens, refill=%.1f/s]",
                tokens, capacity, refillRatePerSecond);
        }
    }

    // =========================================================================
    // FACTORY: AlgorithmFactory
    // =========================================================================

    /**
     * AlgorithmFactory: constructs the right algorithm from a config.
     *
     * The service never calls `new TokenBucketAlgorithm(...)` directly.
     * This isolates construction logic and makes it easy to add new algorithm types:
     * just add a case here and a new implementation class.
     *
     * All methods are static — this is a pure utility factory, not an instance.
     */
    static class AlgorithmFactory {
        static RateLimitAlgorithm create(RateLimiterConfig config) {
            return switch (config.algorithmType) {
                case FIXED_WINDOW ->
                    new FixedWindowAlgorithm(config.limit, config.windowMs);
                case SLIDING_WINDOW ->
                    new SlidingWindowAlgorithm(config.limit, config.windowMs);
                case TOKEN_BUCKET ->
                    new TokenBucketAlgorithm(config.limit, config.refillRatePerSecond);
            };
        }
    }

    // =========================================================================
    // RATE LIMITER ENTRY — the concurrency boundary
    // =========================================================================

    /**
     * RateLimiterEntry: wraps one algorithm instance for one client.
     * This is where ALL thread safety lives.
     *
     * synchronized on tryAcquire():
     * The algorithm's tryAcquire() is a compound operation — it reads state,
     * decides, then writes state (counter++, token--, add timestamp). These
     * three steps must be atomic. synchronized on the entry makes them so.
     *
     * Why not AtomicInteger or AtomicLong?
     * Because the check-and-update is not a single atomic value operation:
     * - FixedWindow: read counter, compare to limit, check window expiry, reset or increment
     * - SlidingWindow: iterate + evict the deque, then count + add
     * - TokenBucket: compute elapsed time, update double token count, check and decrement
     * None of these can be expressed as a single CAS operation. synchronized is correct.
     */
    static class RateLimiterEntry {
        final String clientId;
        final RateLimiterConfig config;
        private final RateLimitAlgorithm algorithm;

        RateLimiterEntry(String clientId, RateLimiterConfig config) {
            this.clientId = clientId;
            this.config = config;
            this.algorithm = AlgorithmFactory.create(config);
        }

        /**
         * Try to allow a request for this client.
         * synchronized: makes the algorithm's read-decide-write atomic.
         */
        synchronized boolean tryAcquire() {
            return algorithm.tryAcquire();
        }

        synchronized String getState() {
            return algorithm.getState();
        }
    }

    // =========================================================================
    // RATE LIMITER SERVICE — Singleton
    // =========================================================================

    /**
     * RateLimiterService: the single entry point for all rate limiting decisions.
     *
     * Registry: ConcurrentHashMap<clientId, RateLimiterEntry>
     * This is the outer layer of our two-level locking:
     * - ConcurrentHashMap handles concurrent registration of new clients
     * - RateLimiterEntry.synchronized handles per-client request decisions
     *
     * Why Singleton? The rate limiter registry must be shared across all threads
     * handling incoming requests. Two instances would have separate registries,
     * meaning client limits wouldn't be enforced across threads.
     *
     * Default config: any client not explicitly registered gets the default limit.
     * This prevents a misconfigured client from bypassing limits entirely.
     */
    static class RateLimiterService {
        private static volatile RateLimiterService instance;

        // Two-level structure: outer = concurrent access, inner = per-entry lock
        private final ConcurrentHashMap<String, RateLimiterEntry> registry;
        private final RateLimiterConfig defaultConfig;

        private RateLimiterService(RateLimiterConfig defaultConfig) {
            this.registry = new ConcurrentHashMap<>();
            this.defaultConfig = defaultConfig;
        }

        // Double-checked locking (volatile + synchronized) — from Phase 4
        static RateLimiterService getInstance() {
            if (instance == null) {
                synchronized (RateLimiterService.class) {
                    if (instance == null) {
                        // Default: token bucket, 10 req/s, capacity 10
                        instance = new RateLimiterService(
                            RateLimiterConfig.tokenBucket(10, 10.0)
                        );
                    }
                }
            }
            return instance;
        }

        /**
         * Explicitly register a client with a specific config.
         * Idempotent: registering the same client twice is a no-op
         * (computeIfAbsent only creates if absent).
         */
        void registerClient(String clientId, RateLimiterConfig config) {
            registry.computeIfAbsent(clientId, id -> new RateLimiterEntry(id, config));
            System.out.printf("[Registry] Registered client '%s' with %s%n", clientId, config);
        }

        /**
         * Main decision point: is this request allowed?
         *
         * Flow:
         * 1. computeIfAbsent: if client not registered, create entry with default config
         *    This is atomic — no race between "is it registered?" and "register it"
         * 2. entry.tryAcquire(): synchronized per-client decision
         */
        boolean allowRequest(String clientId) {
            // computeIfAbsent is atomic at the ConcurrentHashMap level:
            // at most one thread will call the lambda for a given clientId
            RateLimiterEntry entry = registry.computeIfAbsent(
                clientId,
                id -> new RateLimiterEntry(id, defaultConfig)
            );
            return entry.tryAcquire();
        }

        void printClientState(String clientId) {
            RateLimiterEntry entry = registry.get(clientId);
            if (entry != null) {
                System.out.printf("  State [%s]: %s%n", clientId, entry.getState());
            }
        }
    }

    // =========================================================================
    // DEMO DRIVER
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Rate Limiter Demo ===\n");

        RateLimiterService service = RateLimiterService.getInstance();

        // -----------------------------------------------------------------------
        // Scenario 1: Fixed Window — basic limiting
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 1: Fixed Window (limit=3, window=1000ms) ===");
        service.registerClient("client-fw", RateLimiterConfig.fixedWindow(3, 1000));

        for (int i = 1; i <= 5; i++) {
            boolean allowed = service.allowRequest("client-fw");
            System.out.printf("  Request %d: %s%n", i, allowed ? "ALLOWED" : "BLOCKED");
        }
        service.printClientState("client-fw");

        System.out.println("  [waiting 1100ms for window reset...]");
        Thread.sleep(1100);

        for (int i = 6; i <= 8; i++) {
            boolean allowed = service.allowRequest("client-fw");
            System.out.printf("  Request %d (new window): %s%n", i, allowed ? "ALLOWED" : "BLOCKED");
        }
        service.printClientState("client-fw");
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 2: Sliding Window — no boundary burst
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 2: Sliding Window (limit=3, window=1000ms) ===");
        service.registerClient("client-sw", RateLimiterConfig.slidingWindow(3, 1000));

        for (int i = 1; i <= 5; i++) {
            boolean allowed = service.allowRequest("client-sw");
            System.out.printf("  Request %d: %s%n", i, allowed ? "ALLOWED" : "BLOCKED");
        }
        service.printClientState("client-sw");

        System.out.println("  [waiting 600ms — partial window slide...]");
        Thread.sleep(600);

        // Some of the original requests have aged out of the window now
        for (int i = 6; i <= 9; i++) {
            boolean allowed = service.allowRequest("client-sw");
            System.out.printf("  Request %d (after 600ms): %s%n", i, allowed ? "ALLOWED" : "BLOCKED");
        }
        service.printClientState("client-sw");
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 3: Token Bucket — burst tolerance
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 3: Token Bucket (capacity=5, refill=2/s) ===");
        service.registerClient("client-tb", RateLimiterConfig.tokenBucket(5, 2.0));

        System.out.println("  Burst: 7 rapid requests (bucket starts full at 5 tokens)");
        for (int i = 1; i <= 7; i++) {
            boolean allowed = service.allowRequest("client-tb");
            System.out.printf("  Request %d: %s%n", i, allowed ? "ALLOWED" : "BLOCKED");
        }
        service.printClientState("client-tb");

        System.out.println("  [waiting 2000ms — refill 4 tokens at 2/s...]");
        Thread.sleep(2000);

        System.out.println("  After 2s wait:");
        for (int i = 8; i <= 12; i++) {
            boolean allowed = service.allowRequest("client-tb");
            System.out.printf("  Request %d: %s%n", i, allowed ? "ALLOWED" : "BLOCKED");
        }
        service.printClientState("client-tb");
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 4: Unregistered client — gets default config
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 4: Unregistered client gets default config ===");
        boolean allowed = service.allowRequest("unknown-client");
        System.out.println("  Unknown client first request: " + (allowed ? "ALLOWED" : "BLOCKED"));
        service.printClientState("unknown-client");
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 5: Concurrent requests — thread safety verification
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 5: Concurrency — 20 threads, 1 request each (limit=10) ===");
        service.registerClient("client-concurrent",
            RateLimiterConfig.tokenBucket(10, 1.0));

        int threadCount = 20;
        int[] results = new int[]{0, 0}; // [allowed, blocked]
        Thread[] threads = new Thread[threadCount];
        Object lock = new Object();

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                boolean result = service.allowRequest("client-concurrent");
                synchronized (lock) {
                    if (result) results[0]++; else results[1]++;
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.printf("  Allowed: %d, Blocked: %d (expected: 10 allowed, 10 blocked)%n",
            results[0], results[1]);
        System.out.println("  Thread safety: " +
            (results[0] == 10 && results[1] == 10 ? "VERIFIED" : "CHECK FAILED"));
        service.printClientState("client-concurrent");

        System.out.println("\n=== Demo complete ===");
    }
}

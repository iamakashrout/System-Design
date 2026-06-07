# Rate Limiter — LLD Notes

## 1. Problem Intuition

Rate limiting is one of the most practically important systems in backend engineering. Every production API — Stripe, GitHub, AWS, Twilio — uses it. The goal is to enforce a maximum request rate per client, protecting backend services from overload, abuse, and denial-of-service attacks.

What makes it an interesting design problem is the three-way tension between:
- **Accuracy**: Does the limit actually hold, even under boundary conditions?
- **Memory**: How much state do we store per client?
- **Burst tolerance**: Can legitimate bursty clients (a user who was idle for 30 seconds) send a short burst, or is every request drip-fed?

No algorithm wins on all three axes simultaneously. The right choice is a trade-off that depends on the use case.

### The three algorithms

**Fixed Window**: Divide time into equal-width windows (e.g. 1-second buckets). Count requests per window. Reset on window expiry. Simple, cheap, but vulnerable to a "boundary burst" — a client can fire 2× their limit by sending half their quota just before a window resets and the other half just after.

**Sliding Window**: Instead of fixed buckets, track the exact timestamp of every recent request. Count how many fall within the last N milliseconds. Precise — no boundary burst possible. Cost: a `Deque<Long>` of timestamps per client, which consumes more memory under high request rates.

**Token Bucket**: Each client has a bucket with a capacity. Tokens refill at a steady rate. Each request consumes one token. If the bucket is empty, the request is denied. This is what most real APIs use — it naturally tolerates bursts (a full bucket) while enforcing a long-term average rate (the refill rate). Stripe's rate limiter is token bucket. AWS API Gateway uses it.

---

## 2. Requirements

### Functional (in scope)
- Support three algorithms: Fixed Window, Sliding Window, Token Bucket
- Per-client limiting: each client ID has its own independent rate limit
- Runtime-configurable: algorithm type and parameters specified at registration
- Default config: any unregistered client gets a default limit
- Thread-safe: correct under high concurrent request load
- Extensible: new algorithms can be added without touching existing code

### Out of scope
- Distributed rate limiting (Redis-backed cross-node coordination)
- Rate limit headers in HTTP responses (X-RateLimit-Remaining etc.)
- Dynamic limit adjustment at runtime
- Persistent storage of request history

---

## 3. Identifying Entities

| Entity | Responsibility |
|---|---|
| `RateLimiterService` | Singleton; main entry point; maintains client registry |
| `RateLimiterEntry` | Wraps one algorithm instance for one client; owns the lock |
| `RateLimitAlgorithm` | Interface: `tryAcquire()` → boolean; pure logic, no concurrency |
| `FixedWindowAlgorithm` | Counter + window reset time; cheapest memory |
| `SlidingWindowAlgorithm` | Timestamp deque; most accurate |
| `TokenBucketAlgorithm` | Token count + last refill time; burst-tolerant |
| `AlgorithmFactory` | Creates algorithm instances from `AlgorithmType` + `RateLimiterConfig` |
| `RateLimiterConfig` | Value object: limit, windowMs, capacity, refillRatePerSecond |
| `AlgorithmType` | Enum: `FIXED_WINDOW`, `SLIDING_WINDOW`, `TOKEN_BUCKET` |

---

## 4. Design Decisions

### Where does locking live?

This is the most important architectural question. Two options:

**Option A — Lock inside each algorithm**: `synchronized` inside `FixedWindowAlgorithm.tryAcquire()`, etc. Problem: every new algorithm author must remember to add correct synchronization. Easy to forget; hard to audit.

**Option B — Lock in `RateLimiterEntry`**: algorithms are pure logic (no sync). `RateLimiterEntry.tryAcquire()` is `synchronized`. All concurrency concern is isolated to one class.

We use Option B. Separation of concerns: the algorithm knows *how* to rate-limit; the entry knows *how to make it safe*. Adding a new algorithm requires zero knowledge of the concurrency model.

### Two-level locking (same insight as Phase 4 RateLimiter)
- Outer: `ConcurrentHashMap<String, RateLimiterEntry>` — handles concurrent registration of new clients safely via `computeIfAbsent`
- Inner: `synchronized` on `RateLimiterEntry` — handles the compound check-and-update within a single client's state

Without `computeIfAbsent`, two threads registering the same new client concurrently could both call `new RateLimiterEntry(...)` and one entry would be silently lost.

### Strategy pattern for algorithms
`RateLimitAlgorithm` is the Strategy interface. `AlgorithmFactory` constructs the right implementation. `RateLimiterService` depends only on the interface — it can never accidentally hard-code algorithm-specific behavior.

### Why `System.nanoTime()` in TokenBucket
`nanoTime()` is monotonic — it never jumps backward. `currentTimeMillis()` can jump on NTP clock adjustments. A backward jump in a token bucket refill calculation would create a negative time delta, accidentally granting extra tokens. This is a real production bug class.

### `RateLimiterConfig` as a value object
All algorithm parameters live in one place. The factory reads from it. This makes configuration injectable, testable, and serializable without coupling the factory to individual algorithm constructors.

---

## 5. Class Diagram (text UML)

```
RateLimiterService (Singleton)
├── registry: ConcurrentHashMap<String, RateLimiterEntry>
├── defaultConfig: RateLimiterConfig
├── registerClient(clientId, config)
└── allowRequest(clientId) → boolean

RateLimiterEntry
├── clientId: String
├── algorithm: RateLimitAlgorithm
└── synchronized tryAcquire() → boolean   ← ALL locking lives here

RateLimitAlgorithm (interface)
├── tryAcquire() → boolean                ← pure logic, no sync
└── implementations:
    ├── FixedWindowAlgorithm   (counter + windowStartMs)
    ├── SlidingWindowAlgorithm (timestamps: ArrayDeque<Long>)
    └── TokenBucketAlgorithm   (tokens: double, lastRefillNs: long)

AlgorithmFactory
└── create(AlgorithmType, RateLimiterConfig) → RateLimitAlgorithm

RateLimiterConfig (value object)
├── limit: int              (max requests per window / bucket capacity)
├── windowMs: long          (window size in millis, for Fixed + Sliding)
├── capacity: int           (bucket max tokens, for Token Bucket)
└── refillRatePerSecond: double  (tokens added per second, for Token Bucket)

AlgorithmType (enum): FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET
```

---

## 6. Algorithm Comparison

| Property | Fixed Window | Sliding Window | Token Bucket |
|---|---|---|---|
| Memory per client | O(1) — just a counter | O(n) — stores timestamps | O(1) — just token count |
| Boundary burst | Yes — 2× limit possible | No | Bounded by capacity |
| Burst tolerance | No | No | Yes — up to capacity |
| Clock sensitivity | Window reset only | Timestamp precision | Monotonic clock needed |
| Real-world use | Simple internal APIs | Strict SLAs | Most public APIs (Stripe, AWS) |

---

## 7. Pattern Usage Summary

| Pattern | Where applied | Why |
|---|---|---|
| **Strategy** | `RateLimitAlgorithm` + three implementations | Algorithms are interchangeable; logic isolated from infrastructure |
| **Factory** | `AlgorithmFactory` | Creates algorithm from config; decouples construction from use |
| **Singleton** | `RateLimiterService` | One registry per service; shared state for all request threads |
| **Value Object** | `RateLimiterConfig` | Immutable config; safe to share across threads without copying |

---

## 8. Concurrency Summary

| Resource | Lock type | Scope |
|---|---|---|
| Client registration | `ConcurrentHashMap.computeIfAbsent` | Atomic create-if-absent for new clients |
| Per-client algorithm state | `synchronized` on `RateLimiterEntry` | Protects compound check-and-update |
| Algorithm internals | None — pure logic | All safety delegated to `RateLimiterEntry` |

---

## 9. Possible Extensions

| Extension | Where to change |
|---|---|
| Distributed rate limiting | Replace `RateLimiterEntry` with a Redis-backed variant via same interface |
| Leaky Bucket algorithm | New `LeakyBucketAlgorithm` implements `RateLimitAlgorithm`; add to factory |
| Per-endpoint limits | Add `resourceId` dimension to registry key: `clientId + ":" + endpoint` |
| Dynamic limit update | Add `updateConfig(clientId, config)` to `RateLimiterService` |
| Rate limit headers | Extend `tryAcquire()` to return a `RateLimitResult` with remaining count |
| Burst allowance tier | Add a `burstMultiplier` field to `RateLimiterConfig` for premium clients |

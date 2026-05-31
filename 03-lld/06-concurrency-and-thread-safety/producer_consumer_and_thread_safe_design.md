# Producer-Consumer Pattern & Thread-Safe Class Design

## Topics Covered
- Topic 10 — Producer-Consumer Pattern
- Topic 11 — Thread-Safe Class Design (Rate Limiter)

---

## Topic 10 — Producer-Consumer Pattern

### The Core Idea

The producer-consumer pattern decouples **work generation** from **work processing**.
- **Producers** create tasks and place them into a shared queue
- **Consumers** pull tasks from the queue and process them
- The **BlockingQueue** in between acts as the buffer and coordinator

Neither side needs to know about the other's speed, count, or implementation.

### The Restaurant Analogy

| Role | Analogy | Code Component |
|---|---|---|
| Producer | Waiter posting order tickets | `PaymentProducer` |
| Queue | Ticket rail in the kitchen | `LinkedBlockingQueue` |
| Consumer | Chef pulling and cooking tickets | `PaymentConsumer` |

If chefs are slow → tickets pile up (queue fills, producers block via `put()`)
If chefs are fast → they wait for the next ticket (`take()` / `poll()` blocks)

### Why This Pattern Matters

| Approach | Problem |
|---|---|
| Tight coupling (producer calls consumer directly) | Producer blocks until consumer finishes — speeds are locked in step |
| Unbounded thread-per-task | Traffic spike → thousands of threads → OOM crash |
| **BlockingQueue** | Bounded memory, independent speeds, built-in back-pressure |

---

### The Three Components

#### 1. The Work Item — Make It Immutable

```java
public class PaymentTask {
    private final String taskId;      // final = immutable = no synchronization needed
    private final String customerId;
    private final double amount;
}
```

**Why immutable?** An object that cannot change can never have a race condition. Any thread can read any field at any time with zero locking overhead.

---

#### 2. The Producer

```java
queue.put(task); // blocks if queue is FULL — natural back-pressure
```

- `put()` is the back-pressure mechanism. If the queue is full, the producer thread parks and releases the CPU — no busy-waiting, no spin loop.
- When a consumer takes an item and opens a slot, one waiting producer is woken automatically.
- This is a self-regulating system: slow consumers automatically slow producers. No explicit rate limiter needed.

---

#### 3. The Consumer

```java
while (running || !queue.isEmpty()) {
    PaymentTask task = queue.poll(100, TimeUnit.MILLISECONDS);
    if (task == null) continue;
    processPayment(task);
}
```

Three key decisions:

**`poll(timeout)` over `take()`**
- `take()` blocks indefinitely — if producers stop forever, consumers park forever and never exit
- `poll(100ms)` returns `null` if nothing arrives, letting the consumer re-check the `running` flag
- This is the standard pattern for graceful shutdown

**`volatile boolean running`**
- Single writer (main thread calls `stop()`), multiple readers (each consumer thread)
- No compound operation — just a flag flip that needs immediate visibility
- Canonical correct use of `volatile`

**`while (running || !queue.isEmpty())`**
- `running = false` means "drain then stop" — NOT "stop immediately"
- The `|| !queue.isEmpty()` keeps consumers alive until every already-enqueued task is processed
- Without this, signalling stop with tasks still in the queue causes **silent data loss**

---

### The Shutdown Sequence (Order Matters)

```
1. Main waits for all producer futures      → all tasks are now in the queue
2. Main signals consumers to stop           → running = false
3. Consumers see running=false but drain    → while (running || !queue.isEmpty())
4. Main awaits pool termination             → blocks until all consumer threads exit
```

**If you signal stop before producers finish** → tasks never enqueued → data loss  
**If consumers check only `running`** → tasks in queue silently abandoned

---

### Poison Pill — Alternative Shutdown Pattern

```java
static final PaymentTask POISON = new PaymentTask("__STOP__", null, 0);

// Producer signals done:
queue.put(POISON);

// Consumer detects:
PaymentTask task = queue.take();
if (task == POISON) {
    queue.put(POISON); // re-publish for other consumers
    return;
}
```

Cleaner when using `take()` — the shutdown signal travels through the queue in submission order, so consumers see it only after processing everything before it.

---

### Choosing the Right BlockingQueue

| Queue | Capacity | Lock | Use When |
|---|---|---|---|
| `LinkedBlockingQueue` | Bounded or unbounded | Separate head/tail locks | Default choice — high throughput |
| `ArrayBlockingQueue` | Strictly bounded | Single lock | Fixed memory footprint required |
| `PriorityBlockingQueue` | Unbounded | Single lock | `take()` returns highest-priority item |

---

### Producer-Consumer Ratio

```
CPU-bound consumers:
  consumers = Runtime.getRuntime().availableProcessors()

I/O-bound consumers (DB writes, HTTP calls):
  consumers >> cores  (threads sleep during I/O, so many can coexist)
  e.g. 20–50 consumers on 4 cores is reasonable

Producers:
  Usually few — they generate work fast (in memory)
  The bottleneck is almost always on the consumer side
```

---

### Common Variations

**Priority queue**: Swap `LinkedBlockingQueue` for `PriorityBlockingQueue` — `take()` always returns highest-priority item. No other changes needed.

**Fan-out routing**: Route to specialized queues based on task properties:
```java
if (task.getAmount() > 100_000) largePaymentQueue.put(task);
else                             standardPaymentQueue.put(task);
```

**Multiple producers, single consumer**: Queue serializes all work — effectively a thread-safe inbox.

---

## Topic 11 — Thread-Safe Class Design (Rate Limiter)

### Why a Rate Limiter Is the Perfect Capstone

A rate limiter requires every concept from Phase 4 at once:
- Concurrent data structure (ConcurrentHashMap) for multiple clients
- Atomic compound operations (computeIfAbsent)
- Per-resource locking (each client has its own lock)
- Time-window state management
- It's also a real LLD interview problem

---

### The Problem Decomposition

A rate limiter answers one question: **"Does this client have quota remaining in the current window?"**

Required state per client:
1. How many requests made in the current window
2. When the current window started
3. The limits (max requests, window duration — shared, immutable)

Required concurrent guarantees:
1. **Client initialization** — first time a client is seen, create their window; two threads racing on the same new client must not create two windows
2. **Request consumption** — check + maybe reset + increment must be atomic

---

### The Two-Level Concurrency Design

```
Level 1: ConcurrentHashMap   → client isolation  (no cross-client contention)
Level 2: synchronized on ClientWindow → per-client atomicity
```

These two levels are completely independent. Client-A's requests never block client-B's requests — they operate on different `ClientWindow` instances and different locks. Only same-client concurrent requests contend with each other, which is the minimum necessary contention.

**Naive approach (wrong):** One `synchronized` on the whole `RateLimiter` — 1000 concurrent clients all queue for one lock. Throughput collapses.

**This design:** N clients = N independent locks. Throughput scales linearly with client count.

---

### Level 1: `computeIfAbsent` — Atomic Client Initialization

```java
ClientWindow window = clientWindows.computeIfAbsent(
    clientId, id -> new ClientWindow(maxRequests, windowMs)
);
```

`computeIfAbsent` is one atomic operation:
> "If the key is absent, run the factory and insert. Return the value — new or existing."

**Why not `containsKey` + `put`?**

```java
// BROKEN
if (!clientWindows.containsKey(clientId)) {   // Thread A: absent
                                               // Thread B: also sees absent
    clientWindows.put(clientId, new ClientWindow(...)); // both insert
}
// Client A's first window is overwritten → quota state lost
```

Two threads racing on the same new client via `computeIfAbsent` will have exactly one create the window — the other gets back the same instance. The factory lambda runs **at most once per key**.

**Important constraint:** The factory lambda runs while a bucket lock is held internally. It must be:
- Fast (no heavy I/O)
- Non-blocking
- Must not re-access the same ConcurrentHashMap (deadlock risk)

---

### Level 2: `synchronized tryConsume` — Atomic Window Check-and-Increment

```java
synchronized boolean tryConsume() {
    long now = System.currentTimeMillis();
    if (now - windowStart >= windowMs) {
        requestCount = 0;      // reset
        windowStart  = now;    // new window starts now
    }
    if (requestCount < maxRequests) {
        requestCount++;
        return true;
    }
    return false;
}
```

**Why not AtomicInteger here?**

```java
// BROKEN — can't atomically reset TWO related fields with atomics
requestCount.set(0);   // Thread A: resets
windowStart = now;     // Thread B: also resets between these two lines
                       // Now two threads both reset, window drift occurs
```

`requestCount` and `windowStart` must change **together as one atomic unit** — this is a multi-variable update. `synchronized` is exactly right. `AtomicInteger` protects a single variable; a lock protects a whole critical section.

The `synchronized` here locks on `this` — the specific `ClientWindow` instance. Since client-A has their own window object, client-A and client-B's requests lock on different objects and never block each other.

---

### Thread-Safety Design Principles Illustrated

| Principle | How it appears in RateLimiter |
|---|---|
| **Immutable shared state** | `maxRequests`, `windowMs` are `final` — any thread reads them freely |
| **Lock only what you must** | No lock on `allowRequest()` or map lookup |
| **Minimize lock scope** | `synchronized` only on the inner class, not the outer |
| **No shared mutable state across clients** | Each `ClientWindow` owned by exactly one key |
| **Atomic compound operations** | `computeIfAbsent` for init, `synchronized` block for check+increment |

---

### General Thread-Safe Class Design Checklist

When designing a class to be used across threads, ask these questions in order:

**1. What state does this class hold?**
- List every field. Label each: immutable, effectively immutable, or mutable.

**2. Which state is accessed by multiple threads?**
- If only one thread ever touches a field, no synchronization needed.

**3. For shared mutable state: what operations touch it?**
- Single read or write → `volatile` may suffice
- Compound (check-then-act, read-modify-write) → need a lock or `Atomic*`
- Multiple related fields updated together → need a lock covering all of them

**4. How can you minimize contention?**
- Can you partition state so independent operations use different locks? (Lock striping)
- Can you use `ConcurrentHashMap` instead of a locked `HashMap`?
- Can you make work items immutable so they need no synchronization?

**5. What is the invariant?**
- Write it down: "requestCount <= maxRequests within any windowMs period"
- Every synchronized block should preserve this invariant when it exits

---

### The Lock Striping Pattern

This design is a textbook example of **lock striping**:

```
One big lock (bad):               Lock striping (this design):

  [ RateLimiter lock ]              clientA → [ ClientWindow lock A ]
       |                            clientB → [ ClientWindow lock B ]
  [A][B][C][D][E]...                clientC → [ ClientWindow lock C ]
  all clients queue here            each client independent
```

Lock striping = partition your state into N independent pieces, each with its own lock. N independent workloads → N locks → no cross-workload contention. This is how `ConcurrentHashMap` itself works internally (per-bucket locking).

---

### Fixed Window vs Sliding Window

The implementation uses a **fixed window**:
- At T=0, a new window starts. Allows 5 requests until T=1000ms.
- At T=1001ms, a new window starts fresh.

**Weakness:** A burst at T=900ms (5 requests) + T=1001ms (5 more requests) = 10 requests in 100ms — double the intended rate.

**Sliding window** (more accurate but more complex): Track individual request timestamps in a deque. Count only timestamps within the last `windowMs`. More memory, more computation, but no burst vulnerability.

For interviews: mention this trade-off. Implement fixed window first, then discuss sliding window as an improvement.

---

## Quick Reference

### When to Use What

| Scenario | Tool |
|---|---|
| Single boolean flag, one writer | `volatile` |
| Single counter, multiple writers | `AtomicInteger` |
| Multiple related fields updated together | `synchronized` block |
| Key-value store with concurrent access | `ConcurrentHashMap` |
| Atomic initialization of a value | `computeIfAbsent` |
| Bounded work queue with blocking | `LinkedBlockingQueue` |
| Work items passed between threads | Immutable objects (all fields `final`) |
| Independent resources in one class | Separate locks per resource (lock striping) |

### Producer-Consumer Shutdown Pattern (Summary)

```java
// 1. Wait for all producers to finish
for (Future<?> f : producerFutures) f.get();

// 2. Signal consumers to drain and stop
consumers.forEach(c -> c.stop()); // sets volatile running = false

// 3. Await pool termination
pool.shutdown();
pool.awaitTermination(10, TimeUnit.SECONDS);
```

### Rate Limiter Design Pattern (Summary)

```
allowRequest(clientId):
  1. computeIfAbsent → get or create ClientWindow (atomic, lock-free per bucket)
  2. window.tryConsume() → synchronized check+reset+increment (per-client lock)

Result: O(1) per request, zero cross-client contention
```

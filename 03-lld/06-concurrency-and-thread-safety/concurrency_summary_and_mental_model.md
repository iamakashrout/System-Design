# Java Concurrency — Summary & Mental Model

**Phase 4 complete reference. All 11 topics consolidated.**

---

## The core question

Every concurrency decision starts with one question asked about every shared field:

> **What kind of operation is being done on this variable, and by how many threads?**

The answer determines the tool. Not the other way around.

---

## The full mental model

| Problem | Tool | Why |
|---|---|---|
| Single-field visibility | `volatile` | Forces main memory read/write — bypasses CPU cache |
| Compound operations | `synchronized` | Atomic block + mutual exclusion + visibility |
| Simple counters/flags | `AtomicInteger` | CAS hardware instruction — no lock overhead |
| Complex lock control | `ReentrantLock` | Timeout, fairness, interruptible waiting |
| Concurrent key-value | `ConcurrentHashMap` | Bucket-level locking, lock-free reads, atomic ops |
| Rarely-written lists | `CopyOnWriteArrayList` | Lock-free reads, snapshot iteration |
| Work queue | `BlockingQueue` | Blocking put/take, back-pressure, producer-consumer |
| Thread reuse | `ExecutorService` | Pool management, `Future` results, bounded concurrency |
| Deadlock prevention | Consistent lock ordering | Eliminates circular wait — the 4th deadlock condition |
| Producer-consumer | `BlockingQueue` | Decouples producers from consumers naturally |
| Thread-safe design | Minimize shared mutable state | Synchronize at finest granularity needed |

---

## Topic-by-topic distillation

### Topic 1 — Race conditions and visibility

A **race condition** is a timing bug: correctness breaks when threads interleave during a multi-step operation. The root cause is that `x++` is not one instruction — it is read, modify, write.

A **visibility bug** is a caching bug: a thread reads a stale value from its CPU cache and never sees another thread's write.

```
Race condition  → needs atomicity     → synchronized / AtomicInteger
Visibility bug  → needs freshness     → volatile
```

`volatile` does NOT fix race conditions. It guarantees freshness (reads from main memory) but not atomicity (can't prevent interleaving across multiple steps).

---

### Topic 2 — `synchronized`

Provides two guarantees simultaneously:
1. **Mutual exclusion** — only one thread executes the block at a time
2. **Visibility** — all writes before unlock are visible to the next thread that acquires the same lock

Method-level `synchronized` uses `this` as the lock. Block-level `synchronized(obj)` lets you use separate locks for independent resources — reducing contention between unrelated operations.

```java
// Fine-grained: two independent resources, two independent locks
synchronized (inventoryLock) { inventory -= qty; }
synchronized (auditLock)     { auditLog.add(entry); }
// Threads waiting on audit don't block threads waiting on inventory
```

**Rule:** Every access to a shared mutable variable — reads AND writes — must be guarded by the same lock. Half-synchronized code is as broken as unsynchronized code.

---

### Topic 3 — `volatile` vs `synchronized`

| | `volatile` | `synchronized` |
|---|---|---|
| Visibility | Yes | Yes |
| Atomicity | No | Yes (whole block) |
| Use for | Single reads/writes, flags | Compound ops, related fields |
| Performance | Faster (no lock) | Slower (mutex overhead) |

`volatile` for: one writer, many readers, no compound operation needed. Shutdown flags, config toggles, status indicators.

`synchronized` for: any read-modify-write, check-then-act, or multi-field invariant.

---

### Topic 4 — `ReentrantLock`

Same visibility and mutual exclusion as `synchronized`, plus:

| Feature | Method | Real-world use |
|---|---|---|
| Bounded wait | `tryLock(500ms)` | Payment timeouts, distributed locks |
| Cancellable wait | `lockInterruptibly()` | Graceful shutdown, task cancellation |
| Observability | `isLocked()`, `getQueueLength()` | Monitoring dashboards |
| Fairness | `new ReentrantLock(true)` | Rate limiters, ticket systems |

**Non-negotiable pattern:**
```java
lock.lock();
try {
    // critical section
} finally {
    lock.unlock(); // runs even if exception is thrown
}
```

Forgetting `unlock()` in `finally` is a silent deadlock. Use `synchronized` as the default; reach for `ReentrantLock` only when you need one of the four features above.

---

### Topic 5 — `AtomicInteger` and CAS

**Compare-And-Swap (CAS):** a single hardware instruction.
```
CAS(address, expected, new):
  if *address == expected → write new, return true
  else                    → do nothing, return false
```

The CPU bus locks for this one instruction — no software mutex, no thread parking. This is why atomics are faster than `synchronized` under low-to-moderate contention.

```java
AtomicInteger counter = new AtomicInteger(0);
counter.incrementAndGet();          // atomic read-add-write
counter.compareAndSet(5, 10);       // conditional swap — the raw CAS
```

**`LongAdder` over `AtomicLong` for high-throughput counters:** stripes updates across per-thread cells, merges at read time. Near-zero CAS collisions under heavy load.

**The boundary:** atomics protect ONE variable. The moment two related fields must change together (`requestCount` and `windowStart`), you need a lock — not two atomics.

---

### Topic 6 — Thread pools and `ExecutorService`

Thread creation: ~1MB stack + 50–100µs per thread. Thread pools pay this once at startup.

| Pool type | Behavior | Use when |
|---|---|---|
| `newFixedThreadPool(n)` | Exactly n threads, unbounded queue | Predictable load, known parallelism |
| `newCachedThreadPool()` | Grows on demand, dies after 60s idle | Bursty short tasks only |
| `newSingleThreadExecutor()` | 1 thread, FIFO queue | Sequential execution required |
| `newScheduledThreadPool(n)` | Delay / periodic tasks | Cron-style jobs |

**Sizing:**
- CPU-bound: `n = availableProcessors()`
- I/O-bound: `n = 2× to many × availableProcessors()` (threads sleep during I/O)

**Two-phase shutdown — always:**
```java
executor.shutdown();                         // stop accepting new tasks
if (!executor.awaitTermination(30, SECONDS))
    executor.shutdownNow();                  // force-interrupt if too slow
```

`Future.get()` blocks until done. `future.get(5, SECONDS)` adds a timeout. `future.cancel(true)` interrupts the running thread.

---

### Topic 7 — Thread-safe collections

| Scenario | Use | Avoid |
|---|---|---|
| Concurrent map | `ConcurrentHashMap` | `Collections.synchronizedMap` |
| Read-heavy list | `CopyOnWriteArrayList` | `Collections.synchronizedList` |
| Producer-consumer | `LinkedBlockingQueue` | `ArrayList` + manual sync |
| Priority processing | `PriorityBlockingQueue` | — |

**`ConcurrentHashMap` key methods — all atomic:**
```java
map.merge(key, delta, Integer::sum);      // atomic read-modify-write
map.computeIfAbsent(key, k -> build(k));  // atomic put-if-absent
map.computeIfPresent(key, (k, v) -> ...); // atomic update-if-exists
```

These replace the classic broken pattern `get()` + `put()` which has a race between the two calls.

**`CopyOnWriteArrayList`:** O(n) write (copies the array), O(1) read (lock-free). Correct use: observer/listener lists registered once, iterated constantly. Wrong use: high-write queues.

**`BlockingQueue`:** `put()` blocks if full (back-pressure). `take()` blocks if empty (no busy-wait). `poll(timeout)` is the graceful-shutdown variant — returns null instead of blocking forever.

---

### Topic 8 — Deadlock

**Four necessary conditions** (deadlock requires ALL four):
1. Mutual exclusion — resources held exclusively
2. Hold-and-wait — holding one resource while waiting for another
3. No preemption — can't forcibly take a resource
4. Circular wait — a cycle of threads each waiting for the next

**Prevention Strategy 1 — Consistent lock ordering** (eliminates circular wait):
```java
// Always acquire in sorted order — never in role order
BankAccount first  = a.getId() < b.getId() ? a : b;
BankAccount second = a.getId() < b.getId() ? b : a;
synchronized (first)  {
synchronized (second) { /* transfer */ }}
```

**Prevention Strategy 2 — `tryLock` with backoff** (eliminates hold-and-wait):
```java
if (lock1.tryLock()) {
    try {
        if (lock2.tryLock()) {
            try { /* work */ } finally { lock2.unlock(); }
        }
    } finally { lock1.unlock(); }
}
Thread.sleep(1 + random); // backoff prevents livelock
```

**Detection:** `ThreadMXBean.findDeadlockedThreads()` — or read a thread dump (`jstack`).

---

### Topic 9 — Double-checked locking (DCL)

`new MyObject()` compiles to three steps: **allocate**, **initialize** (constructor), **assign** (write reference). The JVM can reorder steps 2 and 3. A reader can see a non-null reference pointing at a half-constructed object.

`volatile` inserts a `StoreStore` + `StoreLoad` barrier — the assign cannot move before the constructor finishes.

```java
private static volatile MyClass instance; // volatile is non-negotiable

public static MyClass getInstance() {
    if (instance == null) {                    // fast path — no lock
        synchronized (MyClass.class) {
            if (instance == null) {            // re-check under lock
                instance = new MyClass();      // volatile write → barrier → visible
            }
        }
    }
    return instance;
}
```

**Prefer these alternatives for singletons:**

```java
// Enum singleton — JVM class loading is thread-safe
public enum Singleton { INSTANCE; }

// Static inner class holder — lazy, thread-safe, zero boilerplate
private static class Holder {
    static final Singleton INSTANCE = new Singleton();
}
public static Singleton getInstance() { return Holder.INSTANCE; }
```

Use DCL with `volatile` for non-singleton lazy initialization where the holder pattern doesn't apply (e.g., instance-level cached values).

---

### Topic 10 — Producer-consumer pattern

```
[Producer threads] → put() → [BlockingQueue] → take()/poll() → [Consumer threads]
```

- `put()` blocks if full → natural back-pressure, no explicit rate limiter needed
- `take()` blocks if empty → no busy-waiting, CPU released while idle
- `poll(timeout)` → returns null after timeout, enabling graceful shutdown

**Consumer loop pattern:**
```java
while (running || !queue.isEmpty()) {    // drain before stopping
    Task t = queue.poll(100, MILLISECONDS);
    if (t == null) continue;             // re-check running flag
    process(t);
}
```

**Shutdown order:**
1. Wait for all producer futures (`future.get()`)
2. Signal consumers (`running = false`)
3. Consumers drain queue (`|| !queue.isEmpty()`)
4. Await pool termination

**Poison pill** (alternative): put a sentinel object in the queue. Consumer exits when it sees it, re-publishes for other consumers. Works cleanly with `take()` instead of `poll()`.

---

### Topic 11 — Thread-safe class design

The **Rate Limiter** as a capstone:

```
ConcurrentHashMap                    → client isolation (no cross-client lock)
  └── computeIfAbsent                → atomic one-time ClientWindow creation
        └── ClientWindow.tryConsume() → synchronized (per-client lock)
              ├── requestCount (int)  → mutable, under lock
              └── windowStart (long) → mutable, under lock, changes with requestCount
maxRequests, windowMs (final)        → immutable, no sync ever needed
```

**Why `synchronized` on `ClientWindow` and not `AtomicInteger`:**
`requestCount` and `windowStart` must change together as one atomic unit. Two `AtomicInteger`s cannot be updated jointly — a reader can see `requestCount = 0` and `windowStart = oldTime` simultaneously, which is a corrupt state. A lock covers both fields.

**Thread-safe class design checklist:**

1. **List every field.** Label as: immutable, effectively immutable, or mutable.
2. **For each mutable field:** which threads access it? Read only, or read-modify-write?
3. **Single mutable field, single read/write:** `volatile`
4. **Single mutable field, compound op:** `AtomicInteger`
5. **Multiple related mutable fields:** `synchronized` block covering all of them
6. **Independent resources:** separate locks (lock striping) — contention stays local
7. **State passed between threads:** make it immutable (all fields `final`)
8. **Write the invariant explicitly.** Every lock exit must preserve it.

---

## Decision guide — one-sentence version per tool

| Tool | One sentence |
|---|---|
| `volatile` | One field, one writer, you only need freshness — not atomicity. |
| `synchronized` | Multiple steps on shared state that must execute as one unit. |
| `AtomicInteger` | One variable, one compound op, no lock needed. |
| `ReentrantLock` | You need timeout, interruptibility, fairness, or lock observability. |
| `ConcurrentHashMap` | Concurrent key-value with high read throughput; use its atomic methods. |
| `CopyOnWriteArrayList` | Registered once, iterated many times; write cost is acceptable. |
| `BlockingQueue` | Producer-consumer decoupling with built-in back-pressure. |
| `ExecutorService` | Manage thread lifecycle; don't create threads manually per task. |
| Consistent lock order | Prevent deadlock by eliminating the circular wait condition. |
| Immutable work items | Objects passed between threads should be immutable — zero sync cost. |
| Lock striping | Independent resources get independent locks; contention never crosses. |

---

## The three hardest mistakes to spot

**1. Volatile where synchronized is needed**
```java
private volatile int count = 0;
public void increment() { count++; } // BROKEN — volatile doesn't fix atomicity
```
Looks correct, compiles clean, fails under concurrent load. `count++` is three CPU ops with a gap between them. `volatile` only guarantees freshness of each individual read/write — not that the three ops are uninterruptible.

**2. Two atomics that look jointly atomic**
```java
AtomicInteger balance = new AtomicInteger(1000);
AtomicInteger txCount = new AtomicInteger(0);
// BROKEN — a reader sees balance decremented but txCount not yet incremented
balance.addAndGet(-amount);
txCount.incrementAndGet();
```
Both operations are individually atomic. Together they are not. A synchronized block covering both is required.

**3. Unsynchronized read of a synchronized field**
```java
public synchronized void set(int v) { this.value = v; }
public int get() { return this.value; }  // NOT synchronized — stale reads possible
```
Writes are guarded, reads are not. The reader can see a stale cached value. Every access — read and write — must hold the same lock.

---

## Concurrency in one paragraph

Threads sharing mutable state need two guarantees: **visibility** (seeing the latest written value, not a stale cache) and **atomicity** (multi-step sequences executing without interruption). `volatile` provides visibility only — use it for simple flags where no compound operation exists. `synchronized` and `ReentrantLock` provide both, protecting a whole block as one unit; `ReentrantLock` adds timeout, fairness, and interruptibility for the cases where `synchronized`'s "wait forever" behavior is unacceptable. `AtomicInteger` provides CAS-based atomicity for single-variable operations without any lock overhead. For collections, use concurrent variants (`ConcurrentHashMap`, `CopyOnWriteArrayList`, `BlockingQueue`) instead of wrapping standard collections — they're designed for their specific access patterns. Deadlock is prevented by acquiring multiple locks in a consistent global order. Thread creation is expensive; `ExecutorService` pools amortize the cost and return `Future` handles to pending results. The design principle tying it all together: minimize shared mutable state, make work items immutable, and synchronize at the finest granularity that preserves correctness.

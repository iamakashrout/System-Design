# Concurrency — Thread Pools, `ExecutorService`, and Thread-Safe Collections

> **Phase context:** This is the third chapter of Phase 4 (Concurrency & Thread Safety). It covers the `ExecutorService` framework for managing thread lifecycles and the concurrent collection types that replace unsafe standard collections.

---

## Table of Contents

1. [Why Thread Pools Exist](#1-why-thread-pools-exist)
2. [ExecutorService — the Core API](#2-executorservice--the-core-api)
3. [Future — the Handle to a Pending Result](#3-future--the-handle-to-a-pending-result)
4. [The Four Pool Types](#4-the-four-pool-types)
5. [Shutting Down a Pool](#5-shutting-down-a-pool)
6. [Sizing the Pool](#6-sizing-the-pool)
7. [Why Standard Collections Break Under Concurrency](#7-why-standard-collections-break-under-concurrency)
8. [ConcurrentHashMap](#8-concurrenthashmap)
9. [CopyOnWriteArrayList](#9-copyonwritearraylist)
10. [BlockingQueue and the Producer-Consumer Pattern](#10-blockingqueue-and-the-producer-consumer-pattern)
11. [Collection Decision Guide](#11-collection-decision-guide)
12. [Common Anti-Patterns](#12-common-anti-patterns)
13. [Key Takeaways](#13-key-takeaways)

---

## 1. Why Thread Pools Exist

### The cost of raw thread creation

Every `new Thread(...).start()` asks the OS to:

1. Allocate a new native thread struct
2. Allocate a **~1MB stack** by default
3. Register the thread with the OS scheduler
4. Initialize thread-local storage
5. Undo all of this on thread death

Cost: **~50–100 microseconds** and **~1MB of memory** per thread. A server handling 10,000 requests/sec with one thread per request spends the majority of its CPU budget on bookkeeping.

### The warehouse analogy

**No pool (new thread per task):** Every order that arrives triggers hiring a new contractor, onboarding them, letting them work, then firing them. 500 concurrent orders = 500 onboarding sessions. The HR overhead crushes the actual work.

**Thread pool:** Hire exactly N permanent packers on day one. Orders go into a queue. Each packer picks up the next order when free. No hiring, no firing, no onboarding.

Thread pools pay the creation cost **once at startup** and amortize it across all tasks.

### The three components

Every `ExecutorService` has three parts:

| Component | What it is | What it does |
|---|---|---|
| **Thread set** | Fixed or dynamic group of worker threads | Loop: pick task → execute → pick next |
| **Work queue** | `BlockingQueue` of pending tasks | Buffers work when all threads are busy |
| **Rejection policy** | Configurable handler | Fires when queue is full and no threads free |

Default rejection policy: throw `RejectedExecutionException`. Alternatives: caller runs the task, silently discard, discard oldest queued task.

---

## 2. ExecutorService — the Core API

```java
ExecutorService executor = Executors.newFixedThreadPool(10);

// Submit a Runnable — no result, returns Future<?>
executor.submit(() -> System.out.println("task ran"));

// Submit a Callable — returns a result via Future<T>
Future<String> future = executor.submit(() -> {
    Thread.sleep(100);
    return "result";
});

// Execute — fire-and-forget, no Future returned
executor.execute(() -> System.out.println("fire and forget"));
```

`submit()` is preferred over `execute()` — it returns a `Future` that lets you check completion, retrieve results, and handle exceptions. With `execute()`, exceptions thrown by the task are silently swallowed by the thread's uncaught exception handler.

---

## 3. Future — the Handle to a Pending Result

`submit()` returns immediately. The task hasn't finished — `Future<T>` is a placeholder for the result that will exist eventually.

### The restaurant receipt analogy

You order → get a receipt (`Future`). You sit down and do other things. At any point you can check "is my order ready?" (`isDone()`). When hungry, block and wait (`get()`). If you leave the restaurant, cancel it (`cancel()`).

### Key methods

```java
Future<OrderResult> future = executor.submit(() -> processOrder(order));

// Blocks until result is ready — the most common use
OrderResult result = future.get();

// Blocks with a timeout — throws TimeoutException if not done in time
OrderResult result = future.get(5, TimeUnit.SECONDS);

// Non-blocking check
if (future.isDone()) {
    result = future.get(); // won't block
}

// Cancel — removes from queue if not started, interrupts if running
future.cancel(true); // true = interrupt the thread if already running

// Was it cancelled?
boolean cancelled = future.isCancelled();
```

### Exception handling

If the task throws an exception, `get()` wraps it in `ExecutionException`:

```java
try {
    result = future.get();
} catch (ExecutionException e) {
    Throwable cause = e.getCause(); // the original exception from the task
    log.error("Task failed: ", cause);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

---

## 4. The Four Pool Types

### `newFixedThreadPool(n)` — bounded concurrency

```java
ExecutorService executor = Executors.newFixedThreadPool(10);
```

- Exactly `n` threads — always alive, never more, never less
- Queue: unbounded `LinkedBlockingQueue`
- New tasks queue up if all threads are busy

**When to use:** Web servers, order processing, any scenario with known parallelism. Predictable memory and CPU.

**Risk:** If tasks are slow and keep arriving, the queue grows without bound → `OutOfMemoryError`.

**Sizing rule:**
- CPU-bound tasks: `n = Runtime.getRuntime().availableProcessors()` (or +1)
- I/O-bound tasks: `n = 2 × cores` or higher (threads spend most time waiting)

### `newCachedThreadPool()` — elastic, short-lived tasks

```java
ExecutorService executor = Executors.newCachedThreadPool();
```

- No upper bound on threads — creates new threads on demand
- Idle threads expire after 60 seconds
- Queue: `SynchronousQueue` (zero capacity — direct handoff)

**When to use:** Bursty, short-lived tasks — async callbacks, quick API calls, event handlers.

**Risk:** Under sustained load, creates thousands of threads. 5,000 threads = ~5GB of stack memory + OS scheduler thrashing. **Never use for tasks that block on slow I/O.**

### `newSingleThreadExecutor()` — sequential ordered execution

```java
ExecutorService executor = Executors.newSingleThreadExecutor();
```

- Exactly 1 thread
- All tasks execute one at a time in FIFO submission order
- If the thread dies (uncaught exception), a replacement is created automatically

**When to use:** Writing to a single file, driving a non-thread-safe legacy component, sequencing migrations. Turns async fan-in into a safe serial queue.

**Important distinction from `synchronized`:** `synchronized` makes concurrent threads wait at a barrier — there are still multiple threads. `SingleThreadExecutor` eliminates concurrency entirely — one thread only.

**Risk:** A slow task blocks all subsequent tasks indefinitely.

### `newScheduledThreadPool(n)` — delayed and recurring tasks

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

// Run once after a delay
scheduler.schedule(task, 5, TimeUnit.SECONDS);

// Fixed rate: period measured from START of each execution
// If task takes longer than period, next run starts immediately after
scheduler.scheduleAtFixedRate(task, 0, 1, TimeUnit.MINUTES);

// Fixed delay: period measured from END of each execution
// Always waits full delay after previous run finishes
scheduler.scheduleWithFixedDelay(task, 0, 30, TimeUnit.SECONDS);
```

**`scheduleAtFixedRate` vs `scheduleWithFixedDelay`:**

| | `AtFixedRate` | `WithFixedDelay` |
|---|---|---|
| Period measured from | Start of previous run | End of previous run |
| If task overruns | Next run starts immediately after | Still waits full delay |
| Use for | "Every minute on the clock" | "30s rest between runs" |

**When to use:** Heartbeat checks, cache refresh, metrics reporting, polling.

---

## 5. Shutting Down a Pool

Pool threads are **non-daemon** by default — they keep the JVM alive after `main()` returns. Forgetting shutdown is a common cause of processes that never exit.

### The two-phase shutdown pattern

```java
public void shutdown(ExecutorService executor) {
    executor.shutdown(); // Phase 1: stop accepting new tasks
                         // In-flight tasks continue running normally
    try {
        // Phase 2: wait up to 30s for running tasks to finish
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow(); // Force-cancel tasks still running
            // shutdownNow() sends interrupt() to each running thread
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt(); // restore interrupt flag
    }
}
```

| Method | Behaviour |
|---|---|
| `shutdown()` | Graceful. Stops accepting new submits. Waits for running tasks. |
| `shutdownNow()` | Forceful. Interrupts running threads. Returns list of queued tasks not started. |
| `awaitTermination(t, u)` | Blocks until shutdown or timeout. Returns true if fully terminated. |

> Always use `shutdown()` first, then `shutdownNow()` as the escape hatch.

---

## 6. Sizing the Pool

### Theoretical formula

```
Thread count = N_cpu × U_cpu × (1 + W/C)

N_cpu = Runtime.getRuntime().availableProcessors()
U_cpu = target CPU utilization (0.0–1.0)
W     = average wait time per task (blocking on I/O, network, locks)
C     = average compute time per task (actually using the CPU)
```

### Practical heuristics

| Task type | Starting point | Reasoning |
|---|---|---|
| CPU-bound (math, crypto, image processing) | `N_cpu` or `N_cpu + 1` | More threads → context switch overhead with no gain |
| I/O-bound (DB queries, HTTP calls, file reads) | `2 × N_cpu` to `10 × N_cpu` | Threads sleep during I/O; many can coexist on few cores |
| Mixed | Profile and measure | No formula beats observation |

> **Always measure.** Profile under realistic load with JVisualVM or async-profiler. Find where threads actually spend their time.

---

## 7. Why Standard Collections Break Under Concurrency

`ArrayList`, `HashMap`, `HashSet` assume single-threaded access. Their internal state (array pointers, bucket counts, size fields) is updated without synchronization.

The naive fix — wrapping in `Collections.synchronizedMap()` — is a blunt instrument:

- One lock for the entire collection — zero read concurrency
- Iteration **still requires manual external synchronization**
- Compound operations (`get` + `put`) are still not atomic

```java
// synchronizedMap: every read and write locks the same object
Map<String, Integer> map = Collections.synchronizedMap(new HashMap<>());

// Iteration still requires external lock — or you get ConcurrentModificationException
synchronized (map) {
    for (Map.Entry<String, Integer> e : map.entrySet()) { ... }
}

// Two separate atomic operations — still races
if (!map.containsKey("key")) {     // thread A: absent
    map.put("key", value);         // thread B also passes check, both put
}
```

**The right answer: use a concurrent collection designed for the access pattern from the start.**

---

## 8. `ConcurrentHashMap`

### How it works

In Java 8+, `ConcurrentHashMap` does **not** lock the whole map for writes. It locks only the specific **bucket** being modified.

```
Bucket[0]: [K1→V1]  ← Thread A writing here (bucket 0 locked)
Bucket[1]: [K3→V3]  ← Thread B writing here (bucket 1 locked) — zero contention
Bucket[2]: empty
...
```

**Reads are entirely lock-free** — they use `volatile` reads of the node array. No lock acquired, no threads blocked, ever.

### Atomic compound operations — the most important feature

These methods perform read-modify-write as one atomic operation, eliminating the gap between `get` and `put`:

```java
ConcurrentHashMap<String, Integer> inventory = new ConcurrentHashMap<>();

// merge: if key absent → put delta. If present → apply mergeFunction(current, delta)
inventory.merge("SKU-001", 5, Integer::sum);  // 0 → 5
inventory.merge("SKU-001", 3, Integer::sum);  // 5 → 8 (atomic — no race possible)

// computeIfAbsent: put value only if key is missing — atomic
// Perfect for lazy cache initialization; no double-checked locking needed
inventory.computeIfAbsent("SKU-NEW", key -> fetchInitialStock(key));

// computeIfPresent: update only if key exists — atomic
inventory.computeIfPresent("SKU-001", (k, v) -> v > 0 ? v - 1 : 0);

// compute: full atomic read-modify-write regardless of key presence
inventory.compute("SKU-001", (k, v) -> v == null ? 1 : v + 1);

// putIfAbsent: atomic — no check-then-act race
inventory.putIfAbsent("SKU-002", 100);
```

### What `ConcurrentHashMap` does NOT guarantee

Individual operations are atomic. **Multiple operations are not jointly atomic:**

```java
// BROKEN — two separate atomic operations still race
if (!inventory.containsKey("SKU-001")) {  // Thread A: absent
                                           // Thread B: also sees absent
    inventory.put("SKU-001", 100);        // Both put — one silently overwrites
}

// FIXED — one atomic operation
inventory.computeIfAbsent("SKU-001", k -> 100);
```

### Iteration

`ConcurrentHashMap` iterators reflect the state at the start of iteration. Concurrent modifications are not guaranteed to show up — but no `ConcurrentModificationException` is thrown. This is the correct contract for a concurrent collection.

---

## 9. `CopyOnWriteArrayList`

### How it works

Every write (add, remove, set) makes a **full copy** of the internal array, applies the change, then atomically swaps the reference. Readers hold a reference to the version they started with — they never see partial writes.

```
Before add("D"): backing → [A, B, C]
                 Reader 1 holds ref → [A, B, C]  ← reading, safe forever

add("D"):
  1. copy:        new array   → [A, B, C, D]
  2. atomic swap: backing ref → [A, B, C, D]

Reader 1: still iterates [A, B, C] — its snapshot is unchanged, no CME
Reader 2 (starts after swap): sees [A, B, C, D]
```

### The write cost

"Copy on write" is literal. Adding one element to a 10,000-element list copies all 10,000 elements. **Write cost is O(n).**

```java
// CORRECT use — written once, read millions of times
CopyOnWriteArrayList<MetricsListener> listeners = new CopyOnWriteArrayList<>();
listeners.add(new DatabaseListener());  // O(n) — done once at startup
listeners.add(new DashboardListener()); // O(n) — done once at startup

// Read-heavy: called 10,000x/sec, zero lock contention
for (MetricsListener l : listeners) {   // iterates snapshot — lock-free
    l.onMetric(name, value);
}
```

```java
// WRONG use — high write rate = O(n) copy per write = performance disaster
CopyOnWriteArrayList<Order> orderQueue = new CopyOnWriteArrayList<>();
orders.forEach(o -> orderQueue.add(o)); // Each add copies the whole list — do NOT do this
// Use LinkedBlockingQueue or ConcurrentLinkedQueue for high-write workloads
```

### Safe iteration

The iterator captures the array reference at `iterator()` call time. Subsequent writes create a new array — the iterator never sees them. No `ConcurrentModificationException`, ever. The iterator may reflect a slightly stale view — which is correct and expected.

### When to use

The access pattern must satisfy: **writes are rare, reads are frequent, and iteration is the primary operation.**

Classic use case: event listeners, observer lists, plugin registries. Registered a handful of times at startup, iterated millions of times during operation.

---

## 10. `BlockingQueue` and the Producer-Consumer Pattern

### The core idea

A `BlockingQueue` is a thread-safe queue with two superpowers:

- **`put(item)`** — if the queue is full, the calling thread sleeps (parks) until space is available
- **`take()`** — if the queue is empty, the calling thread sleeps until an item arrives

This eliminates the busy-wait loop (`while(empty) Thread.sleep(10)`) entirely. Parked threads release the CPU completely. They are woken by the OS scheduler only when there is actual work.

### The producer-consumer pattern

```
Producer threads → put() → [queue buffer] → take() → Consumer threads
```

Producers and consumers are decoupled by the queue. If producers are faster: queue fills, producers block, consumers catch up. If consumers are faster: queue empties, consumers block, producers catch up. Neither side needs to know about the other's speed.

```java
BlockingQueue<String> queue = new LinkedBlockingQueue<>(100); // bounded at 100

// Producer
Thread producer = new Thread(() -> {
    for (int i = 0; i < 1000; i++) {
        try {
            queue.put("task-" + i); // blocks if full — back-pressure
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
    }
});

// Consumer
Runnable consumer = () -> {
    while (!Thread.currentThread().isInterrupted()) {
        try {
            String task = queue.take(); // parks if empty — no busy waiting
            process(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // clean shutdown
        }
    }
};
```

### The three `BlockingQueue` implementations

```java
// LinkedBlockingQueue — optionally bounded, separate locks for head and tail
// Producers and consumers rarely contend — different ends of the queue
// Most common choice. Good default.
BlockingQueue<Task> q = new LinkedBlockingQueue<>(capacity);

// ArrayBlockingQueue — strictly bounded, single lock for put and take
// More producer-consumer contention, but fixed memory footprint
// Use when memory predictability matters more than throughput
BlockingQueue<Task> q = new ArrayBlockingQueue<>(capacity);

// PriorityBlockingQueue — unbounded, sorted by Comparator
// take() always returns highest-priority element
// Use for task scheduling where tasks have different urgency
BlockingQueue<Task> q = new PriorityBlockingQueue<>(16,
    Comparator.comparingInt(Task::getPriority));
```

### Non-blocking variants

```java
// offer() — insert if space available, return false immediately if not
boolean inserted = queue.offer(task);

// offer() with timeout — try for up to 500ms, then give up
boolean inserted = queue.offer(task, 500, TimeUnit.MILLISECONDS);

// poll() with timeout — wait up to 1s for an item
String task = queue.poll(1, TimeUnit.SECONDS); // null if nothing arrived

// peek() — look at head without removing, non-blocking
String next = queue.peek(); // null if empty
```

---

## 11. Collection Decision Guide

| Scenario | Use | Why |
|---|---|---|
| Concurrent key-value lookup/update | `ConcurrentHashMap` | Bucket-level locking, atomic compound ops, lock-free reads |
| Rarely written, frequently iterated | `CopyOnWriteArrayList` | Lock-free iteration, snapshot safety, no CME |
| Producer-consumer task queue | `LinkedBlockingQueue` | Blocking put/take, separate head/tail locks |
| Memory-bounded strict-capacity queue | `ArrayBlockingQueue` | Hard cap, fixed allocation |
| Priority-ordered task processing | `PriorityBlockingQueue` | Sorted take(), concurrent-safe |
| Concurrent sorted set | `ConcurrentSkipListSet` | Lock-free, sorted, O(log n) |
| Concurrent sorted map | `ConcurrentSkipListMap` | Lock-free, sorted, scalable |
| Single counter | `AtomicInteger` | CAS, no lock, 10–100× faster than synchronized |

---

## 12. Common Anti-Patterns

### Using `synchronizedMap` or `synchronizedList`

```java
// WRONG — one lock for all operations, still unsafe for iteration
Map<String, Integer> map = Collections.synchronizedMap(new HashMap<>());

// WRONG — iteration still needs external lock
for (var entry : map.entrySet()) { ... } // ConcurrentModificationException risk

// RIGHT
ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
for (var entry : map.entrySet()) { ... } // always safe
```

### Using `Vector` or `Hashtable`

These are legacy synchronized collections from Java 1.0. They use coarse method-level synchronization. Never use in new code — use `CopyOnWriteArrayList` or `ConcurrentHashMap` instead.

### High-write `CopyOnWriteArrayList`

```java
// WRONG — O(n) copy per add kills performance for high-write workloads
CopyOnWriteArrayList<Order> orders = new CopyOnWriteArrayList<>();
stream.forEach(o -> orders.add(o)); // disaster

// RIGHT — use a queue for high-write workloads
LinkedBlockingQueue<Order> orders = new LinkedBlockingQueue<>();
```

### Two-operation compound actions on `ConcurrentHashMap`

```java
// WRONG — get() + put() is not atomic even on ConcurrentHashMap
Integer current = map.get(key);
map.put(key, current == null ? 1 : current + 1); // race between get and put

// RIGHT — merge() is one atomic operation
map.merge(key, 1, Integer::sum);
```

### Not shutting down the pool

```java
// WRONG — JVM never exits; process hangs indefinitely
ExecutorService pool = Executors.newFixedThreadPool(10);
// ... use pool ...
// missing: pool.shutdown()

// RIGHT
pool.shutdown();
if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow();
```

---

## 13. Key Takeaways

1. **Thread creation costs ~1MB and ~100 microseconds.** Thread pools amortize this cost across all tasks — pay once at startup, reuse forever.

2. **`submit()` returns a `Future` immediately.** The task runs asynchronously in a pool thread. `future.get()` blocks until the result is ready. `future.get(timeout, unit)` prevents indefinite blocking.

3. **Fixed pool = predictable resources.** `N` threads, unbounded queue. Right for most servers. Size at `N_cpu` for CPU-bound, `2×N_cpu+` for I/O-bound.

4. **Cached pool = elastic but dangerous.** No thread limit — can create thousands of threads under sustained load and crash the JVM. Short tasks only.

5. **Always shut down pools.** Non-daemon threads keep the JVM alive. Two-phase: `shutdown()` to drain gracefully, `shutdownNow()` as the escape hatch.

6. **`Collections.synchronizedMap` is a blunt wrapper.** One lock for everything, iteration still unsafe without external lock, compound ops still race. Use `ConcurrentHashMap` instead.

7. **`ConcurrentHashMap` atomic methods are the key feature.** `merge`, `computeIfAbsent`, `compute` eliminate the class of bugs where `get` + `put` sequences race.

8. **`CopyOnWriteArrayList` is for read-dominated access only.** O(n) write cost makes it catastrophically wrong for high-write workloads. It is correct for observer/listener registries.

9. **`BlockingQueue.take()` eliminates busy-waiting.** Threads park and release the CPU when the queue is empty. This is the correct foundation for producer-consumer systems.

10. **Pick the collection for the access pattern, not just "it's concurrent."** The right collection for a read-heavy lookup table (`ConcurrentHashMap`) is wrong for a producer-consumer queue (`LinkedBlockingQueue`), and both are wrong for a priority-ordered task system (`PriorityBlockingQueue`).

---

*Next: Phase 4 — `wait()`, `notify()`, `Condition` objects, and the `java.util.concurrent` higher-level utilities (`CountDownLatch`, `CyclicBarrier`, `Semaphore`).*

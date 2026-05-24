# Concurrency — `ReentrantLock` and the `atomic` Package

> **Phase context:** This is the second chapter of Phase 4 (Concurrency & Thread Safety). It covers explicit locking with `ReentrantLock` and hardware-level lock-free programming with CAS-based atomics.

---

## Table of Contents

1. [The Core Limitation of `synchronized`](#1-the-core-limitation-of-synchronized)
2. [ReentrantLock — Explicit Lock Control](#2-reentrantlock--explicit-lock-control)
3. [What "Reentrant" Means](#3-what-reentrant-means)
4. [The Four Powers of ReentrantLock](#4-the-four-powers-of-reentrantlock)
5. [The Unlock-in-Finally Rule](#5-the-unlock-in-finally-rule)
6. [synchronized vs ReentrantLock — Choosing the Right Tool](#6-synchronized-vs-reentrantlock--choosing-the-right-tool)
7. [Optimistic vs Pessimistic Locking](#7-optimistic-vs-pessimistic-locking)
8. [CAS — Compare-And-Swap](#8-cas--compare-and-swap)
9. [AtomicInteger and the atomic Package](#9-atomicinteger-and-the-atomic-package)
10. [The ABA Problem](#10-the-aba-problem)
11. [AtomicInteger vs synchronized vs LongAdder](#11-atomicinteger-vs-synchronized-vs-longadder)
12. [Common Traps with Atomics](#12-common-traps-with-atomics)
13. [Key Takeaways](#13-key-takeaways)

---

## 1. The Core Limitation of `synchronized`

`synchronized` is simple and safe, but completely inflexible:

- A thread blocked waiting for a lock **cannot be interrupted** — it waits forever or until the lock is free.
- There is **no timeout** — you cannot say "try for 500ms, then give up."
- You **cannot inspect** the lock — no way to ask how many threads are waiting or whether the lock is held.
- There is **no fairness guarantee** — threads can starve indefinitely if the JVM keeps picking the same thread.

For simple cases, these limitations don't matter. For payment systems, connection pools, request queues, and graceful shutdowns, they matter enormously. This is where `ReentrantLock` comes in.

---

## 2. ReentrantLock — Explicit Lock Control

### The analogy

**`synchronized`**: The bank manages the queue entirely. You join the line and wait — no matter how long. No control once you're in.

**`ReentrantLock`**: You get a numbered token. You can set a timer and leave if the wait is too long. You can abandon the queue if tapped on the shoulder. You can inspect how many people are ahead of you.

Same fundamental guarantee — one thread at a time — but with full control over waiting behavior.

### Basic usage

```java
import java.util.concurrent.locks.ReentrantLock;

public class BankAccount {
    private double balance;
    private final ReentrantLock lock = new ReentrantLock();

    // Simple lock/unlock pattern
    public double getBalance() {
        lock.lock();
        try {
            return balance;
        } finally {
            lock.unlock(); // ALWAYS in finally — never skip this
        }
    }
}
```

### synchronized equivalent

```java
// These two are functionally identical for simple cases
public synchronized double getBalance() {
    return balance;
}

// ReentrantLock version above does the same thing — use synchronized here
```

> **Rule:** For simple mutual exclusion, prefer `synchronized`. Use `ReentrantLock` only when you need one of its four extra powers.

---

## 3. What "Reentrant" Means

*Reentrant* means: **the same thread can acquire the same lock multiple times without deadlocking itself.**

The lock maintains a *hold count*. Each `lock()` increments it. Each `unlock()` decrements it. The lock is only truly released when the hold count reaches zero.

```java
public void outerMethod() {
    lock.lock();           // hold count: 1
    try {
        innerMethod();     // calls another method that also locks
    } finally {
        lock.unlock();     // hold count: 0 — truly released
    }
}

public void innerMethod() {
    lock.lock();           // same thread, same lock — allowed! hold count: 2
    try {
        // do work
    } finally {
        lock.unlock();     // hold count back to 1 — NOT yet released
    }
}
```

Without reentrancy, `outerMethod` calling `innerMethod` would deadlock — the thread would wait for itself forever. Both `synchronized` and `ReentrantLock` are reentrant. `ReentrantLock` just exposes the hold count via `getHoldCount()`.

---

## 4. The Four Powers of ReentrantLock

### Power 1: `tryLock()` — timeout on lock acquisition

With `synchronized`, if Thread B holds the lock, Thread A **will** wait. Period.

`tryLock(timeout, unit)` says: *"Try to acquire the lock. If you can't within the timeout, return `false` and give up."*

```java
public boolean withdraw(double amount, long timeoutMs) {
    try {
        if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
            System.out.println("Could not acquire lock — aborting");
            return false;  // give up gracefully instead of hanging
        }
        try {
            if (balance < amount) return false;
            balance -= amount;
            return true;
        } finally {
            lock.unlock();
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }
}
```

**Real-world use:** Payment gateway calls, database row locks, any resource where a timeout is better than an infinite wait.

### Power 2: `lockInterruptibly()` — cancellable waiting

With `synchronized`, calling `thread.interrupt()` on a blocked thread does nothing — it stays blocked.

`lockInterruptibly()` throws `InterruptedException` when the waiting thread is interrupted, enabling clean cancellation:

```java
try {
    lock.lockInterruptibly(); // throws if interrupted while waiting
    try {
        // do protected work
    } finally {
        lock.unlock();
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // restore the interrupt flag
    System.out.println("Cancelled — shutting down cleanly");
}
```

**Real-world use:** Task executors that cancel queued work during shutdown, circuit breakers, request cancellation in web servers.

### Power 3: Observability — inspect the lock's state

`synchronized` is a black box. `ReentrantLock` is transparent:

```java
ReentrantLock lock = new ReentrantLock();

lock.isLocked()              // is anyone holding it right now?
lock.isHeldByCurrentThread() // does THIS thread hold it?
lock.getHoldCount()          // how many times has current thread locked it?
lock.getQueueLength()        // how many threads are waiting?
lock.hasQueuedThreads()      // is anyone waiting at all?
```

**Real-world use:** Monitoring dashboards, lock diagnostics, adaptive routing ("if `getQueueLength() > 10`, send this request elsewhere").

### Power 4: Fair ordering — `new ReentrantLock(true)`

By default, both `synchronized` and `ReentrantLock` give the lock to any waiting thread — often the most recently blocked one. Under high contention, the same thread can acquire the lock repeatedly while others starve indefinitely.

A **fair lock** maintains a strict FIFO queue. The thread that waited longest gets the lock next. No starvation, ever.

```java
// Unfair (default) — faster, but starvation possible
ReentrantLock unfairLock = new ReentrantLock();

// Fair — strict FIFO, ~10-30% slower, no starvation
ReentrantLock fairLock = new ReentrantLock(true);
```

**Real-world use:** Rate limiters, ticket booking systems, any scenario where fairness is a correctness requirement, not just a preference.

---

## 5. The Unlock-in-Finally Rule

`synchronized` releases the lock automatically on block exit, even if an exception is thrown. `ReentrantLock` does not — you are responsible.

If an exception escapes between `lock.lock()` and `lock.unlock()`, the lock is held forever and all waiting threads deadlock.

### The correct pattern — always

```java
lock.lock();
try {
    // critical section
} finally {
    lock.unlock(); // runs whether try succeeded or threw — always
}
```

> **Never** put `lock.lock()` inside the `try` block. If `lock()` itself throws, `finally` would call `unlock()` without a matching `lock()`, throwing `IllegalMonitorStateException`.

### The correct `tryLock` pattern — double try

```java
try {
    // Outer try: handles InterruptedException from tryLock
    if (!lock.tryLock(500, TimeUnit.MILLISECONDS)) {
        return false; // timeout — give up
    }
    try {
        // Inner try/finally: guarantees unlock only if lock was acquired
        // do the work
        return true;
    } finally {
        lock.unlock();
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return false;
}
```

This structure is worth memorizing. It appears in nearly every real `tryLock` usage.

---

## 6. `synchronized` vs `ReentrantLock` — Choosing the Right Tool

| Scenario | Use |
|---|---|
| Simple mutual exclusion | `synchronized` — cleaner, impossible to forget unlock |
| Need timeout on lock acquisition | `ReentrantLock.tryLock(timeout, unit)` |
| Need clean task cancellation / shutdown | `ReentrantLock.lockInterruptibly()` |
| Need to inspect lock state for monitoring | `ReentrantLock.isLocked()`, `getQueueLength()` |
| Need fair ordering, no starvation | `new ReentrantLock(true)` |
| Using `Condition` variables (advanced signaling) | `ReentrantLock.newCondition()` |

> **Default:** `synchronized`. Reach for `ReentrantLock` only when you need a specific feature it provides. Choosing it "just in case" is unnecessary complexity.

---

## 7. Optimistic vs Pessimistic Locking

Every concurrency tool so far has been **pessimistic** — it assumes conflict will happen, so it locks the resource before touching it.

**Pessimistic (synchronized):** Before editing, you call everyone and say "I'm using this, nobody else touch it." Everyone waits. Safe, but everyone is blocked.

**Optimistic (CAS):** You just start editing. When you save, you check: "has anyone else changed this since I read it?" If yes — retry. If no — commit. Nobody was blocked.

The `atomic` package is optimistic. It assumes conflict is rare, does the work without locking, and retries on the rare collision.

---

## 8. CAS — Compare-And-Swap

CAS is a **single hardware instruction** on every modern CPU. The CPU's memory bus locks for the duration of this one instruction — no other core can touch that memory address mid-operation.

### The operation

```
CAS(memoryAddress, expectedValue, newValue)
  → if *memoryAddress == expectedValue:
        *memoryAddress = newValue
        return true   // success
     else:
        return false  // failed — someone else changed it first
```

One atomic check-and-write. Either it fully succeeds or fully fails. No half-done state possible.

### How `incrementAndGet()` uses CAS internally

```
loop:
    current = READ counter from memory    // e.g. 5
    next    = current + 1                 // e.g. 6
    if CAS(counter, current, next):       // "if it's still 5, write 6"
        return next                       // success on first try
    else:
        goto loop                         // someone else changed it — retry
```

Under low contention: almost always succeeds on the first try.
Under high contention: threads collide, one wins, others retry. But they **never block** — they spin at CPU speed instead of sleeping in a queue.

### CAS vs lock

```
Lock (synchronized/ReentrantLock):
  Thread sleeps in OS queue → OS wakes it → context switch → runs
  Cost: ~10,000–100,000 ns per lock/unlock under contention

CAS (AtomicInteger):
  Thread reads → computes → attempts write → retries if needed
  Cost: ~10–100 ns per operation (hardware instruction)
```

CAS wins decisively for single-variable operations under moderate contention.

---

## 9. `AtomicInteger` and the `atomic` Package

### The four categories

#### Scalar atomics — wrapping a single primitive

```java
AtomicInteger   count   = new AtomicInteger(0);
AtomicLong      latency = new AtomicLong(0);
AtomicBoolean   shutdown = new AtomicBoolean(false);
AtomicReference<String> status = new AtomicReference<>("IDLE");
```

#### Array atomics — each element individually atomic

```java
AtomicIntegerArray slots = new AtomicIntegerArray(100);
slots.incrementAndGet(42); // atomically increments slots[42] only
```

#### Field updater atomics — make an existing `volatile` field atomic

Useful when the object is allocated millions of times and the `AtomicInteger` wrapper object would waste memory:

```java
public class Order {
    volatile int status = 0; // plain volatile int — no wrapper object
    static final AtomicIntegerFieldUpdater<Order> STATUS =
        AtomicIntegerFieldUpdater.newUpdater(Order.class, "status");
}

// Use the updater to do atomic CAS on the plain int field
Order.STATUS.compareAndSet(order, 0, 1); // atomically set 0 → 1
```

#### Accumulator classes — high-throughput counting (Java 8+)

```java
LongAdder     hitCounter  = new LongAdder();
LongAccumulator maxLatency = new LongAccumulator(Long::max, 0);

hitCounter.increment();           // writes to a per-thread cell — near-zero contention
hitCounter.sum();                 // merges all cells at read time

maxLatency.accumulate(latencyMs); // applies Long::max atomically
maxLatency.get();                 // current max
```

`LongAdder` stripes updates across per-thread cells, eliminating CAS collisions entirely. At read time, the cells are summed. This is the right tool for very high-throughput counters (request rates, hit counts, event totals).

### Key `AtomicInteger` operations

```java
AtomicInteger c = new AtomicInteger(10);

c.get()                 // → 10 (read only)
c.set(20)               // write 20 (not atomic with respect to other ops)
c.getAndSet(50)         // → 20, then sets 50 (atomic swap)
c.incrementAndGet()     // → 51 (increment first, then return)
c.getAndIncrement()     // → 51 (return first, then increment to 52)
c.addAndGet(5)          // → 57 (add first, then return)
c.getAndAdd(5)          // → 57 (return first, then add to 62)
c.compareAndSet(62, 100)// → true, sets 100 (manual CAS)
c.compareAndSet(62, 100)// → false, 62 no longer matches (it's 100)
```

### The optimistic update loop — custom atomic operations

`compareAndSet` enables arbitrary atomic operations that the standard methods don't cover:

```java
// Atomically double the counter, but only if it's below 1000
int current, next;
do {
    current = counter.get();
    if (current >= 1000) break;
    next = current * 2;
} while (!counter.compareAndSet(current, next));
// Loop retries if another thread changed the value before our CAS
```

This pattern is lock-free, safe, and correct. Use it whenever you need a custom single-variable atomic operation.

---

## 10. The ABA Problem

CAS checks if the value equals what you expect. But it can't tell if the value changed *and then changed back*.

```
Thread A reads:  5
Thread B changes: 5 → 7 → 5  (changed and restored)
Thread A CAS(5, 6): succeeds — but missed B's intermediate changes
```

**For counters:** ABA doesn't matter — you just care about the final number.

**For data structures with pointers** (lock-free linked lists, stacks): ABA can cause use-after-free bugs.

### Fix: `AtomicStampedReference`

Attaches a version counter (stamp) that increments on every write. Even if the value reverts, the stamp won't:

```java
AtomicStampedReference<String> ref =
    new AtomicStampedReference<>("IDLE", 0); // value=IDLE, stamp=0

int[] stampHolder = new int[1];
String value = ref.get(stampHolder);
int stamp = stampHolder[0]; // e.g. 3

// CAS requires BOTH value AND stamp to match
ref.compareAndSet(value, "ACTIVE", stamp, stamp + 1);
// Fails if any write happened since stamp=3, even if value looks the same
```

---

## 11. `AtomicInteger` vs `synchronized` vs `LongAdder`

| Scenario | Tool | Why |
|---|---|---|
| Single counter, moderate contention | `AtomicInteger` | Lock-free, simple, fast |
| Boolean flag (CAS needed) | `AtomicBoolean` | Cleaner than `volatile` for CAS ops |
| Shared object reference swap | `AtomicReference` | Lock-free whole-object swap |
| Custom compound logic | `synchronized` | CAS loops get complex; lock is simpler |
| Very high-throughput counter | `LongAdder` | Per-thread cells eliminate CAS collisions |
| Two related variables updated together | `synchronized` | Atomics only cover one variable |

---

## 12. Common Traps with Atomics

### Trap 1: Two atomics are not jointly atomic

```java
// BROKEN — these two updates are individually atomic but not together
AtomicInteger balance = new AtomicInteger(1000);
AtomicInteger txCount = new AtomicInteger(0);

public void withdraw(int amount) {
    balance.addAndGet(-amount);  // ← reader can see state between these two lines
    txCount.incrementAndGet();   // balance is decremented but count isn't yet
}

// FIX — wrap both in synchronized
public synchronized void withdraw(int amount) {
    balance -= amount;
    txCount++;
}
```

### Trap 2: Using `volatile` when you need `AtomicInteger`

```java
// BROKEN — volatile doesn't help with counter++
private volatile int count = 0;
public void increment() { count++; } // still a race — read/add/write not atomic

// FIXED
private final AtomicInteger count = new AtomicInteger(0);
public void increment() { count.incrementAndGet(); }
```

### Trap 3: `LongAdder.sum()` is approximate under concurrent writes

```java
LongAdder counter = new LongAdder();

// Thread A writes: counter.increment()
// Thread B reads:  counter.sum()
// The sum may not reflect A's increment if called during a write

// Use LongAdder for totals that are read infrequently (stats, metrics)
// Use AtomicLong if you need a precise read at any instant
```

---

## 13. Key Takeaways

1. **`synchronized` is inflexible** — no timeout, no interruption, no observability, no fairness. For complex scenarios, `ReentrantLock` provides explicit control over all four.

2. **`ReentrantLock` has exactly four extra powers:** `tryLock` (timeout), `lockInterruptibly` (cancellable wait), observability methods, and fair ordering. Use `synchronized` as the default; reach for `ReentrantLock` only when you need one of these.

3. **Always `unlock()` in `finally`.** `synchronized` releases automatically. `ReentrantLock` does not — a missed `unlock()` causes a permanent deadlock. The `lock.lock(); try { ... } finally { lock.unlock(); }` pattern is non-negotiable.

4. **CAS is a single hardware instruction** — an atomic check-and-swap with no lock, no OS involvement, and nanosecond-level cost. `AtomicInteger` is built entirely on CAS.

5. **Optimistic (CAS) beats pessimistic (lock) for single variables.** Under low-to-moderate contention, `AtomicInteger.incrementAndGet()` is 10–100× faster than `synchronized` increment.

6. **Atomics protect one variable.** The moment you need two or more variables updated as one atomic unit, atomics aren't enough — use `synchronized` or `ReentrantLock`.

7. **`LongAdder` beats `AtomicLong` at high throughput** by striping updates across per-thread cells and merging at read time. Use it for high-frequency metrics.

8. **ABA doesn't matter for counters** but matters for pointer-based data structures. `AtomicStampedReference` fixes it by versioning every write.

---

*Next: Phase 4 — `wait()`, `notify()`, `Condition`, thread-safe collections, and `Executors`.*

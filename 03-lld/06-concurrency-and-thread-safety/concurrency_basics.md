# Concurrency Basics: Race Conditions, Visibility, `volatile`, and `synchronized`

> **Phase context:** This is the opening chapter of Phase 4 (Concurrency & Thread Safety). It covers the two root causes of concurrency bugs and the two fundamental tools Java gives you to fix them.

---

## Table of Contents

1. [The Two Root Problems](#1-the-two-root-problems)
2. [Race Conditions](#2-race-conditions)
3. [The Visibility Problem](#3-the-visibility-problem)
4. [`volatile` — fixing visibility](#4-volatile--fixing-visibility)
5. [`synchronized` — mutual exclusion + visibility](#5-synchronized--mutual-exclusion--visibility)
6. [Method-level vs Block-level `synchronized`](#6-method-level-vs-block-level-synchronized)
7. [`volatile` vs `synchronized` — choosing the right tool](#7-volatile-vs-synchronized--choosing-the-right-tool)
8. [The Happens-Before Relationship](#8-the-happens-before-relationship)
9. [Common Traps and Anti-Patterns](#9-common-traps-and-anti-patterns)
10. [Decision Guide](#10-decision-guide)
11. [Key Takeaways](#11-key-takeaways)

---

## 1. The Two Root Problems

Every concurrency bug traces back to one or both of these two root causes:

| Problem | What goes wrong | Analogy |
|---|---|---|
| **Race Condition** | Two threads interleave across a multi-step operation, producing a wrong result | Two people erasing and rewriting the same whiteboard at the same time |
| **Visibility Problem** | One thread's write is never seen by another thread (stale cache) | A person reading their sticky note instead of walking to the shared whiteboard |

They are distinct problems and require different fixes:

- Race condition → needs **atomicity** → `synchronized` or `AtomicInteger`
- Visibility problem → needs **freshness** → `volatile` (or `synchronized`)

---

## 2. Race Conditions

### What it is

A race condition occurs when the **correctness of a program depends on the relative timing of thread execution**. Two threads read-modify-write the same variable, and the final result depends on who went last.

### Why it happens

`counter++` looks like one operation. It compiles to **three** CPU instructions:

```
1. READ  counter from memory  → register
2. ADD   1 to register
3. WRITE register back to memory
```

Two threads can interleave these three steps in any order:

```
Thread A: READ  → gets 5
Thread B: READ  → gets 5     (A hasn't written back yet)
Thread A: ADD 1, WRITE → counter = 6
Thread B: ADD 1, WRITE → counter = 6   ← overwrites A's result
```

Both incremented, but counter only went 5 → 6 instead of 5 → 7. **One increment was silently lost.**

### Classic example

```java
public class TicketCounter {
    private int availableTickets = 100;

    // BROKEN — race condition on check + decrement
    public void bookTicket(String userId) {
        if (availableTickets > 0) {       // Thread A reads: 1 ticket left
                                           // Thread B reads: 1 ticket left (same!)
            availableTickets--;            // Both decrement — now -1 tickets!
            System.out.println(userId + " booked. Remaining: " + availableTickets);
        }
    }
}
```

### The three compound operation patterns that cause races

```java
// 1. Read-modify-write
counter++;             // read → add → write

// 2. Check-then-act
if (x > 0) x--;        // check → act (gap between the two)

// 3. Put-if-absent
if (!map.containsKey(k)) map.put(k, v);  // check → act
```

All three look innocent. All three are broken under concurrency without synchronization.

---

## 3. The Visibility Problem

### What it is

Even without a race condition, **one thread's writes may not be visible to other threads**. The JVM allows each thread to cache variables in CPU registers or L1 cache. Thread A updates a value; Thread B keeps reading its stale cached copy indefinitely.

### Why it happens

Modern CPUs have multiple cores, each with their own L1/L2 cache. Reading from cache is ~100× faster than reading from main memory. The JVM exploits this — if you don't use a synchronization primitive, there is **no guarantee** when (or if) one thread's write becomes visible to another.

```
Without any synchronization:

Thread A writes → CPU cache (maybe flushes to main memory... eventually)
Thread B reads  → its own CPU cache (reads stale copy — may never update)
```

### Classic example

```java
public class VisibilityDemo {
    private static boolean running = true; // NOT volatile

    public static void main(String[] args) throws InterruptedException {
        Thread worker = new Thread(() -> {
            int count = 0;
            while (running) {   // reads stale cached value — may loop forever
                count++;
            }
            System.out.println("Stopped. Count: " + count);
        });

        worker.start();
        Thread.sleep(100);
        running = false;   // main thread writes — worker may never see this!
    }
}
```

The worker thread caches `running = true` in its register and never re-reads from main memory. The `while` loop runs forever.

---

## 4. `volatile` — fixing visibility

### What it does

`volatile` is a directive to the JVM and CPU: **bypass the cache entirely for this variable.**

- Every **read** goes straight to main memory (never from cache)
- Every **write** flushes to main memory immediately and invalidates other threads' caches

This is called a **memory barrier** (or memory fence).

```java
private static volatile boolean running = true; // now the worker sees the change
```

### What `volatile` guarantees

- Visibility of reads and writes to that specific variable ✅
- Atomicity of a **single** read or write ✅
- Atomicity of a **sequence** of operations ❌ — does NOT help

### When `volatile` is the right tool

Use `volatile` when all three conditions hold:

1. Only one thread **writes** the variable (or writes don't depend on current value)
2. No compound operation (no check-then-act, no read-modify-write)
3. It is a single field (not two related fields that must be updated together)

```java
// Perfect volatile use case: single-writer status signal
public class PaymentProcessor {
    private volatile boolean shutdownRequested = false;

    public void requestShutdown() {
        shutdownRequested = true;   // single write — atomic on its own
    }

    public void processPayments() {
        while (!shutdownRequested) {   // single read — volatile ensures freshness
            // process next payment...
        }
    }
}
```

### What `volatile` does NOT fix

```java
private volatile int counter = 0;

// STILL BROKEN — volatile makes each read/write fresh, but the
// three steps (read, add, write) can still interleave between threads
public void increment() {
    counter++;   // not atomic! volatile doesn't help here
}
```

---

## 5. `synchronized` — mutual exclusion + visibility

### The analogy: a single-stall bathroom

One bathroom with a latch. The rule: lock it when you enter, unlock when you leave. If it's locked, you wait outside. This is `synchronized` — the bathroom is your shared resource, the latch is the **monitor lock**.

### What `synchronized` guarantees

1. **Mutual exclusion** — only one thread executes the block at a time. No interleaving.
2. **Visibility** — when a thread exits a synchronized block, all writes are flushed to main memory. When another thread enters (acquires the same lock), it reads the freshest values. This covers **all** variables touched inside the block, not just one.

### How the monitor works

Every Java object has a hidden **monitor** with:

- **Entry set** — threads blocked waiting to acquire the lock
- **The lock** — held by at most one thread at a time
- **Wait set** — threads waiting on `wait()` (covered later)

When a thread calls a `synchronized` method:
1. It enters the entry set and tries to acquire the monitor lock.
2. If free — takes the lock, enters the critical section.
3. On exit — releases the lock, flushes all writes to main memory.
4. The JVM picks a thread from the entry set, hands it the lock.

### Fixed TicketCounter

```java
public class TicketCounter {
    private int availableTickets;

    public TicketCounter(int total) {
        this.availableTickets = total;
    }

    // Entire method is atomic — only one thread can execute at a time
    // Lock object = this (the TicketCounter instance)
    public synchronized boolean bookTicket(String userId) {
        if (availableTickets > 0) {
            availableTickets--;   // now truly atomic — no interleaving possible
            System.out.println(userId + " booked. Remaining: " + availableTickets);
            return true;
        }
        System.out.println(userId + " — sold out");
        return false;
    }

    // READ also needs synchronized — otherwise it can see stale values
    public synchronized int getAvailableTickets() {
        return availableTickets;
    }
}
```

> **Rule:** Every access to a shared mutable variable — **reads and writes both** — must be protected by the same lock. Half-synchronized code is as broken as unsynchronized code.

---

## 6. Method-level vs Block-level `synchronized`

### Method-level

Locks the entire method. The implicit lock object is `this`.

```java
public synchronized void bookTicket() { ... }
// equivalent to:
public void bookTicket() {
    synchronized (this) { ... }
}
```

### Block-level — finer granularity

Lets you choose exactly what's protected, and use **different locks for independent resources**.

```java
public class OrderProcessor {
    private final Object inventoryLock = new Object();
    private final Object auditLock     = new Object();
    private int inventory = 500;
    private final List<String> auditLog = new ArrayList<>();

    public void processOrder(String orderId, int quantity) {
        boolean reserved;

        // Only inventory update holds inventoryLock
        synchronized (inventoryLock) {
            if (inventory >= quantity) {
                inventory -= quantity;
                reserved = true;
            } else {
                reserved = false;
            }
        }
        // inventoryLock released here — other threads can update inventory now!
        // Audit logging does NOT block inventory threads

        synchronized (auditLock) {
            auditLog.add(orderId + ": " + (reserved ? "reserved " + quantity : "failed"));
        }
    }
}
```

### Why fine-grained locking matters

With method-level `synchronized (this)`, a thread writing to the audit log blocks threads that need to update inventory — even though they share no data. That's wasted contention.

**Analogy:** Two cashier counters in a store. Using one lock for both is like having one queue for both counters. Fine-grained locks = two independent queues that don't interfere.

### Fine-grained vs coarse-grained trade-offs

| | Coarse-grained (one lock) | Fine-grained (multiple locks) |
|---|---|---|
| **Simplicity** | Easier to reason about | More complex |
| **Contention** | Higher — unrelated operations block each other | Lower — independent operations run concurrently |
| **Deadlock risk** | Lower | Higher — more locks = more potential for deadlock |
| **Use when** | Operations share data | Operations are truly independent |

---

## 7. `volatile` vs `synchronized` — choosing the right tool

### Side-by-side comparison

| Property | `volatile` | `synchronized` |
|---|---|---|
| Visibility | ✅ Guarantees | ✅ Guarantees |
| Atomicity | ❌ Single read/write only | ✅ Entire block |
| Mutual exclusion | ❌ None | ✅ One thread at a time |
| Performance | Faster — no lock | Slower — lock overhead |
| Use for | Flags, status signals | Compound operations |

### The dangerous misconception

```java
// This LOOKS thread-safe — it isn't
public class ConnectionPool {
    private volatile int activeConnections = 0;
    private final int MAX = 10;

    public boolean acquire() {
        if (activeConnections < MAX) {      // Thread A reads 9
                                             // Thread B reads 9 (same!)
            activeConnections++;             // Both increment → 11 connections!
            return true;
        }
        return false;
    }
}
```

`volatile` made every read go to main memory — both threads see `9`, the freshest value. But they still both pass the check and increment. Visibility is not enough. The compound `check → act` still races.

**Fix:**
```java
public synchronized boolean acquire() {
    if (activeConnections < MAX) {
        activeConnections++;
        return true;
    }
    return false;
}
```

Or idiomatically with `AtomicInteger`:
```java
private final AtomicInteger activeConnections = new AtomicInteger(0);

public boolean acquire() {
    return activeConnections.incrementAndGet() <= MAX;
    // incrementAndGet is atomic — read-add-write in one uninterruptible step
}
```

---

## 8. The Happens-Before Relationship

The Java Memory Model defines **happens-before**: a formal guarantee that certain writes are visible to certain reads.

### `volatile` happens-before

A write to a `volatile` variable happens-before every subsequent read of that same variable. Only that variable.

```java
volatile boolean flag = false;
String data = null;

// Thread A
data = "result";    // NOT covered by volatile's happens-before
flag = true;        // volatile write

// Thread B
if (flag) {          // volatile read — sees true
    use(data);       // MAY SEE NULL — 'data' has no visibility guarantee
}
```

This is a real gotcha. `data` isn't volatile, so the JVM is free to reorder `data = "result"` and `flag = true` within Thread A. Thread B may read `true` for `flag` but `null` for `data`.

### `synchronized` happens-before

A monitor unlock happens-before every subsequent lock of the same monitor. Everything written before the unlock is visible after the lock — **all variables**, with no reordering across the barrier.

```java
// synchronized gives a full memory barrier — all variables visible, no reordering
synchronized (lock) {
    data = "result";   // visible to next thread that acquires this lock
    count++;           // visible
    flag = true;       // visible
}
```

---

## 9. Common Traps and Anti-Patterns

### Trap 1: Only synchronizing writes, not reads

```java
// BROKEN
public synchronized void setValue(int v) { this.value = v; }
public int getValue() { return this.value; }  // unsynchronized read — stale!

// FIXED
public synchronized void setValue(int v) { this.value = v; }
public synchronized int getValue() { return this.value; }
```

### Trap 2: Locking on different objects

```java
// Instance method locks on 'this'
public synchronized void instanceMethod() { ... }  // lock = this

// Static method locks on the Class object — DIFFERENT lock!
public static synchronized void staticMethod() { ... }  // lock = MyClass.class

// These two do NOT exclude each other
```

### Trap 3: Holding the lock too long

```java
// BAD — holds lock during slow network call
public synchronized void processOrder(Order order) {
    validateInventory(order);      // fast
    chargePaymentGateway(order);   // SLOW — 500ms network call while holding lock!
    updateDatabase(order);         // slow
}

// GOOD — only lock around shared state mutation
public void processOrder(Order order) {
    validateInventory(order);   // no shared state — no lock needed
    chargePaymentGateway(order); // no shared state — no lock needed

    synchronized (this) {
        updateDatabase(order);  // only lock for actual shared state write
    }
}
```

### Trap 4: Deadlock from inconsistent lock ordering

```java
// Thread A: acquires lockX, then tries lockY
synchronized (lockX) {
    synchronized (lockY) { ... }
}

// Thread B: acquires lockY, then tries lockX — DEADLOCK
synchronized (lockY) {
    synchronized (lockX) { ... }
}

// Fix: always acquire locks in the same order everywhere
```

### Trap 5: `volatile` on compound operations

```java
// The most dangerous anti-pattern — looks correct, is broken
private volatile int tickets = 100;

public void book() {
    if (tickets > 0) {   // volatile read — fresh value
        tickets--;        // still a race! volatile doesn't help here
    }
}
```

---

## 10. Decision Guide

```
Does more than one thread access this variable?
    └─ No  →  No synchronization needed

    └─ Yes
        └─ Is it a compound operation?
           (read-modify-write, check-then-act, put-if-absent)
               └─ Yes  →  synchronized block/method  OR  AtomicInteger/AtomicReference

               └─ No (single write, multiple reads — e.g. a flag)
                   └─ Does correctness depend on OTHER variables also being fresh?
                          └─ Yes  →  synchronized (full memory barrier covers all)
                          └─ No   →  volatile is sufficient
```

### Quick reference

| Scenario | Tool |
|---|---|
| Shutdown/stop flag (one writer) | `volatile boolean` |
| Simple published reference | `volatile MyObject` |
| Counter incremented by multiple threads | `AtomicInteger` or `synchronized` |
| Check-then-act (if inventory > 0, decrement) | `synchronized` |
| Two related variables updated together | `synchronized` (atomically covers both) |
| High-contention, complex locking | `ReentrantLock` (Phase 4 — next topic) |

---

## 11. Key Takeaways

1. **Race condition ≠ visibility problem.** They are two distinct bugs requiring different fixes. Confusing them leads to applying the wrong tool.

2. **`volatile` ≠ thread-safe.** It only solves visibility. It cannot make a compound operation atomic.

3. **`counter++` is not atomic.** It is read → add → write — three steps that any other thread can interleave into.

4. **`synchronized` gives both guarantees.** Mutual exclusion (only one thread in the block) *and* visibility (full memory barrier on entry/exit).

5. **Synchronize reads too.** If a write is synchronized, the corresponding read must be synchronized on the same lock — otherwise you can still read stale data.

6. **Fine-grained locks reduce contention** by letting unrelated operations run concurrently. But more locks = higher deadlock risk. Always acquire multiple locks in a consistent order.

7. **Happens-before is the formal model.** `volatile` creates happens-before for one variable. `synchronized` creates happens-before for everything inside the block.

8. **Prefer `AtomicInteger` over `synchronized` for simple counters.** It uses hardware CAS (compare-and-swap) instructions — faster than a lock, and expresses intent more clearly.

---

*Next: Phase 4 — `ReentrantLock`, `Condition`, `wait()`/`notify()`, and the `java.util.concurrent` toolkit.*

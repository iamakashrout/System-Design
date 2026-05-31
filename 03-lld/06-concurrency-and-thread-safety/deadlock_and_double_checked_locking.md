# Concurrency — Deadlock and Double-Checked Locking

> **Phase context:** This is the fourth chapter of Phase 4 (Concurrency & Thread Safety). It covers the most dangerous concurrency failure mode (deadlock), its prevention strategies, and a deep-dive into the double-checked locking pattern — connecting `volatile`, instruction reordering, and the Java Memory Model into one concrete example.

---

## Table of Contents

1. [What is a Deadlock?](#1-what-is-a-deadlock)
2. [The Four Necessary Conditions](#2-the-four-necessary-conditions)
3. [Why the Broken Code Deadlocks — Step by Step](#3-why-the-broken-code-deadlocks--step-by-step)
4. [Prevention Strategy 1 — Consistent Lock Ordering](#4-prevention-strategy-1--consistent-lock-ordering)
5. [Prevention Strategy 2 — tryLock with Timeout and Backoff](#5-prevention-strategy-2--trylock-with-timeout-and-backoff)
6. [Livelock — the Related Trap](#6-livelock--the-related-trap)
7. [Detecting Deadlocks That Already Exist](#7-detecting-deadlocks-that-already-exist)
8. [Double-Checked Locking — the Pattern](#8-double-checked-locking--the-pattern)
9. [What new MyObject() Actually Does](#9-what-new-myobject-actually-does)
10. [Why volatile is Non-Negotiable](#10-why-volatile-is-non-negotiable)
11. [Walking Through the Correct DCL Code](#11-walking-through-the-correct-dcl-code)
12. [Two Alternatives That Avoid DCL Complexity](#12-two-alternatives-that-avoid-dcl-complexity)
13. [When DCL is Still the Right Pattern](#13-when-dcl-is-still-the-right-pattern)
14. [Key Takeaways](#14-key-takeaways)

---

## 1. What is a Deadlock?

A deadlock occurs when two or more threads each hold a resource the other needs, and none will release what they hold until they get what they need. The result: everyone waits forever. Nothing ever proceeds.

### The workshop analogy

Alice and Bob are assembling furniture. The workshop has two tools: a hammer and a screwdriver. Each piece needs both tools.

- Alice picks up the hammer. Bob picks up the screwdriver.
- Alice waits for the screwdriver. Bob waits for the hammer.
- Neither will put their tool down until they have both.
- They wait forever.

In Java: Alice = Thread 1, Bob = Thread 2, hammer = `lockA`, screwdriver = `lockB`.

### Classic code

```java
public class DeadlockDemo {
    private final Object lockA = new Object();
    private final Object lockB = new Object();

    public void thread1Work() {
        synchronized (lockA) {              // T1 acquires A
            try { Thread.sleep(50); } catch (InterruptedException e) {}
            synchronized (lockB) { }        // T1 waits for B — BLOCKED
        }
    }

    public void thread2Work() {
        synchronized (lockB) {              // T2 acquires B
            try { Thread.sleep(50); } catch (InterruptedException e) {}
            synchronized (lockA) { }        // T2 waits for A — DEADLOCK
        }
    }
}
```

---

## 2. The Four Necessary Conditions

Deadlock requires **all four** conditions simultaneously. Remove any one and deadlock becomes impossible.

| Condition | What it means | How to break it |
|---|---|---|
| **Mutual exclusion** | A resource can only be held by one thread at a time | Cannot break for locks — mutually exclusive by definition |
| **Hold and wait** | A thread holding a resource waits for another | **`tryLock` strategy** — release all if you can't get all |
| **No preemption** | A held resource cannot be forcibly taken away | Cannot break for locks — must release voluntarily |
| **Circular wait** | A cycle exists: T1 waits for T2, T2 waits for T1 | **Consistent lock ordering** — eliminates any possible cycle |

The two practical prevention strategies each attack one of the breakable conditions.

---

## 3. Why the Broken Code Deadlocks — Step by Step

```
T0: Thread 1 starts — tries to acquire lockA
T1: Thread 1 acquires lockA             holds: [A]
T2: Thread 2 starts — tries to acquire lockB
T3: Thread 2 acquires lockB             holds: [B]
T4: Thread 1 tries lockB  → BLOCKED (Thread 2 holds it)
T5: Thread 2 tries lockA  → BLOCKED (Thread 1 holds it)

    Thread 1 waits for Thread 2 to release B
    Thread 2 waits for Thread 1 to release A
    Neither releases what they hold
    → DEADLOCK — both wait forever
```

The `Thread.sleep(50)` inside the `synchronized` block is the trigger — it gives the other thread enough time to acquire its own first lock before either thread tries to acquire the second. Without the sleep, one thread might complete before the other starts. This is why deadlocks are timing-sensitive: they may never appear in single-threaded testing and only surface under production concurrency.

---

## 4. Prevention Strategy 1 — Consistent Lock Ordering

**Attacks: circular wait** — the fourth condition.

If every thread always acquires locks in the same global order, a cycle can never form. Thread 1 and Thread 2 both need A and B — if both always acquire A before B, neither can hold B while waiting for A.

### Bank transfer example

```java
public void safeTransfer(BankAccount from, BankAccount to, double amount) {
    // Impose a canonical ordering by account ID
    // BOTH transfer(A→B) and transfer(B→A) will now acquire in order A, then B
    BankAccount first  = from.getId().compareTo(to.getId()) < 0 ? from : to;
    BankAccount second = from.getId().compareTo(to.getId()) < 0 ? to   : from;

    synchronized (first) {
        synchronized (second) {
            from.debit(amount);
            to.credit(amount);
        }
    }
}
```

The ordering is on the lock objects (by account ID), not on the roles (source vs destination). A cycle cannot form because there is no combination of calls that produces: "Thread 1 holds A waiting for B, Thread 2 holds B waiting for A." Both threads always go for A first.

### The rule for real systems

Assign a canonical identifier to every lock object. When acquiring multiple locks, sort them by identifier and always acquire in sorted order. Document this contract prominently — it only works if every caller follows it.

---

## 5. Prevention Strategy 2 — `tryLock` with Timeout and Backoff

**Attacks: hold and wait** — the second condition.

Instead of blocking indefinitely waiting for the second lock while holding the first, `tryLock` lets you check: "Can I get both? If not, put everything down and retry."

```java
public boolean safeTransfer(BankAccount from, BankAccount to, double amount)
        throws InterruptedException {
    while (true) {
        if (from.getLock().tryLock()) {
            try {
                if (to.getLock().tryLock()) {
                    try {
                        from.debit(amount);
                        to.credit(amount);
                        return true;                  // SUCCESS: held both, did work
                    } finally {
                        to.getLock().unlock();         // release second lock
                    }
                }
                // Could not get second lock — fall through to retry
            } finally {
                from.getLock().unlock();               // ALWAYS release first lock
            }
        }
        // Neither or only one lock acquired — backoff and retry
        Thread.sleep(1); // small pause breaks retry synchrony (prevents livelock)
    }
}
```

A thread either holds both locks or holds none. The "hold and wait" condition is eliminated.

### Choosing between the two strategies

| | Lock ordering | `tryLock` backoff |
|---|---|---|
| Simplicity | Simpler — set the rule once | More complex — retry loop needed |
| Performance | Better — no retries ever | Lower — may retry many times |
| Applicability | Requires control of all acquisition sites | Works without global ordering |
| Deadlock guarantee | Absolute — structural impossibility | Probabilistic — relies on backoff |
| Use when | You control all lock acquisition code | Ordering is impossible or impractical |

Lock ordering is almost always the better default. `tryLock` is the escape hatch for integrating with code you don't control.

---

## 6. Livelock — the Related Trap

Livelock is deadlock's subtle cousin. Both threads are running — they haven't blocked — but neither makes progress because each keeps reacting to the other's moves.

```
Thread A: tries locks → fails (B holds) → backs off → retries
Thread B: tries locks → fails (A holds) → backs off → retries
Both back off at the same time → both retry at the same time → both fail again...
```

The fix: **randomized backoff**. Add a random component to the sleep time so threads desynchronize:

```java
// Instead of fixed Thread.sleep(1):
Thread.sleep(1 + ThreadLocalRandom.current().nextInt(5)); // 1–5ms random
```

---

## 7. Detecting Deadlocks That Already Exist

### `ThreadMXBean` — programmatic detection

```java
ThreadMXBean bean = ManagementFactory.getThreadMXBean();
long[] deadlockedIds = bean.findDeadlockedThreads();

if (deadlockedIds != null) {
    ThreadInfo[] infos = bean.getThreadInfo(deadlockedIds, true, true);
    for (ThreadInfo info : infos) {
        System.out.println("Deadlocked thread: " + info.getThreadName());
        System.out.println("  Waiting for lock: " + info.getLockName());
        System.out.println("  Lock held by:     " + info.getLockOwnerName());
    }
}
```

### Thread dump

`kill -3 <pid>` on Linux (or `jstack <pid>`) produces a full thread dump. Look for threads in `BLOCKED` state and `"waiting to lock"` messages. The JVM prints the cycle explicitly when a deadlock is present.

Every on-call engineer should know how to read a thread dump — it immediately shows which threads are deadlocked and which objects are involved.

---

## 8. Double-Checked Locking — the Pattern

Double-checked locking (DCL) is a pattern for lazy initialization that avoids acquiring a lock on every access once initialization is complete.

### The motivation

A `ConnectionPool` that opens 10 DB connections is expensive to create. You only want to create it once (Singleton) and only when it's first needed (lazy). The naive thread-safe approach — synchronizing `getInstance()` — acquires a lock on every call forever, even after the pool is fully constructed:

```java
// SLOW: every call acquires the lock, even after construction
public static synchronized ConnectionPool getInstance() {
    if (instance == null) instance = new ConnectionPool();
    return instance;
}
```

DCL optimizes this: check first without the lock (fast path for the common case), and only synchronize during the brief construction window.

### The pattern (with volatile — correct)

```java
public class ConnectionPool {
    private static volatile ConnectionPool instance; // volatile is non-negotiable

    private ConnectionPool() {
        // open DB connections, initialize state
    }

    public static ConnectionPool getInstance() {
        if (instance == null) {                     // ① fast path — no lock
            synchronized (ConnectionPool.class) {
                if (instance == null) {             // ② re-check inside lock
                    instance = new ConnectionPool(); // ③ volatile write
                }
            }
        }
        return instance;
    }
}
```

---

## 9. What `new MyObject()` Actually Does

In Java source code, `instance = new ConnectionPool()` looks like one line. At the bytecode and machine instruction level it is **three separate operations**:

```
Step 1: ALLOCATE  reserve memory for the object on the heap
Step 2: INIT      run the constructor — set all fields, open connections
Step 3: ASSIGN    write the completed object reference to 'instance'
```

Correct order: 1 → 2 → 3. Any reader seeing the assignment after step 3 gets a fully-initialized object.

### The reordering hazard

The JVM's JIT compiler is allowed to reorder instructions as long as the result is *equivalent within a single thread*. Within Thread A, swapping steps 2 and 3 doesn't change Thread A's behavior — it still ends up with a fully-constructed pool. But it completely breaks Thread B:

```
JVM reorders to: 1 → 3 → 2  (perfectly legal without volatile)

Thread A: ALLOCATE memory
Thread A: ASSIGN reference to 'instance'     ← instance is now non-null
                                              ← Thread B reads here
Thread A: INIT constructor (still running!)  ← connections not yet created

Thread B: reads instance != null             ← passes the first check
Thread B: returns the non-null reference
Thread B: calls getConnection() on a half-built object
          connections list may be null → NullPointerException
```

Thread B got a non-null reference to an object whose constructor hasn't finished. This is undefined behavior.

---

## 10. Why `volatile` is Non-Negotiable

`volatile` prevents the reordering of steps 2 and 3 by inserting **memory barriers** around the write:

- **StoreStore barrier** before the write: all preceding writes (the constructor's field assignments) must complete and be visible before the volatile write proceeds.
- **StoreLoad barrier** after the write: the write is immediately flushed to main memory and visible to all threads.

Result: Thread A's ASSIGN to `instance` can never be observed by any other thread before Thread A's INIT is complete. Thread B sees either `null` (not yet constructed) or a fully-initialized object — nothing in between.

### Why the double-check alone isn't enough

The `synchronized` block prevents two threads from constructing the pool simultaneously. But the first check — the fast-path read — is *outside* the lock. `synchronized` only establishes visibility when threads use the same lock. A thread reading `instance` without acquiring any lock gets no visibility guarantee. The read can still observe the reordered, partially-constructed state.

`volatile` covers the fast-path readers: a volatile write happens-before every subsequent volatile read of the same variable, giving all threads — including those that never acquire the lock — a guarantee that what they read is fully constructed.

---

## 11. Walking Through the Correct DCL Code

```java
public static ConnectionPool getInstance() {
    if (instance == null) {                    // ① volatile read — fast path
        synchronized (ConnectionPool.class) {  // ② lock acquired
            if (instance == null) {            // ③ re-check inside lock
                instance = new ConnectionPool(); // ④ volatile write + barrier
            }
        }                                       // ⑤ lock released
    }
    return instance;                            // ⑥ return fully-built object
}
```

| Step | What it does | Why it's there |
|---|---|---|
| ① | Read `instance` (volatile — no lock) | Fast path: return immediately if already constructed |
| ② | Acquire class-level lock | Prevent two threads from constructing simultaneously |
| ③ | Re-check `instance` inside lock | Another thread may have constructed between ① and ② |
| ④ | Construct + volatile write | Constructor finishes, barrier fires, assignment visible to all |
| ⑤ | Lock released | Other threads can now enter if needed |
| ⑥ | Return | Safe — either just constructed or previously constructed |

### Why the second check (③) is essential

Without it: two threads both pass the outer check (①), both see null, both enter the `synchronized` block. The first constructs and assigns. The second also constructs and assigns — overwriting the first pool. All connections from the first pool are leaked. The second null-check inside the lock prevents this: only the first thread through finds null, the second sees non-null and skips construction.

---

## 12. Two Alternatives That Avoid DCL Complexity

For the common Singleton case, two idioms are simpler, equally correct, and preferred.

### Enum Singleton

```java
public enum ConnectionPool {
    INSTANCE;

    private final List<Connection> connections = new ArrayList<>();

    ConnectionPool() {
        for (int i = 0; i < 10; i++) connections.add(new Connection("conn-" + i));
    }

    public static ConnectionPool getInstance() { return INSTANCE; }
}
```

The JVM initializes enums exactly once in a class-loading critical section — inherently thread-safe. No `volatile`, no `synchronized`, no double-check. Joshua Bloch recommends this as the best Singleton implementation in *Effective Java*.

### Static inner class (initialization-on-demand holder)

```java
public class ConnectionPool {
    private ConnectionPool() { /* init connections */ }

    // Holder is not loaded until getInstance() is first called
    private static class Holder {
        static final ConnectionPool INSTANCE = new ConnectionPool();
        // JVM class loading is thread-safe — single initialization guaranteed
    }

    public static ConnectionPool getInstance() {
        return Holder.INSTANCE; // triggers Holder class load on first call only
    }
}
```

The JVM guarantees a class is initialized exactly once under a class-loading lock, before any thread can access its static fields. Lazy initialization with no explicit synchronization, no `volatile`. Thread-safe by construction.

### Choosing between the three approaches

| Approach | Lazy? | Thread-safe? | Simplicity | Use when |
|---|---|---|---|---|
| DCL with `volatile` | Yes | Yes | Medium | Lazy init, runtime-dependent state |
| Enum Singleton | No (class load) | Yes | Highest | Simple singletons, serialization safety |
| Static inner class | Yes | Yes | High | Lazy singletons, no runtime state needed |

---

## 13. When DCL is Still the Right Pattern

The enum and holder patterns work for Singletons. DCL extends to any **lazily-initialized cached value** where initialization depends on runtime state that static fields can't capture:

```java
public class UserProfileCache {
    private volatile Map<String, UserProfile> cache;  // volatile — non-negotiable

    public Map<String, UserProfile> getCache() {
        if (cache == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = buildExpensiveCache(); // may query DB, take seconds
                }
            }
        }
        return cache;
    }
}
```

Here a static holder doesn't work because `buildExpensiveCache()` may need instance state or runtime configuration. DCL with `volatile` is the correct pattern.

**Rule:** Use DCL with `volatile` whenever you need lazy initialization of a value that:
- Is expensive to create
- Should be created at most once
- Is read far more often than it's written
- Cannot use a static holder due to runtime dependencies

---

## 14. Key Takeaways

### Deadlock

1. **Deadlock requires all four conditions.** Remove any one and deadlock becomes structurally impossible. The two you can attack in software are circular wait (lock ordering) and hold-and-wait (tryLock).

2. **Consistent lock ordering is the simplest, most reliable prevention.** Assign a canonical order to all locks. Every thread acquires them in that order, everywhere. A cycle can never form.

3. **`tryLock` with backoff eliminates hold-and-wait.** A thread either acquires all needed locks or releases all and retries. Add random jitter to the sleep to prevent livelock.

4. **Deadlocks are timing-sensitive.** They appear under production concurrency, not single-threaded testing. Thread dumps and `ThreadMXBean` are the diagnostic tools.

5. **Livelock is not deadlock.** Both threads are running but neither progresses. Random backoff breaks the synchrony.

### Double-Checked Locking

6. **`new MyObject()` is three steps: allocate, init, assign.** The JVM is allowed to reorder init and assign. Without protection, other threads can see a non-null reference to a half-constructed object.

7. **`volatile` prevents the reordering.** StoreStore and StoreLoad barriers ensure the constructor finishes before the assignment becomes visible to any thread. Thread B sees null or a complete object — never something in between.

8. **The second null-check is essential.** Without it, two threads that both see null on the fast path will both construct the object. One overwrites the other, leaking all resources from the first.

9. **For Singletons, prefer enum or static inner class holder.** Simpler, equally thread-safe, no `volatile` required. Use DCL for lazy-initialized non-singleton cached values where runtime state is involved.

10. **`volatile` without double-check = correct but slow.** `synchronized` without `volatile` = incorrect (fast-path readers get no visibility guarantee). Both `volatile` and the double-check structure are required for the pattern to be both correct and fast.

---

*Next: Phase 5 — Classic LLD interview problems: Parking Lot, Library Management, ATM, and more.*

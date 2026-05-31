import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deadlock Prevention and Double-Checked Locking
 *
 * Demonstrates all core concepts from Topics 8 and 9:
 *
 * Deadlock:
 *   - Classic deadlock: two threads, two locks, opposite acquisition order
 *   - Why the sleep() triggers it: gives both threads time to acquire their first lock
 *   - Prevention 1: consistent lock ordering (eliminates circular wait)
 *   - Prevention 2: tryLock with backoff (eliminates hold-and-wait)
 *   - Livelock: running but making no progress — randomized backoff as fix
 *   - ThreadMXBean: programmatic deadlock detection
 *
 * Double-Checked Locking:
 *   - Why new Object() is three steps: allocate, init, assign
 *   - Why JVM reordering breaks DCL without volatile
 *   - Correct DCL with volatile: ConnectionPool
 *   - Why the second null-check inside the lock is essential
 *   - Alternative 1: Enum Singleton (simplest, serialization-safe)
 *   - Alternative 2: Static inner class holder (lazy, no explicit sync)
 *   - DCL for non-singleton lazy init: UserProfileCache
 *
 * Domain: bank accounts, connection pools, user caches
 */
public class DeadlockAndDoubleCheckedLocking {

    // =========================================================================
    // SECTION 1: Classic Deadlock — what it looks like
    // =========================================================================

    /**
     * ClassicDeadlock shows the textbook deadlock scenario.
     *
     * Thread 1: acquires lockA, then tries lockB
     * Thread 2: acquires lockB, then tries lockA
     *
     * If both threads acquire their first lock before either tries the second,
     * they block each other forever:
     *   T1 holds A, waits for B
     *   T2 holds B, waits for A → DEADLOCK
     *
     * The Thread.sleep(50) deliberately creates the overlap window.
     * Without it, one thread might complete before the other starts.
     * This is why deadlocks are timing-sensitive and hard to reproduce in tests.
     *
     * NOTE: This class is intentionally NOT run in main() — it would hang the JVM.
     * It's here to show the pattern. The detector in Section 5 can find it.
     */
    static class ClassicDeadlock {
        private final Object lockA = new Object();
        private final Object lockB = new Object();

        // Thread 1 acquires A then B
        public void thread1Work() {
            synchronized (lockA) {
                System.out.println("  [T1] holds lockA, waiting for lockB...");
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (lockB) {
                    // Thread 2 is already holding lockB — we never get here
                    System.out.println("  [T1] holds both locks");
                }
            }
        }

        // Thread 2 acquires B then A — OPPOSITE ORDER = deadlock
        public void thread2Work() {
            synchronized (lockB) {
                System.out.println("  [T2] holds lockB, waiting for lockA...");
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (lockA) {
                    // Thread 1 is already holding lockA — we never get here
                    System.out.println("  [T2] holds both locks");
                }
            }
        }
    }

    // =========================================================================
    // SECTION 2: BankAccount — domain object with a ReentrantLock per account
    // =========================================================================

    /**
     * BankAccount is used by both prevention strategies below.
     * Each account has its own ReentrantLock so we can use tryLock().
     * The accountId provides the canonical ordering needed by Strategy 1.
     */
    static class BankAccount {
        private final String accountId;
        private double balance;
        private final ReentrantLock lock = new ReentrantLock();

        BankAccount(String accountId, double initialBalance) {
            this.accountId = accountId;
            this.balance   = initialBalance;
        }

        // Called only while the caller holds this account's lock
        void debit(double amount)  { balance -= amount; }
        void credit(double amount) { balance += amount; }

        double getBalance()    { return balance; }
        String getAccountId()  { return accountId; }
        ReentrantLock getLock(){ return lock; }

        @Override public String toString() {
            return accountId + "(balance=" + String.format("%.0f", balance) + ")";
        }
    }

    // =========================================================================
    // SECTION 3: Prevention Strategy 1 — Consistent Lock Ordering
    // =========================================================================

    /**
     * SafeTransferOrdering prevents deadlock by ALWAYS acquiring account locks
     * in a canonical order determined by accountId, regardless of which account
     * is source and which is destination.
     *
     * WHY this works:
     *   Deadlock requires a circular wait: T1 holds A waiting for B, while
     *   T2 holds B waiting for A. Consistent ordering makes this impossible:
     *   both T1 (doing A→B) and T2 (doing B→A) will try to acquire the
     *   SAME lock first (whichever has the smaller ID). The one who gets it
     *   proceeds; the other waits. No cycle can ever form.
     *
     * RULE: Assign every lock a canonical rank. Always acquire in rank order.
     *       Document this as a system-wide invariant.
     */
    static class SafeTransferOrdering {

        public void transfer(BankAccount from, BankAccount to, double amount) {
            // Impose a total ordering on the two accounts by ID.
            // "first" is always the account with the lexicographically smaller ID.
            // This ordering is consistent regardless of which is 'from' and which is 'to'.
            BankAccount first  = from.getAccountId().compareTo(to.getAccountId()) < 0
                    ? from : to;
            BankAccount second = from.getAccountId().compareTo(to.getAccountId()) < 0
                    ? to : from;

            // Both transfer(A→B) and transfer(B→A) now acquire in the SAME order.
            // A cycle cannot form: every thread requests locks in the same sequence.
            synchronized (first) {
                synchronized (second) {
                    if (from.getBalance() < amount) {
                        System.out.printf("  [Ordering] Transfer %.0f: insufficient funds in %s%n",
                                amount, from.getAccountId());
                        return;
                    }
                    from.debit(amount);
                    to.credit(amount);
                    System.out.printf("  [Ordering] Transferred %.0f: %s → %s%n",
                            amount, from, to);
                }
            }
        }
    }

    // =========================================================================
    // SECTION 4: Prevention Strategy 2 — tryLock with Timeout and Backoff
    // =========================================================================

    /**
     * SafeTransferTryLock prevents deadlock by eliminating the "hold and wait"
     * condition: a thread either acquires BOTH locks or releases ALL and retries.
     * No thread ever sits holding one lock indefinitely waiting for another.
     *
     * WHY this works:
     *   Deadlock needs: hold lock A AND wait for lock B forever.
     *   tryLock makes the wait non-blocking: if we can't get B, we release A
     *   immediately and try the whole sequence again. Both threads may fail on
     *   the same attempt, but one of them will eventually succeed first.
     *
     * WHY Thread.sleep with random jitter:
     *   Without jitter: both threads back off for exactly the same duration,
     *   retry at exactly the same moment, and fail again in perfect lockstep.
     *   This is LIVELOCK — running, but no progress. Random jitter desynchronizes
     *   the retry windows so one thread gets a head start.
     *
     * The double-try structure is non-negotiable:
     *   Outer try:  handles InterruptedException from tryLock
     *   Inner try:  ensures we only unlock what we actually acquired
     */
    static class SafeTransferTryLock {
        private static final int MAX_RETRIES = 20;

        public boolean transfer(BankAccount from, BankAccount to, double amount)
                throws InterruptedException {

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                // Try to acquire 'from' lock — non-blocking, returns immediately
                if (from.getLock().tryLock()) {
                    try {
                        // Got 'from' lock. Now try 'to' lock — also non-blocking.
                        if (to.getLock().tryLock()) {
                            try {
                                // SUCCESS: hold both locks. Safe to transfer.
                                if (from.getBalance() < amount) {
                                    System.out.printf(
                                        "  [tryLock attempt %d] insufficient funds in %s%n",
                                        attempt, from.getAccountId());
                                    return false;
                                }
                                from.debit(amount);
                                to.credit(amount);
                                System.out.printf(
                                    "  [tryLock attempt %d] Transferred %.0f: %s → %s%n",
                                    attempt, amount, from, to);
                                return true;
                            } finally {
                                to.getLock().unlock(); // release second lock
                            }
                        }
                        // Could not get 'to' lock. Fall through to release 'from'.
                        System.out.printf(
                            "  [tryLock attempt %d] could not acquire both — retrying%n",
                            attempt);
                    } finally {
                        from.getLock().unlock(); // ALWAYS release 'from' before retry
                    }
                }

                // Random backoff: 1–5ms. Randomness breaks retry synchrony with
                // competing threads, preventing livelock.
                Thread.sleep(1 + ThreadLocalRandom.current().nextInt(5));
            }

            System.out.println("  [tryLock] transfer failed after " + MAX_RETRIES + " retries");
            return false;
        }
    }

    // =========================================================================
    // SECTION 5: ThreadMXBean — detecting deadlocks programmatically
    // =========================================================================

    /**
     * DeadlockDetector uses the JVM's built-in monitoring API to find
     * threads that are deadlocked (waiting in a cycle for each other's locks).
     *
     * In production: run this periodically in a background thread (e.g. every 30s).
     * On a positive result: log the thread info and raise an alert.
     * This is the same data you get from a thread dump (kill -3 or jstack).
     */
    static class DeadlockDetector {

        public static String detectDeadlocks() {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();

            // findDeadlockedThreads() returns thread IDs involved in a monitor deadlock.
            // findMonitorDeadlockedThreads() only covers synchronized blocks.
            // findDeadlockedThreads() also covers ReentrantLock deadlocks.
            long[] deadlockedIds = bean.findDeadlockedThreads();

            if (deadlockedIds == null) {
                return "  No deadlocks detected.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("  DEADLOCK DETECTED — ").append(deadlockedIds.length)
              .append(" threads involved:\n");

            // getThreadInfo with true, true = include lock info + stack trace
            ThreadInfo[] infos = bean.getThreadInfo(deadlockedIds, true, true);
            for (ThreadInfo info : infos) {
                sb.append("  Thread: ").append(info.getThreadName())
                  .append(" [").append(info.getThreadState()).append("]\n");
                sb.append("    Waiting for lock: ").append(info.getLockName()).append("\n");
                sb.append("    Lock held by:     ").append(info.getLockOwnerName()).append("\n");
            }
            return sb.toString();
        }
    }

    // =========================================================================
    // SECTION 6: Broken DCL — WHY volatile is non-negotiable
    // =========================================================================

    /**
     * BrokenConnectionPool shows DCL WITHOUT volatile — the broken version.
     *
     * new ConnectionPool() compiles to three CPU operations:
     *   Step 1: ALLOCATE  — reserve heap memory for the object
     *   Step 2: INIT      — run the constructor, open DB connections
     *   Step 3: ASSIGN    — write the reference to 'instance'
     *
     * The JVM's JIT compiler is allowed to reorder Step 2 and Step 3 as long
     * as the result is equivalent within a SINGLE thread. Within Thread A,
     * the pool is still fully constructed either way. But for Thread B:
     *
     *   Thread A: ALLOCATE → ASSIGN (instance is now non-null!) → INIT (still running)
     *                                 ↑ Thread B reads here
     *   Thread B: instance != null → skips synchronized block
     *   Thread B: calls getInstance() → gets reference to half-built object
     *   Thread B: connections list is null → NullPointerException
     *
     * This is a real production bug. It is extremely rare (timing-dependent)
     * and almost impossible to reproduce in tests. It can surface after a JIT
     * compilation tier change under high load.
     *
     * NOTE: Do not use this class. It is here only to show the broken pattern.
     */
    static class BrokenConnectionPool {
        // BROKEN: no volatile — JVM may reorder the write to this field
        private static BrokenConnectionPool instance;

        private final List<String> connections = new ArrayList<>();

        private BrokenConnectionPool() {
            // Simulates opening 10 DB connections (expensive)
            for (int i = 0; i < 10; i++) {
                connections.add("conn-" + i);
            }
        }

        // BROKEN DCL — Thread B can receive a non-null but half-initialized object
        public static BrokenConnectionPool getInstance() {
            if (instance == null) {                     // no volatile guarantee
                synchronized (BrokenConnectionPool.class) {
                    if (instance == null) {
                        instance = new BrokenConnectionPool(); // may be reordered!
                    }
                }
            }
            return instance; // may return partially constructed object!
        }
    }

    // =========================================================================
    // SECTION 7: Correct DCL — ConnectionPool with volatile
    // =========================================================================

    /**
     * ConnectionPool is the correct double-checked locking implementation.
     *
     * volatile on 'instance' inserts TWO memory barriers around the write:
     *
     *   StoreStore barrier (before write):
     *     All writes that precede the volatile write (i.e. the constructor)
     *     MUST complete and be flushed before the volatile write proceeds.
     *     The JVM CANNOT reorder INIT after ASSIGN.
     *
     *   StoreLoad barrier (after write):
     *     The volatile write is immediately flushed to main memory and
     *     invalidates other threads' caches. Thread B reading 'instance'
     *     after this point is guaranteed to see a fully-initialized object.
     *
     * Result: Thread B sees either null (not built yet) or a complete object.
     * The partially-initialized state is structurally impossible.
     *
     * WHY the second null-check (inside the synchronized block) is essential:
     *   Without it: Thread A and Thread B both see null on the first check,
     *   both enter the synchronized block (one at a time), and BOTH construct
     *   a pool. Thread B overwrites Thread A's pool. All of Thread A's
     *   connections are leaked forever.
     *   The inner check ensures only the FIRST thread through actually constructs.
     */
    static class ConnectionPool {

        // volatile: prevents reordering of ASSIGN before INIT
        // This is the one field where volatile is not optional
        private static volatile ConnectionPool instance;

        private final List<String> connections;
        private final String poolName;

        private ConnectionPool(String name) {
            this.poolName   = name;
            this.connections = new ArrayList<>();
            // Simulate opening 10 database connections (slow, expensive operation)
            for (int i = 0; i < 10; i++) {
                connections.add(name + "-conn-" + i);
            }
            System.out.println("  [ConnectionPool] Created '" + name
                    + "' with " + connections.size() + " connections"
                    + " on thread: " + Thread.currentThread().getName());
        }

        public static ConnectionPool getInstance() {
            // FIRST CHECK: fast path — no lock acquired after construction
            // volatile read: always reads from main memory, never from CPU cache
            if (instance == null) {

                // LOCK: only one thread constructs the pool
                synchronized (ConnectionPool.class) {

                    // SECOND CHECK: essential — another thread may have constructed
                    // the pool between our first check and our lock acquisition
                    if (instance == null) {

                        // volatile WRITE: StoreStore barrier before this line ensures
                        // the constructor COMPLETES before instance becomes non-null
                        instance = new ConnectionPool("MainPool");
                    }
                }
            }
            // Safe: instance is either null-checked-then-constructed, or
            // previously constructed by an earlier caller
            return instance;
        }

        public String getConnection() {
            if (connections.isEmpty()) throw new IllegalStateException("No connections");
            return connections.get(0); // simplified — real pool would track in-use connections
        }

        public int getConnectionCount() { return connections.size(); }
        public String getPoolName()     { return poolName; }
    }

    // =========================================================================
    // SECTION 8: Alternative 1 — Enum Singleton (simplest, preferred)
    // =========================================================================

    /**
     * EnumConnectionPool is the simplest and most robust Singleton implementation.
     *
     * WHY enum works:
     *   The JVM initializes enum instances exactly once during class loading,
     *   under a class-loading critical section that no user code can interrupt.
     *   This initialization is inherently thread-safe — guaranteed by the JVM spec.
     *
     * Additional benefits over DCL:
     *   - Serialization-safe: Java serialization never creates a second instance
     *     of an enum (a plain Singleton can be duplicated by deserialization)
     *   - Reflection-safe: reflection cannot break enum's single-instance guarantee
     *   - Zero boilerplate: no volatile, no synchronized, no double-check
     *
     * Preferred by Joshua Bloch (Effective Java, Item 3).
     *
     * Limitation: Not lazy — INSTANCE is created when the class is first loaded,
     * not necessarily when getInstance() is first called. For most cases this
     * doesn't matter; if the constructor is very expensive and lazy init is
     * critical, use the static inner class holder instead.
     */
    enum EnumConnectionPool {
        INSTANCE; // JVM guarantees single initialization, thread-safe, serialization-safe

        private final List<String> connections = new ArrayList<>();

        EnumConnectionPool() {
            for (int i = 0; i < 5; i++) {
                connections.add("enum-conn-" + i);
            }
            System.out.println("  [EnumPool] Initialized with " + connections.size()
                    + " connections on thread: " + Thread.currentThread().getName());
        }

        public static EnumConnectionPool getInstance() { return INSTANCE; }
        public int getConnectionCount() { return connections.size(); }
    }

    // =========================================================================
    // SECTION 9: Alternative 2 — Static Inner Class Holder (lazy + safe)
    // =========================================================================

    /**
     * HolderConnectionPool uses the initialization-on-demand holder idiom.
     *
     * HOW it works:
     *   The JVM does not load the Holder class until the first time
     *   HolderConnectionPool.getInstance() is called. At that point,
     *   class loading initializes Holder.INSTANCE under a class-loading lock —
     *   thread-safe, guaranteed by the JVM specification.
     *
     * WHY it's better than DCL for singletons:
     *   - No volatile required
     *   - No synchronized block in the hot path
     *   - Lazy: the pool is only created when first needed
     *   - JVM provides the thread-safety guarantee — no user-written sync code
     *
     * Limitation: cannot pass runtime parameters to the constructor.
     *   If initialization depends on runtime config (e.g. a pool size read from
     *   a config file), use DCL with volatile instead.
     */
    static class HolderConnectionPool {
        private final List<String> connections;

        private HolderConnectionPool() {
            connections = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                connections.add("holder-conn-" + i);
            }
            System.out.println("  [HolderPool] Initialized with " + connections.size()
                    + " connections on thread: " + Thread.currentThread().getName());
        }

        /**
         * Holder is not loaded until getInstance() references it for the first time.
         * Class loading is thread-safe: the JVM guarantees that static initializers
         * (including static field assignments) run exactly once under a lock.
         * INSTANCE is fully constructed before any thread can call getInstance().
         */
        private static class Holder {
            // This line runs under a class-loading lock — completely thread-safe
            static final HolderConnectionPool INSTANCE = new HolderConnectionPool();
        }

        public static HolderConnectionPool getInstance() {
            return Holder.INSTANCE; // triggers Holder class load on first call only
        }

        public int getConnectionCount() { return connections.size(); }
    }

    // =========================================================================
    // SECTION 10: DCL for non-singleton lazy init — UserProfileCache
    // =========================================================================

    /**
     * UserProfileCache demonstrates DCL beyond the Singleton pattern.
     *
     * The enum and holder patterns only work for Singletons (static context).
     * Here, the cache is an instance field and its initialization depends on
     * a runtime configuration value passed to the constructor — a static
     * holder cannot capture this.
     *
     * DCL with volatile is the correct pattern for:
     *   - Instance-level lazy initialization
     *   - Cases where initialization needs runtime parameters
     *   - Any expensive computation that should happen at most once per instance
     *
     * volatile 'cache' is non-negotiable for the same reasons as the Singleton:
     * the JVM can reorder the cache = buildCache() assignment before the
     * expensive buildCache() call completes, exposing an empty map to other threads.
     */
    static class UserProfileCache {

        // volatile: prevents assigning 'cache' before it's fully populated
        private volatile List<String> cache;
        private final int maxProfiles;

        UserProfileCache(int maxProfiles) {
            this.maxProfiles = maxProfiles;
        }

        /**
         * Lazy initialization: cache is only built on the first call.
         * Subsequent calls return the cached result with zero lock overhead.
         */
        public List<String> getCache() {
            if (cache == null) {                  // fast path — no lock after first build
                synchronized (this) {
                    if (cache == null) {           // re-check: another thread may have built it
                        cache = buildExpensiveCache(); // volatile write — barrier ensures
                                                       // list is fully populated before
                                                       // 'cache' becomes non-null
                    }
                }
            }
            return cache;
        }

        private List<String> buildExpensiveCache() {
            // Simulates a slow operation: DB query, API call, etc.
            System.out.println("  [UserProfileCache] Building cache (expensive operation)...");
            List<String> result = new ArrayList<>();
            for (int i = 0; i < maxProfiles; i++) {
                result.add("user-profile-" + i);
            }
            System.out.println("  [UserProfileCache] Cache built with " + result.size()
                    + " profiles on thread: " + Thread.currentThread().getName());
            return result;
        }

        public int getCacheSize() {
            List<String> c = cache; // local copy to avoid double volatile read
            return c == null ? 0 : c.size();
        }
    }

    // =========================================================================
    // MAIN — run all demos
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=".repeat(65));
        System.out.println("DEADLOCK PREVENTION AND DOUBLE-CHECKED LOCKING DEMO");
        System.out.println("=".repeat(65));

        // ------------------------------------------------------------------ //
        // Demo 1: Consistent lock ordering — concurrent transfers, no deadlock //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 1: Lock ordering — concurrent bidirectional transfers ---");

        BankAccount alice = new BankAccount("ACC-ALICE", 1000);
        BankAccount bob   = new BankAccount("ACC-BOB",   1000);
        SafeTransferOrdering orderingService = new SafeTransferOrdering();

        // Simulate two threads doing transfers in opposite directions simultaneously.
        // Without ordering: this would deadlock. With ordering: runs cleanly.
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                orderingService.transfer(alice, bob, 50);
            }
        }, "alice-to-bob");

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                orderingService.transfer(bob, alice, 30);
            }
        }, "bob-to-alice");

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.printf("  Alice: %.0f | Bob: %.0f | Total: %.0f (expected 2000)%n",
                alice.getBalance(), bob.getBalance(),
                alice.getBalance() + bob.getBalance());

        // ------------------------------------------------------------------ //
        // Demo 2: tryLock with backoff — deadlock-free without ordering       //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 2: tryLock backoff — deadlock-free without global ordering ---");

        BankAccount carol = new BankAccount("ACC-CAROL", 2000);
        BankAccount dave  = new BankAccount("ACC-DAVE",  2000);
        SafeTransferTryLock tryLockService = new SafeTransferTryLock();

        Thread t3 = new Thread(() -> {
            try {
                tryLockService.transfer(carol, dave, 200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "carol-to-dave");

        Thread t4 = new Thread(() -> {
            try {
                tryLockService.transfer(dave, carol, 150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "dave-to-carol");

        t3.start();
        t4.start();
        t3.join();
        t4.join();

        System.out.printf("  Carol: %.0f | Dave: %.0f | Total: %.0f (expected 4000)%n",
                carol.getBalance(), dave.getBalance(),
                carol.getBalance() + dave.getBalance());

        // ------------------------------------------------------------------ //
        // Demo 3: ThreadMXBean — no deadlocks present now (expected output)   //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 3: ThreadMXBean deadlock detection (no deadlock present) ---");
        System.out.println(DeadlockDetector.detectDeadlocks());

        // ------------------------------------------------------------------ //
        // Demo 4: Correct DCL — ConnectionPool (volatile Singleton)           //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 4: Correct DCL — ConnectionPool with volatile ---");
        System.out.println("  Requesting pool from 5 concurrent threads...");

        // Reset for demo (in production code, Singletons are never reset)
        List<Thread> poolThreads = new ArrayList<>();
        List<ConnectionPool> retrievedPools = new ArrayList<>();
        Object listLock = new Object();

        for (int i = 0; i < 5; i++) {
            poolThreads.add(new Thread(() -> {
                ConnectionPool pool = ConnectionPool.getInstance();
                synchronized (listLock) {
                    retrievedPools.add(pool);
                }
            }, "pool-requester-" + i));
        }
        poolThreads.forEach(Thread::start);
        for (Thread t : poolThreads) t.join();

        // Every thread must have received the SAME instance
        boolean allSame = retrievedPools.stream().allMatch(p -> p == retrievedPools.get(0));
        System.out.println("  All threads got the same instance: " + allSame + " (must be true)");
        System.out.println("  Constructor called exactly once (see log above — should appear once)");
        System.out.println("  Pool connections: " + ConnectionPool.getInstance().getConnectionCount());

        // ------------------------------------------------------------------ //
        // Demo 5: Enum Singleton                                               //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 5: Enum Singleton (simplest, preferred for singletons) ---");

        EnumConnectionPool ep1 = EnumConnectionPool.getInstance();
        EnumConnectionPool ep2 = EnumConnectionPool.getInstance();
        System.out.println("  Same instance: " + (ep1 == ep2) + " (must be true)");
        System.out.println("  Connections: " + ep1.getConnectionCount());
        System.out.println("  No volatile, no synchronized, no double-check needed");

        // ------------------------------------------------------------------ //
        // Demo 6: Static inner class holder                                    //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 6: Static inner class holder (lazy + no explicit sync) ---");

        System.out.println("  Requesting from 4 concurrent threads...");
        List<Thread> holderThreads = new ArrayList<>();
        List<HolderConnectionPool> holderInstances = new ArrayList<>();
        Object holderLock = new Object();

        for (int i = 0; i < 4; i++) {
            holderThreads.add(new Thread(() -> {
                HolderConnectionPool hp = HolderConnectionPool.getInstance();
                synchronized (holderLock) {
                    holderInstances.add(hp);
                }
            }, "holder-thread-" + i));
        }
        holderThreads.forEach(Thread::start);
        for (Thread t : holderThreads) t.join();

        boolean holderAllSame = holderInstances.stream()
                .allMatch(h -> h == holderInstances.get(0));
        System.out.println("  All threads got the same instance: " + holderAllSame + " (must be true)");
        System.out.println("  Connections: " + holderInstances.get(0).getConnectionCount());

        // ------------------------------------------------------------------ //
        // Demo 7: DCL for non-singleton lazy init                              //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 7: DCL for non-singleton lazy init (UserProfileCache) ---");

        UserProfileCache profileCache = new UserProfileCache(100);
        System.out.println("  Cache size before first access: " + profileCache.getCacheSize()
                + " (0 — not yet built)");

        // Trigger lazy initialization from multiple threads simultaneously
        List<Thread> cacheThreads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cacheThreads.add(new Thread(() -> {
                List<String> c = profileCache.getCache();
                System.out.println("  Thread " + Thread.currentThread().getName()
                        + " got cache with " + c.size() + " profiles");
            }, "cache-user-" + i));
        }
        cacheThreads.forEach(Thread::start);
        for (Thread t : cacheThreads) t.join();

        System.out.println("  Cache built exactly once (see log — 'Building cache' should appear once)");
        System.out.println("  Final cache size: " + profileCache.getCacheSize());

        // ------------------------------------------------------------------ //
        // Summary                                                              //
        // ------------------------------------------------------------------ //
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(65));
        System.out.println("Deadlock Prevention:");
        System.out.println("  Lock ordering   → eliminates circular wait (structural)");
        System.out.println("  tryLock backoff → eliminates hold-and-wait (operational)");
        System.out.println("  Random jitter   → prevents livelock during backoff");
        System.out.println("  ThreadMXBean    → programmatic deadlock detection");
        System.out.println();
        System.out.println("Double-Checked Locking:");
        System.out.println("  new X() = allocate + init + assign (3 steps, reorderable)");
        System.out.println("  volatile  → StoreStore barrier: init must complete before assign");
        System.out.println("  2nd check → prevents double-construction if 2 threads race");
        System.out.println("  Enum      → simplest singleton, no explicit sync needed");
        System.out.println("  Holder    → lazy singleton, JVM class loading is the barrier");
        System.out.println("  DCL       → lazy init for instance fields with runtime deps");
        System.out.println("=".repeat(65));
    }
}

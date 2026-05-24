import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.ArrayList;
import java.util.List;

/**
 * Concurrency — ReentrantLock and the atomic Package
 *
 * Demonstrates all core concepts from Topics 4 and 5:
 *   - ReentrantLock: basic usage and the unlock-in-finally rule
 *   - tryLock: bounded waiting with a timeout
 *   - lockInterruptibly: cancellable lock acquisition
 *   - ReentrantLock observability: isLocked, getQueueLength, etc.
 *   - Fair lock: strict FIFO ordering, no starvation
 *   - CAS (Compare-And-Swap): the hardware instruction behind atomics
 *   - AtomicInteger: all key operations
 *   - Optimistic update loop: custom atomic operations with compareAndSet
 *   - LongAdder: high-throughput counting with per-thread cell striping
 *   - ABA problem and AtomicStampedReference
 *   - Why two AtomicIntegers are NOT jointly atomic
 *
 * Domain: bank accounts, request metrics, order processing
 */
public class ReentrantLockAndAtomics {

    // =========================================================================
    // SECTION 1: ReentrantLock — basic usage and unlock-in-finally
    // =========================================================================

    /**
     * BankAccount shows the fundamental ReentrantLock pattern.
     *
     * For getBalance() alone, synchronized would be sufficient.
     * This class sets up the foundation that withdraw() (Section 2)
     * builds on — where tryLock makes ReentrantLock the right tool.
     *
     * The non-negotiable pattern:
     *   lock.lock();
     *   try { ... }
     *   finally { lock.unlock(); }
     *
     * Why lock() OUTSIDE the try: if lock() is inside try and it throws
     * (rare, but possible), finally calls unlock() without a matching lock()
     * — throwing IllegalMonitorStateException.
     */
    static class BankAccount {
        private double balance;
        private final String accountId;

        // One ReentrantLock per account instance — this is the "latch on the bathroom door"
        private final ReentrantLock lock = new ReentrantLock();

        BankAccount(String accountId, double initialBalance) {
            this.accountId = accountId;
            this.balance = initialBalance;
        }

        // Simple deposit — just needs the lock, no timeout required
        public void deposit(double amount) {
            lock.lock();
            try {
                balance += amount;
            } finally {
                lock.unlock(); // ALWAYS in finally — runs even if an exception is thrown
            }
        }

        // getBalance also needs the lock — reading shared mutable state
        // is just as dangerous as writing to it without synchronization
        public double getBalance() {
            lock.lock();
            try {
                return balance;
            } finally {
                lock.unlock();
            }
        }

        // Observability: expose lock state for monitoring
        public boolean isLocked()    { return lock.isLocked(); }
        public int getQueueLength()  { return lock.getQueueLength(); }
        public String getAccountId() { return accountId; }
    }

    // =========================================================================
    // SECTION 2: tryLock — bounded waiting with a timeout
    // =========================================================================

    /**
     * tryLock() is the first power synchronized cannot provide.
     *
     * Scenario: a user tries to withdraw from their account. If the account
     * lock is held by another operation (e.g. a concurrent transfer), we
     * don't want the request thread to hang indefinitely. We give it a
     * deadline: acquire the lock within 300ms or return false gracefully.
     *
     * Pattern (double-try — worth memorizing):
     *   try {
     *       if (!lock.tryLock(timeout, unit)) { return false; }  // outer try for InterruptedException
     *       try { ... } finally { lock.unlock(); }               // inner try/finally for unlock
     *   } catch (InterruptedException e) { ... }
     */
    static class WithdrawalService {
        private final BankAccount account;

        WithdrawalService(BankAccount account) {
            this.account = account;
        }

        public boolean withdraw(double amount, long timeoutMs) {
            try {
                // tryLock: attempt to acquire the lock within the given timeout.
                // Returns true immediately if free, or waits up to timeoutMs.
                // Returns false if the lock wasn't acquired in time.
                if (!account.lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                    System.out.printf("  [%s] Could not acquire lock within %dms — aborting withdrawal of %.0f%n",
                            Thread.currentThread().getName(), timeoutMs, amount);
                    return false;
                }

                // Lock acquired — inner try/finally guarantees we release it
                try {
                    if (account.balance < amount) {
                        System.out.printf("  [%s] Insufficient funds for %.0f (balance: %.0f)%n",
                                Thread.currentThread().getName(), amount, account.balance);
                        return false;
                    }
                    account.balance -= amount;
                    System.out.printf("  [%s] Withdrew %.0f | Balance: %.0f%n",
                            Thread.currentThread().getName(), amount, account.balance);
                    return true;
                } finally {
                    account.lock.unlock(); // only called because we KNOW we acquired the lock
                }

            } catch (InterruptedException e) {
                // tryLock was interrupted while waiting — honour the interruption
                Thread.currentThread().interrupt(); // restore the interrupted status
                System.out.printf("  [%s] Interrupted while waiting for lock%n",
                        Thread.currentThread().getName());
                return false;
            }
        }
    }

    // =========================================================================
    // SECTION 3: lockInterruptibly — cancellable lock waiting
    // =========================================================================

    /**
     * lockInterruptibly() is the second power synchronized cannot provide.
     *
     * Scenario: a task is queued waiting for a lock. When the system shuts
     * down, we call thread.interrupt() to cancel all waiting tasks.
     *
     * With synchronized: interrupt() on a blocked thread does NOTHING.
     *   The thread stays blocked until the lock is free — no clean shutdown.
     *
     * With lockInterruptibly(): the blocked thread receives InterruptedException
     *   immediately when interrupted — it can clean up and exit gracefully.
     */
    static class InterruptibleTask implements Runnable {
        private final ReentrantLock sharedLock;
        private final String taskName;

        InterruptibleTask(ReentrantLock sharedLock, String taskName) {
            this.sharedLock = sharedLock;
            this.taskName = taskName;
        }

        @Override
        public void run() {
            try {
                System.out.println("  [" + taskName + "] Waiting for lock (interruptibly)...");
                sharedLock.lockInterruptibly(); // throws InterruptedException if interrupted while waiting
                try {
                    System.out.println("  [" + taskName + "] Acquired lock — doing work...");
                    Thread.sleep(200); // simulate work
                    System.out.println("  [" + taskName + "] Work done.");
                } finally {
                    sharedLock.unlock();
                }
            } catch (InterruptedException e) {
                // Clean cancellation path — thread was interrupted while waiting or working
                Thread.currentThread().interrupt();
                System.out.println("  [" + taskName + "] Interrupted — shutting down cleanly.");
            }
        }
    }

    // =========================================================================
    // SECTION 4: Fair lock — strict FIFO ordering, no starvation
    // =========================================================================

    /**
     * FairTicketWindow demonstrates fair vs unfair lock ordering.
     *
     * Unfair lock (default): the JVM gives the lock to any waiting thread,
     *   often the most recently blocked one. Under contention, a thread that
     *   just released the lock can immediately re-acquire it, starving others.
     *
     * Fair lock (new ReentrantLock(true)): strict FIFO. The thread that has
     *   waited the longest always gets the lock next. ~10-30% slower, but
     *   guarantees no starvation ever.
     *
     * Use fair locks for: rate limiters, ticket systems, any scenario where
     * "first come first served" is a correctness requirement.
     */
    static class TicketWindow {
        private int ticketsRemaining;
        private final ReentrantLock lock;

        // Pass fair=true for fair ordering, false for default unfair
        TicketWindow(int tickets, boolean fair) {
            this.ticketsRemaining = tickets;
            this.lock = new ReentrantLock(fair);
            System.out.println("  TicketWindow created: " + tickets + " tickets, fair=" + fair);
        }

        public boolean buyTicket(String customer) {
            lock.lock();
            try {
                if (ticketsRemaining > 0) {
                    ticketsRemaining--;
                    System.out.printf("  [%s] bought ticket. Remaining: %d%n", customer, ticketsRemaining);
                    return true;
                }
                System.out.printf("  [%s] — sold out%n", customer);
                return false;
            } finally {
                lock.unlock();
            }
        }
    }

    // =========================================================================
    // SECTION 5: CAS — the hardware instruction behind all atomics
    // =========================================================================

    /**
     * ManualCASDemo illustrates CAS using AtomicInteger.compareAndSet,
     * which directly exposes the underlying hardware CAS instruction.
     *
     * CAS(address, expected, newValue):
     *   if *address == expected:  → write newValue, return true  (success)
     *   else:                     → do nothing,    return false (fail — retry)
     *
     * This is one uninterruptible CPU instruction. No lock, no OS involvement.
     *
     * incrementAndGet() is just a retry loop around compareAndSet:
     *   loop:
     *     current = read()
     *     next    = current + 1
     *     if CAS(current, next): return next
     *     else: retry
     */
    static class ManualCASDemo {

        // Simulate what incrementAndGet() does internally, manually
        public static int manualIncrementAndGet(AtomicInteger counter) {
            int current, next;
            int attempts = 0;
            do {
                current = counter.get();  // read current value from memory
                next = current + 1;       // compute desired new value
                attempts++;
                // CAS: "if counter still equals current, write next and return true"
                // If another thread changed counter between our read and now: returns false, we retry
            } while (!counter.compareAndSet(current, next));

            if (attempts > 1) {
                System.out.println("  CAS required " + attempts + " attempts (contention detected)");
            }
            return next;
        }

        // Optimistic update loop: custom atomic operation
        // "Double the value, but only if current < 500 (so result stays below 1000)" 2014 atomically
        public static int atomicDoubleIfBelow1000(AtomicInteger counter) {
            int current, next;
            do {
                current = counter.get();
                if (current >= 500) return current; // guard 2014 doubling would exceed 1000
                next = current * 2;
                // Retry if counter changed between our read and this CAS attempt
            } while (!counter.compareAndSet(current, next));
            return next;
        }
    }

    // =========================================================================
    // SECTION 6: AtomicInteger — all key operations
    // =========================================================================

    /**
     * RequestCounter demonstrates the full AtomicInteger API in a realistic
     * domain: tracking HTTP request metrics across many concurrent threads.
     *
     * Each operation is atomic at the hardware level — no lock acquired,
     * no thread ever blocked, no context switch.
     */
    static class RequestCounter {
        private final AtomicInteger totalRequests  = new AtomicInteger(0);
        private final AtomicInteger failedRequests = new AtomicInteger(0);
        private final AtomicLong    totalLatencyMs = new AtomicLong(0);

        public void recordRequest(boolean success, long latencyMs) {
            totalRequests.incrementAndGet();     // atomic: read-add-write in one CPU instruction
            totalLatencyMs.addAndGet(latencyMs); // atomic add for long values
            if (!success) {
                failedRequests.incrementAndGet();
            }
        }

        public void printStats() {
            int total  = totalRequests.get();
            int failed = failedRequests.get();
            double avg = total > 0 ? (double) totalLatencyMs.get() / total : 0;
            System.out.printf("  Total: %d | Failed: %d | Success rate: %.1f%% | Avg latency: %.1fms%n",
                    total, failed,
                    total > 0 ? (total - failed) * 100.0 / total : 0,
                    avg);
        }
    }

    // =========================================================================
    // SECTION 7: LongAdder — high-throughput counting
    // =========================================================================

    /**
     * HighThroughputCounter compares AtomicLong vs LongAdder under contention.
     *
     * AtomicLong: all threads CAS the same memory location.
     *   Under high contention, many CAS operations fail and retry.
     *   Performance degrades as thread count grows.
     *
     * LongAdder: maintains a stripe of per-thread cells.
     *   Each thread writes to its own cell — almost no CAS collisions.
     *   At read time, all cells are summed.
     *
     * LongAdder wins when:
     *   - Many threads increment simultaneously (high write rate)
     *   - Reads are infrequent (metrics, stats, hit counters)
     *
     * AtomicLong wins when:
     *   - You need a precise value at any instant (not an approximate sum)
     *   - Write rate is low
     */
    static class HighThroughputCounter {
        // For comparison: both count the same events
        private final AtomicLong  atomicCount  = new AtomicLong(0);
        private final LongAdder   adderCount   = new LongAdder();

        // Also demonstrate LongAccumulator for tracking the running maximum
        private final LongAccumulator maxLatency =
                new LongAccumulator(Long::max, 0); // identity=0, operator=max

        public void record(long latencyMs) {
            atomicCount.incrementAndGet();
            adderCount.increment();              // writes to this thread's own cell
            maxLatency.accumulate(latencyMs);    // atomically keeps the running max
        }

        public void printStats(String label) {
            System.out.printf("  [%s] AtomicLong: %d | LongAdder.sum(): %d | MaxLatency: %dms%n",
                    label, atomicCount.get(), adderCount.sum(), maxLatency.get());
        }
    }

    // =========================================================================
    // SECTION 8: ABA problem and AtomicStampedReference
    // =========================================================================

    /**
     * ABADemo illustrates the ABA problem and its fix.
     *
     * ABA: Thread A reads value 5. Thread B changes 5→7→5.
     *      Thread A's CAS(5, 6) succeeds — it can't tell B was ever there.
     *
     * For counters, this doesn't matter — you only care about the final number.
     *
     * For shared state where intermediate transitions matter (e.g. an order
     * that went PENDING→CANCELLED→PENDING again), ABA is a real bug.
     *
     * Fix: AtomicStampedReference attaches a version counter (stamp) to the
     * value. The stamp increments on every write. Even if the value reverts,
     * the stamp won't match — CAS correctly detects the intermediate change.
     */
    static class OrderStatusManager {

        // Without stamp: ABA is undetectable
        private final AtomicReference<String> statusNoStamp =
                new AtomicReference<>("PENDING");

        // With stamp: every write increments the version — ABA is detectable
        private final AtomicStampedReference<String> statusWithStamp =
                new AtomicStampedReference<>("PENDING", 0);

        // Simulate Thread A reading status, then trying to update it
        // after Thread B has changed it and changed it back
        public void demonstrateABA() {
            System.out.println("  --- Without stamp (ABA undetectable) ---");
            String readByA = statusNoStamp.get();      // A reads "PENDING"
            System.out.println("  Thread A reads: " + readByA);

            // Simulate Thread B: PENDING → CANCELLED → PENDING
            statusNoStamp.set("CANCELLED");
            statusNoStamp.set("PENDING");
            System.out.println("  Thread B: PENDING → CANCELLED → PENDING");

            // Thread A's CAS succeeds even though status was changed and restored
            boolean success = statusNoStamp.compareAndSet(readByA, "CONFIRMED");
            System.out.println("  Thread A CAS(PENDING, CONFIRMED): " + success
                    + " ← succeeded but missed B's intermediate changes!");

            System.out.println("  --- With stamp (ABA detectable) ---");

            int[] stampHolder = new int[1];
            String valueByA = statusWithStamp.get(stampHolder); // A reads value AND stamp
            int stampByA = stampHolder[0];                       // stamp = 0
            System.out.println("  Thread A reads: " + valueByA + " (stamp=" + stampByA + ")");

            // Simulate Thread B: PENDING(0) → CANCELLED(1) → PENDING(2)
            statusWithStamp.compareAndSet("PENDING",   "CANCELLED", 0, 1);
            statusWithStamp.compareAndSet("CANCELLED", "PENDING",   1, 2);
            System.out.println("  Thread B: PENDING(0) → CANCELLED(1) → PENDING(2)");

            // Thread A's CAS fails — stamp 0 no longer matches current stamp 2
            boolean stampedSuccess = statusWithStamp.compareAndSet(
                    valueByA, "CONFIRMED", stampByA, stampByA + 1);
            System.out.println("  Thread A CAS(PENDING@stamp=0, CONFIRMED): " + stampedSuccess
                    + " ← correctly detected ABA!");
        }
    }

    // =========================================================================
    // SECTION 9: Two atomics are NOT jointly atomic — the key trap
    // =========================================================================

    /**
     * AccountWithTwoAtomics shows the most dangerous atomic anti-pattern:
     * using two separate AtomicIntegers thinking they are jointly atomic.
     *
     * Each field is individually atomic, but they are NOT atomically updated
     * together. A reader thread can see balance decremented but txCount not
     * yet incremented — an inconsistent snapshot of the account's state.
     *
     * Fix: whenever two or more variables must change as one atomic unit,
     * use synchronized (or ReentrantLock) to protect the whole operation.
     */
    static class BrokenTwoAtomicAccount {
        final AtomicInteger balance = new AtomicInteger(1000);
        final AtomicInteger txCount = new AtomicInteger(0);

        // BROKEN — balance and txCount are independently atomic, not jointly
        public void withdraw(int amount) {
            balance.addAndGet(-amount);   // ← reader can observe state here
            txCount.incrementAndGet();    //   balance changed but txCount not yet
        }
    }

    static class FixedTwoFieldAccount {
        private int balance = 1000;
        private int txCount = 0;

        // FIXED — synchronized makes the whole block one atomic unit
        public synchronized void withdraw(int amount) {
            balance -= amount;   // no reader can see between these two lines
            txCount++;           // because only one thread runs this block at a time
        }

        public synchronized int getBalance() { return balance; }
        public synchronized int getTxCount()  { return txCount; }
    }

    // =========================================================================
    // MAIN — run all demos in sequence
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=".repeat(65));
        System.out.println("REENTRANTLOCK AND ATOMICS DEMO");
        System.out.println("=".repeat(65));

        // ------------------------------------------------------------------ //
        // Demo 1: Basic ReentrantLock — unlock in finally                     //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 1: ReentrantLock — basic lock/unlock pattern ---");

        BankAccount account = new BankAccount("ACC-001", 5000);
        WithdrawalService service = new WithdrawalService(account);

        // Concurrent deposits and withdrawals — lock ensures correctness
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            threads.add(new Thread(() -> account.deposit(100), "depositor-" + i));
            threads.add(new Thread(() -> service.withdraw(50, 200), "withdrawer-" + i));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        System.out.println("  Final balance: " + account.getBalance()
                + " (expected: " + (5000 + 5 * 100 - 5 * 50) + ")");

        // ------------------------------------------------------------------ //
        // Demo 2: tryLock — timeout on lock acquisition                       //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 2: tryLock — bounded waiting ---");

        BankAccount slowAccount = new BankAccount("ACC-002", 10000);
        WithdrawalService slowService = new WithdrawalService(slowAccount);

        // Thread A holds the lock for 400ms; Thread B times out at 100ms
        Thread holder = new Thread(() -> {
            slowAccount.lock.lock();
            try {
                System.out.println("  [lock-holder] acquired lock — holding for 400ms");
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                slowAccount.lock.unlock();
                System.out.println("  [lock-holder] released lock");
            }
        }, "lock-holder");

        Thread waiter = new Thread(() -> {
            // 100ms timeout — will fail because holder keeps it for 400ms
            slowService.withdraw(500, 100);
        }, "short-wait-thread");

        holder.start();
        Thread.sleep(50); // let holder acquire first
        waiter.start();
        holder.join();
        waiter.join();

        // ------------------------------------------------------------------ //
        // Demo 3: lockInterruptibly — cancellable waiting                     //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 3: lockInterruptibly — cancellable wait ---");

        ReentrantLock sharedLock = new ReentrantLock();
        Thread task1 = new Thread(new InterruptibleTask(sharedLock, "Task-1"), "task-1");
        Thread task2 = new Thread(new InterruptibleTask(sharedLock, "Task-2"), "task-2");

        task1.start();
        Thread.sleep(30); // task1 gets the lock first
        task2.start();
        Thread.sleep(50); // task2 is now waiting
        task2.interrupt(); // cancel task2 while it waits — clean shutdown
        task1.join();
        task2.join();

        // ------------------------------------------------------------------ //
        // Demo 4: Fair lock — FIFO ordering                                   //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 4: Fair lock — FIFO ordering ---");

        TicketWindow window = new TicketWindow(3, true); // fair=true
        List<Thread> buyers = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            final String customer = "Customer-" + i;
            buyers.add(new Thread(() -> window.buyTicket(customer), "buyer-" + i));
        }
        buyers.forEach(Thread::start);
        for (Thread t : buyers) t.join();

        // ------------------------------------------------------------------ //
        // Demo 5: CAS operations — manual and optimistic loop                 //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 5: CAS — manual incrementAndGet and optimistic loop ---");

        AtomicInteger cas = new AtomicInteger(100);
        System.out.println("  Initial value: " + cas.get());

        int result = ManualCASDemo.manualIncrementAndGet(cas);
        System.out.println("  After manualIncrementAndGet: " + result);

        // Optimistic loop: double if below 1000
        cas.set(300);
        System.out.println("  Before atomicDoubleIfBelow1000 (value=300): "
                + ManualCASDemo.atomicDoubleIfBelow1000(cas) + " (expected 600)");

        cas.set(800);
        System.out.println("  Before atomicDoubleIfBelow1000 (value=800): "
                + ManualCASDemo.atomicDoubleIfBelow1000(cas) + " (expected 800, guarded — 800>=500)");

        // ------------------------------------------------------------------ //
        // Demo 6: AtomicInteger — full API                                    //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 6: AtomicInteger — all key operations ---");

        AtomicInteger c = new AtomicInteger(10);
        System.out.println("  get()             = " + c.get());
        System.out.println("  getAndSet(50)     = " + c.getAndSet(50) + " (was 10, now 50)");
        System.out.println("  incrementAndGet() = " + c.incrementAndGet() + " (now 51)");
        System.out.println("  getAndIncrement() = " + c.getAndIncrement() + " (returns 51, then sets 52)");
        System.out.println("  addAndGet(8)      = " + c.addAndGet(8) + " (52+8=60)");
        System.out.println("  compareAndSet(60,100) = " + c.compareAndSet(60, 100) + " (matches — success)");
        System.out.println("  compareAndSet(60,200) = " + c.compareAndSet(60, 200) + " (stale — failure)");
        System.out.println("  current value     = " + c.get());

        // ------------------------------------------------------------------ //
        // Demo 7: RequestCounter — realistic AtomicInteger usage              //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 7: RequestCounter — concurrent request metrics ---");

        RequestCounter counter = new RequestCounter();
        List<Thread> requestThreads = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            final boolean success = (i % 5 != 0); // every 5th request fails
            final long latency = 10 + (i * 3L);
            requestThreads.add(new Thread(() -> counter.recordRequest(success, latency)));
        }
        requestThreads.forEach(Thread::start);
        for (Thread t : requestThreads) t.join();

        counter.printStats();

        // ------------------------------------------------------------------ //
        // Demo 8: LongAdder vs AtomicLong under concurrent writes             //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 8: LongAdder — high-throughput counting ---");

        HighThroughputCounter htCounter = new HighThroughputCounter();
        List<Thread> recorders = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            final long latency = (i + 1) * 15L;
            recorders.add(new Thread(() -> {
                for (int j = 0; j < 1000; j++) htCounter.record(latency);
            }));
        }
        recorders.forEach(Thread::start);
        for (Thread t : recorders) t.join();

        htCounter.printStats("after 10,000 records");
        System.out.println("  Expected total: 10000 (both should match)");

        // ------------------------------------------------------------------ //
        // Demo 9: ABA problem and AtomicStampedReference                      //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 9: ABA problem and AtomicStampedReference ---");
        new OrderStatusManager().demonstrateABA();

        // ------------------------------------------------------------------ //
        // Demo 10: Two atomics are NOT jointly atomic                         //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 10: Two AtomicIntegers are NOT jointly atomic ---");

        BrokenTwoAtomicAccount broken = new BrokenTwoAtomicAccount();
        FixedTwoFieldAccount   fixed  = new FixedTwoFieldAccount();

        List<Thread> brokenThreads = new ArrayList<>();
        List<Thread> fixedThreads  = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            brokenThreads.add(new Thread(() -> broken.withdraw(10)));
            fixedThreads.add(new Thread(() -> fixed.withdraw(10)));
        }

        brokenThreads.forEach(Thread::start);
        for (Thread t : brokenThreads) t.join();

        fixedThreads.forEach(Thread::start);
        for (Thread t : fixedThreads) t.join();

        System.out.println("  Broken: balance=" + broken.balance.get()
                + " txCount=" + broken.txCount.get()
                + " | balance + txCount*10 should = 1000, got: "
                + (broken.balance.get() + broken.txCount.get() * 10)
                + " (may be inconsistent)");
        System.out.println("  Fixed:  balance=" + fixed.getBalance()
                + " txCount=" + fixed.getTxCount()
                + " | balance + txCount*10 = "
                + (fixed.getBalance() + fixed.getTxCount() * 10)
                + " (always consistent)");

        // ------------------------------------------------------------------ //
        // Summary                                                              //
        // ------------------------------------------------------------------ //
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(65));
        System.out.println("ReentrantLock over synchronized:");
        System.out.println("  tryLock(timeout)       → bounded wait, no infinite hanging");
        System.out.println("  lockInterruptibly()    → cancellable wait, clean shutdown");
        System.out.println("  isLocked/getQueueLen   → observability for monitoring");
        System.out.println("  new ReentrantLock(true)→ fair FIFO, no starvation");
        System.out.println();
        System.out.println("Atomic package:");
        System.out.println("  AtomicInteger          → lock-free single-variable counter");
        System.out.println("  compareAndSet          → raw CAS, enables custom atomic ops");
        System.out.println("  LongAdder              → high-throughput, per-thread striping");
        System.out.println("  AtomicStampedReference → ABA-safe versioned reference");
        System.out.println();
        System.out.println("Key rule: atomics protect ONE variable.");
        System.out.println("          Two variables updated together → use synchronized.");
        System.out.println("=".repeat(65));
    }
}

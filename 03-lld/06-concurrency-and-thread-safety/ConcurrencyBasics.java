import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 4 — Concurrency Basics
 *
 * Demonstrates all core concepts from Topics 1–3:
 *   - Race conditions and why they happen
 *   - The visibility problem (stale caches)
 *   - volatile: fixes visibility, not atomicity
 *   - synchronized: fixes both visibility and atomicity
 *   - Method-level vs block-level synchronized
 *   - volatile vs synchronized: choosing the right tool
 *
 * Each inner class is a self-contained demo. Run main() to see all of them.
 *
 * Domain: ticket booking + order processing (consistent with prior phases)
 */
public class ConcurrencyBasics {

    // =========================================================================
    // SECTION 1: Race Condition — the broken counter
    // =========================================================================

    /**
     * BrokenTicketCounter demonstrates a classic race condition.
     *
     * The bug: availableTickets-- compiles to three CPU instructions:
     *   1. READ  availableTickets into a register
     *   2. DECREMENT the register
     *   3. WRITE the register back to memory
     *
     * Two threads can interleave these three steps, causing both threads to
     * "successfully" book the same last ticket. The counter can go negative.
     */
    static class BrokenTicketCounter {
        private int availableTickets;

        BrokenTicketCounter(int total) {
            this.availableTickets = total;
        }

        // NOT thread-safe — race condition on the check + decrement
        public boolean bookTicket(String userId) {
            if (availableTickets > 0) {
                // ← any thread can be preempted right here, after the check
                //   but before the decrement. Another thread reads the same
                //   value and also passes the check. Both decrement. Boom.
                availableTickets--;
                return true;
            }
            return false;
        }

        public int getAvailableTickets() {
            return availableTickets;
        }
    }

    // =========================================================================
    // SECTION 2: Visibility Problem — the infinite loop
    // =========================================================================

    /**
     * VisibilityDemo demonstrates the visibility problem.
     *
     * The worker thread caches 'running' in its CPU register on the first read.
     * Even after the main thread writes 'false', the worker keeps reading the
     * stale cached copy and loops forever.
     *
     * This is not a race condition — there is no write conflict. The problem is
     * purely about one thread's write never being seen by another thread.
     */
    static class VisibilityDemo {

        // Without volatile: the JVM may cache this in a CPU register.
        // With volatile:    every read goes to main memory; every write flushes.
        private volatile boolean running = true;

        public void startWorker() throws InterruptedException {
            Thread worker = new Thread(() -> {
                int count = 0;
                // Without volatile, this loop may NEVER terminate —
                // the worker reads its stale cached copy of 'running' forever.
                // With volatile, it sees the main thread's write promptly.
                while (running) {
                    count++;
                }
                System.out.println("  [Worker] stopped after " + count + " iterations.");
            }, "worker-thread");

            worker.start();

            Thread.sleep(5); // let the worker spin for a moment
            running = false;  // volatile write — immediately visible to the worker
            System.out.println("  [Main]   set running = false");

            worker.join(2000); // wait up to 2s for the worker to notice
            if (worker.isAlive()) {
                System.out.println("  [Main]   BUG: worker is still running! (remove volatile to reproduce)");
                worker.interrupt();
            }
        }
    }

    // =========================================================================
    // SECTION 3: volatile — correct for a shutdown flag
    // =========================================================================

    /**
     * PaymentProcessor shows the canonical correct use of volatile:
     * a single-writer flag read by many threads.
     *
     * volatile is correct here because:
     *   - One thread writes (requestShutdown)
     *   - The write does NOT depend on the current value (it's just 'true')
     *   - Workers only read it — no compound operation
     *
     * Using synchronized here would also work, but volatile is lighter and
     * perfectly expresses the intent.
     */
    static class PaymentProcessor {

        // volatile is sufficient: single write, no compound operation
        private volatile boolean shutdownRequested = false;
        private final String name;

        PaymentProcessor(String name) {
            this.name = name;
        }

        // Called by a monitoring/admin thread
        public void requestShutdown() {
            shutdownRequested = true; // single write — atomic on its own
            System.out.println("  [Admin]  shutdown requested for " + name);
        }

        // Called by a worker thread in a loop
        public void processPayments(int maxPayments) {
            int processed = 0;
            while (!shutdownRequested && processed < maxPayments) {
                // simulate processing a payment
                processed++;
            }
            System.out.println("  [" + name + "] processed " + processed
                    + " payments, shutdownRequested=" + shutdownRequested);
        }
    }

    // =========================================================================
    // SECTION 4: volatile FAILS for compound operations
    // =========================================================================

    /**
     * BrokenVolatileCounter shows the trap: volatile fixes visibility but
     * NOT atomicity. Making a counter volatile does not make increment() safe.
     *
     * counter++ is still three steps (read, add, write). volatile ensures each
     * step touches main memory, but two threads can still interleave between
     * step 1 and step 3, causing lost increments.
     */
    static class BrokenVolatileCounter {
        // volatile gives visibility — but counter++ is still not atomic!
        private volatile int count = 0;

        // NOT thread-safe despite volatile — the three steps still race
        public void increment() {
            count++; // read → add → write: three non-atomic steps
        }

        public int getCount() {
            return count;
        }
    }

    // =========================================================================
    // SECTION 5: synchronized — method-level, fixes both problems
    // =========================================================================

    /**
     * FixedTicketCounter uses synchronized methods to eliminate the race
     * condition from Section 1.
     *
     * synchronized guarantees:
     *   1. Mutual exclusion: only one thread runs the method at a time.
     *      The check (> 0) and the decrement (--) are now one atomic unit.
     *   2. Visibility: on lock release, all writes flush to main memory.
     *      On lock acquire, the thread reads fresh values from main memory.
     *
     * Both bookTicket AND getAvailableTickets must be synchronized.
     * If only the write is synchronized but the read is not, the reader
     * can still see a stale value. The rule: every access (read + write)
     * to a shared mutable variable must use the same lock.
     */
    static class FixedTicketCounter {
        private int availableTickets;

        FixedTicketCounter(int total) {
            this.availableTickets = total;
        }

        // Lock = this. Only one thread can execute this at a time.
        // The check-then-decrement is now one atomic, uninterruptible unit.
        public synchronized boolean bookTicket(String userId) {
            if (availableTickets > 0) {
                availableTickets--;
                System.out.printf("  [%s] %s booked. Remaining: %d%n",
                        Thread.currentThread().getName(), userId, availableTickets);
                return true;
            }
            System.out.printf("  [%s] %s — sold out%n",
                    Thread.currentThread().getName(), userId);
            return false;
        }

        // READ must also be synchronized — same lock, same guarantee
        public synchronized int getAvailableTickets() {
            return availableTickets;
        }
    }

    // =========================================================================
    // SECTION 6: synchronized — block-level with fine-grained locks
    // =========================================================================

    /**
     * OrderProcessor uses separate locks for inventory and audit logging.
     *
     * WHY separate locks?
     *   Inventory and audit log are independent resources. If we locked 'this'
     *   across the whole method, a thread writing to the audit log would block
     *   threads trying to update inventory — even though they share no data.
     *   That is wasted contention.
     *
     * With two locks:
     *   - Thread A holds inventoryLock while updating inventory.
     *   - Thread A releases inventoryLock, then acquires auditLock.
     *   - Thread B can acquire inventoryLock and update inventory WHILE
     *     Thread A is still writing to the audit log.
     *   - Both run concurrently on their respective independent resources.
     *
     * Analogy: two cashier counters with two independent queues.
     *   One lock = one queue for both counters (unnecessary waiting).
     *   Two locks = two queues, fully independent.
     */
    static class OrderProcessor {

        // Two separate lock objects — one per independent resource
        private final Object inventoryLock = new Object();
        private final Object auditLock     = new Object();

        private int inventory = 500;
        private final List<String> auditLog = new ArrayList<>();

        public void processOrder(String orderId, int quantity) {
            boolean reserved;

            // Critical section 1: protect only the inventory update
            synchronized (inventoryLock) {
                if (inventory >= quantity) {
                    inventory -= quantity;
                    reserved = true;
                } else {
                    reserved = false;
                }
            }
            // inventoryLock released HERE — other threads can now update
            // inventory while this thread moves on to audit logging

            // Critical section 2: protect only the audit log write
            synchronized (auditLock) {
                String entry = orderId + ": " + (reserved
                        ? "reserved " + quantity + " units"
                        : "FAILED — insufficient stock");
                auditLog.add(entry);
                System.out.println("  [Audit] " + entry);
            }
        }

        public synchronized List<String> getAuditLog() {
            // Returning a copy so callers can't modify the internal list
            return new ArrayList<>(auditLog);
        }

        public int getInventory() {
            synchronized (inventoryLock) {
                return inventory;
            }
        }
    }

    // =========================================================================
    // SECTION 7: AtomicInteger — the modern idiomatic counter
    // =========================================================================

    /**
     * AtomicCounter shows the modern, preferred approach for thread-safe
     * counters. AtomicInteger uses hardware CAS (Compare-And-Swap) instructions
     * — faster than a mutex lock and expresses intent more clearly.
     *
     * incrementAndGet() is a single uninterruptible hardware instruction on
     * modern CPUs — no thread can interleave into it.
     *
     * Use AtomicInteger for counters; use synchronized when you need to
     * protect multiple statements as one atomic unit.
     */
    static class AtomicCounter {
        // AtomicInteger wraps an int with atomic read-modify-write operations
        private final AtomicInteger count = new AtomicInteger(0);

        public void increment() {
            count.incrementAndGet(); // atomic: read + add + write in one step
        }

        public int getCount() {
            return count.get();
        }
    }

    // =========================================================================
    // SECTION 8: ConnectionPool — volatile vs synchronized comparison
    // =========================================================================

    /**
     * BrokenVolatilePool shows the most dangerous misconception:
     * "volatile makes it thread-safe."
     *
     * volatile ensures both threads read the freshest value of activeConnections.
     * But they can BOTH read 9, BOTH pass the check, BOTH increment → 11
     * connections against a pool of 10.
     *
     * Visibility is correct. Atomicity of the check-then-act is broken.
     */
    static class BrokenVolatilePool {
        private volatile int activeConnections = 0;
        private static final int MAX = 10;

        // BUG: volatile read + volatile write are individually fresh,
        // but the check (< MAX) and increment (++) still race
        public boolean acquire() {
            if (activeConnections < MAX) {
                activeConnections++; // another thread can increment between the check and this line
                return true;
            }
            return false;
        }

        public void release() {
            if (activeConnections > 0) activeConnections--;
        }

        public int getActiveConnections() {
            return activeConnections;
        }
    }

    /**
     * FixedSynchronizedPool uses synchronized to make the check-then-act
     * atomic. The check and increment happen under the same lock —
     * no thread can see a stale count or interleave between them.
     */
    static class FixedSynchronizedPool {
        private int activeConnections = 0;
        private static final int MAX = 10;

        // synchronized makes the check + increment one atomic unit
        public synchronized boolean acquire() {
            if (activeConnections < MAX) {
                activeConnections++;
                return true;
            }
            return false;
        }

        public synchronized void release() {
            if (activeConnections > 0) activeConnections--;
        }

        public synchronized int getActiveConnections() {
            return activeConnections;
        }
    }

    // =========================================================================
    // MAIN — run all demos
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=".repeat(65));
        System.out.println("PHASE 4 — CONCURRENCY BASICS DEMO");
        System.out.println("=".repeat(65));

        // ------------------------------------------------------------------ //
        // Demo 1: Race Condition — BrokenTicketCounter                        //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 1: Race Condition (BrokenTicketCounter) ---");
        System.out.println("200 threads trying to book 100 tickets (no synchronization)");

        BrokenTicketCounter broken = new BrokenTicketCounter(100);
        List<Thread> brokenThreads = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            final String userId = "User-" + i;
            brokenThreads.add(new Thread(() -> broken.bookTicket(userId)));
        }
        brokenThreads.forEach(Thread::start);
        for (Thread t : brokenThreads) t.join();

        System.out.println("Expected final tickets: 0");
        System.out.println("Actual  final tickets: " + broken.getAvailableTickets()
                + " (may be negative or wrong — race condition)");

        // ------------------------------------------------------------------ //
        // Demo 2: Visibility Problem — volatile fixes the infinite loop        //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 2: Visibility Problem (volatile shutdown flag) ---");
        VisibilityDemo visibilityDemo = new VisibilityDemo();
        visibilityDemo.startWorker();

        // ------------------------------------------------------------------ //
        // Demo 3: volatile — correct use for a shutdown flag                  //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 3: volatile — correct for status flag ---");
        PaymentProcessor processor = new PaymentProcessor("PaymentProcessor-1");

        Thread workerThread = new Thread(() -> processor.processPayments(10_000_000));
        Thread adminThread  = new Thread(() -> {
            try {
                Thread.sleep(10);
                processor.requestShutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        workerThread.start();
        adminThread.start();
        workerThread.join();
        adminThread.join();

        // ------------------------------------------------------------------ //
        // Demo 4: volatile FAILS for compound operations                      //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 4: volatile fails for counter++ ---");
        System.out.println("10 threads each incrementing a volatile counter 1000 times");

        BrokenVolatileCounter volatileCounter = new BrokenVolatileCounter();
        List<Thread> volatileThreads = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            volatileThreads.add(new Thread(() -> {
                for (int j = 0; j < 1000; j++) volatileCounter.increment();
            }));
        }
        volatileThreads.forEach(Thread::start);
        for (Thread t : volatileThreads) t.join();

        System.out.println("Expected: 10000 | Actual: " + volatileCounter.getCount()
                + " (likely less — lost increments)");

        // ------------------------------------------------------------------ //
        // Demo 5: synchronized — method-level fix                             //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 5: synchronized TicketCounter (fixed) ---");
        System.out.println("200 threads, 5 tickets — every booking should succeed exactly once");

        FixedTicketCounter fixed = new FixedTicketCounter(5);
        List<Thread> fixedThreads = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            final String userId = "User-" + i;
            fixedThreads.add(new Thread(() -> fixed.bookTicket(userId)));
        }
        fixedThreads.forEach(Thread::start);
        for (Thread t : fixedThreads) t.join();

        System.out.println("Final tickets: " + fixed.getAvailableTickets()
                + " (must be exactly 0)");

        // ------------------------------------------------------------------ //
        // Demo 6: synchronized — block-level, fine-grained locks              //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 6: Fine-grained synchronized (OrderProcessor) ---");

        OrderProcessor orderProcessor = new OrderProcessor();
        List<Thread> orderThreads = new ArrayList<>();

        for (int i = 1; i <= 8; i++) {
            final String orderId = "ORD-" + String.format("%03d", i);
            final int qty = i * 10;
            orderThreads.add(new Thread(() -> orderProcessor.processOrder(orderId, qty)));
        }
        orderThreads.forEach(Thread::start);
        for (Thread t : orderThreads) t.join();

        System.out.println("Remaining inventory: " + orderProcessor.getInventory());
        System.out.println("Audit log size: " + orderProcessor.getAuditLog().size() + " entries");

        // ------------------------------------------------------------------ //
        // Demo 7: AtomicInteger — correct counter without synchronized         //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 7: AtomicInteger counter (correct) ---");
        System.out.println("10 threads each incrementing 1000 times");

        AtomicCounter atomicCounter = new AtomicCounter();
        List<Thread> atomicThreads = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            atomicThreads.add(new Thread(() -> {
                for (int j = 0; j < 1000; j++) atomicCounter.increment();
            }));
        }
        atomicThreads.forEach(Thread::start);
        for (Thread t : atomicThreads) t.join();

        System.out.println("Expected: 10000 | Actual: " + atomicCounter.getCount()
                + " (always correct)");

        // ------------------------------------------------------------------ //
        // Demo 8: volatile vs synchronized for ConnectionPool                  //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 8: volatile FAILS for connection pool ---");
        System.out.println("50 threads acquiring from a pool of MAX=10 connections");

        BrokenVolatilePool brokenPool = new BrokenVolatilePool();
        List<Thread> poolThreads = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            poolThreads.add(new Thread(() -> brokenPool.acquire()));
        }
        poolThreads.forEach(Thread::start);
        for (Thread t : poolThreads) t.join();

        System.out.println("Broken volatile pool — active connections: "
                + brokenPool.getActiveConnections()
                + " (may exceed MAX=10 — race condition)");

        System.out.println("\n--- Demo 8b: synchronized fixes the connection pool ---");

        FixedSynchronizedPool fixedPool = new FixedSynchronizedPool();
        List<Thread> fixedPoolThreads = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            fixedPoolThreads.add(new Thread(() -> fixedPool.acquire()));
        }
        fixedPoolThreads.forEach(Thread::start);
        for (Thread t : fixedPoolThreads) t.join();

        System.out.println("Fixed synchronized pool — active connections: "
                + fixedPool.getActiveConnections()
                + " (never exceeds MAX=10)");

        // ------------------------------------------------------------------ //
        // Summary                                                              //
        // ------------------------------------------------------------------ //
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(65));
        System.out.println("volatile  → fixes VISIBILITY only");
        System.out.println("           use for: flags, status fields, single-writer signals");
        System.out.println("           NOT for: counter++, check-then-act, read-modify-write");
        System.out.println();
        System.out.println("synchronized → fixes VISIBILITY + ATOMICITY");
        System.out.println("           use for: compound operations on shared mutable state");
        System.out.println("           remember: synchronize READS too, not just writes");
        System.out.println();
        System.out.println("AtomicInteger → hardware CAS, faster than synchronized for counters");
        System.out.println("           use for: single-variable increment/decrement operations");
        System.out.println("=".repeat(65));
    }
}

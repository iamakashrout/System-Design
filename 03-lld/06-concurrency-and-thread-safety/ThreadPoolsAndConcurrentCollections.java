import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread Pools, ExecutorService, and Thread-Safe Collections
 *
 * Demonstrates all core concepts from Topics 6 and 7:
 *
 * Thread Pools & ExecutorService:
 *   - newFixedThreadPool: bounded concurrency, predictable resources
 *   - newCachedThreadPool: elastic, creates threads on demand
 *   - newSingleThreadExecutor: strict serial execution in submission order
 *   - newScheduledThreadPool: delayed and recurring tasks
 *   - Future<T>: handle to a pending result, get/isDone/cancel/timeout
 *   - Two-phase shutdown: shutdown() → awaitTermination() → shutdownNow()
 *
 * Thread-Safe Collections:
 *   - ConcurrentHashMap: bucket-level locking, lock-free reads, atomic ops
 *   - CopyOnWriteArrayList: snapshot-isolation writes, lock-free iteration
 *   - LinkedBlockingQueue: blocking put/take, producer-consumer pattern
 *   - ArrayBlockingQueue: bounded, fixed-memory alternative
 *   - PriorityBlockingQueue: priority-ordered task consumption
 *
 * Domain: order processing, inventory management, metrics, event listeners
 */
public class ThreadPoolsAndConcurrentCollections {

    // =========================================================================
    // SHARED DOMAIN OBJECTS
    // =========================================================================

    /** Represents a customer order flowing through the system. */
    static class Order {
        private final String orderId;
        private final String customerId;
        private final int    quantity;
        private final int    priority; // 1 = highest, 10 = lowest

        Order(String orderId, String customerId, int quantity, int priority) {
            this.orderId    = orderId;
            this.customerId = customerId;
            this.quantity   = quantity;
            this.priority   = priority;
        }

        String getOrderId()    { return orderId; }
        String getCustomerId() { return customerId; }
        int    getQuantity()   { return quantity; }
        int    getPriority()   { return priority; }

        @Override public String toString() {
            return orderId + "(qty=" + quantity + ",pri=" + priority + ")";
        }
    }

    /** Result produced by processing one order. */
    static class OrderResult {
        private final String  orderId;
        private final boolean success;
        private final long    processingMs;

        OrderResult(String orderId, boolean success, long processingMs) {
            this.orderId      = orderId;
            this.success      = success;
            this.processingMs = processingMs;
        }

        @Override public String toString() {
            return orderId + " → " + (success ? "OK" : "FAILED")
                    + " (" + processingMs + "ms)";
        }
    }

    // =========================================================================
    // SECTION 1: Fixed Thread Pool — bounded concurrency
    // =========================================================================

    /**
     * OrderProcessingSystem uses a fixed pool to process orders concurrently.
     *
     * WHY fixed pool: we want at most N orders processing simultaneously.
     * More than N threads for CPU/IO-bound tasks wastes resources and increases
     * context-switch overhead. The queue buffers excess submissions.
     *
     * WHY submit() over execute(): submit() returns a Future so we can:
     *   - Retrieve the result when ready
     *   - Catch exceptions thrown inside the task
     *   - Cancel the task if needed
     *
     * WHY AtomicInteger for processedCount: the counter is updated from
     * multiple pool threads concurrently — a plain int would race.
     */
    static class OrderProcessingSystem {

        // 4 threads process orders concurrently — bounded resource use
        private final ExecutorService executor = Executors.newFixedThreadPool(4);
        private final AtomicInteger   processedCount = new AtomicInteger(0);
        private final AtomicInteger   failedCount    = new AtomicInteger(0);

        /**
         * Submits an order for async processing.
         * Returns a Future immediately — the caller can do other work
         * and retrieve the result later via future.get().
         */
        public Future<OrderResult> submitOrder(Order order) {
            return executor.submit(() -> processOrder(order));
        }

        private OrderResult processOrder(Order order) {
            long start = System.currentTimeMillis();
            try {
                // Simulate I/O-bound work: DB write, payment gateway, etc.
                Thread.sleep(50 + order.getQuantity() % 50);
                processedCount.incrementAndGet();
                return new OrderResult(order.getOrderId(), true,
                        System.currentTimeMillis() - start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedCount.incrementAndGet();
                return new OrderResult(order.getOrderId(), false,
                        System.currentTimeMillis() - start);
            }
        }

        /**
         * Two-phase shutdown — the correct pattern, always.
         *
         * Phase 1: shutdown() stops accepting new tasks. In-flight tasks run
         *          to completion normally. New submits throw RejectedExecutionException.
         *
         * Phase 2: awaitTermination() waits up to 30s for all tasks to finish.
         *          If they don't finish in time, shutdownNow() force-interrupts them.
         *
         * WHY two phases: shutdown() alone doesn't wait. awaitTermination() alone
         * doesn't stop the pool. Together they give "finish gracefully, but not forever."
         */
        public void shutdown() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    System.out.println("  [Pool] Tasks didn't finish in 30s — forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        public int getProcessedCount() { return processedCount.get(); }
        public int getFailedCount()    { return failedCount.get(); }
    }

    // =========================================================================
    // SECTION 2: Future<T> — the receipt from an async submission
    // =========================================================================

    /**
     * FutureDemo shows all key Future operations.
     *
     * Think of Future like a restaurant receipt:
     *   - You order (submit the task) and get a receipt (Future)
     *   - You sit down and do other work
     *   - isDone() = "is my order ready?"
     *   - get() = "I'm hungry — block until it arrives"
     *   - get(timeout) = "I'll wait at most 5 minutes, then leave"
     *   - cancel() = "I changed my mind — cancel my order"
     */
    static class FutureDemo {

        private final ExecutorService executor = Executors.newFixedThreadPool(3);

        public void demonstrateFuture() throws InterruptedException {
            System.out.println("  Submitting 3 tasks...");

            // Task 1: fast — will be done by the time we call get()
            Future<String> fast = executor.submit(() -> {
                Thread.sleep(20);
                return "fast-result";
            });

            // Task 2: slow — get() will block waiting for it
            Future<String> slow = executor.submit(() -> {
                Thread.sleep(300);
                return "slow-result";
            });

            // Task 3: we'll cancel this one before it finishes
            Future<String> toCancel = executor.submit(() -> {
                Thread.sleep(5000);
                return "never-returned";
            });

            // Do other work while tasks run asynchronously
            System.out.println("  [main] tasks submitted — doing other work...");
            Thread.sleep(50);

            // Check fast task without blocking
            System.out.println("  fast.isDone() = " + fast.isDone()); // likely true

            try {
                System.out.println("  fast.get()   = " + fast.get()); // won't block
                System.out.println("  slow.get()   = " + slow.get()); // blocks ~250ms more
            } catch (ExecutionException e) {
                // Task threw an exception — it's wrapped in ExecutionException
                System.out.println("  Task failed: " + e.getCause().getMessage());
            }

            // Cancel the long task — true = interrupt if already running
            boolean cancelled = toCancel.cancel(true);
            System.out.println("  toCancel.cancel() = " + cancelled);
            System.out.println("  toCancel.isCancelled() = " + toCancel.isCancelled());

            // get() with timeout — don't wait more than 100ms
            Future<String> timeoutTask = executor.submit(() -> {
                Thread.sleep(2000);
                return "too-late";
            });
            try {
                timeoutTask.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                System.out.println("  Timed out after 100ms — task still running");
                timeoutTask.cancel(true);
            } catch (ExecutionException e) {
                System.out.println("  Execution failed: " + e.getCause());
            }

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // SECTION 3: Cached Thread Pool — elastic, danger zone for slow tasks
    // =========================================================================

    /**
     * CachedPoolDemo shows the cached pool's elastic thread creation.
     *
     * newCachedThreadPool() creates a new thread for every submitted task
     * if no idle thread is available. Idle threads expire after 60 seconds.
     *
     * CORRECT use: short-lived tasks that complete quickly (< a few seconds).
     * The pool self-sizes to demand.
     *
     * DANGEROUS use: tasks that block on slow I/O for seconds. Under sustained
     * load, you'll create hundreds or thousands of threads — ~1MB stack each.
     * 5000 threads = ~5GB of stack memory + OS scheduler thrashing.
     */
    static class CachedPoolDemo {
        public void demonstrateCachedPool() throws InterruptedException {
            ExecutorService cached = Executors.newCachedThreadPool();

            System.out.println("  Submitting 10 quick tasks to cached pool...");

            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                final int taskId = i;
                // Quick tasks — cached pool shines here
                futures.add(cached.submit(() -> {
                    Thread.sleep(20); // short I/O simulation
                    return taskId * taskId;
                }));
            }

            // Collect results
            int sum = 0;
            for (Future<Integer> f : futures) {
                try {
                    sum += f.get();
                } catch (ExecutionException e) {
                    System.out.println("  Task failed: " + e.getCause());
                }
            }
            System.out.println("  Sum of squares 0..9 = " + sum + " (expected: 285)");

            cached.shutdown();
            cached.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // SECTION 4: Single Thread Executor — sequential, ordered execution
    // =========================================================================

    /**
     * AuditLogger uses a single-thread executor to write all audit entries
     * to a file in strict submission order without any external synchronization.
     *
     * WHY single-thread executor instead of synchronized:
     *   synchronized makes concurrent threads wait at a barrier — there are
     *   still multiple threads competing. SingleThreadExecutor has ONE thread:
     *   no concurrency exists at all. The file is never accessed by two threads
     *   simultaneously, by construction.
     *
     * This pattern is perfect for:
     *   - Writing to a single file or append-only log
     *   - Driving a non-thread-safe legacy component
     *   - Sequencing database migrations
     */
    static class AuditLogger {

        // One thread — all writes are sequential in submission order, always
        private final ExecutorService writer = Executors.newSingleThreadExecutor();
        private final List<String> logBuffer = new ArrayList<>(); // NOT thread-safe
        // logBuffer is safe because it is ONLY ever touched by the single writer thread

        /**
         * Submits a log entry for writing. Returns immediately.
         * The entry will be written in the order this method was called,
         * relative to all other callers — no external synchronization needed.
         */
        public void log(String entry) {
            writer.submit(() -> {
                // Only one thread ever reaches here — logBuffer is safe
                logBuffer.add("[" + Thread.currentThread().getName() + "] " + entry);
            });
        }

        public void shutdown() throws InterruptedException {
            writer.shutdown();
            writer.awaitTermination(5, TimeUnit.SECONDS);
        }

        public List<String> getLog() { return Collections.unmodifiableList(logBuffer); }
    }

    // =========================================================================
    // SECTION 5: Scheduled Thread Pool — delayed and recurring tasks
    // =========================================================================

    /**
     * MetricsReporter demonstrates scheduled task execution.
     *
     * scheduleAtFixedRate: period measured from START of each run.
     *   If the task takes longer than the period, the next run starts
     *   immediately after (no overlap, but it "catches up").
     *   Use for: "report metrics every 10 seconds on the clock."
     *
     * scheduleWithFixedDelay: period measured from END of each run.
     *   Always waits the full delay after the previous run finishes.
     *   Use for: "poll the database 30 seconds after each poll finishes."
     */
    static class MetricsReporter {
        private final ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(2);
        private final AtomicInteger reportCount = new AtomicInteger(0);

        public void start() {
            // Run immediately, then every 200ms
            // scheduleAtFixedRate: period from START of each execution
            scheduler.scheduleAtFixedRate(() -> {
                int count = reportCount.incrementAndGet();
                System.out.println("  [Metrics-AtFixedRate #" + count + "] "
                        + Thread.currentThread().getName());
            }, 0, 200, TimeUnit.MILLISECONDS);

            // Run once after a 100ms delay
            scheduler.schedule(() ->
                System.out.println("  [One-shot] ran after 100ms delay"),
                100, TimeUnit.MILLISECONDS);
        }

        public void stop() throws InterruptedException {
            scheduler.shutdown();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }

        public int getReportCount() { return reportCount.get(); }
    }

    // =========================================================================
    // SECTION 6: ConcurrentHashMap — bucket-level locking + atomic ops
    // =========================================================================

    /**
     * InventoryManager demonstrates ConcurrentHashMap's concurrent reads,
     * bucket-level writes, and atomic compound operations.
     *
     * WHY ConcurrentHashMap over synchronizedMap:
     *   - Reads are lock-free (volatile reads) — no thread blocking on reads
     *   - Writes lock only the affected bucket, not the whole map
     *   - merge(), computeIfAbsent(), compute() are atomic — no get+put races
     *
     * WHY merge() for stock updates:
     *   The broken pattern: get() + put() has a gap between them.
     *   Thread A reads 50. Thread B reads 50. Both add 10. Both write 60.
     *   Result: 60 instead of 70. One update lost.
     *   merge() atomically: reads current value, applies function, writes —
     *   all as one uninterruptible operation. No gap. No lost updates.
     */
    static class InventoryManager {

        private final ConcurrentHashMap<String, Integer> stock =
                new ConcurrentHashMap<>();

        /**
         * Updates stock for a product atomically.
         * merge() is the right tool: it handles both the "key absent" case
         * (treats existing value as 0) and the "key present" case (applies
         * Integer::sum) in one atomic operation.
         */
        public void updateStock(String productId, int delta) {
            stock.merge(productId, delta, Integer::sum);
        }

        /**
         * Initializes stock only if not already present.
         * computeIfAbsent is atomic: check-and-put happens as one unit.
         * No double-checked locking, no synchronization needed.
         */
        public void initializeStock(String productId, int initialQty) {
            stock.computeIfAbsent(productId, k -> initialQty);
        }

        /**
         * Atomically reserves stock: decrements only if sufficient quantity exists.
         * computeIfPresent is atomic: the read, check, and update are one unit.
         * Returns true if reservation succeeded.
         */
        public boolean reserveStock(String productId, int quantity) {
            // We use a flag array to communicate success out of the lambda
            boolean[] reserved = {false};
            stock.computeIfPresent(productId, (k, current) -> {
                if (current >= quantity) {
                    reserved[0] = true;
                    return current - quantity; // atomic decrement
                }
                return current; // not enough stock — no change
            });
            return reserved[0];
        }

        public int getStock(String productId) {
            return stock.getOrDefault(productId, 0);
        }

        public Map<String, Integer> getAllStock() {
            return Collections.unmodifiableMap(stock);
        }
    }

    // =========================================================================
    // SECTION 7: CopyOnWriteArrayList — snapshot-safe reads
    // =========================================================================

    /**
     * EventBus uses CopyOnWriteArrayList for its listener registry.
     *
     * WHY CopyOnWriteArrayList here:
     *   - Listeners are registered once at startup: O(n) write cost is paid once
     *   - Broadcast is called thousands of times per second: zero lock contention
     *   - Iterator is always safe — no ConcurrentModificationException ever
     *
     * WHY NOT CopyOnWriteArrayList for high-write workloads:
     *   add() copies the ENTIRE array. For a 10,000-element list, every add
     *   copies 10,000 elements. High-write use makes this catastrophically slow.
     *   Use LinkedBlockingQueue or ConcurrentLinkedQueue for those cases.
     */
    static class EventBus {

        // Written rarely (at startup), read constantly (on every event)
        private final CopyOnWriteArrayList<String> listeners =
                new CopyOnWriteArrayList<>();

        /**
         * Register a new listener. O(n) — copies the entire internal array.
         * Acceptable cost because this is called rarely.
         */
        public void register(String listener) {
            listeners.add(listener);
            System.out.println("  [EventBus] Registered: " + listener
                    + " (total: " + listeners.size() + ")");
        }

        public void unregister(String listener) {
            listeners.remove(listener);
        }

        /**
         * Broadcast an event to all listeners.
         *
         * The for-each loop captures the internal array reference at the
         * moment the loop starts. Any concurrent add() creates a NEW array
         * and swaps the reference — our loop is still iterating the original.
         *
         * Result: no ConcurrentModificationException, no locking, no blocking.
         */
        public void broadcast(String event) {
            // Iterator works on a snapshot — zero lock contention
            for (String listener : listeners) {
                System.out.println("  [EventBus] " + listener + " → " + event);
            }
        }

        public int getListenerCount() { return listeners.size(); }
    }

    // =========================================================================
    // SECTION 8: BlockingQueue — producer-consumer decoupling
    // =========================================================================

    /**
     * OrderPipeline demonstrates the producer-consumer pattern using
     * LinkedBlockingQueue.
     *
     * WHY BlockingQueue eliminates busy-waiting:
     *   Without BlockingQueue: while(queue.isEmpty()) Thread.sleep(10) — the
     *   consumer wastes CPU checking repeatedly, even under no load.
     *   With take(): the OS parks the consumer thread completely. It consumes
     *   zero CPU while waiting. The OS wakes it the instant an item arrives.
     *
     * WHY bounded queue (capacity=50):
     *   An unbounded queue lets producers race ahead indefinitely, consuming
     *   unbounded memory. A bounded queue creates natural back-pressure:
     *   when full, put() blocks the producer, slowing it to the consumer's pace.
     *
     * WHY LinkedBlockingQueue over ArrayBlockingQueue:
     *   LinkedBlockingQueue uses TWO locks: one for the head (take side)
     *   and one for the tail (put side). Producers and consumers rarely
     *   contend with each other. ArrayBlockingQueue uses ONE lock for both.
     */
    static class OrderPipeline {

        // Bounded at 50 — back-pressure kicks in if consumers fall behind
        private final BlockingQueue<Order> queue = new LinkedBlockingQueue<>(50);
        private volatile boolean running = true;

        /**
         * Producer: submit an order into the pipeline.
         * put() blocks if the queue is full — natural back-pressure.
         * The producer thread will sleep until a consumer makes space.
         */
        public void produce(Order order) throws InterruptedException {
            queue.put(order); // blocks if full — back-pressure on the producer
        }

        /**
         * Consumer: pull the next order from the pipeline.
         * take() blocks if the queue is empty — no busy-waiting.
         * The consumer thread parks until a producer adds work.
         */
        public Order consume() throws InterruptedException {
            return queue.take(); // parks if empty — zero CPU cost while waiting
        }

        /**
         * Non-blocking variant: try to insert but give up after 100ms.
         * Useful when the caller wants to detect back-pressure and apply
         * alternative logic (drop, retry, circuit-break).
         */
        public boolean tryProduce(Order order) throws InterruptedException {
            return queue.offer(order, 100, TimeUnit.MILLISECONDS);
        }

        /**
         * Non-blocking drain: try to get an item but give up after 200ms.
         * Returns null if nothing arrived in time — caller can check for
         * shutdown signals without blocking indefinitely.
         */
        public Order tryConsume() throws InterruptedException {
            return queue.poll(200, TimeUnit.MILLISECONDS);
        }

        public void stop() { running = false; }
        public boolean isRunning() { return running; }
        public int getQueueSize() { return queue.size(); }
    }

    // =========================================================================
    // SECTION 9: PriorityBlockingQueue — priority-ordered consumption
    // =========================================================================

    /**
     * PriorityOrderQueue processes high-priority orders before low-priority ones,
     * regardless of submission order.
     *
     * take() always returns the element with the smallest priority value
     * (priority 1 = highest urgency, comes out first).
     *
     * WHY PriorityBlockingQueue:
     *   Regular BlockingQueue is FIFO. PriorityBlockingQueue is sorted.
     *   The consumer always processes the most urgent task available.
     *
     * NOTE: PriorityBlockingQueue is UNBOUNDED. Add your own size tracking
     * if you need back-pressure (it has no capacity constructor argument).
     */
    static class PriorityOrderQueue {

        // Comparator: lower priority number = higher urgency = comes out first
        private final PriorityBlockingQueue<Order> queue =
                new PriorityBlockingQueue<>(16,
                        Comparator.comparingInt(Order::getPriority));

        public void submit(Order order) {
            queue.offer(order);
        }

        public Order next() throws InterruptedException {
            return queue.take(); // always returns highest-priority (lowest number) first
        }

        public int size() { return queue.size(); }
    }

    // =========================================================================
    // MAIN — run all demos in sequence
    // =========================================================================

    public static void main(String[] args) throws Exception {

        System.out.println("=".repeat(65));
        System.out.println("THREAD POOLS AND CONCURRENT COLLECTIONS DEMO");
        System.out.println("=".repeat(65));

        // ------------------------------------------------------------------ //
        // Demo 1: Fixed Thread Pool + Future — order processing system        //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 1: Fixed thread pool (4 threads) — 12 orders ---");

        OrderProcessingSystem system = new OrderProcessingSystem();
        List<Future<OrderResult>> futures = new ArrayList<>();

        for (int i = 1; i <= 12; i++) {
            Order order = new Order("ORD-" + String.format("%03d", i),
                    "USR-" + (i % 3 + 1), i * 5, i % 3 + 1);
            futures.add(system.submitOrder(order));
        }
        System.out.println("  All 12 orders submitted — collecting results...");

        int successCount = 0;
        for (Future<OrderResult> future : futures) {
            try {
                OrderResult result = future.get(5, TimeUnit.SECONDS);
                System.out.println("  " + result);
                if (result.success) successCount++;
            } catch (TimeoutException e) {
                System.out.println("  Order timed out");
                future.cancel(true);
            } catch (ExecutionException e) {
                System.out.println("  Order failed: " + e.getCause().getMessage());
            }
        }
        system.shutdown();
        System.out.println("  Processed: " + system.getProcessedCount()
                + " | Failed: " + system.getFailedCount());

        // ------------------------------------------------------------------ //
        // Demo 2: Future — all key operations                                 //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 2: Future<T> — isDone, get, cancel, timeout ---");
        new FutureDemo().demonstrateFuture();

        // ------------------------------------------------------------------ //
        // Demo 3: Cached thread pool — elastic short tasks                    //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 3: Cached thread pool — elastic thread creation ---");
        new CachedPoolDemo().demonstrateCachedPool();

        // ------------------------------------------------------------------ //
        // Demo 4: Single thread executor — sequential audit log               //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 4: Single thread executor — sequential audit log ---");

        AuditLogger logger = new AuditLogger();
        List<Thread> loggers = new ArrayList<>();

        // 5 threads all submit log entries concurrently
        for (int i = 0; i < 5; i++) {
            final int id = i;
            loggers.add(new Thread(() -> {
                for (int j = 0; j < 3; j++) {
                    logger.log("Thread-" + id + " entry-" + j);
                }
            }));
        }
        loggers.forEach(Thread::start);
        for (Thread t : loggers) t.join();

        logger.shutdown();
        System.out.println("  Log entries written (sequential — no interleaving):");
        logger.getLog().forEach(e -> System.out.println("  " + e));

        // ------------------------------------------------------------------ //
        // Demo 5: Scheduled thread pool — recurring metrics                   //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 5: Scheduled pool — recurring task (scheduleAtFixedRate) ---");

        MetricsReporter reporter = new MetricsReporter();
        reporter.start();
        Thread.sleep(700); // let it run a few times
        reporter.stop();
        System.out.println("  Reports fired: " + reporter.getReportCount()
                + " (expected ~3 in 700ms with 200ms interval)");

        // ------------------------------------------------------------------ //
        // Demo 6: ConcurrentHashMap — concurrent inventory updates            //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 6: ConcurrentHashMap — concurrent inventory ---");

        InventoryManager inventory = new InventoryManager();
        inventory.initializeStock("SKU-A", 100);
        inventory.initializeStock("SKU-B", 50);

        // 20 threads concurrently update inventory
        List<Thread> stockThreads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // 10 threads each add 5 to SKU-A (total +50)
            stockThreads.add(new Thread(() -> inventory.updateStock("SKU-A", 5)));
            // 10 threads each remove 2 from SKU-B (total -20)
            stockThreads.add(new Thread(() -> inventory.updateStock("SKU-B", -2)));
        }
        stockThreads.forEach(Thread::start);
        for (Thread t : stockThreads) t.join();

        System.out.println("  SKU-A stock: " + inventory.getStock("SKU-A")
                + " (expected 150 = 100 + 10×5)");
        System.out.println("  SKU-B stock: " + inventory.getStock("SKU-B")
                + " (expected 30  = 50 - 10×2)");

        // Demonstrate atomic reservation
        System.out.println("  Reserve 25 of SKU-B: " + inventory.reserveStock("SKU-B", 25));
        System.out.println("  SKU-B after reservation: " + inventory.getStock("SKU-B")
                + " (expected 5)");
        System.out.println("  Reserve 10 of SKU-B (only 5 left): "
                + inventory.reserveStock("SKU-B", 10) + " (expected false)");

        // ------------------------------------------------------------------ //
        // Demo 7: CopyOnWriteArrayList — event listener broadcasting          //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 7: CopyOnWriteArrayList — event bus ---");

        EventBus bus = new EventBus();
        bus.register("InventoryService");
        bus.register("NotificationService");
        bus.register("AuditService");

        // Concurrent broadcast + registration — no ConcurrentModificationException
        Thread broadcastThread = new Thread(() -> {
            for (int i = 0; i < 2; i++) {
                bus.broadcast("ORDER_PLACED");
            }
        });
        Thread registerThread = new Thread(() ->
                bus.register("AnalyticsService")); // O(n) copy — safe concurrently

        broadcastThread.start();
        registerThread.start();
        broadcastThread.join();
        registerThread.join();

        System.out.println("  Final listener count: " + bus.getListenerCount());

        // ------------------------------------------------------------------ //
        // Demo 8: BlockingQueue — producer-consumer pipeline                  //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 8: LinkedBlockingQueue — producer-consumer ---");

        OrderPipeline pipeline = new OrderPipeline();
        AtomicInteger consumed = new AtomicInteger(0);

        // Producer: submits 10 orders then signals completion with a sentinel
        Order SENTINEL = new Order("DONE", "", 0, 99);
        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 10; i++) {
                    Order o = new Order("ORD-" + i, "USR-1", i, 1);
                    pipeline.produce(o);
                    System.out.println("  [Producer] enqueued " + o.getOrderId()
                            + " | queue size: " + pipeline.getQueueSize());
                    Thread.sleep(20); // produce slightly slower than consume
                }
                pipeline.produce(SENTINEL); // signal consumer to stop
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "producer");

        // Consumer: pulls orders until it sees the sentinel
        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    Order o = pipeline.consume(); // parks if empty — no busy-wait
                    if (o == SENTINEL) break;
                    consumed.incrementAndGet();
                    System.out.println("  [Consumer] processed " + o.getOrderId());
                    Thread.sleep(10); // consume faster than produce
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        System.out.println("  Total consumed: " + consumed.get() + " (expected 10)");

        // ------------------------------------------------------------------ //
        // Demo 9: PriorityBlockingQueue — priority order processing           //
        // ------------------------------------------------------------------ //
        System.out.println("\n--- Demo 9: PriorityBlockingQueue — priority ordering ---");

        PriorityOrderQueue priorityQueue = new PriorityOrderQueue();

        // Submit orders in random priority order
        priorityQueue.submit(new Order("ORD-LOW",    "USR-1", 10, 5)); // low priority
        priorityQueue.submit(new Order("ORD-URGENT", "USR-2", 1,  1)); // highest priority
        priorityQueue.submit(new Order("ORD-MED",    "USR-3", 5,  3)); // medium priority
        priorityQueue.submit(new Order("ORD-HIGH",   "USR-4", 3,  2)); // high priority
        priorityQueue.submit(new Order("ORD-LOWEST", "USR-5", 20, 9)); // lowest priority

        System.out.println("  Consuming in priority order (1=highest urgency):");
        for (int i = 0; i < 5; i++) {
            Order o = priorityQueue.next();
            System.out.println("  [Priority " + o.getPriority() + "] " + o.getOrderId());
        }

        // ------------------------------------------------------------------ //
        // Summary                                                              //
        // ------------------------------------------------------------------ //
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(65));
        System.out.println("Thread Pools:");
        System.out.println("  newFixedThreadPool(n)        → bounded concurrency, predictable");
        System.out.println("  newCachedThreadPool()         → elastic, short tasks only");
        System.out.println("  newSingleThreadExecutor()     → strict serial FIFO order");
        System.out.println("  newScheduledThreadPool(n)     → delay + recurring schedules");
        System.out.println("  Always shutdown in two phases: shutdown() + awaitTermination()");
        System.out.println();
        System.out.println("Thread-Safe Collections:");
        System.out.println("  ConcurrentHashMap             → lock-free reads, merge/compute");
        System.out.println("  CopyOnWriteArrayList          → lock-free reads, O(n) writes");
        System.out.println("  LinkedBlockingQueue           → blocking put/take, back-pressure");
        System.out.println("  PriorityBlockingQueue         → sorted take(), priority ordering");
        System.out.println();
        System.out.println("Rule: never add synchronization to standard collections.");
        System.out.println("      use the collection designed for the access pattern.");
        System.out.println("=".repeat(65));
    }
}

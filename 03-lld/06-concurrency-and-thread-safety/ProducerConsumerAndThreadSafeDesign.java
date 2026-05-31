import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * ProducerConsumerAndThreadSafeDesign.java
 *
 * Covers Topics 10 and 11:
 *   - Topic 10: Producer-Consumer Pattern (BlockingQueue, back-pressure, graceful shutdown)
 *   - Topic 11: Thread-Safe Class Design  (Rate Limiter — lock striping, computeIfAbsent,
 *                                          per-client synchronized window)
 *
 * Demos:
 *   1. Basic producer-consumer with LinkedBlockingQueue
 *   2. Back-pressure: producer blocks when queue is full
 *   3. Graceful shutdown — drain queue before stopping consumers
 *   4. Poison pill shutdown pattern
 *   5. Priority consumer — PriorityBlockingQueue
 *   6. Rate limiter: per-client fixed-window, concurrent stress test
 *   7. Rate limiter: two clients are independent (no cross-client contention)
 */
public class ProducerConsumerAndThreadSafeDesign {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Demo 1: Basic Producer-Consumer ===");
        Demo1_BasicProducerConsumer.run();

        System.out.println("\n=== Demo 2: Back-Pressure (Producer Blocks on Full Queue) ===");
        Demo2_BackPressure.run();

        System.out.println("\n=== Demo 3: Graceful Shutdown (Drain Before Stop) ===");
        Demo3_GracefulShutdown.run();

        System.out.println("\n=== Demo 4: Poison Pill Shutdown Pattern ===");
        Demo4_PoisonPill.run();

        System.out.println("\n=== Demo 5: Priority Queue Consumer ===");
        Demo5_PriorityConsumer.run();

        System.out.println("\n=== Demo 6: Rate Limiter — Concurrent Stress Test ===");
        Demo6_RateLimiterStress.run();

        System.out.println("\n=== Demo 7: Rate Limiter — Client Independence ===");
        Demo7_RateLimiterClientIsolation.run();
    }


    // ─────────────────────────────────────────────────────────────────
    // SHARED: Immutable work item — no synchronization ever needed on it
    // ─────────────────────────────────────────────────────────────────

    /**
     * PaymentTask — the unit of work passed through the queue.
     *
     * All fields are final. Once created, a PaymentTask cannot be modified.
     * Any thread can read its fields freely with zero locking overhead —
     * immutable objects have no race conditions by definition.
     */
    static final class PaymentTask {
        private final String taskId;
        private final String customerId;
        private final double amount;

        PaymentTask(String taskId, String customerId, double amount) {
            this.taskId     = taskId;
            this.customerId = customerId;
            this.amount     = amount;
        }

        String getTaskId()     { return taskId; }
        String getCustomerId() { return customerId; }
        double getAmount()     { return amount; }

        @Override
        public String toString() {
            return String.format("Task[%s, customer=%s, amount=%.0f]",
                taskId, customerId, amount);
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // DEMO 1 — Basic Producer-Consumer
    // ─────────────────────────────────────────────────────────────────

    static class Demo1_BasicProducerConsumer {

        /**
         * Producer: generates PaymentTasks and puts them in the queue.
         *
         * put() is the KEY operation — it blocks if the queue is full.
         * This creates natural back-pressure: a slow consumer automatically
         * slows the producer without any explicit coordination.
         */
        static class Producer implements Runnable {
            private final BlockingQueue<PaymentTask> queue;
            private final String producerId;
            private final int taskCount;
            private static final AtomicInteger idCounter = new AtomicInteger(0);

            Producer(BlockingQueue<PaymentTask> queue, String producerId, int taskCount) {
                this.queue      = queue;
                this.producerId = producerId;
                this.taskCount  = taskCount;
            }

            @Override
            public void run() {
                for (int i = 0; i < taskCount; i++) {
                    PaymentTask task = new PaymentTask(
                        producerId + "-T" + idCounter.incrementAndGet(),
                        "CUST-" + (i % 5),
                        100.0 + i
                    );
                    try {
                        queue.put(task);  // blocks if queue is full
                        System.out.println("  [" + producerId + "] Enqueued: " + task.getTaskId());
                        Thread.sleep(30); // simulate real-world arrival rate
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /**
         * Consumer: pulls tasks from the queue and processes them.
         *
         * poll(timeout) is preferred over take() for graceful shutdown:
         * - take() would block forever if the queue becomes empty permanently
         * - poll(100ms) returns null, allowing the consumer to check 'running'
         *
         * The loop condition "running || !queue.isEmpty()" ensures:
         * - We keep running until the flag says stop AND the queue is drained
         * - This prevents silent data loss when shutdown is signalled mid-queue
         */
        static class Consumer implements Runnable {
            private final BlockingQueue<PaymentTask> queue;
            private final String consumerId;
            private volatile boolean running = true;
            final AtomicInteger processed = new AtomicInteger(0);

            Consumer(BlockingQueue<PaymentTask> queue, String consumerId) {
                this.queue      = queue;
                this.consumerId = consumerId;
            }

            void stop() { running = false; }

            @Override
            public void run() {
                // Keep going while: (a) still running, OR (b) queue has items left to drain
                while (running || !queue.isEmpty()) {
                    try {
                        // poll with timeout — won't park forever; lets us re-check 'running'
                        PaymentTask task = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (task == null) continue; // queue was empty during this window

                        // Simulate processing time (I/O-bound work)
                        Thread.sleep(80);
                        processed.incrementAndGet();
                        System.out.println("  [" + consumerId + "] Processed: " + task.getTaskId());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        static void run() throws InterruptedException {
            // Bounded queue — capacity 5 means at most 5 tasks buffer before back-pressure
            BlockingQueue<PaymentTask> queue = new LinkedBlockingQueue<>(5);

            Consumer c1 = new Consumer(queue, "Consumer-1");
            Consumer c2 = new Consumer(queue, "Consumer-2");

            ExecutorService producerPool  = Executors.newFixedThreadPool(2);
            ExecutorService consumerPool  = Executors.newFixedThreadPool(2);

            // Start consumers FIRST — they wait ready; producers fill the queue
            consumerPool.submit(c1);
            consumerPool.submit(c2);

            // Start two producers, each submitting 5 tasks
            List<Future<?>> producerFutures = new ArrayList<>();
            producerFutures.add(producerPool.submit(new Producer(queue, "P1", 5)));
            producerFutures.add(producerPool.submit(new Producer(queue, "P2", 5)));

            // Step 1: wait for all producers to finish enqueueing
            for (Future<?> f : producerFutures) {
                try { f.get(); } catch (ExecutionException e) { e.printStackTrace(); }
            }
            producerPool.shutdown();

            // Step 2: signal consumers to drain and stop
            c1.stop();
            c2.stop();

            // Step 3: await all consumer threads to exit
            consumerPool.shutdown();
            consumerPool.awaitTermination(10, TimeUnit.SECONDS);

            int total = c1.processed.get() + c2.processed.get();
            System.out.println("  Total processed: " + total + " / 10");
            assert total == 10 : "All tasks should be processed";
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // DEMO 2 — Back-Pressure: Producer Blocks on Full Queue
    // ─────────────────────────────────────────────────────────────────

    static class Demo2_BackPressure {
        static void run() throws InterruptedException {
            // Very small queue — capacity 2 — to make back-pressure obvious
            BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);

            // Slow consumer — takes 300ms per item
            Thread consumer = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        String item = queue.poll(500, TimeUnit.MILLISECONDS);
                        if (item == null) break;
                        Thread.sleep(300);
                        System.out.println("  [Consumer] Processed: " + item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            consumer.start();

            // Fast producer — tries to submit 6 items immediately
            // Will block on put() once the queue of 2 is full
            for (int i = 1; i <= 6; i++) {
                long before = System.currentTimeMillis();
                queue.put("item-" + i); // MAY BLOCK HERE if queue is full
                long waited = System.currentTimeMillis() - before;
                System.out.println("  [Producer] Enqueued item-" + i
                    + (waited > 50 ? "  ← blocked " + waited + "ms (back-pressure)" : ""));
            }

            consumer.interrupt();
            consumer.join(3000);
            System.out.println("  Back-pressure demo complete");
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // DEMO 3 — Graceful Shutdown: Drain Queue Before Stopping Consumers
    // ─────────────────────────────────────────────────────────────────

    static class Demo3_GracefulShutdown {

        static class Task { final int id; Task(int id) { this.id = id; } }

        static void run() throws InterruptedException {
            BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
            AtomicInteger processed = new AtomicInteger(0);
            // AtomicBoolean: effectively final, lambda-friendly, thread-safe writes
            AtomicBoolean running = new AtomicBoolean(true);

            // Pre-fill the queue with 8 tasks BEFORE starting consumers
            // This simulates a burst that needs to be fully drained on shutdown
            for (int i = 1; i <= 8; i++) queue.put(new Task(i));
            System.out.println("  Pre-filled queue with 8 tasks");

            // Consumer: processes tasks, stops when signalled AND queue is empty
            Runnable consumer = () -> {
                while (running.get() || !queue.isEmpty()) {
                    try {
                        Task t = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (t == null) continue;
                        Thread.sleep(50);
                        processed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            };

            ExecutorService pool = Executors.newFixedThreadPool(2);
            pool.submit(consumer);
            pool.submit(consumer);

            // Signal stop almost immediately — before all tasks are processed
            Thread.sleep(150);
            running.set(false);
            System.out.println("  Shutdown signal sent (running=false)");

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            System.out.println("  Tasks processed: " + processed.get() + " / 8");
            assert processed.get() == 8 : "All 8 tasks must be drained before exit";
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // DEMO 4 — Poison Pill Shutdown Pattern
    // ─────────────────────────────────────────────────────────────────

    static class Demo4_PoisonPill {

        /**
         * Poison pill: a sentinel task that signals consumers to stop.
         * Travels through the queue in order — consumers see it only after
         * processing every real task before it. Cleaner than a volatile flag
         * when using take() instead of poll(timeout).
         */
        static final PaymentTask POISON = new PaymentTask("__POISON__", null, -1);

        static void run() throws InterruptedException {
            BlockingQueue<PaymentTask> queue = new LinkedBlockingQueue<>();
            AtomicInteger processed = new AtomicInteger(0);
            int CONSUMER_COUNT = 2;

            // Consumer using take() — blocks until something arrives
            // Exits cleanly when it sees the POISON pill
            Runnable consumer = () -> {
                while (true) {
                    try {
                        PaymentTask task = queue.take(); // blocks — no timeout needed
                        if (task == POISON) {
                            // Re-publish so other consumers also see the poison
                            queue.put(POISON);
                            System.out.println("  [" + Thread.currentThread().getName()
                                + "] Saw poison pill — exiting");
                            return;
                        }
                        processed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            };

            ExecutorService pool = Executors.newFixedThreadPool(CONSUMER_COUNT);
            pool.submit(consumer);
            pool.submit(consumer);

            // Producer submits 6 tasks then drops the poison pill
            for (int i = 1; i <= 6; i++) {
                queue.put(new PaymentTask("T" + i, "CUST-1", 100.0 * i));
                System.out.println("  [Producer] Enqueued T" + i);
            }
            queue.put(POISON); // signal: no more tasks coming
            System.out.println("  [Producer] Poison pill sent");

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("  Tasks processed: " + processed.get() + " / 6");
            assert processed.get() == 6;
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // DEMO 5 — Priority Queue Consumer
    // ─────────────────────────────────────────────────────────────────

    static class Demo5_PriorityConsumer {

        /**
         * PriorityTask: a task with an integer priority.
         * Lower number = higher priority (like a hospital triage system).
         * PriorityBlockingQueue.take() always returns the highest-priority item.
         */
        static final class PriorityTask implements Comparable<PriorityTask> {
            final int priority;
            final String name;

            PriorityTask(int priority, String name) {
                this.priority = priority;
                this.name     = name;
            }

            @Override
            public int compareTo(PriorityTask other) {
                return Integer.compare(this.priority, other.priority); // lower = higher priority
            }

            @Override
            public String toString() { return name + "(priority=" + priority + ")"; }
        }

        static void run() throws InterruptedException {
            // PriorityBlockingQueue: unbounded, take() always returns lowest priority value
            BlockingQueue<PriorityTask> queue = new PriorityBlockingQueue<>();

            // Enqueue in random order
            queue.put(new PriorityTask(5, "LowPriority"));
            queue.put(new PriorityTask(1, "Critical"));
            queue.put(new PriorityTask(3, "Medium"));
            queue.put(new PriorityTask(2, "High"));
            queue.put(new PriorityTask(9, "Lowest"));

            // Drain and verify ordering
            System.out.print("  Processing order: ");
            while (!queue.isEmpty()) {
                PriorityTask task = queue.take();
                System.out.print(task.name + " ");
            }
            System.out.println();
            System.out.println("  ✓ Critical processed before High before Medium...");
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // RATE LIMITER — used by Demo 6 and Demo 7
    // ─────────────────────────────────────────────────────────────────

    /**
     * RateLimiter — Thread-safe, per-client, fixed-window rate limiter.
     *
     * Design: Two-level concurrency
     *   Level 1: ConcurrentHashMap for client isolation
     *            - computeIfAbsent atomically creates one ClientWindow per client
     *            - No cross-client contention: client-A and client-B use different locks
     *
     *   Level 2: synchronized on ClientWindow for per-client atomicity
     *            - requestCount and windowStart updated together as one atomic unit
     *            - synchronized on 'this' instance — different clients use different locks
     */
    static class RateLimiter {

        private final int maxRequests;  // final — immutable — any thread reads freely
        private final long windowMs;    // final — immutable — any thread reads freely

        // One ClientWindow per client — initialized on first access
        private final ConcurrentHashMap<String, ClientWindow> clientWindows
            = new ConcurrentHashMap<>();

        RateLimiter(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs    = windowMs;
        }

        /**
         * Returns true if the request is allowed; false if rate limited.
         *
         * No lock is held at this level — the method itself is not synchronized.
         * All synchronization is pushed down to the per-client ClientWindow.
         */
        boolean allowRequest(String clientId) {
            // computeIfAbsent is atomic:
            //   - If clientId is new: creates a ClientWindow, stores it, returns it
            //   - If clientId exists: returns the existing ClientWindow
            //   - If two threads race on the same new clientId: exactly ONE window created
            ClientWindow window = clientWindows.computeIfAbsent(
                clientId, id -> new ClientWindow(maxRequests, windowMs)
            );
            return window.tryConsume();
        }

        int windowCount() { return clientWindows.size(); }


        /**
         * ClientWindow — holds the per-client rate limiting state.
         *
         * Why static? It doesn't need a reference to the outer RateLimiter.
         * Static inner classes are generally preferred over non-static when
         * the inner class doesn't use the outer class's instance fields.
         *
         * Lock: synchronized on 'this' (the ClientWindow instance).
         * Each client has their own ClientWindow → their own lock → no cross-client blocking.
         */
        private static class ClientWindow {

            private final int  maxRequests;  // immutable — set once in constructor
            private final long windowMs;     // immutable — set once in constructor

            // Mutable state — must be accessed only under this object's monitor
            private int  requestCount;
            private long windowStart;

            ClientWindow(int maxRequests, long windowMs) {
                this.maxRequests  = maxRequests;
                this.windowMs     = windowMs;
                this.requestCount = 0;
                this.windowStart  = System.currentTimeMillis();
            }

            /**
             * Atomically: check window expiry → maybe reset → check limit → maybe increment.
             *
             * Why synchronized and not AtomicInteger?
             * requestCount and windowStart must change TOGETHER as one unit.
             * AtomicInteger protects one variable; synchronized protects a whole critical section.
             * If we used AtomicInteger, two threads could both reset windowStart independently,
             * causing window drift and incorrect time calculations.
             */
            synchronized boolean tryConsume() {
                long now = System.currentTimeMillis();

                // If the current window has expired, start a fresh one
                if (now - windowStart >= windowMs) {
                    requestCount = 0;
                    windowStart  = now;
                }

                // Check if quota remains in this window
                if (requestCount < maxRequests) {
                    requestCount++;
                    return true;  // allowed
                }
                return false; // rate limited
            }

            // For testing/assertions only
            synchronized int getRequestCount() { return requestCount; }
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // DEMO 6 — Rate Limiter: Concurrent Stress Test
    // ─────────────────────────────────────────────────────────────────

    static class Demo6_RateLimiterStress {
        static void run() throws InterruptedException {
            // Allow 5 requests per second per client
            RateLimiter limiter = new RateLimiter(5, 1000);

            ExecutorService pool = Executors.newFixedThreadPool(20);
            AtomicInteger allowed = new AtomicInteger(0);
            AtomicInteger denied  = new AtomicInteger(0);

            // Fire 20 concurrent requests from a single client — only 5 should be allowed
            CountDownLatch latch = new CountDownLatch(20);
            for (int i = 0; i < 20; i++) {
                pool.submit(() -> {
                    boolean ok = limiter.allowRequest("stress-client");
                    if (ok) allowed.incrementAndGet();
                    else    denied.incrementAndGet();
                    latch.countDown();
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            pool.shutdown();

            System.out.println("  20 concurrent requests from 1 client (limit=5/sec):");
            System.out.println("  Allowed: " + allowed.get() + "  Denied: " + denied.get());
            assert allowed.get() == 5  : "Exactly 5 should be allowed";
            assert denied.get()  == 15 : "Exactly 15 should be denied";
            System.out.println("  ✓ Exactly 5 allowed, 15 denied");
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // DEMO 7 — Rate Limiter: Client Independence (No Cross-Client Contention)
    // ─────────────────────────────────────────────────────────────────

    static class Demo7_RateLimiterClientIsolation {
        static void run() throws InterruptedException {
            // 3 requests per second per client
            RateLimiter limiter = new RateLimiter(3, 1000);

            int CLIENT_COUNT   = 4;
            int REQUESTS_EACH  = 10;

            ExecutorService pool = Executors.newFixedThreadPool(CLIENT_COUNT * 3);

            // Track allowed/denied per client independently
            Map<String, AtomicInteger> allowedPerClient = new ConcurrentHashMap<>();
            Map<String, AtomicInteger> deniedPerClient  = new ConcurrentHashMap<>();

            for (int c = 0; c < CLIENT_COUNT; c++) {
                String clientId = "client-" + c;
                allowedPerClient.put(clientId, new AtomicInteger(0));
                deniedPerClient.put(clientId,  new AtomicInteger(0));
            }

            CountDownLatch latch = new CountDownLatch(CLIENT_COUNT * REQUESTS_EACH);

            // Each client fires REQUESTS_EACH concurrent requests
            for (int c = 0; c < CLIENT_COUNT; c++) {
                final String clientId = "client-" + c;
                for (int r = 0; r < REQUESTS_EACH; r++) {
                    pool.submit(() -> {
                        boolean ok = limiter.allowRequest(clientId);
                        if (ok) allowedPerClient.get(clientId).incrementAndGet();
                        else    deniedPerClient.get(clientId).incrementAndGet();
                        latch.countDown();
                    });
                }
            }

            latch.await(5, TimeUnit.SECONDS);
            pool.shutdown();

            System.out.println("  Per-client results (limit=3/sec each):");
            for (int c = 0; c < CLIENT_COUNT; c++) {
                String clientId = "client-" + c;
                int a = allowedPerClient.get(clientId).get();
                int d = deniedPerClient.get(clientId).get();
                System.out.printf("  %s → allowed=%d  denied=%d%n", clientId, a, d);
                assert a == 3 : clientId + " should have exactly 3 allowed";
                assert d == 7 : clientId + " should have exactly 7 denied";
            }

            System.out.println("  ✓ Each client independently limited to 3. "
                + "No cross-client interference.");
            System.out.println("  ✓ Windows created: " + limiter.windowCount()
                + " (one per client)");
        }
    }
}

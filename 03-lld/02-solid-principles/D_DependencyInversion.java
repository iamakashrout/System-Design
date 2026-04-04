// ============================================================
//  D — Dependency Inversion Principle (DIP)
//
//  Principle : High-level modules should not depend on
//              low-level modules. Both should depend on
//              abstractions.
//
//  Two examples:
//  1. OrderService / Repository — swapping database implementations
//  2. NotificationService — swapping email / SMS / push
//
//  Key idea  : The interface is OWNED BY the business layer.
//              Infrastructure reaches UP to implement it.
//              Dependency arrow is inverted — hence the name.
// ============================================================

import java.util.HashMap;
import java.util.Map;

public class D_DependencyInversion {

    // ══════════════════════════════════════════════════════════════
    //  Domain object
    // ══════════════════════════════════════════════════════════════

    static class Order {
        String orderId;
        String item;
        double total;

        Order(String orderId, String item, double total) {
            this.orderId = orderId;
            this.item    = item;
            this.total   = total;
        }

        @Override
        public String toString() {
            return "Order(id=" + orderId + ", item='" + item + "', total=₹" + total + ")";
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  EXAMPLE 1 — OrderService / Repository
    // ══════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────
    //  VIOLATION — service creates its own dependency
    // ─────────────────────────────────────────────

    /** Simulated concrete MySQL repository. */
    static class MySQLOrderRepositoryViolation {
        void save(Order order) {
            System.out.println("  [MySQL]   Saving: " + order);
        }

        Order findById(String orderId) {
            System.out.println("  [MySQL]   Querying id='" + orderId + "'");
            return null; // simulated empty result
        }
    }

    /**
     * VIOLATION: Two problems in one line — new MySQLOrderRepositoryViolation()
     *   1. Field is a concrete class → tight coupling
     *   2. 'new' inside service → impossible to override
     *
     * Consequences:
     *   - Migrate to Postgres → must edit OrderService
     *   - Write a unit test   → need a real MySQL database
     *   - DB driver changes   → business logic is impacted
     */
    static class OrderServiceViolation {
        // Business logic is hardwired to MySQL. Cannot be overridden.
        private final MySQLOrderRepositoryViolation repo = new MySQLOrderRepositoryViolation();

        void placeOrder(Order order) {
            System.out.println("  [Service] Validating order: " + order.orderId);
            repo.save(order);
            System.out.println("  [Service] Order placed.");
        }
    }

    // ─────────────────────────────────────────────
    //  FIX — both layers depend on the abstraction
    // ─────────────────────────────────────────────

    /**
     * This interface LIVES IN the business layer — not infrastructure.
     * Infrastructure reaches up to implement it.
     * The dependency arrow is now inverted:
     *   Before: OrderService → MySQLRepository
     *   After:  OrderService → OrderRepository ← MySQLRepository
     */
    interface OrderRepository {
        void  save(Order order);
        Order findById(String orderId);
    }

    /**
     * Production implementation.
     * Changes ONLY when the MySQL layer changes.
     * OrderService is completely unaware of this class.
     */
    static class MySQLOrderRepository implements OrderRepository {
        private final Map<String, Order> db = new HashMap<>();

        @Override
        public void save(Order order) {
            db.put(order.orderId, order);
            System.out.println("  [MySQL]    INSERT: " + order);
        }

        @Override
        public Order findById(String orderId) {
            System.out.println("  [MySQL]    SELECT WHERE id='" + orderId + "'");
            return db.get(orderId);
        }
    }

    /**
     * Test / development implementation — no database needed.
     * Inject this in unit tests: fast, isolated, deterministic.
     * Tests run in milliseconds, not seconds.
     */
    static class InMemoryOrderRepository implements OrderRepository {
        private final Map<String, Order> store = new HashMap<>();

        @Override
        public void save(Order order) {
            store.put(order.orderId, order);
            System.out.println("  [InMemory] Stored: " + order);
        }

        @Override
        public Order findById(String orderId) {
            System.out.println("  [InMemory] Lookup id='" + orderId + "'");
            return store.get(orderId);
        }

        // Test helper — lets tests assert on stored state
        int count() { return store.size(); }
    }

    /**
     * Future implementation — migrating from MySQL to Postgres.
     * Zero changes to OrderService required.
     * Just create this class and wire it up.
     */
    static class PostgresOrderRepository implements OrderRepository {
        private final Map<String, Order> db = new HashMap<>();

        @Override
        public void save(Order order) {
            db.put(order.orderId, order);
            System.out.println("  [Postgres] INSERT INTO orders: " + order);
        }

        @Override
        public Order findById(String orderId) {
            System.out.println("  [Postgres] SELECT * FROM orders WHERE id='" + orderId + "'");
            return db.get(orderId);
        }
    }

    /**
     * Business logic depends ONLY on the OrderRepository abstraction.
     * Zero knowledge of MySQL, Postgres, or InMemory.
     * No 'new' inside — dependency is injected by the caller.
     */
    static class OrderService {
        // Dependency injected via constructor — preferred pattern.
        // The field type is the INTERFACE, not a concrete class.
        private final OrderRepository repository;

        OrderService(OrderRepository repository) {
            this.repository = repository;
        }

        void placeOrder(Order order) {
            System.out.println("  [Service] Validating order: " + order.orderId);
            // Pure business logic — no infrastructure awareness
            repository.save(order);
            System.out.println("  [Service] Order placed ✓");
        }

        void printOrder(String orderId) {
            Order order = repository.findById(orderId);
            if (order != null) {
                System.out.println("  [Service] Found: " + order);
            } else {
                System.out.println("  [Service] Order '" + orderId + "' not found.");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  EXAMPLE 2 — NotificationService
    // ══════════════════════════════════════════════════════════════

    /**
     * Owned by the business layer.
     * Business logic doesn't know if it's email, SMS, or push.
     * It just knows: 'I can send a message to a user.'
     */
    interface NotificationSender {
        void   send(String userId, String message);
        String channelName();
    }

    static class EmailNotificationSender implements NotificationSender {
        @Override public void   send(String userId, String message) {
            System.out.println("  [Email  → " + userId + "] " + message);
        }
        @Override public String channelName() { return "Email"; }
    }

    static class SMSNotificationSender implements NotificationSender {
        @Override public void   send(String userId, String message) {
            System.out.println("  [SMS    → " + userId + "] " + message);
        }
        @Override public String channelName() { return "SMS"; }
    }

    static class PushNotificationSender implements NotificationSender {
        @Override public void   send(String userId, String message) {
            System.out.println("  [Push   → " + userId + "] " + message);
        }
        @Override public String channelName() { return "Push"; }
    }

    /**
     * Both dependencies — repository and notifier — are injected.
     * This service is fully decoupled from all infrastructure choices.
     */
    static class OrderNotificationService {
        private final OrderRepository    repository;
        private final NotificationSender notifier;

        OrderNotificationService(OrderRepository repository, NotificationSender notifier) {
            this.repository = repository;
            this.notifier   = notifier;
        }

        void placeAndNotify(Order order, String userId) {
            repository.save(order);
            notifier.send(userId,
                "Your order " + order.orderId + " is confirmed! Total: ₹" + order.total);
            System.out.println("  [Service] Notified via " + notifier.channelName() + " ✓");
        }
    }

    // ─────────────────────────────────────────────
    //  DEMO — violations and fixes
    // ─────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  Dependency Inversion Principle      ║");
        System.out.println("╚══════════════════════════════════════╝\n");

        // ── Violation ──
        System.out.println("── VIOLATION: service creates its own MySQL dependency ──");
        OrderServiceViolation badService = new OrderServiceViolation();
        badService.placeOrder(new Order("ORD-V01", "Laptop", 75000));
        System.out.println("  (Cannot test without real DB. Cannot swap DB without editing OrderService.)\n");

        // ── Fix: Production — MySQL ──
        System.out.println("── FIX: Production — injecting MySQLOrderRepository ──\n");
        OrderService prodService = new OrderService(new MySQLOrderRepository());
        prodService.placeOrder(new Order("ORD-001", "MacBook Pro",          120000));
        prodService.placeOrder(new Order("ORD-002", "Mechanical Keyboard",   8000));
        prodService.printOrder("ORD-001");
        prodService.printOrder("ORD-999"); // not found
        System.out.println();

        // ── Fix: Tests — InMemory ──
        System.out.println("── FIX: Tests — injecting InMemoryOrderRepository (no DB needed) ──\n");
        InMemoryOrderRepository testRepo    = new InMemoryOrderRepository();
        OrderService            testService = new OrderService(testRepo);
        testService.placeOrder(new Order("ORD-T01", "Test Item A", 500));
        testService.placeOrder(new Order("ORD-T02", "Test Item B", 750));
        testService.printOrder("ORD-T01");
        // In real tests (JUnit) you'd assert here:
        String result = (testRepo.count() == 2) ? "PASS ✓" : "FAIL ✗";
        System.out.println("  [Test] Orders saved: " + testRepo.count() + " — expected 2 → " + result);
        System.out.println();

        // ── Fix: Postgres migration ──
        System.out.println("── FIX: Migrating to Postgres — zero changes to OrderService ──\n");
        OrderService pgService = new OrderService(new PostgresOrderRepository());
        pgService.placeOrder(new Order("ORD-P01", "Monitor", 35000));
        pgService.printOrder("ORD-P01");
        System.out.println();

        // ── Notification channels ──
        System.out.println("── FIX: NotificationService — swapping channels ──\n");
        Order order = new Order("ORD-N01", "Wireless Headphones", 4500);
        NotificationSender[] channels = {
            new EmailNotificationSender(),
            new SMSNotificationSender(),
            new PushNotificationSender()
        };
        for (NotificationSender channel : channels) {
            OrderNotificationService svc =
                new OrderNotificationService(new InMemoryOrderRepository(), channel);
            svc.placeAndNotify(order, "akash@example.com");
            System.out.println();
        }

        System.out.println("✔ OrderService has zero knowledge of MySQL / Postgres / InMemory.");
        System.out.println("✔ Swap any implementation — business logic never changes.");
        System.out.println("✔ Tests run without a real database — fast and isolated.");
    }
}

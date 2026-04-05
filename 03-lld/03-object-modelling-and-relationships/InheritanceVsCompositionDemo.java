import java.util.ArrayList;
import java.util.List;

/**
 * INHERITANCE vs COMPOSITION IN PRACTICE
 *
 * This file demonstrates:
 *   1. When inheritance is CORRECT (genuine IS-A with stable type hierarchy)
 *   2. When inheritance is WRONG (using it just to share behavior)
 *   3. The right composition-based alternative
 *   4. The runtime type change problem — where inheritance fails completely
 *   5. The class explosion problem — composition's biggest win
 *
 * Core rule:
 *   Inheritance = "I AM a kind of that thing" (identity)
 *   Composition  = "I HAVE that capability"  (behavior assembly)
 *
 * Default rule: Start with composition.
 *   Switch to inheritance ONLY when:
 *   (a) IS-A is genuinely true and stable
 *   (b) You need polymorphism — passing subtype where parent type is expected
 */

// ══════════════════════════════════════════════════════════════════════════════
// PART 1 — INHERITANCE DONE RIGHT
// Genuine IS-A with a stable type hierarchy and behavioral reuse
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Notification is a genuine abstract type.
 * EmailNotification IS-A Notification. SmsNotification IS-A Notification.
 * This will always be true — the type relationship is stable and meaningful.
 *
 * Inheritance earns its place here because:
 * - Polymorphism works: you can pass EmailNotification wherever Notification is expected.
 * - Shared behavior (getSummary) is genuinely common to ALL notifications.
 * - The type relationship will never need to change at runtime.
 */
abstract class Notification {
    protected final String recipient;
    protected final String message;

    public Notification(String recipient, String message) {
        this.recipient = recipient;
        this.message = message;
    }

    // Abstract — each subtype MUST implement its own sending mechanism
    public abstract void send();

    // Concrete shared behavior — EmailNotification and SmsNotification both get this for free
    public String getSummary() {
        return "To: " + recipient + " | Message: " + message;
    }
}

class EmailNotification extends Notification {
    private final String subject;

    public EmailNotification(String recipient, String message, String subject) {
        super(recipient, message);
        this.subject = subject;
    }

    @Override
    public void send() {
        System.out.println("[Email] → " + recipient
                + " | Subject: " + subject
                + " | Body: " + message);
    }
}

class SmsNotification extends Notification {
    public SmsNotification(String recipient, String message) {
        super(recipient, message);
    }

    @Override
    public void send() {
        System.out.println("[SMS] → " + recipient + " | " + message);
    }
}

class PushNotification extends Notification {
    private final String deviceToken;

    public PushNotification(String recipient, String message, String deviceToken) {
        super(recipient, message);
        this.deviceToken = deviceToken;
    }

    @Override
    public void send() {
        System.out.println("[Push] → device:" + deviceToken
                + " | user:" + recipient + " | " + message);
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// PART 2 — INHERITANCE DONE WRONG, AND THE COMPOSITION FIX
// "OrderService extends Logger" — IS-A makes no sense here
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Logger interface — defines the logging capability
 */
interface Logger {
    void info(String message);
    void error(String message);
}

/**
 * EventPublisher interface — defines the event publishing capability
 */
interface EventPublisher {
    void publish(String eventName, String payload);
}

/**
 * OrderRepository interface — defines persistence capability
 */
interface OrderRepository {
    void save(String orderId, String details);
    String findById(String orderId);
}

// ── Concrete implementations of each capability ──────────────────────────────

class ConsoleLogger implements Logger {
    @Override
    public void info(String message) {
        System.out.println("[INFO]  " + message);
    }
    @Override
    public void error(String message) {
        System.out.println("[ERROR] " + message);
    }
}

/**
 * NoOpLogger — does nothing. Used in tests so log output doesn't clutter test results.
 * This is ONLY possible because we composed Logger — not inherited it.
 * You cannot "turn off" a parent class.
 */
class NoOpLogger implements Logger {
    @Override public void info(String message)  { /* silent */ }
    @Override public void error(String message) { /* silent */ }
}

class ConsoleEventPublisher implements EventPublisher {
    @Override
    public void publish(String eventName, String payload) {
        System.out.println("[EVENT] " + eventName + " → " + payload);
    }
}

class InMemoryOrderRepository implements OrderRepository {
    private final java.util.Map<String, String> store = new java.util.HashMap<>();

    @Override
    public void save(String orderId, String details) {
        store.put(orderId, details);
        System.out.println("[Repo]  Saved order: " + orderId);
    }

    @Override
    public String findById(String orderId) {
        return store.getOrDefault(orderId, null);
    }
}

/**
 * WRONG DESIGN (commented out to show the anti-pattern):
 *
 *   public class OrderService extends Logger { ... }
 *
 * Problems:
 *   - OrderService IS-A Logger? No. It USES logging. Those are different things.
 *   - Now OrderService inherits info(), error() — but it's NOT a logger.
 *   - You can't swap to a different logger without changing OrderService.
 *   - You can't silence it in tests without subclassing again.
 *   - Java has single inheritance — by wasting it on Logger,
 *     OrderService can never extend anything meaningful.
 */

/**
 * CORRECT DESIGN: OrderService COMPOSES its dependencies.
 *
 * Key benefits shown in main():
 * 1. Swap logger in tests — pass NoOpLogger
 * 2. Swap publisher — pass FakeEventPublisher for testing
 * 3. Swap repo — pass InMemoryRepo for testing, DatabaseRepo in production
 * 4. Each concern (logging, events, persistence) is independently testable
 * 5. OrderService itself is clean — just orchestration logic
 */
class OrderService {
    private final OrderRepository repository;  // composed
    private final Logger logger;               // composed
    private final EventPublisher publisher;    // composed

    /**
     * All dependencies are INJECTED — OrderService doesn't create any of them.
     * This is Dependency Injection, enabled by composition.
     */
    public OrderService(OrderRepository repository,
                        Logger logger,
                        EventPublisher publisher) {
        this.repository = repository;
        this.logger = logger;
        this.publisher = publisher;
    }

    public void placeOrder(String orderId, String details) {
        logger.info("Placing order: " + orderId);
        repository.save(orderId, details);
        publisher.publish("ORDER_PLACED", orderId);
        logger.info("Order placed successfully: " + orderId);
    }

    public String getOrder(String orderId) {
        String result = repository.findById(orderId);
        if (result == null) {
            logger.error("Order not found: " + orderId);
        }
        return result;
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// PART 3 — THE RUNTIME TYPE CHANGE PROBLEM
// Inheritance cannot model "a customer can be upgraded to Premium"
// ══════════════════════════════════════════════════════════════════════════════

/**
 * MembershipTier defines the behavior that CHANGES at runtime.
 * We extract the varying behavior into an interface — this is the Strategy Pattern.
 */
interface MembershipTier {
    double getDiscountRate();
    int getPriorityLevel();
    String getTierName();
    int getMaxBorrowLimit();
}

class StandardTier implements MembershipTier {
    @Override public double getDiscountRate()  { return 0.00; }
    @Override public int    getPriorityLevel() { return 1; }
    @Override public String getTierName()      { return "Standard"; }
    @Override public int    getMaxBorrowLimit(){ return 3; }
}

class PremiumTier implements MembershipTier {
    @Override public double getDiscountRate()  { return 0.15; }
    @Override public int    getPriorityLevel() { return 10; }
    @Override public String getTierName()      { return "Premium"; }
    @Override public int    getMaxBorrowLimit(){ return 10; }
}

class GoldTier implements MembershipTier {
    @Override public double getDiscountRate()  { return 0.25; }
    @Override public int    getPriorityLevel() { return 50; }
    @Override public String getTierName()      { return "Gold"; }
    @Override public int    getMaxBorrowLimit(){ return 20; }
}

/**
 * Customer composes its MembershipTier — so the tier can change at runtime.
 *
 * With inheritance (WRONG approach):
 *   Customer akash = new Customer("Akash");
 *   // Akash upgrades... but you'd have to do:
 *   akash = new PremiumCustomer("Akash")
 *   // This loses ALL of akash's history, cart, sessions, preferences.
 *   // Object identity is broken. That's absurd.
 *
 * With composition (RIGHT approach):
 *   akash.upgradeTo(new PremiumTier()); // One line. Object identity preserved.
 */
class Customer {
    private final String customerId;
    private final String name;
    private final List<String> orderHistory; // survives tier upgrade

    // COMPOSITION: tier is a field that can be swapped at runtime
    private MembershipTier tier;

    public Customer(String customerId, String name) {
        this.customerId = customerId;
        this.name = name;
        this.tier = new StandardTier(); // everyone starts Standard
        this.orderHistory = new ArrayList<>();
        System.out.println("[Customer] " + name + " created with " + tier.getTierName() + " membership");
    }

    /**
     * Runtime type change — IMPOSSIBLE with inheritance, trivial with composition.
     * The customer object's identity is preserved. History is untouched.
     */
    public void upgradeTo(MembershipTier newTier) {
        String oldTier = this.tier.getTierName();
        this.tier = newTier;
        System.out.println("[Customer] " + name + " upgraded: "
                + oldTier + " → " + tier.getTierName());
    }

    public double applyDiscount(double price) {
        double discounted = price * (1 - tier.getDiscountRate());
        System.out.println("[Customer] " + name + " (" + tier.getTierName() + ") pays ₹"
                + discounted + " instead of ₹" + price
                + " (" + (tier.getDiscountRate() * 100) + "% off)");
        return discounted;
    }

    public void addOrder(String orderId) {
        orderHistory.add(orderId);
    }

    public void printStatus() {
        System.out.println("[Customer] " + name
                + " | Tier: " + tier.getTierName()
                + " | Priority: " + tier.getPriorityLevel()
                + " | Borrow limit: " + tier.getMaxBorrowLimit()
                + " | Orders placed: " + orderHistory.size());
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// RUNNER
// ══════════════════════════════════════════════════════════════════════════════

public class InheritanceVsCompositionDemo {
    public static void main(String[] args) {

        // ── PART 1: Correct Inheritance ──────────────────────────────────────
        System.out.println("════════════════════════════════════════════");
        System.out.println("  PART 1 — Inheritance Done Right");
        System.out.println("  (Genuine IS-A with polymorphism)");
        System.out.println("════════════════════════════════════════════\n");

        List<Notification> queue = new ArrayList<>();
        queue.add(new EmailNotification("akash@email.com", "Your order is confirmed", "Order #42 Confirmed"));
        queue.add(new SmsNotification("+91-9876543210", "OTP: 847291"));
        queue.add(new PushNotification("Akash", "Flash sale: 40% off today!", "device_token_xyz"));

        System.out.println("Sending all notifications via polymorphism:\n");
        // This is why inheritance is right here: every Notification can be treated uniformly
        for (Notification n : queue) {
            n.send(); // dynamic dispatch — correct send() called based on actual type
            System.out.println("   Summary: " + n.getSummary()); // shared behavior, no duplication
            System.out.println();
        }

        // ── PART 2: Composition over Inheritance ─────────────────────────────
        System.out.println("════════════════════════════════════════════");
        System.out.println("  PART 2 — Composition over Inheritance");
        System.out.println("  (OrderService uses Logger, not IS-A Logger)");
        System.out.println("════════════════════════════════════════════\n");

        System.out.println("--- Production setup (real logger, real publisher) ---\n");
        OrderService productionService = new OrderService(
                new InMemoryOrderRepository(),
                new ConsoleLogger(),
                new ConsoleEventPublisher()
        );
        productionService.placeOrder("ORD-001", "Laptop x1");
        System.out.println();

        System.out.println("--- Test setup (silent logger, in-memory repo, fake publisher) ---");
        System.out.println("--- OrderService needs ZERO changes to run in test mode ---\n");
        OrderService testService = new OrderService(
                new InMemoryOrderRepository(),
                new NoOpLogger(),            // silence logs in tests
                (name, payload) -> {}        // lambda as fake publisher — captures nothing
        );
        testService.placeOrder("ORD-TEST-001", "Test item");
        System.out.println("   (No log output above — NoOpLogger swapped in silently)\n");

        // ── PART 3: Runtime Type Change ───────────────────────────────────────
        System.out.println("════════════════════════════════════════════");
        System.out.println("  PART 3 — Runtime Type Change");
        System.out.println("  (Composition handles upgrades; inheritance cannot)");
        System.out.println("════════════════════════════════════════════\n");

        Customer akash = new Customer("C001", "Akash");

        // Add some history — this must survive through all upgrades
        akash.addOrder("ORD-001");
        akash.addOrder("ORD-002");
        akash.addOrder("ORD-003");

        System.out.println();
        akash.printStatus();
        System.out.println();
        akash.applyDiscount(5000); // Standard: no discount

        System.out.println();
        // 6 months later — Akash upgrades to Premium
        // ONE LINE. Object identity preserved. All order history intact.
        akash.upgradeTo(new PremiumTier());
        akash.printStatus();
        System.out.println();
        akash.applyDiscount(5000); // Premium: 15% off

        System.out.println();
        // Further upgrade to Gold
        akash.upgradeTo(new GoldTier());
        akash.printStatus();
        System.out.println();
        akash.applyDiscount(5000); // Gold: 25% off

        System.out.println("\n--- Key Takeaway ---");
        System.out.println("Inheritance: use when IS-A is genuinely true, stable, and you need polymorphism.");
        System.out.println("Composition: use when you want to ASSEMBLE behaviors, not extend identity.");
        System.out.println("Runtime type change: IMPOSSIBLE with inheritance, trivial with composition.");
        System.out.println("Default rule: Prefer composition. Add inheritance only when you truly need it.");
    }
}

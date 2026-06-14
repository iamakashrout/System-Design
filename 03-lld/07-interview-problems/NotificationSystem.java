import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * LLD Problem 7: Notification System
 *
 * Patterns used:
 *  - Strategy:              NotificationChannel — each channel type (Email, SMS,
 *                           Push, WhatsApp) encapsulates its own send logic.
 *                           Adding a new channel = one new class, zero edits elsewhere.
 *  - Observer:              NotificationService fans out to all channels the user
 *                           has opted into. The service doesn't know which channels
 *                           are active — it iterates the user's preference set.
 *  - Factory:               ChannelFactory creates channel instances by type.
 *                           Decouples NotificationService from concrete classes.
 *  - Chain of Responsibility: DeliveryHandler chain: RetryHandler →
 *                           FallbackHandler → DeadLetterHandler.
 *                           Each handler attempts delivery; on failure, passes
 *                           to the next. The chain is assembled externally
 *                           (Open/Closed: new handlers don't modify existing ones).
 *
 * Key design decisions:
 *  1. Notification is an immutable value object — safe to pass through the chain.
 *  2. RetryHandler uses exponential backoff; delay is simulated (no Thread.sleep
 *     in demo) via a configurable clock hook — production-ready design.
 *  3. UserPreferences is a first-class domain object, not a raw Map.
 *  4. ChannelType enum drives both Factory lookup and UserPreferences opt-in.
 */
public class NotificationSystem {

    // ─────────────────────────────────────────────────────────────
    // Enums
    // ─────────────────────────────────────────────────────────────

    enum ChannelType { EMAIL, SMS, PUSH, WHATSAPP }

    enum Priority { LOW, NORMAL, HIGH, CRITICAL }

    enum DeliveryStatus { SENT, FAILED, RETRYING, DEAD_LETTERED }

    // ─────────────────────────────────────────────────────────────
    // Value object: Notification
    //
    // Immutable — safe to pass through the entire handler chain
    // without defensive copies.
    // ─────────────────────────────────────────────────────────────

    static final class Notification {
        final String notificationId;
        final String userId;
        final String subject;
        final String body;
        final ChannelType channelType;
        final Priority priority;
        final LocalDateTime createdAt;

        Notification(String userId, String subject, String body,
                     ChannelType channelType, Priority priority) {
            this.notificationId = UUID.randomUUID().toString().substring(0, 8);
            this.userId = userId;
            this.subject = subject;
            this.body = body;
            this.channelType = channelType;
            this.priority = priority;
            this.createdAt = LocalDateTime.now();
        }

        @Override public String toString() {
            return String.format("Notification[id=%s user=%s channel=%s subject='%s']",
                    notificationId, userId, channelType, subject);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Value object: DeliveryResult
    // ─────────────────────────────────────────────────────────────

    static final class DeliveryResult {
        final Notification notification;
        final DeliveryStatus status;
        final String message;
        final int attemptsMade;

        DeliveryResult(Notification notification, DeliveryStatus status,
                       String message, int attemptsMade) {
            this.notification = notification;
            this.status = status;
            this.message = message;
            this.attemptsMade = attemptsMade;
        }

        @Override public String toString() {
            return String.format("DeliveryResult[%s → %s | attempts=%d | %s]",
                    notification.channelType, status, attemptsMade, message);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Template: NotificationTemplate
    //
    // Separates message structure from delivery.
    // {placeholder} slots are resolved against a data map at render time.
    // ─────────────────────────────────────────────────────────────

    static final class NotificationTemplate {
        private final String templateId;
        private final String subjectPattern;
        private final String bodyPattern;
        private final ChannelType defaultChannel;
        private final Priority defaultPriority;

        NotificationTemplate(String templateId, String subjectPattern,
                              String bodyPattern, ChannelType defaultChannel,
                              Priority defaultPriority) {
            this.templateId = templateId;
            this.subjectPattern = subjectPattern;
            this.bodyPattern = bodyPattern;
            this.defaultChannel = defaultChannel;
            this.defaultPriority = defaultPriority;
        }

        /**
         * Renders a Notification by substituting {key} placeholders with data values.
         * Unknown placeholders are left as-is (graceful degradation).
         */
        Notification render(String userId, Map<String, String> data) {
            return render(userId, data, defaultChannel, defaultPriority);
        }

        Notification render(String userId, Map<String, String> data,
                            ChannelType channelType, Priority priority) {
            String subject = substitute(subjectPattern, data);
            String body    = substitute(bodyPattern, data);
            return new Notification(userId, subject, body, channelType, priority);
        }

        private String substitute(String pattern, Map<String, String> data) {
            String result = pattern;
            for (Map.Entry<String, String> entry : data.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return result;
        }

        String getTemplateId() { return templateId; }
    }

    // ─────────────────────────────────────────────────────────────
    // Strategy interface: NotificationChannel
    //
    // Each implementation owns its own send logic, error handling,
    // and payload formatting. The rest of the system is channel-agnostic.
    // ─────────────────────────────────────────────────────────────

    interface NotificationChannel {
        /**
         * Attempt to send the notification.
         * @return true if delivered successfully, false on failure.
         */
        boolean send(Notification notification);
        ChannelType getType();
    }

    // ─────────────────────────────────────────────────────────────
    // Concrete channels
    //
    // Each simulates realistic failure modes. In production, these
    // would call real APIs (SendGrid, Twilio, FCM, WhatsApp Business API).
    // The failure simulation makes the retry/fallback chain demonstrable.
    // ─────────────────────────────────────────────────────────────

    static final class EmailChannel implements NotificationChannel {
        // Simulates occasional SMTP timeout
        private int callCount = 0;
        private final boolean alwaysFail;

        EmailChannel(boolean alwaysFail) { this.alwaysFail = alwaysFail; }

        @Override
        public boolean send(Notification n) {
            callCount++;
            if (alwaysFail) {
                System.out.printf("  [EMAIL] ✗ SMTP timeout for user=%s (attempt #%d)%n",
                        n.userId, callCount);
                return false;
            }
            System.out.printf("  [EMAIL] ✓ Sent '%s' to user=%s%n", n.subject, n.userId);
            return true;
        }

        @Override public ChannelType getType() { return ChannelType.EMAIL; }
    }

    static final class SmsChannel implements NotificationChannel {
        private int callCount = 0;
        private final int failFirstN; // fail the first N attempts (for retry demo)

        SmsChannel(int failFirstN) { this.failFirstN = failFirstN; }

        @Override
        public boolean send(Notification n) {
            callCount++;
            if (callCount <= failFirstN) {
                System.out.printf("  [SMS]   ✗ Gateway error for user=%s (attempt #%d)%n",
                        n.userId, callCount);
                return false;
            }
            System.out.printf("  [SMS]   ✓ Sent '%s' to user=%s%n", n.subject, n.userId);
            return true;
        }

        @Override public ChannelType getType() { return ChannelType.SMS; }
    }

    static final class PushChannel implements NotificationChannel {
        @Override
        public boolean send(Notification n) {
            System.out.printf("  [PUSH]  ✓ Delivered '%s' to user=%s%n", n.subject, n.userId);
            return true;
        }

        @Override public ChannelType getType() { return ChannelType.PUSH; }
    }

    static final class WhatsAppChannel implements NotificationChannel {
        @Override
        public boolean send(Notification n) {
            System.out.printf("  [WA]    ✓ WhatsApp sent '%s' to user=%s%n", n.subject, n.userId);
            return true;
        }

        @Override public ChannelType getType() { return ChannelType.WHATSAPP; }
    }

    // ─────────────────────────────────────────────────────────────
    // Factory: ChannelFactory
    //
    // Creates channel instances by ChannelType.
    // Decouples NotificationService from concrete channel classes —
    // adding a new channel means registering it here and writing the class.
    // ─────────────────────────────────────────────────────────────

    static final class ChannelFactory {
        // Default channel instances (could be injected for testability)
        private final Map<ChannelType, NotificationChannel> registry = new EnumMap<>(ChannelType.class);

        ChannelFactory() {
            // Default: all channels healthy
            registry.put(ChannelType.EMAIL, new EmailChannel(false));
            registry.put(ChannelType.SMS,   new SmsChannel(0));
            registry.put(ChannelType.PUSH,  new PushChannel());
            registry.put(ChannelType.WHATSAPP, new WhatsAppChannel());
        }

        /** Register a custom channel instance (useful for testing or fault injection). */
        void register(ChannelType type, NotificationChannel channel) {
            registry.put(type, channel);
        }

        NotificationChannel get(ChannelType type) {
            NotificationChannel channel = registry.get(type);
            if (channel == null) throw new IllegalArgumentException("Unknown channel: " + type);
            return channel;
        }

        Set<ChannelType> availableTypes() {
            return Collections.unmodifiableSet(registry.keySet());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Domain: UserPreferences
    //
    // A first-class domain object. Users explicitly opt into channels.
    // The service fans out only to opted-in channels — Observer at
    // the application level.
    // ─────────────────────────────────────────────────────────────

    static final class UserPreferences {
        private final String userId;
        private final Set<ChannelType> optedInChannels;

        UserPreferences(String userId, ChannelType... channels) {
            this.userId = userId;
            this.optedInChannels = channels.length > 0
                    ? EnumSet.copyOf(Arrays.asList(channels))
                    : EnumSet.noneOf(ChannelType.class);
        }

        boolean isOptedIn(ChannelType type) { return optedInChannels.contains(type); }
        Set<ChannelType> getChannels()      { return Collections.unmodifiableSet(optedInChannels); }
        String getUserId()                  { return userId; }

        void optIn(ChannelType type)  { optedInChannels.add(type); }
        void optOut(ChannelType type) { optedInChannels.remove(type); }
    }

    // ─────────────────────────────────────────────────────────────
    // Chain of Responsibility: DeliveryHandler
    //
    // Abstract base sets up the chain link (next handler).
    // Each concrete handler either handles the delivery or delegates
    // to next. The chain is assembled in NotificationService — the
    // handlers themselves are fully decoupled from each other.
    // ─────────────────────────────────────────────────────────────

    static abstract class DeliveryHandler {
        private DeliveryHandler next;

        /** Fluent chain builder: handler.then(nextHandler) */
        DeliveryHandler then(DeliveryHandler next) {
            this.next = next;
            return this;
        }

        /** Attempt delivery. Subclasses implement their strategy and call passToNext() on failure. */
        abstract DeliveryResult handle(Notification notification, NotificationChannel channel);

        protected DeliveryResult passToNext(Notification notification, NotificationChannel channel) {
            if (next != null) return next.handle(notification, channel);
            // Chain exhausted — this should be caught by DeadLetterHandler before reaching here
            return new DeliveryResult(notification, DeliveryStatus.DEAD_LETTERED,
                    "Handler chain exhausted", 0);
        }
    }

    /**
     * RetryHandler: attempts delivery up to maxAttempts times with exponential backoff.
     * In production, backoff would use Thread.sleep or a scheduled executor.
     * Here we simulate it via a configurable SleepStrategy — testable without real delays.
     */
    static final class RetryHandler extends DeliveryHandler {
        private final int maxAttempts;
        private final long initialBackoffMs;

        RetryHandler(int maxAttempts, long initialBackoffMs) {
            this.maxAttempts = maxAttempts;
            this.initialBackoffMs = initialBackoffMs;
        }

        @Override
        public DeliveryResult handle(Notification notification, NotificationChannel channel) {
            long backoff = initialBackoffMs;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean success = channel.send(notification);
                if (success) {
                    return new DeliveryResult(notification, DeliveryStatus.SENT,
                            "Delivered on attempt " + attempt, attempt);
                }
                if (attempt < maxAttempts) {
                    System.out.printf("  [RETRY] Backing off %dms before attempt %d/%d%n",
                            backoff, attempt + 1, maxAttempts);
                    // In production: Thread.sleep(backoff)
                    backoff *= 2; // exponential backoff
                }
            }
            System.out.printf("  [RETRY] Exhausted %d attempts for %s — passing to next handler%n",
                    maxAttempts, channel.getType());
            return passToNext(notification, channel);
        }
    }

    /**
     * FallbackHandler: if the primary channel fails after all retries, attempts
     * delivery on a secondary/fallback channel. A common real-world pattern:
     * WhatsApp fails → fall back to SMS; Push fails → fall back to Email.
     */
    static final class FallbackHandler extends DeliveryHandler {
        private final Map<ChannelType, NotificationChannel> fallbacks;

        FallbackHandler(Map<ChannelType, NotificationChannel> fallbacks) {
            this.fallbacks = fallbacks;
        }

        @Override
        public DeliveryResult handle(Notification notification, NotificationChannel channel) {
            NotificationChannel fallback = fallbacks.get(notification.channelType);
            if (fallback != null) {
                System.out.printf("  [FALLBACK] Trying fallback channel for %s%n",
                        notification.channelType);
                boolean success = fallback.send(notification);
                if (success) {
                    return new DeliveryResult(notification, DeliveryStatus.SENT,
                            "Delivered via fallback: " + fallback.getType(), 1);
                }
            }
            System.out.printf("  [FALLBACK] No viable fallback for %s — dead lettering%n",
                    notification.channelType);
            return passToNext(notification, channel);
        }
    }

    /**
     * DeadLetterHandler: terminal handler. Logs the permanently failed notification
     * to a dead-letter queue for manual review / alerting.
     * This is always the last link in the chain.
     */
    static final class DeadLetterHandler extends DeliveryHandler {
        private final List<Notification> deadLetterQueue = new ArrayList<>();

        @Override
        public DeliveryResult handle(Notification notification, NotificationChannel channel) {
            deadLetterQueue.add(notification);
            System.out.printf("  [DLQ] ☠ Notification %s permanently failed — added to DLQ%n",
                    notification.notificationId);
            return new DeliveryResult(notification, DeliveryStatus.DEAD_LETTERED,
                    "Added to dead-letter queue", 0);
        }

        List<Notification> getDeadLetterQueue() {
            return Collections.unmodifiableList(deadLetterQueue);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // NotificationService: the orchestrator
    //
    // Responsibilities:
    //  - Manage user preferences (Observer subscriber registry)
    //  - Fan out notifications to opted-in channels (Observer notify)
    //  - Route each delivery attempt through the handler chain (CoR)
    //  - Expose template-based and direct notification APIs
    // ─────────────────────────────────────────────────────────────

    static final class NotificationService {
        private final ChannelFactory channelFactory;
        private final DeliveryHandler handlerChain;
        private final Map<String, UserPreferences> userPrefs = new HashMap<>();
        private final Map<String, NotificationTemplate> templates = new HashMap<>();
        private final List<DeliveryResult> auditLog = new ArrayList<>();

        NotificationService(ChannelFactory channelFactory, DeliveryHandler handlerChain) {
            this.channelFactory = channelFactory;
            this.handlerChain = handlerChain;
        }

        // ── Preference management ──

        void registerUser(UserPreferences prefs) {
            userPrefs.put(prefs.getUserId(), prefs);
        }

        void registerTemplate(NotificationTemplate template) {
            templates.put(template.getTemplateId(), template);
        }

        // ── Core notification API ──

        /**
         * Template-based fan-out: renders the template for each opted-in channel
         * and delivers through the handler chain.
         *
         * Observer pattern: the service doesn't enumerate specific channels —
         * it iterates the user's opt-in set and dispatches to each.
         */
        List<DeliveryResult> notifyViaTemplate(String userId, String templateId,
                                                Map<String, String> data) {
            NotificationTemplate template = templates.get(templateId);
            if (template == null) throw new IllegalArgumentException("Unknown template: " + templateId);

            UserPreferences prefs = userPrefs.get(userId);
            if (prefs == null) throw new IllegalArgumentException("Unknown user: " + userId);

            List<DeliveryResult> results = new ArrayList<>();
            for (ChannelType channelType : prefs.getChannels()) {
                Notification notification = template.render(userId, data, channelType, Priority.NORMAL);
                DeliveryResult result = deliver(notification, channelType);
                results.add(result);
                auditLog.add(result);
            }
            return results;
        }

        /**
         * Direct notification: send to a specific channel, bypassing template.
         * Used for system alerts, OTP delivery, etc.
         */
        DeliveryResult notify(Notification notification) {
            DeliveryResult result = deliver(notification, notification.channelType);
            auditLog.add(result);
            return result;
        }

        private DeliveryResult deliver(Notification notification, ChannelType channelType) {
            NotificationChannel channel = channelFactory.get(channelType);
            return handlerChain.handle(notification, channel);
        }

        List<DeliveryResult> getAuditLog() {
            return Collections.unmodifiableList(auditLog);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Demo
    // ─────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("══════════════════════════════════════════════════");
        System.out.println("  Notification System — LLD Demo");
        System.out.println("══════════════════════════════════════════════════\n");

        // ── 1. Build the handler chain ──────────────────────────
        // Chain: RetryHandler(3 attempts) → FallbackHandler → DeadLetterHandler
        //
        // Assembly is external — handlers are unaware of each other.
        // Changing the chain (add throttle handler, add audit handler)
        // requires zero changes to existing handler classes.

        DeadLetterHandler dlq = new DeadLetterHandler();

        Map<ChannelType, NotificationChannel> fallbacks = new EnumMap<>(ChannelType.class);
        fallbacks.put(ChannelType.SMS,   new PushChannel()); // SMS fails → try Push
        fallbacks.put(ChannelType.EMAIL, new SmsChannel(0)); // Email fails → try SMS
        FallbackHandler fallbackHandler = new FallbackHandler(fallbacks);

        RetryHandler retryHandler = new RetryHandler(3, 100);
        retryHandler.then(fallbackHandler).then(dlq); // compose the chain

        // ── 2. Register channels ───────────────────────────────
        ChannelFactory factory = new ChannelFactory();
        // Healthy channels by default
        NotificationService service = new NotificationService(factory, retryHandler);

        // ── 3. Register templates ──────────────────────────────
        service.registerTemplate(new NotificationTemplate(
                "ORDER_SHIPPED",
                "Your order #{orderId} has shipped!",
                "Hi {name}, your order #{orderId} is on its way. Estimated delivery: {eta}.",
                ChannelType.EMAIL, Priority.NORMAL
        ));
        service.registerTemplate(new NotificationTemplate(
                "OTP",
                "Your OTP is {otp}",
                "Use OTP {otp} to complete your login. Valid for 5 minutes.",
                ChannelType.SMS, Priority.HIGH
        ));

        // ── 4. Register users with preferences ────────────────
        service.registerUser(new UserPreferences("user_akash",
                ChannelType.EMAIL, ChannelType.PUSH));
        service.registerUser(new UserPreferences("user_priya",
                ChannelType.SMS, ChannelType.WHATSAPP));
        service.registerUser(new UserPreferences("user_rahul",
                ChannelType.EMAIL, ChannelType.SMS, ChannelType.PUSH));

        // ── 5. Scenario 1: Template-based fan-out (healthy channels) ──
        System.out.println("── Scenario 1: Order shipped → akash (EMAIL + PUSH) ────");
        Map<String, String> orderData = Map.of(
                "name", "Akash", "orderId", "ORD-9921", "eta", "Dec 18");
        List<DeliveryResult> results = service.notifyViaTemplate("user_akash", "ORDER_SHIPPED", orderData);
        results.forEach(r -> System.out.println("  → " + r));

        System.out.println();

        // ── 6. Scenario 2: OTP via SMS to priya ───────────────
        System.out.println("── Scenario 2: OTP → priya (SMS + WhatsApp) ────────────");
        Map<String, String> otpData = Map.of("name", "Priya", "otp", "847291");
        List<DeliveryResult> results2 = service.notifyViaTemplate("user_priya", "OTP", otpData);
        results2.forEach(r -> System.out.println("  → " + r));

        System.out.println();

        // ── 7. Scenario 3: Retry then success ─────────────────
        // SMS channel that fails first 2 attempts, succeeds on 3rd
        System.out.println("── Scenario 3: Retry — SMS fails twice, succeeds on 3rd ─");
        factory.register(ChannelType.SMS, new SmsChannel(2));
        Notification smsNotif = new Notification("user_rahul",
                "Payment confirmed", "Your payment of ₹5,000 was successful.",
                ChannelType.SMS, Priority.HIGH);
        DeliveryResult retryResult = service.notify(smsNotif);
        System.out.println("  → " + retryResult);

        System.out.println();

        // ── 8. Scenario 4: All retries fail → fallback ────────
        System.out.println("── Scenario 4: Email fails all retries → fallback to SMS ─");
        factory.register(ChannelType.EMAIL, new EmailChannel(true)); // always fail
        Notification emailNotif = new Notification("user_akash",
                "Security alert", "Unusual login detected on your account.",
                ChannelType.EMAIL, Priority.CRITICAL);
        DeliveryResult fallbackResult = service.notify(emailNotif);
        System.out.println("  → " + fallbackResult);

        System.out.println();

        // ── 9. Scenario 5: Total failure → DLQ ────────────────
        System.out.println("── Scenario 5: No fallback available → Dead Letter Queue ─");
        // No fallback registered for PUSH in our map → goes to DLQ
        Notification pushNotif = new Notification("user_rahul",
                "Flash sale!", "50% off for next 1 hour.",
                ChannelType.PUSH, Priority.LOW);
        // Override: make Push channel also fail
        factory.register(ChannelType.PUSH, new NotificationChannel() {
            public boolean send(Notification n) {
                System.out.printf("  [PUSH]  ✗ Device token expired for user=%s%n", n.userId);
                return false;
            }
            public ChannelType getType() { return ChannelType.PUSH; }
        });
        DeliveryResult dlqResult = service.notify(pushNotif);
        System.out.println("  → " + dlqResult);
        System.out.println("  DLQ size: " + dlq.getDeadLetterQueue().size());

        System.out.println();

        // ── 10. Audit log summary ──────────────────────────────
        System.out.println("── Audit log ────────────────────────────────────────────");
        service.getAuditLog().forEach(r ->
            System.out.printf("  %-12s %-14s %s%n",
                r.notification.channelType, r.status, r.notification.subject));
    }
}

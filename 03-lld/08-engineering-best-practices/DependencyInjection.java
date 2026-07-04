import java.util.ArrayList;
import java.util.List;

/**
 * TOPIC 1: DEPENDENCY INJECTION
 *
 * Domain: Notification System (reused from Phase 5 — Classic LLD Problem #7)
 *
 * This file walks through the SAME feature — "send a notification through
 * one or more channels" — implemented first the tightly-coupled way, then
 * refactored using constructor injection, then contrasted against the
 * service-locator anti-pattern.
 *
 * Everything lives in one file using static nested classes purely so the
 * whole story can be read top-to-bottom without jumping between files.
 * In a real codebase each class below would be its own file/package.
 */
public class DependencyInjection {

    // =========================================================================
    // PART 0: Domain objects shared across every version below
    // =========================================================================

    /**
     * A plain immutable data holder — the "message" being sent.
     * Kept dependency-free on purpose; it's a value object, not a service.
     */
    static final class NotificationMessage {
        private final String recipient;
        private final String body;

        NotificationMessage(String recipient, String body) {
            if (recipient == null || recipient.isBlank()) {
                throw new IllegalArgumentException("recipient must not be blank");
            }
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("body must not be blank");
            }
            this.recipient = recipient;
            this.body = body;
        }

        String getRecipient() {
            return recipient;
        }

        String getBody() {
            return body;
        }
    }

    // =========================================================================
    // PART 1: THE TIGHTLY COUPLED VERSION — what NOT to do
    // =========================================================================

    /**
     * A concrete "sender" that BadNotificationService will construct itself.
     * Notice: nothing is wrong with EmailSenderImpl in isolation. The problem
     * is entirely about who is allowed to create it.
     */
    static final class EmailSenderImpl {
        void send(String recipient, String body) {
            System.out.println("[EMAIL] to=" + recipient + " body=" + body);
        }
    }

    /**
     * ANTI-PATTERN: the dependency is constructed INSIDE the class that
     * needs it. This single line — `new EmailSenderImpl()` — is the entire
     * problem this topic exists to solve.
     *
     * Consequences, all stemming from that one line:
     *   1. Cannot unit test this class without actually invoking EmailSenderImpl.
     *   2. Cannot add SMS/Push support without editing this class's source.
     *   3. Cannot tell what this class depends on without reading its body —
     *      the constructor gives no hint.
     */
    static final class BadNotificationService {
        // no fields, no constructor parameters — dependency is invisible
        // from the outside, which is precisely the red flag.

        void notifyUser(String recipient, String message) {
            EmailSenderImpl sender = new EmailSenderImpl(); // <-- tight coupling
            sender.send(recipient, message);
        }
    }

    // =========================================================================
    // PART 2: THE REFACTORED VERSION — depend on an abstraction
    // =========================================================================

    /**
     * The abstraction. NotificationService will only ever know about THIS
     * interface. It has no idea EmailChannel, SmsChannel, or PushChannel
     * exist.
     */
    interface NotificationChannel {
        void send(NotificationMessage message);

        /** Used for logging/metrics — every channel identifies itself. */
        String channelName();
    }

    static final class EmailChannel implements NotificationChannel {
        @Override
        public void send(NotificationMessage message) {
            System.out.println("[EMAIL] to=" + message.getRecipient()
                    + " body=" + message.getBody());
        }

        @Override
        public String channelName() {
            return "EMAIL";
        }
    }

    static final class SmsChannel implements NotificationChannel {
        @Override
        public void send(NotificationMessage message) {
            System.out.println("[SMS] to=" + message.getRecipient()
                    + " body=" + message.getBody());
        }

        @Override
        public String channelName() {
            return "SMS";
        }
    }

    static final class PushChannel implements NotificationChannel {
        @Override
        public void send(NotificationMessage message) {
            System.out.println("[PUSH] to=" + message.getRecipient()
                    + " body=" + message.getBody());
        }

        @Override
        public String channelName() {
            return "PUSH";
        }
    }

    /**
     * A dependency for OPTIONAL, cross-cutting behavior — demonstrates
     * setter injection later on. Not every NotificationService needs metrics,
     * so it is not forced through the constructor.
     */
    interface MetricsRecorder {
        void recordSent(String channelName);
    }

    static final class InMemoryMetricsRecorder implements MetricsRecorder {
        private int count = 0;

        @Override
        public void recordSent(String channelName) {
            count++;
            System.out.println("[METRICS] " + channelName + " send #" + count);
        }
    }

    /**
     * THE FIX: NotificationService depends only on the NotificationChannel
     * abstraction, and receives its concrete list via CONSTRUCTOR INJECTION.
     *
     * Properties this buys us, directly traceable to the constructor:
     *   - The field is `final` -> once built, this object is always in a
     *     valid, fully-wired state. No half-constructed instances possible.
     *   - Testable: a test can pass in a list of fake/mock channels and
     *     assert on what was "sent" without touching email/SMS/push infra.
     *   - Extensible: adding a WhatsApp channel means writing a new class
     *     that implements NotificationChannel and adding it to the wiring
     *     at the composition root — this class's source is untouched
     *     (Open/Closed Principle in action).
     */
    static final class NotificationService {
        private final List<NotificationChannel> channels;

        // Optional dependency — not passed to the constructor because a
        // NotificationService is still perfectly valid without metrics.
        // This is where SETTER INJECTION is the right tool: the dependency
        // is optional, so forcing it through the constructor would make
        // every caller pass null or a no-op implementation.
        private MetricsRecorder metricsRecorder;

        NotificationService(List<NotificationChannel> channels) {
            if (channels == null || channels.isEmpty()) {
                throw new IllegalArgumentException("at least one channel is required");
            }
            // defensive copy — callers can't mutate our internal list later
            this.channels = new ArrayList<>(channels);
        }

        /** Setter injection: optional, can be attached after construction. */
        void setMetricsRecorder(MetricsRecorder metricsRecorder) {
            this.metricsRecorder = metricsRecorder;
        }

        void notifyUser(NotificationMessage message) {
            for (NotificationChannel channel : channels) {
                channel.send(message);
                if (metricsRecorder != null) {
                    metricsRecorder.recordSent(channel.channelName());
                }
            }
        }
    }

    // =========================================================================
    // PART 3: THE SERVICE LOCATOR ANTI-PATTERN
    //
    // This LOOKS like it solves the problem (no `new` inside the service!)
    // but it doesn't. The dependency is still fetched by the object itself,
    // just from a different hiding place. The constructor still lies about
    // what this class actually needs.
    // =========================================================================

    static final class ServiceLocator {
        private static NotificationChannel registeredChannel;

        static void register(NotificationChannel channel) {
            registeredChannel = channel;
        }

        static NotificationChannel getChannel() {
            if (registeredChannel == null) {
                throw new IllegalStateException("no channel registered with ServiceLocator");
            }
            return registeredChannel;
        }
    }

    static final class NotificationServiceWithLocator {
        // Constructor takes NOTHING -> reading this signature tells you
        // nothing about what this class depends on. That is the smell.
        void notifyUser(NotificationMessage message) {
            NotificationChannel channel = ServiceLocator.getChannel(); // hidden dependency
            channel.send(message);
        }
    }

    // =========================================================================
    // PART 4: COMPOSITION ROOT — manual DI wiring
    //
    // Every `new` for a concrete implementation lives HERE and only here.
    // Everywhere above this point, code only ever referenced interfaces.
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("--- Tightly coupled version ---");
        BadNotificationService badService = new BadNotificationService();
        badService.notifyUser("akash@example.com", "Your order has shipped.");

        System.out.println();
        System.out.println("--- Constructor-injected version ---");
        List<NotificationChannel> channels = new ArrayList<>();
        channels.add(new EmailChannel());
        channels.add(new SmsChannel());
        channels.add(new PushChannel());

        NotificationService service = new NotificationService(channels);
        service.setMetricsRecorder(new InMemoryMetricsRecorder()); // setter injection

        NotificationMessage message =
                new NotificationMessage("akash@example.com", "Your order has shipped.");
        service.notifyUser(message);

        System.out.println();
        System.out.println("--- Service locator anti-pattern (for contrast) ---");
        ServiceLocator.register(new EmailChannel());
        NotificationServiceWithLocator locatorService = new NotificationServiceWithLocator();
        locatorService.notifyUser(message);

        System.out.println();
        System.out.println("--- Why the refactor matters: swapping channels at runtime ---");
        // No source-code change to NotificationService required to reconfigure it.
        List<NotificationChannel> emailOnly = new ArrayList<>();
        emailOnly.add(new EmailChannel());
        NotificationService emailOnlyService = new NotificationService(emailOnly);
        emailOnlyService.notifyUser(message);
    }
}

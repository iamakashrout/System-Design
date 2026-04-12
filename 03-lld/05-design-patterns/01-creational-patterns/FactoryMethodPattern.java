// =============================================================================
// PATTERN: Factory Method
// PURPOSE: Define an interface for creating an object, but let subclasses
//          decide which class to instantiate. The creator defers instantiation
//          to its subclasses.
//
// REAL-WORLD ANALOGY:
//   A logistics company ships packages. Early on it only used trucks. Later it
//   added ships, then drones. If "ship this package" was hardcoded to create a
//   Truck, adding Ship would mean rewriting core logic. Instead, the company
//   delegates the "figure out what vehicle to use" decision to a separate step.
//   That separation is the Factory Method.
//
// THE PROBLEM IT SOLVES:
//   Without this pattern:
//     if (channel.equals("EMAIL"))  sender = new EmailSender();
//     else if (channel.equals("SMS")) sender = new SmsSender();
//   This if-else lives in your business logic. Every new channel → modify
//   business logic. Violates the Open/Closed Principle.
//
//   With this pattern:
//     The creation decision is in one place (the factory).
//     Business logic just calls factory.create("EMAIL") and never touches
//     concrete classes.
//
// FOUR INGREDIENTS:
//   1. Product interface    → what all created objects must be able to do
//   2. Concrete products    → the actual implementations (Email, SMS, Push)
//   3. Abstract creator     → defines the factory method (returns Product type)
//   4. Concrete creators    → override the factory method to return specific products
// =============================================================================

public class FactoryMethodPattern {

    // =========================================================================
    // STEP 1: PRODUCT INTERFACE
    // This is what the factory creates. Callers only ever see this type —
    // they never know (or care) which concrete class they got.
    // =========================================================================
    interface NotificationSender {
        void send(String recipient, String message);
        String getChannel(); // tells us which channel this sender uses
    }


    // =========================================================================
    // STEP 2: CONCRETE PRODUCTS
    // Each class implements the NotificationSender interface differently.
    // The factory decides which one to create — callers never reference these
    // concrete classes directly.
    // =========================================================================

    // Email implementation
    static class EmailSender implements NotificationSender {
        @Override
        public void send(String recipient, String message) {
            System.out.println("  [EMAIL] To: " + recipient);
            System.out.println("          Subject: Notification");
            System.out.println("          Body: " + message);
        }

        @Override
        public String getChannel() { return "EMAIL"; }
    }

    // SMS implementation
    static class SmsSender implements NotificationSender {
        @Override
        public void send(String recipient, String message) {
            System.out.println("  [SMS] To: " + recipient);
            System.out.println("        Text: " + message);
        }

        @Override
        public String getChannel() { return "SMS"; }
    }

    // Push notification implementation
    static class PushNotificationSender implements NotificationSender {
        @Override
        public void send(String recipient, String message) {
            System.out.println("  [PUSH] Device: " + recipient);
            System.out.println("         Alert: " + message);
        }

        @Override
        public String getChannel() { return "PUSH"; }
    }

    // WhatsApp implementation — added later, no existing code was modified
    static class WhatsAppSender implements NotificationSender {
        @Override
        public void send(String recipient, String message) {
            System.out.println("  [WHATSAPP] To: " + recipient);
            System.out.println("             Message: " + message);
        }

        @Override
        public String getChannel() { return "WHATSAPP"; }
    }


    // =========================================================================
    // STEP 3a: ABSTRACT CREATOR (the classic Factory Method approach)
    //
    // The abstract class defines WHAT to do (notifyUser) but delegates
    // the decision of WHAT TO CREATE to subclasses (createSender).
    // This is the textbook "Factory Method" — creation via subclassing.
    // =========================================================================
    static abstract class NotificationService {

        // THE FACTORY METHOD — abstract, so subclasses must override it.
        // The return type is the interface (NotificationSender), not any concrete class.
        protected abstract NotificationSender createSender();

        // Template behavior: uses whatever sender the subclass provides.
        // This method doesn't know (or care) if it gets an Email or SMS sender.
        public void notifyUser(String recipient, String message) {
            NotificationSender sender = createSender(); // subclass decides what this is
            System.out.println("  [NotificationService] Sending via: " + sender.getChannel());
            sender.send(recipient, message);
        }
    }


    // =========================================================================
    // STEP 3b: CONCRETE CREATORS
    // Each subclass overrides createSender() to return a specific product.
    // The business logic (notifyUser) is inherited and unchanged.
    // =========================================================================

    static class EmailNotificationService extends NotificationService {
        @Override
        protected NotificationSender createSender() {
            return new EmailSender(); // this subclass creates EmailSender
        }
    }

    static class SmsNotificationService extends NotificationService {
        @Override
        protected NotificationSender createSender() {
            return new SmsSender();
        }
    }

    static class PushNotificationService extends NotificationService {
        @Override
        protected NotificationSender createSender() {
            return new PushNotificationSender();
        }
    }


    // =========================================================================
    // STEP 4: STATIC FACTORY (the simpler, more common variant in practice)
    //
    // In real-world code, you'll see this more often than the abstract class
    // approach. The creation decision lives in ONE place (the switch statement).
    // To add WhatsApp: add one `case` here. Nothing else changes.
    // =========================================================================
    static class NotificationSenderFactory {

        public static NotificationSender create(String channel) {
            switch (channel.toUpperCase()) {
                case "EMAIL":    return new EmailSender();
                case "SMS":      return new SmsSender();
                case "PUSH":     return new PushNotificationSender();
                case "WHATSAPP": return new WhatsAppSender(); // added later — no other code touched
                default:
                    throw new IllegalArgumentException("Unknown notification channel: " + channel);
            }
        }
    }


    // =========================================================================
    // MAIN — demonstrates both approaches
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Factory Method Pattern Demo ===\n");

        // ----- Approach 1: Classic Factory Method via subclassing -----
        System.out.println("--- Approach 1: Abstract Creator + Concrete Subclasses ---\n");

        // The caller works with NotificationService — never with EmailSender directly.
        // Swapping to SMS is just swapping which subclass you instantiate.
        NotificationService emailService = new EmailNotificationService();
        emailService.notifyUser("akash@email.com", "Your order has shipped!");

        System.out.println();

        NotificationService smsService = new SmsNotificationService();
        smsService.notifyUser("+91-9876543210", "OTP: 482910. Valid for 10 minutes.");

        System.out.println();

        NotificationService pushService = new PushNotificationService();
        pushService.notifyUser("device-token-abc123", "You have a new message from Priya.");


        // ----- Approach 2: Static Factory (more common in practice) -----
        System.out.println("\n--- Approach 2: Static Factory (runtime channel selection) ---\n");

        // Imagine `userPreferredChannel` comes from a database or request parameter.
        // The factory handles the decision — no if-else in business logic.
        String[] channels = {"EMAIL", "SMS", "PUSH", "WHATSAPP"};
        String[] recipients = {"bob@email.com", "+91-9000000001", "token-xyz", "+91-9000000002"};
        String[] messages = {
            "Your invoice is ready",
            "Your ride is arriving in 2 mins",
            "Flash sale: 50% off ends in 1 hour",
            "Your package was delivered"
        };

        for (int i = 0; i < channels.length; i++) {
            // The caller doesn't know (or care) what concrete sender it gets back.
            // It just sends. That's the power of the pattern.
            NotificationSender sender = NotificationSenderFactory.create(channels[i]);
            sender.send(recipients[i], messages[i]);
            System.out.println();
        }


        // ----- Showing what happens with an unknown channel -----
        System.out.println("--- Handling Unknown Channel ---");
        try {
            NotificationSender unknownSender = NotificationSenderFactory.create("TELEGRAM");
        } catch (IllegalArgumentException e) {
            System.out.println("  Caught expected error: " + e.getMessage());
        }


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Callers depend only on the interface (NotificationSender)");
        System.out.println("  2. Adding a new channel = add a new class + one line in factory");
        System.out.println("  3. Existing code is NEVER modified — Open/Closed Principle upheld");
        System.out.println("  4. Static factory = simpler; abstract creator = more extensible");
    }
}

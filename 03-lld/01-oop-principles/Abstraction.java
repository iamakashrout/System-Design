// ============================================================
// OOP Principle 2: ABSTRACTION
// "Expose what, hide how"
//
// Demonstrated through a Notification system.
// - Interface defines the contract (what must be done)
// - Each channel hides its own implementation (how it's done)
// - Caller only interacts with the abstraction, not the details
// Also demonstrates abstract class with Template Method Pattern.
// ============================================================

import java.util.ArrayList;
import java.util.List;

public class Abstraction {

    // ── Abstraction 1: Interface — pure contract ─────────────
    // Defines WHAT a notification channel must do.
    // Says nothing about HOW.
    interface NotificationChannel {
        void send(String recipient, String message);
        boolean isAvailable();
        String getChannelName();
    }


    // ── Concrete Implementation 1: Email ─────────────────────
    static class EmailChannel implements NotificationChannel {
        private final String smtpServer;
        private boolean connected;

        public EmailChannel(String smtpServer) {
            this.smtpServer = smtpServer;
            this.connected = true;
            System.out.println("[EmailChannel] Initialized with SMTP server: " + smtpServer);
        }

        @Override
        public void send(String recipient, String message) {
            // Hides: SMTP connection, retry logic, bounce handling, HTML formatting
            System.out.println("  [Email → " + recipient + "] Subject: Notification");
            System.out.println("  [Email] Body: " + message);
            System.out.println("  [Email] Sent via " + smtpServer);
        }

        @Override
        public boolean isAvailable() { return connected; }

        @Override
        public String getChannelName() { return "Email"; }
    }


    // ── Concrete Implementation 2: SMS ───────────────────────
    static class SmsChannel implements NotificationChannel {
        private final String apiKey;
        private boolean serviceUp;

        public SmsChannel(String apiKey) {
            this.apiKey = apiKey;
            this.serviceUp = true;
            System.out.println("[SmsChannel] Initialized with API key: " + apiKey.substring(0, 4) + "****");
        }

        @Override
        public void send(String recipient, String message) {
            // Hides: Twilio API call, character limit handling, number formatting
            String truncated = message.length() > 60 ? message.substring(0, 57) + "..." : message;
            System.out.println("  [SMS → " + recipient + "] " + truncated);
            System.out.println("  [SMS] Dispatched via Twilio (key: " + apiKey.substring(0, 4) + "****)");
        }

        @Override
        public boolean isAvailable() { return serviceUp; }

        @Override
        public String getChannelName() { return "SMS"; }

        // Simulate service going down — for demo purposes
        public void setServiceUp(boolean up) { this.serviceUp = up; }
    }


    // ── Concrete Implementation 3: Push Notification ─────────
    static class PushChannel implements NotificationChannel {

        public PushChannel() {
            System.out.println("[PushChannel] Initialized with FCM client.");
        }

        @Override
        public void send(String recipient, String message) {
            // Hides: FCM token lookup, device targeting, payload building
            System.out.println("  [Push → device:" + recipient + "] " + message);
            System.out.println("  [Push] Delivered via Firebase Cloud Messaging");
        }

        @Override
        public boolean isAvailable() { return true; } // fire-and-forget

        @Override
        public String getChannelName() { return "Push"; }
    }


    // ── Service: depends only on the abstraction ─────────────
    // NotificationService has NO knowledge of Email, SMS, or Push.
    // It only knows about NotificationChannel.
    static class NotificationService {
        private final List<NotificationChannel> channels = new ArrayList<>();

        public void registerChannel(NotificationChannel channel) {
            channels.add(channel);
            System.out.println("[NotificationService] Registered channel: " + channel.getChannelName());
        }

        public void notifyAll(String recipient, String message) {
            System.out.println("\n  Sending notification to: " + recipient);
            System.out.println("  Message: \"" + message + "\"");
            int sent = 0;
            for (NotificationChannel channel : channels) {
                if (channel.isAvailable()) {
                    channel.send(recipient, message);
                    sent++;
                } else {
                    System.out.println("  [Skipped] " + channel.getChannelName() + " is unavailable.");
                }
            }
            System.out.println("  Notification sent via " + sent + " channel(s).");
        }
    }


    // ── Abstraction 2: Abstract Class — Template Method Pattern ──
    // Defines a SKELETON of an algorithm.
    // Subclasses fill in the variable steps.
    // The overall flow (what happens and in what order) is fixed.
    static abstract class ReportGenerator {

        // Template method — fixed skeleton, cannot be overridden
        public final void generate(String reportName) {
            System.out.println("\n  [Report: " + reportName + "] Starting generation...");
            List<String> data = fetchData();
            List<String> validated = validateData(data);
            String formatted = formatContent(validated);   // abstract — subclass decides
            applyBranding(formatted);
            deliver(formatted, reportName);                // abstract — subclass decides
            System.out.println("  [Report: " + reportName + "] Done.");
        }

        // Shared steps — same for all report types
        private List<String> fetchData() {
            System.out.println("  [Step 1] Fetching data from database...");
            List<String> rows = new ArrayList<>();
            rows.add("Record A: Sales ₹50,000");
            rows.add("Record B: Sales ₹80,000");
            rows.add("Record C: Sales ₹30,000");
            return rows;
        }

        private List<String> validateData(List<String> data) {
            System.out.println("  [Step 2] Validating " + data.size() + " records...");
            return data; // simplified for demo
        }

        private void applyBranding(String content) {
            System.out.println("  [Step 4] Applying company branding...");
        }

        // Variable steps — subclasses must implement these
        protected abstract String formatContent(List<String> data);
        protected abstract void deliver(String content, String name);
    }

    static class PdfReportGenerator extends ReportGenerator {
        @Override
        protected String formatContent(List<String> data) {
            System.out.println("  [Step 3] Formatting as PDF...");
            return String.join(" | ", data);
        }

        @Override
        protected void deliver(String content, String name) {
            System.out.println("  [Step 5] Saving PDF to /reports/" + name + ".pdf");
        }
    }

    static class EmailReportGenerator extends ReportGenerator {
        @Override
        protected String formatContent(List<String> data) {
            System.out.println("  [Step 3] Formatting as HTML for email...");
            return "<html><body>" + String.join("<br>", data) + "</body></html>";
        }

        @Override
        protected void deliver(String content, String name) {
            System.out.println("  [Step 5] Emailing report '" + name + "' to manager@company.com");
        }
    }


    // ── Main ──────────────────────────────────────────────────
    public static void main(String[] args) {

        System.out.println("═══════════════════════════════════════");
        System.out.println("  OOP PRINCIPLE 2: ABSTRACTION");
        System.out.println("═══════════════════════════════════════\n");

        // ── Demo 1: Interface-based abstraction ──
        System.out.println(">>> Demo 1: NotificationService (Interface Abstraction)");
        System.out.println("  Registering channels...");
        SmsChannel sms = new SmsChannel("TWILIO-KEY-9821");
        EmailChannel email = new EmailChannel("smtp.company.com");
        PushChannel push = new PushChannel();

        NotificationService service = new NotificationService();
        service.registerChannel(email);
        service.registerChannel(sms);
        service.registerChannel(push);

        service.notifyAll("akash@example.com / +91-9876543210 / device-token-xyz",
            "Your order #4521 has been shipped!");

        // ── Demo 2: One channel goes down — service adapts ──
        System.out.println("\n>>> Demo 2: One Channel Unavailable — Service Adapts");
        sms.setServiceUp(false);
        service.notifyAll("akash@example.com / +91-9876543210 / device-token-xyz",
            "Your payment of ₹2,000 was successful.");

        // ── Demo 3: Swap channel at runtime ──
        System.out.println("\n>>> Demo 3: Swap to Email-Only Service (Runtime Swap)");
        NotificationService emailOnly = new NotificationService();
        emailOnly.registerChannel(new EmailChannel("smtp.backup.com"));
        emailOnly.notifyAll("akash@example.com", "Backup alert: disk usage > 90%");

        // ── Demo 4: Abstract class — Template Method Pattern ──
        System.out.println("\n>>> Demo 4: Abstract Class — Template Method Pattern");
        ReportGenerator pdfGen   = new PdfReportGenerator();
        ReportGenerator emailGen = new EmailReportGenerator();

        pdfGen.generate("Q3-Sales-Report");
        emailGen.generate("Weekly-Summary");

        System.out.println("\n✔ Abstraction: callers interact with contracts, not implementations.");
    }
}

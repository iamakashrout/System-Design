import java.util.HashMap;
import java.util.Map;

// =============================================================================
// PATTERN: Adapter
// PURPOSE: Make two incompatible interfaces work together by placing a
//          "translator" class between them. Neither the client nor the
//          third-party class needs to change.
//
// REAL-WORLD ANALOGY:
//   Traveling in Europe with an Indian power plug. The wall socket is a
//   different shape. You don't rewire your laptop. You don't rewire the wall.
//   You use a plug adapter — it sits between the two, translating one
//   interface to the other. Neither side changes.
//
// THE PROBLEM THIS SOLVES:
//   Your system already has a PaymentProcessor interface that your entire
//   checkout pipeline is built on. Stripe implements it natively.
//   Now you want to add Razorpay — but Razorpay's SDK has a completely
//   different API: different method names, different data formats (paise vs
//   rupees), different request/response objects.
//   You cannot modify your system's interface (in production).
//   You cannot modify Razorpay's SDK (third-party).
//   You need an Adapter.
//
// THREE INGREDIENTS:
//   1. Target interface  → what your existing code expects (PaymentProcessor)
//   2. Adaptee           → the incompatible third-party class (RazorpayGateway)
//   3. Adapter           → wraps Adaptee, implements Target, translates calls
// =============================================================================

public class AdapterPattern {

    // =========================================================================
    // TARGET INTERFACE
    // This is the contract your entire system is built around.
    // Every payment processor must look like this to your system.
    // =========================================================================
    interface PaymentProcessor {
        PaymentResult charge(String customerId, double amount, String currency);
        boolean refund(String transactionId, double amount);
    }


    // =========================================================================
    // SHARED DATA MODELS — used by the target interface
    // =========================================================================
    static class PaymentResult {
        private final boolean success;
        private final String transactionId;
        private final String message;

        public PaymentResult(boolean success, String transactionId, String message) {
            this.success       = success;
            this.transactionId = transactionId;
            this.message       = message;
        }

        public boolean isSuccess()       { return success; }
        public String getTransactionId() { return transactionId; }
        public String getMessage()       { return message; }

        @Override
        public String toString() {
            return String.format("PaymentResult{success=%b, txId='%s', msg='%s'}",
                    success, transactionId, message);
        }
    }


    // =========================================================================
    // CONCRETE IMPLEMENTATION 1: Stripe (native — no adapter needed)
    // This already implements PaymentProcessor directly. Works perfectly.
    // =========================================================================
    static class StripePaymentProcessor implements PaymentProcessor {

        @Override
        public PaymentResult charge(String customerId, double amount, String currency) {
            System.out.println("  [Stripe SDK] Charging customer: " + customerId
                    + " | Amount: " + currency + " " + amount);
            // Simulate a successful charge
            String txId = "stripe-txn-" + System.currentTimeMillis();
            return new PaymentResult(true, txId, "Stripe charge successful");
        }

        @Override
        public boolean refund(String transactionId, double amount) {
            System.out.println("  [Stripe SDK] Refunding txn: " + transactionId
                    + " | Amount: " + amount);
            return true;
        }
    }


    // =========================================================================
    // THE ADAPTEE: RazorpayGateway
    //
    // This is a third-party SDK. You CANNOT modify it.
    // Notice how different it is from PaymentProcessor:
    //   - Method names are different (initiatePayment vs charge)
    //   - Amount is in PAISE, not rupees (100 paise = 1 rupee)
    //   - Uses its own request/response objects (not ours)
    //   - No concept of customerId — uses orderId instead
    // =========================================================================
    static class RazorpayGateway {

        // Third-party method — completely different signature from charge()
        public RazorpayResponse initiatePayment(RazorpayRequest request) {
            System.out.println("  [Razorpay SDK] Initiating payment"
                    + " | Order: " + request.getOrderId()
                    + " | Amount: " + request.getAmountInPaise() + " paise"
                    + " (" + (request.getAmountInPaise() / 100.0) + " INR)");
            String paymentId = "rzp-pay-" + System.currentTimeMillis();
            return new RazorpayResponse(paymentId, "SUCCESS", "Payment completed successfully");
        }

        // Third-party method — different name and signature from refund()
        public boolean cancelPayment(String razorpayPaymentId) {
            System.out.println("  [Razorpay SDK] Cancelling payment: " + razorpayPaymentId);
            return true;
        }
    }

    // Razorpay's own request model — cannot be used with our PaymentProcessor directly
    static class RazorpayRequest {
        private final String orderId;
        private final long amountInPaise; // Note: paise, not rupees!

        public RazorpayRequest(String orderId, long amountInPaise) {
            this.orderId       = orderId;
            this.amountInPaise = amountInPaise;
        }

        public String getOrderId()     { return orderId; }
        public long getAmountInPaise() { return amountInPaise; }
    }

    // Razorpay's own response model — cannot be used with our PaymentResult directly
    static class RazorpayResponse {
        private final String paymentId;
        private final String status;
        private final String description;

        public RazorpayResponse(String paymentId, String status, String description) {
            this.paymentId   = paymentId;
            this.status      = status;
            this.description = description;
        }

        public String getPaymentId()   { return paymentId; }
        public String getStatus()      { return status; }
        public String getDescription() { return description; }
    }


    // =========================================================================
    // THE ADAPTER: RazorpayAdapter
    //
    // This is the translator class. It:
    //   - IMPLEMENTS PaymentProcessor (so your system accepts it)
    //   - WRAPS RazorpayGateway (so it can delegate to Razorpay)
    //   - TRANSLATES every call: our types → Razorpay types, and back
    //
    // Your CheckoutService never knows it's talking to Razorpay.
    // Razorpay never knows it's being used by your system.
    // The Adapter bridges them invisibly.
    // =========================================================================
    static class RazorpayAdapter implements PaymentProcessor {

        private final RazorpayGateway razorpay; // the adaptee being wrapped

        public RazorpayAdapter(RazorpayGateway razorpay) {
            this.razorpay = razorpay;
        }

        @Override
        public PaymentResult charge(String customerId, double amount, String currency) {
            System.out.println("  [RazorpayAdapter] Translating charge() → initiatePayment()");

            // Translation 1: customerId → orderId (Razorpay uses order IDs, not customer IDs)
            String orderId = "order-" + customerId + "-" + System.currentTimeMillis();

            // Translation 2: amount in rupees → amount in paise
            // Your system sends 1499.0 (rupees) — Razorpay expects 149900 (paise)
            long amountInPaise = (long)(amount * 100);

            // Translation 3: build Razorpay's request object from our parameters
            RazorpayRequest razorpayRequest = new RazorpayRequest(orderId, amountInPaise);

            // Delegate to the actual Razorpay SDK
            RazorpayResponse razorpayResponse = razorpay.initiatePayment(razorpayRequest);

            // Translation 4: convert Razorpay's response back into our PaymentResult
            boolean success = "SUCCESS".equals(razorpayResponse.getStatus());
            return new PaymentResult(success, razorpayResponse.getPaymentId(),
                    razorpayResponse.getDescription());
        }

        @Override
        public boolean refund(String transactionId, double amount) {
            System.out.println("  [RazorpayAdapter] Translating refund() → cancelPayment()");

            // Translation: our transactionId = Razorpay's paymentId
            // Amount is not needed by Razorpay's cancel method — just drops it
            return razorpay.cancelPayment(transactionId);
        }
    }


    // =========================================================================
    // ANOTHER ADAPTEE: PayPalGateway
    // Demonstrates that you can add more payment providers the same way —
    // each with its own Adapter, zero changes to CheckoutService.
    // =========================================================================
    static class PayPalGateway {
        // PayPal also has a completely different API
        public String executePayment(String payerEmail, double usdAmount) {
            System.out.println("  [PayPal SDK] Executing payment"
                    + " | Payer: " + payerEmail
                    + " | Amount: USD " + usdAmount);
            return "PAYPAL-TXN-" + System.currentTimeMillis();
        }

        public void issueRefund(String paypalTxnId) {
            System.out.println("  [PayPal SDK] Issuing refund for: " + paypalTxnId);
        }
    }

    // Adapter for PayPal — same pattern, different translation logic
    static class PayPalAdapter implements PaymentProcessor {
        private final PayPalGateway payPal;
        private static final double INR_TO_USD = 0.012; // conversion rate

        public PayPalAdapter(PayPalGateway payPal) {
            this.payPal = payPal;
        }

        @Override
        public PaymentResult charge(String customerId, double amount, String currency) {
            System.out.println("  [PayPalAdapter] Translating charge() → executePayment()");

            // Translation: INR → USD, customerId → email
            double usdAmount   = amount * INR_TO_USD;
            String payerEmail  = customerId + "@customer.com"; // simplified

            String txnId = payPal.executePayment(payerEmail, usdAmount);
            return new PaymentResult(txnId != null, txnId, "PayPal payment successful");
        }

        @Override
        public boolean refund(String transactionId, double amount) {
            System.out.println("  [PayPalAdapter] Translating refund() → issueRefund()");
            payPal.issueRefund(transactionId);
            return true;
        }
    }


    // =========================================================================
    // CLIENT: CheckoutService
    //
    // KEY POINT: This class only depends on PaymentProcessor (the interface).
    // It has NO knowledge of Stripe, Razorpay, or PayPal.
    // Swapping providers means changing ONE LINE at the wiring point.
    // This is the entire value of the Adapter pattern.
    // =========================================================================
    static class CheckoutService {
        private final PaymentProcessor paymentProcessor; // only interface, no concrete class

        public CheckoutService(PaymentProcessor paymentProcessor) {
            this.paymentProcessor = paymentProcessor;
        }

        public void processOrder(String customerId, double amount) {
            System.out.println("\n  [CheckoutService] Processing order"
                    + " | Customer: " + customerId
                    + " | Amount: INR " + amount);

            PaymentResult result = paymentProcessor.charge(customerId, amount, "INR");

            if (result.isSuccess()) {
                System.out.println("  [CheckoutService] ✓ Payment succeeded: " + result);
            } else {
                System.out.println("  [CheckoutService] ✗ Payment failed: " + result.getMessage());
            }
        }

        public void refundOrder(String transactionId, double amount) {
            System.out.println("\n  [CheckoutService] Processing refund | TxId: " + transactionId);
            boolean refunded = paymentProcessor.refund(transactionId, amount);
            System.out.println("  [CheckoutService] Refund " + (refunded ? "succeeded" : "failed"));
        }
    }


    // =========================================================================
    // MAIN — demonstrates all three providers via the same CheckoutService
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Adapter Pattern Demo ===");
        System.out.println("Same CheckoutService, three different payment providers.\n");

        // ── Provider 1: Stripe — native, no adapter needed ──
        System.out.println("─── Provider 1: Stripe (native) ───────────────────────");
        CheckoutService stripeCheckout = new CheckoutService(new StripePaymentProcessor());
        stripeCheckout.processOrder("user-101", 1499.0);
        stripeCheckout.refundOrder("stripe-txn-12345", 1499.0);

        // ── Provider 2: Razorpay — via adapter ──
        System.out.println("\n─── Provider 2: Razorpay (via Adapter) ─────────────────");
        RazorpayGateway razorpaySDK = new RazorpayGateway();         // the incompatible SDK
        PaymentProcessor razorpayAdapter = new RazorpayAdapter(razorpaySDK); // wrapped in adapter
        CheckoutService razorpayCheckout = new CheckoutService(razorpayAdapter);
        // CheckoutService sees only PaymentProcessor — has no idea Razorpay is involved
        razorpayCheckout.processOrder("user-202", 2999.0);
        razorpayCheckout.refundOrder("rzp-pay-12345", 2999.0);

        // ── Provider 3: PayPal — via adapter ──
        System.out.println("\n─── Provider 3: PayPal (via Adapter) ───────────────────");
        PayPalGateway payPalSDK = new PayPalGateway();
        CheckoutService paypalCheckout = new CheckoutService(new PayPalAdapter(payPalSDK));
        paypalCheckout.processOrder("user-303", 4999.0);

        // ── Demonstrating interface uniformity ──
        System.out.println("\n─── Polymorphic usage — same loop, all providers ────────");
        PaymentProcessor[] processors = {
            new StripePaymentProcessor(),
            new RazorpayAdapter(new RazorpayGateway()),
            new PayPalAdapter(new PayPalGateway())
        };
        String[] customers = {"user-A", "user-B", "user-C"};
        double[] amounts   = {500.0, 1000.0, 750.0};

        for (int i = 0; i < processors.length; i++) {
            PaymentResult result = processors[i].charge(customers[i], amounts[i], "INR");
            System.out.println("  → " + result);
        }

        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. CheckoutService never changed — it only depends on PaymentProcessor");
        System.out.println("  2. Adding a new provider = write one Adapter class, nothing else");
        System.out.println("  3. Adapter handles all translation: names, types, formats, units");
        System.out.println("  4. The adaptee (RazorpayGateway) is never modified");
    }
}

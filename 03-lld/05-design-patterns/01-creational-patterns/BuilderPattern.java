import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// =============================================================================
// PATTERN: Builder
// PURPOSE: Construct complex objects step by step. Separate the construction
//          logic from the representation, and validate before the final object
//          is ever created.
//
// REAL-WORLD ANALOGY:
//   Ordering a custom burger. You tell the counter:
//     "Brioche bun, beef patty, add cheese, add lettuce, no onions, BBQ sauce."
//   You configure it piece by piece. Then you say "done — make it."
//   The Builder mirrors this: configure step by step, then call build().
//
// THE PROBLEM IT SOLVES:
//   Without Builder (telescoping constructors):
//     new Order("ORD-001", "user-123", items, "CREDIT_CARD", null, true, false, null, "EXPRESS");
//   → What does each null mean? What does true mean? This is unreadable.
//   → Adding a new optional field means adding yet another constructor overload.
//   → You can't validate "items must not be empty" until after construction.
//
//   With Builder:
//     new Order.Builder("ORD-001", "user-123", "Mumbai", "UPI")
//         .addItem(item1)
//         .expressDelivery(true)
//         .promoCode("SAVE20")
//         .build();
//   → Every line is self-documenting. No mystery nulls.
//   → build() validates everything before the Order is ever created.
//
// FOUR INGREDIENTS:
//   1. Target object (Order)       → immutable, private constructor, final fields
//   2. Static inner Builder class  → holds mutable state during construction
//   3. Fluent setters              → each returns `this` to enable chaining
//   4. build() method              → validates cross-field rules, then constructs
// =============================================================================

public class BuilderPattern {

    // =========================================================================
    // SUPPORTING CLASS: OrderItem
    // =========================================================================
    static class OrderItem {
        private final String productId;
        private final String productName;
        private final int quantity;
        private final double unitPrice;

        public OrderItem(String productId, String productName, int quantity, double unitPrice) {
            if (quantity <= 0)    throw new IllegalArgumentException("Quantity must be > 0");
            if (unitPrice <= 0)   throw new IllegalArgumentException("Price must be > 0");

            this.productId   = productId;
            this.productName = productName;
            this.quantity    = quantity;
            this.unitPrice   = unitPrice;
        }

        public double getTotal() { return quantity * unitPrice; }

        @Override
        public String toString() {
            return String.format("%s (x%d @ ₹%.2f = ₹%.2f)", productName, quantity, unitPrice, getTotal());
        }
    }


    // =========================================================================
    // THE PRODUCT: Order
    //
    // DESIGN DECISIONS:
    //   - `final` class → can't be subclassed (immutability intent)
    //   - All fields are `final` → the object is immutable after construction
    //   - No setters → once built, an Order cannot be modified
    //   - Private constructor → ONLY the inner Builder can call it
    //   - Takes a Builder → all fields set from the builder in one place
    // =========================================================================
    static final class Order {
        // --- Required fields --- always present, never null
        private final String orderId;
        private final String customerId;
        private final String shippingAddress;
        private final String paymentMethod;
        private final List<OrderItem> items;
        private final LocalDateTime createdAt;

        // --- Optional fields --- may or may not be set by the caller
        private final boolean expressDelivery;
        private final String promoCode;         // null if not provided
        private final String deliveryNotes;     // null if not provided

        // Private constructor — takes the fully-configured Builder
        // Every field is read from the builder — no parameter juggling
        private Order(Builder builder) {
            this.orderId         = builder.orderId;
            this.customerId      = builder.customerId;
            this.shippingAddress = builder.shippingAddress;
            this.paymentMethod   = builder.paymentMethod;
            // Defensive copy + make unmodifiable — caller can't modify our internal list
            this.items           = Collections.unmodifiableList(new ArrayList<>(builder.items));
            this.expressDelivery = builder.expressDelivery;
            this.promoCode       = builder.promoCode;
            this.deliveryNotes   = builder.deliveryNotes;
            this.createdAt       = LocalDateTime.now(); // set at construction time, not by caller
        }

        // --- Getters only --- no setters. Immutable after build().
        public String getOrderId()           { return orderId; }
        public String getCustomerId()        { return customerId; }
        public String getShippingAddress()   { return shippingAddress; }
        public String getPaymentMethod()     { return paymentMethod; }
        public List<OrderItem> getItems()    { return items; }
        public boolean isExpressDelivery()   { return expressDelivery; }
        public String getPromoCode()         { return promoCode; }
        public String getDeliveryNotes()     { return deliveryNotes; }

        public double getTotalAmount() {
            return items.stream().mapToDouble(OrderItem::getTotal).sum();
        }

        public void printReceipt() {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
            System.out.println("  ┌─────────────────────────────────────────┐");
            System.out.println("  │              ORDER RECEIPT               │");
            System.out.println("  ├─────────────────────────────────────────┤");
            System.out.printf ("  │ Order ID  : %-28s │%n", orderId);
            System.out.printf ("  │ Customer  : %-28s │%n", customerId);
            System.out.printf ("  │ Payment   : %-28s │%n", paymentMethod);
            System.out.printf ("  │ Express   : %-28s │%n", expressDelivery ? "YES" : "No");
            System.out.printf ("  │ Promo     : %-28s │%n", promoCode != null ? promoCode : "None");
            System.out.printf ("  │ Notes     : %-28s │%n", deliveryNotes != null ? deliveryNotes : "None");
            System.out.println("  ├─────────────────────────────────────────┤");
            System.out.println("  │ Items:                                   │");
            for (OrderItem item : items) {
                System.out.printf("  │   %-39s│%n", item.toString());
            }
            System.out.println("  ├─────────────────────────────────────────┤");
            System.out.printf ("  │ TOTAL     : ₹%-27.2f │%n", getTotalAmount());
            System.out.printf ("  │ Created   : %-28s │%n", createdAt.format(fmt));
            System.out.println("  └─────────────────────────────────────────┘");
        }


        // =====================================================================
        // THE BUILDER — static inner class
        //
        // DESIGN DECISIONS:
        //   - Required fields go in Builder's constructor → compile-time safety.
        //     You CANNOT call build() without providing them (they're not optional).
        //   - Optional fields have sensible defaults (false, null).
        //   - Each setter returns `this` → enables fluent chaining:
        //       builder.addItem(x).expressDelivery(true).promoCode("SAVE20")
        //   - build() validates cross-field rules (e.g., items must not be empty)
        //     before the Order is ever constructed.
        // =====================================================================
        static class Builder {
            // Required — set in constructor, always present
            private final String orderId;
            private final String customerId;
            private final String shippingAddress;
            private final String paymentMethod;

            // Items accumulate via addItem()
            private final List<OrderItem> items = new ArrayList<>();

            // Optional — have default values
            private boolean expressDelivery = false;
            private String promoCode        = null;
            private String deliveryNotes    = null;

            // Required params in the constructor.
            // If a caller skips any of these, their code won't compile.
            public Builder(String orderId, String customerId, String shippingAddress, String paymentMethod) {
                // Fail-fast validation: catch bad input immediately, not later
                if (orderId == null || orderId.isBlank())
                    throw new IllegalArgumentException("orderId is required");
                if (customerId == null || customerId.isBlank())
                    throw new IllegalArgumentException("customerId is required");
                if (shippingAddress == null || shippingAddress.isBlank())
                    throw new IllegalArgumentException("shippingAddress is required");
                if (paymentMethod == null || paymentMethod.isBlank())
                    throw new IllegalArgumentException("paymentMethod is required");

                this.orderId         = orderId;
                this.customerId      = customerId;
                this.shippingAddress = shippingAddress;
                this.paymentMethod   = paymentMethod;
            }

            // --- Fluent setters --- each returns `this` so you can chain calls ---

            public Builder addItem(OrderItem item) {
                Objects.requireNonNull(item, "OrderItem cannot be null");
                this.items.add(item);
                return this; // ← returning `this` is what makes chaining possible
            }

            public Builder expressDelivery(boolean express) {
                this.expressDelivery = express;
                return this;
            }

            public Builder promoCode(String code) {
                this.promoCode = code;
                return this;
            }

            public Builder deliveryNotes(String notes) {
                this.deliveryNotes = notes;
                return this;
            }

            // build() — final validation gate before the object is created.
            // Cross-field rules (can't be checked individually) are validated here.
            public Order build() {
                // Rule: an order must have at least one item
                if (items.isEmpty()) {
                    throw new IllegalStateException("Order must contain at least one item");
                }

                // Rule: express delivery requires a valid shipping address (already checked above,
                // but you could add more express-specific validation here)
                if (expressDelivery && shippingAddress.equalsIgnoreCase("pickup")) {
                    throw new IllegalStateException("Express delivery is not available for store pickup");
                }

                // All validations passed — now create the immutable Order
                return new Order(this);
            }
        }
    }


    // =========================================================================
    // MAIN — demonstrates the Builder pattern
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Builder Pattern Demo ===\n");

        // ----- Example 1: Full order with all optional fields -----
        System.out.println("--- Example 1: Full order with all options ---\n");

        Order fullOrder = new Order.Builder("ORD-001", "USR-42", "5th Floor, Tower B, Hitech City, Hyderabad", "UPI")
                .addItem(new OrderItem("PROD-A", "Mechanical Keyboard",    1, 4999.00))
                .addItem(new OrderItem("PROD-B", "Ergonomic Mouse",        1, 1999.00))
                .addItem(new OrderItem("PROD-C", "USB-C Hub",              2,  899.00))
                .expressDelivery(true)              // optional — set
                .promoCode("TECH20")                // optional — set
                .deliveryNotes("Leave at reception") // optional — set
                .build();

        fullOrder.printReceipt();

        // ----- Example 2: Minimal order — only required fields -----
        System.out.println("\n--- Example 2: Minimal order (only required fields) ---\n");

        // Optional fields (expressDelivery, promoCode, deliveryNotes) are NOT set.
        // They default to false/null. The object is still valid and complete.
        Order minimalOrder = new Order.Builder("ORD-002", "USR-99", "Koramangala, Bengaluru", "CARD")
                .addItem(new OrderItem("PROD-D", "Coffee Mug", 2, 299.00))
                .build(); // no express, no promo, no notes

        minimalOrder.printReceipt();


        // ----- Example 3: Validation — missing items -----
        System.out.println("\n--- Example 3: Validation — no items → build() throws ---");
        try {
            Order emptyOrder = new Order.Builder("ORD-003", "USR-10", "Delhi", "NET_BANKING")
                    .promoCode("SAVE10")
                    // forgot to add items
                    .build(); // ← should throw IllegalStateException
        } catch (IllegalStateException e) {
            System.out.println("  Caught: " + e.getMessage());
        }


        // ----- Example 4: Validation — missing required field -----
        System.out.println("\n--- Example 4: Validation — missing required field → constructor throws ---");
        try {
            Order badOrder = new Order.Builder("ORD-004", "USR-11", "", "UPI") // blank address
                    .addItem(new OrderItem("PROD-E", "Notebook", 1, 99.0))
                    .build();
        } catch (IllegalArgumentException e) {
            System.out.println("  Caught: " + e.getMessage());
        }


        // ----- Example 5: Immutability — can't modify after build -----
        System.out.println("\n--- Example 5: Immutability — items list is unmodifiable after build ---");
        try {
            fullOrder.getItems().add(new OrderItem("HACKED", "Injected Item", 1, 0.01));
        } catch (UnsupportedOperationException e) {
            System.out.println("  Caught UnsupportedOperationException — list is unmodifiable.");
            System.out.println("  The built Order cannot be tampered with externally.");
        }

        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Required fields in Builder constructor → compile-time safety");
        System.out.println("  2. Optional fields as fluent methods → clean, readable call site");
        System.out.println("  3. build() validates cross-field rules → bad objects are never created");
        System.out.println("  4. Immutable target object → safe to share references anywhere");
        System.out.println("  5. `return this` in each setter → enables chaining without extra code");
    }
}

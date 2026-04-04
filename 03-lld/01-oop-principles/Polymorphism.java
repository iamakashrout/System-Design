// ============================================================
// OOP Principle 4: POLYMORPHISM
// "One interface, many behaviors"
//
// Demonstrated through two systems:
//   1. Shape area calculator — runtime polymorphism via inheritance
//   2. Payment processor — runtime polymorphism via interface
//   3. Method overloading  — compile-time polymorphism
//
// Key lesson: write code once against an abstraction.
// The right behavior fires automatically at runtime.
// No instanceof checks. No conditionals on type.
// ============================================================

import java.util.ArrayList;
import java.util.List;

public class Polymorphism {

    // ════════════════════════════════════════════════════════
    // PART 1: Runtime Polymorphism via Inheritance
    // Shape hierarchy — same method call, different behavior
    // ════════════════════════════════════════════════════════

    static abstract class Shape {
        protected String color;

        public Shape(String color) {
            this.color = color;
        }

        // Every shape MUST define how to calculate its own area
        public abstract double area();

        // Every shape MUST define how to calculate its own perimeter
        public abstract double perimeter();

        // Shared method — uses abstract methods internally (polymorphism within the class)
        public void describe() {
            System.out.println("  Shape: " + getClass().getSimpleName()
                + " | Color: " + color
                + " | Area: " + String.format("%.2f", area())
                + " | Perimeter: " + String.format("%.2f", perimeter()));
        }
    }

    static class Circle extends Shape {
        private double radius;

        public Circle(String color, double radius) {
            super(color);
            this.radius = radius;
        }

        @Override
        public double area() {
            return Math.PI * radius * radius;
        }

        @Override
        public double perimeter() {
            return 2 * Math.PI * radius;
        }
    }

    static class Rectangle extends Shape {
        private double width, height;

        public Rectangle(String color, double width, double height) {
            super(color);
            this.width = width;
            this.height = height;
        }

        @Override
        public double area() {
            return width * height;
        }

        @Override
        public double perimeter() {
            return 2 * (width + height);
        }
    }

    static class Triangle extends Shape {
        private double base, height, side1, side2;

        public Triangle(String color, double base, double height, double side1, double side2) {
            super(color);
            this.base = base;
            this.height = height;
            this.side1 = side1;
            this.side2 = side2;
        }

        @Override
        public double area() {
            return 0.5 * base * height;
        }

        @Override
        public double perimeter() {
            return base + side1 + side2;
        }
    }

    static class Square extends Shape {
        private double side;

        public Square(String color, double side) {
            super(color);
            this.side = side;
        }

        @Override
        public double area() {
            return side * side;
        }

        @Override
        public double perimeter() {
            return 4 * side;
        }
    }

    // One method that works for ALL shapes — current and future
    static void printAllShapes(List<Shape> shapes) {
        System.out.println("  Describing all shapes:");
        for (Shape shape : shapes) {
            shape.describe(); // right describe() fires for each — dynamic dispatch
        }
    }

    static double totalArea(List<Shape> shapes) {
        double total = 0;
        for (Shape shape : shapes) {
            total += shape.area(); // right area() fires for each — no instanceof needed
        }
        return total;
    }


    // ════════════════════════════════════════════════════════
    // PART 2: Runtime Polymorphism via Interface
    // Payment processor — swap implementations freely
    // ════════════════════════════════════════════════════════

    interface PaymentProcessor {
        boolean process(String orderId, double amount);
        boolean refund(String transactionId, double amount);
        String getProviderName();
    }

    static class StripeProcessor implements PaymentProcessor {
        private int transactionCount = 0;

        @Override
        public boolean process(String orderId, double amount) {
            transactionCount++;
            System.out.println("  [Stripe] Processing ₹" + amount
                + " for order #" + orderId
                + " | TxnID: STR-" + (1000 + transactionCount));
            System.out.println("  [Stripe] Calling Stripe API → Applying retry logic → Mapping errors...");
            System.out.println("  [Stripe] ✔ Payment successful.");
            return true;
        }

        @Override
        public boolean refund(String transactionId, double amount) {
            System.out.println("  [Stripe] Initiating refund of ₹" + amount
                + " for transaction: " + transactionId);
            System.out.println("  [Stripe] ✔ Refund processed.");
            return true;
        }

        @Override
        public String getProviderName() { return "Stripe"; }
    }

    static class RazorpayProcessor implements PaymentProcessor {
        private int transactionCount = 0;

        @Override
        public boolean process(String orderId, double amount) {
            transactionCount++;
            System.out.println("  [Razorpay] Processing ₹" + amount
                + " for order #" + orderId
                + " | TxnID: RPY-" + (2000 + transactionCount));
            System.out.println("  [Razorpay] Calling Razorpay SDK → Validating UPI/card → Confirming...");
            System.out.println("  [Razorpay] ✔ Payment successful.");
            return true;
        }

        @Override
        public boolean refund(String transactionId, double amount) {
            System.out.println("  [Razorpay] Initiating refund of ₹" + amount
                + " for transaction: " + transactionId);
            System.out.println("  [Razorpay] ✔ Refund processed.");
            return true;
        }

        @Override
        public String getProviderName() { return "Razorpay"; }
    }

    static class UpiProcessor implements PaymentProcessor {
        private int transactionCount = 0;

        @Override
        public boolean process(String orderId, double amount) {
            transactionCount++;
            System.out.println("  [UPI] Processing ₹" + amount
                + " for order #" + orderId
                + " | TxnID: UPI-" + (3000 + transactionCount));
            System.out.println("  [UPI] Sending collect request → Waiting for PIN confirmation...");
            System.out.println("  [UPI] ✔ Payment successful.");
            return true;
        }

        @Override
        public boolean refund(String transactionId, double amount) {
            System.out.println("  [UPI] Reversing transaction: " + transactionId);
            System.out.println("  [UPI] ✔ Reversal successful.");
            return true;
        }

        @Override
        public String getProviderName() { return "UPI"; }
    }

    // CheckoutService only knows about PaymentProcessor — never Stripe/Razorpay/UPI
    static class CheckoutService {
        private final PaymentProcessor processor;

        public CheckoutService(PaymentProcessor processor) {
            this.processor = processor;
            System.out.println("  [CheckoutService] Initialized with provider: "
                + processor.getProviderName());
        }

        public void checkout(String orderId, double amount) {
            System.out.println("  [CheckoutService] Checking out order #" + orderId
                + " for ₹" + amount);
            boolean success = processor.process(orderId, amount);
            if (success) {
                System.out.println("  [CheckoutService] Order #" + orderId + " confirmed.");
            } else {
                System.out.println("  [CheckoutService] Order #" + orderId + " FAILED.");
            }
        }
    }


    // ════════════════════════════════════════════════════════
    // PART 3: Compile-Time Polymorphism — Method Overloading
    // Same method name, different signatures
    // Java picks the right one at compile time
    // ════════════════════════════════════════════════════════

    static class Logger {

        // Overload 1: log just a message
        public void log(String message) {
            System.out.println("  [LOG] " + message);
        }

        // Overload 2: log with a level
        public void log(String level, String message) {
            System.out.println("  [" + level.toUpperCase() + "] " + message);
        }

        // Overload 3: log with level and a numeric value
        public void log(String level, String message, double value) {
            System.out.println("  [" + level.toUpperCase() + "] " + message + " → " + value);
        }

        // Overload 4: log multiple messages at once
        public void log(String level, String... messages) {
            System.out.print("  [" + level.toUpperCase() + "] Batch: ");
            for (String m : messages) System.out.print(m + " | ");
            System.out.println();
        }
    }


    // ── Main ──────────────────────────────────────────────────
    public static void main(String[] args) {

        System.out.println("═══════════════════════════════════════");
        System.out.println("  OOP PRINCIPLE 4: POLYMORPHISM");
        System.out.println("═══════════════════════════════════════\n");

        // ── Demo 1: Runtime polymorphism — Shape hierarchy ──
        System.out.println(">>> Demo 1: Runtime Polymorphism — Shape Hierarchy");
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Circle("Red", 7));
        shapes.add(new Rectangle("Blue", 5, 8));
        shapes.add(new Triangle("Green", 6, 4, 5, 5));
        shapes.add(new Square("Yellow", 4));

        printAllShapes(shapes);  // same method, 4 different behaviors
        System.out.println("  Total area of all shapes: "
            + String.format("%.2f", totalArea(shapes)) + " sq units");

        // ── Demo 2: Adding a new shape — existing code unchanged ──
        System.out.println("\n>>> Demo 2: New Shape Added — Existing Code Unchanged");
        shapes.add(new Circle("Purple", 3));
        shapes.add(new Rectangle("Orange", 10, 2));
        System.out.println("  Updated total area: "
            + String.format("%.2f", totalArea(shapes)) + " sq units");
        System.out.println("  (totalArea and printAllShapes methods were NOT changed)");

        // ── Demo 3: Runtime polymorphism — Payment via Interface ──
        System.out.println("\n>>> Demo 3: Runtime Polymorphism — Payment Processors");
        System.out.println("  Checkout with Stripe:");
        CheckoutService stripeCheckout = new CheckoutService(new StripeProcessor());
        stripeCheckout.checkout("ORD-101", 2999.00);

        System.out.println();
        System.out.println("  Checkout with Razorpay:");
        CheckoutService razorpayCheckout = new CheckoutService(new RazorpayProcessor());
        razorpayCheckout.checkout("ORD-102", 1599.00);

        System.out.println();
        System.out.println("  Checkout with UPI:");
        CheckoutService upiCheckout = new CheckoutService(new UpiProcessor());
        upiCheckout.checkout("ORD-103", 499.00);

        // ── Demo 4: Swap processor at runtime ──
        System.out.println("\n>>> Demo 4: Polymorphism — Same Loop, All Processors");
        List<PaymentProcessor> processors = new ArrayList<>();
        processors.add(new StripeProcessor());
        processors.add(new RazorpayProcessor());
        processors.add(new UpiProcessor());

        System.out.println("  Processing batch payments via all providers:");
        for (PaymentProcessor p : processors) {
            p.process("BATCH-999", 100.00); // right process() fires for each
        }

        // ── Demo 5: Compile-time polymorphism — Overloading ──
        System.out.println("\n>>> Demo 5: Compile-Time Polymorphism — Method Overloading");
        Logger logger = new Logger();
        logger.log("Server started successfully");                         // overload 1
        logger.log("INFO", "User logged in: akash@example.com");          // overload 2
        logger.log("WARN", "Memory usage", 87.5);                         // overload 3
        logger.log("DEBUG", "Step 1", "Step 2", "Step 3", "Step 4");      // overload 4

        System.out.println("\n>>> Demo 6: The instanceof Anti-Pattern vs Polymorphism");

        // BAD WAY — without polymorphism
        System.out.println("  BAD: instanceof chain (what you'd write without polymorphism):");
        List<Shape> badList = new ArrayList<>();
        badList.add(new Circle("Red", 5));
        badList.add(new Rectangle("Blue", 3, 4));
        for (Shape s : badList) {
            if (s instanceof Circle) {
                System.out.println("  → It's a Circle, call special circle method...");
            } else if (s instanceof Rectangle) {
                System.out.println("  → It's a Rectangle, call special rectangle method...");
            }
            // every new shape type = modify this block
        }

        // GOOD WAY — with polymorphism
        System.out.println("  GOOD: polymorphic call (what you write with polymorphism):");
        for (Shape s : badList) {
            s.describe(); // just this — works for any shape, now or in the future
        }

        System.out.println("\n✔ Polymorphism: one interface, right behavior fires automatically.");
        System.out.println("  Adding new types never requires changing existing calling code.");
    }
}

// ============================================================
//  O — Open/Closed Principle (OCP)
//
//  Principle : Classes should be OPEN for extension
//              but CLOSED for modification.
//
//  Example   : A discount system for an e-commerce platform.
//              BAD  → every new discount type edits existing code
//              GOOD → every new discount type = a new class only
// ============================================================

public class O_OpenClosed {

    // ─────────────────────────────────────────────
    //  VIOLATION — an if-else chain that grows forever
    // ─────────────────────────────────────────────

    /**
     * VIOLATION: Adding any new discount type forces you to
     * open this method and edit it.
     *
     * Risk: while adding STUDENT, you could accidentally change
     * the LOYALTY multiplier and introduce a regression.
     * String-based dispatch also has no compile-time safety.
     */
    static class DiscountCalculatorViolation {
        double calculate(double total, String discountType) {
            if (discountType.equals("SEASONAL")) {
                return total * 0.10;
            } else if (discountType.equals("LOYALTY")) {
                return total * 0.15;
            } else if (discountType.equals("EMPLOYEE")) {
                return total * 0.20;
            }
            // Adding STUDENT → must edit this class again (violation!)
            return 0.0;
        }
    }

    // ─────────────────────────────────────────────
    //  FIX — model variation as types, not conditions
    // ─────────────────────────────────────────────

    /**
     * The stable contract — owned by the business layer.
     * Think of it as a power socket: strategies just plug in.
     * This interface NEVER changes, no matter how many
     * discount types are added in the future.
     */
    interface DiscountStrategy {
        /** Returns the discount amount (not the final price). */
        double calculate(double orderTotal);

        /** Human-readable name of this discount strategy. */
        String getName();
    }

    // Each discount type is its own class — isolated and independently testable.
    // Changing SeasonalDiscount can NEVER affect LoyaltyDiscount.

    /** 10% discount during seasonal sales. */
    static class SeasonalDiscount implements DiscountStrategy {
        @Override
        public double calculate(double orderTotal) {
            return orderTotal * 0.10;
        }

        @Override
        public String getName() {
            return "Seasonal (10%)";
        }
    }

    /** 15% for customers < 3 years, 20% for 3+ years. */
    static class LoyaltyDiscount implements DiscountStrategy {
        private final int loyaltyYears;

        LoyaltyDiscount(int loyaltyYears) {
            this.loyaltyYears = loyaltyYears;
        }

        @Override
        public double calculate(double orderTotal) {
            double rate = (loyaltyYears >= 3) ? 0.20 : 0.15;
            return orderTotal * rate;
        }

        @Override
        public String getName() {
            String pct = (loyaltyYears >= 3) ? "20%" : "15%";
            return "Loyalty " + loyaltyYears + "yr (" + pct + ")";
        }
    }

    /** 25% for employees. */
    static class EmployeeDiscount implements DiscountStrategy {
        @Override
        public double calculate(double orderTotal) {
            return orderTotal * 0.25;
        }

        @Override
        public String getName() {
            return "Employee (25%)";
        }
    }

    /**
     * NEW REQUIREMENT: Student discount.
     * Zero changes to any existing class — just a new class added.
     * This is OCP working exactly as intended.
     */
    static class StudentDiscount implements DiscountStrategy {
        @Override
        public double calculate(double orderTotal) {
            return orderTotal * 0.12;
        }

        @Override
        public String getName() {
            return "Student (12%)";
        }
    }

    /**
     * The calculator has ZERO if-else logic.
     * It simply delegates to whatever strategy was injected.
     * This class will NEVER need to change no matter how many
     * new discount types are added in the future.
     */
    static class DiscountCalculator {
        private final DiscountStrategy strategy;

        // Strategy is injected — calculator doesn't know or care which one
        DiscountCalculator(DiscountStrategy strategy) {
            this.strategy = strategy;
        }

        double calculate(double orderTotal) {
            return strategy.calculate(orderTotal);
        }

        void printSummary(double orderTotal) {
            double discount   = strategy.calculate(orderTotal);
            double finalPrice = orderTotal - discount;
            System.out.printf("  %-22s | Order: ₹%.2f | Discount: ₹%.2f | Final: ₹%.2f%n",
                    strategy.getName(), orderTotal, discount, finalPrice);
        }
    }

    // ─────────────────────────────────────────────
    //  DEMO — bad vs good design
    // ─────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  Open/Closed Principle               ║");
        System.out.println("╚══════════════════════════════════════╝\n");

        // ── VIOLATION ──
        System.out.println("── VIOLATION: if-else chain that grows forever ──");
        DiscountCalculatorViolation badCalc = new DiscountCalculatorViolation();
        System.out.printf("  SEASONAL  on ₹1000 → ₹%.2f%n", badCalc.calculate(1000, "SEASONAL"));
        System.out.printf("  LOYALTY   on ₹1000 → ₹%.2f%n", badCalc.calculate(1000, "LOYALTY"));
        System.out.println("  (Adding STUDENT requires opening and editing this class — risky!)\n");

        // ── FIX ──
        System.out.println("── FIX: each discount is a separate class ──\n");

        double orderTotal = 2000.0;
        System.out.printf("  Order total: ₹%.2f%n%n", orderTotal);

        // Each strategy is plugged into the same calculator
        DiscountCalculator[] calculators = {
            new DiscountCalculator(new SeasonalDiscount()),
            new DiscountCalculator(new LoyaltyDiscount(1)),   // 1-year → 15%
            new DiscountCalculator(new LoyaltyDiscount(5)),   // 5-year → 20%
            new DiscountCalculator(new EmployeeDiscount()),
            new DiscountCalculator(new StudentDiscount()),    // new type — zero existing code changed
        };

        for (DiscountCalculator calc : calculators) {
            calc.printSummary(orderTotal);
        }

        System.out.println();
        System.out.println("✔ StudentDiscount added — zero existing classes touched.");
        System.out.println("✔ DiscountCalculator has zero if-else — it never needs to change.");
    }
}

// =============================================================================
// PATTERN: Strategy
// PURPOSE: Define a family of algorithms, encapsulate each one, and make
//          them interchangeable. The algorithm can vary independently from
//          the clients that use it.
//
// REAL-WORLD ANALOGY:
//   A GPS navigation app has one job: get you from A to B. But HOW it
//   does that varies — fastest route, shortest route, avoid tolls, cycling.
//   The destination logic doesn't change. Only the routing algorithm changes.
//   Strategy lets you swap that algorithm independently of the navigation logic.
//
// THE PROBLEM THIS SOLVES:
//   Without Strategy, you get a growing if-else block:
//     if (type.equals("SEASONAL"))  { ... }
//     else if (type.equals("BULK")) { ... }   // add new rule = modify this class
//     else if (type.equals("VIP"))  { ... }   // Open/Closed Principle violated
//
//   With Strategy:
//     Adding a new pricing rule = add a new class, touch nothing else.
//
// THREE INGREDIENTS:
//   1. Strategy interface  → defines the algorithm's signature
//   2. Concrete strategies → each implements one variant of the algorithm
//   3. Context             → holds a strategy, delegates to it, can swap it
// =============================================================================

public class StrategyPattern {

    // =========================================================================
    // STEP 1: STRATEGY INTERFACE
    // This is the contract all pricing algorithms must follow.
    // The context (OrderPricingService) only depends on THIS — never on
    // a specific concrete strategy.
    // =========================================================================
    interface PricingStrategy {
        double calculatePrice(double basePrice, int quantity);
        String getStrategyName();
    }


    // =========================================================================
    // STEP 2: CONCRETE STRATEGIES
    // Each class encapsulates one pricing algorithm.
    // They are completely independent — none knows about the others.
    // Adding a new one never touches any existing class.
    // =========================================================================

    // ── Strategy 1: Regular Pricing — no discount ─────────────────────────────
    static class RegularPricing implements PricingStrategy {
        @Override
        public double calculatePrice(double basePrice, int quantity) {
            // Simple multiplication — no discounts
            return basePrice * quantity;
        }

        @Override
        public String getStrategyName() { return "REGULAR"; }
    }


    // ── Strategy 2: Seasonal Discount — percentage off the total ──────────────
    static class SeasonalDiscountPricing implements PricingStrategy {
        private final double discountPercent;

        public SeasonalDiscountPricing(double discountPercent) {
            if (discountPercent < 0 || discountPercent > 100)
                throw new IllegalArgumentException("Discount must be between 0 and 100");
            this.discountPercent = discountPercent;
        }

        @Override
        public double calculatePrice(double basePrice, int quantity) {
            double total    = basePrice * quantity;
            double discount = total * (discountPercent / 100.0);
            System.out.printf("    [Seasonal] Base total: ₹%.2f | Discount (%.0f%%): ₹%.2f%n",
                    total, discountPercent, discount);
            return total - discount;
        }

        @Override
        public String getStrategyName() { return "SEASONAL_" + (int) discountPercent + "%_OFF"; }
    }


    // ── Strategy 3: Loyalty Points — 1 rupee off per 10 loyalty points ────────
    static class LoyaltyPointsPricing implements PricingStrategy {
        private final int loyaltyPoints;

        public LoyaltyPointsPricing(int loyaltyPoints) {
            if (loyaltyPoints < 0)
                throw new IllegalArgumentException("Loyalty points cannot be negative");
            this.loyaltyPoints = loyaltyPoints;
        }

        @Override
        public double calculatePrice(double basePrice, int quantity) {
            double total          = basePrice * quantity;
            double pointsDiscount = loyaltyPoints / 10.0; // 10 points = ₹1 off
            double finalPrice     = Math.max(0, total - pointsDiscount); // floor at 0
            System.out.printf("    [Loyalty] Base total: ₹%.2f | Points (%d pts): -₹%.2f%n",
                    total, loyaltyPoints, pointsDiscount);
            return finalPrice;
        }

        @Override
        public String getStrategyName() { return "LOYALTY_" + loyaltyPoints + "pts"; }
    }


    // ── Strategy 4: Bulk Pricing — discount triggers above a threshold qty ────
    static class BulkPricing implements PricingStrategy {
        private static final int    BULK_THRESHOLD = 10;
        private static final double BULK_DISCOUNT   = 0.15; // 15% off

        @Override
        public double calculatePrice(double basePrice, int quantity) {
            double total = basePrice * quantity;
            if (quantity >= BULK_THRESHOLD) {
                double discount = total * BULK_DISCOUNT;
                System.out.printf("    [Bulk] Qty %d ≥ %d threshold | Discount (15%%): ₹%.2f%n",
                        quantity, BULK_THRESHOLD, discount);
                return total - discount;
            }
            // Below threshold — no bulk discount
            System.out.printf("    [Bulk] Qty %d < %d threshold — no bulk discount%n",
                    quantity, BULK_THRESHOLD);
            return total;
        }

        @Override
        public String getStrategyName() { return "BULK_15%_ABOVE_" + BULK_THRESHOLD + "units"; }
    }


    // ── Strategy 5: Flash Sale — fixed flat discount per item (added later) ───
    // Demonstrates that adding a new strategy NEVER touches existing classes.
    static class FlashSalePricing implements PricingStrategy {
        private final double flatDiscountPerItem;

        public FlashSalePricing(double flatDiscountPerItem) {
            this.flatDiscountPerItem = flatDiscountPerItem;
        }

        @Override
        public double calculatePrice(double basePrice, int quantity) {
            double discountedPrice = Math.max(0, basePrice - flatDiscountPerItem);
            double total           = discountedPrice * quantity;
            System.out.printf("    [Flash Sale] ₹%.2f off per item | Effective price: ₹%.2f%n",
                    flatDiscountPerItem, discountedPrice);
            return total;
        }

        @Override
        public String getStrategyName() { return "FLASH_SALE_₹" + (int) flatDiscountPerItem + "_OFF"; }
    }


    // =========================================================================
    // STEP 3: CONTEXT — OrderPricingService
    //
    // This is the class that USES the strategy. It has no pricing logic of its own.
    // It delegates entirely to whichever strategy it currently holds.
    //
    // KEY POINT: This class never changes when new pricing rules are added.
    // Open to extension (new strategy classes), closed to modification.
    // =========================================================================
    static class OrderPricingService {
        private PricingStrategy strategy; // depends on the interface, never a concrete class

        public OrderPricingService(PricingStrategy strategy) {
            this.strategy = strategy;
        }

        // Strategy can be swapped at any point — even mid-session
        // This enables runtime behavior change without recreating the service
        public void setStrategy(PricingStrategy strategy) {
            System.out.println("  [PricingService] Strategy changed → " + strategy.getStrategyName());
            this.strategy = strategy;
        }

        public double calculateOrderPrice(String productName, double basePrice, int quantity) {
            System.out.printf("%n  [PricingService] Product: %-20s | Base: ₹%.2f × %d | Strategy: %s%n",
                    productName, basePrice, quantity, strategy.getStrategyName());

            // Pure delegation — context has zero pricing logic
            double finalPrice = strategy.calculatePrice(basePrice, quantity);

            System.out.printf("  [PricingService] ✓ Final price: ₹%.2f%n", finalPrice);
            return finalPrice;
        }
    }


    // =========================================================================
    // MAIN — demonstrates runtime strategy swapping and OCP compliance
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Strategy Pattern Demo ===");
        System.out.println("Same OrderPricingService — different strategies swapped at runtime.\n");

        OrderPricingService pricingService = new OrderPricingService(new RegularPricing());

        // ── Example 1: Regular pricing ────────────────────────────────────────
        System.out.println("─── Regular Pricing ─────────────────────────────────────────────");
        pricingService.calculateOrderPrice("Laptop", 80000, 1);


        // ── Example 2: Seasonal discount — swap strategy at runtime ──────────
        System.out.println("\n─── Seasonal Discount (20% off) ─────────────────────────────────");
        pricingService.setStrategy(new SeasonalDiscountPricing(20));
        pricingService.calculateOrderPrice("Laptop", 80000, 1);


        // ── Example 3: Loyalty points pricing ────────────────────────────────
        System.out.println("\n─── Loyalty Points Pricing (500 pts) ───────────────────────────");
        pricingService.setStrategy(new LoyaltyPointsPricing(500));
        pricingService.calculateOrderPrice("Phone", 30000, 1);


        // ── Example 4: Bulk pricing — below threshold ────────────────────────
        System.out.println("\n─── Bulk Pricing (below threshold) ──────────────────────────────");
        pricingService.setStrategy(new BulkPricing());
        pricingService.calculateOrderPrice("Pen",   50, 5);   // qty 5 < 10 — no bulk discount


        // ── Example 5: Bulk pricing — above threshold ────────────────────────
        System.out.println("\n─── Bulk Pricing (above threshold) ─────────────────────────────");
        pricingService.calculateOrderPrice("Pen",   50, 15);  // qty 15 ≥ 10 — bulk applies


        // ── Example 6: Flash sale — added without changing any existing code ──
        System.out.println("\n─── Flash Sale (₹5000 off per item) ─────────────────────────────");
        pricingService.setStrategy(new FlashSalePricing(5000));
        pricingService.calculateOrderPrice("Headphones", 15000, 3);


        // ── Example 7: Multiple customers, different strategies simultaneously ─
        System.out.println("\n─── Multiple customers, different strategies ─────────────────────");
        PricingStrategy[] strategies = {
            new RegularPricing(),
            new SeasonalDiscountPricing(10),
            new LoyaltyPointsPricing(1000),
            new FlashSalePricing(200)
        };
        String[] customers = { "Regular Customer", "Sale Customer", "Loyal Customer", "Flash Customer" };
        double   basePrice = 2000.0;
        int      qty       = 2;

        // All use the same calculatePrice signature — completely interchangeable
        for (int i = 0; i < strategies.length; i++) {
            System.out.printf("  %-18s → ₹%.2f%n",
                    customers[i],
                    strategies[i].calculatePrice(basePrice, qty));
        }


        // ── Showing what WITHOUT Strategy looks like ──────────────────────────
        System.out.println("\n─── What the code looks like WITHOUT Strategy ─────────────────────");
        System.out.println("""
  public double calculatePrice(String type, double base, int qty) {
      if (type.equals("SEASONAL"))     { /* calc */ }
      else if (type.equals("LOYALTY")) { /* calc */ }
      else if (type.equals("BULK"))    { /* calc */ }
      else if (type.equals("FLASH"))   { /* calc */ }  ← adding this = modifying class
      // grows forever, violates OCP, hard to test in isolation
  }

  With Strategy: adding FLASH = just add FlashSalePricing class. Nothing else changes.""");


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Context (OrderPricingService) has ZERO pricing logic — pure delegation");
        System.out.println("  2. Adding a new strategy = new class only, no existing code touched");
        System.out.println("  3. Strategy can be swapped at runtime — setStrategy() at any point");
        System.out.println("  4. Each strategy is independently unit-testable");
        System.out.println("  5. Strategies are stateless or carry only their own config (discountPercent)");
    }
}

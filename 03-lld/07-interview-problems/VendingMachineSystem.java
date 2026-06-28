import java.util.*;

/**
 * Vending Machine System — Classic LLD Interview Problem #12.
 *
 * The final problem in the Phase 6 Classic LLD series.
 *
 * Core complexity this system captures:
 *   - A five-state machine (IDLE → MONEY_INSERTED → PRODUCT_SELECTED →
 *     DISPENSING → CHANGE_DISPENSED → IDLE) where each state has distinct
 *     behavior and invalid operations must be rejected cleanly.
 *   - Product selection involves a simultaneous dual-validation: is the
 *     slot in stock AND has enough money been inserted? Either failure has
 *     a different recovery path (refund vs "add more money").
 *   - Change dispensing is mandatory and exact. Unlike an ATM that can
 *     refuse a withdrawal if it cannot make the amount, a vending machine
 *     must refuse the *sale* upfront if it cannot return exact change.
 *   - Two independent Strategy axes: PaymentStrategy (how money is
 *     accepted and validated) and ChangeDispensingStrategy (how coins
 *     are allocated for change). Neither couples to the state machine.
 *
 * Patterns used:
 *   - State    -> VendingState interface + 5 concrete state classes
 *                 (same GoF class-per-state rationale as Problem 11)
 *   - Strategy -> PaymentStrategy (2 impls: coin, cash)
 *   - Strategy -> ChangeDispensingStrategy (2 impls: greedy, DP exact)
 *   - Factory  -> ProductFactory (creates products by category)
 *
 * Key design distinction from ATM (Problem 11):
 *   - The ATM's State pattern gates PIN/card mechanics;
 *     the Vending Machine's gates *money + inventory* mechanics.
 *   - Change dispensing is mandatory here; cash dispensing in the ATM
 *     was the goal of the transaction. Same bounded coin-change algorithm,
 *     opposite role.
 *   - ProductCatalog / VendingSlot mirrors the Book / BookCopy split from
 *     Problem 9: catalog identity vs physical inventory with a count.
 */
public class VendingMachineSystem {

    // =========================================================
    // Exceptions
    // =========================================================

    public static class InvalidOperationException extends RuntimeException {
        public InvalidOperationException(String msg) { super(msg); }
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String msg) { super(msg); }
    }

    public static class OutOfStockException extends RuntimeException {
        public OutOfStockException(String msg) { super(msg); }
    }

    public static class ExactChangeUnavailableException extends RuntimeException {
        public ExactChangeUnavailableException(String msg) { super(msg); }
    }

    public static class InvalidCoinException extends RuntimeException {
        public InvalidCoinException(String msg) { super(msg); }
    }

    // =========================================================
    // Coin enum
    // =========================================================

    public enum Coin {
        TWO_DOLLARS(200), ONE_DOLLAR(100), FIFTY_CENTS(50), QUARTER(25), DIME(10), NICKEL(5);

        private final int valueCents;

        Coin(int valueCents) { this.valueCents = valueCents; }

        public int getValueCents() { return valueCents; }

        public static Coin[] descending() {
            return new Coin[]{TWO_DOLLARS, ONE_DOLLAR, FIFTY_CENTS, QUARTER, DIME, NICKEL};
        }

        public static Optional<Coin> fromCents(int cents) {
            for (Coin c : values()) {
                if (c.valueCents == cents) return Optional.of(c);
            }
            return Optional.empty();
        }
    }

    // =========================================================
    // ProductCategory
    // =========================================================

    public enum ProductCategory { SNACK, DRINK, CANDY }

    // =========================================================
    // Product — catalog entry
    // =========================================================

    public static class Product {
        private final String productId;
        private final String name;
        private final int priceCents;
        private final ProductCategory category;

        public Product(String productId, String name, int priceCents, ProductCategory category) {
            if (priceCents <= 0) throw new IllegalArgumentException("Price must be positive");
            this.productId = productId;
            this.name = name;
            this.priceCents = priceCents;
            this.category = category;
        }

        public String getProductId() { return productId; }
        public String getName() { return name; }
        public int getPriceCents() { return priceCents; }
        public ProductCategory getCategory() { return category; }

        @Override public String toString() {
            return name + " ($" + String.format("%.2f", priceCents / 100.0) + ")";
        }
    }

    // =========================================================
    // VendingSlot — physical slot with inventory count
    // =========================================================

    public static class VendingSlot {
        private final String slotCode;
        private final Product product;
        private int quantity;

        public VendingSlot(String slotCode, Product product, int initialQuantity) {
            this.slotCode = slotCode;
            this.product = product;
            this.quantity = initialQuantity;
        }

        public boolean isInStock() { return quantity > 0; }

        /** Decrements count; throws OutOfStockException if already empty. */
        public void dispense() {
            if (quantity <= 0) throw new OutOfStockException(
                    "Slot " + slotCode + " (" + product.getName() + ") is out of stock");
            quantity--;
        }

        public void restock(int count) { quantity += count; }

        public String getSlotCode() { return slotCode; }
        public Product getProduct() { return product; }
        public int getQuantity() { return quantity; }
    }

    // =========================================================
    // ProductCatalog
    // =========================================================

    public static class ProductCatalog {
        private final Map<String, VendingSlot> slots = new LinkedHashMap<>();

        public void addSlot(VendingSlot slot) {
            slots.put(slot.getSlotCode(), slot);
        }

        public Optional<VendingSlot> findSlot(String slotCode) {
            return Optional.ofNullable(slots.get(slotCode.toUpperCase()));
        }

        public void printInventory() {
            System.out.println("  Slot  Product                   Price    Qty");
            System.out.println("  ----  ------------------------  -------  ---");
            for (VendingSlot s : slots.values()) {
                System.out.printf("  %-5s %-24s $%-6.2f  %d%n",
                        s.getSlotCode(), s.getProduct().getName(),
                        s.getProduct().getPriceCents() / 100.0, s.getQuantity());
            }
        }
    }

    // =========================================================
    // ProductFactory
    // =========================================================

    /**
     * Centralises product construction. Clients specify category and
     * properties; the factory returns a fully configured Product.
     * Naming conventions and default values (e.g. category-specific
     * id prefixes) live here rather than scattered across callers.
     */
    public static class ProductFactory {
        private static int sequence = 1;

        public static Product create(String name, int priceCents, ProductCategory category) {
            String prefix;
            switch (category) {
                case DRINK: prefix = "DR"; break;
                case CANDY: prefix = "CA"; break;
                default:    prefix = "SN"; break;
            }
            return new Product(prefix + String.format("%03d", sequence++), name, priceCents, category);
        }
    }

    // =========================================================
    // CoinBin — the machine's coin float
    // =========================================================

    public static class CoinBin {
        private final Map<Coin, Integer> counts = new EnumMap<>(Coin.class);

        public synchronized void add(Coin coin, int count) {
            counts.merge(coin, count, Integer::sum);
        }

        public synchronized void add(Map<Coin, Integer> coins) {
            coins.forEach((c, n) -> counts.merge(c, n, Integer::sum));
        }

        public synchronized Map<Coin, Integer> getAvailableCounts() {
            return Collections.unmodifiableMap(new EnumMap<>(counts));
        }

        public synchronized void remove(Map<Coin, Integer> coins) {
            for (Map.Entry<Coin, Integer> entry : coins.entrySet()) {
                int have = counts.getOrDefault(entry.getKey(), 0);
                if (have < entry.getValue()) {
                    throw new ExactChangeUnavailableException(
                            "Coin bin short on " + entry.getKey() + ": have " + have
                                    + " need " + entry.getValue());
                }
            }
            coins.forEach((c, n) -> counts.merge(c, -n, Integer::sum));
        }

        public synchronized int getTotalCents() {
            return counts.entrySet().stream().mapToInt(e -> e.getKey().getValueCents() * e.getValue()).sum();
        }
    }

    // =========================================================
    // VendingState interface — State pattern
    // =========================================================

    /**
     * Default methods throw InvalidOperationException for every operation.
     * Concrete states only override the operations valid in their state —
     * the same pattern used in Problem 11's ATMState interface.
     *
     * dispenseProduct is the internal auto-transition trigger fired once a
     * product selection has been validated and committed (PRODUCT_SELECTED).
     * It is part of the interface (with a throwing default) so it can be
     * invoked polymorphically via VendingState without downcasting.
     */
    public interface VendingState {
        default void insertMoney(VendingMachine vm, int cents) {
            throw new InvalidOperationException("Cannot insert money in " + getStateName() + " state");
        }
        default void selectProduct(VendingMachine vm, String slotCode) {
            throw new InvalidOperationException("Cannot select product in " + getStateName() + " state");
        }
        default void refund(VendingMachine vm) {
            throw new InvalidOperationException("Cannot refund in " + getStateName() + " state");
        }
        default void dispenseProduct(VendingMachine vm) {
            throw new InvalidOperationException("Cannot dispense product in " + getStateName() + " state");
        }
        String getStateName();
    }

    // =========================================================
    // Concrete states
    // =========================================================

    /** IDLE — machine is empty-handed. Only money insertion is valid. */
    public static class IdleState implements VendingState {
        @Override
        public void insertMoney(VendingMachine vm, int cents) {
            vm.getPaymentStrategy().acceptMoney(cents);
            vm.addInsertedCents(cents);
            // Also add the inserted coins to the machine's float so they're
            // available for future change-making — inserted money becomes inventory.
            Optional<Coin> coin = Coin.fromCents(cents);
            coin.ifPresent(c -> vm.getCoinBin().add(c, 1));
            System.out.printf("  Inserted: %dc  |  Total: %dc%n", cents, vm.getInsertedCents());
            vm.setState(new MoneyInsertedState());
        }

        @Override
        public void refund(VendingMachine vm) { /* already idle, nothing to refund */ }

        @Override public String getStateName() { return "IDLE"; }
    }

    /**
     * MONEY_INSERTED — some amount has been entered.
     * More money can be added, a product can be selected, or a refund requested.
     */
    public static class MoneyInsertedState implements VendingState {
        @Override
        public void insertMoney(VendingMachine vm, int cents) {
            vm.getPaymentStrategy().acceptMoney(cents);
            vm.addInsertedCents(cents);
            Optional<Coin> coin = Coin.fromCents(cents);
            coin.ifPresent(c -> vm.getCoinBin().add(c, 1));
            System.out.printf("  Inserted: %dc  |  Total: %dc%n", cents, vm.getInsertedCents());
        }

        @Override
        public void selectProduct(VendingMachine vm, String slotCode) {
            VendingSlot slot = vm.getCatalog().findSlot(slotCode)
                    .orElseThrow(() -> new InvalidOperationException("No slot: " + slotCode));

            if (!slot.isInStock()) {
                throw new OutOfStockException(slot.getProduct().getName() + " is out of stock");
            }

            int price = slot.getProduct().getPriceCents();
            int inserted = vm.getInsertedCents();

            if (inserted < price) {
                throw new InsufficientFundsException(
                        "Need " + price + "c, have " + inserted + "c — insert " + (price - inserted) + "c more");
            }

            int changeDue = inserted - price;

            // Check change feasibility BEFORE dispensing product.
            // A vending machine must refuse the sale if it cannot make exact change —
            // unlike an ATM that can refuse a withdrawal after the fact.
            if (changeDue > 0) {
                // Dry-run the change calculation. If it throws, abort the sale.
                // The inserted coins are already in the bin; we must exclude them
                // from "machine's own float" conceptually, but here we treat the
                // whole bin (which already includes them) as available.
                vm.getChangeStrategy().calculateChange(changeDue, vm.getCoinBin().getAvailableCounts());
            }

            vm.setSelectedSlot(slot);
            System.out.printf("  Selected: %s — price %dc, change due %dc%n",
                    slot.getProduct().getName(), price, changeDue);
            vm.setState(new ProductSelectedState());
            // Auto-advance to dispensing
            vm.getCurrentState().dispenseProduct(vm);
        }

        @Override
        public void refund(VendingMachine vm) {
            int total = vm.getInsertedCents();
            System.out.println("  Refunding " + total + "c");
            // Return inserted coins from the bin (best-effort with greedy)
            if (total > 0) {
                try {
                    Map<Coin, Integer> refundCoins =
                            new GreedyChangeStrategy().calculateChange(total, vm.getCoinBin().getAvailableCounts());
                    vm.getCoinBin().remove(refundCoins);
                    printCoins("  Refund:", refundCoins);
                } catch (ExactChangeUnavailableException e) {
                    System.out.println("  [warn] Exact refund not possible, returning what's available");
                }
            }
            vm.resetSession();
            vm.setState(new IdleState());
        }

        @Override public String getStateName() { return "MONEY_INSERTED"; }
    }

    /**
     * PRODUCT_SELECTED — product validated and committed.
     * Auto-transitions through DISPENSING and CHANGE_DISPENSED back to IDLE.
     */
    public static class ProductSelectedState implements VendingState {
        @Override
        public void dispenseProduct(VendingMachine vm) {
            VendingSlot slot = vm.getSelectedSlot();
            slot.dispense();
            System.out.println("  ✓ Dispensing: " + slot.getProduct().getName());
            vm.setState(new DispensingState());

            int changeDue = vm.getInsertedCents() - slot.getProduct().getPriceCents();
            if (changeDue > 0) {
                Map<Coin, Integer> change = vm.getChangeStrategy()
                        .calculateChange(changeDue, vm.getCoinBin().getAvailableCounts());
                vm.getCoinBin().remove(change);
                vm.setState(new ChangeDispensedState());
                printCoins("  ✓ Change:", change);
            } else {
                System.out.println("  ✓ Exact payment — no change");
            }

            vm.resetSession();
            vm.setState(new IdleState());
        }

        @Override public void refund(VendingMachine vm) {
            // Product already committed — refund not meaningful
            throw new InvalidOperationException("Product already committed; cannot refund");
        }

        @Override public String getStateName() { return "PRODUCT_SELECTED"; }
    }

    /** DISPENSING — product is being physically vended. No user input. */
    public static class DispensingState implements VendingState {
        @Override public String getStateName() { return "DISPENSING"; }
    }

    /** CHANGE_DISPENSED — coins are being returned. No user input. */
    public static class ChangeDispensedState implements VendingState {
        @Override public String getStateName() { return "CHANGE_DISPENSED"; }
    }

    // =========================================================
    // PaymentStrategy — Strategy pattern (axis 1)
    // =========================================================

    public interface PaymentStrategy {
        /**
         * Validates an insertion attempt. Throws InvalidCoinException if the
         * amount is unacceptable (e.g. not a recognised coin denomination).
         */
        void acceptMoney(int cents);
        String getDescription();
    }

    /**
     * Only accepts insertions that match a known Coin denomination.
     * Rejects arbitrary amounts — models a physical coin acceptor.
     */
    public static class CoinPaymentStrategy implements PaymentStrategy {
        private static final Set<Integer> VALID_CENTS;
        static {
            VALID_CENTS = new HashSet<>();
            for (Coin c : Coin.values()) VALID_CENTS.add(c.getValueCents());
        }

        @Override
        public void acceptMoney(int cents) {
            if (!VALID_CENTS.contains(cents)) {
                throw new InvalidCoinException(
                        cents + "c is not a valid coin — valid: 5, 10, 25, 50, 100, 200");
            }
        }

        @Override public String getDescription() { return "Coin acceptor (5¢ – $2)"; }
    }

    /**
     * Accepts any positive amount — models a bill validator or stored-value card.
     * Lets users insert e.g. $5 as 500 cents without denomination constraint.
     */
    public static class CashPaymentStrategy implements PaymentStrategy {
        @Override
        public void acceptMoney(int cents) {
            if (cents <= 0) throw new InvalidCoinException("Amount must be positive");
        }

        @Override public String getDescription() { return "Cash / card (any positive amount)"; }
    }

    // =========================================================
    // ChangeDispensingStrategy — Strategy pattern (axis 2)
    // =========================================================

    public interface ChangeDispensingStrategy {
        /**
         * Calculates the coin breakdown to return {@code amountCents} in change.
         * Receives a read-only snapshot of available coin counts.
         * Throws ExactChangeUnavailableException if exact change cannot be made.
         */
        Map<Coin, Integer> calculateChange(int amountCents, Map<Coin, Integer> available);
    }

    /** Greedy: largest coin first. Simple, O(D). Optimal for standard coin sets. */
    public static class GreedyChangeStrategy implements ChangeDispensingStrategy {
        @Override
        public Map<Coin, Integer> calculateChange(int amountCents, Map<Coin, Integer> available) {
            Map<Coin, Integer> result = new EnumMap<>(Coin.class);
            int remaining = amountCents;
            for (Coin coin : Coin.descending()) {
                int canUse = Math.min(remaining / coin.getValueCents(), available.getOrDefault(coin, 0));
                if (canUse > 0) {
                    result.put(coin, canUse);
                    remaining -= canUse * coin.getValueCents();
                }
            }
            if (remaining != 0) {
                throw new ExactChangeUnavailableException(
                        "Greedy cannot make exact change for " + amountCents + "c");
            }
            return result;
        }
    }

    /**
     * Exact-change DP: bounded 0-1 knapsack — same algorithm as Problem 11's
     * MinNotesDispensingStrategy, applied to coins instead of bills.
     *
     * Each physical coin in the bin is treated as a separate 0-1 item.
     * A backward scan per coin enforces supply constraints: a denomination
     * with only 2 coins in the bin cannot be used more than twice.
     *
     * Produces the minimum number of coins for the change amount.
     */
    public static class ExactChangeStrategy implements ChangeDispensingStrategy {
        @Override
        public Map<Coin, Integer> calculateChange(int amountCents, Map<Coin, Integer> available) {
            final int INF = Integer.MAX_VALUE / 2;
            int[] dp = new int[amountCents + 1];
            Coin[] from = new Coin[amountCents + 1];
            Arrays.fill(dp, INF);
            dp[0] = 0;

            for (Coin coin : Coin.descending()) {
                int count = available.getOrDefault(coin, 0);
                int val = coin.getValueCents();
                if (val > amountCents || count == 0) continue;
                for (int k = 0; k < count; k++) {
                    for (int i = amountCents; i >= val; i--) {
                        if (dp[i - val] != INF && dp[i - val] + 1 < dp[i]) {
                            dp[i] = dp[i - val] + 1;
                            from[i] = coin;
                        }
                    }
                }
            }

            if (dp[amountCents] == INF) {
                throw new ExactChangeUnavailableException(
                        "Cannot make exact change for " + amountCents + "c with available coins");
            }

            Map<Coin, Integer> result = new EnumMap<>(Coin.class);
            int rem = amountCents;
            while (rem > 0) {
                Coin c = from[rem];
                result.merge(c, 1, Integer::sum);
                rem -= c.getValueCents();
            }
            return result;
        }
    }

    // =========================================================
    // VendingMachine — context (analogous to ATMContext)
    // =========================================================

    public static class VendingMachine {
        private VendingState currentState;
        private int insertedCents;
        private VendingSlot selectedSlot;
        private final ProductCatalog catalog;
        private final CoinBin coinBin;
        private final PaymentStrategy paymentStrategy;
        private final ChangeDispensingStrategy changeStrategy;

        public VendingMachine(ProductCatalog catalog, CoinBin coinBin,
                              PaymentStrategy paymentStrategy,
                              ChangeDispensingStrategy changeStrategy) {
            this.catalog = catalog;
            this.coinBin = coinBin;
            this.paymentStrategy = paymentStrategy;
            this.changeStrategy = changeStrategy;
            this.currentState = new IdleState();
        }

        // ── user-facing operations ──────────────────────────

        public synchronized void insertMoney(int cents) {
            System.out.println("[" + currentState.getStateName() + "] insertMoney(" + cents + "c)");
            currentState.insertMoney(this, cents);
        }

        public synchronized void selectProduct(String slotCode) {
            System.out.println("[" + currentState.getStateName() + "] selectProduct(" + slotCode + ")");
            currentState.selectProduct(this, slotCode);
        }

        public synchronized void refund() {
            System.out.println("[" + currentState.getStateName() + "] refund()");
            currentState.refund(this);
        }

        // ── state callbacks ─────────────────────────────────

        public void setState(VendingState state) {
            System.out.println("  → " + currentState.getStateName() + " → " + state.getStateName());
            this.currentState = state;
        }

        public void resetSession() {
            this.insertedCents = 0;
            this.selectedSlot = null;
        }

        // ── accessors used by states ────────────────────────

        public void addInsertedCents(int cents) { this.insertedCents += cents; }
        public int getInsertedCents() { return insertedCents; }
        public void setSelectedSlot(VendingSlot slot) { this.selectedSlot = slot; }
        public VendingSlot getSelectedSlot() { return selectedSlot; }
        public ProductCatalog getCatalog() { return catalog; }
        public CoinBin getCoinBin() { return coinBin; }
        public PaymentStrategy getPaymentStrategy() { return paymentStrategy; }
        public ChangeDispensingStrategy getChangeStrategy() { return changeStrategy; }
        public VendingState getCurrentState() { return currentState; }
        public String getCurrentStateName() { return currentState.getStateName(); }
    }

    // =========================================================
    // Utility
    // =========================================================

    static void printCoins(String label, Map<Coin, Integer> coins) {
        StringBuilder sb = new StringBuilder(label + " ");
        coins.forEach((c, n) -> sb.append(n).append("×").append(c.getValueCents()).append("¢ "));
        System.out.println(sb.toString().trim());
    }

    // =========================================================
    // Demo
    // =========================================================

    public static void main(String[] args) {
        ProductCatalog catalog = new ProductCatalog();
        catalog.addSlot(new VendingSlot("A1", ProductFactory.create("Lays Classic",    150, ProductCategory.SNACK), 5));
        catalog.addSlot(new VendingSlot("A2", ProductFactory.create("Doritos",         175, ProductCategory.SNACK), 3));
        catalog.addSlot(new VendingSlot("B1", ProductFactory.create("Coca-Cola",       200, ProductCategory.DRINK), 4));
        catalog.addSlot(new VendingSlot("B2", ProductFactory.create("Water Bottle",    125, ProductCategory.DRINK), 2));
        catalog.addSlot(new VendingSlot("C1", ProductFactory.create("Kit Kat",         100, ProductCategory.CANDY), 1));

        CoinBin coinBin = new CoinBin();
        coinBin.add(Coin.QUARTER, 10);
        coinBin.add(Coin.DIME, 10);
        coinBin.add(Coin.NICKEL, 10);
        coinBin.add(Coin.FIFTY_CENTS, 5);

        System.out.println("=== Inventory ===");
        catalog.printInventory();

        VendingMachine vm = new VendingMachine(catalog, coinBin,
                new CoinPaymentStrategy(), new GreedyChangeStrategy());

        System.out.println();
        System.out.println("=== Scenario 1: exact payment, no change ===");
        vm.insertMoney(100);
        vm.selectProduct("C1"); // Kit Kat at 100¢ — exact

        System.out.println();
        System.out.println("=== Scenario 2: overpayment with change ===");
        vm.insertMoney(100);
        vm.insertMoney(100);
        vm.selectProduct("A1"); // Lays at 150¢, inserted 200¢ → 50¢ change

        System.out.println();
        System.out.println("=== Scenario 3: insufficient funds ===");
        vm.insertMoney(100);
        try {
            vm.selectProduct("B1"); // Coke at 200¢, only 100¢ inserted
        } catch (InsufficientFundsException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }
        vm.insertMoney(100); // add more — now 200¢ total
        vm.selectProduct("B1"); // exact

        System.out.println();
        System.out.println("=== Scenario 4: out of stock ===");
        vm.insertMoney(100);
        try {
            vm.selectProduct("C1"); // Kit Kat — only 1 in stock, already sold in scenario 1
        } catch (OutOfStockException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }
        vm.refund();

        System.out.println();
        System.out.println("=== Scenario 5: refund mid-session ===");
        vm.insertMoney(25);
        vm.insertMoney(25);
        vm.refund(); // machine returns 50¢

        System.out.println();
        System.out.println("=== Scenario 6: invalid coin (coin payment strategy) ===");
        try {
            vm.insertMoney(3); // 3¢ is not a real coin
        } catch (InvalidCoinException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Scenario 7: invalid operation in IDLE state ===");
        try {
            vm.selectProduct("A1"); // no money inserted
        } catch (InvalidOperationException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Scenario 8: ExactChangeStrategy (DP) with a non-greedy case ===");
        // Build a machine that uses DP-based change strategy and has a coin mix
        // that forces the DP to find a non-obvious solution.
        CoinBin specialBin = new CoinBin();
        specialBin.add(Coin.QUARTER, 0);   // no quarters
        specialBin.add(Coin.DIME, 3);      // 3 dimes
        specialBin.add(Coin.NICKEL, 2);    // 2 nickels
        specialBin.add(Coin.ONE_DOLLAR, 5);

        ProductCatalog smallCatalog = new ProductCatalog();
        smallCatalog.addSlot(new VendingSlot("X1",
                ProductFactory.create("Gum", 75, ProductCategory.CANDY), 3));

        VendingMachine vm2 = new VendingMachine(smallCatalog, specialBin,
                new CashPaymentStrategy(), new ExactChangeStrategy());
        vm2.insertMoney(100);
        vm2.selectProduct("X1"); // Gum at 75¢, inserted 100¢ → 25¢ change via dime+dime+nickel

        System.out.println();
        System.out.println("=== Scenario 9: exact change unavailable → sale refused ===");
        CoinBin emptyBin = new CoinBin(); // machine has no coins for change
        ProductCatalog c2 = new ProductCatalog();
        c2.addSlot(new VendingSlot("Y1",
                ProductFactory.create("Chips", 150, ProductCategory.SNACK), 5));
        VendingMachine vm3 = new VendingMachine(c2, emptyBin,
                new CashPaymentStrategy(), new GreedyChangeStrategy());
        vm3.insertMoney(200); // needs 50¢ change but bin is empty
        try {
            vm3.selectProduct("Y1");
        } catch (ExactChangeUnavailableException e) {
            System.out.println("  Sale refused (exact change unavailable): " + e.getMessage());
        }
        vm3.refund();
    }
}
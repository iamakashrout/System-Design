import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// =============================================================================
// PATTERN: Observer
// PURPOSE: Define a one-to-many dependency so that when one object changes
//          state, all its dependents are notified and updated automatically.
//
// REAL-WORLD ANALOGY:
//   A newspaper subscription. The newspaper (publisher) doesn't know who
//   specifically reads it. Subscribers sign up and get notified when a new
//   edition is published. They can subscribe or unsubscribe at any time.
//   The newspaper just publishes — it doesn't track what each reader does.
//   This is the publish-subscribe model. Observer is its OOP implementation.
//
// THE PROBLEM THIS SOLVES:
//   Without Observer, the Stock class would hold direct references:
//     private TradingAlgorithm algo;
//     private PriceAlertService alertService;
//     private AuditLogger logger;
//   Adding a new consumer = modifying the Stock class. That's tight coupling.
//   The Stock class should NOT need to know who is interested in it.
//
// FOUR INGREDIENTS:
//   1. Observer interface  → contract observers implement to receive updates
//   2. Subject interface   → add/remove/notify observers
//   3. Concrete subject    → holds observer list, notifies all on state change
//   4. Concrete observers  → each reacts differently to the same notification
// =============================================================================

public class ObserverPattern {

    // =========================================================================
    // STEP 1: OBSERVER INTERFACE
    // All interested parties must implement this single method.
    // The subject calls this on every registered observer when state changes.
    // =========================================================================
    interface StockObserver {
        // Called by the subject whenever price changes
        // Receives both old and new price so observers can calculate change %
        void onPriceChanged(String ticker, double oldPrice, double newPrice);

        String getObserverName(); // for display purposes
    }


    // =========================================================================
    // STEP 2: SUBJECT INTERFACE
    // Any class that can be "watched" should implement this.
    // Separating it into an interface means multiple subjects can exist.
    // =========================================================================
    interface StockSubject {
        void addObserver(StockObserver observer);
        void removeObserver(StockObserver observer);
        void notifyObservers(double oldPrice, double newPrice);
    }


    // =========================================================================
    // STEP 3: CONCRETE SUBJECT — Stock
    //
    // The Stock class knows its price and a list of observers.
    // It knows NOTHING about what its observers do — it just broadcasts.
    //
    // CRITICAL: Stock never imports TradingAlgorithm, PriceAlertService,
    // or AuditLogger. It only knows StockObserver (the interface).
    // That's the decoupling.
    // =========================================================================
    static class Stock implements StockSubject {
        private final String ticker;
        private double currentPrice;
        private final double initialPrice;
        private final List<StockObserver> observers = new ArrayList<>();

        public Stock(String ticker, double initialPrice) {
            this.ticker       = ticker;
            this.currentPrice = initialPrice;
            this.initialPrice = initialPrice;
        }

        // ── Observer management ───────────────────────────────────────────────

        @Override
        public void addObserver(StockObserver observer) {
            observers.add(observer);
            System.out.println("  [Stock:" + ticker + "] Subscribed: "
                    + observer.getObserverName());
        }

        @Override
        public void removeObserver(StockObserver observer) {
            observers.remove(observer);
            System.out.println("  [Stock:" + ticker + "] Unsubscribed: "
                    + observer.getObserverName());
        }

        @Override
        public void notifyObservers(double oldPrice, double newPrice) {
            // Stock doesn't know what the observers will do — just broadcasts to all
            for (StockObserver observer : observers) {
                observer.onPriceChanged(ticker, oldPrice, newPrice);
            }
        }

        // ── State change — this triggers notifications ────────────────────────
        public void updatePrice(double newPrice) {
            if (newPrice <= 0)
                throw new IllegalArgumentException("Price must be positive");

            double oldPrice   = this.currentPrice;
            this.currentPrice = newPrice;

            double changePercent = ((newPrice - oldPrice) / oldPrice) * 100;
            System.out.printf("%n[Stock] %s price updated: ₹%.2f → ₹%.2f (%+.2f%%)%n",
                    ticker, oldPrice, newPrice, changePercent);

            // Notify ALL registered observers — subject doesn't filter or choose
            notifyObservers(oldPrice, newPrice);
        }

        public String getTicker()       { return ticker; }
        public double getCurrentPrice() { return currentPrice; }
        public int getObserverCount()   { return observers.size(); }
    }


    // =========================================================================
    // STEP 4: CONCRETE OBSERVERS
    // Each observer reacts to the SAME notification in a completely different way.
    // They are independent — adding or removing one doesn't affect the others.
    // =========================================================================

    // ── Observer 1: Trading Algorithm ─────────────────────────────────────────
    // Compares price against buy/sell thresholds and signals accordingly
    static class TradingAlgorithm implements StockObserver {
        private final String algoName;
        private final double buyBelowPrice;   // generate BUY signal if price drops below this
        private final double sellAbovePrice;  // generate SELL signal if price rises above this
        private int buyCount  = 0;
        private int sellCount = 0;

        public TradingAlgorithm(String name, double buyBelow, double sellAbove) {
            this.algoName      = name;
            this.buyBelowPrice = buyBelow;
            this.sellAbovePrice = sellAbove;
        }

        @Override
        public void onPriceChanged(String ticker, double oldPrice, double newPrice) {
            if (newPrice < buyBelowPrice) {
                buyCount++;
                System.out.printf("  [%s] 🟢 BUY signal | %s @ ₹%.2f (below threshold ₹%.2f)%n",
                        algoName, ticker, newPrice, buyBelowPrice);
            } else if (newPrice > sellAbovePrice) {
                sellCount++;
                System.out.printf("  [%s] 🔴 SELL signal | %s @ ₹%.2f (above threshold ₹%.2f)%n",
                        algoName, ticker, newPrice, sellAbovePrice);
            } else {
                System.out.printf("  [%s] ⚪ HOLD | %s @ ₹%.2f (within range ₹%.2f–₹%.2f)%n",
                        algoName, ticker, newPrice, buyBelowPrice, sellAbovePrice);
            }
        }

        @Override
        public String getObserverName() { return algoName; }

        public void printSummary() {
            System.out.printf("  [%s] Summary → BUY signals: %d | SELL signals: %d%n",
                    algoName, buyCount, sellCount);
        }
    }


    // ── Observer 2: Price Alert Service ───────────────────────────────────────
    // Notifies a user when the price crosses their configured threshold
    static class PriceAlertService implements StockObserver {
        private final String userId;
        private final double alertThreshold;
        private final String alertType; // "ABOVE" or "BELOW"
        private int alertCount = 0;

        public PriceAlertService(String userId, double threshold, String alertType) {
            if (!alertType.equals("ABOVE") && !alertType.equals("BELOW"))
                throw new IllegalArgumentException("alertType must be ABOVE or BELOW");
            this.userId         = userId;
            this.alertThreshold = threshold;
            this.alertType      = alertType;
        }

        @Override
        public void onPriceChanged(String ticker, double oldPrice, double newPrice) {
            boolean triggered = alertType.equals("ABOVE")
                    ? newPrice > alertThreshold
                    : newPrice < alertThreshold;

            if (triggered) {
                alertCount++;
                System.out.printf("  [AlertService] 📱 SMS → %s: %s is %s ₹%.2f | Current: ₹%.2f%n",
                        userId, ticker, alertType, alertThreshold, newPrice);
            }
        }

        @Override
        public String getObserverName() { return "PriceAlert(" + userId + ")"; }

        public int getAlertCount() { return alertCount; }
    }


    // ── Observer 3: Audit Logger ───────────────────────────────────────────────
    // Records every price change with timestamp and percentage — never skips any
    static class AuditLogger implements StockObserver {
        private final List<String> log = new ArrayList<>();
        private static final DateTimeFormatter FMT =
                DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        @Override
        public void onPriceChanged(String ticker, double oldPrice, double newPrice) {
            double changePct = ((newPrice - oldPrice) / oldPrice) * 100;
            String direction = changePct >= 0 ? "▲" : "▼";
            String entry = String.format("[%s] %s | ₹%.2f → ₹%.2f | %s %+.2f%%",
                    LocalDateTime.now().format(FMT),
                    ticker, oldPrice, newPrice, direction, changePct);
            log.add(entry);
            System.out.println("  [AuditLogger] 📋 " + entry);
        }

        @Override
        public String getObserverName() { return "AuditLogger"; }

        public void printFullLog() {
            System.out.println("\n  ── Full Audit Log (" + log.size() + " entries) ──");
            log.forEach(e -> System.out.println("  " + e));
        }

        public int getEntryCount() { return log.size(); }
    }


    // ── Observer 4: Portfolio Tracker ─────────────────────────────────────────
    // Tracks P&L for a user who holds shares — added later, nothing else changed
    static class PortfolioTracker implements StockObserver {
        private final String investorName;
        private final int sharesHeld;
        private final double purchasePrice;

        public PortfolioTracker(String name, int shares, double purchasePrice) {
            this.investorName  = name;
            this.sharesHeld    = shares;
            this.purchasePrice = purchasePrice;
        }

        @Override
        public void onPriceChanged(String ticker, double oldPrice, double newPrice) {
            double investedValue  = sharesHeld * purchasePrice;
            double currentValue   = sharesHeld * newPrice;
            double pnl            = currentValue - investedValue;
            double pnlPercent     = (pnl / investedValue) * 100;
            String pnlEmoji       = pnl >= 0 ? "💰" : "📉";
            System.out.printf("  [Portfolio:%s] %s %s: %d shares | Invested: ₹%.0f | Current: ₹%.0f | P&L: %+.0f (%.1f%%)%n",
                    investorName, pnlEmoji, ticker, sharesHeld,
                    investedValue, currentValue, pnl, pnlPercent);
        }

        @Override
        public String getObserverName() { return "Portfolio(" + investorName + ")"; }
    }


    // =========================================================================
    // MAIN — demonstrates subscribe, notify, and unsubscribe lifecycle
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Observer Pattern Demo ===\n");

        // Create the subject
        Stock infosys = new Stock("INFY", 1500.0);

        // Create observers
        TradingAlgorithm  algo      = new TradingAlgorithm("MomentumAlgo", 1400.0, 1600.0);
        PriceAlertService alert     = new PriceAlertService("akash@arcesium.com", 1450.0, "BELOW");
        AuditLogger       auditor   = new AuditLogger();
        PortfolioTracker  portfolio = new PortfolioTracker("Akash", 50, 1450.0);

        // Subscribe observers — subject doesn't know what type they are
        System.out.println("─── Registering Observers ───────────────────────────────────────");
        infosys.addObserver(algo);
        infosys.addObserver(alert);
        infosys.addObserver(auditor);
        infosys.addObserver(portfolio);
        System.out.println("  Total subscribers: " + infosys.getObserverCount());


        // ── Price updates — all 4 observers notified on each change ───────────
        System.out.println("\n─── Price Updates ───────────────────────────────────────────────");

        infosys.updatePrice(1550.0);  // within algo range → HOLD; above alert threshold → no alert

        infosys.updatePrice(1430.0);  // below algo buy threshold → BUY
                                       // below alert threshold → SMS fired

        infosys.updatePrice(1650.0);  // above algo sell threshold → SELL

        infosys.updatePrice(1480.0);  // back within range → HOLD


        // ── Dynamic unsubscription ────────────────────────────────────────────
        System.out.println("\n─── Unsubscribing TradingAlgorithm ──────────────────────────────");
        infosys.removeObserver(algo);
        System.out.println("  Remaining subscribers: " + infosys.getObserverCount());

        // Next update — only alert, auditor, portfolio are notified
        infosys.updatePrice(1380.0);  // algo is gone — won't see this


        // ── Adding a new observer mid-stream ──────────────────────────────────
        System.out.println("\n─── Adding New Observer Mid-Stream ──────────────────────────────");
        PriceAlertService alert2 = new PriceAlertService("manager@arcesium.com", 1400.0, "BELOW");
        infosys.addObserver(alert2);
        System.out.println("  Subscribers now: " + infosys.getObserverCount());

        infosys.updatePrice(1350.0);  // both alert + alert2 fire; portfolio shows loss


        // ── Results and audit log ─────────────────────────────────────────────
        System.out.println("\n─── Summary ─────────────────────────────────────────────────────");
        algo.printSummary();
        System.out.println("  Alerts sent to akash: " + alert.getAlertCount());
        System.out.println("  Audit log entries: " + auditor.getEntryCount());
        auditor.printFullLog();


        // ── Demonstrating: subject doesn't care what observers do ─────────────
        System.out.println("\n─── Key Proof: Subject Decoupling ───────────────────────────────");
        System.out.println("  Stock.java has zero imports of TradingAlgorithm,");
        System.out.println("  PriceAlertService, AuditLogger, or PortfolioTracker.");
        System.out.println("  It only knows StockObserver (the interface).");
        System.out.println("  Adding a new observer type = zero changes to Stock.");


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Subject (Stock) never imports any concrete observer class");
        System.out.println("  2. Each observer reacts differently to the same notification");
        System.out.println("  3. Observers can subscribe/unsubscribe at any time dynamically");
        System.out.println("  4. Adding a new observer type = new class only, Stock unchanged");
        System.out.println("  5. Always removeObserver() when done — forgotten observers leak memory");
    }
}

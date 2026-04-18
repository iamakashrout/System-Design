import java.util.ArrayList;
import java.util.List;

// =============================================================================
// PATTERN: Chain of Responsibility
// PURPOSE: Pass a request along a chain of handlers. Each handler decides
//          to process the request or pass it to the next handler.
//          The sender doesn't know which handler will ultimately process it.
//
// REAL-WORLD ANALOGY:
//   Customer support escalation. You call support. Level 1 tries to resolve.
//   Can't? Escalates to Level 2. Still can't? Specialist. You don't know
//   (or care) who ultimately resolves it — you just submit the request and
//   the chain handles routing. Each level either handles or passes up.
//
// THE PROBLEM THIS SOLVES:
//   Without Chain of Responsibility:
//     if (amount <= 10000)        { manager.approve(request); }
//     else if (amount <= 50000)   { director.approve(request); }
//     else if (amount <= 200000)  { vp.approve(request); }
//     else                        { board.approve(request); }
//   This lives in the calling code. Adding a new level = modifying the caller.
//   The caller also needs to know about ALL handler types.
//
//   With Chain: the caller just calls manager.handle(request).
//   The chain routes itself. The caller knows only the first handler.
//
// FOUR INGREDIENTS:
//   1. Handler interface    → handle(request) and setNext(handler)
//   2. Abstract handler     → stores next reference, provides passToNext()
//   3. Concrete handlers    → process if possible, otherwise passToNext()
//   4. Client               → sends to first handler, unaware of the rest
// =============================================================================

public class ChainOfResponsibilityPattern {

    // =========================================================================
    // REQUEST OBJECTS
    // Encapsulates all the data a handler might need to make its decision.
    // =========================================================================
    static class ExpenseRequest {
        private final String employeeName;
        private final String employeeLevel; // "JUNIOR", "SENIOR", "LEAD"
        private final double amount;
        private final String purpose;
        private final String category;      // "TRAVEL", "EQUIPMENT", "TRAINING", "OTHER"

        public ExpenseRequest(String employee, String level, double amount,
                              String purpose, String category) {
            this.employeeName  = employee;
            this.employeeLevel = level;
            this.amount        = amount;
            this.purpose       = purpose;
            this.category      = category;
        }

        public String getEmployeeName()  { return employeeName; }
        public String getEmployeeLevel() { return employeeLevel; }
        public double getAmount()        { return amount; }
        public String getPurpose()       { return purpose; }
        public String getCategory()      { return category; }

        @Override
        public String toString() {
            return String.format("%-10s (%-6s) | ₹%7.0f | %-10s | %s",
                    employeeName, employeeLevel, amount, category, purpose);
        }
    }


    // =========================================================================
    // STEP 1: HANDLER INTERFACE
    // Every handler in the chain must implement this.
    // setNext()  → links this handler to the next one in the chain
    // handle()   → process the request or pass it along
    // =========================================================================
    interface ExpenseHandler {
        void setNext(ExpenseHandler next);
        void handle(ExpenseRequest request);
        String getHandlerName();
    }


    // =========================================================================
    // STEP 2: ABSTRACT HANDLER — BaseExpenseHandler
    //
    // Provides the default pass-along behavior.
    // Concrete handlers extend this and override handle() to add their logic.
    // They call passToNext() when the request exceeds their authority.
    //
    // WHY abstract? Because the chain-linking logic (setNext/passToNext)
    // is the same for every handler — no need to repeat it in each one.
    // =========================================================================
    static abstract class BaseExpenseHandler implements ExpenseHandler {
        private ExpenseHandler next; // the next handler in the chain (null if last)
        private int            handledCount = 0;
        private int            passedCount  = 0;

        @Override
        public void setNext(ExpenseHandler next) {
            this.next = next;
        }

        // Called by concrete handlers when they can't or won't handle the request
        protected void passToNext(ExpenseRequest request) {
            passedCount++;
            if (next != null) {
                next.handle(request);
            } else {
                // End of chain — no handler was able to process this request
                System.out.println("  [END OF CHAIN] ✗ No handler approved: " + request);
            }
        }

        // Subclasses call this when they successfully handle a request
        protected void markHandled() {
            handledCount++;
        }

        public int getHandledCount() { return handledCount; }
        public int getPassedCount()  { return passedCount; }
    }


    // =========================================================================
    // STEP 3: CONCRETE HANDLERS
    // Each has an approval limit. Requests within the limit are approved.
    // Requests above the limit are passed to the next handler.
    // =========================================================================

    // ── Handler 1: Manager — approves up to ₹10,000 ──────────────────────────
    static class ManagerHandler extends BaseExpenseHandler {
        private static final double LIMIT = 10_000;
        private final String        managerName;

        public ManagerHandler(String name) { this.managerName = name; }

        @Override
        public void handle(ExpenseRequest request) {
            if (request.getAmount() <= LIMIT) {
                markHandled();
                System.out.printf("  [Manager: %-8s] ✓ Approved  ₹%7.0f for %s%n",
                        managerName, request.getAmount(), request.getEmployeeName());
            } else {
                System.out.printf("  [Manager: %-8s] ↑ Escalating ₹%7.0f (limit: ₹%.0f) → Director%n",
                        managerName, request.getAmount(), LIMIT);
                passToNext(request);
            }
        }

        @Override
        public String getHandlerName() { return "Manager(" + managerName + ")"; }
    }


    // ── Handler 2: Director — approves up to ₹50,000 ─────────────────────────
    static class DirectorHandler extends BaseExpenseHandler {
        private static final double LIMIT = 50_000;
        private final String        directorName;

        public DirectorHandler(String name) { this.directorName = name; }

        @Override
        public void handle(ExpenseRequest request) {
            if (request.getAmount() <= LIMIT) {
                markHandled();
                System.out.printf("  [Director: %-6s] ✓ Approved  ₹%7.0f for %s%n",
                        directorName, request.getAmount(), request.getEmployeeName());
            } else {
                System.out.printf("  [Director: %-6s] ↑ Escalating ₹%7.0f (limit: ₹%.0f) → VP%n",
                        directorName, request.getAmount(), LIMIT);
                passToNext(request);
            }
        }

        @Override
        public String getHandlerName() { return "Director(" + directorName + ")"; }
    }


    // ── Handler 3: VP — approves up to ₹200,000 ──────────────────────────────
    static class VPHandler extends BaseExpenseHandler {
        private static final double LIMIT = 200_000;
        private final String        vpName;

        public VPHandler(String name) { this.vpName = name; }

        @Override
        public void handle(ExpenseRequest request) {
            if (request.getAmount() <= LIMIT) {
                markHandled();
                System.out.printf("  [VP: %-11s] ✓ Approved  ₹%7.0f for %s%n",
                        vpName, request.getAmount(), request.getEmployeeName());
            } else {
                System.out.printf("  [VP: %-11s] ↑ Escalating ₹%7.0f (limit: ₹%.0f) → Board%n",
                        vpName, request.getAmount(), LIMIT);
                passToNext(request);
            }
        }

        @Override
        public String getHandlerName() { return "VP(" + vpName + ")"; }
    }


    // ── Handler 4: Board — handles everything that reaches it ─────────────────
    static class BoardHandler extends BaseExpenseHandler {

        @Override
        public void handle(ExpenseRequest request) {
            markHandled();
            System.out.printf("  [Board           ] 📋 Under review  ₹%7.0f for %s — pending board vote%n",
                    request.getAmount(), request.getEmployeeName());
        }

        @Override
        public String getHandlerName() { return "Board"; }
    }


    // ── Handler 5: Category Filter — a non-amount based handler ──────────────
    // Demonstrates that Chain of Responsibility doesn't have to be about amounts.
    // This handler intercepts "TRAINING" requests and routes them to HR.
    // All other categories are passed along unchanged.
    // Inserted at the START of the chain — runs before the approval chain.
    static class CategoryFilterHandler extends BaseExpenseHandler {
        private static final String SPECIAL_CATEGORY = "TRAINING";

        @Override
        public void handle(ExpenseRequest request) {
            if (SPECIAL_CATEGORY.equals(request.getCategory())) {
                markHandled();
                System.out.printf("  [CategoryFilter  ] 🎓 Routed to HR  ₹%7.0f for %s (TRAINING)%n",
                        request.getAmount(), request.getEmployeeName());
                // Training requests are handled here — not passed to approval chain
            } else {
                // Non-training requests continue down the chain
                passToNext(request);
            }
        }

        @Override
        public String getHandlerName() { return "CategoryFilter"; }
    }


    // ── Handler 6: Audit Handler — runs AFTER any approval ───────────────────
    // This shows that handlers can be inserted anywhere in the chain.
    // It wraps another handler and adds logging without modifying it.
    static class AuditingHandler extends BaseExpenseHandler {
        private final List<String> auditTrail = new ArrayList<>();

        @Override
        public void handle(ExpenseRequest request) {
            // Log the request before passing it along
            String entry = String.format("SUBMITTED: %s | ₹%.0f | %s",
                    request.getEmployeeName(), request.getAmount(), request.getPurpose());
            auditTrail.add(entry);
            System.out.printf("  [AuditHandler    ] 📝 Logged: %s%n", entry);
            passToNext(request); // always passes — auditing doesn't block
        }

        @Override
        public String getHandlerName() { return "AuditHandler"; }

        public void printAuditTrail() {
            System.out.println("\n  ── Audit Trail (" + auditTrail.size() + " requests) ──");
            auditTrail.forEach(e -> System.out.println("    " + e));
        }
    }


    // =========================================================================
    // CHAIN BUILDER — helper to wire and display the chain
    // =========================================================================
    static ExpenseHandler buildChain(ExpenseHandler... handlers) {
        for (int i = 0; i < handlers.length - 1; i++) {
            handlers[i].setNext(handlers[i + 1]);
        }
        return handlers[0]; // return the first handler (entry point)
    }


    // =========================================================================
    // MAIN — demonstrates chain routing, short-circuiting, and dynamic wiring
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Chain of Responsibility Pattern Demo ===\n");

        // ── Build the standard approval chain ─────────────────────────────────
        ManagerHandler  manager  = new ManagerHandler("Priya");
        DirectorHandler director = new DirectorHandler("Rohit");
        VPHandler       vp       = new VPHandler("Sunita");
        BoardHandler    board    = new BoardHandler();

        // Wire: manager → director → vp → board
        ExpenseHandler approvalChain = buildChain(manager, director, vp, board);


        // ── Run standard approval requests ────────────────────────────────────
        System.out.println("─── Standard Approval Chain ─────────────────────────────────────");
        System.out.println("Chain: Manager(₹10k) → Director(₹50k) → VP(₹200k) → Board\n");

        List<ExpenseRequest> requests = List.of(
            new ExpenseRequest("Akash",  "SENIOR", 5_000,   "Team lunch",         "OTHER"),
            new ExpenseRequest("Meera",  "LEAD",   35_000,  "Conference tickets", "TRAINING"),
            new ExpenseRequest("Vikram", "SENIOR", 120_000, "Hardware equipment", "EQUIPMENT"),
            new ExpenseRequest("Priya",  "LEAD",   500_000, "Office renovation",  "OTHER")
        );

        for (ExpenseRequest req : requests) {
            System.out.println("  Request: " + req);
            approvalChain.handle(req);
            System.out.println();
        }


        // ── Chain with category filter prepended ──────────────────────────────
        System.out.println("\n─── Chain with Category Filter (Training → HR) ──────────────────");
        System.out.println("Chain: CategoryFilter → Audit → Manager → Director → VP → Board\n");

        CategoryFilterHandler filter  = new CategoryFilterHandler();
        AuditingHandler       auditor = new AuditingHandler();

        // Dynamically build a different chain for a different policy
        // None of the existing handlers changed — just rewired
        ExpenseHandler enrichedChain = buildChain(filter, auditor, manager, director, vp, board);

        List<ExpenseRequest> mixedRequests = List.of(
            new ExpenseRequest("Alice", "JUNIOR", 8_000,  "Online course",       "TRAINING"),
            new ExpenseRequest("Bob",   "SENIOR", 25_000, "Laptop upgrade",      "EQUIPMENT"),
            new ExpenseRequest("Carol", "LEAD",   15_000, "Team offsite travel", "TRAVEL"),
            new ExpenseRequest("Dave",  "SENIOR", 90_000, "Cloud credits",       "EQUIPMENT")
        );

        for (ExpenseRequest req : mixedRequests) {
            System.out.println("  Request: " + req);
            enrichedChain.handle(req);
            System.out.println();
        }

        auditor.printAuditTrail();


        // ── Handling when nothing in the chain matches ─────────────────────────
        System.out.println("\n─── End-of-Chain Fallthrough ─────────────────────────────────────");
        // Build a short chain with only Manager — no further handlers
        ManagerHandler  smallManager = new ManagerHandler("Solo");
        // No setNext() call — smallManager.next is null
        System.out.println("  Request: ₹500,000 with only a Manager in the chain:");
        smallManager.handle(
            new ExpenseRequest("Eve", "JUNIOR", 500_000, "Huge purchase", "OTHER")
        );


        // ── Handler statistics ─────────────────────────────────────────────────
        System.out.println("\n─── Handler Statistics ───────────────────────────────────────────");
        BaseExpenseHandler[] allHandlers = {manager, director, vp, board, filter, auditor};
        for (BaseExpenseHandler h : allHandlers) {
            System.out.printf("  %-25s | Handled: %d | Passed: %d%n",
                    h.getHandlerName(), h.getHandledCount(), h.getPassedCount());
        }


        // ── Showing what the code looks like WITHOUT the pattern ──────────────
        System.out.println("\n─── Without Chain of Responsibility ─────────────────────────────");
        System.out.println("""
  WITHOUT the pattern (routing logic in the caller):
    if (category.equals("TRAINING")) { hrDepartment.handle(request); }
    else if (amount <= 10000)  { manager.approve(request); }
    else if (amount <= 50000)  { director.approve(request); }
    else if (amount <= 200000) { vp.approve(request); }
    else                       { board.review(request); }
    → Caller knows ALL handler types — tightly coupled to every one
    → Adding a new level = modify the caller
    → Changing routing logic = modify the caller

  WITH Chain of Responsibility:
    firstHandler.handle(request);  ← caller knows ONLY the first handler
    → Adding a new handler = new class + one setNext() call
    → Chain is reconfigurable without touching any handler or caller""");


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Caller always enters at the first handler — never jumps into the middle");
        System.out.println("  2. Each handler is independent — doesn't know who comes before or after");
        System.out.println("  3. Chain is built by wiring via setNext() — configurable at runtime");
        System.out.println("  4. Always handle the end-of-chain case — don't silently drop requests");
        System.out.println("  5. Non-approval handlers (audit, filter) can be inserted anywhere");
        System.out.println("  6. Real uses: HTTP middleware, logging levels, UI event bubbling");
    }
}

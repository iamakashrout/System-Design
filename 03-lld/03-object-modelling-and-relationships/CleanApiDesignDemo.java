import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CLEAN API DESIGN — The Three Rules
 *
 * This file demonstrates the three rules that consistently produce clean, robust APIs:
 *
 *   RULE 1 — Tell, Don't Ask
 *             Ask objects to act on themselves. Don't pull out their state,
 *             make decisions externally, and push state back in.
 *             → Logic lives where the data lives.
 *
 *   RULE 2 — Return Rich Domain Objects, Not Primitives
 *             A boolean or null tells callers almost nothing.
 *             A domain result object carries success/failure, identifiers, reasons.
 *             → The return type is part of the contract.
 *
 *   RULE 3 — Fail Fast with Meaningful Exceptions
 *             Validate at the method boundary, not deep inside logic.
 *             Use custom domain exceptions with clear messages.
 *             → Crashes near the source, with a message that explains why.
 *
 * Each rule is demonstrated with a BAD version and a GOOD version side by side.
 */

// ═══════════════════════════════════════════════════════════════════════════════
// CUSTOM EXCEPTIONS — Named, domain-specific, meaningful
// (Used across all three rules)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Thrown when an operation is attempted on an order that is in an invalid state.
 * E.g., trying to confirm a DELIVERED order.
 */
class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(String message) {
        super(message);
    }
}

/**
 * Thrown when a payment fails for a known domain reason.
 */
class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String message) {
        super(message);
    }
}

/**
 * Thrown when an account has insufficient funds.
 */
class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RULE 1 — TELL, DON'T ASK
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * OrderStatus — the lifecycle states an Order can be in.
 * Having this as an enum (not a String) prevents invalid states entirely.
 */
enum OrderStatus {
    PENDING, CONFIRMED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED
}

/**
 * GOOD VERSION of Order — demonstrates Tell, Don't Ask.
 *
 * The BAD version would look like this (do NOT do this):
 *
 *   if (order.getStatus().equals("PENDING")) {
 *       order.setStatus("CONFIRMED");    // caller reaches in and manipulates state
 *   }
 *   if (order.getStatus().equals("DELIVERED")) {
 *       order.setStatus("CANCELLED");   // invalid — but nothing stops this externally
 *   }
 *
 * Problems with BAD version:
 *   - The same `if` check is copy-pasted everywhere confirmation happens.
 *   - If rules change ("also allow PAYMENT_FAILED → CONFIRMED after retry"),
 *     you must find and update every single copy of that check.
 *   - Order has no opinion about its own lifecycle. It's just a data bag.
 *   - Invalid transitions (DELIVERED → CONFIRMED) are possible — nothing guards them.
 *
 * GOOD version: Order manages its own transitions. Caller just tells it what to do.
 */
class Order {
    private final String orderId;
    private final String customerName;
    private OrderStatus status;
    private double totalAmount;

    public Order(String orderId, String customerName, double totalAmount) {
        Objects.requireNonNull(orderId, "orderId cannot be null");
        Objects.requireNonNull(customerName, "customerName cannot be null");
        if (totalAmount <= 0) throw new IllegalArgumentException("totalAmount must be positive");

        this.orderId = orderId;
        this.customerName = customerName;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.PENDING;
        System.out.println("[Order] Created order " + orderId + " for " + customerName
                + " | Amount: ₹" + totalAmount);
    }

    /**
     * TELL DON'T ASK in action.
     * Caller says: "confirm yourself."
     * Order decides if that's valid. Order updates its own state.
     * The validation rule ("must be PENDING") lives HERE — not scattered in callers.
     */
    public void confirm() {
        if (status != OrderStatus.PENDING) {
            throw new InvalidOrderStateException(
                    "Cannot confirm order " + orderId
                    + " — current status: " + status
                    + ". Order must be PENDING to confirm.");
        }
        this.status = OrderStatus.CONFIRMED;
        System.out.println("[Order] " + orderId + " confirmed ✓");
    }

    public void dispatch() {
        if (status != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException(
                    "Cannot dispatch order " + orderId
                    + " — must be CONFIRMED first. Current: " + status);
        }
        this.status = OrderStatus.OUT_FOR_DELIVERY;
        System.out.println("[Order] " + orderId + " dispatched for delivery ✓");
    }

    public void markDelivered() {
        if (status != OrderStatus.OUT_FOR_DELIVERY) {
            throw new InvalidOrderStateException(
                    "Cannot mark delivered — order " + orderId
                    + " must be OUT_FOR_DELIVERY. Current: " + status);
        }
        this.status = OrderStatus.DELIVERED;
        System.out.println("[Order] " + orderId + " delivered ✓");
    }

    /**
     * Cancel has a more nuanced rule — multiple states allow cancellation.
     * This complexity lives HERE, not in every caller.
     */
    public void cancel(String reason) {
        if (status == OrderStatus.DELIVERED || status == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException(
                    "Cannot cancel order " + orderId
                    + " — already " + status);
        }
        this.status = OrderStatus.CANCELLED;
        System.out.println("[Order] " + orderId + " cancelled. Reason: " + reason);
    }

    /**
     * BAD (Tell Don't Ask violation):
     *   if (account.getBalance() >= order.getTotalAmount()) { ... }
     *
     * GOOD: Order asks itself if it's affordable. External code just asks yes/no.
     * The affordability logic — and any future rules around it — live on Order.
     */
    public boolean isAffordableWith(double availableBalance) {
        return availableBalance >= totalAmount;
    }

    public OrderStatus getStatus() { return status; }
    public String getOrderId() { return orderId; }
    public double getTotalAmount() { return totalAmount; }
    public String getCustomerName() { return customerName; }
}

/**
 * BankAccount — another Tell Don't Ask example.
 *
 * BAD (what you often see):
 *   if (account.getBalance() >= amount) {
 *       account.setBalance(account.getBalance() - amount);  // external manipulation
 *   }
 *
 * GOOD: Account enforces its own rules. debit() and credit() are the API.
 */
class BankAccount {
    private final String accountId;
    private double balance;
    private final double minimumBalance;

    public BankAccount(String accountId, double initialBalance, double minimumBalance) {
        this.accountId = accountId;
        this.balance = initialBalance;
        this.minimumBalance = minimumBalance;
        System.out.println("[BankAccount] " + accountId + " created with balance ₹" + balance);
    }

    /**
     * TELL DON'T ASK: Account enforces all rules about debiting.
     * Callers just say "debit 500" — they don't check balance and then subtract.
     * If rules change (add daily limit, add overdraft fee), change ONLY here.
     */
    public void debit(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Debit amount must be positive");
        if (balance - amount < minimumBalance) {
            throw new InsufficientFundsException(
                    "Account " + accountId + ": balance ₹" + balance
                    + " − ₹" + amount + " would fall below minimum ₹" + minimumBalance);
        }
        this.balance -= amount;
        System.out.println("[BankAccount] " + accountId + " debited ₹" + amount
                + " | New balance: ₹" + balance);
    }

    public void credit(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Credit amount must be positive");
        this.balance += amount;
        System.out.println("[BankAccount] " + accountId + " credited ₹" + amount
                + " | New balance: ₹" + balance);
    }

    public double getBalance() { return balance; }
    public String getAccountId() { return accountId; }
}

/**
 * TransferService — benefits of Tell Don't Ask in the caller.
 * When accounts enforce their own rules, the service becomes trivially simple.
 */
class TransferService {
    /**
     * With Tell Don't Ask — this method is 2 lines of real logic.
     * All the rules live in BankAccount where they belong.
     */
    public void transfer(BankAccount from, BankAccount to, double amount) {
        Objects.requireNonNull(from, "Source account cannot be null");
        Objects.requireNonNull(to, "Destination account cannot be null");
        if (amount <= 0) throw new IllegalArgumentException("Transfer amount must be positive");

        from.debit(amount);   // from enforces its own minimum balance rules
        to.credit(amount);    // to validates its own credit rules
        System.out.println("[Transfer] ₹" + amount + " transferred: "
                + from.getAccountId() + " → " + to.getAccountId());
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RULE 2 — RETURN RICH DOMAIN OBJECTS, NOT PRIMITIVES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * PaymentResult — a rich return type that carries all meaningful information.
 *
 * BAD return types (what NOT to do):
 *   public boolean processPayment(...)  → what does false mean? Network? Declined? Insufficient funds?
 *   public int processPayment(...)      → what does -1 mean? Magic numbers everywhere.
 *   public String processPayment(...)   → caller has to string-compare. Fragile.
 *   public void processPayment(...)     → caller has no idea what happened.
 *
 * GOOD: PaymentResult carries everything the caller needs:
 *   - Did it succeed?
 *   - If yes, what's the transaction ID?
 *   - If no, why did it fail?
 *   - Should the caller retry?
 */
class PaymentResult {
    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        CARD_DECLINED,
        NETWORK_ERROR,
        INVALID_CARD
    }

    private final Status status;
    private final String transactionId;  // meaningful only on SUCCESS
    private final String failureReason;  // meaningful only on failure
    private final boolean retryable;     // should the caller try again?

    // Static factory methods — readable, named construction (better than constructors)
    public static PaymentResult success(String transactionId) {
        return new PaymentResult(Status.SUCCESS, transactionId, null, false);
    }

    public static PaymentResult failed(Status status, String reason, boolean retryable) {
        return new PaymentResult(status, null, reason, retryable);
    }

    // Private constructor — callers use the factory methods above
    private PaymentResult(Status status, String transactionId,
                          String failureReason, boolean retryable) {
        this.status = status;
        this.transactionId = transactionId;
        this.failureReason = failureReason;
        this.retryable = retryable;
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isRetryable() { return retryable; }

    public String getTransactionId() {
        if (!isSuccess()) throw new IllegalStateException("No transaction ID — payment failed");
        return transactionId;
    }

    public String getFailureReason() { return failureReason; }
    public Status getStatus() { return status; }

    @Override
    public String toString() {
        if (isSuccess()) return "PaymentResult{SUCCESS, txn=" + transactionId + "}";
        return "PaymentResult{" + status + ", reason='" + failureReason
                + "', retryable=" + retryable + "}";
    }
}

/**
 * PaymentService — processes payments and returns a rich PaymentResult.
 *
 * Notice: the return type PaymentResult tells you, just by reading the signature,
 * that multiple outcomes are possible and they have meaning.
 */
class PaymentService {
    /**
     * GOOD: Returns PaymentResult — caller gets full context.
     * BAD would be: public boolean processPayment(...) or public void processPayment(...)
     */
    public PaymentResult processPayment(String customerId, double amount, String cardNumber) {
        // Simulate different outcomes based on card number for demo purposes
        if (cardNumber.startsWith("0000")) {
            return PaymentResult.failed(PaymentResult.Status.INVALID_CARD,
                    "Card number is invalid", false);
        }
        if (cardNumber.startsWith("9999")) {
            return PaymentResult.failed(PaymentResult.Status.NETWORK_ERROR,
                    "Payment gateway timed out", true); // retryable!
        }
        if (amount > 100000) {
            return PaymentResult.failed(PaymentResult.Status.CARD_DECLINED,
                    "Amount exceeds daily limit", false);
        }

        // Success case
        String txnId = "TXN-" + System.currentTimeMillis();
        return PaymentResult.success(txnId);
    }
}

/**
 * BookSearchResult — rich return for a search operation.
 *
 * BAD: returning null when book is not found.
 *   public Book findBook(String isbn) { return null; } // caller might forget to check
 *
 * GOOD: returning Optional<Book> — the return type itself signals "might be empty"
 *   The caller is forced to handle both cases.
 */
class BookSearchResult {
    private final Optional<String> bookTitle;  // Optional — might not exist
    private final String searchedIsbn;
    private final boolean wasFound;

    public static BookSearchResult found(String title, String isbn) {
        return new BookSearchResult(Optional.of(title), isbn, true);
    }

    public static BookSearchResult notFound(String isbn) {
        return new BookSearchResult(Optional.empty(), isbn, false);
    }

    private BookSearchResult(Optional<String> bookTitle, String isbn, boolean found) {
        this.bookTitle = bookTitle;
        this.searchedIsbn = isbn;
        this.wasFound = found;
    }

    public boolean wasFound() { return wasFound; }
    public Optional<String> getBookTitle() { return bookTitle; }
    public String getSearchedIsbn() { return searchedIsbn; }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RULE 3 — FAIL FAST WITH MEANINGFUL EXCEPTIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Custom domain exceptions.
 * These are named after WHAT WENT WRONG in the domain — not generic runtime errors.
 *
 * BAD: throw new RuntimeException("something went wrong")
 *      → caller can't catch specifically, message is useless for debugging
 *
 * GOOD: throw new BookNotAvailableException("Copy C002 of 'Clean Code' is borrowed")
 *      → caller catches specifically and responds appropriately
 *      → message tells you exactly what happened
 */
class BookNotAvailableException extends RuntimeException {
    private final String copyId;
    public BookNotAvailableException(String copyId, String bookTitle) {
        super("Copy '" + copyId + "' of '" + bookTitle + "' is currently borrowed");
        this.copyId = copyId;
    }
    public String getCopyId() { return copyId; }
}

class BorrowLimitExceededException extends RuntimeException {
    public BorrowLimitExceededException(String memberId, int limit) {
        super("Member " + memberId + " has reached the borrow limit of " + limit);
    }
}

class MemberNotFoundException extends RuntimeException {
    public MemberNotFoundException(String memberId) {
        super("No member found with ID: " + memberId);
    }
}

/**
 * BorrowingService — demonstrates Fail Fast with Meaningful Exceptions.
 *
 * BAD (no validation):
 *   public void borrowBook(Member member, BookCopy copy) {
 *       Borrowing b = new Borrowing(member, copy);  // NPE if member is null
 *       repo.save(b);                                // NPE propagates 3 levels deep
 *   }
 *   → Crash message: "NullPointerException at line 47 of Borrowing.java"
 *   → You have NO idea what was null or why.
 *
 * GOOD (fail fast):
 *   Validate at the boundary. If something is wrong, crash immediately with a
 *   clear message close to where the bad data entered the system.
 *   → Crash message: "Member M999 has reached the borrow limit of 5"
 *   → You know EXACTLY what went wrong.
 */
class BorrowingService {
    private static final int MAX_BORROW_LIMIT = 3; // kept small for demo

    /**
     * FAIL FAST: All validations happen at the very top of the method.
     * Real work happens only AFTER every check has passed.
     *
     * The order of validations matters:
     *   1. Null checks first (programming mistakes)
     *   2. Business rule checks second (domain violations)
     */
    public String borrowBook(String memberId, String copyId, String bookTitle, boolean copyAvailable) {

        // ── Layer 1: Null checks — catch programming mistakes immediately ──────
        // These tell you "the caller passed garbage". Fail right here.
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(copyId, "copyId cannot be null");
        Objects.requireNonNull(bookTitle, "bookTitle cannot be null");

        // ── Layer 2: Business rule checks — catch invalid domain state ─────────
        // These tell you "the domain state doesn't allow this operation."

        if (memberId.equals("UNKNOWN")) {
            // In real code: look up member from repository, throw if absent
            throw new MemberNotFoundException(memberId);
        }

        if (!copyAvailable) {
            // Custom exception — caller can catch this specifically and say
            // "Sorry, this copy is unavailable" to the user
            throw new BookNotAvailableException(copyId, bookTitle);
        }

        // Simulate member being at limit for demo purposes
        if (memberId.equals("M_AT_LIMIT")) {
            throw new BorrowLimitExceededException(memberId, MAX_BORROW_LIMIT);
        }

        // ── Only after ALL validations pass — do the actual work ──────────────
        String borrowingId = "BRW-" + memberId + "-" + copyId;
        System.out.println("[BorrowingService] Borrowing created: " + borrowingId
                + " | Member: " + memberId + " | Book: " + bookTitle
                + " | Due: " + java.time.LocalDate.now().plusDays(14));
        return borrowingId;
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RUNNER
// ═══════════════════════════════════════════════════════════════════════════════

public class CleanApiDesignDemo {
    public static void main(String[] args) {

        // ── RULE 1: Tell, Don't Ask ───────────────────────────────────────────
        System.out.println("════════════════════════════════════════════════");
        System.out.println("  RULE 1 — Tell, Don't Ask");
        System.out.println("════════════════════════════════════════════════\n");

        // --- Order state machine ---
        Order order = new Order("ORD-001", "Akash", 4500.0);

        System.out.println();
        // Tell the order to manage its own transitions
        order.confirm();
        order.dispatch();
        order.markDelivered();

        System.out.println();
        // Try an invalid transition — Order protects itself
        System.out.println("[Demo] Attempting to cancel a DELIVERED order:");
        try {
            order.cancel("Changed my mind");
        } catch (InvalidOrderStateException e) {
            System.out.println("[Caught] " + e.getMessage());
        }

        System.out.println();
        // --- Bank transfer with Tell Don't Ask ---
        BankAccount savings  = new BankAccount("SAV-001", 10000.0, 500.0);
        BankAccount checking = new BankAccount("CHK-001", 2000.0, 0.0);

        TransferService transferService = new TransferService();
        System.out.println();
        transferService.transfer(savings, checking, 3000.0);

        System.out.println();
        System.out.println("[Demo] Attempting overdraft — account enforces its own rules:");
        try {
            transferService.transfer(savings, checking, 9999.0); // would breach minimum
        } catch (InsufficientFundsException e) {
            System.out.println("[Caught] " + e.getMessage());
        }

        // ── RULE 2: Rich Return Objects ───────────────────────────────────────
        System.out.println("\n════════════════════════════════════════════════");
        System.out.println("  RULE 2 — Return Rich Domain Objects");
        System.out.println("════════════════════════════════════════════════\n");

        PaymentService paymentService = new PaymentService();

        // Case 1: Successful payment
        PaymentResult result1 = paymentService.processPayment("C001", 5000.0, "4111111111111111");
        System.out.println("[Payment] Result: " + result1);
        if (result1.isSuccess()) {
            System.out.println("   → Transaction ID: " + result1.getTransactionId());
        }

        System.out.println();

        // Case 2: Network error — retryable
        PaymentResult result2 = paymentService.processPayment("C001", 5000.0, "9999000011112222");
        System.out.println("[Payment] Result: " + result2);
        if (!result2.isSuccess()) {
            if (result2.isRetryable()) {
                System.out.println("   → Will retry. Reason: " + result2.getFailureReason());
            } else {
                System.out.println("   → Permanent failure: " + result2.getFailureReason());
            }
        }

        System.out.println();

        // Case 3: Invalid card — not retryable
        PaymentResult result3 = paymentService.processPayment("C001", 5000.0, "0000000000000000");
        System.out.println("[Payment] Result: " + result3);
        System.out.println("   → Retryable? " + result3.isRetryable());
        System.out.println("   → Reason: " + result3.getFailureReason());

        System.out.println();

        // Optional as return type — forces caller to handle "not found"
        BookSearchResult found    = BookSearchResult.found("Clean Code", "9780132350884");
        BookSearchResult notFound = BookSearchResult.notFound("9999999999999");

        System.out.println("[Search] ISBN 9780132350884:");
        found.getBookTitle().ifPresentOrElse(
                title -> System.out.println("   Found: " + title),
                ()    -> System.out.println("   Not found")
        );

        System.out.println("[Search] ISBN 9999999999999:");
        notFound.getBookTitle().ifPresentOrElse(
                title -> System.out.println("   Found: " + title),
                ()    -> System.out.println("   Not found in library")
        );

        // ── RULE 3: Fail Fast with Meaningful Exceptions ──────────────────────
        System.out.println("\n════════════════════════════════════════════════");
        System.out.println("  RULE 3 — Fail Fast with Meaningful Exceptions");
        System.out.println("════════════════════════════════════════════════\n");

        BorrowingService borrowingService = new BorrowingService();

        // Happy path — all validations pass
        System.out.println("--- Happy path ---");
        borrowingService.borrowBook("M001", "C001", "Clean Code", true);

        System.out.println();
        System.out.println("--- Fail: book not available ---");
        try {
            borrowingService.borrowBook("M002", "C001", "Clean Code", false);
        } catch (BookNotAvailableException e) {
            System.out.println("[Caught BookNotAvailableException] " + e.getMessage());
            System.out.println("   → Show user: 'This copy is currently checked out'");
        }

        System.out.println();
        System.out.println("--- Fail: member at borrow limit ---");
        try {
            borrowingService.borrowBook("M_AT_LIMIT", "C002", "Refactoring", true);
        } catch (BorrowLimitExceededException e) {
            System.out.println("[Caught BorrowLimitExceededException] " + e.getMessage());
            System.out.println("   → Show user: 'Return a book before borrowing more'");
        }

        System.out.println();
        System.out.println("--- Fail: member not found ---");
        try {
            borrowingService.borrowBook("UNKNOWN", "C003", "DDIA", true);
        } catch (MemberNotFoundException e) {
            System.out.println("[Caught MemberNotFoundException] " + e.getMessage());
            System.out.println("   → Show user: 'Member ID not found. Please register first.'");
        }

        System.out.println();
        System.out.println("--- Fail: null check (programming mistake, fail immediately) ---");
        try {
            borrowingService.borrowBook(null, "C001", "Clean Code", true);
        } catch (NullPointerException e) {
            System.out.println("[Caught NullPointerException] " + e.getMessage());
            System.out.println("   → This is a bug in the caller — caught at entry, not 10 levels deep");
        }

        System.out.println("\n--- Key Takeaways ---");
        System.out.println("Rule 1 — Tell Don't Ask:   Logic lives where data lives. Objects protect themselves.");
        System.out.println("Rule 2 — Rich Returns:     Return types carry full meaning. No magic booleans/nulls.");
        System.out.println("Rule 3 — Fail Fast:        Validate at boundaries. Named exceptions explain why.");
    }
}

import java.io.IOException;

/**
 * TOPIC 3: EXCEPTION DESIGN
 *
 * Domain: ATM System (Phase 5, Problem 11)
 *
 * Demonstrates a clean, unchecked exception hierarchy for a fintech domain,
 * exception translation across a hardware boundary (checked -> unchecked,
 * with cause preserved), fail-fast input validation, and — explicitly
 * labeled — the anti-patterns to avoid.
 */
public class ExceptionDesign {

    // =========================================================================
    // PART 1: THE EXCEPTION HIERARCHY
    //
    //   AtmException (abstract, unchecked)
    //   ├── AuthenticationException
    //   │   ├── InvalidPinException
    //   │   └── CardBlockedException
    //   ├── TransactionException
    //   │   ├── InsufficientFundsException
    //   │   ├── DailyLimitExceededException
    //   │   └── InsufficientCashInDispenserException
    //   └── AtmSystemException
    //       └── CashDispenserHardwareException
    // =========================================================================

    /**
     * Abstract root of the domain hierarchy. Unchecked (extends RuntimeException)
     * deliberately: most callers up the stack cannot meaningfully recover from
     * an ATM-domain failure beyond reacting to a SPECIFIC subtype they know
     * how to handle, or letting it propagate to a top-level handler.
     */
    abstract static class AtmException extends RuntimeException {
        AtmException(String message) {
            super(message);
        }

        AtmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // --- Authentication branch ---

    static class AuthenticationException extends AtmException {
        AuthenticationException(String message) {
            super(message);
        }
    }

    static class InvalidPinException extends AuthenticationException {
        InvalidPinException(String cardNumberMasked) {
            super("Invalid PIN entered for card " + cardNumberMasked);
        }
    }

    static class CardBlockedException extends AuthenticationException {
        CardBlockedException(String cardNumberMasked, int failedAttempts) {
            super("Card " + cardNumberMasked + " blocked after " + failedAttempts + " failed attempts");
        }
    }

    // --- Transaction branch ---

    static class TransactionException extends AtmException {
        TransactionException(String message) {
            super(message);
        }
    }

    static class InsufficientFundsException extends TransactionException {
        InsufficientFundsException(double requested, double available) {
            super("Requested withdrawal of " + requested + " exceeds available balance of " + available);
        }
    }

    static class DailyLimitExceededException extends TransactionException {
        DailyLimitExceededException(double requested, double dailyLimitRemaining) {
            super("Requested withdrawal of " + requested
                    + " exceeds remaining daily limit of " + dailyLimitRemaining);
        }
    }

    static class InsufficientCashInDispenserException extends TransactionException {
        InsufficientCashInDispenserException(double requested) {
            super("ATM cannot dispense " + requested + " — insufficient cash loaded in dispenser");
        }
    }

    // --- System/infrastructure branch ---

    static class AtmSystemException extends AtmException {
        AtmSystemException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A wrapped, unchecked exception. Note the constructor REQUIRES a cause —
     * this exception only ever exists as a translation of some lower-level
     * failure, so losing that cause would defeat its purpose.
     */
    static class CashDispenserHardwareException extends AtmSystemException {
        CashDispenserHardwareException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // =========================================================================
    // PART 2: EXCEPTION TRANSLATION ACROSS A LAYER BOUNDARY
    // =========================================================================

    /**
     * Simulates a low-level hardware driver. IOException here is a reasonable
     * use of a CHECKED exception — it's a well-established JDK convention for
     * I/O failures, and this class sits at the very edge of the system talking
     * to physical hardware.
     */
    static final class CashDispenserHardware {
        private int billsLoaded;

        CashDispenserHardware(int billsLoaded) {
            this.billsLoaded = billsLoaded;
        }

        /** Throws a CHECKED exception — appropriate at this literal hardware edge. */
        void dispenseBills(int count) throws IOException {
            if (count > billsLoaded) {
                throw new IOException("Dispenser jam: requested " + count
                        + " bills but only " + billsLoaded + " loaded");
            }
            billsLoaded -= count;
            System.out.println("[HARDWARE] Dispensed " + count + " bills. Remaining: " + billsLoaded);
        }
    }

    /**
     * The translation boundary. This service is the ONE place that knows about
     * the checked IOException from the hardware layer. It converts it into the
     * domain's unchecked vocabulary, preserving the original as `cause` so no
     * diagnostic information is lost. Everything above this class only ever
     * sees AtmException and its subtypes.
     */
    static final class CashDispenserService {
        private final CashDispenserHardware hardware;

        CashDispenserService(CashDispenserHardware hardware) {
            this.hardware = hardware;
        }

        void dispense(double amount, int denomination) {
            int billCount = (int) (amount / denomination);
            try {
                hardware.dispenseBills(billCount);
            } catch (IOException e) {
                // TRANSLATION: checked -> unchecked, cause preserved.
                throw new CashDispenserHardwareException(
                        "Failed to dispense " + amount + " due to a hardware fault", e);
            }
        }
    }

    // =========================================================================
    // PART 3: FAIL-FAST VALIDATION IN THE ORCHESTRATING SERVICE
    // =========================================================================

    static final class Account {
        private double balance;
        private double dailyLimitRemaining;

        Account(double balance, double dailyLimitRemaining) {
            this.balance = balance;
            this.dailyLimitRemaining = dailyLimitRemaining;
        }

        double getBalance() {
            return balance;
        }

        double getDailyLimitRemaining() {
            return dailyLimitRemaining;
        }

        void debit(double amount) {
            balance -= amount;
            dailyLimitRemaining -= amount;
        }
    }

    static final class AtmService {
        private static final int DENOMINATION = 100;
        private final CashDispenserService dispenserService;

        AtmService(CashDispenserService dispenserService) {
            this.dispenserService = dispenserService;
        }

        void withdraw(Account account, double amount) {
            // FAIL-FAST: validate the input itself before touching any state
            // or talking to hardware. A negative or malformed amount is a
            // programmer/input error, not a business condition — IllegalArgumentException.
            if (amount <= 0) {
                throw new IllegalArgumentException("Withdrawal amount must be positive, got: " + amount);
            }
            if (amount % DENOMINATION != 0) {
                throw new IllegalArgumentException(
                        "Withdrawal amount must be a multiple of " + DENOMINATION + ", got: " + amount);
            }

            // Business-rule validation — these ARE expected domain failures,
            // each gets its own specific, named exception.
            if (amount > account.getBalance()) {
                throw new InsufficientFundsException(amount, account.getBalance());
            }
            if (amount > account.getDailyLimitRemaining()) {
                throw new DailyLimitExceededException(amount, account.getDailyLimitRemaining());
            }

            // Only now do we touch external state / hardware.
            dispenserService.dispense(amount, DENOMINATION);
            account.debit(amount);
            System.out.println("[ATM] Dispensed " + amount + ". Remaining balance: " + account.getBalance());
        }
    }

    // =========================================================================
    // PART 4: ANTI-PATTERNS — explicitly labeled, for contrast only
    // =========================================================================

    static final class AntiPatternDemos {

        /** ANTI-PATTERN 1: swallowing an exception. */
        static void swallowedException(AtmService atmService, Account account) {
            try {
                atmService.withdraw(account, 100_000); // will fail — far exceeds balance
            } catch (Exception e) {
                // Nothing here. The failure vanishes. The caller has no idea
                // the withdrawal did NOT happen — this is how silent data
                // corruption / silent business-logic failures are born.
            }
            System.out.println("[ANTI-PATTERN] swallowedException: no error surfaced, "
                    + "caller believes the operation may have succeeded.");
        }

        /** ANTI-PATTERN 2: catching Exception broadly instead of specific types. */
        static void broadCatch(AtmService atmService, Account account) {
            try {
                atmService.withdraw(account, -50); // this is actually a caller BUG
            } catch (Exception e) {
                // This catches InsufficientFundsException, IllegalArgumentException,
                // AND a genuine NullPointerException from a real defect, identically.
                // A caller cannot tell "expected business failure" from "we have a bug."
                System.out.println("[ANTI-PATTERN] broadCatch: caught \"" + e.getClass().getSimpleName()
                        + "\" — cannot distinguish a real bug from an expected domain failure here.");
            }
        }

        /** ANTI-PATTERN 3: using an exception for ordinary, expected control flow. */
        static boolean hasSufficientFunds(Account account, double amount) {
            try {
                if (amount > account.getBalance()) {
                    throw new InsufficientFundsException(amount, account.getBalance());
                }
                return true;
            } catch (InsufficientFundsException e) {
                // Using an exception here just to return `false` for a routine,
                // expected, common case is expensive (stack trace construction)
                // and misleading (this isn't exceptional — it's a normal check).
                // A plain boolean-returning method (as shown just above, MINUS
                // the try/catch) is the correct tool.
                return false;
            }
        }
    }

    // =========================================================================
    // MAIN — exercise the hierarchy, translation, fail-fast validation,
    // and (separately, clearly labeled) the anti-patterns.
    // =========================================================================

    public static void main(String[] args) {
        CashDispenserHardware hardware = new CashDispenserHardware(5); // only 5 bills loaded
        CashDispenserService dispenserService = new CashDispenserService(hardware);
        AtmService atmService = new AtmService(dispenserService);

        System.out.println("--- Fail-fast validation ---");
        Account account1 = new Account(1000, 500);
        try {
            atmService.withdraw(account1, -100);
        } catch (IllegalArgumentException e) {
            System.out.println("[CAUGHT] IllegalArgumentException: " + e.getMessage());
        }

        System.out.println();
        System.out.println("--- Specific business exception: insufficient funds ---");
        try {
            atmService.withdraw(account1, 2000);
        } catch (InsufficientFundsException e) {
            System.out.println("[CAUGHT] InsufficientFundsException: " + e.getMessage());
        }

        System.out.println();
        System.out.println("--- Specific business exception: daily limit exceeded ---");
        try {
            atmService.withdraw(account1, 600); // within balance, exceeds daily limit of 500
        } catch (DailyLimitExceededException e) {
            System.out.println("[CAUGHT] DailyLimitExceededException: " + e.getMessage());
        }

        System.out.println();
        System.out.println("--- Successful withdrawal ---");
        atmService.withdraw(account1, 400); // 4 bills of 100, within limits, hardware has 5

        System.out.println();
        System.out.println("--- Exception translation: hardware fault -> domain exception ---");
        try {
            atmService.withdraw(account1, 200); // hardware now only has 1 bill left, needs 2
        } catch (CashDispenserHardwareException e) {
            System.out.println("[CAUGHT] CashDispenserHardwareException: " + e.getMessage());
            System.out.println("         caused by: " + e.getCause().getClass().getSimpleName()
                    + " -> " + e.getCause().getMessage());
        }

        System.out.println();
        System.out.println("--- Catching at a broader level (AuthenticationException) ---");
        try {
            throw new CardBlockedException("**** **** **** 1234", 3);
        } catch (AuthenticationException e) {
            // caught broadly on purpose — this handler doesn't care WHICH
            // authentication failure occurred, just that re-auth is needed.
            System.out.println("[CAUGHT] " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Anti-pattern demonstrations (what NOT to do) ===");
        Account account2 = new Account(1000, 500);
        AntiPatternDemos.swallowedException(atmService, account2);
        AntiPatternDemos.broadCatch(atmService, account2);
        System.out.println("[ANTI-PATTERN] hasSufficientFunds via exception-as-control-flow: "
                + AntiPatternDemos.hasSufficientFunds(account2, 2000));
    }
}

import java.time.LocalDateTime;
import java.util.*;

/**
 * ATM System — Classic LLD Interview Problem #11.
 *
 * Core complexity this system captures:
 *   - The ATM has radically different behavior depending on its current state
 *     (IDLE, CARD_INSERTED, PIN_ENTERED, TRANSACTION, DISPENSING). Without
 *     the State pattern, every public method would carry a giant nested
 *     if-else chain — unmaintainable and closed to extension. With it, each
 *     state is a class that only implements what's valid in that state; the
 *     ATMContext delegates every user action to the current state object.
 *   - This is the first problem in this series to use the *full* GoF State
 *     pattern (class-per-state), rather than the rich-enum approach used in
 *     Problems 7-10. The distinction: rich enums are right when the only
 *     complex behavior is "is this transition legal?". Full state classes are
 *     right when each state has substantial, *different* behavior — as here,
 *     where CardInsertedState tracks PIN retries, DispensingState blocks all
 *     user input, and PinEnteredState creates and fires Commands.
 *   - Cash dispensing with multiple denominations: the allocation algorithm
 *     is a Strategy, decoupled from both the ATM's control flow and from the
 *     Command that requests the withdrawal.
 *
 * Patterns used:
 *   - State   -> ATMState interface + 5 concrete state classes
 *   - Command -> TransactionCommand interface + 3 concrete commands
 *   - Strategy -> CashDispensingStrategy + 2 implementations
 *   - Facade  -> ATMContext (single entry point for all user interactions)
 *
 * Concurrency model:
 *   - ATMContext public methods are synchronized. An ATM machine is a
 *     single-session device — no parallel access is meaningful or expected.
 *   - BankAccount balance updates are synchronized at the account level.
 *   - CashBin denomination counts are synchronized at the bin level.
 *   - The two synchronization concerns are independent and can never
 *     deadlock because no code acquires both locks simultaneously.
 *
 * State default methods: ATMState uses Java interface default methods to
 * throw InvalidOperationException for every operation. Concrete states
 * only override the operations that are *legal* in that state. This keeps
 * each state class focused on its valid behaviors — not on rejecting invalid
 * ones — and avoids repeating the same boilerplate throw in five classes.
 */
public class ATMSystem {

    // =========================================================
    // Exceptions
    // =========================================================

    public static class InvalidOperationException extends RuntimeException {
        public InvalidOperationException(String msg) { super(msg); }
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String msg) { super(msg); }
    }

    public static class InsufficientCashException extends RuntimeException {
        public InsufficientCashException(String msg) { super(msg); }
    }

    // =========================================================
    // Denomination enum
    // =========================================================

    public enum Denomination {
        TWO_HUNDRED(200), ONE_HUNDRED(100), FIFTY(50), TWENTY(20), TEN(10);

        private final int value;

        Denomination(int value) { this.value = value; }

        public int getValue() { return value; }

        /** Denominations in descending order — the standard iteration order for dispensing. */
        public static Denomination[] descending() {
            return new Denomination[]{TWO_HUNDRED, ONE_HUNDRED, FIFTY, TWENTY, TEN};
        }

        public static Denomination fromValue(int value) {
            for (Denomination d : values()) {
                if (d.value == value) return d;
            }
            throw new IllegalArgumentException("No denomination for value: " + value);
        }
    }

    // =========================================================
    // TransactionType enum
    // =========================================================

    public enum TransactionType { WITHDRAWAL, DEPOSIT, BALANCE_INQUIRY }

    // =========================================================
    // CashBin
    // =========================================================

    public static class CashBin {
        private final Map<Denomination, Integer> counts = new EnumMap<>(Denomination.class);

        public synchronized void load(Denomination denom, int count) {
            counts.merge(denom, count, Integer::sum);
        }

        public synchronized Map<Denomination, Integer> getAvailableCounts() {
            return Collections.unmodifiableMap(new EnumMap<>(counts));
        }

        /** Atomically removes the given bill allocation; throws if any denomination is short. */
        public synchronized void remove(Map<Denomination, Integer> bills) {
            for (Map.Entry<Denomination, Integer> entry : bills.entrySet()) {
                int available = counts.getOrDefault(entry.getKey(), 0);
                if (available < entry.getValue()) {
                    throw new InsufficientCashException(
                            "Cash bin has only " + available + " x $" + entry.getKey().getValue()
                                    + " but needs " + entry.getValue());
                }
            }
            bills.forEach((d, qty) -> counts.merge(d, -qty, Integer::sum));
        }

        public synchronized int getTotalCash() {
            return counts.entrySet().stream().mapToInt(e -> e.getKey().getValue() * e.getValue()).sum();
        }
    }

    // =========================================================
    // Card
    // =========================================================

    public static class Card {
        private final String cardNumber;
        private final String holderName;
        private volatile boolean retained;

        public Card(String cardNumber, String holderName) {
            this.cardNumber = cardNumber;
            this.holderName = holderName;
        }

        public void markRetained() { this.retained = true; }
        public String getCardNumber() { return cardNumber; }
        public String getHolderName() { return holderName; }
        public boolean isRetained() { return retained; }

        @Override public String toString() { return "Card[" + cardNumber + "]"; }
    }

    // =========================================================
    // BankAccount
    // =========================================================

    public static class BankAccount {
        private final String accountId;
        private final String cardNumber;
        private final String pin;
        private double balance;

        public BankAccount(String accountId, String cardNumber, String pin, double initialBalance) {
            this.accountId = accountId;
            this.cardNumber = cardNumber;
            this.pin = pin;
            this.balance = initialBalance;
        }

        public synchronized void debit(double amount) {
            if (balance < amount) throw new InsufficientFundsException(
                    "Balance $" + String.format("%.2f", balance) + " < requested $" + String.format("%.2f", amount));
            balance -= amount;
        }

        public synchronized void credit(double amount) { balance += amount; }
        public synchronized double getBalance() { return balance; }

        public String getAccountId() { return accountId; }
        public String getCardNumber() { return cardNumber; }
        String getPin() { return pin; }
    }

    // =========================================================
    // Bank
    // =========================================================

    public static class Bank {
        private final Map<String, BankAccount> byCardNumber = new HashMap<>();

        public BankAccount registerAccount(String accountId, String cardNumber, String pin, double balance) {
            BankAccount account = new BankAccount(accountId, cardNumber, pin, balance);
            byCardNumber.put(cardNumber, account);
            return account;
        }

        public BankAccount findAccountByCard(Card card) {
            return byCardNumber.get(card.getCardNumber());
        }

        public boolean validatePin(BankAccount account, String pin) {
            return account.getPin().equals(pin);
        }
    }

    // =========================================================
    // TransactionRecord
    // =========================================================

    public static class TransactionRecord {
        private final LocalDateTime timestamp;
        private final String accountId;
        private final TransactionType type;
        private final double amount;
        private final boolean success;
        private final String details;

        private TransactionRecord(String accountId, TransactionType type, double amount,
                                  boolean success, String details) {
            this.timestamp = LocalDateTime.now();
            this.accountId = accountId;
            this.type = type;
            this.amount = amount;
            this.success = success;
            this.details = details;
        }

        public static TransactionRecord withdrawal(String accountId, double amount,
                                                   Map<Denomination, Integer> bills) {
            StringBuilder sb = new StringBuilder("Dispensed:");
            bills.forEach((d, c) -> sb.append(" ").append(c).append("×$").append(d.getValue()));
            return new TransactionRecord(accountId, TransactionType.WITHDRAWAL, amount, true, sb.toString());
        }

        public static TransactionRecord deposit(String accountId, double amount) {
            return new TransactionRecord(accountId, TransactionType.DEPOSIT, amount, true, "Deposit accepted");
        }

        public static TransactionRecord balanceInquiry(String accountId, double balance) {
            return new TransactionRecord(accountId, TransactionType.BALANCE_INQUIRY, balance, true,
                    "Balance: $" + String.format("%.2f", balance));
        }

        public static TransactionRecord failure(String accountId, String reason) {
            return new TransactionRecord(accountId, TransactionType.WITHDRAWAL, 0, false, "FAILED: " + reason);
        }

        public String getSummary() {
            return String.format("[%s] %s %-18s  %s",
                    timestamp.toLocalTime(), accountId, type, details);
        }

        public String getAccountId() { return accountId; }
        public boolean isSuccess() { return success; }
    }

    // =========================================================
    // TransactionLog
    // =========================================================

    public static class TransactionLog {
        private final List<TransactionRecord> records = Collections.synchronizedList(new ArrayList<>());

        public void addRecord(TransactionRecord record) { records.add(record); }

        public void print() {
            System.out.println("  Transaction log:");
            synchronized (records) {
                for (TransactionRecord r : records) System.out.println("    " + r.getSummary());
            }
        }
    }

    // =========================================================
    // ATMState interface — State pattern
    // =========================================================

    /**
     * Each method carries a default implementation that throws
     * InvalidOperationException. Concrete states only override the
     * operations that are *legal* in that state, keeping each class
     * focused on its own responsibilities rather than on rejecting
     * operations that belong to other states.
     */
    public interface ATMState {
        default void insertCard(ATMContext atm, Card card) {
            throw new InvalidOperationException("Cannot insert card in " + getStateName() + " state");
        }
        default void enterPin(ATMContext atm, String pin) {
            throw new InvalidOperationException("Cannot enter PIN in " + getStateName() + " state");
        }
        default void selectTransaction(ATMContext atm, TransactionCommand command) {
            throw new InvalidOperationException("Cannot select transaction in " + getStateName() + " state");
        }
        default void cancel(ATMContext atm) {
            throw new InvalidOperationException("Cannot cancel in " + getStateName() + " state");
        }
        String getStateName();
    }

    // =========================================================
    // Concrete states
    // =========================================================

    /** IDLE — the resting state. Only insertCard is valid. */
    public static class IdleState implements ATMState {
        @Override
        public void insertCard(ATMContext atm, Card card) {
            if (card.isRetained()) {
                throw new InvalidOperationException("Card " + card.getCardNumber() + " has been retained by the ATM");
            }
            BankAccount account = atm.getBank().findAccountByCard(card);
            if (account == null) {
                System.out.println("  Card not recognized. Returning card.");
                return;
            }
            atm.setCurrentCard(card);
            atm.setCurrentAccount(account);
            atm.resetPinAttempts();
            atm.setState(new CardInsertedState());
            System.out.println("  Card accepted. Please enter your PIN.");
        }

        @Override
        public void cancel(ATMContext atm) { /* already idle — no-op */ }

        @Override public String getStateName() { return "IDLE"; }
    }

    /**
     * CARD_INSERTED — waiting for PIN.
     * Tracks failed attempts via ATMContext; retains card after MAX_PIN_ATTEMPTS failures.
     */
    public static class CardInsertedState implements ATMState {
        @Override
        public void enterPin(ATMContext atm, String pin) {
            atm.incrementPinAttempts();
            if (atm.getBank().validatePin(atm.getCurrentAccount(), pin)) {
                atm.resetPinAttempts();
                atm.setState(new PinEnteredState());
                System.out.println("  PIN accepted. Select a transaction.");
            } else {
                int remaining = ATMContext.MAX_PIN_ATTEMPTS - atm.getPinAttempts();
                if (remaining <= 0) {
                    System.out.println("  Too many incorrect PINs. Card retained.");
                    atm.retainCard();
                    atm.setState(new IdleState());
                } else {
                    System.out.println("  Incorrect PIN. " + remaining + " attempt(s) remaining.");
                }
            }
        }

        @Override
        public void cancel(ATMContext atm) {
            System.out.println("  Cancelled. Card ejected.");
            atm.ejectCard();
            atm.setState(new IdleState());
        }

        @Override public String getStateName() { return "CARD_INSERTED"; }
    }

    /** PIN_ENTERED — PIN validated, waiting for transaction selection. */
    public static class PinEnteredState implements ATMState {
        @Override
        public void selectTransaction(ATMContext atm, TransactionCommand command) {
            System.out.println("  Transaction selected: " + command.getDescription());
            atm.setState(new TransactionState());
            atm.executeCommand(command);
        }

        @Override
        public void cancel(ATMContext atm) {
            System.out.println("  Session cancelled. Card ejected.");
            atm.ejectCard();
            atm.setState(new IdleState());
        }

        @Override public String getStateName() { return "PIN_ENTERED"; }
    }

    /**
     * TRANSACTION — command is executing. No user input is accepted.
     * This state is transient: ATMContext.executeCommand() immediately
     * transitions out (to DISPENSING or IDLE) once the command runs.
     */
    public static class TransactionState implements ATMState {
        @Override public String getStateName() { return "TRANSACTION"; }
        // All operations throw via default implementations — no user input during execution.
    }

    /**
     * DISPENSING — cash is being physically dispensed. No user input accepted.
     * dispensingComplete() is called by ATMContext once the mechanical step
     * finishes, transitioning back to IDLE and ejecting the card.
     */
    public static class DispensingState implements ATMState {
        public void dispensingComplete(ATMContext atm) {
            System.out.println("  Please collect your cash and card.");
            atm.ejectCard();
            atm.setState(new IdleState());
        }

        @Override public String getStateName() { return "DISPENSING"; }
        // All default operations throw — machine is busy.
    }

    // =========================================================
    // ATMContext — context / facade
    // =========================================================

    public static class ATMContext {
        static final int MAX_PIN_ATTEMPTS = 3;

        private ATMState currentState;
        private Card currentCard;
        private BankAccount currentAccount;
        private int pinAttempts;
        private final CashBin cashBin;
        private final Bank bank;
        private final CashDispensingStrategy dispensingStrategy;
        private final TransactionLog transactionLog;

        public ATMContext(CashBin cashBin, Bank bank,
                         CashDispensingStrategy dispensingStrategy, TransactionLog transactionLog) {
            this.cashBin = cashBin;
            this.bank = bank;
            this.dispensingStrategy = dispensingStrategy;
            this.transactionLog = transactionLog;
            this.currentState = new IdleState();
        }

        // ── user-facing operations ──────────────────────────

        public synchronized void insertCard(Card card) {
            System.out.println("[" + currentState.getStateName() + "] insertCard(" + card.getCardNumber() + ")");
            currentState.insertCard(this, card);
        }

        public synchronized void enterPin(String pin) {
            System.out.println("[" + currentState.getStateName() + "] enterPin(****)");
            currentState.enterPin(this, pin);
        }

        public synchronized void requestWithdrawal(double amount) {
            System.out.println("[" + currentState.getStateName() + "] withdraw($" + (int) amount + ")");
            currentState.selectTransaction(this, new WithdrawalCommand(amount));
        }

        public synchronized void requestDeposit(double amount) {
            System.out.println("[" + currentState.getStateName() + "] deposit($" + (int) amount + ")");
            currentState.selectTransaction(this, new DepositCommand(amount));
        }

        public synchronized void requestBalance() {
            System.out.println("[" + currentState.getStateName() + "] balanceInquiry()");
            currentState.selectTransaction(this, new BalanceInquiryCommand());
        }

        public synchronized void cancel() {
            System.out.println("[" + currentState.getStateName() + "] cancel()");
            currentState.cancel(this);
        }

        // ── called by states ────────────────────────────────

        public void setState(ATMState state) {
            System.out.println("  → state: " + currentState.getStateName() + " → " + state.getStateName());
            this.currentState = state;
        }

        /**
         * Runs a TransactionCommand. If it requires dispensing (withdrawal),
         * transitions through DISPENSING before returning to IDLE. Non-
         * dispensing transactions (deposit, balance inquiry) go straight to IDLE.
         */
        public void executeCommand(TransactionCommand command) {
            try {
                TransactionRecord record = command.execute(currentAccount, cashBin, dispensingStrategy);
                transactionLog.addRecord(record);
                System.out.println("  ✓ " + record.getSummary());

                if (command.requiresDispensing()) {
                    DispensingState dispensing = new DispensingState();
                    setState(dispensing);
                    dispensing.dispensingComplete(this);
                } else {
                    ejectCard();
                    setState(new IdleState());
                }
            } catch (Exception e) {
                System.out.println("  ✗ Transaction failed: " + e.getMessage());
                transactionLog.addRecord(TransactionRecord.failure(
                        currentAccount != null ? currentAccount.getAccountId() : "UNKNOWN",
                        e.getMessage()));
                ejectCard();
                setState(new IdleState());
            }
        }

        public void ejectCard() {
            if (currentCard != null) {
                System.out.println("  Card ejected: " + currentCard.getCardNumber());
            }
            currentCard = null;
            currentAccount = null;
            pinAttempts = 0;
        }

        public void retainCard() {
            if (currentCard != null) {
                currentCard.markRetained();
                System.out.println("  Card retained: " + currentCard.getCardNumber());
            }
            currentCard = null;
            currentAccount = null;
            pinAttempts = 0;
        }

        public void setCurrentCard(Card card) { this.currentCard = card; }
        public void setCurrentAccount(BankAccount account) { this.currentAccount = account; }
        public void incrementPinAttempts() { pinAttempts++; }
        public void resetPinAttempts() { pinAttempts = 0; }
        public int getPinAttempts() { return pinAttempts; }
        public Card getCurrentCard() { return currentCard; }
        public BankAccount getCurrentAccount() { return currentAccount; }
        public Bank getBank() { return bank; }
        public CashBin getCashBin() { return cashBin; }
        public CashDispensingStrategy getDispensingStrategy() { return dispensingStrategy; }
        public TransactionLog getTransactionLog() { return transactionLog; }
        public String getCurrentStateName() { return currentState.getStateName(); }
    }

    // =========================================================
    // TransactionCommand interface — Command pattern
    // =========================================================

    public interface TransactionCommand {
        /**
         * Executes the transaction against the account and cash bin.
         * Returns an audit record. Throws on any failure (insufficient
         * funds, insufficient cash, etc.) — ATMContext catches and logs.
         */
        TransactionRecord execute(BankAccount account, CashBin cashBin, CashDispensingStrategy strategy);

        /** Whether executing this command requires the DISPENSING state. */
        boolean requiresDispensing();

        String getDescription();
    }

    public static class WithdrawalCommand implements TransactionCommand {
        private final double amount;

        public WithdrawalCommand(double amount) {
            if (amount <= 0 || amount % 10 != 0) {
                throw new IllegalArgumentException("Withdrawal amount must be a positive multiple of 10");
            }
            this.amount = amount;
        }

        @Override
        public TransactionRecord execute(BankAccount account, CashBin cashBin, CashDispensingStrategy strategy) {
            account.debit(amount); // throws InsufficientFundsException if balance too low
            Map<Denomination, Integer> bills = strategy.calculateDispensing((int) amount, cashBin.getAvailableCounts());
            cashBin.remove(bills); // throws InsufficientCashException if bin is short
            return TransactionRecord.withdrawal(account.getAccountId(), amount, bills);
        }

        @Override public boolean requiresDispensing() { return true; }
        @Override public String getDescription() { return "Withdraw $" + (int) amount; }
    }

    public static class DepositCommand implements TransactionCommand {
        private final double amount;

        public DepositCommand(double amount) {
            if (amount <= 0) throw new IllegalArgumentException("Deposit amount must be positive");
            this.amount = amount;
        }

        @Override
        public TransactionRecord execute(BankAccount account, CashBin cashBin, CashDispensingStrategy strategy) {
            account.credit(amount);
            return TransactionRecord.deposit(account.getAccountId(), amount);
        }

        @Override public boolean requiresDispensing() { return false; }
        @Override public String getDescription() { return "Deposit $" + (int) amount; }
    }

    public static class BalanceInquiryCommand implements TransactionCommand {
        @Override
        public TransactionRecord execute(BankAccount account, CashBin cashBin, CashDispensingStrategy strategy) {
            return TransactionRecord.balanceInquiry(account.getAccountId(), account.getBalance());
        }

        @Override public boolean requiresDispensing() { return false; }
        @Override public String getDescription() { return "Balance inquiry"; }
    }

    // =========================================================
    // CashDispensingStrategy — Strategy pattern
    // =========================================================

    public interface CashDispensingStrategy {
        /**
         * Calculates the denomination breakdown to dispense {@code amount}.
         * Receives a read-only snapshot of currently available bill counts.
         * Returns the allocation or throws InsufficientCashException if the
         * exact amount cannot be made from available bills.
         */
        Map<Denomination, Integer> calculateDispensing(int amount, Map<Denomination, Integer> available);
    }

    /**
     * Greedy strategy: allocate as many of the largest denomination as
     * possible, then move to the next smaller, and so on. Fast, simple,
     * and optimal for standard ATM denominations (200, 100, 50, 20, 10)
     * where the greedy choice always equals the minimum-bills choice.
     */
    public static class GreedyDispensingStrategy implements CashDispensingStrategy {
        @Override
        public Map<Denomination, Integer> calculateDispensing(int amount, Map<Denomination, Integer> available) {
            Map<Denomination, Integer> result = new EnumMap<>(Denomination.class);
            int remaining = amount;
            for (Denomination denom : Denomination.descending()) {
                int canUse = Math.min(remaining / denom.getValue(), available.getOrDefault(denom, 0));
                if (canUse > 0) {
                    result.put(denom, canUse);
                    remaining -= canUse * denom.getValue();
                }
            }
            if (remaining != 0) {
                throw new InsufficientCashException(
                        "Greedy cannot dispense exactly $" + amount + " with available bills");
            }
            return result;
        }
    }

    /**
     * Minimum-bills strategy: bounded coin-change DP.
     *
     * Uses a bounded 0-1 knapsack approach: each individual bill is treated
     * as a separate item. A backward scan per bill prevents the same bill
     * from being counted more than once, correctly enforcing the finite
     * supply of each denomination.
     *
     * Differs from greedy when the bin has an unusual mix — e.g., if there
     * are no $50 bills, the greedy algorithm may fail to make $70 from
     * [$20×3, $10×5] while the DP finds {$20×2, $10×3} = 5 bills exactly.
     *
     * Time: O(amount × total_bills). Space: O(amount).
     */
    public static class MinNotesDispensingStrategy implements CashDispensingStrategy {
        @Override
        public Map<Denomination, Integer> calculateDispensing(int amount, Map<Denomination, Integer> available) {
            final int INF = Integer.MAX_VALUE / 2;
            int[] dp = new int[amount + 1];
            Denomination[] from = new Denomination[amount + 1];
            Arrays.fill(dp, INF);
            dp[0] = 0;

            // Treat each individual bill as a 0-1 knapsack item.
            for (Denomination denom : Denomination.descending()) {
                int count = available.getOrDefault(denom, 0);
                int val = denom.getValue();
                if (val > amount || count == 0) continue;
                for (int k = 0; k < count; k++) {
                    // Backward scan: ensures this specific bill is used at most once per pass.
                    for (int i = amount; i >= val; i--) {
                        if (dp[i - val] != INF && dp[i - val] + 1 < dp[i]) {
                            dp[i] = dp[i - val] + 1;
                            from[i] = denom;
                        }
                    }
                }
            }

            if (dp[amount] == INF) {
                throw new InsufficientCashException(
                        "MinNotes cannot dispense exactly $" + amount + " with available bills");
            }

            // Reconstruct denomination breakdown from the 'from' backtrack array.
            Map<Denomination, Integer> result = new EnumMap<>(Denomination.class);
            int rem = amount;
            while (rem > 0) {
                Denomination d = from[rem];
                result.merge(d, 1, Integer::sum);
                rem -= d.getValue();
            }
            return result;
        }
    }

    // =========================================================
    // Demo
    // =========================================================

    public static void main(String[] args) {
        Bank bank = new Bank();
        BankAccount aliceAcc = bank.registerAccount("ACC001", "4111-1111", "1234", 2000.00);
        BankAccount bobAcc   = bank.registerAccount("ACC002", "4222-2222", "5678", 500.00);

        Card aliceCard = new Card("4111-1111", "Alice");
        Card bobCard   = new Card("4222-2222", "Bob");
        Card unknownCard = new Card("9999-9999", "Unknown");

        CashBin cashBin = new CashBin();
        cashBin.load(Denomination.ONE_HUNDRED, 10);
        cashBin.load(Denomination.FIFTY, 10);
        cashBin.load(Denomination.TWENTY, 10);
        cashBin.load(Denomination.TEN, 10);

        TransactionLog log = new TransactionLog();
        ATMContext atm = new ATMContext(cashBin, bank, new GreedyDispensingStrategy(), log);

        System.out.println("=== Scenario 1: normal withdrawal ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        atm.requestWithdrawal(250);
        System.out.printf("  Alice balance after withdrawal: $%.2f%n", aliceAcc.getBalance());

        System.out.println();
        System.out.println("=== Scenario 2: balance inquiry ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        atm.requestBalance();

        System.out.println();
        System.out.println("=== Scenario 3: deposit ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        atm.requestDeposit(300);
        System.out.printf("  Alice balance after deposit: $%.2f%n", aliceAcc.getBalance());

        System.out.println();
        System.out.println("=== Scenario 4: wrong PIN then correct PIN ===");
        atm.insertCard(bobCard);
        atm.enterPin("0000"); // wrong
        atm.enterPin("5678"); // correct
        atm.requestWithdrawal(100);

        System.out.println();
        System.out.println("=== Scenario 5: card retention after 3 wrong PINs ===");
        Card victimCard = new Card("4222-2222", "Bob-copy");
        atm.insertCard(victimCard);
        atm.enterPin("0000");
        atm.enterPin("1111");
        atm.enterPin("2222"); // third wrong attempt — card retained
        System.out.println("  Card retained status: " + victimCard.isRetained());
        try {
            atm.insertCard(victimCard); // attempt to use retained card
        } catch (InvalidOperationException e) {
            System.out.println("  Correctly rejected retained card: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Scenario 6: insufficient funds ===");
        atm.insertCard(bobCard);
        atm.enterPin("5678");
        atm.requestWithdrawal(1000); // Bob only has ~$400 left after scenario 4

        System.out.println();
        System.out.println("=== Scenario 7: invalid operation in IDLE state ===");
        try {
            atm.enterPin("1234");
        } catch (InvalidOperationException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }
        try {
            atm.requestWithdrawal(100);
        } catch (InvalidOperationException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Scenario 8: cancel mid-session ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        atm.cancel();
        System.out.println("  ATM state after cancel: " + atm.getCurrentStateName());

        System.out.println();
        System.out.println("=== Scenario 9: MinNotes dispensing strategy ===");
        // Load a special-case bin: only $20s and $10s — greedy still works fine,
        // but MinNotes shows the DP path explicitly.
        CashBin specialBin = new CashBin();
        specialBin.load(Denomination.TWENTY, 3);
        specialBin.load(Denomination.TEN, 5);
        ATMContext atm2 = new ATMContext(specialBin, bank, new MinNotesDispensingStrategy(), new TransactionLog());
        atm2.insertCard(aliceCard);
        atm2.enterPin("1234");
        atm2.requestWithdrawal(70); // $20×3 + $10×1 = 4 bills (greedy: 20+20+20+10; MinNotes: same)

        System.out.println();
        System.out.println("=== Transaction log ===");
        log.print();
    }
}

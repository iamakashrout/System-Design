// =============================================================================
// PATTERN: State
// PURPOSE: Allow an object to alter its behavior when its internal state
//          changes. The object appears to change its class.
//
// REAL-WORLD ANALOGY:
//   A vending machine. When idle: inserting a coin changes state.
//   When it has a coin: selecting a product triggers dispensing.
//   When dispensing: no input is accepted. Each state has its own valid
//   inputs and transitions. The machine isn't a pile of conditionals —
//   it IS a state, and that state handles inputs appropriately.
//
// THE PROBLEM THIS SOLVES:
//   Without State, every method has scattered if-else blocks:
//     public void insertCard() {
//         if (state == IDLE)         { acceptCard(); }
//         else if (state == CARD_IN) { reject(); }
//         else if (state == PIN_OK)  { reject(); }
//     }
//     public void requestCash() {
//         if (state == IDLE)         { reject(); }
//         else if (state == CARD_IN) { reject(); }
//         else if (state == PIN_OK)  { dispense(); }
//     }
//   This grows linearly with every new state AND every new operation.
//
//   With State: each state handles its own valid operations.
//   Invalid operations are rejected cleanly by the current state object.
//
// THREE INGREDIENTS:
//   1. State interface → all operations the context can perform
//   2. Concrete states → each encapsulates behavior for one state
//   3. Context         → holds current state, delegates everything to it
// =============================================================================

public class StatePattern {

    // =========================================================================
    // STEP 1: STATE INTERFACE
    // Defines every operation the ATM can perform.
    // Each concrete state class decides what to do (or reject) for each op.
    // =========================================================================
    interface ATMState {
        void insertCard(ATMContext atm, String cardNumber);
        void enterPin(ATMContext atm, String pin);
        void requestCash(ATMContext atm, double amount);
        void ejectCard(ATMContext atm);
        String getStateName(); // for display in transitions
    }


    // =========================================================================
    // STEP 2: CONTEXT — ATMContext
    //
    // The ATM machine itself. It:
    //   - Holds the current state object
    //   - Delegates EVERY operation to the current state
    //   - Has no if-else logic of its own — zero conditional branches
    //   - Provides setState() for state objects to trigger transitions
    //   - Provides data accessors (balance, card) for state objects to use
    // =========================================================================
    static class ATMContext {
        private ATMState currentState;
        private double   balance;
        private String   insertedCardNumber;

        // All state instances — created once, reused throughout the lifecycle
        // This avoids creating new objects on every transition
        private final ATMState idleState;
        private final ATMState cardInsertedState;
        private final ATMState pinVerifiedState;
        private final ATMState maintenanceState;

        public ATMContext(double initialBalance) {
            this.balance          = initialBalance;
            this.idleState        = new IdleState();
            this.cardInsertedState = new CardInsertedState();
            this.pinVerifiedState  = new PinVerifiedState();
            this.maintenanceState  = new MaintenanceState();
            this.currentState      = idleState; // start in Idle
            System.out.println("[ATM] Initialized | Balance: ₹" + initialBalance
                    + " | State: " + currentState.getStateName());
        }

        // ── All public operations delegate to the current state ───────────────
        // The ATM itself has NO decision logic — the state decides everything
        public void insertCard(String cardNumber) {
            this.insertedCardNumber = cardNumber;
            currentState.insertCard(this, cardNumber);
        }

        public void enterPin(String pin) {
            currentState.enterPin(this, pin);
        }

        public void requestCash(double amount) {
            currentState.requestCash(this, amount);
        }

        public void ejectCard() {
            currentState.ejectCard(this);
        }

        // ── State transition — called by state objects to move to next state ──
        public void setState(ATMState newState) {
            System.out.println("  [ATM] ⟶ Transition: " + currentState.getStateName()
                    + " → " + newState.getStateName());
            this.currentState = newState;
        }

        // ── Accessors for state objects ───────────────────────────────────────
        public ATMState getIdleState()          { return idleState; }
        public ATMState getCardInsertedState()  { return cardInsertedState; }
        public ATMState getPinVerifiedState()   { return pinVerifiedState; }
        public ATMState getMaintenanceState()   { return maintenanceState; }

        public double  getBalance()             { return balance; }
        public String  getInsertedCardNumber()  { return insertedCardNumber; }
        public boolean hasSufficientFunds(double amount) { return balance >= amount; }

        public void deductBalance(double amount) {
            if (amount > balance) throw new IllegalStateException("Insufficient funds");
            balance -= amount;
        }

        public void clearCard() {
            this.insertedCardNumber = null;
        }

        public String getCurrentStateName() {
            return currentState.getStateName();
        }
    }


    // =========================================================================
    // STEP 3: CONCRETE STATES
    //
    // Each state handles its own valid operations and rejects invalid ones.
    // State objects call atm.setState() to trigger transitions.
    // They access atm data via context accessors.
    // =========================================================================

    // ── State 1: Idle ─────────────────────────────────────────────────────────
    // The ATM is waiting. The only valid action is inserting a card.
    static class IdleState implements ATMState {

        @Override
        public void insertCard(ATMContext atm, String cardNumber) {
            // VALID in this state — accept the card and transition
            System.out.println("[Idle] ✓ Card accepted: " + mask(cardNumber));
            atm.setState(atm.getCardInsertedState()); // transition to next state
        }

        @Override
        public void enterPin(ATMContext atm, String pin) {
            // INVALID — no card inserted yet
            System.out.println("[Idle] ✗ Cannot enter PIN — please insert a card first");
        }

        @Override
        public void requestCash(ATMContext atm, double amount) {
            // INVALID
            System.out.println("[Idle] ✗ Cannot request cash — please insert a card first");
        }

        @Override
        public void ejectCard(ATMContext atm) {
            // INVALID — no card to eject
            System.out.println("[Idle] ✗ No card inserted to eject");
        }

        @Override
        public String getStateName() { return "IDLE"; }

        // Mask the card number for security in logs
        private String mask(String cardNumber) {
            if (cardNumber == null || cardNumber.length() < 4) return "****";
            return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
        }
    }


    // ── State 2: Card Inserted ────────────────────────────────────────────────
    // A card is in the slot. Waiting for PIN entry.
    // Valid: enterPin, ejectCard
    // Invalid: insertCard again, requestCash without PIN
    static class CardInsertedState implements ATMState {
        private static final String CORRECT_PIN = "1234"; // simplified — real ATMs use encrypted PIN
        private int pinAttempts = 0;
        private static final int MAX_ATTEMPTS = 3;

        @Override
        public void insertCard(ATMContext atm, String cardNumber) {
            // INVALID — card already inserted
            System.out.println("[CardInserted] ✗ A card is already inserted");
        }

        @Override
        public void enterPin(ATMContext atm, String pin) {
            // VALID in this state — check the PIN
            pinAttempts++;
            if (CORRECT_PIN.equals(pin)) {
                System.out.println("[CardInserted] ✓ PIN verified successfully");
                pinAttempts = 0; // reset for next time
                atm.setState(atm.getPinVerifiedState()); // transition to verified state
            } else {
                int remaining = MAX_ATTEMPTS - pinAttempts;
                if (remaining > 0) {
                    System.out.println("[CardInserted] ✗ Incorrect PIN — " + remaining + " attempt(s) left");
                } else {
                    // Too many wrong attempts — card is locked/ejected
                    System.out.println("[CardInserted] ✗ Too many wrong attempts — card ejected for security");
                    pinAttempts = 0;
                    atm.clearCard();
                    atm.setState(atm.getIdleState());
                }
            }
        }

        @Override
        public void requestCash(ATMContext atm, double amount) {
            // INVALID — PIN not verified yet
            System.out.println("[CardInserted] ✗ Please verify your PIN before requesting cash");
        }

        @Override
        public void ejectCard(ATMContext atm) {
            // VALID — user wants to cancel
            System.out.println("[CardInserted] Card ejected — transaction cancelled");
            pinAttempts = 0;
            atm.clearCard();
            atm.setState(atm.getIdleState());
        }

        @Override
        public String getStateName() { return "CARD_INSERTED"; }
    }


    // ── State 3: PIN Verified ─────────────────────────────────────────────────
    // PIN is confirmed. The user can now request cash.
    // Valid: requestCash, ejectCard
    // Invalid: inserting card again, entering PIN again
    static class PinVerifiedState implements ATMState {

        @Override
        public void insertCard(ATMContext atm, String cardNumber) {
            // INVALID — session already in progress
            System.out.println("[PinVerified] ✗ Transaction in progress — cannot insert another card");
        }

        @Override
        public void enterPin(ATMContext atm, String pin) {
            // INVALID — PIN already verified
            System.out.println("[PinVerified] ✗ PIN already verified for this session");
        }

        @Override
        public void requestCash(ATMContext atm, double amount) {
            // VALID — the core operation this state enables
            if (amount <= 0) {
                System.out.println("[PinVerified] ✗ Amount must be positive");
                return;
            }
            if (!atm.hasSufficientFunds(amount)) {
                System.out.printf("[PinVerified] ✗ Insufficient funds | Requested: ₹%.0f | Available: ₹%.0f%n",
                        amount, atm.getBalance());
                // Stay in PinVerified — user can try a different amount
                return;
            }
            atm.deductBalance(amount);
            System.out.printf("[PinVerified] ✓ Dispensing ₹%.0f 💵 | Remaining balance: ₹%.0f%n",
                    amount, atm.getBalance());
            // After dispensing, auto-eject and return to idle
            atm.ejectCard();
        }

        @Override
        public void ejectCard(ATMContext atm) {
            // VALID — user wants to end session without withdrawing
            System.out.println("[PinVerified] Card ejected — session ended");
            atm.clearCard();
            atm.setState(atm.getIdleState());
        }

        @Override
        public String getStateName() { return "PIN_VERIFIED"; }
    }


    // ── State 4: Maintenance ──────────────────────────────────────────────────
    // ATM is under maintenance — ALL operations rejected
    // Shows how State makes it trivial to add new states without touching others
    static class MaintenanceState implements ATMState {
        private static final String MSG = "[Maintenance] ✗ ATM is under maintenance. Please try another ATM.";

        @Override public void insertCard(ATMContext atm, String card)   { System.out.println(MSG); }
        @Override public void enterPin(ATMContext atm, String pin)       { System.out.println(MSG); }
        @Override public void requestCash(ATMContext atm, double amount) { System.out.println(MSG); }
        @Override public void ejectCard(ATMContext atm)                  { System.out.println(MSG); }

        @Override
        public String getStateName() { return "MAINTENANCE"; }
    }


    // =========================================================================
    // MAIN — demonstrates all state transitions and invalid operation handling
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== State Pattern Demo ===\n");

        ATMContext atm = new ATMContext(10000.0);


        // ── Scenario 1: Happy path — full successful withdrawal ───────────────
        System.out.println("\n─── Scenario 1: Happy Path ──────────────────────────────────────");
        atm.insertCard("4111-1111-1111-9999");
        atm.enterPin("1234");
        atm.requestCash(3000.0);
        // After dispensing, auto-ejects and returns to Idle


        // ── Scenario 2: Operations in wrong state ─────────────────────────────
        System.out.println("\n─── Scenario 2: Invalid Operations (Wrong State) ────────────────");
        System.out.println("  [Test] Trying operations before inserting card:");
        atm.requestCash(500.0);  // should reject — Idle state
        atm.enterPin("1234");    // should reject — Idle state


        // ── Scenario 3: Wrong PIN with retry ──────────────────────────────────
        System.out.println("\n─── Scenario 3: Wrong PIN Flow ──────────────────────────────────");
        atm.insertCard("4111-2222-3333-4444");
        atm.requestCash(500.0); // invalid — PIN not entered yet
        atm.enterPin("9999");   // wrong
        atm.enterPin("0000");   // wrong again
        atm.enterPin("1234");   // correct this time
        atm.requestCash(8000.0); // insufficient — only ₹7000 left
        atm.requestCash(5000.0); // valid withdrawal


        // ── Scenario 4: Card ejected mid-flow ────────────────────────────────
        System.out.println("\n─── Scenario 4: Eject Card Mid-Flow ────────────────────────────");
        atm.insertCard("4111-5555-6666-7777");
        atm.enterPin("1234");
        System.out.println("  [User] Changed mind — ejecting card");
        atm.ejectCard(); // eject during PinVerified state


        // ── Scenario 5: Too many wrong PINs ───────────────────────────────────
        System.out.println("\n─── Scenario 5: Too Many Wrong PINs (Security Lockout) ──────────");
        atm.insertCard("4111-8888-9999-0000");
        atm.enterPin("1111"); // wrong 1
        atm.enterPin("2222"); // wrong 2
        atm.enterPin("3333"); // wrong 3 — card ejected automatically


        // ── Scenario 6: Maintenance state ─────────────────────────────────────
        System.out.println("\n─── Scenario 6: Maintenance State ───────────────────────────────");
        atm.setState(atm.getMaintenanceState()); // admin puts ATM in maintenance
        atm.insertCard("4111-1111-1111-9999");   // rejected
        atm.enterPin("1234");                    // rejected
        atm.requestCash(1000.0);                 // rejected
        // Restore to idle
        atm.setState(atm.getIdleState());
        atm.insertCard("4111-1111-1111-9999");   // now works again


        // ── Why State pattern: showing the alternative ────────────────────────
        System.out.println("\n─── Why State Pattern? The Alternative ──────────────────────────");
        System.out.println("""
  WITHOUT State (conditional nightmare):
    public void insertCard(String card) {
        if (state == IDLE)             { acceptCard(); setState(CARD_IN); }
        else if (state == CARD_IN)     { reject("already inserted"); }
        else if (state == PIN_VERIFIED){ reject("session in progress"); }
        else if (state == MAINTENANCE) { reject("maintenance"); }
    }
    public void enterPin(String pin) {
        if (state == IDLE)             { reject("no card"); }
        else if (state == CARD_IN)     { verifyPin(pin); }
        else if (state == PIN_VERIFIED){ reject("already verified"); }
        else if (state == MAINTENANCE) { reject("maintenance"); }
    }
    → Every method = full state check. Adding a new state = modify ALL methods.

  WITH State:
    public void insertCard(String card) { currentState.insertCard(this, card); }
    → No conditionals. Each state class handles exactly its own behavior.
    → Adding a new state = 1 new class, zero changes to existing code.""");


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Context has ZERO conditional logic — pure delegation to current state");
        System.out.println("  2. Each state rejects invalid operations cleanly with helpful messages");
        System.out.println("  3. States trigger their own transitions via atm.setState(...)");
        System.out.println("  4. State instances are created once in context and reused");
        System.out.println("  5. Adding a new state = new class only, no existing states touched");
        System.out.println("  6. vs Strategy: State transitions internally; Strategy is swapped by client");
    }
}

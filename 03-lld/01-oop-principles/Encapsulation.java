// ============================================================
// OOP Principle 1: ENCAPSULATION
// "Hide what doesn't need to be seen"
//
// Demonstrated through a BankAccount system.
// - Private fields protect internal state
// - Public methods act as controlled gateways
// - Validation lives inside the class, not scattered in callers
// ============================================================

public class Encapsulation {

    // ── Core class: BankAccount ───────────────────────────────
    static class BankAccount {
        private final String accountId;
        private final String ownerName;
        private double balance;
        private int transactionCount;

        public BankAccount(String accountId, String ownerName, double initialBalance) {
            if (initialBalance < 0) {
                throw new IllegalArgumentException("Initial balance cannot be negative.");
            }
            this.accountId = accountId;
            this.ownerName = ownerName;
            this.balance = initialBalance;
            this.transactionCount = 0;
            System.out.println("[Account Created] " + ownerName
                + " | ID: " + accountId
                + " | Opening Balance: ₹" + initialBalance);
        }

        // ── Controlled deposit: validates before mutating state ──
        public void deposit(double amount) {
            if (amount <= 0) {
                System.out.println("[Deposit Failed] Amount must be positive. Received: ₹" + amount);
                return;
            }
            balance += amount;
            transactionCount++;
            System.out.println("[Deposit] ₹" + amount
                + " deposited into " + ownerName + "'s account."
                + " | New Balance: ₹" + balance);
        }

        // ── Controlled withdraw: validates before mutating state ──
        public void withdraw(double amount) {
            if (amount <= 0) {
                System.out.println("[Withdraw Failed] Amount must be positive. Received: ₹" + amount);
                return;
            }
            if (amount > balance) {
                System.out.println("[Withdraw Failed] Insufficient funds."
                    + " Requested: ₹" + amount
                    + " | Available: ₹" + balance);
                return;
            }
            balance -= amount;
            transactionCount++;
            System.out.println("[Withdraw] ₹" + amount
                + " withdrawn from " + ownerName + "'s account."
                + " | New Balance: ₹" + balance);
        }

        // ── Transfer: uses existing methods — no duplicate logic ──
        public void transfer(BankAccount target, double amount) {
            System.out.println("[Transfer] Initiating transfer of ₹" + amount
                + " from " + this.ownerName + " to " + target.ownerName);
            this.withdraw(amount);
            target.deposit(amount);
        }

        // ── Read-only getters — no setters for sensitive fields ──
        public double getBalance()        { return balance; }
        public String getAccountId()      { return accountId; }
        public String getOwnerName()      { return ownerName; }
        public int getTransactionCount()  { return transactionCount; }

        public void printSummary() {
            System.out.println("─────────────────────────────────────");
            System.out.println("Account Summary for: " + ownerName);
            System.out.println("  Account ID    : " + accountId);
            System.out.println("  Balance       : ₹" + balance);
            System.out.println("  Transactions  : " + transactionCount);
            System.out.println("─────────────────────────────────────");
        }
    }


    // ── Supporting class: UserProfile ────────────────────────
    // Demonstrates encapsulation with input validation on every setter
    static class UserProfile {
        private String name;
        private String email;
        private int age;

        public UserProfile(String name, String email, int age) {
            setName(name);
            setEmail(email);
            setAge(age);
        }

        public void setName(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Name cannot be empty.");
            }
            this.name = name.trim();
        }

        public void setEmail(String email) {
            if (email == null || !email.contains("@")) {
                throw new IllegalArgumentException("Invalid email: " + email);
            }
            this.email = email;
        }

        public void setAge(int age) {
            if (age < 0 || age > 150) {
                throw new IllegalArgumentException("Age out of range: " + age);
            }
            this.age = age;
        }

        public String getName()  { return name; }
        public String getEmail() { return email; }
        public int getAge()      { return age; }

        public void printProfile() {
            System.out.println("  Profile → Name: " + name
                + " | Email: " + email
                + " | Age: " + age);
        }
    }


    // ── Main: demonstrates both classes ──────────────────────
    public static void main(String[] args) {

        System.out.println("═══════════════════════════════════════");
        System.out.println("  OOP PRINCIPLE 1: ENCAPSULATION");
        System.out.println("═══════════════════════════════════════\n");

        // ── Demo 1: Normal operations ──
        System.out.println(">>> Demo 1: Normal Deposits and Withdrawals");
        BankAccount akash = new BankAccount("ACC-001", "Akash", 10000);
        akash.deposit(5000);
        akash.withdraw(3000);
        akash.printSummary();

        // ── Demo 2: Invalid operations are blocked ──
        System.out.println("\n>>> Demo 2: Invalid Operations Blocked by Encapsulation");
        akash.deposit(-500);       // negative deposit blocked
        akash.withdraw(50000);     // overdraft blocked
        akash.withdraw(-100);      // negative withdraw blocked
        System.out.println("  Balance still safe: ₹" + akash.getBalance());

        // ── Demo 3: Transfer between accounts ──
        System.out.println("\n>>> Demo 3: Transfer Between Accounts");
        BankAccount priya = new BankAccount("ACC-002", "Priya", 2000);
        akash.transfer(priya, 4000);
        akash.printSummary();
        priya.printSummary();

        // ── Demo 4: UserProfile validation ──
        System.out.println("\n>>> Demo 4: UserProfile with Input Validation");
        UserProfile user = new UserProfile("Akash", "akash@example.com", 24);
        user.printProfile();

        System.out.println("  Updating age to 25...");
        user.setAge(25);
        user.printProfile();

        System.out.println("  Trying invalid email...");
        try {
            user.setEmail("not-an-email");
        } catch (IllegalArgumentException e) {
            System.out.println("  [Blocked] " + e.getMessage());
        }

        System.out.println("  Trying invalid age...");
        try {
            user.setAge(-10);
        } catch (IllegalArgumentException e) {
            System.out.println("  [Blocked] " + e.getMessage());
        }

        System.out.println("\n✔ Encapsulation ensures internal state is always valid and protected.");
    }
}

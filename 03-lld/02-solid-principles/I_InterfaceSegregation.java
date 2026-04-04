// ============================================================
//  I — Interface Segregation Principle (ISP)
//
//  Principle : Clients should not be forced to depend on
//              interfaces they don't use.
//
//  Two examples:
//  1. Worker / Robot — canonical example
//  2. Printer / Scanner / Fax — real-world hardware
//
//  Key idea  : Design interfaces around what the CALLER needs,
//              not what the implementor can do.
// ============================================================

public class I_InterfaceSegregation {

    // ══════════════════════════════════════════════════════════════
    //  EXAMPLE 1 — Worker / Robot
    // ══════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────
    //  VIOLATION — fat interface
    // ─────────────────────────────────────────────

    /**
     * VIOLATION: Fat interface forces ALL implementors — including
     * Robot — to implement eat() and sleep(). Robot has no honest
     * implementation for these. Every option is bad:
     *   - Silent no-op  → ghost behaviour, caller never knows
     *   - Throw error   → runtime bomb, breaks LSP too
     *   - Log warning   → noise in production
     */
    interface WorkerViolation {
        void work();
        void eat();   // Makes no sense for a Robot
        void sleep(); // Makes no sense for a Robot
    }

    static class HumanWorkerViolation implements WorkerViolation {
        @Override public void work()  { System.out.println("  [Human]  Working on the task."); }
        @Override public void eat()   { System.out.println("  [Human]  Eating lunch."); }
        @Override public void sleep() { System.out.println("  [Human]  Resting after shift."); }
    }

    static class RobotViolation implements WorkerViolation {
        @Override
        public void work() { System.out.println("  [Robot]  Executing task at full speed."); }

        // Option A: silent no-op — caller gets no feedback. Lying.
        @Override
        public void eat() {
            // nothing happens — caller has no idea
        }

        // Option B: exception — runtime bomb, breaks LSP
        @Override
        public void sleep() {
            throw new UnsupportedOperationException("Robots don't sleep!");
        }
    }

    // ─────────────────────────────────────────────
    //  FIX — segregated interfaces
    // ─────────────────────────────────────────────

    /** Any entity that can work. */
    interface Workable {
        void work();
    }

    /** Any entity that needs food. */
    interface Feedable {
        void eat();
    }

    /** Any entity that needs rest. */
    interface Restable {
        void sleep();
    }

    /**
     * Human honestly implements all three — no stubs, no exceptions.
     * Every method has a real, meaningful implementation.
     */
    static class HumanWorker implements Workable, Feedable, Restable {
        private final String name;

        HumanWorker(String name) { this.name = name; }

        @Override public void work()  { System.out.println("  [" + name + "] Working on the task."); }
        @Override public void eat()   { System.out.println("  [" + name + "] Having lunch."); }
        @Override public void sleep() { System.out.println("  [" + name + "] Resting after shift."); }
    }

    /**
     * Robot implements ONLY what it can genuinely support.
     * No eat(), no sleep() — completely honest.
     * Trying to pass Robot to a Feedable slot won't compile.
     */
    static class Robot implements Workable {
        private final String model;

        Robot(String model) { this.model = model; }

        @Override
        public void work() {
            System.out.println("  [" + model + "] Executing automated task at 100% efficiency.");
        }

        // Robot's own unique behaviour — no interface forces this anywhere
        void recharge() {
            System.out.println("  [" + model + "] Recharging battery...");
        }
    }

    /**
     * Each method accepts exactly the capability it needs — nothing more.
     * Passing a Robot to lunchBreak() will cause a compile-time error.
     */
    static class WorkflowManager {
        void assignTask(Workable worker) {
            System.out.println("  Assigning task...");
            worker.work();
        }

        void lunchBreak(Feedable worker) {
            System.out.println("  Lunch break...");
            worker.eat();
        }

        void endOfShift(Restable worker) {
            System.out.println("  End of shift...");
            worker.sleep();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  EXAMPLE 2 — Printer / Scanner / Fax
    // ══════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────
    //  VIOLATION
    // ─────────────────────────────────────────────

    /**
     * VIOLATION: BasicPrinter can only print,
     * but is forced to 'implement' scan and fax.
     */
    interface MultiFunctionDeviceViolation {
        void printDoc(String document);
        void scan(String document);
        void fax(String document);
    }

    static class BasicPrinterViolation implements MultiFunctionDeviceViolation {
        @Override
        public void printDoc(String document) {
            System.out.println("  [BasicPrinter] Printing: " + document);
        }

        // Forced stubs — runtime bombs
        @Override
        public void scan(String document) {
            throw new UnsupportedOperationException("BasicPrinter cannot scan!");
        }

        @Override
        public void fax(String document) {
            throw new UnsupportedOperationException("BasicPrinter cannot fax!");
        }
    }

    // ─────────────────────────────────────────────
    //  FIX — segregated by device capability
    // ─────────────────────────────────────────────

    interface Printable {
        void printDoc(String document);
    }

    interface Scannable {
        void scan(String document);
    }

    interface Faxable {
        void fax(String document);
    }

    /** Only prints — completely honest. */
    static class BasicPrinter implements Printable {
        @Override
        public void printDoc(String document) {
            System.out.println("  [BasicPrinter]   Printing: " + document);
        }
    }

    /** Prints and scans — no fax capability. */
    static class OfficePrinter implements Printable, Scannable {
        @Override
        public void printDoc(String document) {
            System.out.println("  [OfficePrinter]  Printing: " + document);
        }

        @Override
        public void scan(String document) {
            System.out.println("  [OfficePrinter]  Scanning: " + document);
        }
    }

    /** Full all-in-one — honestly supports everything. */
    static class EnterpriseDevice implements Printable, Scannable, Faxable {
        @Override
        public void printDoc(String document) {
            System.out.println("  [Enterprise]     Printing: " + document);
        }

        @Override
        public void scan(String document) {
            System.out.println("  [Enterprise]     Scanning: " + document);
        }

        @Override
        public void fax(String document) {
            System.out.println("  [Enterprise]     Faxing:   " + document);
        }
    }

    /**
     * Each method only asks for the capability it actually needs.
     * printReport() safely accepts all three printers.
     * sendFax() only accepts devices with fax capability.
     */
    static class OfficeSystem {
        void printReport(Printable device, String doc) {
            System.out.println("  Sending to printer...");
            device.printDoc(doc);
        }

        void scanDocument(Scannable device, String doc) {
            System.out.println("  Starting scan...");
            device.scan(doc);
        }

        void sendFax(Faxable device, String doc) {
            System.out.println("  Dialling fax number...");
            device.fax(doc);
        }
    }

    // ─────────────────────────────────────────────
    //  DEMO — violations and fixes
    // ─────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  Interface Segregation Principle     ║");
        System.out.println("╚══════════════════════════════════════╝\n");

        // ── Worker/Robot — Violation ──
        System.out.println("── VIOLATION: fat Worker interface ──");
        HumanWorkerViolation human = new HumanWorkerViolation();
        RobotViolation       robot = new RobotViolation();

        human.work();
        human.eat();
        robot.work();
        robot.eat();   // silent no-op — nothing happens, caller is misled

        try {
            robot.sleep(); // runtime bomb
        } catch (UnsupportedOperationException e) {
            System.out.println("  CRASH → " + e.getMessage());
        }
        System.out.println();

        // ── Worker/Robot — Fix ──
        System.out.println("── FIX: Workable / Feedable / Restable ──\n");

        HumanWorker   akash   = new HumanWorker("Akash");
        HumanWorker   rohan   = new HumanWorker("Rohan");
        Robot         bot     = new Robot("RoboX-3000");
        WorkflowManager manager = new WorkflowManager();

        // All three can work
        manager.assignTask(akash);
        manager.assignTask(rohan);
        manager.assignTask(bot);
        System.out.println();

        // Only humans go to lunch — Robot correctly excluded at compile time
        manager.lunchBreak(akash);
        manager.lunchBreak(rohan);
        // manager.lunchBreak(bot); ← compile-time error — Robot is not Feedable!
        System.out.println();

        bot.recharge();  // Robot's own method — no interface pollution
        System.out.println();

        manager.endOfShift(akash);
        manager.endOfShift(rohan);
        System.out.println();

        // ── Printer — Violation ──
        System.out.println("── VIOLATION: fat MultiFunctionDevice interface ──");
        BasicPrinterViolation badPrinter = new BasicPrinterViolation();
        badPrinter.printDoc("Q3 Report");
        try {
            badPrinter.scan("Q3 Report"); // runtime bomb
        } catch (UnsupportedOperationException e) {
            System.out.println("  CRASH → " + e.getMessage());
        }
        System.out.println();

        // ── Printer — Fix ──
        System.out.println("── FIX: Printable / Scannable / Faxable ──\n");

        BasicPrinter    basic      = new BasicPrinter();
        OfficePrinter   office     = new OfficePrinter();
        EnterpriseDevice enterprise = new EnterpriseDevice();
        OfficeSystem    system     = new OfficeSystem();

        // All three can print
        system.printReport(basic,      "Invoice.pdf");
        system.printReport(office,     "Contract.pdf");
        system.printReport(enterprise, "AnnualReport.pdf");
        System.out.println();

        // Only office and enterprise can scan
        system.scanDocument(office,     "Passport.pdf");
        system.scanDocument(enterprise, "LegalDoc.pdf");
        // system.scanDocument(basic, ...) ← compile error — BasicPrinter not Scannable
        System.out.println();

        // Only enterprise can fax
        system.sendFax(enterprise, "NDA.pdf");
        // system.sendFax(office, ...) ← compile error — OfficePrinter not Faxable
        System.out.println();

        System.out.println("✔ Each class implements only what it can honestly support.");
        System.out.println("✔ Unsupported operations are caught by the compiler — no runtime bombs.");
    }
}

// ============================================================
//  S — Single Responsibility Principle (SRP)
//
//  Principle : A class should have only ONE reason to change.
//
//  Example   : An Employee payroll system.
//              BAD  → one Employee class does everything
//              GOOD → each class owns exactly one concern
// ============================================================

import java.util.HashMap;
import java.util.Map;

public class S_SingleResponsibility {

    // ─────────────────────────────────────────────
    //  VIOLATION — one class doing three jobs
    // ─────────────────────────────────────────────

    /**
     * VIOLATION: This class has three responsibilities:
     *   1. Domain logic   (calculate pay)
     *   2. Persistence    (save to DB)
     *   3. Report         (generate payslip)
     *
     * Any of the three stakeholders — DBA, Finance, HR —
     * can force a change to this single class.
     * One class, too many reasons to change.
     */
    static class EmployeeViolation {
        String name;
        double hourlyRate;

        EmployeeViolation(String name, double hourlyRate) {
            this.name = name;
            this.hourlyRate = hourlyRate;
        }

        // Responsibility 1: business / domain logic
        double calculatePay(int hoursWorked) {
            return hourlyRate * hoursWorked;
        }

        // Responsibility 2: persistence — should NOT live here
        void saveToDatabase() {
            System.out.println("  [DB]  Saving employee '" + name + "' to database...");
        }

        // Responsibility 3: report generation — should NOT live here
        void generatePayslip(int hoursWorked) {
            double pay = calculatePay(hoursWorked);
            System.out.println("  [PDF] Generating payslip for " + name + " | Pay: ₹" + pay);
        }
    }

    // ─────────────────────────────────────────────
    //  FIX — each class owns exactly one concern
    // ─────────────────────────────────────────────

    /**
     * Concern 1: Pure data holder.
     * Changes ONLY when the business definition of 'employee' changes.
     * No logic, no side effects — just data.
     */
    static class Employee {
        String empId;
        String name;
        double hourlyRate;

        Employee(String empId, String name, double hourlyRate) {
            this.empId = empId;
            this.name = name;
            this.hourlyRate = hourlyRate;
        }

        @Override
        public String toString() {
            return "Employee(id=" + empId + ", name=" + name + ", rate=₹" + hourlyRate + ")";
        }
    }

    /**
     * Concern 2: Payroll rules.
     * Changes ONLY when pay rules change
     * (e.g., overtime policy, tax slabs, bonus logic).
     */
    static class PayrollCalculator {
        double calculatePay(Employee employee, int hoursWorked) {
            // Overtime rule: hours beyond 40 are paid at 1.5x
            if (hoursWorked > 40) {
                double regularPay  = employee.hourlyRate * 40;
                double overtimePay = employee.hourlyRate * 1.5 * (hoursWorked - 40);
                return regularPay + overtimePay;
            }
            return employee.hourlyRate * hoursWorked;
        }
    }

    /**
     * Concern 3: Persistence.
     * Changes ONLY when the storage layer changes
     * (e.g., switching DB, changing ORM, schema migration).
     */
    static class EmployeeRepository {
        // Simulating a database with an in-memory Map
        private Map<String, Employee> db = new HashMap<>();

        void save(Employee employee) {
            db.put(employee.empId, employee);
            System.out.println("  [DB]  Saved   → " + employee);
        }

        Employee findById(String empId) {
            Employee employee = db.get(empId);
            if (employee != null) {
                System.out.println("  [DB]  Found   → " + employee);
            } else {
                System.out.println("  [DB]  Employee '" + empId + "' not found.");
            }
            return employee;
        }
    }

    /**
     * Concern 4: Report / payslip generation.
     * Changes ONLY when the payslip format changes
     * (e.g., new branding, legal sections, layout).
     */
    static class PayslipGenerator {
        void generate(Employee employee, double payAmount) {
            System.out.println("  ─────────────────────────────");
            System.out.println("  [PDF] PAYSLIP");
            System.out.println("        Employee : " + employee.name);
            System.out.println("        ID       : " + employee.empId);
            System.out.printf( "        Net Pay  : ₹%.2f%n", payAmount);
            System.out.println("  ─────────────────────────────");
        }
    }

    // ─────────────────────────────────────────────
    //  DEMO — bad vs good design
    // ─────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  Single Responsibility Principle     ║");
        System.out.println("╚══════════════════════════════════════╝\n");

        // ── VIOLATION ──
        System.out.println("── VIOLATION: one class doing everything ──");
        EmployeeViolation badEmp = new EmployeeViolation("Akash", 500.0);
        badEmp.saveToDatabase();
        badEmp.generatePayslip(45);
        System.out.println("  (If DB changes, payslip format changes, OR pay rules change");
        System.out.println("   → same class must be edited. All three risks collide.)\n");

        // ── FIX ──
        System.out.println("── FIX: each class owns one concern ──\n");

        Employee emp1 = new Employee("E01", "Akash", 500.0);
        Employee emp2 = new Employee("E02", "Rohan", 400.0);

        // Each collaborator does only its job
        EmployeeRepository repo       = new EmployeeRepository();
        PayrollCalculator  calculator = new PayrollCalculator();
        PayslipGenerator   payslip    = new PayslipGenerator();

        // Persistence — only EmployeeRepository changes if the DB changes
        repo.save(emp1);
        repo.save(emp2);
        repo.findById("E01");
        repo.findById("E99"); // not found
        System.out.println();

        // Payroll — only PayrollCalculator changes if pay rules change
        int    hours1 = 45; // 5 overtime hours
        int    hours2 = 38; // no overtime
        double pay1   = calculator.calculatePay(emp1, hours1);
        double pay2   = calculator.calculatePay(emp2, hours2);
        System.out.printf("  [Payroll] %s | %d hrs → ₹%.2f  (includes overtime)%n", emp1.name, hours1, pay1);
        System.out.printf("  [Payroll] %s | %d hrs → ₹%.2f%n", emp2.name, hours2, pay2);
        System.out.println();

        // Report generation — only PayslipGenerator changes if format changes
        payslip.generate(emp1, pay1);
        payslip.generate(emp2, pay2);

        System.out.println("\n✔ Each class has exactly one reason to change.");
    }
}

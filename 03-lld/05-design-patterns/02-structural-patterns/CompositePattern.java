import java.util.ArrayList;
import java.util.List;

// =============================================================================
// PATTERN: Composite
// PURPOSE: Compose objects into tree structures to represent part-whole
//          hierarchies. Let clients treat individual objects and
//          compositions of objects uniformly — same interface, same calls.
//
// REAL-WORLD ANALOGY:
//   A file system. A File is a leaf — it has a size, you can open it.
//   A Folder is a composite — it contains files or other folders.
//   When your OS calculates the size of a folder, it recursively sums
//   everything inside. From your perspective, getSize() works the same
//   whether you call it on one file or a folder with 10,000 nested files.
//   That uniformity — same call, different scales — is the Composite pattern.
//
// THE PROBLEM THIS SOLVES:
//   Without Composite, clients write type checks everywhere:
//     if (node instanceof Employee) {
//         total += ((Employee) node).getSalary();
//     } else if (node instanceof Department) {
//         for (Object child : ((Department) node).getChildren()) {
//             // ... recurse manually ...
//         }
//     }
//   This logic spreads across every caller and breaks whenever structure changes.
//
//   With Composite, the client just calls:
//     component.getSalaryCost()   ← works on Employee, Department, or entire Company
//   No type checks. No recursion in client code. The tree handles itself.
//
// THREE INGREDIENTS:
//   1. Component interface → common operations for BOTH leaf and composite
//   2. Leaf               → no children; implements operations with its own data
//   3. Composite          → holds List<Component>; implements operations recursively
// =============================================================================

public class CompositePattern {

    // =========================================================================
    // COMPONENT INTERFACE — the unified contract
    //
    // Both Employee (leaf) and Department (composite) implement this.
    // Client code works with this interface ONLY — never with concrete types.
    // =========================================================================
    interface OrganizationComponent {
        String getName();
        String getRole();
        double getSalaryCost();  // leaf: own salary | composite: sum of entire subtree
        int getHeadCount();      // leaf: 1          | composite: count of all people below
        void display(int depth); // recursive display — depth controls indentation
    }


    // =========================================================================
    // LEAF: Employee
    //
    // A leaf has no children. It implements all operations using its own data.
    // getSalaryCost() → just return own salary (no recursion needed)
    // getHeadCount()  → just return 1 (it IS one person)
    // =========================================================================
    static class Employee implements OrganizationComponent {
        private final String name;
        private final String role;
        private final double salary;
        private final String department;

        public Employee(String name, String role, double salary, String department) {
            this.name       = name;
            this.role       = role;
            this.salary     = salary;
            this.department = department;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getRole() { return role; }

        @Override
        public double getSalaryCost() {
            return salary; // leaf: just my own salary — no recursion
        }

        @Override
        public int getHeadCount() {
            return 1; // leaf: I am one person
        }

        @Override
        public void display(int depth) {
            // Indent based on depth to show tree structure visually
            String indent = "  ".repeat(depth);
            System.out.printf("%s👤 %-20s %-25s ₹%,.0f%n",
                    indent, name, "(" + role + ")", salary);
        }
    }


    // =========================================================================
    // COMPOSITE: Department
    //
    // A composite holds a list of children — which are OrganizationComponents.
    // CRITICAL: The list type is OrganizationComponent, NOT Employee or Department.
    // This is what makes the tree work uniformly at any depth.
    //
    // getSalaryCost() → sum getSalaryCost() of ALL children (recursive)
    // getHeadCount()  → sum getHeadCount()  of ALL children (recursive)
    //
    // Whether a child is a leaf (Employee) or another composite (Department),
    // the call is identical — that's polymorphism enabling the recursion.
    // =========================================================================
    static class Department implements OrganizationComponent {
        private final String name;
        private final String headRole;  // e.g., "Engineering Lead", "VP of Product"

        // The list holds OrganizationComponent — NOT Employee or Department separately.
        // This is what allows a Department to contain both Employees AND sub-Departments.
        private final List<OrganizationComponent> children = new ArrayList<>();

        public Department(String name, String headRole) {
            this.name     = name;
            this.headRole = headRole;
        }

        // Children management — only composites can have children
        public void add(OrganizationComponent component) {
            children.add(component);
        }

        public void remove(OrganizationComponent component) {
            children.remove(component);
        }

        public List<OrganizationComponent> getChildren() {
            return children;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getRole() { return headRole; }

        @Override
        public double getSalaryCost() {
            // RECURSIVE: ask each child for their cost.
            // Each child handles it themselves — leaf returns salary, composite recurses further.
            // No type-checking needed — it just works via the interface.
            return children.stream()
                           .mapToDouble(OrganizationComponent::getSalaryCost)
                           .sum();
        }

        @Override
        public int getHeadCount() {
            // RECURSIVE: ask each child for their headcount.
            // Leaf returns 1. Composite returns sum of all its children's headcounts.
            return children.stream()
                           .mapToInt(OrganizationComponent::getHeadCount)
                           .sum();
        }

        @Override
        public void display(int depth) {
            String indent = "  ".repeat(depth);
            System.out.printf("%s🏢 %-20s %-25s Cost: ₹%,.0f  | People: %d%n",
                    indent, name, "(" + headRole + ")", getSalaryCost(), getHeadCount());

            // Recursively display all children, indented one level deeper
            for (OrganizationComponent child : children) {
                child.display(depth + 1); // ← same method on both leaf and composite
            }
        }
    }


    // =========================================================================
    // REPORTING UTILITY — demonstrates treating everything uniformly
    //
    // This works with the OrganizationComponent interface — never cares
    // whether it receives a leaf, a sub-department, or the entire company.
    // =========================================================================
    static class ReportGenerator {

        // Same logic works on an individual, a team, or the whole company
        public void printCostReport(OrganizationComponent component) {
            System.out.println("  Cost Report for: " + component.getName());
            System.out.printf("  ├─ Total Salary Cost : ₹%,.0f%n", component.getSalaryCost());
            System.out.printf("  ├─ Headcount         : %d people%n", component.getHeadCount());
            System.out.printf("  └─ Avg Salary        : ₹%,.0f%n",
                    component.getHeadCount() > 0
                            ? component.getSalaryCost() / component.getHeadCount()
                            : 0);
        }

        // Find the most expensive subtree among direct children of a composite
        public void printMostExpensiveUnit(Department department) {
            OrganizationComponent mostExpensive = null;
            for (OrganizationComponent child : department.getChildren()) {
                if (mostExpensive == null
                        || child.getSalaryCost() > mostExpensive.getSalaryCost()) {
                    mostExpensive = child;
                }
            }
            if (mostExpensive != null) {
                System.out.println("  Most expensive unit under " + department.getName()
                        + ": " + mostExpensive.getName()
                        + " (₹" + String.format("%,.0f", mostExpensive.getSalaryCost()) + ")");
            }
        }
    }


    // =========================================================================
    // MAIN — builds a company org chart and demonstrates uniform operations
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Composite Pattern Demo ===\n");

        // ── Build the leaf nodes (individual contributors) ────────────────────
        Employee alice  = new Employee("Alice",  "Backend Engineer",    120000, "Engineering");
        Employee bob    = new Employee("Bob",    "Frontend Engineer",   110000, "Engineering");
        Employee carol  = new Employee("Carol",  "ML Engineer",         145000, "Engineering");
        Employee dave   = new Employee("Dave",   "QA Engineer",          90000, "Engineering");
        Employee eve    = new Employee("Eve",    "Product Manager",     125000, "Product");
        Employee frank  = new Employee("Frank",  "UX Designer",         100000, "Product");
        Employee grace  = new Employee("Grace",  "Data Analyst",        115000, "Data");
        Employee henry  = new Employee("Henry",  "Data Engineer",       130000, "Data");
        Employee ivan   = new Employee("Ivan",   "DevOps Engineer",     135000, "Infra");
        Employee julia  = new Employee("Julia",  "Security Engineer",   140000, "Infra");


        // ── Build sub-departments (composites containing leaves) ───────────────
        Department engineering = new Department("Engineering", "VP of Engineering");
        engineering.add(alice);
        engineering.add(bob);
        engineering.add(carol);
        engineering.add(dave);

        Department product = new Department("Product", "VP of Product");
        product.add(eve);
        product.add(frank);

        Department data = new Department("Data", "Head of Data");
        data.add(grace);
        data.add(henry);

        Department infrastructure = new Department("Infrastructure", "Head of Infra");
        infrastructure.add(ivan);
        infrastructure.add(julia);


        // ── Build Technology division (composite containing composites) ────────
        // A Department containing other Departments — the tree goes deeper
        Department technology = new Department("Technology", "CTO");
        technology.add(engineering);    // adding a Department inside a Department
        technology.add(infrastructure); // works seamlessly — same interface

        Department productAndData = new Department("Product & Data", "CPO");
        productAndData.add(product);
        productAndData.add(data);


        // ── Build the top-level company (composite of composites of composites) ─
        Department company = new Department("Arcesium Corp", "CEO");
        company.add(technology);
        company.add(productAndData);


        // ── Display full org chart ─────────────────────────────────────────────
        System.out.println("─── Full Org Chart ──────────────────────────────────────────────");
        company.display(0); // single call on the root — recursion handles the rest


        // ── Demonstrate uniform interface ─────────────────────────────────────
        System.out.println("\n─── Uniform Interface: same call on leaf, department, company ────");
        ReportGenerator reporter = new ReportGenerator();

        // These three calls look identical — client doesn't know or care about depth
        System.out.println("\n  On a leaf (individual):");
        reporter.printCostReport(alice);

        System.out.println("\n  On a sub-department:");
        reporter.printCostReport(engineering);

        System.out.println("\n  On a division (composite of composites):");
        reporter.printCostReport(technology);

        System.out.println("\n  On the entire company:");
        reporter.printCostReport(company);


        // ── Cost breakdown comparison ─────────────────────────────────────────
        System.out.println("\n─── Cost Breakdown by Division ───────────────────────────────────");
        for (OrganizationComponent child : company.getChildren()) {
            System.out.printf("  %-20s : ₹%,.0f  (%d people)%n",
                    child.getName(),
                    child.getSalaryCost(),
                    child.getHeadCount());
        }
        System.out.printf("  %-20s : ₹%,.0f  (%d people)%n",
                "TOTAL",
                company.getSalaryCost(),
                company.getHeadCount());


        // ── Finding the most expensive unit ──────────────────────────────────
        System.out.println("\n─── Most Expensive Units ──────────────────────────────────────────");
        reporter.printMostExpensiveUnit(company);
        reporter.printMostExpensiveUnit(technology);
        reporter.printMostExpensiveUnit(engineering);


        // ── Dynamic modification — add/remove at runtime ──────────────────────
        System.out.println("\n─── Dynamic Modification ─────────────────────────────────────────");
        System.out.printf("  Company headcount before hire: %d%n", company.getHeadCount());

        Employee newHire = new Employee("Kiran", "Staff Engineer", 160000, "Engineering");
        engineering.add(newHire);

        // The entire tree above automatically reflects this change
        // No cache to invalidate, no aggregations to recalculate manually
        System.out.printf("  Company headcount after hire:  %d%n", company.getHeadCount());
        System.out.printf("  Engineering cost after hire:   ₹%,.0f%n", engineering.getSalaryCost());
        System.out.printf("  Technology cost after hire:    ₹%,.0f%n", technology.getSalaryCost());
        System.out.printf("  Company total after hire:      ₹%,.0f%n", company.getSalaryCost());

        System.out.println("\n  Removing Dave from Engineering...");
        engineering.remove(dave);
        System.out.printf("  Engineering headcount: %d%n", engineering.getHeadCount());
        System.out.printf("  Engineering cost:      ₹%,.0f%n", engineering.getSalaryCost());


        // ── Without Composite — showing what clients would have to do ──────────
        System.out.println("\n─── What Client Code Looks Like (with vs without Composite) ─────");
        System.out.println("""
  WITHOUT Composite (type checks everywhere):
    if (node instanceof Employee) {
        total += ((Employee) node).getSalary();
    } else if (node instanceof Department) {
        for (Object child : ((Department) node).getMembers()) {
            if (child instanceof Employee) { ... }
            else if (child instanceof Department) { ... recurse manually ... }
        }
    }
    → Every caller must know about leaf vs composite
    → Adding a new node type breaks all callers

  WITH Composite:
    total += component.getSalaryCost();  ← works on anything
    component.display(0);               ← works on anything
    → Client never knows (or cares) what it's talking to""");


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. List<OrganizationComponent> — not List<Employee> — enables uniform recursion");
        System.out.println("  2. Client code is identical for leaf, subtree, or entire tree");
        System.out.println("  3. Recursion lives in the Composite — never in client code");
        System.out.println("  4. Tree modifications automatically propagate upward through aggregations");
        System.out.println("  5. Real-world uses: file systems, UI component trees, HTML DOM, menus");
    }
}

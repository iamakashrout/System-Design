// ============================================================
// OOP Principle 3: INHERITANCE
// "Is-a relationships and shared contracts"
//
// Demonstrated through two hierarchies:
//   1. Vehicle → Car / ElectricCar  (override + extend with super)
//   2. Employee → Manager / Engineer (abstract class + template method)
//
// Shows: inherit as-is, override, extend with super(),
//        abstract methods, and the is-a relationship in action.
// ============================================================

import java.util.ArrayList;
import java.util.List;

public class Inheritance {

    // ════════════════════════════════════════════════════════
    // HIERARCHY 1: Vehicle
    // ════════════════════════════════════════════════════════

    static class Vehicle {
        protected String brand;
        protected String model;
        protected int year;
        protected double fuelLevel; // percentage 0–100

        public Vehicle(String brand, String model, int year) {
            this.brand = brand;
            this.model = model;
            this.year = year;
            this.fuelLevel = 100.0;
            System.out.println("[Vehicle Created] " + year + " " + brand + " " + model);
        }

        // ── Inherited as-is by most subclasses ──
        public void startEngine() {
            System.out.println("  [" + brand + " " + model + "] Engine started. Vroom!");
        }

        public void stopEngine() {
            System.out.println("  [" + brand + " " + model + "] Engine stopped.");
        }

        // ── Can be overridden ──
        public void refuel(double amount) {
            fuelLevel = Math.min(100, fuelLevel + amount);
            System.out.println("  [" + brand + " " + model + "] Refueled. Fuel level: " + fuelLevel + "%");
        }

        public void printStatus() {
            System.out.println("  Status → " + year + " " + brand + " " + model
                + " | Fuel: " + fuelLevel + "%");
        }

        public String getBrandModel() { return brand + " " + model; }
    }


    // ── Subclass 1: Car — inherits startEngine, stopEngine as-is ──
    static class Car extends Vehicle {
        private int numberOfDoors;
        private double mileage; // km driven

        public Car(String brand, String model, int year, int numberOfDoors) {
            super(brand, model, year);  // parent constructor runs first
            this.numberOfDoors = numberOfDoors;
            this.mileage = 0;
        }

        // New behavior added by Car — not in Vehicle
        public void drive(double km) {
            if (fuelLevel <= 0) {
                System.out.println("  [" + getBrandModel() + "] Cannot drive — no fuel!");
                return;
            }
            double fuelUsed = km * 0.08; // 8L per 100km
            fuelLevel = Math.max(0, fuelLevel - fuelUsed);
            mileage += km;
            System.out.println("  [" + getBrandModel() + "] Drove " + km + " km."
                + " Fuel remaining: " + String.format("%.1f", fuelLevel) + "%"
                + " | Total mileage: " + mileage + " km");
        }

        @Override
        public void printStatus() {
            super.printStatus();  // call parent's printStatus first
            System.out.println("  Car extras → Doors: " + numberOfDoors
                + " | Mileage: " + mileage + " km");
        }
    }


    // ── Subclass 2: ElectricCar — overrides refuel (charges instead) ──
    static class ElectricCar extends Vehicle {
        private double batteryCapacityKWh;
        private double chargeLevel; // percentage 0–100

        public ElectricCar(String brand, String model, int year, double batteryCapacityKWh) {
            super(brand, model, year);
            this.batteryCapacityKWh = batteryCapacityKWh;
            this.chargeLevel = 80.0;
            this.fuelLevel = 0; // Electric cars don't use fuel
        }

        // Override startEngine — electric cars have a different startup
        @Override
        public void startEngine() {
            System.out.println("  [" + getBrandModel() + "] Motor activated silently. Ready.");
        }

        // Override refuel — electric cars don't refuel, they charge
        @Override
        public void refuel(double amount) {
            chargeLevel = Math.min(100, chargeLevel + amount);
            System.out.println("  [" + getBrandModel() + "] Charging... Battery: " + chargeLevel + "%");
        }

        public void drive(double km) {
            double energyUsed = (km / 100) * 15; // 15 kWh per 100 km
            double chargeUsed = (energyUsed / batteryCapacityKWh) * 100;
            chargeLevel = Math.max(0, chargeLevel - chargeUsed);
            System.out.println("  [" + getBrandModel() + "] Drove " + km + " km electrically."
                + " Battery: " + String.format("%.1f", chargeLevel) + "%");
        }

        @Override
        public void printStatus() {
            System.out.println("  Status → " + year + " " + brand + " " + model
                + " | Battery: " + String.format("%.1f", chargeLevel) + "%"
                + " | Capacity: " + batteryCapacityKWh + " kWh");
        }
    }


    // ════════════════════════════════════════════════════════
    // HIERARCHY 2: Employee
    // Shows abstract class enforcing contract on subclasses
    // ════════════════════════════════════════════════════════

    static abstract class Employee {
        protected String name;
        protected String employeeId;
        protected double baseSalary;

        public Employee(String name, String employeeId, double baseSalary) {
            this.name = name;
            this.employeeId = employeeId;
            this.baseSalary = baseSalary;
            System.out.println("[Employee Hired] " + name + " | ID: " + employeeId
                + " | Base Salary: ₹" + baseSalary);
        }

        // Shared behavior — same for all employees
        public void clockIn() {
            System.out.println("  [" + name + "] Clocked in at 9:00 AM.");
        }

        public void clockOut() {
            System.out.println("  [" + name + "] Clocked out at 6:00 PM.");
        }

        // Template method — skeleton is fixed, subclasses fill in the parts
        public final void performMonthlyReview() {
            System.out.println("  [Review: " + name + "] Starting monthly review...");
            assessPerformance();            // subclass-specific
            double bonus = calculateBonus(); // subclass-specific
            System.out.println("  [Review: " + name + "] Bonus approved: ₹" + bonus);
            sendReviewReport();             // shared
        }

        private void sendReviewReport() {
            System.out.println("  [Review: " + name + "] Report sent to HR.");
        }

        // Subclasses MUST implement these
        public abstract double calculateBonus();
        public abstract void assessPerformance();
        public abstract String getRole();

        public void printInfo() {
            System.out.println("  [" + getRole() + "] " + name
                + " | ID: " + employeeId
                + " | Base: ₹" + baseSalary
                + " | Bonus: ₹" + calculateBonus());
        }
    }


    static class Manager extends Employee {
        private int teamSize;
        private double performanceRating; // 0.0 to 5.0

        public Manager(String name, String employeeId, double baseSalary, int teamSize) {
            super(name, employeeId, baseSalary);
            this.teamSize = teamSize;
            this.performanceRating = 4.2;
        }

        @Override
        public void assessPerformance() {
            System.out.println("  [Manager " + name + "] Assessing team delivery,"
                + " OKRs, and stakeholder feedback. Rating: " + performanceRating + "/5.0");
        }

        @Override
        public double calculateBonus() {
            // Managers get 20% base + ₹5,000 per team member
            return baseSalary * 0.20 + teamSize * 5000;
        }

        @Override
        public String getRole() { return "Manager"; }
    }


    static class Engineer extends Employee {
        private int bugsFixed;
        private int featuresDelivered;

        public Engineer(String name, String employeeId, double baseSalary) {
            super(name, employeeId, baseSalary);
            this.bugsFixed = 0;
            this.featuresDelivered = 0;
        }

        public void fixBug() {
            bugsFixed++;
            System.out.println("  [Engineer " + name + "] Bug fixed. Total bugs fixed: " + bugsFixed);
        }

        public void deliverFeature(String featureName) {
            featuresDelivered++;
            System.out.println("  [Engineer " + name + "] Feature delivered: " + featureName
                + " | Total features: " + featuresDelivered);
        }

        @Override
        public void assessPerformance() {
            System.out.println("  [Engineer " + name + "] Assessing code quality,"
                + " bugs fixed: " + bugsFixed
                + ", features: " + featuresDelivered);
        }

        @Override
        public double calculateBonus() {
            // Engineers get ₹2,000 per bug + ₹10,000 per feature
            return bugsFixed * 2000 + featuresDelivered * 10000;
        }

        @Override
        public String getRole() { return "Engineer"; }
    }


    // ── Main ──────────────────────────────────────────────────
    public static void main(String[] args) {

        System.out.println("═══════════════════════════════════════");
        System.out.println("  OOP PRINCIPLE 3: INHERITANCE");
        System.out.println("═══════════════════════════════════════\n");

        // ── Demo 1: Car inherits from Vehicle ──
        System.out.println(">>> Demo 1: Car — inherits and extends Vehicle");
        Car honda = new Car("Honda", "City", 2023, 4);
        honda.startEngine();    // inherited from Vehicle, used as-is
        honda.drive(120);       // Car's own method
        honda.drive(60);
        honda.refuel(20);       // inherited from Vehicle, used as-is
        honda.stopEngine();     // inherited
        honda.printStatus();    // extended — calls super.printStatus() then adds more

        // ── Demo 2: ElectricCar overrides behavior ──
        System.out.println("\n>>> Demo 2: ElectricCar — overrides startEngine and refuel");
        ElectricCar tesla = new ElectricCar("Tesla", "Model 3", 2024, 75.0);
        tesla.startEngine();    // overridden — silent startup
        tesla.drive(200);
        tesla.refuel(30);       // overridden — charges battery instead of fuel
        tesla.printStatus();

        // ── Demo 3: IS-A relationship — polymorphic list ──
        System.out.println("\n>>> Demo 3: IS-A in Action — All are Vehicles");
        List<Vehicle> fleet = new ArrayList<>();
        fleet.add(new Car("Toyota", "Camry", 2022, 4));
        fleet.add(new ElectricCar("Tata", "Nexon EV", 2024, 40.5));
        fleet.add(new Car("Hyundai", "Creta", 2023, 4));

        System.out.println("  Starting all vehicles in fleet:");
        for (Vehicle v : fleet) {
            v.startEngine(); // right startEngine() fires for each
        }

        // ── Demo 4: Employee hierarchy ──
        System.out.println("\n>>> Demo 4: Employee — Abstract Class with Template Method");
        Manager mgr = new Manager("Ravi Kumar", "MGR-101", 150000, 8);
        Engineer eng = new Engineer("Akash Singh", "ENG-202", 100000);

        eng.fixBug();
        eng.fixBug();
        eng.fixBug();
        eng.deliverFeature("Payment Gateway Integration");
        eng.deliverFeature("Real-time Notifications");

        mgr.clockIn();
        mgr.clockOut();

        System.out.println("\n  Monthly Reviews:");
        mgr.performMonthlyReview();   // template method — fixed skeleton
        eng.performMonthlyReview();   // template method — different steps, same skeleton

        System.out.println("\n  Employee Info:");
        mgr.printInfo();
        eng.printInfo();

        System.out.println("\n✔ Inheritance enables reuse, specialization, and IS-A relationships.");
    }
}

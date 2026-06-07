import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Parking Lot System — Phase 5, Problem 1
 *
 * Demonstrates: Strategy (pricing + allocation), Singleton (manager),
 *               Composition (lot → floor → spot), Abstract class (Vehicle),
 *               Thread-safe design (ReentrantLock per floor + synchronized spots)
 *
 * Design philosophy: ParkingLotManager orchestrates but delegates every
 * decision to injected strategies. This makes the core flow stable while
 * pricing and allocation rules change independently.
 */
public class ParkingLot {

    // =========================================================================
    // ENUMS
    // =========================================================================

    enum VehicleType { BIKE, CAR, TRUCK }

    /**
     * SpotType maps to physical spot size.
     * The mapping from VehicleType → SpotType is NOT encoded here —
     * that belongs in SpotAllocationStrategy so it can be changed.
     */
    enum SpotType { COMPACT, REGULAR, LARGE }

    // =========================================================================
    // VEHICLE HIERARCHY
    // =========================================================================

    /**
     * Abstract base: all vehicles share licensePlate + vehicleType.
     * Abstract class (not interface) because shared state lives here.
     */
    static abstract class Vehicle {
        private final String licensePlate;
        private final VehicleType vehicleType;

        Vehicle(String licensePlate, VehicleType vehicleType) {
            this.licensePlate = licensePlate;
            this.vehicleType = vehicleType;
        }

        String getLicensePlate() { return licensePlate; }
        VehicleType getVehicleType() { return vehicleType; }

        @Override
        public String toString() {
            return vehicleType + "[" + licensePlate + "]";
        }
    }

    static class Bike  extends Vehicle { Bike(String plate)  { super(plate, VehicleType.BIKE);  } }
    static class Car   extends Vehicle { Car(String plate)   { super(plate, VehicleType.CAR);   } }
    static class Truck extends Vehicle { Truck(String plate) { super(plate, VehicleType.TRUCK); } }

    // =========================================================================
    // PARKING SPOT
    // =========================================================================

    /**
     * A single physical spot. Its only job: know its type and
     * safely transition between occupied ↔ free.
     *
     * synchronized on occupy/release because the ParkingFloor's ReentrantLock
     * guards the "find + assign" compound operation, but we still want
     * individual spot state transitions to be safe for future use cases
     * (e.g., an admin releasing a spot directly).
     */
    static class ParkingSpot {
        private final String spotId;
        private final SpotType spotType;
        private volatile boolean isOccupied;  // volatile: safe visibility across threads

        ParkingSpot(String spotId, SpotType spotType) {
            this.spotId = spotId;
            this.spotType = spotType;
            this.isOccupied = false;
        }

        String getSpotId()     { return spotId; }
        SpotType getSpotType() { return spotType; }
        boolean isOccupied()   { return isOccupied; }

        // synchronized: guard the actual state transition
        synchronized boolean occupy() {
            if (isOccupied) return false;
            isOccupied = true;
            return true;
        }

        synchronized void release() {
            isOccupied = false;
        }

        @Override
        public String toString() {
            return spotId + "(" + spotType + "," + (isOccupied ? "OCCUPIED" : "FREE") + ")";
        }
    }

    // =========================================================================
    // TICKET — immutable value object
    // =========================================================================

    /**
     * Ticket records a parking session. Immutable at entry — no setters
     * except for recording exit time when the car leaves.
     *
     * Why not fully immutable? exitTime doesn't exist at creation.
     * We use a single controlled setter for it, called only by exitVehicle().
     */
    static class Ticket {
        private final String ticketId;
        private final Vehicle vehicle;
        private final ParkingSpot spot;
        private final LocalDateTime entryTime;
        private LocalDateTime exitTime;  // set exactly once, at exit

        Ticket(String ticketId, Vehicle vehicle, ParkingSpot spot, LocalDateTime entryTime) {
            this.ticketId = ticketId;
            this.vehicle = vehicle;
            this.spot = spot;
            this.entryTime = entryTime;
        }

        String getTicketId()         { return ticketId; }
        Vehicle getVehicle()         { return vehicle; }
        ParkingSpot getSpot()        { return spot; }
        LocalDateTime getEntryTime() { return entryTime; }
        LocalDateTime getExitTime()  { return exitTime; }

        void setExitTime(LocalDateTime exitTime) {
            if (this.exitTime != null) throw new IllegalStateException("Exit already recorded");
            this.exitTime = exitTime;
        }

        long getParkingDurationHours() {
            if (exitTime == null) throw new IllegalStateException("Vehicle hasn't exited yet");
            // Minimum charge: 1 hour
            return Math.max(1, ChronoUnit.HOURS.between(entryTime, exitTime));
        }

        @Override
        public String toString() {
            return "Ticket{id=" + ticketId + ", vehicle=" + vehicle
                   + ", spot=" + spot.getSpotId() + ", entry=" + entryTime + "}";
        }
    }

    // =========================================================================
    // STRATEGY: PRICING
    // =========================================================================

    /**
     * PricingStrategy is the central extensibility point for fees.
     * The manager never knows hourly rates; it only calls calculateFee().
     */
    interface PricingStrategy {
        double calculateFee(Ticket ticket);
    }

    /**
     * Hourly pricing: different rates per spot type.
     * COMPACT < REGULAR < LARGE — bigger spots cost more.
     */
    static class HourlyPricingStrategy implements PricingStrategy {
        private final Map<SpotType, Double> hourlyRates;

        HourlyPricingStrategy() {
            hourlyRates = new EnumMap<>(SpotType.class);
            hourlyRates.put(SpotType.COMPACT, 20.0);   // ₹20/hour for bikes
            hourlyRates.put(SpotType.REGULAR, 40.0);   // ₹40/hour for cars
            hourlyRates.put(SpotType.LARGE,   80.0);   // ₹80/hour for trucks
        }

        @Override
        public double calculateFee(Ticket ticket) {
            SpotType spotType = ticket.getSpot().getSpotType();
            double rate = hourlyRates.getOrDefault(spotType, 40.0);
            return rate * ticket.getParkingDurationHours();
        }
    }

    // =========================================================================
    // STRATEGY: SPOT ALLOCATION
    // =========================================================================

    /**
     * SpotAllocationStrategy answers: "Given this vehicle type, which spot type
     * should I look for?"
     *
     * Extracting this as a strategy means we can later add rules like:
     * "premium members' bikes can use REGULAR spots" without changing
     * ParkingLotManager or ParkingFloor.
     */
    interface SpotAllocationStrategy {
        SpotType getRequiredSpotType(VehicleType vehicleType);
    }

    static class DefaultAllocationStrategy implements SpotAllocationStrategy {
        @Override
        public SpotType getRequiredSpotType(VehicleType vehicleType) {
            return switch (vehicleType) {
                case BIKE  -> SpotType.COMPACT;
                case CAR   -> SpotType.REGULAR;
                case TRUCK -> SpotType.LARGE;
            };
        }
    }

    // =========================================================================
    // PARKING FLOOR
    // =========================================================================

    /**
     * ParkingFloor manages all spots on one level.
     *
     * Critical concurrency note: findAvailableSpot() uses a ReentrantLock
     * to make the "find + occupy" compound operation atomic.
     *
     * Without the lock:
     *   Thread A: finds spot S free  (not yet occupied)
     *   Thread B: finds spot S free  (same spot!)
     *   Thread A: occupies spot S
     *   Thread B: occupies spot S  → double assignment, two tickets for one spot
     *
     * With the lock: only one thread enters the find+occupy critical section
     * per floor at a time. Different floors can run concurrently.
     */
    static class ParkingFloor {
        private final int floorNumber;
        private final List<ParkingSpot> spots;
        private final ReentrantLock lock;  // per-floor lock — not system-wide

        ParkingFloor(int floorNumber, List<ParkingSpot> spots) {
            this.floorNumber = floorNumber;
            this.spots = Collections.unmodifiableList(spots);
            this.lock = new ReentrantLock();
        }

        int getFloorNumber() { return floorNumber; }

        /**
         * Find and occupy an available spot of the required type.
         * Returns the spot if found, empty if this floor is full for that type.
         *
         * The lock makes the check-then-act atomic.
         */
        Optional<ParkingSpot> findAndOccupySpot(SpotType required) {
            lock.lock();
            try {
                for (ParkingSpot spot : spots) {
                    if (spot.getSpotType() == required && spot.occupy()) {
                        return Optional.of(spot);
                    }
                }
                return Optional.empty();
            } finally {
                lock.unlock();  // always release, even if exception thrown
            }
        }

        long countAvailable(SpotType type) {
            return spots.stream()
                .filter(s -> s.getSpotType() == type && !s.isOccupied())
                .count();
        }

        List<ParkingSpot> getAllSpots() { return spots; }
    }

    // =========================================================================
    // PARKING LOT — Physical aggregate
    // =========================================================================

    /**
     * ParkingLot holds the physical structure. It doesn't know about pricing
     * or vehicle-to-spot mapping — those are the manager's concern.
     */
    static class ParkingLotStructure {
        private final String lotId;
        private final String address;
        private final List<ParkingFloor> floors;

        ParkingLotStructure(String lotId, String address, List<ParkingFloor> floors) {
            this.lotId = lotId;
            this.address = address;
            this.floors = Collections.unmodifiableList(floors);
        }

        String getLotId()             { return lotId; }
        String getAddress()           { return address; }
        List<ParkingFloor> getFloors() { return floors; }
    }

    // =========================================================================
    // FACTORY: builds the lot structure
    // =========================================================================

    /**
     * ParkingLotFactory isolates construction from logic.
     * Real-world: this data would come from a database or config file.
     */
    static class ParkingLotFactory {
        static ParkingLotStructure createDefaultLot() {
            List<ParkingFloor> floors = new ArrayList<>();

            for (int f = 1; f <= 3; f++) {
                List<ParkingSpot> spots = new ArrayList<>();
                // Each floor: 4 compact, 6 regular, 2 large
                for (int i = 1; i <= 4; i++)
                    spots.add(new ParkingSpot("F" + f + "-C" + i, SpotType.COMPACT));
                for (int i = 1; i <= 6; i++)
                    spots.add(new ParkingSpot("F" + f + "-R" + i, SpotType.REGULAR));
                for (int i = 1; i <= 2; i++)
                    spots.add(new ParkingSpot("F" + f + "-L" + i, SpotType.LARGE));
                floors.add(new ParkingFloor(f, spots));
            }

            return new ParkingLotStructure("LOT-001", "Cyber Tower, Hyderabad", floors);
        }
    }

    // =========================================================================
    // PARKING LOT MANAGER — Singleton orchestrator
    // =========================================================================

    /**
     * ParkingLotManager is the single entry point for all operations.
     * It wires together the physical structure, pricing, and allocation.
     *
     * Why Singleton here?
     * There is one parking lot system. All entry/exit points share the same
     * spot inventory. A second manager instance would have a stale view of
     * availability, leading to double-booking.
     *
     * Thread-safety of Singleton: uses double-checked locking with volatile
     * (same pattern from Phase 4 — DoubleCheckedLocking.java).
     */
    static class ParkingLotManager {
        private static volatile ParkingLotManager instance;  // volatile: prevents instruction reorder

        private final ParkingLotStructure lot;
        private final PricingStrategy pricingStrategy;
        private final SpotAllocationStrategy allocationStrategy;
        private final Map<String, Ticket> activeTickets;  // ticketId → Ticket
        private int ticketCounter;

        // Private constructor: prevents external instantiation
        private ParkingLotManager(ParkingLotStructure lot,
                                   PricingStrategy pricing,
                                   SpotAllocationStrategy allocation) {
            this.lot = lot;
            this.pricingStrategy = pricing;
            this.allocationStrategy = allocation;
            this.activeTickets = new HashMap<>();
            this.ticketCounter = 1000;
        }

        /**
         * Double-checked locking: first check avoids the lock overhead once
         * initialized; second check inside the synchronized block prevents
         * two threads from both seeing null and both constructing the instance.
         */
        static ParkingLotManager getInstance() {
            if (instance == null) {
                synchronized (ParkingLotManager.class) {
                    if (instance == null) {
                        ParkingLotStructure lot = ParkingLotFactory.createDefaultLot();
                        instance = new ParkingLotManager(
                            lot,
                            new HourlyPricingStrategy(),
                            new DefaultAllocationStrategy()
                        );
                    }
                }
            }
            return instance;
        }

        /**
         * Entry flow:
         * 1. Ask allocation strategy: what spot type does this vehicle need?
         * 2. Walk floors in order, asking each floor to find + occupy a spot.
         * 3. If found: create ticket, record it, return it.
         * 4. If no floor has a suitable spot: lot is full for this vehicle type.
         */
        synchronized Ticket parkVehicle(Vehicle vehicle) {
            SpotType required = allocationStrategy.getRequiredSpotType(vehicle.getVehicleType());

            for (ParkingFloor floor : lot.getFloors()) {
                Optional<ParkingSpot> spot = floor.findAndOccupySpot(required);
                if (spot.isPresent()) {
                    String ticketId = "TKT-" + (++ticketCounter);
                    Ticket ticket = new Ticket(ticketId, vehicle, spot.get(), LocalDateTime.now());
                    activeTickets.put(ticketId, ticket);
                    System.out.println("[ENTRY] " + vehicle + " assigned " + spot.get()
                                       + " on floor " + floor.getFloorNumber()
                                       + " | Ticket: " + ticketId);
                    return ticket;
                }
            }

            throw new IllegalStateException("Parking full for vehicle type: " + vehicle.getVehicleType());
        }

        /**
         * Exit flow:
         * 1. Look up the ticket.
         * 2. Record exit time on the ticket.
         * 3. Calculate fee via the pricing strategy.
         * 4. Release the spot.
         * 5. Remove ticket from active map.
         */
        synchronized double exitVehicle(String ticketId) {
            Ticket ticket = activeTickets.get(ticketId);
            if (ticket == null) throw new IllegalArgumentException("Unknown ticket: " + ticketId);

            ticket.setExitTime(LocalDateTime.now());
            double fee = pricingStrategy.calculateFee(ticket);

            ticket.getSpot().release();
            activeTickets.remove(ticketId);

            System.out.printf("[EXIT]  %s | Duration: %d hour(s) | Fee: ₹%.2f%n",
                ticket.getVehicle(), ticket.getParkingDurationHours(), fee);
            return fee;
        }

        void printAvailability() {
            System.out.println("\n--- Availability Report ---");
            for (ParkingFloor floor : lot.getFloors()) {
                System.out.printf("Floor %d: COMPACT=%d, REGULAR=%d, LARGE=%d%n",
                    floor.getFloorNumber(),
                    floor.countAvailable(SpotType.COMPACT),
                    floor.countAvailable(SpotType.REGULAR),
                    floor.countAvailable(SpotType.LARGE));
            }
            System.out.println("---------------------------\n");
        }
    }

    // =========================================================================
    // DEMO DRIVER
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Parking Lot System Demo ===\n");

        ParkingLotManager manager = ParkingLotManager.getInstance();
        manager.printAvailability();

        // --- Basic single-threaded flow ---
        System.out.println("--- Single-threaded parking flow ---");

        Vehicle bike1  = new Bike("KA-01-HH-1234");
        Vehicle car1   = new Car("MH-02-AB-5678");
        Vehicle truck1 = new Truck("DL-03-XY-9999");
        Vehicle car2   = new Car("TN-04-CD-1111");

        Ticket t1 = manager.parkVehicle(bike1);
        Ticket t2 = manager.parkVehicle(car1);
        Ticket t3 = manager.parkVehicle(truck1);
        Ticket t4 = manager.parkVehicle(car2);

        manager.printAvailability();

        // Simulate time passing before exit (without actually sleeping 1 hour,
        // we force a 1-hour minimum in getParkingDurationHours())
        manager.exitVehicle(t1.getTicketId());
        manager.exitVehicle(t2.getTicketId());

        manager.printAvailability();

        // --- Concurrent entry/exit ---
        System.out.println("--- Concurrent parking (8 cars, 4 threads) ---");

        Thread[] threads = new Thread[8];
        Ticket[] tickets = new Ticket[8];

        // 8 cars trying to park concurrently
        for (int i = 0; i < 8; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                Vehicle car = new Car("CONCURRENT-" + idx);
                try {
                    tickets[idx] = manager.parkVehicle(car);
                } catch (IllegalStateException e) {
                    System.out.println("  [FULL] " + car + " could not park: " + e.getMessage());
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        manager.printAvailability();

        // Exit remaining concurrent cars
        System.out.println("--- Exiting concurrent cars ---");
        for (Ticket t : tickets) {
            if (t != null) manager.exitVehicle(t.getTicketId());
        }

        // Exit remaining from single-threaded section
        manager.exitVehicle(t3.getTicketId());
        manager.exitVehicle(t4.getTicketId());

        manager.printAvailability();

        // --- Parking lot full scenario ---
        System.out.println("--- Filling all COMPACT spots (12 bikes across 3 floors) ---");
        List<Ticket> bikeTickets = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            bikeTickets.add(manager.parkVehicle(new Bike("BIKE-" + i)));
        }

        System.out.println("\nAttempting to park one more bike (should fail):");
        try {
            manager.parkVehicle(new Bike("BIKE-EXTRA"));
        } catch (IllegalStateException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }

        // Release bikes
        for (Ticket bt : bikeTickets) manager.exitVehicle(bt.getTicketId());

        System.out.println("\n=== Demo complete ===");
    }
}

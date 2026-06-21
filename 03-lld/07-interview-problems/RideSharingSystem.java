import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * Ride Sharing System — Classic LLD Interview Problem #8.
 *
 * Core complexity this system captures:
 *   - Concurrent driver matching: multiple ride requests can race to grab
 *     the same nearest driver. Correctness here means exactly one rider
 *     wins, with no lock held over the entire driver pool.
 *   - A well-defined ride lifecycle state machine with illegal-transition
 *     guarding (REQUESTED -> ACCEPTED -> IN_PROGRESS -> COMPLETED, with
 *     CANCELLED reachable only from the two pre-trip states).
 *   - Pluggable fare computation (flat metered vs. surge-multiplied).
 *   - Pluggable driver matching algorithms (nearest vs. highest-rated).
 *   - Decoupled status broadcasting to rider, driver, and analytics.
 *
 * Patterns used:
 *   - State        -> RideStatus (rich enum with an explicit transition table)
 *   - Strategy     -> FareCalculationStrategy, DriverMatchingStrategy
 *   - Observer     -> RideObserver and its implementations
 *   - Facade       -> RideSharingService, the single entry point for clients
 *
 * Concurrency model:
 *   - Driver.status is an AtomicReference<DriverStatus>. Matching a driver
 *     is a compare-and-swap from AVAILABLE to EN_ROUTE_TO_PICKUP. If the
 *     CAS fails, another thread won the race for that specific driver and
 *     the matcher simply retries against the remaining candidate pool.
 *     No external lock is ever taken over the driver registry.
 *   - Ride.status transitions are synchronized on a private per-ride lock
 *     object, so two threads racing to change the same ride's state are
 *     serialized — but rides never contend with each other.
 *
 * NOTE on a fixed bug: Ride.complete() and Ride.cancel() now compute/assign
 * all derived state (fare, cancellation reason) BEFORE calling
 * transitionTo(...). transitionTo synchronously fires observer callbacks,
 * and observers (e.g. RideAnalyticsObserver) read ride.getFare() as soon as
 * they see status == COMPLETED. If transitionTo is called first, observers
 * can fire before fare is set, seeing a null Fare and throwing a
 * NullPointerException. Any state an observer might read as a side effect
 * of a new status must be fully populated before transitionTo is invoked.
 */
public class RideSharingSystem {

    // =====================================================================
    // Exceptions
    // =====================================================================

    public static class InvalidRideStateException extends RuntimeException {
        public InvalidRideStateException(String message) { super(message); }
    }

    public static class EntityNotFoundException extends RuntimeException {
        public EntityNotFoundException(String message) { super(message); }
    }

    // =====================================================================
    // Value Objects
    // =====================================================================

    public static final class Location {
        private final double latitude;
        private final double longitude;

        public Location(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }

        /**
         * Haversine great-circle distance in kilometers. This is a
         * deliberate simplification: a production system would use a
         * routing engine for actual road distance, which is always
         * greater than the straight-line distance and depends on traffic.
         */
        public double distanceTo(Location other) {
            final double earthRadiusKm = 6371.0;
            double dLat = Math.toRadians(other.latitude - this.latitude);
            double dLon = Math.toRadians(other.longitude - this.longitude);
            double lat1 = Math.toRadians(this.latitude);
            double lat2 = Math.toRadians(other.latitude);

            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return earthRadiusKm * c;
        }

        @Override
        public String toString() {
            return String.format("(%.4f, %.4f)", latitude, longitude);
        }
    }

    /**
     * Immutable fare breakdown. The surge multiplier is applied to the
     * sum of the three components rather than baked into each one
     * individually — this keeps SurgePricingFareStrategy a clean decorator
     * over any other strategy's output (see below).
     */
    public static final class Fare {
        private final double baseFare;
        private final double distanceFare;
        private final double timeFare;
        private final double surgeMultiplier;
        private final double totalFare;

        public Fare(double baseFare, double distanceFare, double timeFare, double surgeMultiplier) {
            this.baseFare = baseFare;
            this.distanceFare = distanceFare;
            this.timeFare = timeFare;
            this.surgeMultiplier = surgeMultiplier;
            this.totalFare = (baseFare + distanceFare + timeFare) * surgeMultiplier;
        }

        public double getBaseFare() { return baseFare; }
        public double getDistanceFare() { return distanceFare; }
        public double getTimeFare() { return timeFare; }
        public double getSurgeMultiplier() { return surgeMultiplier; }
        public double getTotalFare() { return totalFare; }

        @Override
        public String toString() {
            return String.format(
                "Fare[base=%.2f, distance=%.2f, time=%.2f, surge=x%.2f, total=%.2f]",
                baseFare, distanceFare, timeFare, surgeMultiplier, totalFare);
        }
    }

    public enum VehicleType {
        HATCHBACK, SEDAN, SUV, BIKE
    }

    public static final class Vehicle {
        private final String vehicleId;
        private final String make;
        private final String model;
        private final String plateNumber;
        private final VehicleType type;

        public Vehicle(String vehicleId, String make, String model, String plateNumber, VehicleType type) {
            this.vehicleId = vehicleId;
            this.make = make;
            this.model = model;
            this.plateNumber = plateNumber;
            this.type = type;
        }

        public String getVehicleId() { return vehicleId; }
        public VehicleType getType() { return type; }
        public String getPlateNumber() { return plateNumber; }

        @Override
        public String toString() {
            return make + " " + model + " (" + plateNumber + ")";
        }
    }

    // =====================================================================
    // State Machine: RideStatus
    // =====================================================================

    /**
     * Rich enum encoding the ride lifecycle's transition table directly
     * in the type system, same approach used for Chess and Notification
     * System. IN_PROGRESS deliberately only permits COMPLETED — once a
     * trip has physically started, cancellation is out of scope for this
     * simplified model (a real system would add an EMERGENCY_STOPPED
     * state with its own fare/refund handling).
     */
    public enum RideStatus {
        REQUESTED {
            @Override public boolean canTransitionTo(RideStatus target) {
                return target == ACCEPTED || target == CANCELLED;
            }
        },
        ACCEPTED {
            @Override public boolean canTransitionTo(RideStatus target) {
                return target == IN_PROGRESS || target == CANCELLED;
            }
        },
        IN_PROGRESS {
            @Override public boolean canTransitionTo(RideStatus target) {
                return target == COMPLETED;
            }
        },
        COMPLETED {
            @Override public boolean canTransitionTo(RideStatus target) { return false; }
        },
        CANCELLED {
            @Override public boolean canTransitionTo(RideStatus target) { return false; }
        };

        public abstract boolean canTransitionTo(RideStatus target);
    }

    public enum DriverStatus {
        AVAILABLE, EN_ROUTE_TO_PICKUP, ON_TRIP, OFFLINE
    }

    // =====================================================================
    // Users
    // =====================================================================

    public static abstract class User {
        protected final String id;
        protected final String name;
        protected final String phone;

        protected User(String id, String name, String phone) {
            this.id = id;
            this.name = name;
            this.phone = phone;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getPhone() { return phone; }
    }

    public static final class Rider extends User {
        private final List<String> rideHistory = Collections.synchronizedList(new ArrayList<>());

        public Rider(String id, String name, String phone) {
            super(id, name, phone);
        }

        public void addRideToHistory(String rideId) { rideHistory.add(rideId); }
        public List<String> getRideHistory() { return new ArrayList<>(rideHistory); }
    }

    public static final class Driver extends User {
        private final Vehicle vehicle;
        private final AtomicReference<DriverStatus> status;
        private final AtomicReference<Location> currentLocation;
        private volatile double rating;

        public Driver(String id, String name, String phone, Vehicle vehicle, Location startLocation) {
            super(id, name, phone);
            this.vehicle = vehicle;
            this.status = new AtomicReference<>(DriverStatus.AVAILABLE);
            this.currentLocation = new AtomicReference<>(startLocation);
            this.rating = 5.0;
        }

        public Vehicle getVehicle() { return vehicle; }
        public DriverStatus getStatus() { return status.get(); }
        public Location getCurrentLocation() { return currentLocation.get(); }
        public double getRating() { return rating; }

        public void updateLocation(Location location) { currentLocation.set(location); }

        /**
         * Atomically transitions driver status only if currently in
         * expectedStatus. This is the linchpin of safe concurrent
         * matching: when two ride requests race to grab the same
         * nearest driver, only one CAS succeeds. The loser falls through
         * to the next candidate with no external lock involved.
         */
        public boolean compareAndSetStatus(DriverStatus expected, DriverStatus update) {
            return status.compareAndSet(expected, update);
        }

        /** Unconditional status write, used for releasing a driver after a ride ends. */
        public void forceSetStatus(DriverStatus newStatus) { status.set(newStatus); }

        public void applyRating(double newRating) {
            if (newRating < 1.0 || newRating > 5.0) {
                throw new IllegalArgumentException("Rating must be in [1.0, 5.0]");
            }
            // Simplified: overwrites rather than averaging across ride history.
            this.rating = newRating;
        }
    }

    // =====================================================================
    // Strategy: Fare Calculation
    // =====================================================================

    public interface FareCalculationStrategy {
        Fare calculateFare(double distanceKm, long durationMinutes);
    }

    /** Standard metered fare: base + per-km + per-minute, no surge. */
    public static class StandardFareStrategy implements FareCalculationStrategy {
        private final double baseFare;
        private final double perKmRate;
        private final double perMinuteRate;

        public StandardFareStrategy(double baseFare, double perKmRate, double perMinuteRate) {
            this.baseFare = baseFare;
            this.perKmRate = perKmRate;
            this.perMinuteRate = perMinuteRate;
        }

        @Override
        public Fare calculateFare(double distanceKm, long durationMinutes) {
            double distanceFare = distanceKm * perKmRate;
            double timeFare = durationMinutes * perMinuteRate;
            return new Fare(baseFare, distanceFare, timeFare, 1.0);
        }
    }

    /**
     * Decorates another fare strategy with an additional surge multiplier.
     * Composition over inheritance: any base strategy becomes surge-priced
     * without duplicating fare math, and multiple surge layers could even
     * be stacked (e.g. demand surge wrapping a weather surge).
     */
    public static class SurgePricingFareStrategy implements FareCalculationStrategy {
        private final FareCalculationStrategy delegate;
        private final double additionalSurgeMultiplier;

        public SurgePricingFareStrategy(FareCalculationStrategy delegate, double additionalSurgeMultiplier) {
            this.delegate = delegate;
            this.additionalSurgeMultiplier = additionalSurgeMultiplier;
        }

        @Override
        public Fare calculateFare(double distanceKm, long durationMinutes) {
            Fare base = delegate.calculateFare(distanceKm, durationMinutes);
            return new Fare(
                base.getBaseFare(),
                base.getDistanceFare(),
                base.getTimeFare(),
                base.getSurgeMultiplier() * additionalSurgeMultiplier
            );
        }
    }

    // =====================================================================
    // Strategy: Driver Matching
    // =====================================================================

    /**
     * Pure matching logic — given a pickup location and a pool of
     * candidates, return the best one. Deliberately knows nothing about
     * concurrency: the CAS retry loop lives in RideSharingService, not
     * here, so a new matching algorithm never has to re-solve the race
     * condition.
     */
    public interface DriverMatchingStrategy {
        Optional<Driver> findMatch(Location pickupLocation, List<Driver> candidateDrivers);
    }

    public static class NearestDriverMatchingStrategy implements DriverMatchingStrategy {
        @Override
        public Optional<Driver> findMatch(Location pickupLocation, List<Driver> candidateDrivers) {
            return candidateDrivers.stream()
                    .min(Comparator.comparingDouble(d -> d.getCurrentLocation().distanceTo(pickupLocation)));
        }
    }

    public static class HighestRatedNearbyDriverMatchingStrategy implements DriverMatchingStrategy {
        private final double radiusKm;

        public HighestRatedNearbyDriverMatchingStrategy(double radiusKm) {
            this.radiusKm = radiusKm;
        }

        @Override
        public Optional<Driver> findMatch(Location pickupLocation, List<Driver> candidateDrivers) {
            return candidateDrivers.stream()
                    .filter(d -> d.getCurrentLocation().distanceTo(pickupLocation) <= radiusKm)
                    .max(Comparator.comparingDouble(Driver::getRating));
        }
    }

    // =====================================================================
    // Observer: Ride Status Updates
    // =====================================================================

    public interface RideObserver {
        void onRideStatusChanged(Ride ride, RideStatus oldStatus, RideStatus newStatus);
    }

    public static class RiderNotificationObserver implements RideObserver {
        @Override
        public void onRideStatusChanged(Ride ride, RideStatus oldStatus, RideStatus newStatus) {
            System.out.printf("  [Notify Rider %s] Ride %s: %s -> %s%n",
                    ride.getRider().getName(), ride.getId(), oldStatus, newStatus);
        }
    }

    public static class DriverNotificationObserver implements RideObserver {
        @Override
        public void onRideStatusChanged(Ride ride, RideStatus oldStatus, RideStatus newStatus) {
            Driver driver = ride.getDriver();
            if (driver != null) {
                System.out.printf("  [Notify Driver %s] Ride %s: %s -> %s%n",
                        driver.getName(), ride.getId(), oldStatus, newStatus);
            }
        }
    }

    public static class RideAnalyticsObserver implements RideObserver {
        private final AtomicInteger completedRides = new AtomicInteger(0);
        private final AtomicInteger cancelledRides = new AtomicInteger(0);
        private final DoubleAdder totalRevenue = new DoubleAdder();

        @Override
        public void onRideStatusChanged(Ride ride, RideStatus oldStatus, RideStatus newStatus) {
            if (newStatus == RideStatus.COMPLETED) {
                completedRides.incrementAndGet();
                Fare fare = ride.getFare();
                if (fare != null) {
                    totalRevenue.add(fare.getTotalFare());
                }
            } else if (newStatus == RideStatus.CANCELLED) {
                cancelledRides.incrementAndGet();
            }
        }

        public void printSummary() {
            System.out.printf("  Analytics -> completed=%d, cancelled=%d, revenue=%.2f%n",
                    completedRides.get(), cancelledRides.get(), totalRevenue.sum());
        }
    }

    // =====================================================================
    // Core Entity: Ride
    // =====================================================================

    public static final class Ride {
        private final String id;
        private final Rider rider;
        private volatile Driver driver;
        private final Location pickupLocation;
        private final Location dropLocation;
        private final FareCalculationStrategy fareStrategy;
        private final Object stateLock = new Object();

        private volatile RideStatus status;
        private final long requestTime;
        private volatile long acceptTime;
        private volatile long startTime;
        private volatile long endTime;
        private volatile Fare fare;
        private volatile String cancellationReason;

        private final List<RideObserver> observers = new CopyOnWriteArrayList<>();

        public Ride(String id, Rider rider, Location pickupLocation, Location dropLocation,
                    FareCalculationStrategy fareStrategy) {
            this.id = id;
            this.rider = rider;
            this.pickupLocation = pickupLocation;
            this.dropLocation = dropLocation;
            this.fareStrategy = fareStrategy;
            this.status = RideStatus.REQUESTED;
            this.requestTime = System.currentTimeMillis();
        }

        public void addObserver(RideObserver observer) { observers.add(observer); }

        /**
         * Single choke point for every state change. Synchronized on a
         * lock private to this ride, so concurrent transition attempts on
         * the SAME ride (e.g. a rider cancelling while the driver
         * simultaneously starts the trip) are serialized and validated
         * against the RideStatus transition table — but two different
         * rides never contend with each other over this lock.
         *
         * IMPORTANT: this method synchronously fires observer callbacks
         * (see notifyObservers below). Any derived state that an observer
         * might read in response to the new status (e.g. fare on
         * COMPLETED) MUST be fully assigned by the caller before invoking
         * transitionTo — otherwise observers can race ahead of that
         * assignment and read stale/null state.
         */
        private void transitionTo(RideStatus target) {
            RideStatus previous;
            synchronized (stateLock) {
                if (!status.canTransitionTo(target)) {
                    throw new InvalidRideStateException(
                        "Cannot transition ride " + id + " from " + status + " to " + target);
                }
                previous = status;
                status = target;
            }
            notifyObservers(previous, target);
        }

        private void notifyObservers(RideStatus oldStatus, RideStatus newStatus) {
            for (RideObserver observer : observers) {
                observer.onRideStatusChanged(this, oldStatus, newStatus);
            }
        }

        public void assignDriver(Driver driver) {
            this.driver = driver;
            transitionTo(RideStatus.ACCEPTED);
            this.acceptTime = System.currentTimeMillis();
        }

        public void start() {
            transitionTo(RideStatus.IN_PROGRESS);
            this.startTime = System.currentTimeMillis();
        }

        /**
         * FIX: fare must be computed and assigned BEFORE transitionTo is
         * called, since transitionTo synchronously notifies observers and
         * RideAnalyticsObserver reads ride.getFare() as soon as it sees
         * status == COMPLETED. Previously fare was computed after
         * transitionTo, so observers could see status == COMPLETED with
         * fare still null, causing a NullPointerException.
         */
        public void complete() {
            long completionTime = System.currentTimeMillis();
            double distanceKm = pickupLocation.distanceTo(dropLocation);
            // Simplified duration: real systems track actual elapsed trip time;
            // we floor at 1 minute so a fast demo run never charges zero.
            long durationMinutes = Math.max(1, (completionTime - startTime) / 60000);
            this.fare = fareStrategy.calculateFare(distanceKm, durationMinutes);

            transitionTo(RideStatus.COMPLETED);

            this.endTime = completionTime;
            rider.addRideToHistory(id);
            if (driver != null) {
                driver.forceSetStatus(DriverStatus.AVAILABLE);
            }
        }

        /**
         * FIX: cancellationReason is now assigned before transitionTo for
         * the same reason as complete() above — no observer currently
         * reads it, but keeping assignment-before-notify consistent avoids
         * reintroducing this class of bug if an observer starts doing so.
         */
        public void cancel(String reason) {
            this.cancellationReason = reason;

            transitionTo(RideStatus.CANCELLED);

            if (driver != null) {
                driver.forceSetStatus(DriverStatus.AVAILABLE);
            }
        }

        public String getId() { return id; }
        public Rider getRider() { return rider; }
        public Driver getDriver() { return driver; }
        public RideStatus getStatus() { return status; }
        public Location getPickupLocation() { return pickupLocation; }
        public Location getDropLocation() { return dropLocation; }
        public Fare getFare() { return fare; }
        public String getCancellationReason() { return cancellationReason; }
    }

    // =====================================================================
    // Facade: RideSharingService
    // =====================================================================

    public static class RideSharingService {
        private final ConcurrentMap<String, Rider> riders = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Driver> drivers = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Ride> rides = new ConcurrentHashMap<>();

        private final DriverMatchingStrategy matchingStrategy;
        private final FareCalculationStrategy defaultFareStrategy;
        private final List<RideObserver> defaultObservers;

        private final AtomicLong rideIdGenerator = new AtomicLong(1);

        public RideSharingService(DriverMatchingStrategy matchingStrategy,
                                   FareCalculationStrategy defaultFareStrategy,
                                   List<RideObserver> defaultObservers) {
            this.matchingStrategy = matchingStrategy;
            this.defaultFareStrategy = defaultFareStrategy;
            this.defaultObservers = defaultObservers;
        }

        public void registerRider(Rider rider) { riders.put(rider.getId(), rider); }
        public void registerDriver(Driver driver) { drivers.put(driver.getId(), driver); }

        public void updateDriverLocation(String driverId, Location location) {
            getDriverOrThrow(driverId).updateLocation(location);
        }

        /**
         * Requests a ride and immediately attempts to match a driver
         * synchronously. A production system would make this async (a
         * dispatcher polling or queue-driven) so a temporary shortage of
         * drivers doesn't block the request thread; here it's synchronous
         * for clarity. If no driver is available right now, the ride is
         * left in REQUESTED rather than failing outright — that's a
         * deliberate "stay open to a later match" design choice over
         * throwing a hard NoDriverAvailableException.
         */
        public Ride requestRide(String riderId, Location pickup, Location drop) {
            return requestRide(riderId, pickup, drop, defaultFareStrategy);
        }

        public Ride requestRide(String riderId, Location pickup, Location drop,
                                 FareCalculationStrategy fareStrategy) {
            Rider rider = getRiderOrThrow(riderId);
            String rideId = "RIDE-" + rideIdGenerator.getAndIncrement();
            Ride ride = new Ride(rideId, rider, pickup, drop, fareStrategy);
            defaultObservers.forEach(ride::addObserver);
            rides.put(rideId, ride);

            Optional<Driver> matched = matchDriver(pickup);
            if (matched.isPresent()) {
                ride.assignDriver(matched.get());
            } else {
                System.out.println("  No driver currently available for ride " + rideId);
            }
            return ride;
        }

        /**
         * The CAS retry loop. Filters to currently-AVAILABLE drivers, asks
         * the matching strategy for its top pick, then attempts to claim
         * that exact driver atomically. A failed CAS means someone else
         * claimed this driver in the window between the filter and the
         * CAS — the candidate is dropped and the strategy is re-run on
         * the remaining pool, never on a stale snapshot.
         */
        private Optional<Driver> matchDriver(Location pickup) {
            List<Driver> candidates = drivers.values().stream()
                    .filter(d -> d.getStatus() == DriverStatus.AVAILABLE)
                    .collect(Collectors.toCollection(ArrayList::new));

            while (!candidates.isEmpty()) {
                Optional<Driver> best = matchingStrategy.findMatch(pickup, candidates);
                if (!best.isPresent()) {
                    return Optional.empty();
                }
                Driver candidate = best.get();
                if (candidate.compareAndSetStatus(DriverStatus.AVAILABLE, DriverStatus.EN_ROUTE_TO_PICKUP)) {
                    return Optional.of(candidate);
                }
                candidates.remove(candidate);
            }
            return Optional.empty();
        }

        public void startRide(String rideId) {
            Ride ride = getRideOrThrow(rideId);
            ride.start();
            if (ride.getDriver() != null) {
                ride.getDriver().forceSetStatus(DriverStatus.ON_TRIP);
            }
        }

        public Fare completeRide(String rideId) {
            Ride ride = getRideOrThrow(rideId);
            ride.complete();
            return ride.getFare();
        }

        public void cancelRide(String rideId, String reason) {
            getRideOrThrow(rideId).cancel(reason);
        }

        public Ride getRide(String rideId) { return getRideOrThrow(rideId); }
        public Driver getDriver(String driverId) { return getDriverOrThrow(driverId); }

        private Rider getRiderOrThrow(String id) {
            Rider r = riders.get(id);
            if (r == null) throw new EntityNotFoundException("Rider not found: " + id);
            return r;
        }

        private Driver getDriverOrThrow(String id) {
            Driver d = drivers.get(id);
            if (d == null) throw new EntityNotFoundException("Driver not found: " + id);
            return d;
        }

        private Ride getRideOrThrow(String id) {
            Ride r = rides.get(id);
            if (r == null) throw new EntityNotFoundException("Ride not found: " + id);
            return r;
        }
    }

    // =====================================================================
    // Demo
    // =====================================================================

    public static void main(String[] args) throws Exception {
        RideAnalyticsObserver analytics = new RideAnalyticsObserver();
        List<RideObserver> observers = Arrays.asList(
                new RiderNotificationObserver(),
                new DriverNotificationObserver(),
                analytics
        );

        FareCalculationStrategy standardFare = new StandardFareStrategy(40.0, 12.0, 1.5);
        DriverMatchingStrategy nearest = new NearestDriverMatchingStrategy();
        RideSharingService service = new RideSharingService(nearest, standardFare, observers);

        Rider akash = new Rider("R1", "Akash", "9999990000");
        Rider neha = new Rider("R2", "Neha", "9999990001");
        service.registerRider(akash);
        service.registerRider(neha);

        Driver d1 = new Driver("D1", "Ramesh", "8888880000",
                new Vehicle("V1", "Maruti", "Swift", "DL01AB1234", VehicleType.HATCHBACK),
                new Location(28.6139, 77.2090)); // Connaught Place
        Driver d2 = new Driver("D2", "Suresh", "8888880001",
                new Vehicle("V2", "Hyundai", "Verna", "DL02CD5678", VehicleType.SEDAN),
                new Location(28.5355, 77.3910)); // Noida
        service.registerDriver(d1);
        service.registerDriver(d2);

        System.out.println("--- Scenario 1: straightforward ride lifecycle ---");
        Ride ride1 = service.requestRide("R1", new Location(28.6139, 77.2090), new Location(28.6129, 77.2295));
        System.out.println("  Matched driver: " + ride1.getDriver().getName());
        service.startRide(ride1.getId());
        Thread.sleep(50); // simulate trip duration
        Fare fare1 = service.completeRide(ride1.getId());
        System.out.println("  Ride completed -> " + fare1);

        System.out.println();
        System.out.println("--- Scenario 2: concurrent requests racing for one driver ---");
        Driver d3 = new Driver("D3", "Vikram", "8888880002",
                new Vehicle("V3", "Honda", "City", "DL03EF9012", VehicleType.SEDAN),
                new Location(28.6000, 77.2000));
        service.registerDriver(d3);
        // Force the previously-used drivers offline so D3 is the only available
        // candidate, making the CAS race deterministic and easy to observe.
        d1.forceSetStatus(DriverStatus.OFFLINE);
        d2.forceSetStatus(DriverStatus.OFFLINE);

        Location sharedPickup = new Location(28.6005, 77.2005);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Ride> requestTask = () -> service.requestRide("R2", sharedPickup, new Location(28.61, 77.21));
        Future<Ride> f1 = pool.submit(requestTask);
        Future<Ride> f2 = pool.submit(requestTask);
        Ride raceRideA = f1.get();
        Ride raceRideB = f2.get();
        pool.shutdown();

        long matchedCount = Stream.of(raceRideA, raceRideB).filter(r -> r.getDriver() != null).count();
        System.out.println("  Ride A matched: " + (raceRideA.getDriver() != null ? raceRideA.getDriver().getId() : "none"));
        System.out.println("  Ride B matched: " + (raceRideB.getDriver() != null ? raceRideB.getDriver().getId() : "none"));
        System.out.println("  Total rides matched to the single available driver: " + matchedCount + " (must be exactly 1)");

        d1.forceSetStatus(DriverStatus.AVAILABLE);
        d2.forceSetStatus(DriverStatus.AVAILABLE);

        System.out.println();
        System.out.println("--- Scenario 3: cancellation and illegal transition guarding ---");
        Ride ride3 = service.requestRide("R1", new Location(28.61, 77.21), new Location(28.62, 77.23));
        service.cancelRide(ride3.getId(), "Rider changed their mind");
        try {
            service.startRide(ride3.getId());
            System.out.println("  ERROR: should not reach here");
        } catch (InvalidRideStateException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }

        System.out.println();
        System.out.println("--- Scenario 4: surge pricing via Strategy decoration ---");
        FareCalculationStrategy surgeFare = new SurgePricingFareStrategy(standardFare, 1.8);
        Ride ride4 = service.requestRide("R2", new Location(28.6139, 77.2090), new Location(28.6448, 77.2167), surgeFare);
        if (ride4.getDriver() != null) {
            service.startRide(ride4.getId());
            Thread.sleep(30);
            Fare surged = service.completeRide(ride4.getId());
            System.out.println("  Surge ride completed -> " + surged);
        }

        System.out.println();
        analytics.printSummary();
    }
}
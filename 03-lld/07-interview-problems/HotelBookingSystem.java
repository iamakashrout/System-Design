import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Hotel Booking System — Classic LLD Interview Problem #10.
 *
 * Core complexity this system captures:
 *   - Concurrent booking attempts for the same room on overlapping dates.
 *     The check-availability-then-book flow must be atomic per room — a
 *     classic TOCTOU (Time-of-Check-to-Time-of-Use) race if not guarded.
 *   - Date-range overlap detection with correct boundary semantics: two
 *     bookings conflict iff checkIn1 < checkOut2 && checkIn2 < checkOut1.
 *     Strict less-than means a guest checking out on day N and another
 *     checking in on day N is NOT a conflict (standard hotel semantics).
 *   - Two independent pricing and cancellation Strategy axes — neither
 *     depends on the other, so they compose freely.
 *   - Room creation delegated to a Factory — clients ask for a SUITE,
 *     not a Room with amenities hardcoded inline.
 *
 * Patterns used:
 *   - State   -> BookingStatus (rich enum with explicit transition table)
 *   - Strategy -> PricingStrategy (room type + seasonal rate)
 *   - Strategy -> CancellationPolicy (refund rules at cancel time)
 *   - Factory  -> RoomFactory (creates Room by RoomType)
 *   - Facade   -> HotelService (single client entry point)
 *
 * Concurrency model:
 *   - Each Room owns a ReentrantLock. Conflict detection + booking creation
 *     is a compound check-then-act that must be atomic within one room, but
 *     two bookings for *different* rooms never contend — so a per-room lock
 *     is the right granularity. No global booking lock is ever held.
 *   - Booking state transitions use a synchronized block on the Booking
 *     object itself — the same per-resource scope principle.
 *   - HotelService registries (rooms, guests, bookings) use ConcurrentHashMap
 *     for lock-free reads under concurrent access.
 */
public class HotelBookingSystem {

    // =========================================================
    // Exceptions
    // =========================================================

    public static class RoomNotAvailableException extends RuntimeException {
        public RoomNotAvailableException(String msg) { super(msg); }
    }

    public static class EntityNotFoundException extends RuntimeException {
        public EntityNotFoundException(String msg) { super(msg); }
    }

    public static class InvalidBookingStateException extends RuntimeException {
        public InvalidBookingStateException(String msg) { super(msg); }
    }

    public static class InvalidDateRangeException extends RuntimeException {
        public InvalidDateRangeException(String msg) { super(msg); }
    }

    // =========================================================
    // RoomType enum
    // =========================================================

    public enum RoomType {
        SINGLE(1, 80.0,  "Single bed"),
        DOUBLE(2, 130.0, "Double bed, city view"),
        SUITE (4, 300.0, "King bed, lounge, sea view");

        private final int capacity;
        private final double baseRatePerNight;
        private final String description;

        RoomType(int capacity, double baseRatePerNight, String description) {
            this.capacity = capacity;
            this.baseRatePerNight = baseRatePerNight;
            this.description = description;
        }

        public int getCapacity() { return capacity; }
        public double getBaseRatePerNight() { return baseRatePerNight; }
        public String getDescription() { return description; }
    }

    // =========================================================
    // BookingStatus — rich enum / state machine
    // =========================================================

    /**
     * CONFIRMED -> CHECKED_IN  : guest arrives
     * CONFIRMED -> CANCELLED   : pre-arrival cancellation
     * CHECKED_IN -> COMPLETED  : guest checks out
     * CHECKED_IN -> CANCELLED  : mid-stay cancellation (with fee)
     *
     * COMPLETED and CANCELLED are terminal — no further transitions.
     */
    public enum BookingStatus {
        CONFIRMED {
            @Override public boolean canTransitionTo(BookingStatus target) {
                return target == CHECKED_IN || target == CANCELLED;
            }
        },
        CHECKED_IN {
            @Override public boolean canTransitionTo(BookingStatus target) {
                return target == COMPLETED || target == CANCELLED;
            }
        },
        COMPLETED {
            @Override public boolean canTransitionTo(BookingStatus target) { return false; }
        },
        CANCELLED {
            @Override public boolean canTransitionTo(BookingStatus target) { return false; }
        };

        public abstract boolean canTransitionTo(BookingStatus target);
    }

    // =========================================================
    // Room
    // =========================================================

    public static class Room {
        private final String roomNumber;
        private final RoomType type;
        // Per-room lock: conflict check + booking creation must be atomic
        // within one room, but rooms never need to lock each other.
        private final ReentrantLock bookingLock = new ReentrantLock();
        private final List<Booking> bookings = new ArrayList<>();

        public Room(String roomNumber, RoomType type) {
            this.roomNumber = roomNumber;
            this.type = type;
        }

        /**
         * Returns true if this room has no active booking that overlaps
         * [checkIn, checkOut). Overlap condition (strict):
         *   checkIn < existingCheckOut && existingCheckIn < checkOut
         * A guest checking out on day N and another checking in on day N is
         * NOT a conflict — the outgoing guest leaves before noon, the
         * incoming guest arrives after 3 pm.
         *
         * Called only while holding bookingLock.
         */
        public boolean isAvailable(LocalDate checkIn, LocalDate checkOut) {
            for (Booking b : bookings) {
                if (b.getStatus() == BookingStatus.CANCELLED) continue;
                if (checkIn.isBefore(b.getCheckOut()) && b.getCheckIn().isBefore(checkOut)) {
                    return false;
                }
            }
            return true;
        }

        public void addBooking(Booking booking) { bookings.add(booking); }

        public ReentrantLock getBookingLock() { return bookingLock; }
        public String getRoomNumber() { return roomNumber; }
        public RoomType getType() { return type; }

        public List<Booking> getBookings() {
            bookingLock.lock();
            try { return new ArrayList<>(bookings); }
            finally { bookingLock.unlock(); }
        }
    }

    // =========================================================
    // Guest
    // =========================================================

    public static class Guest {
        private final String guestId;
        private final String name;
        private final String email;

        public Guest(String guestId, String name, String email) {
            this.guestId = guestId;
            this.name = name;
            this.email = email;
        }

        public String getGuestId() { return guestId; }
        public String getName() { return name; }
        public String getEmail() { return email; }
    }

    // =========================================================
    // Booking
    // =========================================================

    public static class Booking {
        private final String bookingId;
        private final Room room;
        private final Guest guest;
        private final LocalDate checkIn;
        private final LocalDate checkOut;
        private final double totalPrice;
        private volatile BookingStatus status;

        public Booking(String bookingId, Room room, Guest guest,
                       LocalDate checkIn, LocalDate checkOut, double totalPrice) {
            this.bookingId = bookingId;
            this.room = room;
            this.guest = guest;
            this.checkIn = checkIn;
            this.checkOut = checkOut;
            this.totalPrice = totalPrice;
            this.status = BookingStatus.CONFIRMED;
        }

        public synchronized void transitionTo(BookingStatus target) {
            if (!status.canTransitionTo(target)) {
                throw new InvalidBookingStateException(
                        "Booking " + bookingId + " cannot move from " + status + " to " + target);
            }
            status = target;
        }

        public long getNights() { return ChronoUnit.DAYS.between(checkIn, checkOut); }

        public String getBookingId() { return bookingId; }
        public Room getRoom() { return room; }
        public Guest getGuest() { return guest; }
        public LocalDate getCheckIn() { return checkIn; }
        public LocalDate getCheckOut() { return checkOut; }
        public double getTotalPrice() { return totalPrice; }
        public BookingStatus getStatus() { return status; }

        @Override public String toString() {
            return String.format("Booking{%s, room=%s, guest=%s, %s→%s, $%.2f, %s}",
                    bookingId, room.getRoomNumber(), guest.getName(),
                    checkIn, checkOut, totalPrice, status);
        }
    }

    // =========================================================
    // Factory — RoomFactory
    // =========================================================

    /**
     * Centralises room construction. Clients request a RoomType; the factory
     * assembles a fully configured Room. Adding a new type (e.g. PENTHOUSE)
     * requires only one change — here — rather than touching every caller.
     */
    public static class RoomFactory {
        public static Room createRoom(RoomType type, String roomNumber) {
            return new Room(roomNumber, type);
        }
    }

    // =========================================================
    // Strategy — PricingStrategy
    // =========================================================

    public interface PricingStrategy {
        /**
         * Returns the total price for this room over [checkIn, checkOut).
         * Implementations may vary the nightly rate by room type, season,
         * day of week, or any other dimension — callers don't need to know.
         */
        double calculatePrice(Room room, LocalDate checkIn, LocalDate checkOut);
    }

    /** Base rate from RoomType × number of nights. No seasonal adjustment. */
    public static class StandardPricingStrategy implements PricingStrategy {
        @Override
        public double calculatePrice(Room room, LocalDate checkIn, LocalDate checkOut) {
            long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
            return room.getType().getBaseRatePerNight() * nights;
        }
    }

    /**
     * Per-night rate: peak-season months (June–August, December) apply a
     * multiplier; all other months use the base rate.
     *
     * Iterates night by night rather than computing a single aggregate
     * multiplier for the whole stay — stays that straddle a season boundary
     * are priced correctly at per-night granularity.
     */
    public static class SeasonalPricingStrategy implements PricingStrategy {
        private static final Set<Month> PEAK_MONTHS = EnumSet.of(
                Month.JUNE, Month.JULY, Month.AUGUST, Month.DECEMBER);
        private final double peakMultiplier;

        public SeasonalPricingStrategy(double peakMultiplier) {
            this.peakMultiplier = peakMultiplier;
        }

        @Override
        public double calculatePrice(Room room, LocalDate checkIn, LocalDate checkOut) {
            double total = 0.0;
            LocalDate night = checkIn;
            while (night.isBefore(checkOut)) {
                double rate = room.getType().getBaseRatePerNight();
                if (PEAK_MONTHS.contains(night.getMonth())) rate *= peakMultiplier;
                total += rate;
                night = night.plusDays(1);
            }
            return total;
        }
    }

    // =========================================================
    // Strategy — CancellationPolicy
    // =========================================================

    public interface CancellationPolicy {
        /**
         * Calculates the refund amount given the booking being cancelled
         * and the date on which the cancellation is made.
         */
        double calculateRefund(Booking booking, LocalDate cancelDate);
    }

    /**
     * Full refund if cancelled at least {@code freeCancellationDays} before
     * check-in. Partial refund (50%) if cancelled after that but before
     * check-in. No refund once checked in.
     */
    public static class FreeCancellationPolicy implements CancellationPolicy {
        private final int freeCancellationDays;

        public FreeCancellationPolicy(int freeCancellationDays) {
            this.freeCancellationDays = freeCancellationDays;
        }

        @Override
        public double calculateRefund(Booking booking, LocalDate cancelDate) {
            long daysUntilCheckIn = ChronoUnit.DAYS.between(cancelDate, booking.getCheckIn());
            if (booking.getStatus() == BookingStatus.CHECKED_IN) return 0.0;
            if (daysUntilCheckIn >= freeCancellationDays) return booking.getTotalPrice();
            if (daysUntilCheckIn > 0) return booking.getTotalPrice() * 0.50;
            return 0.0;
        }
    }

    /** Zero refund regardless of when cancellation occurs. */
    public static class NonRefundablePolicy implements CancellationPolicy {
        @Override
        public double calculateRefund(Booking booking, LocalDate cancelDate) { return 0.0; }
    }

    // =========================================================
    // Facade — HotelService
    // =========================================================

    public static class HotelService {
        private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Guest> guests = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Booking> bookings = new ConcurrentHashMap<>();
        private final PricingStrategy pricingStrategy;
        private final CancellationPolicy cancellationPolicy;
        private final AtomicInteger bookingIdSeq = new AtomicInteger(1);

        public HotelService(PricingStrategy pricingStrategy, CancellationPolicy cancellationPolicy) {
            this.pricingStrategy = pricingStrategy;
            this.cancellationPolicy = cancellationPolicy;
        }

        // --- setup --------------------------------------------------

        public Room addRoom(RoomType type, String roomNumber) {
            Room room = RoomFactory.createRoom(type, roomNumber);
            rooms.put(roomNumber, room);
            return room;
        }

        public Guest registerGuest(String guestId, String name, String email) {
            Guest guest = new Guest(guestId, name, email);
            guests.put(guestId, guest);
            return guest;
        }

        // --- booking ------------------------------------------------

        /**
         * Books a specific room for a guest over [checkIn, checkOut).
         *
         * Concurrency: acquires the room's ReentrantLock before checking
         * availability and creating the booking. This makes the
         * check-then-act atomic for that room. Two concurrent bookings for
         * *different* rooms proceed in parallel — no cross-room blocking.
         */
        public Booking bookRoom(String guestId, String roomNumber,
                                LocalDate checkIn, LocalDate checkOut) {
            validateDates(checkIn, checkOut);
            Guest guest = getGuestOrThrow(guestId);
            Room room = getRoomOrThrow(roomNumber);

            room.getBookingLock().lock();
            try {
                if (!room.isAvailable(checkIn, checkOut)) {
                    throw new RoomNotAvailableException(
                            "Room " + roomNumber + " is not available from " + checkIn + " to " + checkOut);
                }
                double price = pricingStrategy.calculatePrice(room, checkIn, checkOut);
                String bookingId = "B" + bookingIdSeq.getAndIncrement();
                Booking booking = new Booking(bookingId, room, guest, checkIn, checkOut, price);
                room.addBooking(booking);
                bookings.put(bookingId, booking);
                return booking;
            } finally {
                room.getBookingLock().unlock();
            }
        }

        public void checkIn(String bookingId) {
            getBookingOrThrow(bookingId).transitionTo(BookingStatus.CHECKED_IN);
        }

        public void checkOut(String bookingId) {
            getBookingOrThrow(bookingId).transitionTo(BookingStatus.COMPLETED);
        }

        /**
         * Cancels a booking and returns the refund amount per the configured
         * CancellationPolicy. The booking moves to CANCELLED so
         * Room.isAvailable() no longer counts it as occupying those dates.
         */
        public double cancelBooking(String bookingId, LocalDate cancelDate) {
            Booking booking = getBookingOrThrow(bookingId);
            double refund = cancellationPolicy.calculateRefund(booking, cancelDate);
            booking.transitionTo(BookingStatus.CANCELLED);
            return refund;
        }

        // --- search -------------------------------------------------

        /**
         * Returns rooms available for the entire requested period.
         * Optionally filtered by type (pass null for any type).
         */
        public List<Room> searchAvailableRooms(LocalDate checkIn, LocalDate checkOut,
                                               RoomType type) {
            validateDates(checkIn, checkOut);
            List<Room> available = new ArrayList<>();
            for (Room room : rooms.values()) {
                if (type != null && room.getType() != type) continue;
                room.getBookingLock().lock();
                try {
                    if (room.isAvailable(checkIn, checkOut)) available.add(room);
                } finally {
                    room.getBookingLock().unlock();
                }
            }
            return available;
        }

        public Booking getBooking(String bookingId) { return getBookingOrThrow(bookingId); }

        // --- internals ----------------------------------------------

        private void validateDates(LocalDate checkIn, LocalDate checkOut) {
            if (!checkIn.isBefore(checkOut)) {
                throw new InvalidDateRangeException(
                        "checkIn " + checkIn + " must be before checkOut " + checkOut);
            }
        }

        private Guest getGuestOrThrow(String guestId) {
            Guest g = guests.get(guestId);
            if (g == null) throw new EntityNotFoundException("No guest with id " + guestId);
            return g;
        }

        private Room getRoomOrThrow(String roomNumber) {
            Room r = rooms.get(roomNumber);
            if (r == null) throw new EntityNotFoundException("No room " + roomNumber);
            return r;
        }

        private Booking getBookingOrThrow(String bookingId) {
            Booking b = bookings.get(bookingId);
            if (b == null) throw new EntityNotFoundException("No booking " + bookingId);
            return b;
        }
    }

    // =========================================================
    // Demo
    // =========================================================

    public static void main(String[] args) throws Exception {
        PricingStrategy pricing = new SeasonalPricingStrategy(1.5);
        CancellationPolicy policy = new FreeCancellationPolicy(7);
        HotelService hotel = new HotelService(pricing, policy);

        hotel.addRoom(RoomType.SINGLE, "101");
        hotel.addRoom(RoomType.SINGLE, "102");
        hotel.addRoom(RoomType.DOUBLE, "201");
        hotel.addRoom(RoomType.SUITE,  "301");

        Guest alice = hotel.registerGuest("G1", "Alice", "alice@example.com");
        Guest bob   = hotel.registerGuest("G2", "Bob",   "bob@example.com");
        Guest carol = hotel.registerGuest("G3", "Carol", "carol@example.com");

        System.out.println("=== Scenario 1: standard booking + full lifecycle ===");
        LocalDate jan10 = LocalDate.of(2025, 1, 10);
        LocalDate jan15 = LocalDate.of(2025, 1, 15);
        Booking b1 = hotel.bookRoom("G1", "201", jan10, jan15);
        System.out.println("  Booked: " + b1);
        hotel.checkIn(b1.getBookingId());
        System.out.println("  After check-in: " + b1.getStatus());
        hotel.checkOut(b1.getBookingId());
        System.out.println("  After check-out: " + b1.getStatus());

        System.out.println();
        System.out.println("=== Scenario 2: peak-season pricing (July) ===");
        LocalDate jul1 = LocalDate.of(2025, 7, 1);
        LocalDate jul8 = LocalDate.of(2025, 7, 8);
        Booking b2 = hotel.bookRoom("G2", "301", jul1, jul8);
        System.out.printf("  Suite for 7 nights in July: $%.2f (base $%.2f/night × 1.5 peak)%n",
                b2.getTotalPrice(), RoomType.SUITE.getBaseRatePerNight());

        System.out.println();
        System.out.println("=== Scenario 3: conflict detection ===");
        // bob already has 301 for Jul 1-8; carol tries overlapping Jul 5-10
        LocalDate jul5  = LocalDate.of(2025, 7, 5);
        LocalDate jul10 = LocalDate.of(2025, 7, 10);
        try {
            hotel.bookRoom("G3", "301", jul5, jul10);
            System.out.println("  ERROR: should have thrown");
        } catch (RoomNotAvailableException e) {
            System.out.println("  Correctly rejected overlap: " + e.getMessage());
        }
        // Check-out day is NOT a conflict: Jul 8 check-out allows Jul 8 check-in
        LocalDate jul8B = LocalDate.of(2025, 7, 8);
        LocalDate jul12 = LocalDate.of(2025, 7, 12);
        Booking b3 = hotel.bookRoom("G3", "301", jul8B, jul12);
        System.out.println("  Back-to-back booking accepted (Jul 8-12): " + b3.getBookingId());

        System.out.println();
        System.out.println("=== Scenario 4: concurrent booking race ===");
        // Seed room 101 as fully open for Feb dates
        LocalDate feb1 = LocalDate.of(2025, 2, 1);
        LocalDate feb5 = LocalDate.of(2025, 2, 5);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go    = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (String gid : List.of("G2", "G3")) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    Booking b = hotel.bookRoom(gid, "101", feb1, feb5);
                    return gid + " WON: " + b.getBookingId();
                } catch (RoomNotAvailableException e) {
                    return gid + " LOST: " + e.getMessage();
                }
            }));
        }
        ready.await();
        go.countDown();
        for (Future<String> f : results) System.out.println("  " + f.get());
        pool.shutdown();

        System.out.println();
        System.out.println("=== Scenario 5: cancellation and refund policy ===");
        LocalDate mar1  = LocalDate.of(2025, 3, 1);
        LocalDate mar7  = LocalDate.of(2025, 3, 7);
        Booking b4 = hotel.bookRoom("G1", "102", mar1, mar7);
        System.out.printf("  Booked room 102 for $%.2f%n", b4.getTotalPrice());

        // Cancel with 10 days to spare → full refund
        LocalDate earlyCancel = mar1.minusDays(10);
        double refund1 = hotel.cancelBooking(b4.getBookingId(), earlyCancel);
        System.out.printf("  Cancelled 10 days early → refund $%.2f (full)%n", refund1);

        // Book again; cancel only 3 days before check-in → 50% refund
        Booking b5 = hotel.bookRoom("G1", "102", mar1, mar7);
        LocalDate lateCancel = mar1.minusDays(3);
        double refund2 = hotel.cancelBooking(b5.getBookingId(), lateCancel);
        System.out.printf("  Cancelled 3 days before check-in → refund $%.2f (50%%)%n", refund2);

        System.out.println();
        System.out.println("=== Scenario 6: search available rooms ===");
        // Room 102 is cancelled so should appear; 101 is booked (Feb 1-5 survivor from race)
        List<Room> available = hotel.searchAvailableRooms(feb1, feb5, RoomType.SINGLE);
        System.out.println("  Available SINGLE rooms for Feb 1-5:");
        for (Room r : available) {
            System.out.println("    " + r.getRoomNumber() + " (" + r.getType() + ")");
        }

        System.out.println();
        System.out.println("=== Scenario 7: invalid state transition guard ===");
        Booking b6 = hotel.bookRoom("G2", "102", mar1, mar7);
        hotel.checkIn(b6.getBookingId());
        hotel.checkOut(b6.getBookingId());
        try {
            hotel.checkOut(b6.getBookingId()); // COMPLETED → COMPLETED not allowed
            System.out.println("  ERROR: should have thrown");
        } catch (InvalidBookingStateException e) {
            System.out.println("  Correctly rejected: " + e.getMessage());
        }
    }
}

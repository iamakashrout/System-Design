import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TOPIC 4: CLEAN ARCHITECTURE PRINCIPLES
 *
 * Domain: Hotel Booking System (Phase 5, Problem 10), restructured into
 * three explicit layers. Comment banners simulate package boundaries
 * (in a real project these would be separate packages/modules, e.g.
 * com.hotel.domain, com.hotel.application, com.hotel.infrastructure) —
 * kept as one file here purely for portability in this JRE-only environment.
 *
 * DEPENDENCY RULE CHECK: read top to bottom. The DOMAIN section never
 * references anything from APPLICATION or INFRASTRUCTURE. APPLICATION
 * references DOMAIN only (including domain-defined repository interfaces).
 * INFRASTRUCTURE implements domain interfaces — it depends inward, domain
 * never depends outward on it.
 */
public class CleanArchitecture {

    // =========================================================================
    // ============================ DOMAIN LAYER ==============================
    // (would live in: com.hotel.domain)
    // Zero imports from application or infrastructure. Plain business rules.
    // =========================================================================

    enum BookingStatus {
        CONFIRMED, CANCELLED, CHECKED_IN, CHECKED_OUT
    }

    /** Domain exception — see Topic 3. Lives in the domain layer with the rules it protects. */
    static class InvalidBookingOperationException extends RuntimeException {
        InvalidBookingOperationException(String message) {
            super(message);
        }
    }

    static class RoomUnavailableException extends RuntimeException {
        RoomUnavailableException(String roomId, LocalDate checkIn, LocalDate checkOut) {
            super("Room " + roomId + " is not available for " + checkIn + " to " + checkOut);
        }
    }

    /** A room entity. Simple here, but this is where room-level rules would live. */
    static final class Room {
        private final String roomId;
        private final String type;
        private final double nightlyRate;

        Room(String roomId, String type, double nightlyRate) {
            this.roomId = roomId;
            this.type = type;
            this.nightlyRate = nightlyRate;
        }

        String getRoomId() {
            return roomId;
        }

        double getNightlyRate() {
            return nightlyRate;
        }

        String getType() {
            return type;
        }
    }

    /**
     * RICH DOMAIN MODEL: Booking enforces its own invariants. Notice that
     * `cancel()` and `checkIn()` are METHODS ON THE ENTITY, not external
     * service logic reading/writing a status field. The entity makes it
     * IMPOSSIBLE to reach an invalid state through its own public API.
     */
    static final class Booking {
        private final String bookingId;
        private final String roomId;
        private final LocalDate checkIn;
        private final LocalDate checkOut;
        private BookingStatus status;

        Booking(String bookingId, String roomId, LocalDate checkIn, LocalDate checkOut) {
            if (!checkOut.isAfter(checkIn)) {
                // Fail-fast (Topic 3): invalid input rejected at construction.
                throw new IllegalArgumentException("checkOut must be after checkIn");
            }
            this.bookingId = bookingId;
            this.roomId = roomId;
            this.checkIn = checkIn;
            this.checkOut = checkOut;
            this.status = BookingStatus.CONFIRMED;
        }

        String getBookingId() {
            return bookingId;
        }

        String getRoomId() {
            return roomId;
        }

        LocalDate getCheckIn() {
            return checkIn;
        }

        LocalDate getCheckOut() {
            return checkOut;
        }

        BookingStatus getStatus() {
            return status;
        }

        /** The business rule lives HERE, enforced by the entity itself, always. */
        void cancel(LocalDate today) {
            if (status != BookingStatus.CONFIRMED) {
                throw new InvalidBookingOperationException(
                        "Cannot cancel a booking with status " + status);
            }
            if (!today.isBefore(checkIn)) {
                throw new InvalidBookingOperationException(
                        "Cannot cancel a booking on or after its check-in date");
            }
            this.status = BookingStatus.CANCELLED;
        }

        void checkIn(LocalDate today) {
            if (status != BookingStatus.CONFIRMED) {
                throw new InvalidBookingOperationException(
                        "Cannot check in a booking with status " + status);
            }
            if (!today.equals(checkIn)) {
                throw new InvalidBookingOperationException(
                        "Cannot check in before the scheduled check-in date");
            }
            this.status = BookingStatus.CHECKED_IN;
        }

        boolean overlaps(LocalDate otherCheckIn, LocalDate otherCheckOut) {
            if (status == BookingStatus.CANCELLED) {
                return false; // a cancelled booking no longer occupies the room
            }
            return checkIn.isBefore(otherCheckOut) && otherCheckIn.isBefore(checkOut);
        }
    }

    /**
     * REPOSITORY INTERFACES DEFINED IN THE DOMAIN LAYER.
     * This is the crux of the Dependency Rule: the domain declares what it
     * needs ("something that can find/save rooms and bookings") without
     * knowing or caring how that's implemented. Infrastructure will
     * implement these interfaces below — dependency points inward.
     */
    interface RoomRepository {
        Optional<Room> findById(String roomId);

        void save(Room room);
    }

    interface BookingRepository {
        Optional<Booking> findById(String bookingId);

        java.util.List<Booking> findByRoomId(String roomId);

        void save(Booking booking);

        String nextId();
    }

    // =========================================================================
    // ========================= APPLICATION LAYER =============================
    // (would live in: com.hotel.application)
    // Depends ONLY on the domain layer (entities + repository interfaces).
    // Orchestrates; does NOT contain business rule conditionals itself.
    // =========================================================================

    static final class BookingService {
        private final RoomRepository roomRepository;
        private final BookingRepository bookingRepository;

        BookingService(RoomRepository roomRepository, BookingRepository bookingRepository) {
            this.roomRepository = roomRepository;
            this.bookingRepository = bookingRepository;
        }

        /**
         * Pure orchestration: fetch, delegate the actual "is this allowed"
         * decision to the domain (via Booking.overlaps), persist. No business
         * rule about WHAT makes a booking valid is decided in this method
         * beyond "is the room free" — a check that inherently needs to see
         * every existing booking and therefore naturally lives at this
         * coordinating layer rather than inside a single Booking instance.
         */
        Booking createBooking(String roomId, LocalDate checkIn, LocalDate checkOut) {
            Room room = roomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("No such room: " + roomId));

            boolean roomIsFree = bookingRepository.findByRoomId(room.getRoomId()).stream()
                    .noneMatch(existing -> existing.overlaps(checkIn, checkOut));

            if (!roomIsFree) {
                throw new RoomUnavailableException(roomId, checkIn, checkOut);
            }

            Booking booking = new Booking(bookingRepository.nextId(), roomId, checkIn, checkOut);
            bookingRepository.save(booking);
            return booking;
        }

        /** Orchestration only — the actual cancellation RULE lives inside Booking.cancel(). */
        void cancelBooking(String bookingId, LocalDate today) {
            Booking booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new IllegalArgumentException("No such booking: " + bookingId));
            booking.cancel(today); // delegate to the entity — no status-checking `if` here
            bookingRepository.save(booking);
        }

        void checkInGuest(String bookingId, LocalDate today) {
            Booking booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new IllegalArgumentException("No such booking: " + bookingId));
            booking.checkIn(today); // delegate to the entity
            bookingRepository.save(booking);
        }
    }

    // =========================================================================
    // ======================== INFRASTRUCTURE LAYER ============================
    // (would live in: com.hotel.infrastructure)
    // Implements domain-defined interfaces. Depends INWARD on domain types
    // (Room, Booking) — domain never depends outward on this layer.
    // In a real system, these would be JPA/JDBC/HTTP-backed implementations;
    // here, simple in-memory maps stand in for a real database.
    // =========================================================================

    static final class InMemoryRoomRepository implements RoomRepository {
        private final Map<String, Room> storage = new HashMap<>();

        @Override
        public Optional<Room> findById(String roomId) {
            return Optional.ofNullable(storage.get(roomId));
        }

        @Override
        public void save(Room room) {
            storage.put(room.getRoomId(), room);
        }
    }

    static final class InMemoryBookingRepository implements BookingRepository {
        private final Map<String, Booking> storage = new HashMap<>();
        private final AtomicLong idSequence = new AtomicLong(1);

        @Override
        public Optional<Booking> findById(String bookingId) {
            return Optional.ofNullable(storage.get(bookingId));
        }

        @Override
        public java.util.List<Booking> findByRoomId(String roomId) {
            return storage.values().stream()
                    .filter(b -> b.getRoomId().equals(roomId))
                    .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public void save(Booking booking) {
            storage.put(booking.getBookingId(), booking);
        }

        @Override
        public String nextId() {
            return "BK-" + idSequence.getAndIncrement();
        }
    }

    // =========================================================================
    // BEFORE/AFTER CONTRAST: the ANEMIC version, for direct comparison
    // =========================================================================

    static final class AnemicBookingExample {

        /** ANEMIC: a pure data bag — no behavior, no self-enforced invariants. */
        static final class AnemicBooking {
            String bookingId;
            String roomId;
            LocalDate checkIn;
            LocalDate checkOut;
            BookingStatus status;

            // just getters/setters — any code anywhere can set `status` to
            // anything, at any time, with no enforcement whatsoever.
        }

        /**
         * ANTI-PATTERN CONTRAST: the cancellation RULE now lives here, in the
         * service, instead of on the entity. Every other service method that
         * also happens to touch AnemicBooking.status must independently
         * remember to re-check these same conditions — nothing stops a
         * second, slightly different implementation from being written
         * elsewhere and silently diverging from this one.
         */
        static void cancel(AnemicBooking booking, LocalDate today) {
            if (booking.status != BookingStatus.CONFIRMED) {
                throw new InvalidBookingOperationException(
                        "Cannot cancel a booking with status " + booking.status);
            }
            if (!today.isBefore(booking.checkIn)) {
                throw new InvalidBookingOperationException(
                        "Cannot cancel a booking on or after its check-in date");
            }
            booking.status = BookingStatus.CANCELLED; // external mutation of internal state
        }
    }

    // =========================================================================
    // MAIN — wire the layers together (composition root, same idea as Topic 1)
    // =========================================================================

    public static void main(String[] args) {
        // Composition root: infrastructure implementations are constructed
        // here and injected into the application layer via domain interfaces.
        RoomRepository roomRepository = new InMemoryRoomRepository();
        BookingRepository bookingRepository = new InMemoryBookingRepository();
        BookingService bookingService = new BookingService(roomRepository, bookingRepository);

        roomRepository.save(new Room("R101", "Deluxe", 150.0));

        System.out.println("--- Creating a booking ---");
        Booking booking = bookingService.createBooking(
                "R101", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12));
        System.out.println("Created " + booking.getBookingId() + " with status " + booking.getStatus());

        System.out.println();
        System.out.println("--- Attempting an overlapping booking (rejected by the domain) ---");
        try {
            bookingService.createBooking(
                    "R101", LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13));
        } catch (RoomUnavailableException e) {
            System.out.println("[REJECTED] " + e.getMessage());
        }

        System.out.println();
        System.out.println("--- Cancelling the booking (rule enforced by Booking.cancel itself) ---");
        bookingService.cancelBooking(booking.getBookingId(), LocalDate.of(2026, 8, 1));
        System.out.println("Booking " + booking.getBookingId() + " is now " + booking.getStatus());

        System.out.println();
        System.out.println("--- Attempting to cancel an already-cancelled booking ---");
        try {
            bookingService.cancelBooking(booking.getBookingId(), LocalDate.of(2026, 8, 1));
        } catch (InvalidBookingOperationException e) {
            System.out.println("[REJECTED] " + e.getMessage());
        }

        System.out.println();
        System.out.println("--- Same rule, ANEMIC style, for direct contrast ---");
        AnemicBookingExample.AnemicBooking anemic = new AnemicBookingExample.AnemicBooking();
        anemic.bookingId = "BK-ANEMIC";
        anemic.roomId = "R101";
        anemic.checkIn = LocalDate.of(2026, 9, 1);
        anemic.checkOut = LocalDate.of(2026, 9, 3);
        anemic.status = BookingStatus.CONFIRMED;
        AnemicBookingExample.cancel(anemic, LocalDate.of(2026, 8, 1));
        System.out.println("Anemic booking status after cancel: " + anemic.status
                + "  (rule was enforced externally, in the service — not by the entity itself)");
    }
}

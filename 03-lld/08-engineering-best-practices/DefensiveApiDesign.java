import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TOPIC 6: DEFENSIVE PROGRAMMING AND API DESIGN
 *
 * Domain: Hotel Booking System (continued from Topics 4-5), with its public
 * API hardened: an immutable DateRange value object, Optional instead of
 * null, the Null Object pattern for an optional collaborator, a minimal
 * BookingService surface with defensive copying, and a fail-fast builder
 * for constructing requests.
 */
public class DefensiveApiDesign {

    // =========================================================================
    // PART 1: IMMUTABLE VALUE OBJECT — replaces two raw LocalDate parameters
    // =========================================================================

    /**
     * Fail-fast at construction: it is IMPOSSIBLE to hold a DateRange whose
     * checkOut is not after its checkIn. Every piece of code that later
     * receives a DateRange can trust that invariant without re-checking it.
     * Immutable (no setters, final fields) — cannot be corrupted after creation.
     */
    static final class DateRange {
        private final LocalDate checkIn;
        private final LocalDate checkOut;

        DateRange(LocalDate checkIn, LocalDate checkOut) {
            if (checkIn == null || checkOut == null) {
                throw new IllegalArgumentException("checkIn and checkOut must not be null");
            }
            if (!checkOut.isAfter(checkIn)) {
                throw new IllegalArgumentException(
                        "checkOut (" + checkOut + ") must be after checkIn (" + checkIn + ")");
            }
            this.checkIn = checkIn;
            this.checkOut = checkOut;
        }

        LocalDate getCheckIn() {
            return checkIn;
        }

        LocalDate getCheckOut() {
            return checkOut;
        }

        /** The value object owns its own behavior, not just its data. */
        boolean overlaps(DateRange other) {
            return this.checkIn.isBefore(other.checkOut) && other.checkIn.isBefore(this.checkOut);
        }

        @Override
        public String toString() {
            return checkIn + " to " + checkOut;
        }
    }

    /** A tiny value object wrapping a raw String ID — prevents mixing up ID types by accident. */
    static final class RoomId {
        private final String value;

        RoomId(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("RoomId must not be blank");
            }
            this.value = value;
        }

        String getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RoomId && ((RoomId) o).value.equals(this.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return value;
        }
    }

    // =========================================================================
    // PART 2: NULL SAFETY — before/after, plus the Null Object pattern
    // =========================================================================

    static final class Room {
        private final RoomId roomId;
        private final double nightlyRate;

        Room(RoomId roomId, double nightlyRate) {
            this.roomId = roomId;
            this.nightlyRate = nightlyRate;
        }

        RoomId getRoomId() {
            return roomId;
        }

        double getNightlyRate() {
            return nightlyRate;
        }
    }

    /** ANTI-PATTERN (for contrast only): returns null when a room isn't found. */
    static final class BadRoomLookup {
        private final Map<RoomId, Room> rooms = new ConcurrentHashMap<>();

        void save(Room room) {
            rooms.put(room.getRoomId(), room);
        }

        Room findById(RoomId roomId) {
            return rooms.get(roomId); // returns null silently — caller must remember to check
        }
    }

    /** THE FIX: Optional<T> makes "might not exist" part of the method's signature. */
    interface RoomRepository {
        Optional<Room> findById(RoomId roomId);

        void save(Room room);
    }

    static final class InMemoryRoomRepository implements RoomRepository {
        private final Map<RoomId, Room> rooms = new ConcurrentHashMap<>();

        @Override
        public Optional<Room> findById(RoomId roomId) {
            return Optional.ofNullable(rooms.get(roomId));
        }

        @Override
        public void save(Room room) {
            rooms.put(room.getRoomId(), room);
        }
    }

    /**
     * NULL OBJECT PATTERN: a collaborator interface for an OPTIONAL
     * behavior. Instead of allowing `notificationSender` to be null and
     * scattering `if (notificationSender != null)` checks through
     * BookingService, we give it a real, do-nothing implementation.
     * Calling code never needs a null check at all.
     */
    interface NotificationSender {
        void notifyBookingCreated(String bookingId, RoomId roomId);
    }

    static final class EmailNotificationSender implements NotificationSender {
        @Override
        public void notifyBookingCreated(String bookingId, RoomId roomId) {
            System.out.println("[EMAIL] Booking " + bookingId + " confirmed for room " + roomId);
        }
    }

    /** The Null Object: implements the full interface contract by doing nothing. */
    static final class NoOpNotificationSender implements NotificationSender {
        @Override
        public void notifyBookingCreated(String bookingId, RoomId roomId) {
            // Intentionally does nothing — used when a guest has opted out
            // of notifications. BookingService never has to null-check.
        }
    }

    // =========================================================================
    // PART 3: FAIL-FAST BUILDER — guarantees a BookingRequest is always valid
    // =========================================================================

    /**
     * A BookingRequest can only come into existence fully valid. Builder's
     * build() is the single place all required-field validation happens —
     * once built, every downstream consumer can trust it completely.
     */
    static final class BookingRequest {
        private final RoomId roomId;
        private final DateRange dateRange;
        private final boolean notificationsOptedIn;

        private BookingRequest(RoomId roomId, DateRange dateRange, boolean notificationsOptedIn) {
            this.roomId = roomId;
            this.dateRange = dateRange;
            this.notificationsOptedIn = notificationsOptedIn;
        }

        RoomId getRoomId() {
            return roomId;
        }

        DateRange getDateRange() {
            return dateRange;
        }

        boolean isNotificationsOptedIn() {
            return notificationsOptedIn;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private RoomId roomId;
            private DateRange dateRange;
            private boolean notificationsOptedIn = true;

            Builder roomId(String roomId) {
                this.roomId = new RoomId(roomId); // fails fast if blank
                return this;
            }

            Builder dates(LocalDate checkIn, LocalDate checkOut) {
                this.dateRange = new DateRange(checkIn, checkOut); // fails fast if invalid
                return this;
            }

            Builder optOutOfNotifications() {
                this.notificationsOptedIn = false;
                return this;
            }

            BookingRequest build() {
                // FAIL-FAST: required fields must be present before this
                // object is allowed to exist at all.
                if (roomId == null) {
                    throw new IllegalStateException("roomId is required");
                }
                if (dateRange == null) {
                    throw new IllegalStateException("dates are required");
                }
                return new BookingRequest(roomId, dateRange, notificationsOptedIn);
            }
        }
    }

    // =========================================================================
    // PART 4: MINIMAL PUBLIC API SURFACE — BookingService
    // =========================================================================

    static class RoomUnavailableException extends RuntimeException {
        RoomUnavailableException(RoomId roomId, DateRange range) {
            super("Room " + roomId + " is not available for " + range);
        }
    }

    static final class Booking {
        private final String bookingId;
        private final RoomId roomId;
        private final DateRange dateRange;

        Booking(String bookingId, RoomId roomId, DateRange dateRange) {
            this.bookingId = bookingId;
            this.roomId = roomId;
            this.dateRange = dateRange;
        }

        String getBookingId() {
            return bookingId;
        }

        RoomId getRoomId() {
            return roomId;
        }

        DateRange getDateRange() {
            return dateRange;
        }
    }

    /**
     * MINIMAL SURFACE: only three public methods, each an intent-revealing
     * action. Internal storage (a mutable List) is NEVER returned directly —
     * getBookingsForRoom returns an unmodifiable view, so no caller can
     * corrupt internal state through the reference they receive.
     */
    static final class BookingService {
        private final RoomRepository roomRepository;
        private final NotificationSender notificationSender; // never null, thanks to Null Object pattern
        private final List<Booking> allBookings = new ArrayList<>();
        private final AtomicLong idSeq = new AtomicLong(1);

        BookingService(RoomRepository roomRepository, NotificationSender notificationSender) {
            this.roomRepository = roomRepository;
            // Defaults to the Null Object if none supplied — never null internally.
            this.notificationSender = notificationSender != null ? notificationSender : new NoOpNotificationSender();
        }

        Booking createBooking(BookingRequest request) {
            Room room = roomRepository.findById(request.getRoomId())
                    .orElseThrow(() -> new IllegalArgumentException("No such room: " + request.getRoomId()));

            boolean roomIsFree = allBookings.stream()
                    .filter(b -> b.getRoomId().equals(room.getRoomId()))
                    .noneMatch(existing -> existing.getDateRange().overlaps(request.getDateRange()));

            if (!roomIsFree) {
                throw new RoomUnavailableException(request.getRoomId(), request.getDateRange());
            }

            Booking booking = new Booking("BK-" + idSeq.getAndIncrement(), request.getRoomId(), request.getDateRange());
            allBookings.add(booking);

            if (request.isNotificationsOptedIn()) {
                notificationSender.notifyBookingCreated(booking.getBookingId(), booking.getRoomId());
            }
            // Note: no null-check needed here even when opted out — the
            // NoOpNotificationSender path never reaches this branch anyway,
            // and even if it did, calling it is always safe by construction.

            return booking;
        }

        void cancelBooking(String bookingId) {
            Booking toCancel = allBookings.stream()
                    .filter(b -> b.getBookingId().equals(bookingId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No such booking: " + bookingId));
            allBookings.remove(toCancel);
        }

        /** DEFENSIVE COPY: caller gets a read-only view, never the live internal list. */
        List<Booking> getBookingsForRoom(RoomId roomId) {
            List<Booking> result = new ArrayList<>();
            for (Booking b : allBookings) {
                if (b.getRoomId().equals(roomId)) {
                    result.add(b);
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    // =========================================================================
    // PART 5: ANTI-PATTERN DEMONSTRATIONS — explicitly labeled, for contrast
    // =========================================================================

    static final class AntiPatternDemos {

        /** ANTI-PATTERN 1: returning null instead of Optional. */
        static void nullReturnDemo() {
            BadRoomLookup badLookup = new BadRoomLookup();
            Room missing = badLookup.findById(new RoomId("R999")); // not saved -> returns null
            System.out.println("[ANTI-PATTERN] nullReturnDemo: findById returned "
                    + (missing == null ? "null" : missing)
                    + " — every caller must remember a null check, or crash with NPE far from here.");
        }

        /** ANTI-PATTERN 2: leaking a mutable internal list directly. */
        static final class LeakyBookingStore {
            private final List<String> bookingIds = new ArrayList<>();

            void add(String id) {
                bookingIds.add(id);
            }

            /** BAD: returns the live internal list — caller can mutate our state directly. */
            List<String> getBookingIdsUnsafe() {
                return bookingIds;
            }
        }

        static void mutableLeakDemo() {
            LeakyBookingStore store = new LeakyBookingStore();
            store.add("BK-1");
            List<String> leaked = store.getBookingIdsUnsafe();
            leaked.add("BK-INJECTED-BY-CALLER"); // corrupts internal state from outside!
            System.out.println("[ANTI-PATTERN] mutableLeakDemo: internal state after external mutation = "
                    + store.getBookingIdsUnsafe()
                    + " — caller corrupted internal state without BookingStore's knowledge.");
        }

        /** ANTI-PATTERN 3: primitive obsession — swapped arguments compile fine, but are wrong. */
        static void createBookingPrimitiveObsessed(String roomId, String checkIn, String checkOut) {
            System.out.println("[ANTI-PATTERN] primitiveObsessionDemo: booking room=" + roomId
                    + " checkIn=" + checkIn + " checkOut=" + checkOut
                    + " — if a caller swaps checkIn/checkOut, this STILL COMPILES because both are String.");
        }
    }

    // =========================================================================
    // MAIN
    // =========================================================================

    public static void main(String[] args) {
        RoomRepository roomRepository = new InMemoryRoomRepository();
        roomRepository.save(new Room(new RoomId("R101"), 150.0));

        System.out.println("--- Fail-fast builder: valid request ---");
        BookingRequest request = BookingRequest.builder()
                .roomId("R101")
                .dates(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12))
                .build();
        System.out.println("Built request for room " + request.getRoomId() + ", " + request.getDateRange());

        System.out.println();
        System.out.println("--- Fail-fast builder: invalid dates rejected immediately ---");
        try {
            BookingRequest.builder()
                    .roomId("R101")
                    .dates(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 10)) // swapped!
                    .build();
        } catch (IllegalArgumentException e) {
            System.out.println("[REJECTED at construction] " + e.getMessage());
        }

        System.out.println();
        System.out.println("--- BookingService with real notification sender ---");
        BookingService serviceWithEmail = new BookingService(roomRepository, new EmailNotificationSender());
        Booking booking = serviceWithEmail.createBooking(request);
        System.out.println("Created " + booking.getBookingId());

        System.out.println();
        System.out.println("--- BookingService with Null Object (guest opted out, no null checks needed) ---");
        BookingService serviceNoNotify = new BookingService(roomRepository, null); // defaults to NoOp internally
        BookingRequest optOutRequest = BookingRequest.builder()
                .roomId("R101")
                .dates(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))
                .optOutOfNotifications()
                .build();
        Booking booking2 = serviceNoNotify.createBooking(optOutRequest);
        System.out.println("Created " + booking2.getBookingId() + " silently — no notification, no null-check needed anywhere.");

        System.out.println();
        System.out.println("--- Defensive copy: caller cannot mutate internal state ---");
        List<Booking> bookings = serviceWithEmail.getBookingsForRoom(new RoomId("R101"));
        try {
            bookings.add(null); // attempting to mutate the returned view
        } catch (UnsupportedOperationException e) {
            System.out.println("[BLOCKED] Caller attempted to mutate the returned list: " + e.getClass().getSimpleName());
        }

        System.out.println();
        System.out.println("=== Anti-pattern demonstrations (what NOT to do) ===");
        AntiPatternDemos.nullReturnDemo();
        AntiPatternDemos.mutableLeakDemo();
        AntiPatternDemos.createBookingPrimitiveObsessed("R101", "2026-08-12", "2026-08-10"); // swapped, still compiles
    }
}

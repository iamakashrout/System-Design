import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TOPIC 5: LOGGING AND OBSERVABILITY
 *
 * Domain: Hotel Booking System (continued from Topic 4's Clean Architecture
 * example), instrumented with structured logging, correlation IDs, metrics,
 * and a minimal tracing span — to make the logging/metrics/tracing
 * distinction concrete rather than abstract.
 *
 * No external logging framework (SLF4J/Logback) is used, since this
 * environment has no Maven dependency access — the Logger abstraction below
 * mirrors the shape of a real structured logging API closely enough that
 * swapping in SLF4J + Logstash-encoder later is a drop-in replacement.
 */
public class LoggingObservability {

    // =========================================================================
    // PART 1: STRUCTURED LOGGER ABSTRACTION
    // =========================================================================

    enum LogLevel {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    interface Logger {
        void log(LogLevel level, String event, Map<String, Object> fields);

        void log(LogLevel level, String event, Map<String, Object> fields, Throwable cause);

        default void debug(String event, Map<String, Object> fields) {
            log(LogLevel.DEBUG, event, fields);
        }

        default void info(String event, Map<String, Object> fields) {
            log(LogLevel.INFO, event, fields);
        }

        default void warn(String event, Map<String, Object> fields) {
            log(LogLevel.WARN, event, fields);
        }

        default void error(String event, Map<String, Object> fields, Throwable cause) {
            log(LogLevel.ERROR, event, fields, cause);
        }
    }

    /**
     * Emits STRUCTURED log lines (key-value, not free text). The
     * correlation ID is pulled automatically from CorrelationContext and
     * merged into every line, so callers never have to pass it explicitly.
     * MINIMUM_LEVEL demonstrates level discipline — TRACE/DEBUG are
     * suppressed here the way they typically would be in production.
     */
    static final class ConsoleLogger implements Logger {
        private static final LogLevel MINIMUM_LEVEL = LogLevel.DEBUG;

        @Override
        public void log(LogLevel level, String event, Map<String, Object> fields) {
            log(level, event, fields, null);
        }

        @Override
        public void log(LogLevel level, String event, Map<String, Object> fields, Throwable cause) {
            if (level.ordinal() < MINIMUM_LEVEL.ordinal()) {
                return; // level discipline: TRACE suppressed even when DEBUG is the floor
            }

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("timestamp", Instant.now());
            line.put("level", level);
            line.put("event", event);
            String correlationId = CorrelationContext.getCorrelationId();
            if (correlationId != null) {
                line.put("correlationId", correlationId);
            }
            line.putAll(fields);

            System.out.println(toJsonish(line));
            if (cause != null) {
                // Preserve the full cause chain (Topic 3) — never swallow it.
                System.out.println("    caused by: " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
            }
        }

        private String toJsonish(Map<String, Object> fields) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof Number || value instanceof LogLevel) {
                    sb.append("\"").append(value).append("\"");
                } else {
                    sb.append("\"").append(value).append("\"");
                }
            }
            sb.append("}");
            return sb.toString();
        }
    }

    // =========================================================================
    // PART 2: CORRELATION CONTEXT — threads one ID through an entire request
    // =========================================================================

    /**
     * ThreadLocal-based propagation. Within a single process/thread, every
     * log line emitted during the handling of one "request" automatically
     * picks up the same correlation ID without it being passed as an
     * explicit parameter through every method signature.
     *
     * NOTE: this only solves the single-process case. A real distributed
     * system must propagate this same ID across service boundaries (e.g.
     * via an HTTP header), which is a related but distinct problem.
     */
    static final class CorrelationContext {
        private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

        static String startNewRequest() {
            String id = UUID.randomUUID().toString().substring(0, 8);
            CURRENT.set(id);
            return id;
        }

        static String getCorrelationId() {
            return CURRENT.get();
        }

        static void clear() {
            CURRENT.remove();
        }
    }

    // =========================================================================
    // PART 3: METRICS — "how much / how often", separate from logging
    // =========================================================================

    /** A minimal metrics recorder — pre-aggregated counters, not individual events. */
    static final class Metrics {
        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

        void increment(String metricName) {
            counters.computeIfAbsent(metricName, k -> new AtomicLong()).incrementAndGet();
        }

        long get(String metricName) {
            AtomicLong counter = counters.get(metricName);
            return counter == null ? 0 : counter.get();
        }

        void printSummary() {
            System.out.println("[METRICS SUMMARY] " + counters);
        }
    }

    // =========================================================================
    // PART 4: TRACING — "where time was spent", as a tree of timed spans
    // =========================================================================

    /** A minimal span — records how long one named step took. */
    static final class Span implements AutoCloseable {
        private final String name;
        private final long startNanos;
        private final Logger logger;

        Span(String name, Logger logger) {
            this.name = name;
            this.logger = logger;
            this.startNanos = System.nanoTime();
        }

        @Override
        public void close() {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("span", name);
            fields.put("durationMs", elapsedMillis);
            logger.debug("span_completed", fields);
        }
    }

    // =========================================================================
    // PART 5: DOMAIN (simplified from Topic 4) — instrumented BookingService
    // =========================================================================

    enum BookingStatus {
        CONFIRMED, CANCELLED
    }

    static class RoomUnavailableException extends RuntimeException {
        RoomUnavailableException(String roomId) {
            super("Room " + roomId + " is not available for the requested dates");
        }
    }

    static final class Booking {
        final String bookingId;
        final String roomId;
        final LocalDate checkIn;
        final LocalDate checkOut;
        BookingStatus status = BookingStatus.CONFIRMED;

        Booking(String bookingId, String roomId, LocalDate checkIn, LocalDate checkOut) {
            this.bookingId = bookingId;
            this.roomId = roomId;
            this.checkIn = checkIn;
            this.checkOut = checkOut;
        }
    }

    /**
     * BookingService instrumented with structured logging, metrics, and a
     * tracing span — demonstrating all three working together on the same
     * request, each answering a different question about it.
     */
    static final class BookingService {
        private final Logger logger;
        private final Metrics metrics;
        private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
        private final AtomicLong idSeq = new AtomicLong(1);
        private boolean simulateDatabaseFailure = false;

        BookingService(Logger logger, Metrics metrics) {
            this.logger = logger;
            this.metrics = metrics;
        }

        void setSimulateDatabaseFailure(boolean value) {
            this.simulateDatabaseFailure = value;
        }

        Booking createBooking(String roomId, LocalDate checkIn, LocalDate checkOut, boolean roomAvailable) {
            try (Span span = new Span("createBooking", logger)) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("roomId", roomId);
                context.put("checkIn", checkIn);
                context.put("checkOut", checkOut);

                // DEBUG: fine-grained flow detail, off by default in prod volume terms
                logger.debug("booking_validation_started", context);

                if (!roomAvailable) {
                    // WARN: expected, handled business outcome — NOT an ERROR.
                    logger.warn("booking_rejected", mergeContext(context, "reason", "room_unavailable"));
                    metrics.increment("booking.rejected");
                    throw new RoomUnavailableException(roomId);
                }

                if (simulateDatabaseFailure) {
                    RuntimeException dbFailure = new RuntimeException("connection refused: db-primary:5432");
                    // ERROR: genuine unexpected failure, full cause chain preserved (Topic 3).
                    logger.error("booking_persist_failed", context, dbFailure);
                    metrics.increment("booking.failed");
                    throw new RuntimeException("Failed to persist booking", dbFailure);
                }

                Booking booking = new Booking("BK-" + idSeq.getAndIncrement(), roomId, checkIn, checkOut);
                bookings.put(booking.bookingId, booking);

                // INFO: a business-significant event — always on in production.
                logger.info("booking_created", mergeContext(context, "bookingId", booking.bookingId));
                metrics.increment("booking.created");

                return booking;
            }
        }

        private Map<String, Object> mergeContext(Map<String, Object> base, String key, Object value) {
            Map<String, Object> merged = new LinkedHashMap<>(base);
            merged.put(key, value);
            return merged;
        }
    }

    // =========================================================================
    // PART 6: ANTI-PATTERN DEMONSTRATIONS — explicitly labeled, for contrast
    // =========================================================================

    static final class AntiPatternDemos {

        /** ANTI-PATTERN 1: unstructured free-text logging. */
        static void unstructuredLog() {
            // Cannot be queried ("show me all rejections for room R101") —
            // it's just a string. Compare to logger.warn("booking_rejected", {...}).
            System.out.println("LOG: Booking failed for room 101, dates overlap with existing booking");
            System.out.println("[ANTI-PATTERN] unstructuredLog: not queryable/aggregable at scale.");
        }

        /** ANTI-PATTERN 2: logging sensitive data in full. */
        static void loggedSensitiveData(String fullCardNumber) {
            // NEVER do this — full card number in a log that may be retained
            // for months and shipped to third-party log aggregation tools.
            System.out.println("LOG: Payment attempted with card " + fullCardNumber);
            System.out.println("[ANTI-PATTERN] loggedSensitiveData: should be masked, e.g. **** **** **** "
                    + fullCardNumber.substring(fullCardNumber.length() - 4));
        }

        /** ANTI-PATTERN 3: using ERROR for an expected business outcome. */
        static void misusedErrorLevel(Logger logger) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("roomId", "R101");
            // WRONG: this is a routine, anticipated outcome — should be WARN, not ERROR.
            logger.log(LogLevel.ERROR, "booking_rejected_MISUSED", fields);
            System.out.println("[ANTI-PATTERN] misusedErrorLevel: expected outcomes logged at ERROR "
                    + "train engineers to ignore real ERROR alerts.");
        }
    }

    // =========================================================================
    // MAIN — a full "request" flow: correlation ID, structured logs,
    // metrics, and a tracing span, all working together.
    // =========================================================================

    public static void main(String[] args) {
        Logger logger = new ConsoleLogger();
        Metrics metrics = new Metrics();
        BookingService bookingService = new BookingService(logger, metrics);

        System.out.println("--- Request 1: successful booking (correlation ID threads every line) ---");
        CorrelationContext.startNewRequest();
        bookingService.createBooking("R101", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), true);
        CorrelationContext.clear();

        System.out.println();
        System.out.println("--- Request 2: rejected booking (WARN, not ERROR — expected outcome) ---");
        CorrelationContext.startNewRequest();
        try {
            bookingService.createBooking("R101", LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), false);
        } catch (RoomUnavailableException e) {
            // handled — the WARN log above already captured why
        }
        CorrelationContext.clear();

        System.out.println();
        System.out.println("--- Request 3: infrastructure failure (ERROR, full cause chain preserved) ---");
        CorrelationContext.startNewRequest();
        bookingService.setSimulateDatabaseFailure(true);
        try {
            bookingService.createBooking("R102", LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 16), true);
        } catch (RuntimeException e) {
            // handled — the ERROR log above already captured the cause
        }
        CorrelationContext.clear();

        System.out.println();
        System.out.println("--- Metrics: how much/how often, aggregated across all requests above ---");
        metrics.printSummary();

        System.out.println();
        System.out.println("=== Anti-pattern demonstrations (what NOT to do) ===");
        AntiPatternDemos.unstructuredLog();
        AntiPatternDemos.loggedSensitiveData("4111111111111234");
        AntiPatternDemos.misusedErrorLevel(logger);
    }
}

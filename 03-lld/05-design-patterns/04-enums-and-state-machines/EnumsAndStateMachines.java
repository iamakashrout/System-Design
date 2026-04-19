import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// =============================================================================
// TOPIC: Enums and State Machines
// PURPOSE: Model domain object lifecycles so that illegal state transitions
//          are impossible by design — not just prevented by convention.
//
// REAL-WORLD PROBLEM:
//   An Order progresses through: PENDING → CONFIRMED → PROCESSING → SHIPPED
//   → DELIVERED. These transitions are business rules:
//     - You cannot ship an order that hasn't been confirmed
//     - You cannot cancel an order that's already been shipped
//     - You cannot do anything with a cancelled order (terminal state)
//
//   The naive approach scatters these rules everywhere:
//     if (order.getStatus() == PENDING) order.setStatus(CONFIRMED); // anyone bypasses this
//   Or worse — no checks at all.
//
//   A state machine makes illegal transitions structurally impossible.
//   They throw a domain exception at the moment the rule is violated,
//   not silently corrupting state.
//
// WHAT THIS FILE COVERS:
//   Part 1: Basic enum vs Rich enum (enums with methods and transition rules)
//   Part 2: Events — the triggers that cause transitions
//   Part 3: Transition table — the central source of truth
//   Part 4: InvalidTransitionException — domain-level failure
//   Part 5: Order domain model — wires everything together
//   Part 6: State handler objects — side effects on enter/exit (combined approach)
//   Part 7: Demo — all scenarios including invalid transitions
// =============================================================================

public class EnumsAndStateMachines {

    // =========================================================================
    // PART 1: RICH ENUM — OrderStatus
    //
    // Java enums are full classes. They can carry:
    //   - Abstract methods (each constant must override)
    //   - Concrete methods (shared by all constants)
    //   - Fields
    //
    // Here, each state declares which states it can legally transition TO.
    // This makes the enum the single source of truth for valid transitions.
    // No external code needs to know these rules — the state knows itself.
    //
    // EnumSet: a specialized Set for enum values, backed by a bit vector.
    //   Use EnumSet instead of HashSet when elements are enum constants.
    //   It's faster (O(1) contains), lower memory, and clearer in intent.
    // =========================================================================
    enum OrderStatus {

        PENDING {
            @Override
            public Set<OrderStatus> validNextStates() {
                // From PENDING: order can be confirmed or cancelled
                return EnumSet.of(CONFIRMED, CANCELLED);
            }

            @Override
            public String getDescription() { return "Order placed, awaiting confirmation"; }
        },

        CONFIRMED {
            @Override
            public Set<OrderStatus> validNextStates() {
                // From CONFIRMED: can move to processing or be cancelled
                return EnumSet.of(PROCESSING, CANCELLED);
            }

            @Override
            public String getDescription() { return "Order confirmed, awaiting processing"; }
        },

        PROCESSING {
            @Override
            public Set<OrderStatus> validNextStates() {
                // From PROCESSING: can ship or cancel
                return EnumSet.of(SHIPPED, CANCELLED);
            }

            @Override
            public String getDescription() { return "Order being prepared for dispatch"; }
        },

        SHIPPED {
            @Override
            public Set<OrderStatus> validNextStates() {
                // NOTICE: CANCELLED is intentionally absent here.
                // Once shipped, the order cannot be cancelled.
                // This business rule is encoded in the state, not in a service class.
                return EnumSet.of(DELIVERED);
            }

            @Override
            public String getDescription() { return "Order dispatched and in transit"; }
        },

        DELIVERED {
            @Override
            public Set<OrderStatus> validNextStates() {
                // After delivery, only a refund is possible
                return EnumSet.of(REFUNDED);
            }

            @Override
            public String getDescription() { return "Order delivered to customer"; }
        },

        CANCELLED {
            @Override
            public Set<OrderStatus> validNextStates() {
                // TERMINAL STATE — no exits from here
                return EnumSet.noneOf(OrderStatus.class);
            }

            @Override
            public String getDescription() { return "Order cancelled"; }
        },

        REFUNDED {
            @Override
            public Set<OrderStatus> validNextStates() {
                // TERMINAL STATE — no exits from here
                return EnumSet.noneOf(OrderStatus.class);
            }

            @Override
            public String getDescription() { return "Order refunded to customer"; }
        };

        // ── Abstract methods — every constant MUST implement these ────────────
        public abstract Set<OrderStatus> validNextStates();
        public abstract String getDescription();

        // ── Concrete method — shared by all constants ─────────────────────────
        // Centralizes transition validation. No one else needs to implement this.
        public boolean canTransitionTo(OrderStatus target) {
            return validNextStates().contains(target);
        }

        // Is this a terminal state? (no exits possible)
        public boolean isTerminal() {
            return validNextStates().isEmpty();
        }
    }


    // =========================================================================
    // PART 2: EVENTS — OrderEvent
    //
    // Events are the triggers that cause transitions.
    // They represent BUSINESS ACTIONS, not technical operations.
    //   "SHIP" is clearer than "setStatusToShipped"
    //   "CANCEL" is clearer than "markAsCancelled"
    //
    // Each event corresponds to one business action in the system.
    // Not all events are valid from all states — the transition table decides.
    // =========================================================================
    enum OrderEvent {
        CONFIRM,            // merchant confirms the order
        START_PROCESSING,   // warehouse picks the order for processing
        SHIP,               // order is dispatched to courier
        DELIVER,            // delivery confirmed (by customer or courier)
        CANCEL,             // order is cancelled by customer or system
        REFUND              // refund is issued after delivery
    }


    // =========================================================================
    // PART 3: INVALID TRANSITION EXCEPTION
    //
    // This is a DOMAIN exception — it represents a violated business rule.
    // It should propagate to the API layer as a 400 Bad Request,
    // NOT be caught and swallowed somewhere in the middle.
    //
    // The key design: illegal transitions THROW LOUDLY.
    // They never silently ignore the request or leave state undefined.
    // =========================================================================
    static class InvalidTransitionException extends RuntimeException {
        private final OrderStatus fromState;
        private final OrderEvent  event;

        public InvalidTransitionException(OrderStatus from, OrderEvent event) {
            super(String.format(
                "Cannot apply event [%s] in state [%s]. Valid events from %s: %s",
                event, from, from, getValidEvents(from)
            ));
            this.fromState = from;
            this.event     = event;
        }

        public OrderStatus getFromState() { return fromState; }
        public OrderEvent  getEvent()     { return event; }

        // Helpful hint: tells the caller which events ARE valid
        private static String getValidEvents(OrderStatus state) {
            return state.validNextStates().isEmpty() ? "none (terminal state)" :
                    state.validNextStates().toString();
        }
    }


    // =========================================================================
    // PART 4: ORDER STATE MACHINE — the transition table
    //
    // This is the heart of the state machine.
    // It maps every legal (currentState, event) → nextState pair.
    // Anything NOT in this table is an illegal transition.
    //
    // EnumMap: a specialized Map with enum keys, backed by an array.
    //   Use EnumMap instead of HashMap when your keys are enum constants.
    //   It's O(1) lookup, lower memory, and type-safe.
    //
    // WHY a separate table if the enum has validNextStates()?
    //   The enum says: "SHIPPED can go to DELIVERED"
    //   The table says: "SHIPPED + DELIVER event → DELIVERED"
    //   Events add the business action dimension — the same target state
    //   might be reachable via different events with different meanings.
    // =========================================================================
    static class OrderStateMachine {

        // The transition table: Map<currentState, Map<event, nextState>>
        // Declared static final — it's a constant, built once, never changes.
        private static final Map<OrderStatus, Map<OrderEvent, OrderStatus>> TRANSITIONS;

        static {
            TRANSITIONS = new EnumMap<>(OrderStatus.class);

            // ── PENDING: can be confirmed or cancelled ────────────────────────
            TRANSITIONS.put(OrderStatus.PENDING, buildMap(
                OrderEvent.CONFIRM,           OrderStatus.CONFIRMED,
                OrderEvent.CANCEL,            OrderStatus.CANCELLED
            ));

            // ── CONFIRMED: can start processing or be cancelled ───────────────
            TRANSITIONS.put(OrderStatus.CONFIRMED, buildMap(
                OrderEvent.START_PROCESSING,  OrderStatus.PROCESSING,
                OrderEvent.CANCEL,            OrderStatus.CANCELLED
            ));

            // ── PROCESSING: can ship or cancel ────────────────────────────────
            TRANSITIONS.put(OrderStatus.PROCESSING, buildMap(
                OrderEvent.SHIP,              OrderStatus.SHIPPED,
                OrderEvent.CANCEL,            OrderStatus.CANCELLED
            ));

            // ── SHIPPED: can only be delivered ────────────────────────────────
            // CANCEL is intentionally absent — cannot cancel after shipping
            TRANSITIONS.put(OrderStatus.SHIPPED, buildMap(
                OrderEvent.DELIVER,           OrderStatus.DELIVERED
            ));

            // ── DELIVERED: can only be refunded ──────────────────────────────
            TRANSITIONS.put(OrderStatus.DELIVERED, buildMap(
                OrderEvent.REFUND,            OrderStatus.REFUNDED
            ));

            // CANCELLED and REFUNDED are terminal — no entries needed
        }

        // ── Core method: apply event to a state, returns the next state ───────
        // Throws InvalidTransitionException if the transition is not in the table.
        public OrderStatus transition(OrderStatus current, OrderEvent event) {
            Map<OrderEvent, OrderStatus> validEvents = TRANSITIONS.get(current);

            if (validEvents == null || !validEvents.containsKey(event)) {
                throw new InvalidTransitionException(current, event);
            }

            return validEvents.get(event);
        }

        // ── Non-throwing check — for UI or pre-validation ─────────────────────
        // Use this to decide whether to show a "Cancel" button, for example.
        public boolean canApply(OrderStatus current, OrderEvent event) {
            Map<OrderEvent, OrderStatus> validEvents = TRANSITIONS.get(current);
            return validEvents != null && validEvents.containsKey(event);
        }

        // ── Returns all valid events from a given state ───────────────────────
        public Set<OrderEvent> validEventsFrom(OrderStatus state) {
            Map<OrderEvent, OrderStatus> validEvents = TRANSITIONS.get(state);
            return validEvents != null
                    ? Collections.unmodifiableSet(validEvents.keySet())
                    : Collections.emptySet();
        }

        // ── Helper: builds an EnumMap from alternating (event, state) pairs ───
        private static Map<OrderEvent, OrderStatus> buildMap(Object... pairs) {
            Map<OrderEvent, OrderStatus> map = new EnumMap<>(OrderEvent.class);
            for (int i = 0; i < pairs.length; i += 2) {
                map.put((OrderEvent) pairs[i], (OrderStatus) pairs[i + 1]);
            }
            return Collections.unmodifiableMap(map);
        }
    }


    // =========================================================================
    // PART 5: STATE HANDLER INTERFACE — the combined approach
    //
    // The enum owns the TRANSITION GRAPH (which state follows which event).
    // State handler objects own the SIDE EFFECTS (what happens on enter/exit).
    //
    // This is the "combined approach" — enum for routing, handlers for behavior.
    // It's clean separation: business analysts read the enum to understand flow;
    // engineers modify handlers to change what happens during transitions.
    // =========================================================================
    interface OrderStateHandler {
        void onEnter(Order order);   // called when entering this state
        void onExit(Order order);    // called when leaving this state
        OrderStatus handlesState();  // which state this handler is for
    }

    // ── Handler for SHIPPED state: simulate sending notifications ─────────────
    static class ShippedStateHandler implements OrderStateHandler {
        @Override
        public void onEnter(Order order) {
            // In a real system: call NotificationService, AnalyticsService, etc.
            System.out.println("    [ShippedHandler.onEnter] Sending shipping SMS to customer "
                    + order.getCustomerId());
            System.out.println("    [ShippedHandler.onEnter] Recording shipment in analytics");
            System.out.println("    [ShippedHandler.onEnter] Starting delivery timer");
        }

        @Override
        public void onExit(Order order) {
            System.out.println("    [ShippedHandler.onExit] Stopping delivery timer");
        }

        @Override
        public OrderStatus handlesState() { return OrderStatus.SHIPPED; }
    }

    // ── Handler for DELIVERED state ────────────────────────────────────────────
    static class DeliveredStateHandler implements OrderStateHandler {
        @Override
        public void onEnter(Order order) {
            System.out.println("    [DeliveredHandler.onEnter] Sending delivery confirmation to "
                    + order.getCustomerId());
            System.out.println("    [DeliveredHandler.onEnter] Starting 7-day return window");
            System.out.println("    [DeliveredHandler.onEnter] Releasing payment to merchant");
        }

        @Override
        public void onExit(Order order) {
            // Nothing special on exit from DELIVERED
        }

        @Override
        public OrderStatus handlesState() { return OrderStatus.DELIVERED; }
    }

    // ── Handler for CANCELLED state ────────────────────────────────────────────
    static class CancelledStateHandler implements OrderStateHandler {
        @Override
        public void onEnter(Order order) {
            System.out.println("    [CancelledHandler.onEnter] Restoring inventory for "
                    + order.getOrderId());
            System.out.println("    [CancelledHandler.onEnter] Triggering refund if payment was taken");
        }

        @Override
        public void onExit(Order order) {
            // Terminal state — onExit will never be called (no transitions out)
        }

        @Override
        public OrderStatus handlesState() { return OrderStatus.CANCELLED; }
    }


    // =========================================================================
    // PART 6: ORDER — the domain entity
    //
    // The Order class wires everything together. Key design decisions:
    //
    //   1. applyTransition() is the SINGLE CHOKEPOINT
    //      Every state change goes through it — nothing bypasses it.
    //      This is where validation, audit logging, and handler invocation live.
    //
    //   2. Domain methods use BUSINESS LANGUAGE
    //      confirm(), ship(), cancel() — not setStatus(CONFIRMED)
    //
    //   3. Audit trail is AUTOMATIC
    //      Because every transition goes through applyTransition(), every change
    //      is recorded. No extra code needed in individual domain methods.
    //
    //   4. Invalid transitions THROW at the domain level
    //      Never silently ignored. Never return boolean success/failure.
    //      This follows the "fail fast" principle.
    // =========================================================================
    static class Order {
        private final String          orderId;
        private final String          customerId;
        private       OrderStatus     status;
        private final List<String>    statusHistory;   // full audit trail
        private final LocalDateTime   createdAt;
        private       LocalDateTime   updatedAt;

        // Shared state machine — it's stateless so safe to share across instances
        private static final OrderStateMachine stateMachine = new OrderStateMachine();

        // State handlers — look up by state when entering/exiting
        private static final Map<OrderStatus, OrderStateHandler> STATE_HANDLERS;
        static {
            STATE_HANDLERS = new EnumMap<>(OrderStatus.class);
            STATE_HANDLERS.put(OrderStatus.SHIPPED,   new ShippedStateHandler());
            STATE_HANDLERS.put(OrderStatus.DELIVERED, new DeliveredStateHandler());
            STATE_HANDLERS.put(OrderStatus.CANCELLED, new CancelledStateHandler());
            // States without handlers are fine — handlers are optional
        }

        private static final DateTimeFormatter DISPLAY_FMT =
                DateTimeFormatter.ofPattern("HH:mm:ss");

        public Order(String orderId, String customerId) {
            this.orderId       = orderId;
            this.customerId    = customerId;
            this.status        = OrderStatus.PENDING; // always starts PENDING
            this.statusHistory = new ArrayList<>();
            this.createdAt     = LocalDateTime.now();
            this.updatedAt     = createdAt;

            // Record the initial state in history
            String entry = String.format("[%s] START → PENDING | Order created",
                    createdAt.format(DISPLAY_FMT));
            statusHistory.add(entry);
        }

        // ── Domain methods — business language maps to state machine events ────

        public void confirm() {
            applyTransition(OrderEvent.CONFIRM, "Order confirmed by merchant");
        }

        public void startProcessing() {
            applyTransition(OrderEvent.START_PROCESSING, "Order picked for processing");
        }

        public void ship(String trackingNumber) {
            applyTransition(OrderEvent.SHIP, "Shipped | Tracking: " + trackingNumber);
        }

        public void deliver() {
            applyTransition(OrderEvent.DELIVER, "Delivered to customer");
        }

        public void cancel(String reason) {
            applyTransition(OrderEvent.CANCEL, "Cancelled: " + reason);
        }

        public void refund(String reason) {
            applyTransition(OrderEvent.REFUND, "Refunded: " + reason);
        }

        // ── The single chokepoint — ALL transitions go through here ───────────
        private void applyTransition(OrderEvent event, String note) {
            OrderStatus previous = this.status;

            // 1. Validate and get the next state
            //    Throws InvalidTransitionException if illegal — never silently fails
            OrderStatus next = stateMachine.transition(this.status, event);

            // 2. Invoke exit handler for the current state (if registered)
            OrderStateHandler exitHandler = STATE_HANDLERS.get(previous);
            if (exitHandler != null) {
                exitHandler.onExit(this);
            }

            // 3. Apply the transition
            this.status    = next;
            this.updatedAt = LocalDateTime.now();

            // 4. Record in audit trail
            String entry = String.format("[%s] %s → %s | %s",
                    updatedAt.format(DISPLAY_FMT), previous.name(), next.name(), note);
            statusHistory.add(entry);

            // 5. Console output for visibility
            System.out.printf("  [%s] %s → %s | %s%n",
                    orderId, previous, next, note);

            // 6. Invoke enter handler for the new state (if registered)
            OrderStateHandler enterHandler = STATE_HANDLERS.get(next);
            if (enterHandler != null) {
                enterHandler.onEnter(this);
            }
        }

        // ── Query methods — safe reads without mutating state ─────────────────

        // Check before calling cancel() — useful for UI: show/hide Cancel button
        public boolean canCancel() {
            return stateMachine.canApply(status, OrderEvent.CANCEL);
        }

        public boolean isTerminal() {
            return status.isTerminal();
        }

        // What events can be applied right now?
        public Set<OrderEvent> availableEvents() {
            return stateMachine.validEventsFrom(status);
        }

        public OrderStatus  getStatus()       { return status; }
        public String       getOrderId()      { return orderId; }
        public String       getCustomerId()   { return customerId; }

        public void printHistory() {
            System.out.println("  ── Status History: " + orderId + " ──────────────────────");
            statusHistory.forEach(e -> System.out.println("  " + e));
            System.out.println("  Current: " + status + " | " + status.getDescription());
        }

        public void printAvailableActions() {
            Set<OrderEvent> events = availableEvents();
            if (events.isEmpty()) {
                System.out.println("  [" + orderId + "] No actions available (terminal state: "
                        + status + ")");
            } else {
                System.out.println("  [" + orderId + "] Available actions from " + status
                        + ": " + events);
            }
        }
    }


    // =========================================================================
    // DEMONSTRATION: Basic enum contrast
    // Shows the difference between a plain enum and a rich enum.
    // =========================================================================
    static void demonstrateRichEnum() {
        System.out.println("─── Rich Enum Capabilities ──────────────────────────────────────");

        // Each state knows its own description
        for (OrderStatus state : OrderStatus.values()) {
            System.out.printf("  %-12s → %-45s | terminal: %b%n",
                    state,
                    state.getDescription(),
                    state.isTerminal());
        }

        System.out.println();

        // Each state knows its own valid transitions
        for (OrderStatus state : OrderStatus.values()) {
            System.out.printf("  %-12s can go to: %s%n",
                    state,
                    state.isTerminal() ? "NOWHERE (terminal)" : state.validNextStates().toString());
        }

        System.out.println();

        // canTransitionTo() — inline validation without external service
        System.out.println("  Inline validation (no service needed):");
        System.out.printf("  SHIPPED.canTransitionTo(CANCELLED) = %b  (business rule: no cancel after ship)%n",
                OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED));
        System.out.printf("  SHIPPED.canTransitionTo(DELIVERED) = %b%n",
                OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED));
        System.out.printf("  CANCELLED.canTransitionTo(PENDING) = %b  (terminal — no way back)%n",
                OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PENDING));
    }


    // =========================================================================
    // MAIN — all scenarios
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Enums and State Machines Demo ===\n");

        // ── Show rich enum capabilities first ─────────────────────────────────
        demonstrateRichEnum();


        // ── Scenario 1: Happy path — full lifecycle ───────────────────────────
        System.out.println("\n─── Scenario 1: Happy Path (PENDING → DELIVERED) ────────────────");
        Order order1 = new Order("ORD-001", "USR-42");

        System.out.println("  Available actions initially: " + order1.availableEvents());
        order1.confirm();
        order1.startProcessing();
        order1.ship("TRACK-XYZ-9876");
        order1.deliver();

        System.out.println();
        order1.printHistory();


        // ── Scenario 2: Cancellation path ─────────────────────────────────────
        System.out.println("\n─── Scenario 2: Cancellation (CONFIRMED → CANCELLED) ────────────");
        Order order2 = new Order("ORD-002", "USR-55");
        order2.confirm();

        System.out.printf("  Can cancel from CONFIRMED? %b%n", order2.canCancel());
        order2.printAvailableActions();

        order2.cancel("Customer changed their mind");

        System.out.printf("  Is terminal? %b%n", order2.isTerminal());
        order2.printAvailableActions(); // should show: no actions available
        System.out.println();
        order2.printHistory();


        // ── Scenario 3: Illegal transition — SHIPPED cannot be cancelled ───────
        System.out.println("\n─── Scenario 3: Illegal Transition (SHIP then try to CANCEL) ────");
        Order order3 = new Order("ORD-003", "USR-77");
        order3.confirm();
        order3.startProcessing();
        order3.ship("TRACK-ABC-1234");

        System.out.printf("%n  Can cancel after shipping? %b%n", order3.canCancel());

        try {
            order3.cancel("Customer wants to cancel — but too late!"); // should throw
        } catch (InvalidTransitionException e) {
            System.out.println("  ✓ Caught InvalidTransitionException:");
            System.out.println("    " + e.getMessage());
        }

        // Order state is unchanged after the failed attempt — no corruption
        System.out.println("  Order state after failed cancel: " + order3.getStatus()
                + " (unchanged ✓)");

        // Complete the order normally
        order3.deliver();
        order3.refund("Item arrived damaged");
        System.out.println();
        order3.printHistory();


        // ── Scenario 4: Terminal state — nothing works after CANCELLED ─────────
        System.out.println("\n─── Scenario 4: Terminal State (double-cancel attempt) ───────────");
        Order order4 = new Order("ORD-004", "USR-99");
        order4.cancel("Out of stock");

        System.out.println("  Order is now terminal: " + order4.isTerminal());
        System.out.println("  Available actions: " + order4.availableEvents());

        // Try EVERY possible event on a terminal state — all should throw
        OrderEvent[] events = {
            OrderEvent.CANCEL, OrderEvent.CONFIRM,
            OrderEvent.SHIP,   OrderEvent.DELIVER, OrderEvent.REFUND
        };
        for (OrderEvent event : events) {
            try {
                // We can't call domain methods here so we test the state machine directly
                new OrderStateMachine().transition(OrderStatus.CANCELLED, event);
                System.out.println("  ERROR: " + event + " should have thrown!");
            } catch (InvalidTransitionException e) {
                System.out.println("  ✓ " + event + " correctly rejected from CANCELLED");
            }
        }


        // ── Scenario 5: State handlers (combined approach) ────────────────────
        System.out.println("\n─── Scenario 5: State Handlers (side effects on enter/exit) ─────");
        System.out.println("  (Watch for handler output when SHIPPED, DELIVERED, CANCELLED occur)\n");

        Order order5 = new Order("ORD-005", "USR-11");
        order5.confirm();
        order5.startProcessing();
        System.out.println("  [Entering SHIPPED — ShippedStateHandler.onEnter should fire]");
        order5.ship("TRACK-HANDLER-001"); // ShippedStateHandler.onEnter fires
        System.out.println("  [Entering DELIVERED — DeliveredStateHandler.onEnter should fire]");
        order5.deliver();                 // ShippedStateHandler.onExit fires, DeliveredStateHandler.onEnter fires


        // ── Scenario 6: Rich enum — transition rules enforced inline ──────────
        System.out.println("\n─── Scenario 6: Rich Enum Inline Validation ─────────────────────");
        System.out.println("  Using OrderStatus.canTransitionTo() directly without state machine:");

        OrderStatus current = OrderStatus.PROCESSING;
        OrderStatus[] targets = {
            OrderStatus.SHIPPED, OrderStatus.CANCELLED,
            OrderStatus.DELIVERED, OrderStatus.PENDING
        };

        for (OrderStatus target : targets) {
            System.out.printf("  %s → %-12s : %s%n",
                    current,
                    target,
                    current.canTransitionTo(target) ? "✓ valid" : "✗ invalid");
        }


        // ── Summary: what the code looks like WITHOUT this pattern ─────────────
        System.out.println("\n─── Without State Machine (the naive approach) ───────────────────");
        System.out.println("""
  PROBLEM — rules scattered across service classes:
    // In OrderService.java
    public void shipOrder(Order order) {
        if (order.getStatus() == PROCESSING) {
            order.setStatus(SHIPPED); // no enforcement — anyone can bypass
        }
    }

    // In AdminService.java
    public void forceShip(Order order) {
        order.setStatus(SHIPPED); // forgot the check — now PENDING → SHIPPED is possible
    }

  RESULT: Rules only exist in whoever remembered to check.
          One missing check = corrupted state = production bug.

  WITH STATE MACHINE:
    order.ship("TRACK-123");  // always goes through applyTransition()
    // InvalidTransitionException if the state doesn't allow it
    // Impossible to bypass — private constructor, private applyTransition()
    // Audit trail is automatic — no one needs to remember to log it""");


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Rich enums carry transition rules — the state knows its own valid successors");
        System.out.println("  2. EnumSet for enum sets, EnumMap for enum keys — faster and lower memory");
        System.out.println("  3. Transition table is the single source of truth for all legal (state, event) pairs");
        System.out.println("  4. InvalidTransitionException is a domain exception — let it propagate");
        System.out.println("  5. applyTransition() is the chokepoint — validation + audit + handlers all here");
        System.out.println("  6. Audit trail is free — a natural byproduct of routing through one method");
        System.out.println("  7. Combined approach: enum owns the graph, handlers own the side effects");
        System.out.println("  8. vs State pattern: enum table for routing; State pattern for per-state behavior");
    }
}

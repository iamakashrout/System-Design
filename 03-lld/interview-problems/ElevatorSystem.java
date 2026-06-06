import java.util.*;

/**
 * Elevator System — Phase 5, Problem 2
 *
 * Demonstrates: State (per-elevator state machine), Strategy (dispatch),
 *               Singleton (controller), Observer (status events),
 *               Thread-safe design (synchronized controller + per-elevator lock)
 *
 * Simulation model: discrete ticks via controller.step().
 * One tick = one floor moved, OR one door-open cycle.
 * This keeps behavior deterministic and fully testable without Thread.sleep.
 */
public class ElevatorSystem {

    // =========================================================================
    // ENUMS
    // =========================================================================

    enum Direction { UP, DOWN }

    enum ElevatorEvent {
        DEPARTED,       // elevator started moving
        ARRIVED,        // elevator reached a floor
        DOOR_OPENED,    // doors opened at a floor
        DOOR_CLOSED,    // doors closed
        BECAME_IDLE     // elevator has no more work
    }

    // =========================================================================
    // REQUESTS
    // =========================================================================

    /**
     * ExternalRequest: from a hall button — "I'm on floor F, going UP/DOWN".
     * This needs to be dispatched to an elevator.
     */
    static class ExternalRequest {
        final int floor;
        final Direction direction;

        ExternalRequest(int floor, Direction direction) {
            this.floor = floor;
            this.direction = direction;
        }

        @Override
        public String toString() {
            return "ExternalReq{floor=" + floor + ", dir=" + direction + "}";
        }
    }

    /**
     * InternalRequest: from an inside panel — "take elevator E to floor F".
     * Already assigned to a specific elevator; bypasses dispatch.
     */
    static class InternalRequest {
        final int elevatorId;
        final int targetFloor;

        InternalRequest(int elevatorId, int targetFloor) {
            this.elevatorId = elevatorId;
            this.targetFloor = targetFloor;
        }

        @Override
        public String toString() {
            return "InternalReq{elevator=" + elevatorId + ", target=" + targetFloor + "}";
        }
    }

    // =========================================================================
    // OBSERVER
    // =========================================================================

    /**
     * ElevatorObserver: any component that wants to know about elevator events.
     * Display boards, monitoring dashboards, and logging systems implement this.
     *
     * Decouples Elevator from the things that react to it.
     */
    interface ElevatorObserver {
        void onElevatorEvent(int elevatorId, ElevatorEvent event, int floor);
    }

    /** Simple console display board — a concrete observer. */
    static class DisplayBoard implements ElevatorObserver {
        @Override
        public void onElevatorEvent(int elevatorId, ElevatorEvent event, int floor) {
            System.out.printf("  [Display] Elevator %d — %s at floor %d%n",
                elevatorId, event, floor);
        }
    }

    // =========================================================================
    // STATE PATTERN: ElevatorState interface + 4 concrete states
    // =========================================================================

    /**
     * ElevatorState defines what an elevator does in a given state.
     *
     * Why interface + separate classes instead of enum + switch?
     * Each state has real behavior — it needs to read the elevator's stop queue,
     * decide on the next action, and trigger a transition. An enum can hold
     * constants; a class hierarchy holds polymorphic behavior.
     *
     * Two events drive transitions:
     * - onFloorReached: the elevator has arrived at a floor
     * - onDoorClosed: the door-open cycle is complete
     */
    interface ElevatorState {
        void onFloorReached(Elevator elevator);
        void onDoorClosed(Elevator elevator);
        String name();
    }

    /**
     * IDLE: elevator has no work. Waiting for a stop to be assigned.
     *
     * When a stop is added (by dispatch or internal button), the elevator
     * checks whether to go up or down and transitions accordingly.
     * This transition is triggered by Elevator.addStop(), not by a state event.
     */
    static class IdleState implements ElevatorState {
        @Override
        public void onFloorReached(Elevator elevator) {
            // IDLE means we're not moving — this event shouldn't occur.
            // Defensive: do nothing.
        }

        @Override
        public void onDoorClosed(Elevator elevator) {
            // If somehow doors closed while idle, check for new work.
            elevator.resumeOrIdle();
        }

        @Override public String name() { return "IDLE"; }
    }

    /**
     * MOVING_UP: elevator is traveling upward to its next stop.
     *
     * step() increments currentFloor by 1.
     * When currentFloor == the next stop above, onFloorReached() fires.
     */
    static class MovingUpState implements ElevatorState {
        @Override
        public void onFloorReached(Elevator elevator) {
            // We've arrived at a target floor — open doors.
            elevator.removeStop(elevator.getCurrentFloor());
            elevator.setState(new DoorOpenState());
            elevator.notifyObservers(ElevatorEvent.ARRIVED, elevator.getCurrentFloor());
            elevator.notifyObservers(ElevatorEvent.DOOR_OPENED, elevator.getCurrentFloor());
        }

        @Override
        public void onDoorClosed(Elevator elevator) {
            // This state doesn't handle door close — DoorOpenState does.
        }

        @Override public String name() { return "MOVING_UP"; }
    }

    /**
     * MOVING_DOWN: mirror of MovingUpState, traveling downward.
     */
    static class MovingDownState implements ElevatorState {
        @Override
        public void onFloorReached(Elevator elevator) {
            elevator.removeStop(elevator.getCurrentFloor());
            elevator.setState(new DoorOpenState());
            elevator.notifyObservers(ElevatorEvent.ARRIVED, elevator.getCurrentFloor());
            elevator.notifyObservers(ElevatorEvent.DOOR_OPENED, elevator.getCurrentFloor());
        }

        @Override
        public void onDoorClosed(Elevator elevator) {
            // Handled by DoorOpenState.
        }

        @Override public String name() { return "MOVING_DOWN"; }
    }

    /**
     * DOOR_OPEN: doors are open at a floor. Passengers board/alight.
     *
     * This is a single tick in our simulation.
     * On the next tick, onDoorClosed() fires and we decide what to do next:
     *
     * Key decision logic in resumeOrIdle():
     * - If there are still stops ABOVE current floor → continue up
     * - If there are still stops BELOW current floor → go down
     * - If no stops remain → become IDLE
     *
     * Why this order? SCAN algorithm: finish the current direction before
     * reversing. Prevents starvation of high-floor requests when low-floor
     * requests keep arriving.
     */
    static class DoorOpenState implements ElevatorState {
        @Override
        public void onFloorReached(Elevator elevator) {
            // Already at a floor, doors are open — do nothing.
        }

        @Override
        public void onDoorClosed(Elevator elevator) {
            elevator.notifyObservers(ElevatorEvent.DOOR_CLOSED, elevator.getCurrentFloor());
            // Decide: continue, reverse, or idle.
            elevator.resumeOrIdle();
        }

        @Override public String name() { return "DOOR_OPEN"; }
    }

    // =========================================================================
    // ELEVATOR
    // =========================================================================

    /**
     * Elevator: the core entity. Manages its own state machine and stop queue.
     *
     * The stop queue is a TreeSet<Integer>:
     * - Sorted order lets us use ceiling() and floor() for SCAN navigation
     * - No duplicate stops (pressing floor 7 twice adds it once)
     *
     * Concurrency: stop queue modifications are synchronized on `this`.
     * step() is also synchronized — it reads + modifies queue and state atomically.
     * Observer notifications happen outside the synchronized block to avoid
     * deadlock (an observer calling back into the elevator).
     */
    static class Elevator {
        private final int id;
        private final int minFloor;
        private final int maxFloor;
        private int currentFloor;
        private ElevatorState currentState;
        private final TreeSet<Integer> stops;
        private final List<ElevatorObserver> observers;

        Elevator(int id, int startFloor, int minFloor, int maxFloor) {
            this.id = id;
            this.currentFloor = startFloor;
            this.minFloor = minFloor;
            this.maxFloor = maxFloor;
            this.currentState = new IdleState();
            this.stops = new TreeSet<>();
            this.observers = new ArrayList<>();
        }

        int getId()           { return id; }
        int getCurrentFloor() { return currentFloor; }

        synchronized ElevatorState getState() { return currentState; }

        synchronized void setState(ElevatorState newState) {
            this.currentState = newState;
        }

        void addObserver(ElevatorObserver observer) {
            observers.add(observer);
        }

        /**
         * Add a stop to this elevator's queue.
         * If elevator is IDLE, this triggers an immediate direction decision.
         *
         * synchronized: dispatch thread and step() both touch the stop queue.
         */
        synchronized void addStop(int floor) {
            if (floor < minFloor || floor > maxFloor) {
                throw new IllegalArgumentException("Floor " + floor + " out of range");
            }
            if (floor == currentFloor && currentState instanceof DoorOpenState) {
                return; // already here with doors open — passenger can board now
            }
            stops.add(floor);

            // If idle, start moving immediately toward the new stop.
            if (currentState instanceof IdleState) {
                startMovingToward(floor);
            }
        }

        synchronized void removeStop(int floor) {
            stops.remove(floor);
        }

        /**
         * Determine the next direction and transition out of IDLE.
         */
        private void startMovingToward(int targetFloor) {
            if (targetFloor > currentFloor) {
                currentState = new MovingUpState();
                notifyObserversUnsafe(ElevatorEvent.DEPARTED, currentFloor);
            } else if (targetFloor < currentFloor) {
                currentState = new MovingDownState();
                notifyObserversUnsafe(ElevatorEvent.DEPARTED, currentFloor);
            } else {
                // Target is current floor: open doors immediately.
                stops.remove(targetFloor);
                currentState = new DoorOpenState();
                notifyObserversUnsafe(ElevatorEvent.DOOR_OPENED, currentFloor);
            }
        }

        /**
         * Called when doors close: should we continue, reverse, or idle?
         *
         * SCAN logic: prefer the current direction of travel.
         * - If coming from UP (stops above exist) → continue up
         * - If coming from DOWN (stops below exist) → continue down
         * - Then check the other direction
         * - Then idle
         *
         * We infer direction from which stops remain.
         */
        synchronized void resumeOrIdle() {
            Integer nextAbove = stops.ceiling(currentFloor + 1);
            Integer nextBelow = stops.floor(currentFloor - 1);

            if (nextAbove != null) {
                currentState = new MovingUpState();
                notifyObserversUnsafe(ElevatorEvent.DEPARTED, currentFloor);
            } else if (nextBelow != null) {
                currentState = new MovingDownState();
                notifyObserversUnsafe(ElevatorEvent.DEPARTED, currentFloor);
            } else {
                currentState = new IdleState();
                notifyObserversUnsafe(ElevatorEvent.BECAME_IDLE, currentFloor);
            }
        }

        /**
         * Advance one simulation tick.
         *
         * If MOVING_UP:   increment floor, check if it's a target
         * If MOVING_DOWN: decrement floor, check if it's a target
         * If DOOR_OPEN:   fire onDoorClosed (one tick for doors)
         * If IDLE:        nothing to do
         *
         * synchronized: step() modifies floor and state; addStop() modifies stops.
         */
        synchronized void step() {
            if (currentState instanceof MovingUpState) {
                currentFloor++;
                Integer nextStop = stops.ceiling(currentFloor);
                if (nextStop != null && nextStop == currentFloor) {
                    currentState.onFloorReached(this);
                }
            } else if (currentState instanceof MovingDownState) {
                currentFloor--;
                Integer nextStop = stops.floor(currentFloor);
                if (nextStop != null && nextStop == currentFloor) {
                    currentState.onFloorReached(this);
                }
            } else if (currentState instanceof DoorOpenState) {
                currentState.onDoorClosed(this);
            }
            // IdleState: nothing happens
        }

        /** Notify observers while already holding the lock (for state transitions). */
        private void notifyObserversUnsafe(ElevatorEvent event, int floor) {
            // Copy the list to avoid ConcurrentModificationException
            List<ElevatorObserver> copy = new ArrayList<>(observers);
            for (ElevatorObserver obs : copy) {
                obs.onElevatorEvent(id, event, floor);
            }
        }

        /** Notify observers outside a synchronized block (safe for external callers). */
        void notifyObservers(ElevatorEvent event, int floor) {
            List<ElevatorObserver> copy;
            synchronized (this) { copy = new ArrayList<>(observers); }
            for (ElevatorObserver obs : copy) {
                obs.onElevatorEvent(id, event, floor);
            }
        }

        synchronized boolean isIdle() {
            return currentState instanceof IdleState;
        }

        synchronized int distanceTo(int floor) {
            return Math.abs(currentFloor - floor);
        }

        synchronized boolean isMovingToward(int floor, Direction direction) {
            if (direction == Direction.UP && currentState instanceof MovingUpState) {
                return floor >= currentFloor;
            }
            if (direction == Direction.DOWN && currentState instanceof MovingDownState) {
                return floor <= currentFloor;
            }
            return false;
        }

        synchronized String getStatus() {
            return String.format("Elevator %d | Floor: %2d | State: %-14s | Stops: %s",
                id, currentFloor, currentState.name(), stops);
        }
    }

    // =========================================================================
    // STRATEGY: DISPATCH
    // =========================================================================

    /**
     * DispatchStrategy: given an external request and the fleet of elevators,
     * pick the best one to serve the request.
     *
     * Returns Optional.empty() if no suitable elevator exists (shouldn't happen
     * in a well-sized building, but we design defensively).
     */
    interface DispatchStrategy {
        Optional<Elevator> selectElevator(ExternalRequest request, List<Elevator> elevators);
    }

    /**
     * NearestElevatorStrategy: scoring-based dispatch.
     *
     * Score per elevator (lower = better):
     * - Already moving toward the request floor in the right direction: score = distance
     * - Idle: score = distance + small penalty (idle is still good)
     * - Moving away or in wrong direction: score = distance + large penalty
     *
     * Why penalty-based instead of strict tiers?
     * Because in a real building you might prefer a slightly-farther elevator
     * that's already heading the right way over a nearby one going the wrong way.
     * Penalties encode that preference as continuous values, so the scoring is
     * consistent and extensible (e.g., add a load penalty later).
     */
    static class NearestElevatorStrategy implements DispatchStrategy {
        private static final int IDLE_PENALTY    = 2;
        private static final int WRONG_DIR_PENALTY = 20;

        @Override
        public Optional<Elevator> selectElevator(ExternalRequest request,
                                                   List<Elevator> elevators) {
            Elevator best = null;
            int bestScore = Integer.MAX_VALUE;

            for (Elevator elevator : elevators) {
                int score = computeScore(elevator, request);
                if (score < bestScore) {
                    bestScore = score;
                    best = elevator;
                }
            }
            return Optional.ofNullable(best);
        }

        private int computeScore(Elevator elevator, ExternalRequest request) {
            int distance = elevator.distanceTo(request.floor);

            if (elevator.isMovingToward(request.floor, request.direction)) {
                // Best case: already heading there in the right direction
                return distance;
            } else if (elevator.isIdle()) {
                // Good: idle and can be redirected
                return distance + IDLE_PENALTY;
            } else {
                // Moving away or wrong direction — penalize heavily
                return distance + WRONG_DIR_PENALTY;
            }
        }
    }

    // =========================================================================
    // ELEVATOR CONTROLLER — Singleton
    // =========================================================================

    /**
     * ElevatorController: the single entry point for the entire system.
     *
     * Responsibilities:
     * - Maintain the fleet of elevators
     * - Receive and dispatch external requests
     * - Route internal requests to the correct elevator
     * - Drive the simulation via step()
     *
     * Why Singleton? Like ParkingLotManager, there is exactly one controller
     * per building. All hall buttons and inside panels share the same elevator
     * fleet state. Two controller instances would see different floor states.
     */
    static class ElevatorController {
        private static volatile ElevatorController instance;

        private final List<Elevator> elevators;
        private final DispatchStrategy dispatchStrategy;
        private int tickCount;

        private ElevatorController(List<Elevator> elevators, DispatchStrategy strategy) {
            this.elevators = Collections.unmodifiableList(elevators);
            this.dispatchStrategy = strategy;
            this.tickCount = 0;
        }

        static ElevatorController getInstance() {
            if (instance == null) {
                synchronized (ElevatorController.class) {
                    if (instance == null) {
                        // Build a 3-elevator, 20-floor building
                        List<Elevator> fleet = new ArrayList<>();
                        for (int i = 1; i <= 3; i++) {
                            fleet.add(new Elevator(i, 1, 1, 20));
                        }
                        instance = new ElevatorController(fleet, new NearestElevatorStrategy());
                    }
                }
            }
            return instance;
        }

        /** Register an observer with all elevators. */
        void addObserver(ElevatorObserver observer) {
            for (Elevator e : elevators) e.addObserver(observer);
        }

        /**
         * Handle a hall button press: dispatch to the best elevator.
         *
         * synchronized: dispatch reads all elevator states to score them;
         * this must be atomic with the resulting addStop() call to prevent
         * two simultaneous dispatches both assigning to the same elevator.
         */
        synchronized void requestElevator(ExternalRequest request) {
            System.out.println("[Controller] External request: " + request);
            Optional<Elevator> chosen = dispatchStrategy.selectElevator(request, elevators);
            chosen.ifPresentOrElse(
                e -> {
                    System.out.printf("[Controller] Dispatching to Elevator %d (currently at floor %d)%n",
                        e.getId(), e.getCurrentFloor());
                    e.addStop(request.floor);
                },
                () -> System.out.println("[Controller] No elevator available for: " + request)
            );
        }

        /**
         * Handle an inside button press: goes directly to the specified elevator.
         */
        synchronized void addInternalRequest(InternalRequest request) {
            elevators.stream()
                .filter(e -> e.getId() == request.elevatorId)
                .findFirst()
                .ifPresentOrElse(
                    e -> {
                        System.out.println("[Controller] Internal request: " + request);
                        e.addStop(request.targetFloor);
                    },
                    () -> System.out.println("[Controller] Unknown elevator ID: " + request.elevatorId)
                );
        }

        /**
         * Advance all elevators by one tick.
         * In a real system this would be driven by hardware timers.
         */
        synchronized void step() {
            tickCount++;
            for (Elevator e : elevators) e.step();
        }

        /** Run N ticks, printing status after each. */
        void runTicks(int n) {
            for (int i = 0; i < n; i++) {
                step();
                printStatus();
            }
        }

        void printStatus() {
            System.out.println("  -- Tick " + tickCount + " --");
            for (Elevator e : elevators) {
                System.out.println("  " + e.getStatus());
            }
        }

        boolean allIdle() {
            return elevators.stream().allMatch(Elevator::isIdle);
        }

        List<Elevator> getElevators() { return elevators; }
    }

    // =========================================================================
    // DEMO DRIVER
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Elevator System Demo ===\n");

        // Reset singleton for demo (in production, this wouldn't exist)
        ElevatorController controller = ElevatorController.getInstance();
        DisplayBoard board = new DisplayBoard();
        controller.addObserver(board);

        // -----------------------------------------------------------------------
        // Scenario 1: Simple single elevator dispatch
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 1: Single request, elevator 1 goes to floor 5 ===\n");
        controller.requestElevator(new ExternalRequest(5, Direction.UP));
        System.out.println();
        controller.runTicks(6); // 4 ticks to reach floor 5, 1 for doors, 1 back to idle
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 2: Internal button — passenger inside elevator 1, goes to floor 12
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 2: Passenger inside elevator 1 presses floor 12 ===\n");
        controller.addInternalRequest(new InternalRequest(1, 12));
        System.out.println();
        controller.runTicks(9); // 7 floors up, 1 door, 1 idle
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 3: Multiple simultaneous external requests — dispatch in action
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 3: Three simultaneous requests ===\n");
        // Elevator 1 is at floor 12 (idle), Elevator 2 at floor 1 (idle), Elevator 3 at 1 (idle)
        controller.requestElevator(new ExternalRequest(8,  Direction.UP));   // → elevator 1 (closest from 12)
        controller.requestElevator(new ExternalRequest(3,  Direction.DOWN)); // → elevator 2 or 3 (both at 1)
        controller.requestElevator(new ExternalRequest(15, Direction.UP));   // → elevator 1 or 3
        System.out.println();

        System.out.println("Status after dispatch:");
        controller.printStatus();
        System.out.println();

        // Run until all elevators are idle
        int maxTicks = 30;
        int t = 0;
        while (!controller.allIdle() && t < maxTicks) {
            controller.step();
            t++;
        }
        System.out.println("All elevators idle after " + t + " ticks.");
        controller.printStatus();
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 4: Elevator picks up on the way (SCAN behavior)
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 4: SCAN — elevator 1 picks up on the way up ===\n");
        // Elevator 1 is idle. Give it a destination at floor 18.
        // Then someone on floor 10 also wants to go up — elevator 1 should stop there en route.
        controller.addInternalRequest(new InternalRequest(1, 18));
        controller.requestElevator(new ExternalRequest(10, Direction.UP)); // should go to elevator 1

        System.out.println("Status after requests:");
        controller.printStatus();
        System.out.println();

        t = 0;
        while (!controller.allIdle() && t < maxTicks) {
            controller.step();
            t++;
        }
        System.out.println("SCAN scenario complete after " + t + " ticks.");
        controller.printStatus();

        System.out.println("\n=== Demo complete ===");
    }
}

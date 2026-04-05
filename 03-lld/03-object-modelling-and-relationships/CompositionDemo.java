import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * RELATIONSHIP 4: COMPOSITION — "You exist because I exist"
 *
 * Definition:
 *   Class A creates Class B internally and owns it completely.
 *   B has no meaningful existence outside A.
 *   When A is destroyed, B is logically destroyed too.
 *   B is NOT passed in from outside — it is BORN inside A.
 *
 * Real-world analogy:
 *   A human body HAS a heart. The heart is created as part of the body,
 *   exists solely within one body, and cannot be shared between two people
 *   simultaneously. A heart without a body makes no sense.
 *
 * Code signals:
 *   - Child is created with new X() INSIDE the parent's constructor or methods.
 *   - Child's constructor is often package-private — only parent can create it.
 *   - Child is never passed around independently.
 *   - Child is never shared between two parents.
 *
 * Aggregation vs Composition — the key test:
 *   "If I delete the parent, does the child logically stop existing?"
 *   YES → Composition (OrderLineItem without Order = meaningless)
 *   NO  → Aggregation (Professor without Department = still a professor)
 *
 * UML notation: A ◆——→ B  (FILLED diamond at A's end)
 */

// ─── Composition Example 1: Order ◆——→ OrderLineItem ────────────────────────

/**
 * OrderLineItem has NO meaningful existence outside an Order.
 * "3 units of Product X at ₹200 each on [no order]" means nothing.
 *
 * Notice: package-private constructor — ONLY Order can create this.
 * This enforces the composition contract at the language level.
 */
class OrderLineItem {
    private final String productName;
    private final int quantity;
    private final double pricePerUnit;

    /**
     * Package-private constructor.
     * The comment "created only by Order" is backed by the access modifier.
     * Nobody outside this package can write: new OrderLineItem(...)
     */
    OrderLineItem(String productName, int quantity, double pricePerUnit) {
        this.productName = productName;
        this.quantity = quantity;
        this.pricePerUnit = pricePerUnit;
    }

    public double getSubtotal() {
        return quantity * pricePerUnit;
    }

    @Override
    public String toString() {
        return productName + " x" + quantity + " @ ₹" + pricePerUnit
                + " = ₹" + getSubtotal();
    }
}

/**
 * Order COMPOSES OrderLineItem objects.
 *
 * Key observations:
 * 1. Line items are created INSIDE addItem() — no external code creates them.
 * 2. The list is initialized in the constructor — Order owns it from birth.
 * 3. The list is unmodifiable when returned — external code can't tamper with it.
 * 4. If this Order object is discarded, its line items go with it.
 */
class Order {
    private final String orderId;
    private final String customerName;

    // COMPOSITION: Order creates and completely owns these line items
    private final List<OrderLineItem> lineItems;

    public Order(String customerName) {
        this.orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        this.customerName = customerName;
        this.lineItems = new ArrayList<>(); // Order creates its own list
    }

    /**
     * COMPOSITION in action:
     * OrderLineItem is created HERE, inside Order.
     * No external caller ever does: new OrderLineItem(...)
     * Order controls what line items it contains.
     */
    public void addItem(String productName, int quantity, double pricePerUnit) {
        // Child is born inside parent — this is the composition signal
        OrderLineItem item = new OrderLineItem(productName, quantity, pricePerUnit);
        lineItems.add(item);
        System.out.println("[Order] Added: " + item);
    }

    public double getTotal() {
        return lineItems.stream().mapToDouble(OrderLineItem::getSubtotal).sum();
    }

    // Return unmodifiable — callers can READ the line items but cannot add to them directly
    // This prevents bypassing addItem() and losing control of the composition
    public List<OrderLineItem> getLineItems() {
        return Collections.unmodifiableList(lineItems);
    }

    public void printReceipt() {
        System.out.println("\n[Receipt] Order: " + orderId + " | Customer: " + customerName);
        System.out.println("          ─────────────────────────────────");
        for (OrderLineItem item : lineItems) {
            System.out.println("          " + item);
        }
        System.out.println("          ─────────────────────────────────");
        System.out.println("          TOTAL: ₹" + getTotal());
    }

    public String getOrderId() { return orderId; }
}

// ─── Composition Example 2: House ◆——→ Room ──────────────────────────────────

/**
 * Room has no meaningful existence without a House.
 * "Room 3 in [no house]" is not a useful concept.
 *
 * Package-private constructor enforces: only House can create Rooms.
 */
class Room {
    private final int roomNumber;
    private final String roomType; // "Bedroom", "Kitchen", etc.

    /**
     * Package-private — only House instantiates this.
     */
    Room(int roomNumber, String roomType) {
        this.roomNumber = roomNumber;
        this.roomType = roomType;
    }

    public int getRoomNumber() { return roomNumber; }
    public String getRoomType() { return roomType; }

    @Override
    public String toString() {
        return "Room #" + roomNumber + " (" + roomType + ")";
    }
}

/**
 * House COMPOSES Room objects.
 * Rooms are created in the constructor — they are born with the house.
 * Destroying the house logically destroys its rooms.
 */
class House {
    private final String address;

    // COMPOSITION: Room list is created and populated internally
    private final List<Room> rooms;

    /**
     * Rooms are created INSIDE the constructor.
     * No external code creates Room objects.
     * The number and type of rooms is decided at construction time.
     */
    public House(String address, int bedrooms) {
        this.address = address;
        this.rooms = new ArrayList<>();

        // Rooms are born as part of the house — composition
        rooms.add(new Room(1, "Living Room"));
        rooms.add(new Room(2, "Kitchen"));
        for (int i = 1; i <= bedrooms; i++) {
            rooms.add(new Room(2 + i, "Bedroom " + i));
        }

        System.out.println("[House] Built house at: " + address
                + " with " + rooms.size() + " rooms");
    }

    public void listRooms() {
        System.out.println("[House] Rooms in " + address + ":");
        for (Room r : rooms) {
            System.out.println("   → " + r);
        }
    }

    public int getRoomCount() { return rooms.size(); }
    public String getAddress() { return address; }
}

// ─── Composition Example 3: Human Body ◆——→ Heart ────────────────────────────

/**
 * Heart cannot exist meaningfully outside a body.
 * Created inside Body — owned entirely.
 */
class Heart {
    private boolean beating;
    private int bpm;

    /**
     * Package-private — only Body creates this.
     */
    Heart(int initialBpm) {
        this.beating = true;
        this.bpm = initialBpm;
        System.out.println("[Heart] Heart initialized at " + bpm + " BPM");
    }

    public void setBpm(int bpm) {
        this.bpm = bpm;
    }

    public void beat() {
        if (beating) {
            System.out.println("[Heart] ♥ Beating at " + bpm + " BPM");
        }
    }

    public void stop() {
        this.beating = false;
        System.out.println("[Heart] Heart has stopped");
    }
}

/**
 * Body COMPOSES Heart — heart is created inside Body, owned entirely.
 */
class Body {
    private final String ownerName;

    // COMPOSITION: Heart is created inside Body — not passed in
    private final Heart heart;

    public Body(String ownerName) {
        this.ownerName = ownerName;
        // Heart is born with the body — cannot exist without it
        this.heart = new Heart(72);
    }

    public void exercise() {
        System.out.println("[Body] " + ownerName + " is exercising...");
        heart.setBpm(120); // body tells the heart to beat faster
        heart.beat();
    }

    public void rest() {
        System.out.println("[Body] " + ownerName + " is resting...");
        heart.setBpm(60);
        heart.beat();
    }

    public String getOwnerName() { return ownerName; }
}

// ─── Runner ───────────────────────────────────────────────────────────────────

public class CompositionDemo {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  COMPOSITION RELATIONSHIP DEMO");
        System.out.println("========================================\n");

        // --- Example 1: Order ◆——→ OrderLineItem ---
        System.out.println("--- Example 1: Order ◆——→ OrderLineItem ---\n");

        Order order = new Order("Akash");
        // addItem creates OrderLineItems INTERNALLY — nobody passes them in
        order.addItem("Laptop",       1, 75000.00);
        order.addItem("Mouse",        2,  1500.00);
        order.addItem("USB-C Cable",  3,   400.00);

        order.printReceipt();

        System.out.println();
        System.out.println("[Demo] Line items are exposed READ-ONLY:");
        System.out.println("       order.getLineItems() returns unmodifiableList");
        System.out.println("       Callers cannot do: getLineItems().add(...) directly");

        System.out.println("\n--- Example 2: House ◆——→ Room ---\n");

        // Rooms are created INSIDE House constructor — no separate Room creation needed
        House house1 = new House("12, MG Road, Bangalore", 3);
        house1.listRooms();

        System.out.println();
        House house2 = new House("7, Jubilee Hills, Hyderabad", 2);
        house2.listRooms();

        // Each house has its OWN rooms — rooms are not shared between houses
        System.out.println("\n[Demo] house1 has " + house1.getRoomCount() + " rooms");
        System.out.println("       house2 has " + house2.getRoomCount() + " rooms");
        System.out.println("       These rooms CANNOT belong to both houses simultaneously");

        System.out.println("\n--- Example 3: Body ◆——→ Heart ---\n");

        Body body = new Body("Akash");
        body.exercise();
        System.out.println();
        body.rest();

        System.out.println("\n--- Key Takeaway ---");
        System.out.println("Composition = child is CREATED INSIDE parent.");
        System.out.println("Child has no meaningful existence without the parent.");
        System.out.println("Package-private constructors enforce this at the language level.");
        System.out.println("Child cannot be shared between two parents simultaneously.");
        System.out.println("If parent is gone, children are logically gone too.");
    }
}

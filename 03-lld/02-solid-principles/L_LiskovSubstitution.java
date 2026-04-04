// ============================================================
//  L — Liskov Substitution Principle (LSP)
//
//  Principle : Subtypes must be substitutable for their base
//              types without changing program correctness.
//
//  Two examples:
//  1. Classic Rectangle / Square problem
//  2. Bird / Penguin problem
//
//  Key idea  : Mathematical IS-A ≠ Behavioural IS-A
//              Square is a Rectangle in geometry.
//              Square is NOT a Rectangle in behaviour.
// ============================================================

import java.util.List;

public class L_LiskovSubstitution {

    // ══════════════════════════════════════════════════════════════
    //  EXAMPLE 1 — Rectangle / Square
    // ══════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────
    //  VIOLATION
    // ─────────────────────────────────────────────

    /**
     * The implicit contract of setWidth():
     *   → sets the width
     *   → leaves the height UNCHANGED
     * Every caller assumes this. It's the behavioural promise.
     */
    static class RectangleViolation {
        protected int width;
        protected int height;

        void setWidth(int width)   { this.width  = width; }
        void setHeight(int height) { this.height = height; }
        int  area()                { return width * height; }
    }

    /**
     * VIOLATION: Square forces both sides to stay equal.
     * setWidth(5) silently changes height to 5 as well.
     * The caller's assumption — "only width changed" — is broken.
     */
    static class SquareViolation extends RectangleViolation {
        @Override
        void setWidth(int width) {
            this.width  = width;
            this.height = width;  // ← side-effect the caller never expected
        }

        @Override
        void setHeight(int height) {
            this.height = height;
            this.width  = height; // ← same surprise
        }
    }

    // ─────────────────────────────────────────────
    //  FIX — find the real shared abstraction
    // ─────────────────────────────────────────────

    /**
     * The only behaviour both Rectangle and Square truly share:
     * they can compute their area. That is the honest contract.
     */
    interface Shape {
        int    area();
        String shapeName();
    }

    /**
     * Immutable — no setters, so no contract can ever be broken.
     * Width and height are independent by construction.
     */
    static class Rectangle implements Shape {
        private final int width;
        private final int height;

        Rectangle(int width, int height) {
            this.width  = width;
            this.height = height;
        }

        @Override public int    area()      { return width * height; }
        @Override public String shapeName() { return "Rectangle(" + width + "×" + height + ")"; }
    }

    /**
     * Square controls its own invariant (all sides equal)
     * without touching Rectangle's contract.
     */
    static class Square implements Shape {
        private final int side;

        Square(int side) { this.side = side; }

        @Override public int    area()      { return side * side; }
        @Override public String shapeName() { return "Square(" + side + "×" + side + ")"; }
    }

    /** Works correctly for EVERY Shape implementation. No instanceof, no surprises. */
    static void printArea(Shape shape) {
        System.out.printf("  %-22s → Area = %d%n", shape.shapeName(), shape.area());
    }

    // ══════════════════════════════════════════════════════════════
    //  EXAMPLE 2 — Bird / Penguin
    // ══════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────
    //  VIOLATION
    // ─────────────────────────────────────────────

    /** Base class with fly() — every subclass inherits this promise. */
    static class BirdViolation {
        void fly() { System.out.println("  [BirdViolation] Flying..."); }
    }

    /**
     * VIOLATION: Penguin IS-A bird biologically.
     * But it cannot fly — forcing it here breaks the fly() contract.
     * Only option: throw an exception → LSP violated.
     */
    static class PenguinViolation extends BirdViolation {
        @Override
        void fly() {
            throw new UnsupportedOperationException("Penguins can't fly!");
        }
    }

    // ─────────────────────────────────────────────
    //  FIX — honest interface hierarchy
    // ─────────────────────────────────────────────

    /**
     * Safe base contract — only behaviours ALL birds share.
     * No fly() here — that would be a lie for penguins.
     */
    interface Bird {
        void   eat();
        void   makeSound();
        String birdName();
    }

    /**
     * Extended contract — only for birds that CAN truly fly.
     * If you implement this, you PROMISE you can fly.
     */
    interface FlyingBird extends Bird {
        void fly();
    }

    static class Eagle implements FlyingBird {
        @Override public void   eat()       { System.out.println("  [" + birdName() + "] Eating a fish."); }
        @Override public void   makeSound() { System.out.println("  [" + birdName() + "] Screeches!"); }
        @Override public void   fly()       { System.out.println("  [" + birdName() + "] Soars high in the sky."); }
        @Override public String birdName()  { return "Eagle"; }
    }

    static class Sparrow implements FlyingBird {
        @Override public void   eat()       { System.out.println("  [" + birdName() + "] Eating seeds."); }
        @Override public void   makeSound() { System.out.println("  [" + birdName() + "] Chirps!"); }
        @Override public void   fly()       { System.out.println("  [" + birdName() + "] Flutters between branches."); }
        @Override public String birdName()  { return "Sparrow"; }
    }

    /**
     * Penguin honestly implements Bird — no fly(), no lies, no exceptions.
     * It adds its own unique behaviour (swim) without breaking any contract.
     */
    static class Penguin implements Bird {
        @Override public void   eat()       { System.out.println("  [" + birdName() + "] Eating a fish."); }
        @Override public void   makeSound() { System.out.println("  [" + birdName() + "] Honks!"); }
        @Override public String birdName()  { return "Penguin"; }

        // Penguin's own unique behaviour — nothing forces this into Bird
        void swim() { System.out.println("  [" + birdName() + "] Swims gracefully underwater."); }
    }

    /**
     * Takes FlyingBird — only types that can truly fly.
     * Penguin cannot be passed here. Compiler enforces the contract.
     */
    static void trainToFly(FlyingBird bird) {
        System.out.println("  Training " + bird.birdName() + " to fly:");
        bird.fly();
    }

    /** Safe for every Bird — including Penguin. */
    static void feedAll(List<Bird> birds) {
        for (Bird bird : birds) {
            bird.eat();
        }
    }

    // ─────────────────────────────────────────────
    //  DEMO — violations and fixes
    // ─────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  Liskov Substitution Principle       ║");
        System.out.println("╚══════════════════════════════════════╝\n");

        // ── Rectangle/Square — Violation ──
        System.out.println("── VIOLATION: Rectangle / Square ──");
        SquareViolation r = new SquareViolation();
        r.setWidth(5);   // Square silently sets height = 5
        r.setHeight(4);  // Square silently sets width = 4
        // Caller expected 5 × 4 = 20. Gets 4 × 4 = 16.
        System.out.println("  setWidth(5), setHeight(4) on a Square → area = " + r.area());
        System.out.println("  Expected 20, got " + r.area() + " — contract broken!\n");

        // ── Rectangle/Square — Fix ──
        System.out.println("── FIX: Rectangle and Square both implement Shape ──");
        List<Shape> shapes = List.of(
            new Rectangle(5, 4),
            new Rectangle(10, 3),
            new Square(6),
            new Square(4)
        );
        for (Shape shape : shapes) {
            printArea(shape);
        }
        System.out.println();

        // ── Bird/Penguin — Violation ──
        System.out.println("── VIOLATION: Penguin forced to extend BirdViolation ──");
        PenguinViolation badBird = new PenguinViolation();
        try {
            badBird.fly();
        } catch (UnsupportedOperationException e) {
            System.out.println("  CRASH → " + e.getMessage() + "  (runtime explosion — LSP violated)\n");
        }

        // ── Bird/Penguin — Fix ──
        System.out.println("── FIX: Honest interface hierarchy ──\n");

        Eagle   eagle   = new Eagle();
        Sparrow sparrow = new Sparrow();
        Penguin penguin = new Penguin();

        // Only FlyingBird instances here — type-safe
        System.out.println("  Flying bird training:");
        List<FlyingBird> flyingBirds = List.of(eagle, sparrow);
        for (FlyingBird fb : flyingBirds) {
            trainToFly(fb);
        }
        System.out.println();

        // All birds can be fed — including penguin
        System.out.println("  Feeding all birds:");
        feedAll(List.of(eagle, sparrow, penguin));
        System.out.println();

        // Penguin's own behaviour
        System.out.println("  Penguin's unique behaviour:");
        penguin.swim();

        System.out.println();
        System.out.println("✔ Every subtype honours its contract — no runtime surprises.");
        System.out.println("✔ Passing Penguin to trainToFly() is a compile-time error — caught by the compiler.");
    }
}

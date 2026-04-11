# UML Class Diagrams — Complete Notes

> **Phase 2 Supplement — Object Modeling and Relationships**
> Topic: Reading and Drawing UML Class Diagrams

---

## Table of Contents

1. [What is a UML Class Diagram?](#what-is-a-uml-class-diagram)
2. [The Class Box — Anatomy](#the-class-box--anatomy)
3. [Visibility Symbols](#visibility-symbols)
4. [Abstract Classes and Interfaces](#abstract-classes-and-interfaces)
5. [The Five Relationship Arrows](#the-five-relationship-arrows)
6. [Multiplicity — How Many?](#multiplicity--how-many)
7. [How to Read a UML Diagram](#how-to-read-a-uml-diagram)
8. [How to Draw a UML Diagram from Scratch](#how-to-draw-a-uml-diagram-from-scratch)
9. [Interview Cheat Sheet](#interview-cheat-sheet)
10. [Quick Reference — All Symbols](#quick-reference--all-symbols)

---

## What is a UML Class Diagram?

UML stands for **Unified Modeling Language**. A class diagram is a static blueprint of your system — it shows:

- What **classes** exist
- What **data** each class holds (fields)
- What **behavior** each class has (methods)
- How classes **relate** to each other

Think of it as an architectural floor plan for your code. A floor plan doesn't show people walking around — it shows structure. Similarly, a class diagram doesn't show runtime behavior — it shows the *shape* of your design.

**Why it matters for you:**
- In LLD interviews, this is the shared language for sketching designs on a whiteboard
- It lets you communicate a design in minutes without writing a single line of code
- It makes relationships (composition, inheritance, etc.) immediately visible

---

## The Class Box — Anatomy

Every class is drawn as a rectangle divided into **three horizontal compartments**:

```
┌─────────────────────────┐
│       ClassName         │  ← Compartment 1: Class Name (bold, centered)
├─────────────────────────┤
│  - fieldName: Type      │  ← Compartment 2: Fields / Attributes
│  - anotherField: Type   │
├─────────────────────────┤
│  + methodName(): Type   │  ← Compartment 3: Methods / Operations
│  + anotherMethod()      │
└─────────────────────────┘
```

**Example — BankAccount class:**

```
┌──────────────────────────────┐
│         BankAccount          │
├──────────────────────────────┤
│  - accountId: String         │
│  - balance: double           │
│  # minimumBalance: double    │
├──────────────────────────────┤
│  + debit(amount: double)     │
│  + credit(amount: double)    │
│  + getBalance(): double      │
└──────────────────────────────┘
```

**Field format:** `visibility fieldName: Type`

**Method format:** `visibility methodName(paramName: Type): ReturnType`

**In interviews:** You don't need to list every field and method. List the ones that communicate your design decisions. A `BankAccount` without `debit()` and `credit()` is missing its core behavior.

---

## Visibility Symbols

These symbols appear at the start of every field and method entry:

| Symbol | Meaning | Java equivalent |
|--------|---------|----------------|
| `+` | public | `public` |
| `-` | private | `private` |
| `#` | protected | `protected` |
| `~` | package-private | (default, no keyword) |

**Rule of thumb for interviews:** Most fields should be `-` (private). Most methods in the public API should be `+` (public). Use `#` when you explicitly want subclasses to access something.

---

## Abstract Classes and Interfaces

These two have special notations that appear constantly in LLD diagrams.

### Abstract Class

```
┌──────────────────────────────┐
│        «abstract»            │  ← stereotype label
│       Notification           │  ← class name in italics
├──────────────────────────────┤
│  - recipient: String         │
│  - message: String           │
├──────────────────────────────┤
│  + send()                    │  ← abstract method: also in italics
│  + getSummary(): String      │  ← concrete method: normal text
└──────────────────────────────┘
```

**Signals:**
- Class name is written in *italics*
- Optionally write `«abstract»` above the name
- Abstract methods are also in *italics*
- Can have both fields and concrete methods

### Interface

```
┌──────────────────────────────┐
│        «interface»           │  ← stereotype label (required)
│       MembershipTier         │
├──────────────────────────────┤
│                              │  ← fields section usually empty
├──────────────────────────────┤
│  + getDiscountRate(): double │
│  + getTierName(): String     │
└──────────────────────────────┘
```

**Signals:**
- Write `«interface»` above the name (the angle-bracket labels are called *stereotypes*)
- Fields compartment is typically empty (interfaces have no state)
- All methods are implicitly abstract — no need for italics
- Many people omit the empty fields row for interfaces

### The Difference, Visually

| | Abstract Class | Interface |
|---|---|---|
| Notation | Italic name + `«abstract»` | `«interface»` stereotype |
| Can have fields? | Yes | No |
| Can have concrete methods? | Yes | No (Java 8+ default methods aside) |
| Relationship arrow | Solid line + hollow triangle | Dashed line + hollow triangle |

---

## The Five Relationship Arrows

This is the most important section. Each of the four relationships from our object modeling study has a distinct arrow. There are five arrows total including inheritance.

---

### Arrow 1: Dependency — Dashed line with open arrow

```
ClassA - - - - -> ClassB
```

**What it means:** ClassA uses ClassB *temporarily* — only inside a method (as a parameter or local variable). No field is stored.

**Memory trick:** Dashed = doesn't last. The relationship is brief, like borrowing a pen.

**Java signal:**
```java
public void export(PdfExporter exporter) {  // PdfExporter is a dependency
    exporter.export(content);
}
```

**Example:** `Invoice - - -> PdfExporter` (Invoice uses exporter in one method, doesn't store it)

---

### Arrow 2: Association — Solid line with open arrow

```
ClassA ————————> ClassB
```

**What it means:** ClassA holds a *field reference* to ClassB. The relationship persists across method calls. Both exist independently — neither creates nor owns the other.

**Memory trick:** Solid = stored. A permanent reference that sticks around.

**Java signal:**
```java
class Doctor {
    private List<Patient> patients;  // association — stored as field
}
```

**Example:** `Doctor ————> Patient` (Doctor stores a list of patients, both exist independently)

**Bidirectional association** (rare, usually split into two one-way):
```
ClassA <————————> ClassB
```

---

### Arrow 3: Aggregation — Solid line with hollow diamond

```
Team ◇————————> Player
```

The **hollow (empty/white) diamond** sits at the *container's end* (Team side, not Player side).

**What it means:** Team *has* Players, but Players are passed in from outside. If Team is destroyed, Players survive. Child can belong to multiple parents.

**Memory trick:** Hollow = empty on the inside = the child can exist on its own without being "filled in" by the parent.

**Java signal:**
```java
class Team {
    public void addPlayer(Player p) {  // player passed IN from outside
        players.add(p);
    }
}
```

**Example:** `Department ◇————> Professor` (Professors are hired into a Department; they survive if the Department is dissolved)

---

### Arrow 4: Composition — Solid line with filled diamond

```
Order ◆————————> OrderLineItem
```

The **filled (black) diamond** sits at the *owner's end* (Order side, not OrderLineItem side).

**What it means:** Order *creates* OrderLineItems internally. LineItems cannot exist without an Order. If Order is destroyed, LineItems go with it. Child belongs to exactly one parent.

**Memory trick:** Filled = the child is filled in, part of the owner. Born inside. Dies inside.

**Java signal:**
```java
class Order {
    public void addItem(String name, int qty, double price) {
        lineItems.add(new OrderLineItem(name, qty, price));  // created INSIDE
    }
}
```

**Example:** `House ◆————> Room` (Rooms are created as part of the House, meaningless without it)

---

### Arrow 5: Inheritance (Generalization) — Solid line with hollow triangle

```
EmailNotification ————————▷ Notification
```

The **hollow (empty) triangle** points at the *parent* class.

**What it means:** EmailNotification IS-A Notification. Full inheritance — gets all fields and methods of the parent. Arrow always points from child up to parent.

**Memory trick:** Triangle = "I am going up to my parent." Arrow points upward.

**Java signal:**
```java
class EmailNotification extends Notification { ... }
```

---

### Arrow 5b: Realization — Dashed line with hollow triangle

```
StandardTier - - - - - -▷ MembershipTier
```

Same hollow triangle, but the line is **dashed**.

**What it means:** StandardTier *implements* the MembershipTier interface. It realizes the contract.

**Memory trick:** Dashed triangle = "I implement this interface." Compare to solid triangle = "I extend this class."

**Java signal:**
```java
class StandardTier implements MembershipTier { ... }
```

---

### Summary Table: All Five Arrows

| Arrow | Line | End symbol | Relationship | Java keyword |
|-------|------|-----------|-------------|-------------|
| Dependency | Dashed | Open arrow (`->`) | Uses temporarily | method param |
| Association | Solid | Open arrow (`->`) | Knows about (field) | field reference |
| Aggregation | Solid | Hollow diamond + arrow | Has-a, child survives | `add(X x)` |
| Composition | Solid | Filled diamond + arrow | Owns, child dies with parent | `new X()` inside |
| Inheritance | Solid | Hollow triangle | IS-A (extends) | `extends` |
| Realization | Dashed | Hollow triangle | Implements interface | `implements` |

---

## Multiplicity — How Many?

Multiplicity numbers written near the ends of association lines tell you *how many* objects of each class participate in the relationship.

**Format:** Written at **both ends** of the line.

```
Library (1) ◆———————— (0..*) Book
```

Read as: *"One Library has zero or more Books. Each Book belongs to exactly one Library."*

### Common Multiplicity Notations

| Notation | Meaning |
|----------|---------|
| `1` | Exactly one |
| `0..1` | Zero or one (optional) |
| `*` or `0..*` | Zero or more |
| `1..*` | One or more (at least one) |
| `2..5` | Between 2 and 5 |
| `3` | Exactly 3 |

### Real Examples

```
Doctor (1) ———————— (0..*) Patient
```
One doctor has zero or more patients. Each patient has exactly one doctor.

```
Student (0..*) ———————— (0..*) Course
```
Many-to-many: students enroll in many courses; each course has many students.

```
Order (1) ◆———————— (1..*) OrderLineItem
```
One order has one or more line items. Each line item belongs to exactly one order.

**In interviews:** Add multiplicities only when the cardinality is a meaningful design decision. "A Member can have at most 5 active borrowings" (`0..5`) is worth writing. "A String has many chars" is not.

---

## How to Read a UML Diagram

When you see a class diagram, follow this systematic approach:

### Step 1 — Find the aggregate root
Look for the class at the top that most arrows point *away from*. That's your entry point into the system. In a library system, it's `Library`. In an e-commerce system, it's `Order` or `Customer`.

### Step 2 — Trace the composition chain
Follow the filled diamonds (◆) downward. This tells you the ownership tree — what creates what, and what can't exist independently.

### Step 3 — Note the aggregation relationships
Hollow diamonds (◇) show you collections that are managed but not owned. Children here can be shared or moved between parents.

### Step 4 — Follow the inheritance hierarchy
Hollow triangles tell you the type hierarchy. Abstract classes and interfaces are usually at the top of these chains.

### Step 5 — Check the dashed lines
Dashed lines (dependencies and realizations) show which classes *use* others without owning them, and which classes *implement* contracts.

### Step 6 — Read multiplicities
Check the numbers at relationship ends. `1..*` vs `0..*` is a meaningful business rule encoded right in the diagram.

---

## How to Draw a UML Diagram from Scratch

This is the workflow for interviews and design sessions.

### Phase 1 — Identify entities (2 minutes)

From the problem statement:
- **Nouns** → candidate classes
- **Verbs** → candidate methods
- **Adjectives/quantities** → candidate fields

Ask: Is each noun a Class (has identity + behavior), an Enum (finite set of values), or a Value Object (immutable, no identity)?

### Phase 2 — Layout (1 minute)

Place classes on the page before drawing any arrows:
- Aggregate root at the **top center**
- Parent classes **above** child classes
- Inheritance hierarchies flow **downward**
- Services and utility classes go to the **sides or bottom**
- Leave space — crossing arrows look messy

### Phase 3 — Draw relationships (3 minutes)

For each pair of related classes, ask:
1. Does one create the other internally? → **Composition** (◆)
2. Does one manage a collection of the other, passed in from outside? → **Aggregation** (◇)
3. Does one hold a field reference to the other? → **Association** (→)
4. Does one use the other only in a method? → **Dependency** (- - →)
5. Is one a specific kind of the other? → **Inheritance** (▷)
6. Does one implement a contract defined by the other? → **Realization** (- - ▷)

Add multiplicities to the most meaningful relationships.

### Phase 4 — Fill in key fields and methods (2 minutes)

Focus on fields and methods that communicate design decisions:
- Fields that define ownership (`copies: List<BookCopy>`)
- Methods that enforce business rules (`hasReachedBorrowLimit()`)
- Methods that carry domain logic (`calculateFine()`)

Skip trivial getters/setters unless they're part of the discussion.

### Phase 5 — Verify (1 minute)

Quick sanity check:
- [ ] Is the aggregate root clear?
- [ ] Are diamonds on the correct (owner's) end?
- [ ] Do inheritance arrows point from child to parent?
- [ ] Are abstract classes/interfaces marked correctly?
- [ ] Do multiplicities make sense?

---

## Interview Cheat Sheet

### What interviewers look for

| Question they're asking | What to show |
|---|---|
| Are relationships correct? | Filled vs hollow diamond, dashed vs solid, correct end |
| Are responsibilities right? | `calculateFine()` on `Borrowing`, not `Library` |
| Are abstract types correct? | Interfaces and abstract classes marked with stereotypes |
| Is multiplicity sensible? | `1` library to `0..*` books |
| Is the aggregate root clear? | One class at the top, others flow from it |

### Progressive refinement approach

**Round 1 (2 min):** Boxes only — class names, no fields. Establish layout.

**Round 2 (3 min):** Relationships — draw all arrows with correct notation. Add key multiplicities.

**Round 3 (2 min):** Fill key fields and the most important methods.

### Common mistakes to avoid

1. **Diamond on the wrong end** — The diamond is always at the *owner/container* side. `Order ◆→ OrderLineItem`, never `OrderLineItem ◆→ Order`.

2. **Inheritance arrow pointing wrong way** — Always points from *child to parent*. `Dog → Animal`, not `Animal → Dog`.

3. **Confusing aggregation and composition** — Ask: "If the parent is deleted, does the child logically stop existing?" Yes → Composition. No → Aggregation.

4. **Forgetting to mark interfaces** — An interface without `«interface»` looks like a regular class.

5. **Too many classes at once** — Draw 3-5 core classes first, add more as needed. A clear small diagram beats a cramped large one.

6. **Crossing arrows** — Route arrows around boxes with L-bends, not diagonally through other classes.

---

## Quick Reference — All Symbols

```
CLASS BOX:
┌──────────────────┐
│    ClassName     │   ← bold, centered
├──────────────────┤
│  - field: Type   │   ← visibility + name + type
├──────────────────┤
│  + method(): T   │   ← visibility + name + return type
└──────────────────┘

ABSTRACT CLASS:                     INTERFACE:
┌──────────────────┐                ┌──────────────────┐
│   «abstract»     │                │   «interface»    │
│  ClassName       │ (italic)       │   InterfaceName  │
├──────────────────┤                ├──────────────────┤
│  fields...       │                │                  │
├──────────────────┤                ├──────────────────┤
│  + method()      │ (italic=abstr) │  + method()      │
└──────────────────┘                └──────────────────┘

RELATIONSHIPS:
A - - - -> B        Dependency    (uses temporarily)
A ————————> B       Association   (knows about, field)
A ◇————————> B      Aggregation   (has-a, child survives)
A ◆————————> B      Composition   (owns, child dies with parent)
A ————————▷ B       Inheritance   (IS-A, extends)
A - - - -▷ B        Realization   (implements interface)

MULTIPLICITY:
1        exactly one
0..1     zero or one
*        zero or more
0..*     zero or more
1..*     one or more
```

---

## Diagrams

Four downloadable SVG diagrams are provided alongside this file:

| File | System | Complexity | Concepts covered |
|------|--------|-----------|-----------------|
| `diagram_01_simple_vehicle.svg` | Vehicle hierarchy | Simple | Inheritance, abstract class, enum, realization |
| `diagram_02_medium_parking_lot.svg` | Parking Lot | Medium | Composition, association, multiplicity, enum |
| `diagram_03_complex_library.svg` | Library Management | Complex | All five relationships, aggregate root, value object, service layer |
| `diagram_04_complex_ecommerce.svg` | E-Commerce Order | Complex | Multi-level composition, interface hierarchy, strategy pattern in UML |

---

*These notes are part of Phase 2 — Object Modeling and Relationships. The next phase is Phase 3 — Design Patterns.*

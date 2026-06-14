# Chess Game — LLD Design Notes

## Problem Summary

Design a two-player chess game with full move validation, check/checkmate/
stalemate detection, move history, and undo support.

---

## Three Sub-Problems (and their solutions)

| Sub-problem | Solution |
|-------------|----------|
| Each piece has different movement rules | Strategy pattern per piece type |
| Move history and undo | Command pattern — Move is executable/undoable |
| Check/checkmate detection | Simulation via Command's execute/undo |

---

## Patterns Used

### 1. Strategy — `MovementStrategy` per piece type

```
MovementStrategy (interface)
├── KingStrategy    — 8 adjacent squares
├── QueenStrategy   — slides in all 8 directions
├── RookStrategy    — slides horizontally/vertically
├── BishopStrategy  — slides diagonally
├── KnightStrategy  — 8 fixed L-shape jumps
└── PawnStrategy    — forward + diagonal capture (most rule-dense)
```

Each `Piece` holds a `MovementStrategy` reference. `getLegalSquares()` returns
raw candidates; check filtering happens in the `Game` layer via simulation.

Why Strategy here specifically: PawnStrategy alone would require ~40 lines of
conditional logic in a monolithic switch. Isolated to its own class, it's
testable, readable, and adding a custom fairy piece is one new class.

### 2. Command — `Move`

Every move is a `Move` object with `from`, `to`, `piece`, and `captured`.

```java
move.execute(board);  // apply to board
move.undo(board);     // reverse exactly
```

`GameHistory` is a `Deque<Move>` — push on execute, pop on undo.

### 3. Factory — `PieceFactory`

```java
PieceFactory.createPiece(Color.WHITE, PieceType.QUEEN)
PieceFactory.setupStandardBoard(board)
```

Decouples piece construction from game logic. Swap in Chess960 setup or
custom positions without touching `Game`.

---

## Key Design Decision: Check Detection via Simulation

This is the most important algorithmic decision in the design.

**The question:** After White plays Bc4, is White's King safe? After Black
plays Nf6, is Black's King exposed?

**Naïve approach:** Write special-case logic for each way a King can be
left in check — discovered checks, pinned pieces, etc. Fragile and incomplete.

**Correct approach — move simulation:**

```java
for (Position to : candidates) {
    Move candidate = new Move(from, to, piece, board.getPiece(to));
    candidate.execute(board);                              // 1. try the move
    boolean leavesKingInCheck = board.isUnderAttack(
            board.findKing(currentTurn), currentTurn.opponent());  // 2. test
    candidate.undo(board);                                // 3. revert
    if (!leavesKingInCheck) legal.add(to);               // 4. keep if safe
}
```

This is *correct by construction* — it handles pins, discoveries, and every
edge case automatically. The Command pattern's `undo()` is what makes this
simulation cheap and clean.

**Checkmate and stalemate** follow from the same logic:

```
In check  + no legal moves  → CHECKMATE
Not in check + no legal moves → STALEMATE
```

`hasAnyLegalMove()` iterates all own pieces and calls `getLegalMoves()` on
each. If the aggregate is empty, the game is over.

---

## Key Design Decisions

### Board as source of truth

Pieces don't know their position — the Board does. `getLegalSquares(from, board)`
takes `from` as a parameter. This avoids the awkward state where a Piece
holds a mutable position reference that must stay in sync with the board.

### `Position` as an immutable value object

```java
static final class Position {
    final int row, col;
    // equals + hashCode based on row, col
}
```

Safe to use in `List.contains()` (used in `getLegalMoves` check), safe to
share across method boundaries, no aliasing bugs.

### `slide()` as a package-private helper

Rook, Bishop, and Queen all use directional sliding. Rather than duplicating
the loop in each strategy, a static `slide(from, board, color, dr, dc, result)`
helper is shared. This is a clean SRP application: the sliding logic is in
one place, strategies just provide direction vectors.

### PawnStrategy is the most rule-dense class

This is intentional — it demonstrates the Strategy pattern's isolation benefit.
Pawn has: forward-only movement, 2-square opening move, diagonal-only capture,
direction dependence on color, and promotion. All of this is contained in one
class, fully independent of King, Rook, etc.

### Pawn promotion auto-queens

When a Pawn reaches the back rank, `Move.execute()` places a Queen instead.
The `Move.undo()` restores the original Pawn (the `piece` field is the
pre-promotion Pawn). A full implementation would prompt the player; the
`isPromotion` flag makes this extension point explicit.

---

## Class Summary

```
Color (enum) — WHITE, BLACK, .opponent()
PieceType (enum) — KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN

Position — immutable (row, col), value equality
Board    — Piece[8][8], findKing(), isUnderAttack()
Piece    — color + type + MovementStrategy

MovementStrategy (interface)
└── KingStrategy, QueenStrategy, RookStrategy,
    BishopStrategy, KnightStrategy, PawnStrategy

Move     — from, to, piece, captured; execute() / undo()
GameHistory — Deque<Move>

Player      — name, color
PieceFactory — createPiece(), setupStandardBoard()
Game        — getLegalMoves(), makeMove(), undoLastMove(),
              isInCheck(), checkTerminalCondition()
```

---

## Complexity

| Operation | Time | Notes |
|-----------|------|-------|
| `getLegalMoves(pos)` | O(m × 64) | m = candidate squares; each simulated move calls isUnderAttack (O(64)) |
| `isUnderAttack(pos, color)` | O(64 × m) | scan all opponent pieces |
| `hasAnyLegalMove(color)` | O(64 × m × 64) | worst case — scans entire board |
| `makeMove()` | O(1) (execute) + O(m × 64) (terminal check) | |
| `undoLastMove()` | O(1) | just undo + pop |
| `findKing()` | O(64) | linear scan; acceptable for 8×8 |

Production chess engines would cache King position and attacked squares, but
for LLD interviews this is the right trade-off: correct > fast.

---

## Concurrency Design

None applied — chess is strictly turn-based and single-threaded. In an
online multiplayer system, move submission would go through a synchronized
`GameSession` per game, but that's network/infrastructure concern, not LLD.

---

## What's Not Implemented (and why)

| Feature | Why omitted |
|---------|-------------|
| Castling | Requires King/Rook "never moved" flag — adds 2 fields, straightforward |
| En passant | Requires tracking the last move's double-pawn push — `GameHistory.peek()` |
| 50-move rule / threefold repetition | Draw rules, not LLD core |
| Clock / time control | Infrastructure concern |

Each extension is isolated — adding castling doesn't change PawnStrategy,
adding en passant doesn't change KingStrategy.

---

## Extension Possibilities

- **Custom pieces** (fairy chess): implement `MovementStrategy`, pass to
  `PieceFactory.createPiece()`. Zero changes to existing classes.
- **Chess960**: swap `PieceFactory.setupStandardBoard()` with a shuffled
  back-row setup.
- **Move notation (PGN)**: `Move.toString()` already produces algebraic-ish
  notation; a `PGNSerializer` reads `GameHistory`.
- **AI opponent**: minimax over `getLegalMoves()` + `makeMove()` + `undoLastMove()`.
  The Command pattern's undo makes search trees viable.

---

## Phase Connections

| Concept | Where learned | Where applied |
|---------|--------------|---------------|
| Strategy pattern | Phase 3 (GoF Behavioral) | MovementStrategy per piece |
| Command pattern | Phase 3 (GoF Behavioral) | Move.execute() / undo() |
| Factory pattern | Phase 3 (GoF Creational) | PieceFactory |
| Value object | Phase 2 (Object Modeling) | Position |
| SRP / OCP | Phase 1 (SOLID) | Each strategy class has one job |
| Enums with methods | Phase 3.5 | Color.opponent() |
| Interface + polymorphism | Phase 1 (OOP) | Board.isUnderAttack() calls getCandidateSquares() uniformly |

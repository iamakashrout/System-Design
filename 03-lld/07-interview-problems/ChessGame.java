import java.util.*;

/**
 * LLD Problem 6: Chess Game
 *
 * Patterns used:
 *  - Strategy:  MovementStrategy per piece type — each piece owns its movement
 *               rules. Adding a new piece = one new class, zero edits elsewhere.
 *  - Command:   Move is an executable/undoable object. This enables undo,
 *               history, and — critically — move simulation for check detection.
 *  - Factory:   PieceFactory sets up the standard 32-piece starting position,
 *               decoupling construction from game logic.
 *
 * Key design decisions:
 *  1. Board is the source of truth for piece locations. Pieces don't know
 *     their position — the Board does. Position is passed into getLegalMoves().
 *  2. Check detection works by simulation: execute candidate move on board,
 *     test if own King is attacked, undo move. This is correct by construction.
 *  3. Position is an immutable value object — safe to use as Map key, share freely.
 *  4. No concurrency — chess is turn-based and single-threaded by nature.
 */
public class ChessGame {

    // ─────────────────────────────────────────────────────────────
    // Enums
    // ─────────────────────────────────────────────────────────────

    enum Color { WHITE, BLACK;
        Color opponent() { return this == WHITE ? BLACK : WHITE; }
    }

    enum PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

    // ─────────────────────────────────────────────────────────────
    // Value object: Position
    // Immutable — safe to use as Map/Set key.
    // ─────────────────────────────────────────────────────────────

    static final class Position {
        final int row, col;

        Position(int row, int col) { this.row = row; this.col = col; }

        boolean isValid() { return row >= 0 && row < 8 && col >= 0 && col < 8; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Position p)) return false;
            return row == p.row && col == p.col;
        }
        @Override public int hashCode() { return row * 8 + col; }
        @Override public String toString() {
            return "" + (char)('a' + col) + (row + 1);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Strategy interface: MovementStrategy
    //
    // Each implementation encapsulates the movement rules for one piece type.
    // getLegalSquares returns candidate squares; the Game layer filters out
    // any that leave the own King in check.
    // ─────────────────────────────────────────────────────────────

    interface MovementStrategy {
        /**
         * Returns squares this piece can move to from 'from', ignoring check.
         * Check filtering happens in ChessGame.getLegalMoves() via simulation.
         */
        List<Position> getLegalSquares(Position from, Board board, Color color);
    }

    // Helper: slide in a direction until blocked
    private static void slide(Position from, Board board, Color color,
                               int dr, int dc, List<Position> result) {
        int r = from.row + dr, c = from.col + dc;
        while (r >= 0 && r < 8 && c >= 0 && c < 8) {
            Position pos = new Position(r, c);
            Piece occupant = board.getPiece(pos);
            if (occupant == null) {
                result.add(pos);
            } else {
                if (occupant.color != color) result.add(pos); // capture
                break;
            }
            r += dr; c += dc;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Concrete strategies — one per piece type
    // ─────────────────────────────────────────────────────────────

    /** King: one step in any of 8 directions. Castling handled separately. */
    static final class KingStrategy implements MovementStrategy {
        private static final int[][] DIRS = {
            {1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}
        };
        @Override
        public List<Position> getLegalSquares(Position from, Board board, Color color) {
            List<Position> result = new ArrayList<>();
            for (int[] d : DIRS) {
                Position p = new Position(from.row + d[0], from.col + d[1]);
                if (!p.isValid()) continue;
                Piece occupant = board.getPiece(p);
                if (occupant == null || occupant.color != color) result.add(p);
            }
            return result;
        }
    }

    /** Queen: combines Rook + Bishop movement. */
    static final class QueenStrategy implements MovementStrategy {
        private static final int[][] DIRS = {
            {1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}
        };
        @Override
        public List<Position> getLegalSquares(Position from, Board board, Color color) {
            List<Position> result = new ArrayList<>();
            for (int[] d : DIRS) slide(from, board, color, d[0], d[1], result);
            return result;
        }
    }

    /** Rook: slides horizontally and vertically. */
    static final class RookStrategy implements MovementStrategy {
        private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
        @Override
        public List<Position> getLegalSquares(Position from, Board board, Color color) {
            List<Position> result = new ArrayList<>();
            for (int[] d : DIRS) slide(from, board, color, d[0], d[1], result);
            return result;
        }
    }

    /** Bishop: slides diagonally. */
    static final class BishopStrategy implements MovementStrategy {
        private static final int[][] DIRS = {{1,1},{1,-1},{-1,1},{-1,-1}};
        @Override
        public List<Position> getLegalSquares(Position from, Board board, Color color) {
            List<Position> result = new ArrayList<>();
            for (int[] d : DIRS) slide(from, board, color, d[0], d[1], result);
            return result;
        }
    }

    /** Knight: 8 fixed L-shaped jumps, ignores intervening pieces. */
    static final class KnightStrategy implements MovementStrategy {
        private static final int[][] JUMPS = {
            {2,1},{2,-1},{-2,1},{-2,-1},{1,2},{1,-2},{-1,2},{-1,-2}
        };
        @Override
        public List<Position> getLegalSquares(Position from, Board board, Color color) {
            List<Position> result = new ArrayList<>();
            for (int[] j : JUMPS) {
                Position p = new Position(from.row + j[0], from.col + j[1]);
                if (!p.isValid()) continue;
                Piece occupant = board.getPiece(p);
                if (occupant == null || occupant.color != color) result.add(p);
            }
            return result;
        }
    }

    /**
     * Pawn: moves forward one square (or two from starting rank),
     * captures diagonally. Direction depends on color.
     *
     * This is the most rule-dense strategy — a good demonstration of why
     * Strategy beats a monolithic switch: Pawn's complexity is fully isolated.
     */
    static final class PawnStrategy implements MovementStrategy {
        @Override
        public List<Position> getLegalSquares(Position from, Board board, Color color) {
            List<Position> result = new ArrayList<>();
            int dir = (color == Color.WHITE) ? 1 : -1;
            int startRow = (color == Color.WHITE) ? 1 : 6;

            // Forward one
            Position oneAhead = new Position(from.row + dir, from.col);
            if (oneAhead.isValid() && board.getPiece(oneAhead) == null) {
                result.add(oneAhead);
                // Forward two from starting rank
                if (from.row == startRow) {
                    Position twoAhead = new Position(from.row + 2 * dir, from.col);
                    if (board.getPiece(twoAhead) == null) result.add(twoAhead);
                }
            }

            // Diagonal captures
            for (int dc : new int[]{-1, 1}) {
                Position diag = new Position(from.row + dir, from.col + dc);
                if (!diag.isValid()) continue;
                Piece occupant = board.getPiece(diag);
                if (occupant != null && occupant.color != color) result.add(diag);
            }

            return result;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Domain: Piece
    // Holds color + piece type + strategy reference.
    // Board holds the position — pieces themselves are position-agnostic.
    // ─────────────────────────────────────────────────────────────

    static final class Piece {
        final Color color;
        final PieceType type;
        private final MovementStrategy strategy;

        Piece(Color color, PieceType type, MovementStrategy strategy) {
            this.color = color;
            this.type = type;
            this.strategy = strategy;
        }

        /**
         * Raw candidate squares — does NOT filter for self-check.
         * Callers (ChessGame) apply check filtering via move simulation.
         */
        List<Position> getCandidateSquares(Position from, Board board) {
            return strategy.getLegalSquares(from, board, color);
        }

        @Override public String toString() {
            String symbol = switch (type) {
                case KING -> "K"; case QUEEN -> "Q"; case ROOK -> "R";
                case BISHOP -> "B"; case KNIGHT -> "N"; case PAWN -> "P";
            };
            return (color == Color.WHITE ? "W" : "B") + symbol;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Board: source of truth for piece locations
    // ─────────────────────────────────────────────────────────────

    static final class Board {
        private final Piece[][] grid = new Piece[8][8];

        Piece getPiece(Position pos) { return grid[pos.row][pos.col]; }

        void setPiece(Position pos, Piece piece) { grid[pos.row][pos.col] = piece; }

        void removePiece(Position pos) { grid[pos.row][pos.col] = null; }

        /**
         * Find the King of the given color.
         * Used by check detection — O(64) scan, acceptable for interview context.
         */
        Position findKing(Color color) {
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Piece p = grid[r][c];
                    if (p != null && p.color == color && p.type == PieceType.KING) {
                        return new Position(r, c);
                    }
                }
            }
            throw new IllegalStateException("King not found — invalid board state");
        }

        /**
         * Is the given position attacked by any piece of 'attackerColor'?
         * Used to validate: is my King in check after a candidate move?
         */
        boolean isUnderAttack(Position pos, Color attackerColor) {
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Piece p = grid[r][c];
                    if (p == null || p.color != attackerColor) continue;
                    List<Position> attacks = p.getCandidateSquares(new Position(r, c), this);
                    if (attacks.contains(pos)) return true;
                }
            }
            return false;
        }

        /** Pretty-print the board for demo output. */
        void print() {
            System.out.println("  a   b   c   d   e   f   g   h");
            System.out.println("  ─────────────────────────────────");
            for (int r = 7; r >= 0; r--) {
                System.out.print((r + 1) + " ");
                for (int c = 0; c < 8; c++) {
                    Piece p = grid[r][c];
                    System.out.printf("%-4s", p == null ? "·" : p.toString());
                }
                System.out.println(" " + (r + 1));
            }
            System.out.println("  ─────────────────────────────────");
            System.out.println("  a   b   c   d   e   f   g   h");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Command: Move
    //
    // Captures everything needed to execute AND undo a move.
    // This is what makes check simulation correct: execute → test → undo.
    // ─────────────────────────────────────────────────────────────

    static final class Move {
        final Position from;
        final Position to;
        final Piece piece;
        final Piece captured;      // null if not a capture
        final boolean isPromotion; // pawn reaching back rank

        Move(Position from, Position to, Piece piece, Piece captured) {
            this.from = from;
            this.to = to;
            this.piece = piece;
            this.captured = captured;
            this.isPromotion = piece.type == PieceType.PAWN
                    && ((piece.color == Color.WHITE && to.row == 7)
                        || (piece.color == Color.BLACK && to.row == 0));
        }

        void execute(Board board) {
            board.removePiece(from);
            if (isPromotion) {
                // Auto-promote to Queen (standard default)
                board.setPiece(to, PieceFactory.createPiece(piece.color, PieceType.QUEEN));
            } else {
                board.setPiece(to, piece);
            }
        }

        void undo(Board board) {
            board.setPiece(from, piece);      // restore original piece (pre-promotion)
            if (captured != null) {
                board.setPiece(to, captured); // restore captured piece
            } else {
                board.removePiece(to);
            }
        }

        @Override public String toString() {
            String cap = captured != null ? " x" + captured : "";
            String promo = isPromotion ? "=Q" : "";
            return piece + ": " + from + cap + " → " + to + promo;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Factory: PieceFactory
    // Centralises piece construction and board setup.
    // ─────────────────────────────────────────────────────────────

    static final class PieceFactory {
        static Piece createPiece(Color color, PieceType type) {
            MovementStrategy strategy = switch (type) {
                case KING   -> new KingStrategy();
                case QUEEN  -> new QueenStrategy();
                case ROOK   -> new RookStrategy();
                case BISHOP -> new BishopStrategy();
                case KNIGHT -> new KnightStrategy();
                case PAWN   -> new PawnStrategy();
            };
            return new Piece(color, type, strategy);
        }

        /** Sets up the standard chess starting position. */
        static void setupStandardBoard(Board board) {
            PieceType[] backRow = {
                PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
                PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
            };
            for (int c = 0; c < 8; c++) {
                board.setPiece(new Position(0, c), createPiece(Color.WHITE, backRow[c]));
                board.setPiece(new Position(7, c), createPiece(Color.BLACK, backRow[c]));
                board.setPiece(new Position(1, c), createPiece(Color.WHITE, PieceType.PAWN));
                board.setPiece(new Position(6, c), createPiece(Color.BLACK, PieceType.PAWN));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Player
    // ─────────────────────────────────────────────────────────────

    static final class Player {
        final String name;
        final Color color;

        Player(String name, Color color) { this.name = name; this.color = color; }

        @Override public String toString() { return name + "(" + color + ")"; }
    }

    // ─────────────────────────────────────────────────────────────
    // GameHistory: Deque of executed moves
    // ─────────────────────────────────────────────────────────────

    static final class GameHistory {
        private final Deque<Move> moves = new ArrayDeque<>();

        void push(Move move) { moves.push(move); }
        Move pop()           { return moves.pop(); }
        boolean isEmpty()    { return moves.isEmpty(); }
        int size()           { return moves.size(); }
        List<Move> getAll()  { return new ArrayList<>(moves); }
    }

    // ─────────────────────────────────────────────────────────────
    // ChessGame: the game engine
    //
    // Responsibilities:
    //  - Turn management
    //  - Legal move generation (candidate squares → filter for self-check)
    //  - Move execution via Command pattern
    //  - Check, checkmate, stalemate detection
    // ─────────────────────────────────────────────────────────────

    static final class Game {
        private final Board board;
        private final Player white;
        private final Player black;
        private final GameHistory history = new GameHistory();
        private Color currentTurn = Color.WHITE;
        private boolean gameOver = false;

        Game(Player white, Player black) {
            this.white = white;
            this.black = black;
            this.board = new Board();
            PieceFactory.setupStandardBoard(board);
        }

        /**
         * Returns all legal moves for the piece at 'from' (belonging to currentTurn).
         *
         * Algorithm:
         *  1. Get raw candidate squares from the piece's MovementStrategy.
         *  2. For each candidate: simulate the move (execute), check if own King
         *     is now under attack, undo. Filter out any that leave King in check.
         *
         * This is the key insight: Command's undo() makes simulation cheap and correct.
         */
        List<Position> getLegalMoves(Position from) {
            Piece piece = board.getPiece(from);
            if (piece == null || piece.color != currentTurn) return List.of();

            List<Position> candidates = piece.getCandidateSquares(from, board);
            List<Position> legal = new ArrayList<>();

            for (Position to : candidates) {
                Move candidate = new Move(from, to, piece, board.getPiece(to));
                candidate.execute(board);
                boolean leavesKingInCheck = board.isUnderAttack(
                        board.findKing(currentTurn), currentTurn.opponent());
                candidate.undo(board);
                if (!leavesKingInCheck) legal.add(to);
            }

            return legal;
        }

        /**
         * Execute a move. Returns false if the move is illegal.
         */
        boolean makeMove(Position from, Position to) {
            if (gameOver) { System.out.println("Game is already over."); return false; }

            List<Position> legal = getLegalMoves(from);
            if (!legal.contains(to)) {
                System.out.printf("Illegal move: %s → %s%n", from, to);
                return false;
            }

            Piece piece = board.getPiece(from);
            Piece captured = board.getPiece(to);
            Move move = new Move(from, to, piece, captured);
            move.execute(board);
            history.push(move);
            System.out.println("Move: " + move);

            // Switch turns and check for terminal conditions
            currentTurn = currentTurn.opponent();
            checkTerminalCondition();
            return true;
        }

        /** Undo the last move. Switches turn back. */
        boolean undoLastMove() {
            if (history.isEmpty()) { System.out.println("No moves to undo."); return false; }
            Move last = history.pop();
            last.undo(board);
            currentTurn = currentTurn.opponent();
            gameOver = false;
            System.out.println("Undone: " + last);
            return true;
        }

        /** Is the given color's King currently in check? */
        boolean isInCheck(Color color) {
            return board.isUnderAttack(board.findKing(color), color.opponent());
        }

        /**
         * Does the given color have any legal moves?
         * Used for both checkmate and stalemate detection.
         */
        private boolean hasAnyLegalMove(Color color) {
            Color saved = currentTurn;
            currentTurn = color;
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Piece p = board.getPiece(new Position(r, c));
                    if (p != null && p.color == color) {
                        if (!getLegalMoves(new Position(r, c)).isEmpty()) {
                            currentTurn = saved;
                            return true;
                        }
                    }
                }
            }
            currentTurn = saved;
            return false;
        }

        private void checkTerminalCondition() {
            boolean inCheck = isInCheck(currentTurn);
            boolean hasLegal = hasAnyLegalMove(currentTurn);

            if (inCheck && !hasLegal) {
                System.out.println("CHECKMATE! " + currentTurn.opponent() + " wins!");
                gameOver = true;
            } else if (!inCheck && !hasLegal) {
                System.out.println("STALEMATE! Draw.");
                gameOver = true;
            } else if (inCheck) {
                System.out.println(currentTurn + " is in CHECK!");
            }
        }

        Board getBoard() { return board; }
        Color getCurrentTurn() { return currentTurn; }
        GameHistory getHistory() { return history; }
        boolean isGameOver() { return gameOver; }
    }

    // ─────────────────────────────────────────────────────────────
    // Demo
    // ─────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("══════════════════════════════════════════════");
        System.out.println("  Chess Game — LLD Demo");
        System.out.println("══════════════════════════════════════════════\n");

        Player white = new Player("Akash", Color.WHITE);
        Player black = new Player("Magnus", Color.BLACK);
        Game game = new Game(white, black);

        System.out.println("── Starting position ─────────────────────────");
        game.getBoard().print();

        // ── Scholar's Mate (4-move checkmate) ──
        // 1. e4
        System.out.println("\n── 1. e4 (White pawn e2 → e4) ───────────────");
        game.makeMove(new Position(1, 4), new Position(3, 4));

        // 1... e5
        System.out.println("── 1... e5 (Black pawn e7 → e5) ─────────────");
        game.makeMove(new Position(6, 4), new Position(4, 4));

        // 2. Bc4
        System.out.println("── 2. Bc4 (White bishop f1 → c4) ────────────");
        game.makeMove(new Position(0, 5), new Position(3, 2));

        // 2... Nc6
        System.out.println("── 2... Nc6 (Black knight b8 → c6) ──────────");
        game.makeMove(new Position(7, 1), new Position(5, 2));

        // 3. Qh5
        System.out.println("── 3. Qh5 (White queen d1 → h5) ─────────────");
        game.makeMove(new Position(0, 3), new Position(4, 7));

        System.out.println("\n── Board after 3. Qh5 ───────────────────────");
        game.getBoard().print();

        // 3... Nf6? (blunder — allows Qxf7#)
        System.out.println("\n── 3... Nf6? (Black knight g8 → f6) ─────────");
        game.makeMove(new Position(7, 6), new Position(5, 5));

        // 4. Qxf7# — Scholar's Mate
        System.out.println("── 4. Qxf7# (White queen h5 → f7, checkmate!) ─");
        game.makeMove(new Position(4, 7), new Position(6, 5));

        System.out.println("\n── Final board ───────────────────────────────");
        game.getBoard().print();

        // ── Demonstrate undo ──
        System.out.println("\n── Undoing last two moves ────────────────────");
        game.undoLastMove();
        game.undoLastMove();

        System.out.println("\n── Board after undo (back to move 3) ─────────");
        game.getBoard().print();

        // ── Demonstrate move history ──
        System.out.println("\n── Move history ──────────────────────────────");
        game.getHistory().getAll().forEach(m -> System.out.println("  " + m));

        // ── Legal move enumeration ──
        System.out.println("\n── Legal moves for White Queen at h5 (after undo) ─");
        Position queenPos = new Position(4, 7);
        List<Position> queenMoves = game.getLegalMoves(queenPos);
        System.out.println("  " + queenMoves);

        // ── Illegal move attempt ──
        System.out.println("\n── Attempting illegal move (pawn backward) ───");
        game.makeMove(new Position(3, 4), new Position(2, 4)); // e4 → e3 (backward)
    }
}

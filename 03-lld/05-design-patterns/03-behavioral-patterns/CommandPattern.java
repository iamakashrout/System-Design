import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// =============================================================================
// PATTERN: Command
// PURPOSE: Encapsulate an action as an object. This lets you parameterize
//          clients with different requests, queue or log requests, and
//          support undoable operations.
//
// REAL-WORLD ANALOGY:
//   A restaurant. You (client) tell a waiter (invoker) your order.
//   The waiter writes it on an ORDER SLIP (command object) and hands it
//   to the kitchen (receiver). The slip can be queued, replayed, cancelled.
//   The waiter doesn't cook. The kitchen doesn't know who ordered.
//   The command object IS that slip — it holds everything needed to act later.
//
// THE PROBLEM THIS SOLVES:
//   Direct method calls are fire-and-forget:
//     document.insert(5, "Hello");  ← can't undo, can't queue, can't replay
//
//   With Command, every action becomes an object:
//     EditorCommand cmd = new InsertTextCommand(doc, 5, "Hello");
//     history.execute(cmd);         ← stored, undoable, replayable
//
// FOUR INGREDIENTS:
//   1. Command interface  → execute() and undo()
//   2. Concrete commands  → encapsulate one action + the data to reverse it
//   3. Receiver           → the object that actually does the work (TextDocument)
//   4. Invoker            → stores history, calls execute/undo/redo
// =============================================================================

public class CommandPattern {

    // =========================================================================
    // STEP 1: COMMAND INTERFACE
    // Every action in the editor implements this.
    // execute() — performs the action
    // undo()    — reverses the action
    // The undo() method is what makes this pattern so powerful.
    // =========================================================================
    interface EditorCommand {
        void execute();
        void undo();
        String getDescription(); // for history display and logging
    }


    // =========================================================================
    // STEP 2: RECEIVER — TextDocument
    //
    // This is the object that actually does the work.
    // It provides primitive operations (insert, delete, replace).
    // It knows NOTHING about Command, undo stacks, or history.
    // Its only job is to correctly mutate the text.
    // =========================================================================
    static class TextDocument {
        private final StringBuilder content;
        private final String name;

        public TextDocument(String name, String initialContent) {
            this.name    = name;
            this.content = new StringBuilder(initialContent);
        }

        // Primitive operation 1: insert text at a position
        public void insert(int position, String text) {
            if (position < 0 || position > content.length())
                throw new IndexOutOfBoundsException("Invalid insert position: " + position);
            content.insert(position, text);
        }

        // Primitive operation 2: delete a range of characters
        public void delete(int position, int length) {
            if (position < 0 || position + length > content.length())
                throw new IndexOutOfBoundsException("Invalid delete range");
            content.delete(position, position + length);
        }

        public String getContent()   { return content.toString(); }
        public int length()          { return content.length(); }
        public String getName()      { return name; }
    }


    // =========================================================================
    // STEP 3: CONCRETE COMMANDS
    //
    // Each command encapsulates ONE specific action.
    // The KEY DESIGN: each command saves whatever is needed to UNDO itself
    // before it executes. You can't undo what you don't remember.
    // =========================================================================

    // ── Command 1: Insert Text ────────────────────────────────────────────────
    static class InsertTextCommand implements EditorCommand {
        private final TextDocument document;
        private final int          position;
        private final String       text;

        public InsertTextCommand(TextDocument document, int position, String text) {
            this.document = document;
            this.position = position;
            this.text     = text;
        }

        @Override
        public void execute() {
            document.insert(position, text);
        }

        @Override
        public void undo() {
            // Undo an insert = delete exactly what was inserted
            // We know: position (where we inserted) and text.length() (how much)
            document.delete(position, text.length());
        }

        @Override
        public String getDescription() {
            return String.format("Insert \"%s\" at pos %d", text, position);
        }
    }


    // ── Command 2: Delete Text ────────────────────────────────────────────────
    static class DeleteTextCommand implements EditorCommand {
        private final TextDocument document;
        private final int          position;
        private final int          length;
        private String             savedText; // ← CRITICAL: saved BEFORE deletion for undo

        public DeleteTextCommand(TextDocument document, int position, int length) {
            this.document = document;
            this.position = position;
            this.length   = length;
        }

        @Override
        public void execute() {
            // Save what we're about to delete — this is the "memory" for undo
            // If we don't save now, the text is gone and undo is impossible
            savedText = document.getContent().substring(position, position + length);
            document.delete(position, length);
        }

        @Override
        public void undo() {
            // Undo a delete = re-insert the text we saved before deleting
            document.insert(position, savedText);
        }

        @Override
        public String getDescription() {
            // savedText may be null if execute() hasn't been called yet
            String preview = savedText != null ? "\"" + savedText + "\"" : "(not yet executed)";
            return String.format("Delete %d chars at pos %d → was %s", length, position, preview);
        }
    }


    // ── Command 3: Replace Text ───────────────────────────────────────────────
    static class ReplaceTextCommand implements EditorCommand {
        private final TextDocument document;
        private final int          position;
        private final String       newText;
        private String             oldText; // ← saved for undo

        public ReplaceTextCommand(TextDocument document, int position, String newText) {
            this.document = document;
            this.position = position;
            this.newText  = newText;
        }

        @Override
        public void execute() {
            // Save the text being overwritten before replacing it
            oldText = document.getContent().substring(position, position + newText.length());
            document.delete(position, newText.length());
            document.insert(position, newText);
        }

        @Override
        public void undo() {
            // Undo a replace = put the old text back
            document.delete(position, newText.length());
            document.insert(position, oldText);
        }

        @Override
        public String getDescription() {
            return String.format("Replace at pos %d: \"%s\" → \"%s\"",
                    position, oldText != null ? oldText : "?", newText);
        }
    }


    // ── Command 4: Macro Command (composite command) ──────────────────────────
    // A macro is a list of commands treated as one single command.
    // execute() runs all of them in order.
    // undo() reverses them in reverse order.
    // This shows that Command objects are composable.
    static class MacroCommand implements EditorCommand {
        private final String              macroName;
        private final List<EditorCommand> commands = new ArrayList<>();

        public MacroCommand(String name) {
            this.macroName = name;
        }

        public void addCommand(EditorCommand command) {
            commands.add(command);
        }

        @Override
        public void execute() {
            // Execute all commands in order
            for (EditorCommand command : commands) {
                command.execute();
            }
        }

        @Override
        public void undo() {
            // Undo in REVERSE order — last action undone first
            for (int i = commands.size() - 1; i >= 0; i--) {
                commands.get(i).undo();
            }
        }

        @Override
        public String getDescription() {
            return "Macro[" + macroName + "] (" + commands.size() + " steps)";
        }
    }


    // =========================================================================
    // STEP 4: INVOKER — EditorHistory
    //
    // The invoker manages the command history (undo/redo stacks).
    // It never knows what a command does — it just calls execute() and undo().
    //
    // UNDO STACK: every executed command is pushed here
    // REDO STACK: every undone command is pushed here
    //             cleared whenever a new command is executed (same as real editors)
    // =========================================================================
    static class EditorHistory {
        private final TextDocument           document;
        private final Deque<EditorCommand>   undoStack = new ArrayDeque<>();
        private final Deque<EditorCommand>   redoStack = new ArrayDeque<>();

        public EditorHistory(TextDocument document) {
            this.document = document;
        }

        public void execute(EditorCommand command) {
            command.execute();
            undoStack.push(command);
            redoStack.clear(); // executing a new command invalidates the redo history
            printState("Executed", command.getDescription());
        }

        public void undo() {
            if (undoStack.isEmpty()) {
                System.out.println("  [History] Nothing to undo");
                return;
            }
            EditorCommand command = undoStack.pop();
            command.undo();
            redoStack.push(command); // move to redo stack so it can be re-applied
            printState("Undid", command.getDescription());
        }

        public void redo() {
            if (redoStack.isEmpty()) {
                System.out.println("  [History] Nothing to redo");
                return;
            }
            EditorCommand command = redoStack.pop();
            command.execute();
            undoStack.push(command); // move back to undo stack
            printState("Redid", command.getDescription());
        }

        private void printState(String action, String description) {
            System.out.printf("  [History] %-8s | %-40s | Doc: \"%s\"%n",
                    action, description, document.getContent());
            System.out.printf("            Undo stack: %d  | Redo stack: %d%n",
                    undoStack.size(), redoStack.size());
        }

        public int undoDepth() { return undoStack.size(); }
        public int redoDepth() { return redoStack.size(); }
    }


    // =========================================================================
    // AUDIT LOG — demonstrates Command as an audit/log mechanism
    // Every action that goes through this invoker is also recorded.
    // =========================================================================
    static class AuditedHistory extends EditorHistory {
        private final List<String> auditLog = new ArrayList<>();

        public AuditedHistory(TextDocument document) {
            super(document);
        }

        @Override
        public void execute(EditorCommand command) {
            auditLog.add("EXECUTE: " + command.getDescription());
            super.execute(command);
        }

        @Override
        public void undo() {
            auditLog.add("UNDO");
            super.undo();
        }

        @Override
        public void redo() {
            auditLog.add("REDO");
            super.redo();
        }

        public void printAuditLog() {
            System.out.println("\n  ── Audit Log ──");
            for (int i = 0; i < auditLog.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + auditLog.get(i));
            }
        }
    }


    // =========================================================================
    // MAIN — demonstrates execute, undo, redo, macros, and audit logging
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Command Pattern Demo ===\n");

        TextDocument  doc    = new TextDocument("design-notes.txt", "Hello World");
        AuditedHistory editor = new AuditedHistory(doc);

        System.out.println("  Initial content: \"" + doc.getContent() + "\"");
        System.out.println();


        // ── Step 1: Execute a series of commands ──────────────────────────────
        System.out.println("─── Executing Commands ──────────────────────────────────────────");
        editor.execute(new InsertTextCommand(doc, 5, ", Java"));
        editor.execute(new DeleteTextCommand(doc, 0, 5));
        editor.execute(new ReplaceTextCommand(doc, 0, "Hi  "));


        // ── Step 2: Undo operations ───────────────────────────────────────────
        System.out.println("\n─── Undo (3 times) ──────────────────────────────────────────────");
        editor.undo(); // undo the Replace
        editor.undo(); // undo the Delete
        editor.undo(); // undo the Insert


        // ── Step 3: Redo ──────────────────────────────────────────────────────
        System.out.println("\n─── Redo (2 times) ──────────────────────────────────────────────");
        editor.redo(); // redo the Insert
        editor.redo(); // redo the Delete


        // ── Step 4: New command clears redo stack ─────────────────────────────
        System.out.println("\n─── New Command After Partial Redo (clears redo stack) ──────────");
        editor.execute(new InsertTextCommand(doc, 0, "Note: "));
        // Redo stack should now be 0
        editor.redo(); // should print "nothing to redo"


        // ── Step 5: Macro — multiple commands as one undoable unit ────────────
        System.out.println("\n─── Macro Command (multi-step as one unit) ──────────────────────");
        TextDocument doc2   = new TextDocument("macro-test.txt", "aaa bbb ccc");
        EditorHistory editor2 = new EditorHistory(doc2);

        System.out.println("  Before macro: \"" + doc2.getContent() + "\"");

        // Build a macro that does 3 things
        MacroCommand formatMacro = new MacroCommand("FormatDocument");
        formatMacro.addCommand(new ReplaceTextCommand(doc2, 0, "AAA"));
        formatMacro.addCommand(new ReplaceTextCommand(doc2, 4, "BBB"));
        formatMacro.addCommand(new ReplaceTextCommand(doc2, 8, "CCC"));

        editor2.execute(formatMacro);       // 3 operations, 1 history entry
        System.out.println("  After macro: \"" + doc2.getContent() + "\"");
        System.out.println("  Undo stack depth: " + editor2.undoDepth() + " (just 1 entry for all 3 ops)");

        editor2.undo(); // undoes all 3 operations in reverse
        System.out.println("  After macro undo: \"" + doc2.getContent() + "\"");


        // ── Step 6: Audit log ─────────────────────────────────────────────────
        System.out.println("\n─── Audit Log (every action recorded) ──────────────────────────");
        editor.printAuditLog();


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Commands save state for undo BEFORE executing (see DeleteTextCommand)");
        System.out.println("  2. Invoker (EditorHistory) has zero knowledge of what commands do");
        System.out.println("  3. New execute() always clears redoStack — same as real editors");
        System.out.println("  4. Macro = composite command — N operations, 1 undo entry");
        System.out.println("  5. Audit logging is free — wrap the invoker without touching commands");
        System.out.println("  6. Beyond undo: commands can be serialized, queued, or scheduled");
    }
}

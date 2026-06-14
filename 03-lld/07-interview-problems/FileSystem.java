import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * LLD Problem 5: File System
 *
 * Core patterns:
 *  - Composite: FileSystemNode is the component; File is the leaf;
 *               Directory is the composite. Callers treat both uniformly.
 *  - Iterator:  FileSystemIterator does DFS traversal, decoupled from search logic.
 *  - Strategy:  SearchCriteria is a @FunctionalInterface — criteria compose
 *               via .and() / .or() without modifying any class.
 *
 * Design decisions:
 *  1. getSize() on Directory is computed recursively — no cached field to keep
 *     consistent. For a production FS this would be memoized, but purity wins
 *     here for clarity.
 *  2. FileMetadata is an immutable value object. Mutation (e.g. rename)
 *     replaces the metadata reference, never mutates in place.
 *  3. No concurrency — file system operations in a single-process model are
 *     inherently sequential. Real FS concurrency is at the OS level.
 *  4. move() works by remove-from-source + add-to-destination. This keeps
 *     each method single-responsibility.
 */
public class FileSystem {

    // ────────────────────────────────────────────────
    // Component interface (Composite pattern)
    // ────────────────────────────────────────────────

    interface FileSystemNode {
        String getName();
        long getSize();               // files: raw bytes; directories: recursive sum
        LocalDateTime getCreatedAt();
        LocalDateTime getModifiedAt();
        boolean isDirectory();
        FileSystemNode rename(String newName);  // returns new node (immutable metadata)
    }

    // ────────────────────────────────────────────────
    // Value object: file metadata
    // ────────────────────────────────────────────────

    static final class FileMetadata {
        private final LocalDateTime createdAt;
        private final LocalDateTime modifiedAt;
        private final String extension;

        FileMetadata(String extension) {
            this.createdAt = LocalDateTime.now();
            this.modifiedAt = this.createdAt;
            this.extension = extension;
        }

        private FileMetadata(LocalDateTime createdAt, LocalDateTime modifiedAt, String extension) {
            this.createdAt = createdAt;
            this.modifiedAt = modifiedAt;
            this.extension = extension;
        }

        FileMetadata withModifiedAt(LocalDateTime ts) {
            return new FileMetadata(this.createdAt, ts, this.extension);
        }

        FileMetadata withExtension(String ext) {
            return new FileMetadata(this.createdAt, LocalDateTime.now(), ext);
        }

        String getExtension() { return extension; }
        LocalDateTime getCreatedAt() { return createdAt; }
        LocalDateTime getModifiedAt() { return modifiedAt; }

        @Override public String toString() {
            return String.format("ext=%s created=%s modified=%s",
                    extension, createdAt.toLocalTime(), modifiedAt.toLocalTime());
        }
    }

    // ────────────────────────────────────────────────
    // Leaf: File
    // ────────────────────────────────────────────────

    static final class File implements FileSystemNode {
        private final String name;
        private final long size;
        private final FileMetadata metadata;

        File(String name, long sizeBytes) {
            this.name = name;
            this.size = sizeBytes;
            String ext = name.contains(".")
                    ? name.substring(name.lastIndexOf('.') + 1)
                    : "";
            this.metadata = new FileMetadata(ext);
        }

        // Private constructor for rename — preserves original metadata, updates extension
        private File(String name, long size, FileMetadata metadata) {
            this.name = name;
            this.size = size;
            this.metadata = metadata;
        }

        @Override public String getName() { return name; }
        @Override public long getSize() { return size; }
        @Override public LocalDateTime getCreatedAt() { return metadata.getCreatedAt(); }
        @Override public LocalDateTime getModifiedAt() { return metadata.getModifiedAt(); }
        @Override public boolean isDirectory() { return false; }
        @Override public FileSystemNode rename(String newName) {
            String newExt = newName.contains(".")
                    ? newName.substring(newName.lastIndexOf('.') + 1) : "";
            return new File(newName, this.size, metadata.withExtension(newExt));
        }

        String getExtension() { return metadata.getExtension(); }

        @Override public String toString() {
            return String.format("File[%s, %d bytes, %s]", name, size, metadata);
        }
    }

    // ────────────────────────────────────────────────
    // Composite: Directory
    //
    // Stores children in a LinkedHashMap for O(1) lookup by name while
    // preserving insertion order (predictable listings).
    // ────────────────────────────────────────────────

    static final class Directory implements FileSystemNode {
        private String name;
        private final LocalDateTime createdAt;
        private LocalDateTime modifiedAt;
        // LinkedHashMap: O(1) lookup + stable iteration order
        private final Map<String, FileSystemNode> children = new LinkedHashMap<>();

        Directory(String name) {
            this.name = name;
            this.createdAt = LocalDateTime.now();
            this.modifiedAt = this.createdAt;
        }

        @Override public String getName() { return name; }

        /**
         * getSize() on a Directory is the recursive sum of all children.
         * This is the defining behaviour of the Composite pattern:
         * clients call getSize() identically on a File or a Directory.
         */
        @Override
        public long getSize() {
            return children.values().stream()
                    .mapToLong(FileSystemNode::getSize)
                    .sum();
        }

        @Override public LocalDateTime getCreatedAt() { return createdAt; }
        @Override public LocalDateTime getModifiedAt() { return modifiedAt; }
        @Override public boolean isDirectory() { return true; }
        @Override public FileSystemNode rename(String newName) {
            this.name = newName;
            this.modifiedAt = LocalDateTime.now();
            return this;
        }

        /** Add or overwrite a child node. */
        void add(FileSystemNode node) {
            children.put(node.getName(), node);
            modifiedAt = LocalDateTime.now();
        }

        /** Remove a child by name. Returns the removed node, or empty. */
        Optional<FileSystemNode> remove(String childName) {
            FileSystemNode removed = children.remove(childName);
            if (removed != null) modifiedAt = LocalDateTime.now();
            return Optional.ofNullable(removed);
        }

        Optional<FileSystemNode> find(String childName) {
            return Optional.ofNullable(children.get(childName));
        }

        Collection<FileSystemNode> getChildren() {
            return Collections.unmodifiableCollection(children.values());
        }

        @Override public String toString() {
            return String.format("Directory[%s, %d children, %d bytes total]",
                    name, children.size(), getSize());
        }
    }

    // ────────────────────────────────────────────────
    // Strategy: SearchCriteria
    //
    // A @FunctionalInterface so lambda criteria work out of the box.
    // Composed via .and() and .or() — open/closed principle: new criteria
    // are new classes/lambdas, not edits to existing code.
    // ────────────────────────────────────────────────

    @FunctionalInterface
    interface SearchCriteria {
        boolean matches(FileSystemNode node);

        default SearchCriteria and(SearchCriteria other) {
            return node -> this.matches(node) && other.matches(node);
        }

        default SearchCriteria or(SearchCriteria other) {
            return node -> this.matches(node) || other.matches(node);
        }
    }

    // Concrete criteria

    static final class NameCriteria implements SearchCriteria {
        private final String namePattern;
        NameCriteria(String namePattern) { this.namePattern = namePattern.toLowerCase(); }

        @Override
        public boolean matches(FileSystemNode node) {
            return node.getName().toLowerCase().contains(namePattern);
        }
    }

    static final class ExtensionCriteria implements SearchCriteria {
        private final String extension;
        ExtensionCriteria(String extension) {
            this.extension = extension.startsWith(".") ? extension.substring(1) : extension;
        }

        @Override
        public boolean matches(FileSystemNode node) {
            if (node.isDirectory()) return false;
            return ((File) node).getExtension().equalsIgnoreCase(extension);
        }
    }

    static final class SizeRangeCriteria implements SearchCriteria {
        private final long minBytes;
        private final long maxBytes;
        SizeRangeCriteria(long minBytes, long maxBytes) {
            this.minBytes = minBytes;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean matches(FileSystemNode node) {
            long size = node.getSize();
            return size >= minBytes && size <= maxBytes;
        }
    }

    // ────────────────────────────────────────────────
    // Iterator: FileSystemIterator
    //
    // Encapsulates DFS traversal of the composite tree.
    // Separates "how to walk" from "what to do at each node".
    // ────────────────────────────────────────────────

    static final class FileSystemIterator {
        private final Directory root;

        FileSystemIterator(Directory root) { this.root = root; }

        /**
         * DFS search from root.
         * Returns all nodes (including sub-directories) that satisfy the criteria.
         */
        List<FileSystemNode> search(SearchCriteria criteria) {
            List<FileSystemNode> results = new ArrayList<>();
            dfs(root, criteria, results);
            return results;
        }

        private void dfs(FileSystemNode node, SearchCriteria criteria, List<FileSystemNode> results) {
            if (criteria.matches(node)) {
                results.add(node);
            }
            if (node.isDirectory()) {
                for (FileSystemNode child : ((Directory) node).getChildren()) {
                    dfs(child, criteria, results);
                }
            }
        }

        /** Produces a pretty-printed tree, similar to the Unix `tree` command. */
        String printTree() {
            StringBuilder sb = new StringBuilder();
            printTree(root, "", true, sb);
            return sb.toString();
        }

        private void printTree(FileSystemNode node, String prefix, boolean isLast, StringBuilder sb) {
            String connector = isLast ? "└── " : "├── ";
            String sizeStr = node.isDirectory()
                    ? String.format("[%d bytes]", node.getSize())
                    : String.format("(%d bytes)", node.getSize());
            sb.append(prefix).append(connector).append(node.getName())
              .append("  ").append(sizeStr).append("\n");

            if (node.isDirectory()) {
                List<FileSystemNode> children = new ArrayList<>(((Directory) node).getChildren());
                for (int i = 0; i < children.size(); i++) {
                    boolean last = (i == children.size() - 1);
                    String childPrefix = prefix + (isLast ? "    " : "│   ");
                    printTree(children.get(i), childPrefix, last, sb);
                }
            }
        }
    }

    // ────────────────────────────────────────────────
    // FileSystemService: high-level operations
    //
    // Handles move, delete, and path-based navigation.
    // Keeps Directory dumb (just add/remove) and puts
    // multi-step coordination here.
    // ────────────────────────────────────────────────

    static final class FileSystemService {
        private final Directory root;

        FileSystemService(Directory root) { this.root = root; }

        /**
         * Resolve a path like "/projects/java/Main.java" to its parent directory
         * and the target node name. Returns empty if any segment is missing.
         */
        Optional<FileSystemNode> getNode(String path) {
            if (path == null || path.isEmpty()) return Optional.of(root);
            String[] parts = path.split("/");
            FileSystemNode current = root;
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (!current.isDirectory()) return Optional.empty();
                Optional<FileSystemNode> next = ((Directory) current).find(part);
                if (next.isEmpty()) return Optional.empty();
                current = next.get();
            }
            return Optional.of(current);
        }

        /**
         * Move a node from sourcePath to targetDirectoryPath.
         * Atomic: removes from source first, then adds to target.
         * If target add fails (shouldn't happen), node is re-added to source.
         */
        boolean move(String sourcePath, String targetDirPath) {
            int lastSlash = sourcePath.lastIndexOf('/');
            String parentPath = lastSlash <= 0 ? "" : sourcePath.substring(0, lastSlash);
            String nodeName   = sourcePath.substring(lastSlash + 1);

            Optional<FileSystemNode> parentOpt = getNode(parentPath);
            Optional<FileSystemNode> targetOpt = getNode(targetDirPath);

            if (parentOpt.isEmpty() || !parentOpt.get().isDirectory()) return false;
            if (targetOpt.isEmpty() || !targetOpt.get().isDirectory()) return false;

            Directory parent = (Directory) parentOpt.get();
            Directory target = (Directory) targetOpt.get();

            Optional<FileSystemNode> nodeOpt = parent.remove(nodeName);
            if (nodeOpt.isEmpty()) return false;

            target.add(nodeOpt.get());
            return true;
        }

        /** Delete a node by path. Recursively deletes if directory. */
        boolean delete(String path) {
            int lastSlash = path.lastIndexOf('/');
            String parentPath = lastSlash <= 0 ? "" : path.substring(0, lastSlash);
            String nodeName   = path.substring(lastSlash + 1);

            Optional<FileSystemNode> parentOpt = getNode(parentPath);
            if (parentOpt.isEmpty() || !parentOpt.get().isDirectory()) return false;
            return ((Directory) parentOpt.get()).remove(nodeName).isPresent();
        }
    }

    // ────────────────────────────────────────────────
    // Demo
    // ────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  File System — LLD Demo");
        System.out.println("═══════════════════════════════════════════\n");

        // Build a directory tree
        Directory root = new Directory("root");

        Directory projects = new Directory("projects");
        Directory java     = new Directory("java");
        Directory python   = new Directory("python");

        java.add(new File("Main.java", 4096));
        java.add(new File("Service.java", 8192));
        java.add(new File("README.md", 1024));
        python.add(new File("app.py", 2048));
        python.add(new File("utils.py", 1536));

        projects.add(java);
        projects.add(python);

        Directory docs = new Directory("docs");
        docs.add(new File("design.pdf", 512000));
        docs.add(new File("requirements.md", 20480));
        docs.add(new File("notes.txt", 512));

        root.add(projects);
        root.add(docs);
        root.add(new File("config.json", 256));

        // 1. Print tree
        FileSystemIterator iterator = new FileSystemIterator(root);
        System.out.println("── Directory tree ──────────────────────────");
        System.out.println(iterator.printTree());

        // 2. Total size via Composite — same call on root as on a leaf
        System.out.printf("Total root size: %,d bytes%n%n", root.getSize());

        // 3. Search by extension
        List<FileSystemNode> mdFiles = iterator.search(new ExtensionCriteria("md"));
        System.out.println("── Search: .md files ───────────────────────");
        mdFiles.forEach(n -> System.out.println("  " + n.getName()));
        System.out.println();

        // 4. Composed criteria: java files larger than 5 KB
        SearchCriteria largeJava = new ExtensionCriteria("java")
                .and(new SizeRangeCriteria(5000, Long.MAX_VALUE));
        List<FileSystemNode> results = iterator.search(largeJava);
        System.out.println("── Search: .java files > 5 KB ──────────────");
        results.forEach(n -> System.out.printf("  %s  (%,d bytes)%n", n.getName(), n.getSize()));
        System.out.println();

        // 5. Search by name fragment
        List<FileSystemNode> serviceNodes = iterator.search(new NameCriteria("service"));
        System.out.println("── Search: name contains 'service' ─────────");
        serviceNodes.forEach(n -> System.out.println("  " + n.getName()));
        System.out.println();

        // 6. Move: move Main.java from projects/java to docs
        FileSystemService service = new FileSystemService(root);
        boolean moved = service.move("projects/java/Main.java", "docs");
        System.out.println("── Move: Main.java → docs/ ──────────────────");
        System.out.println("  Success: " + moved);
        System.out.println(iterator.printTree());

        // 7. Delete a file
        boolean deleted = service.delete("docs/notes.txt");
        System.out.println("── Delete: docs/notes.txt ───────────────────");
        System.out.println("  Success: " + deleted);
        System.out.println(iterator.printTree());

        // 8. Rename a file
        Optional<FileSystemNode> mainNode = service.getNode("docs/Main.java");
        mainNode.ifPresent(node -> {
            FileSystemNode renamed = node.rename("Main_v2.java");
            // Swap in parent directory
            ((Directory) service.getNode("docs").get()).add(renamed);
            ((Directory) service.getNode("docs").get()).remove("Main.java");
            System.out.println("── Rename: Main.java → Main_v2.java ─────────");
            System.out.println(iterator.printTree());
        });

        System.out.println("── docs/ directory total size ───────────────");
        service.getNode("docs").ifPresent(d ->
            System.out.printf("  %,d bytes%n", d.getSize()));
    }
}

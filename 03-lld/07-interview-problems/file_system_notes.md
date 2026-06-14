# File System — LLD Design Notes

## Problem Summary

Design a hierarchical file system with drives, folders, and files supporting
create, delete, move, rename, and multi-criteria search operations.

---

## Core Insight

A folder *is* a node that can contain other nodes — both files and folders.
This recursive self-similarity maps perfectly to the **Composite pattern**:
treat a single file and an entire directory tree through the same interface.

---

## Patterns Used

### 1. Composite (structural backbone)

| Role | Class |
|------|-------|
| Component | `FileSystemNode` interface |
| Leaf | `File` |
| Composite | `Directory` |

The defining win: `getSize()` called on a `Directory` recursively sums all
children's sizes — no `instanceof` checks, no caller awareness of depth.

```java
// Caller doesn't care whether 'node' is a File or a 500-node tree
long totalSize = node.getSize();
```

### 2. Iterator (tree traversal)

`FileSystemIterator` owns DFS traversal logic — separated from both the
Composite structure and the search criteria. The classic SRP application:
`Directory` knows its children; `FileSystemIterator` knows how to walk;
`SearchCriteria` knows what to match.

### 3. Strategy (search criteria)

`SearchCriteria` is a `@FunctionalInterface`. Criteria compose via `.and()`
and `.or()` default methods — open for extension, closed for modification:

```java
SearchCriteria largeJavaFiles = new ExtensionCriteria("java")
        .and(new SizeRangeCriteria(5_000, Long.MAX_VALUE));

List<FileSystemNode> results = iterator.search(largeJavaFiles);
```

Adding a `DateRangeCriteria` or `OwnerCriteria` requires only a new class —
zero changes to existing code.

---

## Key Design Decisions

### Children stored in `LinkedHashMap`

`Map<String, FileSystemNode>` gives O(1) lookup by name (for `find`, `remove`)
while `LinkedHashMap` preserves insertion order for stable directory listings.
Alternative — `List` — would degrade `find` to O(n).

### `FileMetadata` is an immutable value object

Rename creates a new `FileMetadata` with an updated extension. Mutation never
happens in-place. This eliminates a whole class of aliasing bugs — if two
references point to the same metadata, neither can corrupt the other.

### `getSize()` on `Directory` is computed on-demand

No cached `totalSize` field on `Directory`. This is deliberate: for an
interview/learning implementation, purity wins over performance. In production,
this would be memoized with invalidation on `add`/`remove`.

### `rename()` returns the node, not void

`File.rename()` returns a **new** `File` with the updated name and extension —
the caller is responsible for replacing the reference in the parent. This
avoids mutation side-effects and makes the API explicit about what changed.

### `FileSystemService` as a coordinator

Multi-step operations (`move`, `delete`) live in `FileSystemService`, not in
`Directory`. Keeps `Directory` focused on a single responsibility (managing
its own children) and puts coordination logic in one discoverable place.

---

## Class Summary

```
FileSystemNode (interface)
├── File (leaf)
│     └── FileMetadata (value object, immutable)
└── Directory (composite)
      └── Map<String, FileSystemNode> children

SearchCriteria (strategy interface, @FunctionalInterface)
├── NameCriteria
├── ExtensionCriteria
└── SizeRangeCriteria

FileSystemIterator   — DFS traversal + tree printer
FileSystemService    — move, delete, path resolution
```

---

## Complexity

| Operation | Time | Notes |
|-----------|------|-------|
| `find(name)` in Directory | O(1) | LinkedHashMap lookup |
| `add` / `remove` | O(1) | HashMap put/remove |
| `getSize()` on Directory | O(n) | n = total node count in subtree |
| `search()` | O(n) | DFS over entire tree |
| `printTree()` | O(n) | DFS |
| `move()` | O(depth) | path resolution is O(depth) |

---

## Concurrency Design

None applied here. File system operations in a single-process design are
sequential. Real OS-level file system concurrency (inode locks, page cache
locks) is OS internals — outside LLD scope.

If multi-threaded access were required: per-directory `ReentrantReadWriteLock`
(same pattern as Parking Lot's per-floor locks) — read lock for listing/search,
write lock for add/remove.

---

## Extension Possibilities

- **Soft links / symlinks**: new `Symlink` leaf implementing `FileSystemNode`,
  delegates all calls to its target.
- **Permissions**: add `Permission` field to `FileMetadata`; `FileSystemNode`
  gets `canRead(User)`, `canWrite(User)`.
- **Watch service**: Observer pattern — `Directory` notifies registered
  `FileChangeListener` instances on add/remove/rename.
- **Size caching**: `Directory` caches `totalSize` and invalidates on
  structural changes. Consistent with the existing immutable-metadata design.
- **Sorting**: `FileSystemIterator.listSorted(Comparator<FileSystemNode>)` —
  trivially composable since children are already in a collection.

---

## Phase Connections

| Concept | Where learned | Where applied |
|---------|--------------|---------------|
| Composite pattern | Phase 3 (GoF Structural) | FileSystemNode / File / Directory |
| Iterator pattern | Phase 3 (GoF Behavioral) | FileSystemIterator |
| Strategy pattern | Phase 3 (GoF Behavioral) | SearchCriteria + criteria classes |
| `@FunctionalInterface` | Phase 1 (OOP) | SearchCriteria |
| Value object (immutable) | Phase 2 (Object Modeling) | FileMetadata |
| SRP / OCP | Phase 1 (SOLID) | Each class has one job; new criteria need no edits |
| `LinkedHashMap` | Phase 4 (Java Concurrency / Collections) | Directory.children |

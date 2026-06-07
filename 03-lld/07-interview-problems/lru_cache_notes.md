# LRU Cache — LLD Notes

## 1. Problem Intuition

An LRU (Least Recently Used) cache sits between a fast tier (memory) and a slow tier (database, disk, network). When the cache is full and a new item must be stored, we evict the entry that was accessed *least recently* — the intuition being that if you haven't needed it in a while, you're less likely to need it soon.

The interesting design challenge isn't the eviction *policy* — it's achieving O(1) time complexity for every operation: `get`, `put`, and eviction. A naive implementation fails on at least one of these.

### Why O(1) is hard

- `HashMap` alone gives O(1) lookup but has no notion of access order.
- `LinkedList` alone maintains order but requires O(n) search to find a specific item.
- The classical solution: combine both. The `HashMap` stores keys mapped to *direct node references* in a `DoublyLinkedList`. The list maintains order (MRU at head, LRU at tail). Because the map gives you the node directly, you can splice it out and move it to the head in O(1) — no searching needed.

This combination is so canonical it's the basis of Java's `LinkedHashMap`.

---

## 2. Requirements

### Functional (in scope)
- Generic `Cache<K, V>` with configurable capacity
- `get(key)` — returns value if present, `Optional.empty()` if not; marks entry as most recently used
- `put(key, value)` — inserts or updates; if at capacity, evicts LRU entry first
- Thread-safe under concurrent reads and writes
- Eviction policy pluggable via Strategy: `LRUEvictionPolicy` implemented, `LFUEvictionPolicy` as stub extension

### Out of scope
- TTL-based expiry (time-to-live)
- Cache statistics / hit-rate metrics API (though they're noted as an extension)
- Distributed / multi-node cache
- Persistence

---

## 3. Identifying Entities

| Entity | Responsibility |
|---|---|
| `Cache<K,V>` | Public interface: `get`, `put`, `size`, `contains` |
| `LRUCache<K,V>` | Implements `Cache`; owns the `HashMap`, the `EvictionPolicy`, and the `ReadWriteLock` |
| `EvictionPolicy<K>` | Strategy interface: `onAccess(key)`, `onInsert(key)`, `evict()` → K |
| `LRUEvictionPolicy<K>` | Maintains access order via `DoublyLinkedList` + internal `nodeMap` |
| `DoublyLinkedList<K,V>` | Doubly-linked list with sentinel head/tail; O(1) addFirst, remove, getLast |
| `Node<K,V>` | Single list node: key, value, prev, next |
| `LFUEvictionPolicy<K>` | Stub — demonstrates extensibility; not fully implemented |

---

## 4. Design Decisions

### Strategy pattern for eviction policy
`EvictionPolicy` is the volatile axis: LRU, LFU, MRU, Random are all legitimate choices depending on access patterns. By extracting this interface, `LRUCache` becomes a generic `Cache` — it knows how to manage a `HashMap` and a lock, but delegates the "who gets evicted?" decision entirely to the policy. Adding LFU means writing only `LFUEvictionPolicy`; the cache itself doesn't change.

### `ReentrantReadWriteLock` for concurrency
A production cache is overwhelmingly read-heavy. `synchronized` on every method serializes all readers through a single lock — unnecessary contention for read operations that don't mutate state.

`ReentrantReadWriteLock` allows:
- Multiple concurrent readers (no writes in progress)
- Exclusive writer access for `put` and eviction

**Subtlety with `get`:** Even a cache *hit* mutates state — the accessed node must move to the MRU position. So `get` cannot safely use a read lock on a hit. Our implementation holds the write lock for any `get` that produces a hit. For a miss, a read lock suffices (nothing is mutated). We check existence under a read lock, then re-acquire a write lock only if the key exists.

**Why not upgrade from read to write lock?** Java's `ReentrantReadWriteLock` does not support direct lock upgrade. Releasing the read lock and re-acquiring the write lock introduces a TOCTOU window where another thread could evict the entry between the two acquisitions. The safest and simplest approach: hold the write lock for all `get` operations. This is slightly less concurrent but completely correct.

### Sentinel nodes in `DoublyLinkedList`
Sentinel `head` and `tail` nodes (holding no data) are always present. They make every operation uniform — no null checks for "is the list empty?" or "is this the first/last node?":
- `addFirst(node)`: always insert between `head` and `head.next`
- `removeLast()`: always remove `tail.prev`
- `remove(node)`: always `node.prev.next = node.next; node.next.prev = node.prev`

### `EvictionPolicy` holds its own `nodeMap`
`LRUEvictionPolicy` maintains its own `HashMap<K, Node>` mapping keys to their positions in the doubly-linked list. This is *separate* from `LRUCache`'s `HashMap<K, V>` (which maps keys to values). Two maps, two responsibilities:
- Cache map: "does this key exist, and what is its value?"
- Policy map: "where in the ordering does this key sit?"

This separation means the policy is self-contained and independently testable.

---

## 5. Class Diagram (text UML)

```
Cache<K,V> (interface)
└── get(key): Optional<V>
└── put(key, value): void
└── size(): int
└── contains(key): boolean

LRUCache<K,V> implements Cache<K,V>
├── capacity: int
├── store: HashMap<K, V>               ← value lookup
├── policy: EvictionPolicy<K>          ← injected strategy
└── lock: ReentrantReadWriteLock       ← read lock for miss, write lock for hit/put

EvictionPolicy<K> (interface)
├── onAccess(key): void                ← called on cache hit
├── onInsert(key): void                ← called on new entry insertion
└── evict(): K                         ← returns key to evict; called when full

LRUEvictionPolicy<K> implements EvictionPolicy<K>
├── list: DoublyLinkedList<K, Void>    ← maintains order
└── nodeMap: HashMap<K, Node>          ← O(1) access to any node by key

DoublyLinkedList<K, V>
├── head: Node  (sentinel, no data)
├── tail: Node  (sentinel, no data)
├── addFirst(node): void
├── remove(node): void
└── removeLast(): Node

Node<K, V>
├── key: K
├── value: V
├── prev: Node
└── next: Node
```

---

## 6. Complexity Analysis

| Operation | Time | Space |
|---|---|---|
| `get` (miss) | O(1) | — |
| `get` (hit) | O(1) — map lookup + doubly-linked splice | — |
| `put` (new key, not full) | O(1) | O(1) per entry |
| `put` (new key, full) | O(1) — tail eviction + insert | O(1) |
| `put` (existing key) | O(1) | — |
| Total space | — | O(capacity) |

---

## 7. Pattern Usage Summary

| Pattern | Where applied | Why |
|---|---|---|
| **Strategy** | `EvictionPolicy` + `LRUEvictionPolicy` | Eviction logic is volatile; cache plumbing is stable |
| **Composition** | `LRUCache` uses `EvictionPolicy` (not inherits) | Policy is a pluggable behavior, not a type hierarchy |
| **Iterator** (light) | `DoublyLinkedList` traversal in `toString` | Natural linked-list traversal encapsulated in the list class |

---

## 8. Concurrency Summary

| Operation | Lock acquired | Why |
|---|---|---|
| `get` (miss) | Read lock | No state mutation |
| `get` (hit) | Write lock | Must move node to MRU position — mutates list |
| `put` (any) | Write lock | Inserts node, possibly evicts |
| `size`, `contains` | Read lock | Read-only |

---

## 9. Possible Extensions

| Extension | Where to change |
|---|---|
| LFU eviction | New `LFUEvictionPolicy` implements `EvictionPolicy`; inject into `LRUCache` |
| TTL expiry | Add `expiryMs` to `Node`; in `get`, check expiry before returning |
| Hit-rate metrics | Wrap `LRUCache` in a `MeteredCache` decorator; count hits/misses |
| Max-size by bytes | Replace `capacity` (count) with `maxBytes`; `Node` tracks serialized size |
| Write-through | After `put`, asynchronously write to backing store (DB) |
| `LinkedHashMap` shortcut | Java's `LinkedHashMap(cap, 0.75f, true)` is a single-threaded LRU; use it when no concurrency or custom policy is needed |

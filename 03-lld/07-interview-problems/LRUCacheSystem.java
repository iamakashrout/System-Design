import java.util.*;
import java.util.concurrent.locks.*;

/**
 * LRU Cache — Phase 5, Problem 4
 *
 * Demonstrates: Strategy (EvictionPolicy), Composition over inheritance,
 *               ReadWriteLock for read-heavy concurrency, sentinel-node
 *               DoublyLinkedList for O(1) all operations, Generic design.
 *
 * Core data structure insight:
 *   HashMap  → O(1) key-to-node lookup
 *   DoublyLinkedList → O(1) node removal and re-insertion (MRU promotion)
 *   Together → O(1) get, put, and eviction with maintained access order
 */
public class LRUCacheSystem {

    // =========================================================================
    // NODE — the building block of the doubly-linked list
    // =========================================================================

    /**
     * Node holds a key-value pair plus doubly-linked list pointers.
     *
     * Why store the key in the node?
     * When we evict the tail node (the LRU), we need to remove it from the
     * cache's HashMap too. Without the key in the node, we'd have no way to
     * do that in O(1). The key stored in the node is the bridge back to the map.
     */
    static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        // Sentinel constructor (head/tail — no key or value)
        Node() { }
    }

    // =========================================================================
    // DOUBLY-LINKED LIST — with sentinel head and tail
    // =========================================================================

    /**
     * DoublyLinkedList with permanent sentinel head and tail nodes.
     *
     * Why sentinels?
     * Without them, every operation needs null checks:
     *   - Is the list empty? (head == null)
     *   - Is this the only node? (node.prev == null && node.next == null)
     *   - Is this the head? Is this the tail?
     *
     * With sentinels, every operation is unconditionally uniform:
     *   - head.next is ALWAYS the MRU node (or tail if empty)
     *   - tail.prev is ALWAYS the LRU node (or head if empty)
     *   - addFirst: always insert between head and head.next
     *   - removeLast: always remove tail.prev
     *   - remove(node): always bridge node.prev to node.next
     *
     * This isn't a Java doubly-linked list curiosity — this same pattern
     * appears in the Linux kernel's list_head implementation.
     */
    static class DoublyLinkedList<K, V> {
        private final Node<K, V> head;  // sentinel: never holds data
        private final Node<K, V> tail;  // sentinel: never holds data
        private int size;

        DoublyLinkedList() {
            head = new Node<>();
            tail = new Node<>();
            head.next = tail;
            tail.prev = head;
            size = 0;
        }

        /**
         * Insert a node immediately after head (MRU position).
         * Before: head ↔ [rest...]
         * After:  head ↔ node ↔ [rest...]
         */
        void addFirst(Node<K, V> node) {
            node.next = head.next;
            node.prev = head;
            head.next.prev = node;
            head.next = node;
            size++;
        }

        /**
         * Remove a node from wherever it currently sits in the list.
         * We have direct prev/next pointers — no search needed. O(1).
         * Before: [prev] ↔ node ↔ [next]
         * After:  [prev] ↔ [next]
         */
        void remove(Node<K, V> node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
            // Null out pointers to help GC and catch dangling-pointer bugs early
            node.prev = null;
            node.next = null;
            size--;
        }

        /**
         * Remove and return the LRU node (the one just before tail).
         * Returns null if list is empty (head.next == tail).
         */
        Node<K, V> removeLast() {
            if (head.next == tail) return null;  // empty list
            Node<K, V> lru = tail.prev;
            remove(lru);
            return lru;
        }

        /**
         * Convenience: move an existing node to the MRU position.
         * This is the "promote on access" operation in LRU.
         */
        void moveToFront(Node<K, V> node) {
            remove(node);
            addFirst(node);
        }

        int size()    { return size; }
        boolean isEmpty() { return size == 0; }

        /** Debug: print list from MRU to LRU, excluding sentinels. */
        String toOrderedString() {
            StringBuilder sb = new StringBuilder("[");
            Node<K, V> curr = head.next;
            while (curr != tail) {
                // Show value only if non-null (policy nodes store null values)
                if (curr.value != null) {
                    sb.append(curr.key).append("=").append(curr.value);
                } else {
                    sb.append(curr.key);
                }
                if (curr.next != tail) sb.append(", ");
                curr = curr.next;
            }
            return sb.append("] (MRU→LRU)").toString();
        }
    }

    // =========================================================================
    // EVICTION POLICY — Strategy interface
    // =========================================================================

    /**
     * EvictionPolicy is the Strategy interface for all eviction algorithms.
     *
     * Three events the cache reports to the policy:
     * - onAccess(key): a key was accessed (cache hit) — update ordering
     * - onInsert(key): a new key was inserted — track it
     * - onRemove(key): a key was explicitly removed — clean up policy state
     * - evict(): the cache is full — return the key that should be evicted
     *
     * The policy knows ordering; the cache knows values.
     * Neither needs to know the other's internals.
     */
    interface EvictionPolicy<K> {
        void onAccess(K key);
        void onInsert(K key);
        void onRemove(K key);
        K evict();
    }

    // -------------------------------------------------------------------------
    // LRU Eviction Policy
    // -------------------------------------------------------------------------

    /**
     * LRUEvictionPolicy: evicts the key accessed least recently.
     *
     * Internal structure: DoublyLinkedList (access order) + HashMap (key → node).
     * The HashMap here maps keys to their Node positions in the list,
     * giving O(1) access to any node for promotion.
     *
     * This is separate from LRUCache's own map (which stores values).
     * Two maps, two responsibilities:
     *   - Cache map:  key → value
     *   - Policy map: key → node (position in ordering)
     *
     * NOT thread-safe — LRUCache holds the lock before calling any method here.
     */
    static class LRUEvictionPolicy<K> implements EvictionPolicy<K> {
        // We only care about ordering (keys), not values, so Node<K, Void>
        private final DoublyLinkedList<K, Void> list;
        private final HashMap<K, Node<K, Void>> nodeMap;

        LRUEvictionPolicy() {
            this.list = new DoublyLinkedList<>();
            this.nodeMap = new HashMap<>();
        }

        @Override
        public void onAccess(K key) {
            // Cache hit: move this key to the MRU (front) position
            Node<K, Void> node = nodeMap.get(key);
            if (node != null) {
                list.moveToFront(node);
            }
        }

        @Override
        public void onInsert(K key) {
            // New key: create a node and place it at the MRU position
            Node<K, Void> node = new Node<>(key, null);
            list.addFirst(node);
            nodeMap.put(key, node);
        }

        @Override
        public void onRemove(K key) {
            Node<K, Void> node = nodeMap.remove(key);
            if (node != null) list.remove(node);
        }

        @Override
        public K evict() {
            // The LRU key is at the tail of the list
            Node<K, Void> lru = list.removeLast();
            if (lru == null) throw new IllegalStateException("Cannot evict from empty policy");
            nodeMap.remove(lru.key);
            return lru.key;
        }

        String getOrderString() { return list.toOrderedString(); }
    }

    // -------------------------------------------------------------------------
    // LFU Eviction Policy — stub demonstrating extensibility
    // -------------------------------------------------------------------------

    /**
     * LFUEvictionPolicy stub: evicts the key accessed *least frequently*.
     * Among ties in frequency, evicts the least recently used among them.
     *
     * This is intentionally not fully implemented — its purpose here is to
     * demonstrate that adding a new eviction strategy requires:
     *   1. A new class implementing EvictionPolicy<K>
     *   2. Nothing else — LRUCache doesn't change at all
     *
     * Full LFU implementation uses:
     *   - freqMap: HashMap<K, Integer>          (key → frequency)
     *   - buckets: HashMap<Integer, LinkedHashSet<K>> (freq → keys at that freq, insertion ordered)
     *   - minFreq: int                           (current minimum frequency)
     */
    static class LFUEvictionPolicy<K> implements EvictionPolicy<K> {
        private final Map<K, Integer> freqMap = new HashMap<>();
        private final Map<Integer, LinkedHashSet<K>> buckets = new HashMap<>();
        private int minFreq = 0;

        @Override
        public void onAccess(K key) {
            int freq = freqMap.getOrDefault(key, 0);
            freqMap.put(key, freq + 1);
            if (freq > 0) {
                buckets.getOrDefault(freq, new LinkedHashSet<>()).remove(key);
            }
            buckets.computeIfAbsent(freq + 1, k -> new LinkedHashSet<>()).add(key);
            // Update minFreq only if the bucket that just lost a key is now empty
            if (freq == minFreq && buckets.getOrDefault(freq, new LinkedHashSet<>()).isEmpty()) {
                minFreq = freq + 1;
            }
        }

        @Override
        public void onInsert(K key) {
            freqMap.put(key, 1);
            buckets.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
            minFreq = 1;
        }

        @Override
        public void onRemove(K key) {
            int freq = freqMap.remove(key);
            buckets.getOrDefault(freq, new LinkedHashSet<>()).remove(key);
        }

        @Override
        public K evict() {
            LinkedHashSet<K> minBucket = buckets.get(minFreq);
            if (minBucket == null || minBucket.isEmpty())
                throw new IllegalStateException("Cannot evict from empty LFU policy");
            K evicted = minBucket.iterator().next();  // LRU among min-freq (insertion order)
            minBucket.remove(evicted);
            freqMap.remove(evicted);
            return evicted;
        }
    }

    // =========================================================================
    // CACHE INTERFACE
    // =========================================================================

    interface Cache<K, V> {
        Optional<V> get(K key);
        void put(K key, V value);
        boolean contains(K key);
        int size();
        int capacity();
    }

    // =========================================================================
    // LRU CACHE — the main implementation
    // =========================================================================

    /**
     * LRUCache: generic, thread-safe cache with pluggable eviction policy.
     *
     * Concurrency design — ReentrantReadWriteLock:
     *
     * Read lock: acquired for operations that don't mutate state.
     *   - get() that results in a MISS (no state changes)
     *   - contains(), size(), capacity()
     *
     * Write lock: acquired for all mutations.
     *   - get() that results in a HIT (must promote node to MRU — mutates policy state)
     *   - put() — inserts/updates value, possibly evicts
     *
     * Why not use read lock for all gets and upgrade for hits?
     * Java's ReentrantReadWriteLock doesn't support direct lock upgrade.
     * Releasing read + re-acquiring write creates a TOCTOU window where
     * another thread could evict the entry between the two acquisitions.
     * The simplest correct approach: write lock for all get-and-hit paths.
     *
     * In practice: if your workload is truly read-heavy and hit rate is high,
     * consider a two-stage approach or a concurrent skiplist-based policy.
     * For most use cases, write-locked gets are fast enough.
     */
    static class LRUCache<K, V> implements Cache<K, V> {
        private final int capacity;
        private final Map<K, V> store;
        private final EvictionPolicy<K> policy;
        private final ReentrantReadWriteLock lock;
        private final Lock readLock;
        private final Lock writeLock;

        // Stats (read under any lock, updated under write lock)
        private long hits;
        private long misses;

        LRUCache(int capacity, EvictionPolicy<K> policy) {
            if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
            this.capacity = capacity;
            this.store = new HashMap<>(capacity * 2);  // pre-size to avoid rehashing
            this.policy = policy;
            this.lock = new ReentrantReadWriteLock();
            this.readLock  = lock.readLock();
            this.writeLock = lock.writeLock();
        }

        /**
         * Get a value by key.
         *
         * On miss: read lock sufficient (no state change).
         * On hit:  write lock needed (must promote node to MRU in policy).
         *
         * We take the write lock upfront since we don't know if it's a hit
         * until we've looked up the key. Alternative: read-then-upgrade,
         * but as noted above, that introduces a TOCTOU window in Java.
         */
        @Override
        public Optional<V> get(K key) {
            writeLock.lock();
            try {
                V value = store.get(key);
                if (value == null) {
                    misses++;
                    return Optional.empty();
                }
                // Cache hit: notify policy to update access order
                policy.onAccess(key);
                hits++;
                return Optional.of(value);
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Insert or update a key-value pair.
         *
         * If key exists: update value, promote to MRU.
         * If key is new and cache is full: evict LRU entry, then insert.
         * If key is new and cache has space: insert directly.
         *
         * Always takes write lock — always mutates state.
         */
        @Override
        public void put(K key, V value) {
            writeLock.lock();
            try {
                if (store.containsKey(key)) {
                    // Update existing: just change value and mark as accessed
                    store.put(key, value);
                    policy.onAccess(key);
                    return;
                }

                // New key: evict if at capacity
                if (store.size() >= capacity) {
                    K evictedKey = policy.evict();
                    store.remove(evictedKey);
                    System.out.printf("  [Evict] '%s' evicted (LRU)%n", evictedKey);
                }

                // Insert the new entry
                store.put(key, value);
                policy.onInsert(key);
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public boolean contains(K key) {
            readLock.lock();
            try { return store.containsKey(key); }
            finally { readLock.unlock(); }
        }

        @Override
        public int size() {
            readLock.lock();
            try { return store.size(); }
            finally { readLock.unlock(); }
        }

        @Override
        public int capacity() { return capacity; }

        public double hitRate() {
            readLock.lock();
            try {
                long total = hits + misses;
                return total == 0 ? 0.0 : (double) hits / total * 100;
            } finally { readLock.unlock(); }
        }

        public void printState(String label) {
            writeLock.lock();
            try {
                System.out.printf("[%s] size=%d/%d | %s%n",
                    label, store.size(), capacity,
                    policy instanceof LRUEvictionPolicy
                        ? ((LRUEvictionPolicy<?>) policy).getOrderString()
                        : store.keySet().toString());
            } finally { writeLock.unlock(); }
        }
    }

    // =========================================================================
    // DEMO DRIVER
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== LRU Cache Demo ===\n");

        // -----------------------------------------------------------------------
        // Scenario 1: Basic LRU eviction behavior
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 1: Basic LRU ordering and eviction ===");
        LRUCache<String, Integer> cache = new LRUCache<>(3, new LRUEvictionPolicy<>());

        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.printState("After A,B,C put");

        // Access A — makes A the MRU, so B becomes the LRU
        System.out.println("  get(A) = " + cache.get("A").orElse(-1));
        cache.printState("After get(A)");

        // Put D — cache is full, must evict LRU which is now B
        cache.put("D", 4);
        cache.printState("After put(D) — B should be evicted");

        System.out.println("  contains(B) = " + cache.contains("B") + " (expected false)");
        System.out.println("  contains(A) = " + cache.contains("A") + " (expected true)");
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 2: Update existing key — should not change capacity count
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 2: Update existing key ===");
        cache.printState("Before update");
        cache.put("A", 100);
        cache.printState("After put(A, 100) — A updated, still MRU");
        System.out.println("  get(A) = " + cache.get("A").orElse(-1) + " (expected 100)");
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 3: Cache miss
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 3: Cache miss ===");
        Optional<Integer> miss = cache.get("Z");
        System.out.println("  get(Z) = " + miss + " (expected empty)");
        System.out.printf("  Hit rate: %.1f%%%n", cache.hitRate());
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 4: Capacity = 1 edge case
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 4: Capacity = 1 edge case ===");
        LRUCache<Integer, String> tiny = new LRUCache<>(1, new LRUEvictionPolicy<>());
        tiny.put(1, "one");
        tiny.printState("After put(1)");
        tiny.put(2, "two");
        tiny.printState("After put(2) — 1 evicted");
        System.out.println("  get(1) = " + tiny.get(1) + " (expected empty)");
        System.out.println("  get(2) = " + tiny.get(2).orElse("") + " (expected 'two')");
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 5: LFU policy — demonstrates Strategy extensibility
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 5: LFU policy (same LRUCache, different policy) ===");
        LRUCache<String, Integer> lfuCache = new LRUCache<>(3, new LFUEvictionPolicy<>());
        lfuCache.put("X", 10);
        lfuCache.put("Y", 20);
        lfuCache.put("Z", 30);

        // Access X twice, Y once, Z not at all
        lfuCache.get("X");
        lfuCache.get("X");
        lfuCache.get("Y");

        // Now put a new key — cache is full, Z should be evicted (freq=0, or 1 for Y, 2 for X)
        // Z has lowest frequency (0 accesses beyond initial insert)
        lfuCache.put("W", 40);
        System.out.println("  contains(Z) = " + lfuCache.contains("Z") + " (expected false — LFU evicted Z)");
        System.out.println("  contains(X) = " + lfuCache.contains("X") + " (expected true — X accessed twice)");
        System.out.println();

        // -----------------------------------------------------------------------
        // Scenario 6: Concurrent access — thread safety
        // -----------------------------------------------------------------------
        System.out.println("=== Scenario 6: Concurrent read/write (20 threads) ===");
        LRUCache<Integer, String> concurrent = new LRUCache<>(5, new LRUEvictionPolicy<>());

        // Pre-fill
        for (int i = 0; i < 5; i++) concurrent.put(i, "val-" + i);

        Thread[] threads = new Thread[20];
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                if (idx % 2 == 0) {
                    // Readers
                    concurrent.get(idx % 5);
                } else {
                    // Writers
                    concurrent.put(idx % 10, "thread-" + idx);
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println("  Concurrent ops complete. Final state:");
        concurrent.printState("Concurrent cache");
        System.out.printf("  Size within capacity: %b%n", concurrent.size() <= 5);
        System.out.printf("  Hit rate: %.1f%%%n", concurrent.hitRate());

        System.out.println("\n=== Demo complete ===");
    }
}

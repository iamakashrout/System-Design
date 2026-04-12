import java.util.HashMap;
import java.util.Map;

// =============================================================================
// PATTERN: Singleton
// PURPOSE: Ensure a class has exactly ONE instance, and provide a global point
//          of access to it.
//
// REAL-WORLD ANALOGY:
//   Think of the President of a country. There is exactly one at any time.
//   Everyone who asks "who is the president?" gets a reference to the SAME
//   person — not a brand new person each time.
//
// THE THREE INGREDIENTS:
//   1. Private constructor   → nobody outside can call `new`
//   2. Static instance field → the single object lives at the class level
//   3. Static accessor       → the ONE way to retrieve the instance
// =============================================================================

public class SingletonPattern {

    // =========================================================================
    // VERSION 1: NAIVE — broken under multithreading
    // Shown here to understand WHY we need better versions.
    // =========================================================================
    static class NaiveConfigManager {
        private static NaiveConfigManager instance; // not volatile — visibility problem
        private final Map<String, String> config;

        private NaiveConfigManager() {
            config = new HashMap<>();
            config.put("db.host", "localhost");
            config.put("db.port", "5432");
            System.out.println("  [NaiveConfigManager] Config loaded");
        }

        // PROBLEM: Two threads can both see `instance == null` at the same time
        // and both enter the if-block, creating TWO instances.
        // This is called a "race condition".
        public static NaiveConfigManager getInstance() {
            if (instance == null) {
                instance = new NaiveConfigManager(); // ← race condition here
            }
            return instance;
        }

        public String get(String key) {
            return config.getOrDefault(key, "not found");
        }
    }


    // =========================================================================
    // VERSION 2: SYNCHRONIZED METHOD — thread-safe but slow
    // =========================================================================
    static class SynchronizedConfigManager {
        private static SynchronizedConfigManager instance;
        private final Map<String, String> config;

        private SynchronizedConfigManager() {
            config = new HashMap<>();
            config.put("db.host", "localhost");
            System.out.println("  [SynchronizedConfigManager] Config loaded");
        }

        // PROBLEM: `synchronized` forces every thread to acquire a lock on every call.
        // After the instance is created, the lock is unnecessary — but every call
        // still pays the cost. getInstance() is called very frequently → bottleneck.
        public static synchronized SynchronizedConfigManager getInstance() {
            if (instance == null) {
                instance = new SynchronizedConfigManager();
            }
            return instance;
        }

        public String get(String key) {
            return config.getOrDefault(key, "not found");
        }
    }


    // =========================================================================
    // VERSION 3: DOUBLE-CHECKED LOCKING — correct and efficient
    //
    // The idea: only synchronize during the initial creation. Once the instance
    // exists, all subsequent calls take the fast path with no locking.
    // =========================================================================
    static class DoubleCheckedConfigManager {

        // WHY volatile?
        // Without `volatile`, the JVM can reorder CPU instructions for performance.
        // A thread might see instance != null but still read a partially-constructed
        // object — an object where fields are not yet written. `volatile` prevents
        // this by ensuring writes are visible to all threads immediately.
        private static volatile DoubleCheckedConfigManager instance;
        private final Map<String, String> config;

        private DoubleCheckedConfigManager() {
            config = new HashMap<>();
            config.put("db.host", "localhost");
            config.put("db.port", "5432");
            config.put("max.connections", "100");
            System.out.println("  [DoubleCheckedConfigManager] Config loaded");
        }

        public static DoubleCheckedConfigManager getInstance() {
            // FIRST CHECK (no lock): Fast path for the 99.9% of calls after instance exists.
            // Avoids acquiring the lock every time.
            if (instance == null) {

                // LOCK: Only one thread can be inside this block at a time.
                synchronized (DoubleCheckedConfigManager.class) {

                    // SECOND CHECK (inside lock): Handles the race condition.
                    // Two threads may have both passed the first check. The second
                    // thread to get the lock must check again — instance is already created.
                    if (instance == null) {
                        instance = new DoubleCheckedConfigManager();
                    }
                }
            }
            return instance;
        }

        public String get(String key) { return config.getOrDefault(key, "not found"); }
        public void set(String key, String value) { config.put(key, value); }
    }


    // =========================================================================
    // VERSION 4: INITIALIZATION-ON-DEMAND HOLDER — the cleanest approach ✅
    //
    // This is the recommended Singleton idiom in Java.
    //
    // HOW IT WORKS:
    //   The JVM only loads a class when it is first used. The `Holder` inner
    //   class is not loaded until `getInstance()` is called for the first time.
    //   At that point, the JVM loads `Holder` and initializes `INSTANCE` exactly
    //   once. The JVM guarantees class loading is thread-safe — so no `volatile`,
    //   no `synchronized` — zero manual concurrency management needed.
    // =========================================================================
    static class ConfigurationManager {
        private final Map<String, String> config;

        private ConfigurationManager() {
            config = new HashMap<>();
            config.put("db.host", "localhost");
            config.put("db.port", "5432");
            config.put("max.connections", "100");
            config.put("app.env", "production");
            System.out.println("  [ConfigurationManager] Config loaded from source (happens exactly once)");
        }

        // The Holder class is only loaded when getInstance() is first called.
        // INSTANCE is initialized at that point — thread-safely by the JVM.
        private static class Holder {
            private static final ConfigurationManager INSTANCE = new ConfigurationManager();
        }

        public static ConfigurationManager getInstance() {
            return Holder.INSTANCE; // JVM guarantees this is initialized once and safely
        }

        public String get(String key) { return config.getOrDefault(key, "not found"); }
        public void set(String key, String value) { config.put(key, value); }

        @Override
        public String toString() {
            return "ConfigurationManager" + config;
        }
    }


    // =========================================================================
    // MAIN — demonstrates Singleton behavior
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Singleton Pattern Demo ===\n");

        // ----- Demonstrating core Singleton guarantee -----
        System.out.println("--- Core Guarantee: Same Instance ---");

        ConfigurationManager ref1 = ConfigurationManager.getInstance();
        ConfigurationManager ref2 = ConfigurationManager.getInstance();
        ConfigurationManager ref3 = ConfigurationManager.getInstance();

        // All three references point to the exact same object in memory.
        // The constructor was only called once (you'll see the print above once).
        System.out.println("  ref1 == ref2: " + (ref1 == ref2)); // true
        System.out.println("  ref2 == ref3: " + (ref2 == ref3)); // true
        System.out.println("  ref1 hash: " + System.identityHashCode(ref1));
        System.out.println("  ref2 hash: " + System.identityHashCode(ref2)); // same hash

        // ----- Demonstrating shared state -----
        System.out.println("\n--- Shared State: Change via one ref, visible via another ---");
        System.out.println("  DB host (via ref1): " + ref1.get("db.host"));

        ref1.set("db.host", "prod-db-server.example.com"); // change via ref1

        // ref2 sees the change because ref2 IS ref1 — same object
        System.out.println("  DB host (via ref2 after ref1 set): " + ref2.get("db.host"));

        // ----- Demonstrating lazy initialization -----
        System.out.println("\n--- Lazy Initialization ---");
        System.out.println("  Notice the config was loaded only once at the very beginning.");
        System.out.println("  Calling getInstance() 3 times only triggered one constructor call.");

        // ----- Showing the naive version problem conceptually -----
        System.out.println("\n--- Naive Version (single-threaded only) ---");
        NaiveConfigManager n1 = NaiveConfigManager.getInstance();
        NaiveConfigManager n2 = NaiveConfigManager.getInstance();
        System.out.println("  Same instance (single thread)? " + (n1 == n2));
        System.out.println("  (In multithreaded context, this could be false — race condition)");

        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Private constructor prevents external instantiation");
        System.out.println("  2. volatile + double-checked locking = thread-safe + efficient");
        System.out.println("  3. Holder idiom = cleanest, leverages JVM class-loading guarantee");
        System.out.println("  4. All callers share the same state — changes are globally visible");
    }
}

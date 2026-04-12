import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// =============================================================================
// PATTERN: Prototype
// PURPOSE: Create new objects by copying (cloning) an existing object instead
//          of building from scratch. The original object is the "prototype."
//
// REAL-WORLD ANALOGY:
//   A document editor. You have a complex document with styles, formatting, and
//   embedded objects. Creating a new document "based on this one" doesn't mean
//   you rebuild every element from scratch — you duplicate it, then tweak the
//   copy. That's Prototype.
//
// THE PROBLEM IT SOLVES:
//   Object creation can be expensive:
//     - Reading default config from a database
//     - Fetching a template over a network
//     - Running complex initialization logic
//   If you need 50 similar objects (e.g., 50 server configs, all starting from
//   the same base), creating each from scratch wastes time and resources.
//   Clone the expensive base object once, then customize each copy.
//
// THE KEY CONCEPT — SHALLOW vs DEEP COPY:
//   Primitive fields (int, String) are safely copied by value.
//   Reference fields (List, Map, objects) need special handling:
//
//   SHALLOW COPY: The cloned object gets a reference to the SAME list/map.
//     → clone.list.add(x) also adds to original.list!
//     → Almost always a bug.
//
//   DEEP COPY: The cloned object gets its own NEW list/map.
//     → Changes to clone's list do NOT affect original.
//     → This is what you almost always want.
// =============================================================================

public class PrototypePattern {

    // =========================================================================
    // PART 1: SHALLOW COPY DEMO
    // Shows WHY shallow copy is usually wrong for objects with mutable fields.
    // =========================================================================
    static class ShallowServerConfig implements Cloneable {
        String host;
        int port;
        List<String> allowedIPs; // mutable reference type — danger zone for shallow copy

        ShallowServerConfig(String host, int port) {
            this.host       = host;
            this.port       = port;
            this.allowedIPs = new ArrayList<>();
        }

        // Shallow clone — super.clone() copies all fields by value.
        // For primitives: fine. For references (like allowedIPs): BOTH objects
        // now point to the SAME list object in memory.
        @Override
        public ShallowServerConfig clone() {
            try {
                // super.clone() copies field values directly — for reference fields
                // this means copying the memory address, not the object itself.
                return (ShallowServerConfig) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException("Clone failed", e);
            }
        }
    }


    // =========================================================================
    // PART 2: DEEP COPY — the correct approach
    // =========================================================================
    static class ServerConfiguration implements Cloneable {
        private String host;
        private int port;
        private boolean sslEnabled;
        private List<String> allowedIPs;        // mutable — needs deep copy
        private Map<String, String> settings;   // mutable — needs deep copy
        private DatabaseConfig dbConfig;        // nested object — needs deep copy

        ServerConfiguration(String host, int port, boolean sslEnabled) {
            this.host       = host;
            this.port       = port;
            this.sslEnabled = sslEnabled;
            this.allowedIPs = new ArrayList<>();
            this.settings   = new HashMap<>();
            this.dbConfig   = new DatabaseConfig("", 0);
        }

        // --- Fluent builder-style methods for easy config setup ---
        public ServerConfiguration withIP(String ip) {
            allowedIPs.add(ip);
            return this;
        }

        public ServerConfiguration withSetting(String key, String value) {
            settings.put(key, value);
            return this;
        }

        public ServerConfiguration withDatabase(String dbHost, int dbPort) {
            this.dbConfig = new DatabaseConfig(dbHost, dbPort);
            return this;
        }

        public void setHost(String host)        { this.host = host; }
        public void setPort(int port)           { this.port = port; }
        public void setSslEnabled(boolean ssl)  { this.sslEnabled = ssl; }
        public List<String> getAllowedIPs()     { return allowedIPs; }
        public Map<String, String> getSettings(){ return settings; }
        public DatabaseConfig getDbConfig()     { return dbConfig; }

        // ─────────────────────────────────────────────────────────────────
        // DEEP CLONE — the key method
        //
        // Step 1: super.clone() creates a new object with all fields copied.
        //         For primitives (int, boolean): fine — they're value types.
        //         For String: fine — Strings are immutable in Java.
        //         For List/Map: NOT fine — still points to the same object.
        //
        // Step 2: Manually replace each mutable reference field with a new copy.
        //         - new ArrayList<>(original) → copies all elements into a new list
        //         - new HashMap<>(original) → copies all entries into a new map
        //         - dbConfig.clone() → deep copy the nested object too
        // ─────────────────────────────────────────────────────────────────
        @Override
        public ServerConfiguration clone() {
            try {
                // Step 1: shallow clone of the object itself
                ServerConfiguration cloned = (ServerConfiguration) super.clone();

                // Step 2: deep copy each mutable reference field
                cloned.allowedIPs = new ArrayList<>(this.allowedIPs);   // new list, same elements
                cloned.settings   = new HashMap<>(this.settings);         // new map, same entries
                cloned.dbConfig   = this.dbConfig.clone();               // deep copy nested object

                return cloned;
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException("Clone failed", e);
            }
        }

        @Override
        public String toString() {
            return String.format(
                "ServerConfig{host='%s', port=%d, ssl=%b, ips=%s, settings=%s, db=%s}",
                host, port, sslEnabled, allowedIPs, settings, dbConfig
            );
        }
    }


    // =========================================================================
    // NESTED OBJECT: DatabaseConfig
    // Demonstrates that nested objects also need their own clone() method.
    // =========================================================================
    static class DatabaseConfig implements Cloneable {
        private String dbHost;
        private int dbPort;
        private List<String> replicaHosts; // mutable — must deep copy

        DatabaseConfig(String dbHost, int dbPort) {
            this.dbHost        = dbHost;
            this.dbPort        = dbPort;
            this.replicaHosts  = new ArrayList<>();
        }

        public void addReplica(String host) { replicaHosts.add(host); }
        public void setDbHost(String host)  { this.dbHost = host; }

        @Override
        public DatabaseConfig clone() {
            try {
                DatabaseConfig cloned   = (DatabaseConfig) super.clone();
                cloned.replicaHosts     = new ArrayList<>(this.replicaHosts); // deep copy
                return cloned;
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException("Clone failed", e);
            }
        }

        @Override
        public String toString() {
            return String.format("DB{host='%s', port=%d, replicas=%s}", dbHost, dbPort, replicaHosts);
        }
    }


    // =========================================================================
    // PROTOTYPE REGISTRY
    // A common companion to Prototype: a registry that stores named prototypes.
    // Instead of holding a reference to the original and calling clone() yourself,
    // you ask the registry: "give me a clone of the 'production' template."
    // =========================================================================
    static class ConfigRegistry {
        private final Map<String, ServerConfiguration> registry = new HashMap<>();

        // Store a named prototype in the registry
        public void register(String name, ServerConfiguration config) {
            // Store a clone so the registered "master" stays untouched
            registry.put(name, config.clone());
            System.out.println("  [Registry] Registered template: '" + name + "'");
        }

        // Return a CLONE of the registered prototype — never the original
        public ServerConfiguration get(String name) {
            ServerConfiguration template = registry.get(name);
            if (template == null) {
                throw new IllegalArgumentException("No config registered with name: " + name);
            }
            return template.clone(); // caller gets a fresh copy, not the master template
        }
    }


    // =========================================================================
    // MAIN — demonstrates shallow vs deep copy and the registry
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Prototype Pattern Demo ===\n");

        // ─────────────────────────────────────────────────────────────────────
        // PART 1: Demonstrate the SHALLOW COPY PROBLEM
        // ─────────────────────────────────────────────────────────────────────
        System.out.println("--- Part 1: The Shallow Copy Problem ---\n");

        ShallowServerConfig shallowOriginal = new ShallowServerConfig("api.example.com", 8080);
        shallowOriginal.allowedIPs.add("10.0.0.1");
        shallowOriginal.allowedIPs.add("10.0.0.2");

        ShallowServerConfig shallowClone = shallowOriginal.clone();
        System.out.println("  Before adding IP to clone:");
        System.out.println("    Original IPs : " + shallowOriginal.allowedIPs);
        System.out.println("    Clone IPs    : " + shallowClone.allowedIPs);

        // Adding to the CLONE's list also modifies the ORIGINAL's list!
        // They share the SAME list object.
        shallowClone.allowedIPs.add("192.168.1.100");

        System.out.println("\n  After adding IP to clone only:");
        System.out.println("    Original IPs : " + shallowOriginal.allowedIPs + "  ← ALSO CHANGED! Bug!");
        System.out.println("    Clone IPs    : " + shallowClone.allowedIPs);
        System.out.println("  Both point to the same list: "
            + (shallowOriginal.allowedIPs == shallowClone.allowedIPs)); // true — same reference


        // ─────────────────────────────────────────────────────────────────────
        // PART 2: Deep Copy — the correct approach
        // ─────────────────────────────────────────────────────────────────────
        System.out.println("\n--- Part 2: Deep Copy (correct) ---\n");

        // Imagine this base config was loaded from a database (expensive!)
        System.out.println("  Creating base config (imagine this hits a database)...");
        ServerConfiguration baseConfig = new ServerConfiguration("api.example.com", 8080, true)
                .withIP("10.0.0.1")
                .withIP("10.0.0.2")
                .withSetting("timeout", "30s")
                .withSetting("maxConnections", "100")
                .withDatabase("db-primary.example.com", 5432);
        baseConfig.getDbConfig().addReplica("db-replica-1.example.com");
        baseConfig.getDbConfig().addReplica("db-replica-2.example.com");

        System.out.println("  Base: " + baseConfig);

        // Clone for staging — cheap, immediate. No DB call needed.
        ServerConfiguration stagingConfig = baseConfig.clone();
        stagingConfig.setHost("staging.example.com");
        stagingConfig.withIP("192.168.1.0");                     // staging-only IP
        stagingConfig.withSetting("maxConnections", "20");        // lower limit for staging
        stagingConfig.getDbConfig().setDbHost("db-staging.example.com"); // different DB

        // Clone for load testing
        ServerConfiguration loadTestConfig = baseConfig.clone();
        loadTestConfig.setHost("loadtest.example.com");
        loadTestConfig.withSetting("maxConnections", "500");      // higher for load test

        System.out.println("\n  After customizing each clone:");
        System.out.println("  Base     : " + baseConfig);
        System.out.println("  Staging  : " + stagingConfig);
        System.out.println("  LoadTest : " + loadTestConfig);

        // Prove independence — none of the clones affected the base
        System.out.println("\n  Base IPs unchanged: " + baseConfig.getAllowedIPs());
        System.out.println("  Base settings unchanged: " + baseConfig.getSettings());
        System.out.println("  Lists are different objects: "
            + (baseConfig.getAllowedIPs() != stagingConfig.getAllowedIPs())); // true


        // ─────────────────────────────────────────────────────────────────────
        // PART 3: Prototype Registry
        // ─────────────────────────────────────────────────────────────────────
        System.out.println("\n--- Part 3: Prototype Registry ---\n");

        ConfigRegistry registry = new ConfigRegistry();

        // Register named templates
        ServerConfiguration prodTemplate = new ServerConfiguration("prod.example.com", 443, true)
                .withSetting("logLevel", "ERROR")
                .withSetting("maxConnections", "500");
        registry.register("production", prodTemplate);

        ServerConfiguration devTemplate = new ServerConfiguration("localhost", 8080, false)
                .withSetting("logLevel", "DEBUG")
                .withSetting("maxConnections", "10");
        registry.register("development", devTemplate);

        System.out.println();

        // Get clones from the registry — each call returns a fresh independent clone
        ServerConfiguration newProdServer = registry.get("production");
        newProdServer.withIP("10.10.1.5"); // customize for this specific server

        ServerConfiguration anotherProdServer = registry.get("production");
        anotherProdServer.withIP("10.10.1.6"); // different IP for second server

        System.out.println("  Server 1 IPs: " + newProdServer.getAllowedIPs());
        System.out.println("  Server 2 IPs: " + anotherProdServer.getAllowedIPs()); // different!
        System.out.println("  Templates in registry are unaffected by customizations.");


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Clone when object creation is expensive — avoid repeated DB/network calls");
        System.out.println("  2. Shallow copy shares mutable fields — almost always a bug");
        System.out.println("  3. Deep copy: super.clone() first, then manually copy each List/Map/Object");
        System.out.println("  4. Nested objects must implement clone() too — otherwise still shallow");
        System.out.println("  5. Registry pattern stores named templates, serves fresh clones on demand");
    }
}

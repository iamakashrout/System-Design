# 2.4 Data Partitioning & Scaling Strategies (HLD Level)
> *This is where systems truly scale.*
> *Caches and queues help — partitioning changes the architecture.*

Most junior candidates talk about caching.
Senior engineers talk about **partitioning, hot keys, and resharding**.

---

## 1. Why Partitioning Exists (The Core Problem)

A single database node **cannot scale forever**. No matter how much money you spend, you will eventually hit the laws of physics.

### The Physical Limits of a Single Machine
Every server has hard limits:
1.  **CPU Cores:** You can only fit so many processors on a motherboard. Once your query load exceeds CPU capacity, latency spikes.
2.  **RAM:** You cannot fit the entire internet in one machine's memory. Once data exceeds RAM, the system swaps to disk, slowing down by 100x.
3.  **Disk I/O:** Even with NVMe SSDs, a single drive controller has a maximum throughput (IOPS).
4.  **Network Bandwidth:** A single network card (NIC) can only handle so many packets per second.

### What Happens Without Partitioning?
If you rely on a single monolithic database:
-   **The Ceiling:** You hit a hard user limit (e.g., 1 million users). You cannot accept user #1,000,001 without crashing.
-   **Single Point of Failure (SPOF):** If that one machine dies (hardware failure, OS crash), your entire business stops.
-   **Maintenance Nightmares:** You cannot upgrade the OS or database software without taking the entire system offline.

### The Solution: Partitioning
Partitioning (or Sharding) is the process of splitting a large dataset into smaller, manageable chunks (shards) stored across multiple machines.

> **Partitioning is how systems go from thousands → millions → billions of users.**

---

## 2. Two Fundamental Scaling Axes

Scaling has only **two real directions**. Understanding the trade-offs between them is the first step in system design.

### 2.1 Vertical Scaling (Scale Up)

**Concept:**
You replace your existing server with a bigger, more powerful one. You add more RAM, more CPU cores, or faster SSDs.

**Analogy:**
You have a car that can't carry enough people. You trade it in for a bus.

**Example:**
-   Migrating an AWS EC2 instance from `t3.medium` (2 vCPU, 4GB RAM) to `u-12tb1.metal` (448 vCPUs, 12TB RAM).

**Pros:**
-   **Simplicity:** No code changes required. You don't need to change your database architecture or query logic.
-   **No Distributed Complexity:** You don't have to worry about network partitions, data consistency between nodes, or complex routing.

**Cons:**
-   **Exponential Cost:** A server with 2x performance often costs 4x or 10x as much. High-end hardware is incredibly expensive.
-   **Hard Limits:** There is a maximum size for a server. Once you buy the biggest machine on the market, you are stuck.
-   **Downtime:** Upgrading usually requires shutting down the old server to move data to the new one.

> **Verdict:** Vertical scaling is a temporary solution or an optimization for early-stage startups. It is not a long-term architecture.

### 2.2 Horizontal Scaling (Scale Out)

**Concept:**
You add **more machines** to your pool. Instead of one giant server, you have many smaller, commodity servers working together.

**Analogy:**
You have a car that can't carry enough people. You buy 10 more cars and hire drivers.

**Example:**
-   Going from 1 Database Node → 10 Database Nodes → 100 Database Nodes.
-   Cassandra, MongoDB, and Google Spanner are designed for this.

**Pros:**
-   **Infinite Scale:** Theoretically, you can keep adding servers forever to handle more load.
-   **Cost-Effective:** You can use cheaper, standard hardware.
-   **Fault Tolerance:** If one server dies, the others keep running. You lose 1% of your capacity, not 100%.

**Cons:**
-   **Complexity:** You need logic to route users to the correct server.
-   **Consistency Issues:** Keeping data in sync across nodes is hard (CAP Theorem).
-   **Operational Overhead:** Managing 100 servers is much harder than managing 1.

> **Senior Mindset:** Assume horizontal scaling by default for high-growth systems.

---

## 3. Partitioning (Sharding) Strategies

Partitioning decides **where data lives**. Choosing the right strategy prevents uneven load (skew) and allows efficient querying.

### 3.1 Range-Based Partitioning

**How it works:**
You split data based on continuous ranges of a specific key (e.g., User ID, Creation Date, Alphabetical).

**Example:**
-   **Shard A:** User IDs 1 – 1,000,000
-   **Shard B:** User IDs 1,000,001 – 2,000,000
-   **Shard C:** User IDs 2,000,001 – 3,000,000

**Pros:**
-   **Simple Implementation:** Easy to route traffic (`if id < 1M then Shard A`).
-   **Efficient Range Queries:** Great for queries like "Get all users created between Jan and March". The data is physically close together.

**Cons:**
-   **Hot Partitions (Data Skew):** If you partition by "Creation Date", all *new* traffic hits the latest shard (the current date). The old shards sit idle while the new one melts down.
-   **Uneven Load:** If Shard A holds "inactive users" and Shard C holds "active users", Shard C will be overloaded.

### 3.2 Hash-Based Partitioning

**How it works:**
You use a hash function on the key to determine the shard.
Formula: `Shard_ID = hash(key) % Number_of_Shards`

**Example (4 Shards):**
-   User A (ID 100) → `hash(100) % 4` = Shard 0
-   User B (ID 101) → `hash(101) % 4` = Shard 1

**Pros:**
-   **Uniform Distribution:** A good hash function distributes data evenly across all shards, preventing hotspots.
-   **Simple Routing:** You don't need a lookup table; the math tells you where the data is.

**Cons:**
-   **Resharding Nightmare:** If you change the number of shards (N) from 4 to 5, the formula changes (`% 5`). Almost **all** keys will map to a new shard. You have to move nearly 100% of your data to new servers.
-   **No Range Queries:** User 1 and User 2 might be on completely different servers. You cannot efficiently "Get users 1-100".

### 3.3 Consistent Hashing (The Senior Solution)

**The Problem:**
Standard Hash Partitioning breaks when you add/remove servers because `N` changes.

**The Solution:**
Consistent Hashing maps both data and servers onto a logical **Ring** (0 to 360 degrees).
1.  **Place Servers on the Ring:** Hash the server IP to find its position.
2.  **Place Keys on the Ring:** Hash the data key to find its position.
3.  **Map Key to Server:** Walk clockwise from the key's position until you find a server. That server owns the key.

**Virtual Nodes (VNodes):**
To ensure balance, each physical server appears multiple times on the ring (e.g., Server A is at positions 10, 45, 90). This prevents one server from getting a huge slice of the ring by luck.

**Pros:**
-   **Minimal Data Movement:** When you add a server, it only takes a small slice of keys from its neighbor. You only move ~1/N of the data, not 100%.
-   **Dynamic Scaling:** You can add/remove nodes without downtime.

**Cons:**
-   **Complexity:** Harder to implement than simple modulo hashing.
-   **Key Distribution:** Without VNodes, distribution can still be uneven.

> **Interview Signal:** Explaining Consistent Hashing clearly proves you understand distributed systems at a deep level.

---

## 4. Partitioning Key Selection (Critical Senior Skill)

Choosing the wrong partition key is the most common reason for system failure at scale. Once chosen, it is extremely expensive to change.

### The Golden Rule
> **Partition by the dominant access pattern.**

You must analyze how the application *reads* the data.

### Detailed Examples

#### 1. URL Shortener (e.g., bit.ly)
-   **Access Pattern:** Users mostly look up a URL by its `short_code` (e.g., `abc1234`).
-   **Bad Key:** `created_at` (Causes hot spots on write).
-   **Good Key:** `short_code`.
-   **Why:** We use Hash Partitioning on `short_code`. This distributes URLs evenly. Lookups are O(1) because we know exactly which shard holds `abc1234`.

#### 2. Social Media Feed (e.g., Twitter/Instagram)
-   **Access Pattern:** "Show me the feed for User A." This requires fetching posts from all the people User A follows.
-   **Option A: Partition by `post_id`:**
    -   Writes are fast (random shard).
    -   Reads are slow (Scatter-Gather). To build a feed, you must query *every* shard to find posts for the user's friends.
-   **Option B: Partition by `user_id`:**
    -   All posts for a specific user live on one shard.
    -   Reads are faster if we are fetching "User A's posts".
    -   **Trade-off:** Generating a "Home Feed" is still hard (Fan-out on Read vs Fan-out on Write). Usually, we partition the *User Feed Table* by `user_id` so fetching the pre-computed feed is a single-shard query.

#### 3. Chat Application (e.g., WhatsApp)
-   **Access Pattern:** "Show me the messages in Conversation X."
-   **Bad Key:** `message_id` (Messages are scattered everywhere).
-   **Good Key:** `conversation_id`.
-   **Why:** All messages for a single chat group live on the same shard. When a user opens the chat, the DB performs a fast local query to fetch the last 50 messages.

---

## 5. Replication (Scaling Reads & Availability)

**Partitioning scales writes (by splitting data).**
**Replication scales reads (by copying data) and ensures availability.**

### 5.1 Primary–Replica (Leader–Follower)
**Architecture:**
-   **1 Primary Node:** Handles all **Writes** (INSERT, UPDATE, DELETE).
-   **N Replica Nodes:** Copies of the Primary. Handle **Reads** (SELECT).

**How it works:**
1.  Client writes to Primary.
2.  Primary saves data and sends a log (Binlog/WAL) to Replicas.
3.  Replicas update their own data.

**Pros:**
-   **Read Scalability:** If you have 1M reads/sec, just add more Replicas.
-   **Backup:** If Primary dies, a Replica can be promoted to be the new Primary.

**Cons:**
-   **Replication Lag:** Data takes time to travel to Replicas. A user might write data and immediately read from a Replica that doesn't have it yet (Eventual Consistency).
-   **Write Bottleneck:** All writes must go to the single Primary.

### 5.2 Multi-Primary (Multi-Leader)
**Architecture:**
-   Multiple nodes can accept **Writes**.
-   Nodes sync with each other.

**Pros:**
-   **Global Availability:** A user in the US writes to the US Leader. A user in the EU writes to the EU Leader.
-   **Resilience:** If one Leader dies, others accept writes.

**Cons:**
-   **Write Conflicts:** What if User A sets `X=1` in the US, and User B sets `X=2` in the EU at the same time?
-   **Complexity:** Requires conflict resolution strategies (Last Write Wins, Vector Clocks).

---

## 6. Hot Keys & Skew (Real-World Pain)

A **Hot Key** is a specific partition key that receives disproportionate traffic, overloading a single shard while others sit idle.

### Examples
-   **Celebrity Problem:** Justin Bieber has 100M followers. If you partition by `user_id`, the shard holding his data will be crushed by millions of people fetching his tweets.
-   **Viral Content:** A specific video ID gets 1M views/sec.

### Mitigation Strategies

#### 1. Key Salting (Sharding the Key)
If `key_A` is hot, we artificially split it into `key_A_1`, `key_A_2` ... `key_A_N`.
-   **Writes:** Randomly distribute writes to one of the salted keys.
-   **Reads:** Query *all* salted keys and aggregate the result.
-   **Trade-off:** Writes are fast, reads become slightly more expensive (scatter-gather), but the load is distributed.

#### 2. Load-Aware Routing
The Load Balancer or Router tracks the heat of keys. If it detects a hot key, it can route requests to a specialized pool of servers or a cached version.

#### 3. Caching (The First Line of Defense)
Put the hot data in a distributed cache (Redis/Memcached) or a CDN.
-   If 99% of traffic is reads, the database never sees the load.
-   **Local Caching:** For extreme heat, cache the data directly in the application server's RAM (L1 Cache) to avoid network calls to Redis.

---

## 7. Rebalancing & Resharding

Resharding is the process of moving data between nodes to balance the load. This is one of the riskiest operations in distributed systems.

### When is it needed?
-   **Data Growth:** Storage is full.
-   **Traffic Spikes:** Need more CPU/RAM.
-   **Node Failure:** A node died permanently.

### Challenges
1.  **Network Saturation:** Moving Terabytes of data consumes bandwidth, slowing down the live application.
2.  **Consistency:** How do you handle writes to a user while their data is moving from Shard A to Shard B?
3.  **Dual Writes:** You might need to write to both the old and new location during the transition.

### Solutions
-   **Incremental Migration:** Move data in small chunks (e.g., one user at a time) rather than all at once.
-   **Hierarchical Partitioning:** Use a directory service to track where each key lives, allowing you to move keys individually without changing the hash formula.

> **Senior Statement:** "Resharding must be an online operation. We cannot stop the world to move data. We use background workers and consistency checks to migrate without downtime."

---

## 8. Geo-Partitioning (Global Scale)

For global applications, latency and laws (GDPR) force you to partition by geography.

### Strategies

#### 1. Partition by Region (Data Residency)
-   **Concept:** EU users' data stays in EU servers. US users in US servers.
-   **Pros:** Compliance with laws (GDPR). Low latency for local users.
-   **Cons:** What happens when an EU user travels to the US? (They experience high latency or can't access data).

#### 2. Geo-Caching (Read-Heavy)
-   **Concept:** Master database in one region (e.g., US). Read Replicas in every other region.
-   **Pros:** Fast reads everywhere.
-   **Cons:** Writes are slow for non-US users (must travel across the ocean).

#### 3. Active-Active Geo-Replication
-   **Concept:** Full database copies in multiple regions. Writes can happen anywhere and propagate asynchronously.
-   **Pros:** Lowest latency for everyone. High fault tolerance (Region failover).
-   **Cons:** Extremely complex conflict resolution.

---

## 9. Read vs Write Scaling Summary

| Scenario | Primary Bottleneck | Solution Strategy |
| :--- | :--- | :--- |
| **Read-Heavy** (100:1) | CPU / Network | **Replication** (Read Replicas), **Caching** (Redis/CDN). |
| **Write-Heavy** (1:1) | Disk I/O / Locking | **Sharding** (Partitioning), **Async Queues** (Buffer writes). |
| **Data Volume** (Petabytes) | Disk Space | **Sharding**, **Object Storage** (S3) for blobs, **Data Archival**. |
| **Hot Key** (Viral Item) | Single Node Overload | **Caching**, **Key Salting**, **Read Replicas**. |
| **Global Latency** | Speed of Light | **Geo-Partitioning**, **Edge Caching** (CDN). |

---

## 10. Conclusion

System design is about identifying bottlenecks and removing them.

-   **Vertical Scaling** buys you time.
-   **Horizontal Scaling** buys you a future.
-   **Partitioning** is the engine of horizontal scaling.
-   **Replication** is the safety net for availability.

When you design a system, start simple. But when the interviewer asks, *"How do we handle 100 million users?"*, your answer should immediately pivot to **Partitioning Keys, Consistent Hashing, and Handling Hot Spots**.

**Final Mental Model:**
> **Partitioning decides where data lives.**
> **Replication decides how data survives.**
> **Keys decide whether your system dies under load.**
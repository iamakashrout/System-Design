# 2.6 Consistency, Performance & Trade-offs (HLD View)
> Seniors don’t ask “What is best?”  
> Seniors ask “What is acceptable given constraints?”

Every system design is a **negotiation** between correctness, speed, cost, and availability.

---

# 1. The Fundamental Tension

You **cannot maximize all three simultaneously**:

- Consistency (correctness)
- Availability (system always responds)
- Low Latency / Performance (fast responses)

Improving one usually **hurts another**.

---

## Real-World Analogy

### Bank ATM
- Must be **consistent** (no wrong balances)
- Will go down if uncertain → **availability sacrificed**

### Social Media Feed
- Must be **fast and always available**
- Slightly stale posts acceptable → **consistency sacrificed**

---

# 2. CAP Theorem (Interview Foundation)

## CAP States

In presence of **network partitions**, you must choose between:

### Consistency (C)
All nodes see the same data at the same time.

### Availability (A)
Every request gets a response (even if data is stale).

### Partition Tolerance (P) 
The system continues to operate despite an arbitrary number of messages being dropped (or delayed) by the network between nodes.

### Why it Occurs
In any real-world distributed system, the network is unreliable. Servers can become disconnected from each other (a "partition"). For example, the network link between a US data center and a European data center might fail.

When a partition happens, the system is split into two or more groups of nodes that cannot communicate. At this point, a critical choice must be made:
1.  **Forbid writes** to prevent the two sides from becoming out of sync. This ensures **Consistency** but sacrifices **Availability**.
2.  **Allow writes** on both sides. The system remains **Available**, but the data on each side will diverge, sacrificing **Consistency**.


## Key Insight

In distributed systems:

> **Network partitions WILL happen → P is mandatory**

So you must choose:

- CP (Consistency + Partition Tolerance)
- AP (Availability + Partition Tolerance)

---

## CP Systems (Consistency + Partition Tolerance)

### Behavior & Implementation
When a partition occurs, a CP system will sacrifice availability to maintain consistency.
- **How it works:** The system might designate one side of the partition as the "leader" and disallow writes on the "follower" side. Any client trying to write to a follower node will receive an error or a timeout. This is often managed using consensus algorithms like **Paxos** or **Raft**, which ensure that a majority of nodes (a "quorum") must agree on a write before it is committed.
- **Pros:**
    - Guarantees data is correct and predictable.
    - Simpler to reason about from a developer's perspective.
- **Cons:**
    - Can have higher latency due to coordination.
    - May become unavailable to some users during a network partition.

### Use Cases & Examples
- **Use Cases:** Financial transactions, inventory management, user authentication, and any system where correctness is non-negotiable.
- **Examples:**
    - **Traditional SQL Databases** (like PostgreSQL in its default configuration).
    - **Zookeeper, etcd:** Used for configuration management and service discovery where consistency is paramount.
    - **MongoDB** can be configured to be a CP system.

---

## AP Systems (Availability + Partition Tolerance)

### Behavior & Implementation
When a partition occurs, an AP system will sacrifice consistency to remain available.
- **How it works:** Every node remains able to accept reads and writes. When the partition heals, the system uses a **conflict resolution** strategy to merge the divergent data. A common strategy is **"Last Write Wins" (LWW)**, where the update with the later timestamp is chosen. This is achieved via asynchronous ("gossip") replication.
- **Pros:**
    - Highly available and resilient to network failures.
    - Low latency for both reads and writes.
- **Cons:**
    - Data can be stale (eventual consistency).
    - Conflict resolution can be complex and may lead to data loss if not handled carefully.

### Use Cases & Examples
- **Use Cases:** Social media feeds, like counters, real-time analytics, and logging systems where high availability is key and some data staleness is acceptable.
- **Examples:**
    - **Amazon DynamoDB** and **Apache Cassandra** are classic examples of AP systems.
    - **Riak** and **Couchbase**.

---

## Senior Interview Line

> “For user-facing social features, we prefer availability; for financial correctness, we prefer consistency.”

---

# 3. Consistency Models (What Users See)

Consistency models are specific contracts that define **what users observe** about data freshness, not just the internal mechanics of the system.

---

## 3.1 Strong Consistency

### Meaning & Implementation
The strictest model. It guarantees that any read operation will return the value from the most recently completed write operation. The system effectively behaves like a single, non-distributed machine.
- **How it's implemented:**
    - **Single Leader Replication:** All writes and reads are directed to a single primary "leader" node.
    - **Synchronous Replication:** The leader waits for confirmation from all (or a quorum of) replicas before confirming the write to the client.

### Pros & Cons
- **Pros:** Simple mental model for developers; no stale data issues.
- **Cons:** Higher latency on writes (due to coordination); lower availability (if the leader or enough replicas are down, writes can fail).

### Use Cases
Banking, payment processing, inventory control, and other systems where correctness is the top priority.

---

## 3.2 Eventual Consistency

### Meaning & Implementation
A much weaker model that guarantees that if no new updates are made to a given data item, all accesses to that item will *eventually* return the last updated value. It allows for temporary inconsistencies.
- **How it's implemented:**
    - **Asynchronous Replication:** A write is confirmed to the client as soon as it's accepted by one node. That node then propagates the change to other replicas in the background.

### Pros & Cons
- **Pros:** Very high availability and low latency; scales well globally.
- **Cons:** Can result in stale reads; makes application logic more complex as developers must account for stale data.

### Use Cases
Social media likes, view counters, analytics, and any scenario where data being slightly out-of-date is acceptable.

---

## 3.3 Causal / Session Consistency (Middle Ground)

This is a family of practical, user-centric models that are stronger than eventual consistency but weaker than strong consistency.

#### Read-Your-Writes
A user should be able to see their own updates immediately after making them. This is a very intuitive guarantee.
- **How it's implemented:**
    - **Sticky Sessions:** After a user writes data, the load balancer routes all of that user's requests to the same replica that received the write for a short period.
    - **Read from Primary:** For a few seconds after a write, reads for that user are directed to the primary/leader node.

#### Monotonic Reads
If a user performs a sequence of reads, they will never see data go "backward in time." Once they have seen a particular update, subsequent reads will always return that same data or newer data.
- **How it's implemented:** This is often achieved by ensuring a user is always directed to the same replica, or to replicas that have at least the same level of data freshness.

### Pros & Cons of these models
- **Pros:** Provides a much better user experience than pure eventual consistency by preventing confusing anomalies (like a user's own comment disappearing).
- **Cons:** Adds complexity to the routing or data access layer; does not provide global consistency guarantees between different users.

### Use Cases
User profiles, comments, posts—anywhere a user directly interacts with and then views their own content.

---

# 4. Latency vs Correctness Trade-offs

This is a core senior engineering judgment call. The key is to classify the different data paths in your system and choose the right trade-off for each.

### When to Prioritize Correctness (Consistency)
Prioritize correctness when stale or incorrect data has a significant negative consequence.
- **Financial Impact:** A user's bank balance, an order total, or a stock price for a trade.
- **Security Impact:** A user's permissions or authentication status.
- **Business Logic Integrity:** An inventory count. Overselling an item leads to customer dissatisfaction and operational overhead.

In these cases, it is acceptable for the system to be slightly slower or even temporarily unavailable to prevent an error.

### When to Prioritize Latency (Speed)
Prioritize latency when the user experience is paramount and the data is not critical.
- **User Engagement:** A social media feed, a list of recommended products, or search results. A slow response will cause users to leave.
- **Non-critical Data:** A "like" count, a view counter, or analytics events. An eventually consistent count is perfectly fine.
- **Read-only Content:** A news article or a blog post.

In these cases, serving a slightly stale version from a fast cache or a nearby replica is the better choice.

---

## Interview Line

> “We classify data paths into correctness-critical and latency-critical.”

---

# 5. Read vs Write Performance Trade-offs

Reads and writes have **very different scaling strategies**.

---

## 5.1 Read-Optimized Systems (e.g., a News Feed)

These systems are designed for scenarios where data is written once but read many times (e.g., 100:1 read/write ratio). The goal is to make reads as fast as possible.

### Implementation Techniques
- **Replication (Read Replicas):** The primary database handles writes, while multiple read-only replicas handle read queries. This distributes the read load.
- **Caching:** Placing a fast in-memory cache like Redis in front of the database to serve frequently accessed data without hitting the DB at all.
- **Denormalization:** Instead of performing expensive `JOIN` operations at read time, data is pre-joined and stored in a single document. For example, a `post` document might contain the `user` information directly within it.
- **Pre-computation (Materialized Views):** For complex aggregations (e.g., "top 10 posts of the day"), a background job calculates the result periodically and stores it, so reads are just simple lookups.

### Pros & Cons
- **Pros:** Extremely fast reads; can handle very high read QPS (Queries Per Second).
- **Cons:** Writes become more complex (must invalidate caches and update denormalized data); higher storage costs due to data duplication.

---

## 5.2 Write-Optimized Systems

These systems are designed for high-volume data ingestion (e.g., 1:1 or 1:10 read/write ratio). The goal is to accept writes as quickly as possible.

### Implementation Techniques
- **Append-Only Logs:** Data is written sequentially to a log file, which is extremely fast. Think of a distributed log like Apache Kafka.
- **LSM Trees (Log-Structured Merge-Trees):** A data structure used by databases like Cassandra and RocksDB. Writes are first buffered in memory (a *memtable*) and then flushed to disk in sorted, immutable files (*SSTables*). This avoids slow, random disk I/O.
- **Batching:** Instead of writing one record at a time, the system buffers many writes and commits them to the database in a single, efficient batch operation.
- **Sharding:** Distributing writes across multiple database instances based on a partition key.

### Pros & Cons
- **Pros:** Can handle massive write throughput; excellent for sequential data like logs or time-series metrics.
- **Cons:** Reads can be slower because data for a single query might be spread across multiple files or even nodes, requiring a "read-and-merge" step.

---

# 6. Synchronous vs Asynchronous Processing

## Synchronous

### Meaning
User waits for operation to complete.

### Pros & Cons
- **Pros:** Immediate feedback to the user; simple, linear control flow that is easy to reason about.
- **Cons:**
    - **High User-Perceived Latency:** The user is blocked waiting for the entire chain of operations to complete.
    - **Tight Coupling & Cascading Failures:** If a downstream service is slow or fails, the calling service is directly impacted, and the failure can ripple up the chain.

### When to Use
Use for core, blocking operations where the user needs an immediate result to proceed.
- **Examples:** User login, payment authorization, form validation.

---

## Asynchronous

### Meaning
User gets immediate response, work happens later.

### Pros & Cons
- **Pros:**
    - **Low User-Perceived Latency:** The user gets a fast response.
    - **Decoupling & Resilience:** The producer service is isolated from failures in the consumer service. If the email service is down, the order can still be placed.
    - **Load Leveling:** Absorbs traffic spikes by buffering tasks in a message queue.
- **Cons:**
    - **Eventual Consistency:** The user doesn't see the final result immediately.
    - **Increased Complexity:** Requires managing a message broker (like RabbitMQ or Kafka) and makes debugging harder (requires distributed tracing).

### When to Use
Use for non-critical side effects or long-running tasks that can happen in the background.
- **Examples:** Sending confirmation emails, generating PDF invoices, resizing images, updating an analytics dashboard.

---

## Senior Pattern

> **Sync for core path, async for side effects.**

---

# 7. Throughput vs Latency

## Latency
**Latency** is the time it takes to complete a single request (e.g., 50ms). It's a measure of speed.

## Throughput
**Throughput** is the number of requests that can be processed in a given time period (e.g., 10,000 requests/second). It's a measure of capacity.

### How to Enhance Each
- **To improve Latency:**
    - Use caching to serve data from memory.
    - Use a CDN to serve content closer to the user.
    - Choose more efficient algorithms.
    - Co-locate services in the same data center to reduce network hops.
- **To improve Throughput:**
    - Use asynchronous processing.
    - Batch operations together (e.g., write 100 log entries at once).
    - Scale horizontally by adding more servers.
    - Use parallel processing.

### When to Prioritize Which
- **Prioritize Low Latency** for user-facing, interactive operations. A user logging in or loading their profile expects an immediate response. High throughput is secondary.
- **Prioritize High Throughput** for background processing and data ingestion pipelines. For a video processing system, it's more important to be able to process 1,000 videos per hour (high throughput) than for a single video to be processed in 100ms (low latency).

---

# 8. Storage Trade-offs

## Memory vs SSD vs Disk

| Storage | Speed | Cost per GB | Volatility | Use Case |
|---|---|---|---|---|
| **Memory (RAM)** | Nanoseconds | Highest | **Volatile** (data lost on power off) | Caching (Redis), active computations |
| **SSD (Solid State)** | Microseconds | Medium | Persistent | Database files, OS boot volumes |
| **HDD (Hard Disk)** | Milliseconds | Lowest | Persistent | Backups, log archives, cold storage |

## Structured vs Blob Storage

### Structured Storage
- SQL / NoSQL databases
- Supports queries and indexes
- **Use Case:** Storing metadata, user profiles, order information—anything you need to query efficiently.

### Blob Storage
- Object storage (S3, GCS)
- Large files: images, videos, backups
- **Use Case:** Storing large, unstructured binary files. It's cheap, highly durable, and infinitely scalable, but you can't query the contents of the files.

### Comprehensive Example: E-commerce Product Page
When a user loads a product page for a new phone:
- The phone's main image and promotional video are loaded from **Blob Storage** (like AWS S3) via a CDN.
- The product's name, price, description, and current stock count are fetched from a **Structured Database** (like PostgreSQL or DynamoDB).
- The user's shopping cart information is loaded from a fast in-memory **Cache** (like Redis) using their session ID.

---

# 9. Consistency in Caches (Important HLD Topic)

Caches are **fast but inconsistent by nature**.

## Cache Consistency Strategies

### TTL-Based Expiration
- Data expires automatically
- Simple, but stale data possible
- **When it's useful:** Perfect for non-critical data where you can tolerate a known window of staleness. For example, caching a "top 10 articles" list for 5 minutes is simple and effective.

### Write-Through Cache
- Write to cache and DB together
- Stronger consistency, slower writes
- **When it's useful:** Use for critical data that must be consistent and is read frequently, like user permissions or system configuration settings. The performance hit on writes is the price for consistency.

### Write-Back Cache
- Write to cache, DB async
- Fast writes, risk of data loss
- **When it's useful:** Ideal for write-heavy workloads where performance is paramount and a small amount of data loss on a crash is acceptable. Examples include real-time counters or analytics event collectors.

### Explicit Invalidation
- Delete cache on updates
- Complex but fresher data
- **When it's useful:** The best choice when data must be fresh, but you can't afford the write latency of write-through. It's the most powerful but also the most complex strategy, as you must handle failed invalidation attempts.

---

# 10. Distributed Transactions vs Eventual Consistency

## Two-Phase Commit (2PC)

### Meaning
This is a protocol that ensures all participating services in a distributed transaction either all commit or all abort. It provides ACID-like atomicity across microservices.

### How it Works (Example: Travel Booking)
Imagine booking a flight and a hotel in a single transaction.
1.  **Phase 1 (Prepare):** A central **Coordinator** asks each service (Flight Service, Hotel Service) if they are ready to commit. Each service reserves the resource (e.g., puts a hold on the seat/room) and replies "Yes" or "No".
2.  **Phase 2 (Commit/Abort):**
    - If *all* services reply "Yes", the Coordinator sends a "Commit" command to all of them.
    - If *any* service replies "No" or times out, the Coordinator sends an "Abort" command to all of them, and they release their reserved resources.

### Why it's Rarely Used
- **Blocking & High Latency:** While waiting for all services to respond, resources are locked. This leads to poor performance.
- **Fragile:** If the Coordinator crashes after sending "Prepare" but before sending "Commit", the resources remain locked indefinitely until an admin intervenes.

---

## Saga Pattern

### Meaning
The Saga pattern is a way to manage data consistency across microservices in an eventually consistent manner. It's a sequence of local transactions. If one transaction fails, the saga executes compensating transactions to undo the preceding transactions.

### How it Works (Example: Travel Booking)
1.  **User requests a trip.** The `Trip Service` starts a saga.
2.  **Transaction 1:** The `Trip Service` makes an API call to the `Flight Service` to book a flight. The `Flight Service` commits this in its own database.
3.  **Transaction 2:** Upon success, the `Trip Service` calls the `Hotel Service` to book a hotel.
4.  **Failure Scenario:** The `Hotel Service` fails (e.g., no rooms available).
5.  **Compensating Transaction:** The `Trip Service` now calls the `Flight Service`'s `cancelFlight` endpoint to undo the first transaction.

The final state is consistent (no flight, no hotel), but it was temporarily inconsistent (flight booked, no hotel).

### Advantages
- **No Global Locks:** Each service uses local transactions, so there is no blocking. This makes the system highly available and scalable.
- **Decoupling:** Services are loosely coupled and only communicate via APIs or events.
- **Resilience:** Failures are isolated to individual services and handled gracefully by compensating logic.

---

## Senior Interview Line

> “We avoid distributed transactions; we prefer sagas with compensating actions.”

---

# 11. Design Decision Framework (Senior Mental Model)

Let's apply these questions to a single, connected example: **Designing a "Like" button for a social media site.**

### 1. Is correctness critical?
- **Question:** What happens if a "like" is miscounted or temporarily lost?
- **Answer:** The impact is very low. No financial loss, no security breach.
- **Decision:** Correctness is not critical. We can sacrifice strong consistency.

### 2. What is acceptable staleness?
- **Question:** Is it okay if a user in Europe sees a like count of 99 while a user in the US sees 100 for a few seconds?
- **Answer:** Yes, this is perfectly acceptable.
- **Decision:** We can use an **eventually consistent** model.

### 3. What is the user-perceived latency requirement?
- **Question:** How fast should the "like" button feel after a user clicks it?
- **Answer:** It must feel instant (<100ms) for a good user experience. The UI should update immediately.
- **Decision:** The client-side action should be decoupled from the backend write. We'll use an **asynchronous** approach.

### 4. Is downtime acceptable?
- **Question:** What happens if the "Like" service is down?
- **Answer:** It's a poor experience, but it shouldn't bring down the entire website. Users should still be able to view posts.
- **Decision:** The system should be highly **available**. This points towards an **AP system** over a CP one.

### 5. What is the cost constraint?
- **Question:** This feature will handle billions of writes per day. How do we make it cheap?
- **Answer:** We need a system that scales horizontally and is optimized for high-volume writes.
- **Decision:** We'll use a **write-optimized** database (like Cassandra) and **batch** writes to reduce load.

**Final Design:** An AP system using an eventually consistent, write-optimized database. The client UI updates instantly, and an asynchronous API call is sent to a service that batches writes into a NoSQL database like Cassandra.

---

# 12. Interview Power Lines (Memorize)

Use these verbatim:

- “We prioritize availability for user-facing reads.”
- “Consistency is critical for financial state.”
- “We accept eventual consistency for non-critical data.”
- “Caching improves latency at the cost of staleness.”
- “Distributed transactions are avoided in favor of sagas.”
- “Batching increases throughput but increases latency.”

---

# 13. Mental Model Summary

Consistency ↔ Latency  
Availability ↔ Correctness  
Throughput ↔ Real-time  

---

# 14. Conclusion

System design is not about finding the "perfect" solution; it's about navigating a landscape of trade-offs. Every choice—from the database you select to the consistency model you adopt—has consequences for performance, reliability, and complexity.

A junior engineer might know what Kafka is. A senior engineer knows *why* and *when* to use it over RabbitMQ, and can clearly articulate the trade-offs being made regarding throughput, latency, and operational cost.

The final insight remains the most important:

> **Every system is a conscious compromise. Senior engineers make those compromises explicit and intentional.**
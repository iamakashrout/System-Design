# 1.7 Consistency (System Design Basics)

Consistency is one of the **most misunderstood** topics in system design.
This section builds intuition first, then ties it to real systems.

---

## 1.7.1 What Is Consistency? (Practical Definition)

Consistency answers one core question:

> “If I write data now, when will others see it?”

Consistency is about:
- Data visibility
- Data freshness
- Agreement between replicas

It is **NOT** about:
- Business correctness
- Validation logic
- Bug-free code

---

### Simple Analogy

Imagine a shared Google Doc with poor internet:
- You type a sentence
- Others may see it immediately, or after some delay

That delay and agreement is **consistency**.

---

### Real Example

User updates profile picture:
- Some users see new photo
- Others see old one for a while

System is functioning correctly — but **not strongly consistent**.

---

## 1.7.2 Why Consistency Is a Trade-off

### Reality of Distributed Systems

In real systems:
- Data is replicated
- Networks are slow/unreliable
- Nodes can fail

You cannot:
> You cannot have perfect consistency, perfect availability, and perfect partition tolerance all at once (this is the CAP Theorem). Since network partitions (failures) are a fact of life, you must choose between Consistency and Availability.

---

### Practical Scenario

Consider an e-commerce site during a flash sale.

1.  **Prioritizing Strong Consistency (CP - Consistent & Partition-Tolerant):**
    -   **Action:** A user buys the last item in stock.
    -   **Behavior:** The system locks the inventory record and ensures every part of the system (checkout, product page, search) agrees that the item is "Sold Out" before confirming the purchase. If it can't guarantee this (e.g., a network failure to a replica), it might return an error to the user.
    -   **Outcome:** Slower and less available, but no risk of overselling the item.

2.  **Prioritizing Availability (AP - Available & Partition-Tolerant):**
    -   **Action:** A user "likes" a product.
    -   **Behavior:** The system immediately accepts the "like" and tells the user's browser it succeeded. The "like count" is updated asynchronously across all replicas over the next few seconds. A user in another country might see the old count for a moment.
    -   **Outcome:** Faster and always available, but the data is temporarily stale.

---

## 1.7.3 Strong Consistency

### What Strong Consistency Means

Strong consistency guarantees:
- All reads see the **latest write**
- System behaves like a single machine
- Once a write is complete, all subsequent reads will see that write.

Guarantee:
> Read-after-write consistency for everyone.

---

### How It Works (Simplified)

To achieve this, systems use techniques like:
-   **Single Leader Replication:** All writes and reads go to a single primary "leader" node. This is simple but makes the leader a bottleneck.
-   **Synchronous Replication:** When data is written, the system waits for confirmation from multiple replicas before telling the client the write was successful.
-   **Quorum Writes:** The system writes to a majority of replicas (W) and reads from a majority (R) such that `W + R > N` (where N is the total number of replicas). This guarantees that the read and write sets overlap, ensuring the latest data is seen.

This ensures:
- No stale reads
- Higher coordination cost

---

### Advantages

- Simple mental model
- Easy to reason about correctness
- No conflicting data views

---

### Disadvantages

- Higher latency
- Lower availability during failures
- Harder to scale globally

---

### Where Strong Consistency Is Needed

- Banking transactions
- Payments
- Inventory systems
- Authentication & authorization

Interview line:
> “For critical financial or security-related data, we prioritize strong consistency.”

---

## 1.7.4 Eventual Consistency

### What Eventual Consistency Means

Eventual consistency guarantees:
- Updates propagate over time
- Temporary stale reads are allowed
- If no new writes occur, all replicas will **eventually** converge to the same state.

---

### How It Works (Simplified)

Systems achieve this through **asynchronous replication**.
-   A write is sent to one node (or a few). That node immediately confirms the write to the client.
-   In the background, the change is slowly propagated to all other replicas.
-   A read request might hit a replica that hasn't received the update yet, resulting in a stale read.

This trades:
- Correctness immediacy → availability & speed

---

### Advantages

- Very high availability
- Low latency
- Excellent horizontal scalability

---

### Disadvantages

- Stale reads
- Complex user-visible behavior
- Harder reasoning

---

### Where Eventual Consistency Is Acceptable

- Social feeds
- Likes, views
- Analytics
- Caches
- Notifications

Interview line:
> “Eventual consistency is acceptable for non-critical, user-facing data.”

---

## 1.7.5 Read-After-Write & Read-Your-Writes

These are **important sub-guarantees** of consistency.

---

### Read-After-Write Consistency

Guarantee:
> After writing data, a user can immediately read it.

Example:
- User posts a comment
- Refreshes page
- Sees their own comment

---

### How Systems Achieve This

-   **Sticky Sessions (Routing):** The load balancer is configured to send all requests from a specific user to the same server for a short period (e.g., 1 minute) after a write. Since that server handled the write, it has the fresh data.
-   **Reading from the Leader:** For a user who has just written data, subsequent read requests can be routed directly to the leader/primary replica, bypassing any potentially stale follower replicas.
-   **Client-Side Caching:** The application frontend can cache the user's new data (e.g., the new profile name) and display it immediately, while the backend propagates the change.
-   **Write-Through Caches:** If a user's data is in a cache, updating it there and in the database simultaneously ensures the next read from the cache is fresh.

### Advantages
-   **Great User Experience:** Prevents the most jarring type of stale data issue.
-   **Balanced Performance:** It's less expensive than full strong consistency but provides a better guarantee than pure eventual consistency.

---

## 1.7.6 Consistency in Caches

### Why Cache Consistency Is Hard

Caches:
- Are a separate, duplicated copy of data.
- Sit between your application and your primary data store (the "source of truth").
- By their nature, they introduce consistency challenges.

**Example Problem: Stale Data**
1.  A product's price is `$100`. This value is in the database and also in the Redis cache.
2.  A manager updates the price to `$90` in the database.
3.  A user requests the product page. The application checks the cache first, finds the price, and returns the stale value of `$100`. The user is shown the wrong price.

---

### Common Mitigations

- TTL (time-based expiry)
- Write-through caching
- Cache invalidation
- Versioned keys

1.  **Time-To-Live (TTL):** This is the simplest strategy. You set an expiration time on each cache key (e.g., 60 seconds).
    -   **How it works:** After 60 seconds, the cache automatically evicts the data. The next request will be a "cache miss," forcing the application to fetch the fresh data from the database.
    -   **Trade-off:** Data can be stale for up to the TTL duration. It's a simple way to accept and bound staleness.
2.  **Write-Through Caching:** Ensures the cache and database are always in sync on writes.
    -   **How it works:** When updating a product's price, the application writes the new value to the cache, and the cache itself is responsible for writing that value to the database. The operation only succeeds if both writes are successful.
    -   **Trade-off:** This adds latency to write operations, as you're writing to two systems.
3.  **Cache Invalidation:** Explicitly remove a key from the cache when its source data changes.
    -   **How it works:** When the manager updates the price to `$90` in the database, the application immediately sends a `DELETE` command to the cache for the corresponding product key. The next read will be a miss, fetching the new price.
    -   **Trade-off:** This is more complex to implement correctly. If the invalidation signal fails, the cache will hold stale data indefinitely (or until the TTL expires).
4.  **Versioned Keys:** Embed a data version in the cache key.
    -   **How it works:** Instead of `cache.get('product:123')`, the application fetches `cache.get('product:123:v2')`. When the product is updated, its version is incremented to `v3` in the database. The application now requests `product:123:v3`, which will result in a cache miss, forcing a fetch of the new version.

---

## 1.7.7 Consistency in Distributed Databases (High-Level)

### Common Techniques

#### 1. Leader-Based Replication (e.g., PostgreSQL, MySQL)
-   **How it works:** One database server is designated as the "leader" (or master). It is the only server that can accept `WRITE` operations. It logs these changes and replicates them to multiple "follower" (or slave) servers.
-   **Consistency:** You can achieve strong consistency by directing all `READ` requests to the leader as well. You can achieve eventual consistency (with higher read scalability) by directing reads to the followers, which may lag slightly behind the leader.

#### 2. Quorum Reads/Writes (e.g., Cassandra, DynamoDB)
-   **How it works:** There is no leader. Data is replicated across `N` nodes. When writing, the system waits for acknowledgement from `W` nodes. When reading, it queries `R` nodes.
-   **Consistency:** Consistency is tunable. If `W + R > N`, you have a **strong consistency** guarantee because the set of nodes you read from is guaranteed to overlap with the set of nodes you wrote to. For example, if N=3, W=2, and R=2, then `2+2 > 3`. Your read of 2 nodes must include at least one node that received the latest write. If `W + R <= N`, you get eventual consistency.

#### 3. Multi-Leader Replication (e.g., CockroachDB, some Cassandra setups)
-   **How it works:** Multiple nodes can accept writes, typically one in each geographic region. The leaders then replicate changes to each other.
-   **Consistency:** This model is complex because it must resolve **write conflicts**. What happens if a user in Europe and a user in the US update the same record at the same time? Systems use algorithms like "last write wins" (based on timestamps) or more advanced conflict-resolution data types (CRDTs) to solve this.

#### 4. Versioning & Timestamps (Vector Clocks)
-   **How it works:** Instead of just storing the data, the database also stores a version vector (e.g., `[ServerA: 3, ServerB: 5]`) with it. When a server updates the data, it increments its own counter.
-   **Consistency:** This allows the database to detect both stale data and write conflicts. If it sees two different versions of an object where neither is a clear ancestor of the other, it knows a conflict has occurred that must be resolved.

---

## 1.7.8 Choosing Consistency Level (Decision Framework)

To choose the right consistency level, ask these questions about your data and feature:

1.  **What is the business/user impact of stale data?**
    -   **Example (High Impact):** Showing a user the wrong bank balance or selling an out-of-stock item. This can lead to financial loss and loss of trust. **Requires Strong Consistency.**
    -   **Example (Low Impact):** A "like" counter on a social media post being off by a few counts for a minute. This has no real consequence. **Eventual Consistency is fine.**

2.  **Does the user expect to see their own changes immediately?**
    -   **Example (Yes):** A user updates their shipping address in their profile. If they refresh the page and see the old address, they will be confused. **Requires Read-Your-Writes Consistency.**
    -   **Example (No):** A user uploads a video. They understand that video processing takes time and don't expect it to be live instantly. **Eventual Consistency is acceptable.**

3.  **Is this data a source of truth, or is it derived/cached?**
    -   **Example (Source of Truth):** The `inventory` table in an e-commerce database. **Requires Strong Consistency.**
    -   **Example (Derived):** A cache entry holding the "Top 10 most popular products." This data is calculated from other sources and can be slightly stale. **Eventual Consistency is fine.**

---

### Consistency Checklist

For each feature or piece of data, ask:

| Question | If Yes... | Example |
| :--- | :--- | :--- |
| **1. Will stale data cause financial loss or security issues?** | Use **Strong Consistency**. | Bank Balance, Inventory |
| **2. Will users be confused if they don't see their own write?** | Use **Read-Your-Writes**. | Profile Updates, Comments |
| **3. Is the data non-critical and high-volume?** | Use **Eventual Consistency**. | Like Counts, Analytics |
| **4. Is this data just a temporary copy of other data?** | Use **Eventual Consistency**. | Caches |

Design rule:
> Start with eventual consistency as the default for scalability, and apply stronger guarantees only where absolutely necessary.

---

## 1.7.9 Consistency Anti-Patterns

Avoid:
-   **Strong Consistency Everywhere**
    -   **What it is:** Using strongly consistent databases (like default RDBMS) for every single piece of data, including like counts, logs, and user sessions.
    -   **Why it's bad:** This creates a slow, expensive, and unscalable system. The high coordination cost of strong consistency becomes a bottleneck for features that don't need it.
-   **Ignoring Stale Read Scenarios**
    -   **What it is:** Reading from a cache or a read replica without considering what happens if that data is out of date.
    -   **Why it's bad:** This leads to subtle and dangerous bugs. For example, an order processing service reads a cached (and old) shipping address, causing a package to be sent to the wrong location even after the user updated their profile.
-   **Not Documenting Consistency Guarantees**
    -   **What it is:** The backend team builds a service with eventual consistency, but the frontend and mobile teams assume all data is real-time.
    -   **Why it's bad:** This causes endless cross-team confusion and bugs. The frontend team reports "missing data" that isn't actually missing, just delayed. APIs should clearly document their consistency model.
-   **Mixing Consistency Levels Unknowingly**
    -   **What it is:** A single service call reads some data from a strongly consistent database and other data from an eventually consistent cache, then combines them.
    -   **Why it's bad:** This can create logically impossible states. For example, you might fetch an "order" record that exists but then fail to fetch its corresponding "order items" because the cache replica you hit hasn't received the update yet.

---

## Key Interview Takeaways

- Consistency = data visibility
- Always a trade-off
- Strong consistency for critical data
- Eventual consistency for scale
- Read-your-writes shows maturity
- Cache consistency is never free

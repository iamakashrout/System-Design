# Canonical Problem 1: URL Shortener (High-Level Design)

This document outlines the High-Level Design (HLD) for a scalable URL Shortener service (similar to bit.ly or tinyurl). It follows a structured approach: Requirements, Data Modeling, API Design, Architecture, Scaling, and Reliability.

---

## 1. Step 1: Clarify Requirements

Defining the scope is critical to prevent over-engineering. We must distinguish between what is essential for the Minimum Viable Product (MVP) and what can be added later.

### 1.1 Functional Requirements

**Core Features:**
1.  **Shortening:** The system must accept a long URL (e.g., `https://www.google.com/search?q=system+design`) and return a unique, short alias (e.g., `https://tiny.url/abc1234`).
2.  **Redirection:** When a user accesses the short alias, the system must redirect them to the original long URL.

**Out of Scope (for this design):**
*   Custom aliases (e.g., `tiny.url/my-custom-link`).
*   User accounts and authentication.
*   Link editing or deletion.
*   Expiration dates (links are permanent by default).

### 1.2 Non-Functional Requirements

These requirements dictate the architecture and infrastructure choices.

1.  **Scale:**
    *   **Write Volume:** 100 million new URLs generated per month. This translates to approximately **40 writes per second** (average), but we should design for peak bursts (e.g., **100-200 writes/sec**).
    *   **Read Volume:** 100:1 Read-to-Write ratio. This implies **4,000 reads per second** average, with peaks potentially reaching **10,000+ reads/sec**.
    *   **Storage:** If we store URLs for 5 years: 100M * 12 months * 5 years = **6 Billion URLs**.
2.  **Latency:**
    *   Redirection must be extremely fast. The target latency is **< 100ms** (excluding network travel time). Slow redirects negatively impact the user experience.
3.  **Availability:**
    *   The system requires high availability (99.99%). If the service is down, links across the internet stop working.
4.  **Consistency:**
    *   **Writes:** Strong consistency is required for ID generation (we cannot issue the same short code to two different URLs).
    *   **Analytics:** Eventual consistency is acceptable.

---

## 2. Core Entity & Data Model

We need to determine the best way to store the mapping between short codes and long URLs.

### 2.1 The Entity: URLMapping

The data structure is simple. We need a single core entity.

**Schema:**
*   **short_code** (Primary Key): The unique 7-character string (e.g., `abc1234`).
*   **original_url**: The destination URL (max 2048 characters).
*   **created_at**: Timestamp of creation.
*   **expiration_date**: (Optional) Timestamp for when the link expires.

### 2.2 Data Model Deduction
**Why NoSQL (Key-Value) over Relational (SQL)?**
1.  **Access Pattern:** The dominant operation is a simple key-value lookup: `GET original_url WHERE short_code = X`.
2.  **Relationships:** There are no complex relationships between data entities (no joins required).
3.  **Scale:** We need to store billions of rows. Key-Value stores (like DynamoDB, Cassandra, or Riak) or Document stores (MongoDB) scale horizontally more easily than traditional RDBMS for this specific use case.

**Decision:** We will use a **NoSQL Key-Value store** or a highly scalable Document store.

---

## 3. API Design

We will use RESTful principles for the API. It should be predictable and lightweight.

### 3.1 Create Short URL
**Endpoint:** `POST /api/v1/urls`

**Request Body:**
```json
{
  "original_url": "https://www.example.com/very-long-url"
}
```

**Response (201 Created):**
```json
{
  "short_code": "abc1234",
  "short_url": "https://tiny.url/abc1234"
}
```

### 3.2 Redirect
**Endpoint:** `GET /{short_code}`

**Response:**
*   **HTTP 302 (Found):** The server returns a 302 status code with the `Location` header set to the `original_url`.

**Why HTTP 302 and not 301?**
*   **301 (Moved Permanently):** The browser caches the redirect. Subsequent requests for the same short code happen entirely in the browser; the request never reaches our server. This reduces server load but **breaks analytics** (we cannot track clicks).
*   **302 (Found / Temporary):** The browser always hits our server to get the new location. This allows us to track click analytics, though it increases server load. Given the requirements, **302 is the correct choice**.

---

## 4. Baseline Architecture

Before scaling, we define the logical flow of data through the system components.

### 4.1 Components
1.  **Client:** The user's browser or mobile app.
2.  **Load Balancer (LB):** Distributes incoming HTTP traffic across multiple application servers.
3.  **Web Servers (Stateless):** Runs the application logic (validating URLs, generating codes).
4.  **Database:** Stores the persistent mapping of `short_code` -> `original_url`.

### 4.2 Data Flow
1.  **Write Flow:** Client sends `POST` -> LB -> Web Server -> Server generates ID -> Saves to DB -> Returns Short URL.
2.  **Read Flow:** Client sends `GET` -> LB -> Web Server -> Server looks up ID in DB -> Returns 302 Redirect.

---

## 5. Scaling the Read Path

**Problem:** At high scale (thousands of reads/sec), hitting the database for every redirect is inefficient and creates a bottleneck.

**Solution: Caching (Cache-Aside Pattern)**
We introduce a distributed cache (e.g., Redis or Memcached) between the Web Servers and the Database.

### 5.1 Detailed Flow
1.  **Check Cache:** When a redirect request comes in, the Web Server first checks Redis for the `short_code`.
2.  **Cache Hit:** If found, return the redirect immediately. (Latency: < 5ms).
3.  **Cache Miss:** If not found, query the Database.
4.  **Populate Cache:** Save the result from the DB into Redis so subsequent requests are fast.
5.  **Return:** Send the response to the user.

### 5.2 Cache Eviction Policy
*   **LRU (Least Recently Used):** We should use an LRU policy. Popular links stay in memory; unused links are removed to save space.
*   **TTL (Time-to-Live):** We can set a TTL (e.g., 24 hours) to ensure data doesn't stay stale forever, though URL mappings rarely change.

---

## 6. Scaling the Write Path

**Problem:** How do we generate a unique, short (e.g., 7-character) string efficiently without collisions?

### 6.1 Strategy: Key Generation Service (KGS)
Generating random strings and checking the DB for duplicates is slow and prone to race conditions. A better approach is to pre-generate unique tokens.

**How it works:**
1.  **Offline Generation:** A standalone service generates random 7-character Base62 strings (A-Z, a-z, 0-9) and stores them in a "Unused Tokens" database table.
2.  **Fetching Tokens:** When a Web Server starts up, it requests a batch of tokens (e.g., 1,000) from the KGS.
3.  **In-Memory Assignment:** The Web Server holds these 1,000 tokens in memory. When a user creates a URL, the server takes the next available token from its local memory and uses it.
4.  **No Collisions:** Since the KGS ensures it never gives the same batch of tokens to two different servers, there is zero risk of collision and no need for a database "check if exists" query on the write path.

**Trade-off:** If a Web Server crashes, the unused tokens in its memory are lost. This is acceptable because the space of 7-character Base62 strings is massive (3.5 trillion combinations), so losing a few thousand is negligible.

---

## 7. Database Scaling Strategy

Even with caching, storing billions of URLs requires a sharded database architecture.

### 7.1 Partitioning (Sharding)
We need to split the data across multiple database servers.

**Partition Key:** `short_code`
**Strategy:** Hash-Based Partitioning.
*   Formula: `Shard_ID = hash(short_code) % Number_of_Shards`
*   **Reasoning:** This distributes URLs evenly across all database nodes. Since we always look up by `short_code`, we can calculate exactly which shard holds the data without querying a central directory.

### 7.2 Replication
To ensure high availability and durability:
*   Each shard will have a **Primary-Replica** setup.
*   **Writes** go to the Primary.
*   **Reads** (on cache miss) can go to Replicas.
*   If the Primary fails, a Replica is promoted to Primary automatically.

---

## 8. Handling Hot Keys

**Scenario:** A celebrity tweets a link. That specific `short_code` receives millions of requests in seconds.

**Problem:** This traffic spike could overwhelm the specific shard or cache node responsible for that key.

**Solution: Multi-Level Caching**
1.  **CDN (Content Delivery Network):** We can cache the HTTP 302 response at the edge (Cloudflare/Akamai). This is the most effective defense, as traffic stops before reaching our data center.
2.  **Local Cache:** Web Servers can cache very hot keys in their local RAM (e.g., Guava cache). This avoids network calls to the Redis cluster.

---

## 9. Reliability and Availability

To ensure the system stays up:

1.  **Redundancy:** Every component (Web Servers, Cache nodes, DB nodes) must have multiple instances.
2.  **Rate Limiting:** Implement rate limiting (e.g., Token Bucket algorithm) at the Load Balancer or API Gateway level to prevent abuse (DDoS) or buggy clients from taking down the service.
3.  **Circuit Breakers:** If the Database becomes unresponsive, the Web Servers should fail fast rather than piling up requests and crashing the entire fleet.

---

## 10. Analytics Handling

We want to track how many times a link was clicked, user location, and browser type.

**Constraint:** Analytics writing must not slow down the user's redirect.

**Solution: Asynchronous Processing**
1.  **Redirect First:** When a request comes in, the Web Server looks up the URL and returns the 302 response immediately.
2.  **Emit Event:** *After* or *in parallel* to sending the response, the Web Server sends a "Click Event" message to a Message Queue (e.g., Kafka).
3.  **Process Later:** A separate "Analytics Worker" service reads from Kafka, aggregates the data (e.g., increments counters), and writes it to an OLAP database (like ClickHouse or a time-series DB).

---

## 11. Trade-offs and Reasoning

1.  **Consistency vs. Availability:**
    *   We prioritize **Availability** (AP in CAP theorem). It is better to serve a slightly stale redirect (rare) than to fail completely.
    *   For ID generation, we prioritize Consistency to ensure no two URLs get the same code.

2.  **302 vs. 301 Redirect:**
    *   We chose **302** to enable analytics, accepting the trade-off of higher server load.

3.  **Pre-generated Keys vs. Hashing:**
    *   We chose **Pre-generated Keys (KGS)**. While it adds a new component (complexity), it completely removes the need for collision checking on the write path, ensuring predictable low-latency writes.

---

## 12. Extensions and Multi-Region Design

**Extensions:**
*   **Custom Aliases:** Requires a separate check in the DB to ensure the custom string isn't already taken.
*   **Expiration:** A background "cleanup" job can scan the DB for expired links and delete them during off-peak hours.

**Multi-Region Design:**
*   **Geo-DNS:** Route users to the nearest data center (e.g., US-East vs. EU-West).
*   **Database Replication:** Use multi-master replication or active-passive with read replicas in every region.
*   **Global Cache:** CDNs handle the majority of global read traffic.

---

## 13. Final Production Architecture

### Summary
The final system is a read-heavy, highly available distributed system. It leverages **Caching** and **CDNs** to handle read traffic, **Sharding** to handle data volume, and **Asynchronous Queues** to handle analytics without impacting latency.

### Diagram

```text
                                  [CDN]
                                    ^
                                    |
[Client] <---> [DNS] <---> [Load Balancer]
                                    |
                                    v
                           [Web Server Cluster]
                           /        |         \
                          /         |          \
             (Cache Hit) /          | (Async)   \ (Cache Miss)
                        v           v            v
                 [Redis Cluster]  [Kafka]    [Database Cluster]
                                    |        (Sharded Key-Value)
                                    v
                            [Analytics Worker]
                                    |
                                    v
                             [OLAP Database]
```
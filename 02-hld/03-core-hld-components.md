# 2.3 Core High-Level System Design Components

Mental model: Stop thinking in features. Start thinking in infrastructure primitives.

## 1. Core HLD Building Blocks (Mental Model)

Almost every large-scale system is composed of infrastructure primitives:

- Load Balancers
- Stateless Services
- Databases
- Caches
- Message Queues / Streams
- Storage Systems (Blob/Object/File)

> Everything else (Kubernetes, microservices, service mesh) is orchestration and glue.

### Senior Insight

A senior engineer sees systems as:

Traffic → Compute → Storage → Async pipelines

## 2. Load Balancers (LBs)

### What Load Balancers Do
Load balancers act as the "traffic cop" sitting in front of your servers. They are the entry point for all traffic and are critical for availability and scalability.

- **Traffic Distribution:** Spreads incoming requests across a pool of healthy servers to prevent overloading any single resource.
- **Health Checks:** Regularly pings backend servers (e.g., `/health` endpoint). If a server fails, the LB stops sending traffic to it (Self-Healing).
- **SSL Termination:** Decrypts incoming HTTPS traffic so backend servers don't spend CPU cycles on encryption/decryption.
- **Session Persistence (Sticky Sessions):** Ensures a specific user always connects to the same server (though stateless architectures avoid this).

### Without Load Balancers

- Single server bottleneck
- Manual failover
- No elasticity
- Downtime during deployments

### Algorithms & Strategies
- **Round Robin:** Cycles through servers sequentially (A, B, C, A...). Simple, assumes equal server power.
- **Least Connections:** Sends traffic to the server with the fewest active connections. Ideal for long-lived connections (e.g., WebSockets).
- **Consistent Hashing:** Maps users to servers based on a hash of their IP/ID. Useful for caching layers to minimize reshuffling when scaling.

### Types of Load Balancers (HLD-Level)

#### Layer 4 Load Balancer (Transport Level)

- **Operates at:** TCP/UDP (OSI Layer 4).
- **Behavior:** Routes packets based on IP and Port. Does *not* inspect packet contents.
- **Pros:** Extremely fast, handles millions of connections.
- **Cons:** Cannot route based on URL or Headers.
- **Examples:** AWS Network Load Balancer (NLB), LVS.
- **Use cases:** Database proxies, Raw TCP services, Real-time gaming.

#### Layer 7 Load Balancer (Application Level)

- **Operates at:** HTTP/HTTPS (OSI Layer 7).
- **Behavior:** Inspects the request (URL, Headers, Cookies).
- **Capabilities:**
    - **Path-based Routing:** `/api/users` -> User Service, `/api/orders` -> Order Service.
    - **Rate Limiting:** Block IPs sending too many requests.
    - **Auth Offloading:** Verify JWT tokens at the gateway.
- **Examples:** AWS Application Load Balancer (ALB), NGINX, HAProxy.
- **Use cases:** API gateways, Microservices routing, Canary deployments.

### Senior Trade-offs

| Type | Pros | Cons | Use Case |
| :--- | :--- | :--- | :--- |
| **L4** | Ultra-low latency, high throughput | "Dumb" routing, no content awareness | DB Proxies, Real-time gaming, TCP streams |
| **L7** | Smart routing, security features | Higher CPU usage, slight latency | Microservices Gateway, Web Apps, A/B Testing |

### Stateful vs Stateless LB

- **Stateless LB** → preferred (config stored centrally)
- **Stateful LB** → session-aware (rare, harder to scale)

## 3. Stateless Application Services

### The Concept: Cattle vs. Pets
- **Pets (Stateful):** Servers with unique names (db-master-01). If they get sick, you nurse them back to health. You care about them individually.
- **Cattle (Stateless):** Servers with numbers (web-001 to web-999). If one gets sick, you terminate it and spin up a new one. You care about the *herd*.

### Why Stateless is Sacred
In a stateless architecture, the server retains **no data** (state) from previous requests. Every request contains all necessary context (e.g., via a JWT token).

- **Horizontal Scaling:** You can go from 1 to 1000 servers instantly because any server can handle any request.
- **Resilience:** If a server crashes, the Load Balancer just retries the request on another server. No user data is lost.
- **Zero-Downtime Deployment:** You can kill old version servers and start new ones without worrying about active user sessions.

### Managing State in a Stateless World
If the app server is stateless, where does the data go?
1.  **User Session:** Stored in a distributed cache (Redis) or the client (JWT).
2.  **Application Data:** Stored in the Database.
3.  **File Uploads:** Stored in Object Storage (S3), not the local disk.

### Typical Pattern
```text
Client (JWT) → LB → Stateless API (Validates JWT) → Redis (Fetch Session) → DB (Fetch Data)
```

### Where State Lives

- Databases
- Caches
- Object storage
- Queues

> Never inside API servers.

### Senior Rule

State belongs in dedicated systems, not app servers.

## 4. Databases (Primary Source of Truth)

The database is often the bottleneck of a system. Choosing the right type is the most critical HLD decision.

### Core Roles

- Durable storage
- Transactional correctness
- Querying & indexing

### Database Categories (HLD-Level)

#### 1. Relational Databases (RDBMS / SQL)
- **Data Model:** Tables with rows and columns. Strict schema.
- **Key Feature:** ACID Transactions (Atomicity, Consistency, Isolation, Durability).
- **Scaling:** Vertical (bigger machine) is easy. Horizontal (sharding) is hard.
- **Examples:** PostgreSQL, MySQL, Oracle, SQL Server.
- **Use Cases:**
    - **Financial Systems:** Money transfers must be atomic.
    - **User Management:** Structured data with relationships.
    - **Inventory:** Strict consistency required to prevent overselling.

#### 2. NoSQL Databases
Designed for scale and flexibility, often sacrificing ACID for BASE (Basically Available, Soft state, Eventual consistency).

**a. Key-Value Stores**
- **Data Model:** Hash map (Key -> Value). O(1) access.
- **Examples:** Redis, DynamoDB, Memcached.
- **Use Cases:** Caching, Sessions, Shopping Carts.

**b. Document Stores**
- **Data Model:** JSON-like documents. Flexible schema.
- **Examples:** MongoDB, Couchbase.
- **Use Cases:** Content Management (CMS), Catalogs, Rapid Prototyping.

**c. Wide-Column Stores**
- **Data Model:** 2D Key-Value. Optimized for massive write throughput.
- **Examples:** Cassandra, HBase.
- **Use Cases:** Time-Series Data (IoT), Activity Feeds, Write-Heavy Logs.

**d. Graph Databases**
- **Data Model:** Nodes and Edges. Optimized for traversing relationships.
- **Examples:** Neo4j, Amazon Neptune.
- **Use Cases:** Social Networks, Recommendation Engines, Fraud Detection.

### HLD Talking Points

- Primary + replicas
- Read replicas for read scaling
- Sharding for write scaling
- Backup and disaster recovery

## 5. Caches (Latency and Cost Weapon)

### Why Caches Exist
- **Speed:** RAM (nanoseconds) is orders of magnitude faster than Disk (milliseconds).
- **Cost:** Reduces load on expensive databases, allowing you to use smaller DB instances.

### Where Caches Sit

- Client-side (browser/mobile)
- CDN (edge cache)
- Load Balancer / Gateway
- Application Cache (Redis/Memcached)
- DB query cache

### What to Cache

- Read-heavy data
- Hot keys
- Expensive computations
- Aggregated results

### Cache Eviction Policies
When the cache is full, what do we delete?
- **LRU (Least Recently Used):** Delete the item that hasn't been used for the longest time. (Most common).
- **LFU (Least Frequently Used):** Delete the item with the fewest hits.
- **TTL (Time To Live):** Automatically delete items after X seconds (e.g., 5 minutes).

### Cache Patterns (Name These)

- **Cache-Aside (Lazy Loading)**
    - App checks cache → DB on miss → populate cache
    - Most common
- **Read-Through Cache**
    - Cache automatically loads from DB
- **Write-Through Cache**
    - Write to cache + DB synchronously
- **Write-Back Cache**
    - Write to cache first → async DB write

### Senior Trade-offs

- Cache staleness
- Invalidation complexity
- Cache stampede
- Cold cache on restart

## 6. Message Queues & Streams (Async Backbone)

### Why Queues Exist
Decoupling is the primary goal. If Service A talks directly to Service B, A is broken when B is broken. With a queue, A sends a message and moves on.

### Core Benefits
- **Throttling (Load Leveling):** If 10,000 users sign up in 1 second, the queue buffers them. The email worker processes them at 50/sec. The system survives.
- **Reliability:** If the worker crashes, the message stays in the queue until a new worker picks it up.
- **Decoupling:** The producer doesn't need to know who the consumer is.

### Common Use Cases
- Email sending
- Notifications
- Video processing
- Logging & analytics
- Payment workflows

### Queue vs Stream (Detailed Distinction)

#### Message Queue (Task-Based)
- **Model:** Point-to-Point.
- **Behavior:** A message is a task. Once a worker processes it, it is **deleted**.
- **Examples:** RabbitMQ, AWS SQS, ActiveMQ.
- **Use Cases:**
    - **Image Resizing:** User uploads -> Queue -> Worker resizes.
    - **Email Sending:** Signup -> Queue -> Worker sends email.
    - **Order Processing:** Checkout -> Queue -> Fulfillment Service.

#### Event Stream (Log-Based)
- **Model:** Publish-Subscribe (Pub/Sub).
- **Behavior:** A message is an event (fact). It is appended to a log. It is **retained** for a period (e.g., 7 days). Multiple consumers can read the same message.
- **Examples:** Apache Kafka, AWS Kinesis.
- **Use Cases:**
    - **Analytics:** Clickstream -> Kafka -> Data Warehouse AND Real-time Dashboard.
    - **Event Sourcing:** Replaying history to rebuild system state.
    - **Log Aggregation:** Collecting logs from all servers.

### Senior Interview Statement

> "I use Queues for async tasks where the action must happen once (like sending an email). I use Streams for data pipelines where multiple services might need to react to the same event (like a 'UserCreated' event triggering both 'Welcome Email' and 'Analytics')."

## 7. Storage Systems (Blob / Object / File)

### Why Separate from DB
Databases are expensive block storage optimized for structured querying. Storing large binary blobs (images, PDFs) in a DB ("bloat") ruins performance and increases backup costs.

### Storage Types

#### 1. Object Storage (Blob)
- **Concept:** Flat structure. Files are objects with an ID (Key) and Metadata. Accessed via HTTP API (REST).
- **Characteristics:** Highly durable, infinite scale, cheaper than block storage. Immutable (usually overwrite, not edit).
- **Examples:** AWS S3, Google Cloud Storage, Azure Blob.
- **Use Cases:** User Media (profile pics), Backups, Static Websites.

#### 2. Block Storage
- **Concept:** Acts like a raw hard drive attached to a server.
- **Characteristics:** Low latency, high IOPS. Expensive.
- **Examples:** AWS EBS, Local SSDs.
- **Use Cases:** Database Files, OS Boot Volumes.

#### 3. File Storage (Network File System)
- **Concept:** Hierarchical directory tree shared across multiple servers.
- **Characteristics:** Shared access, standard file protocols (NFS/SMB).
- **Examples:** AWS EFS, Google Filestore.
- **Use Cases:** Legacy Apps, Content Repositories.

## 8. Canonical High-Level Architecture Template

```text
Client
   ↓
Load Balancer
   ↓
Stateless Service Tier
   ↓        ↓         ↓
 Cache     Database   Queue
                       ↓
                    Async Workers
```

**Plus:**
- Object storage for large files
- Monitoring & logging systems

## 9. Senior-Level Design Principles (Memorize)

### Applied Example: Designing a "Flash Sale" System
Let's apply these principles to a system selling 1000 iPhones at 50% off.

#### Principle 1 — Stateless Compute, Stateful Storage
- **Design:** We spin up 100 Web Servers to handle the traffic spike. They hold no inventory data.
- **Why:** If a server crashes under load, the user is seamlessly routed to another. We can auto-scale from 10 to 100 servers in minutes.

#### Principle 2 — Sync for User Path, Async for Side Effects
- **Design:**
    1.  **Sync:** User clicks "Buy". We validate stock and reserve the item. Return "Success" immediately.
    2.  **Async:** We publish a `OrderCreated` event to a Queue.
    3.  **Worker:** Picks up the event to: Charge Credit Card, Send Email, Update Analytics.
- **Why:** If the Email service is slow, the user doesn't see a spinner. The checkout feels instant.

#### Principle 3 — Cache Aggressively, Invalidate Thoughtfully
- **Design:** The "Product Details" page (Title, Image, Description) is cached in CDN and Redis.
- **Why:** 99% of traffic is just *viewing* the item. Hitting the DB for static text is wasteful.
- **Invalidation:** When the sale starts, we don't need to invalidate the description, only the "Buy Button" status.

#### Principle 4 — Scale Reads with Replicas, Writes with Sharding
- **Design:**
    - **Reads:** 1 Million users refreshing the page. We serve this from Read Replicas and Cache.
    - **Writes:** 10,000 users clicking buy. A single DB might lock up. We might shard the inventory by Region or use a high-write DB like DynamoDB.

#### Principle 5 — Every Component Must Solve a Bottleneck
- **Design:** Do we need Kafka?
    - *Junior:* "Yes, it's cool."
    - *Senior:* "For 1000 items, a simple SQS queue is enough. Kafka adds maintenance overhead. We only add Kafka if we need to stream these events to 5 different downstream systems."

### Final Senior Mental Model

Start simple → find bottleneck → add only what fixes it.

### Interview Cheat Code Sentence

Memorize this:

> "My design uses stateless services behind a load balancer, primary-replica databases with sharding for writes, Redis for caching hot reads, and queues for async workflows like notifications and analytics."
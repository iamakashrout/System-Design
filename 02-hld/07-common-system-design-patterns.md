# 2.7 Common System Design Patterns (HLD Patterns)

> *Patterns are compressed experience.*
> *Naming the right pattern in interviews = instant senior credibility.*

## What are System Design Patterns?

System design patterns are **reusable architectural solutions** to recurring problems in distributed systems. Just as a chef uses established recipes to ensure a dish tastes good every time, software engineers use design patterns to ensure systems are scalable, reliable, and maintainable.

**Why are they needed?**
1.  **Shared Vocabulary:** Instead of explaining a complex error-handling logic for ten minutes, you can simply say, "We'll use a Circuit Breaker," and other engineers immediately understand the mechanism.
2.  **Proven Reliability:** These patterns have been battle-tested by companies like Netflix, Amazon, and Google. You don't need to reinvent the wheel.
3.  **Handling Complexity:** Distributed systems introduce problems that don't exist in single-server apps (network latency, partial failures, consistency issues). Patterns provide structured ways to handle these specific challenges.

---

# 1. Monolith vs. Microservices (The Foundation)

This is the most fundamental architectural decision you will make. It dictates how your code is organized, deployed, and scaled.

## 1.1 Monolithic Architecture

### What It Is
A **Monolith** is a single, unified software application where all components—authorization, business logic, database access, and UI handling—are packaged and deployed together as a single unit.

**Analogy:** Think of a **Swiss Army Knife**. It has a knife, a screwdriver, a scissor, and a file all in one tool. If you want to use it, you carry the whole thing.

### Pros
*   **Simplicity:** Easy to develop, test, and deploy initially. Everything is in one place.
*   **Performance:** Internal function calls are faster than network calls between services.
*   **Easy Debugging:** You can step through the code from the API endpoint down to the database in one IDE session.
*   **Operational Simplicity:** You only have one application to monitor and scale.

### Cons
*   **Tight Coupling:** A bug in the "Recommendations" module could crash the entire application, including "Checkout."
*   **Scaling Limitations:** You cannot scale just the "Image Processing" part; you have to duplicate the entire application, wasting resources.
*   **Slow Velocity at Scale:** With 100 engineers working on the same codebase, merge conflicts become a nightmare, and build times skyrocket.
*   **Technology Lock-in:** It is very hard to change the core language or framework once built.

### Use Cases
*   Early-stage startups (MVP).
*   Internal tools with predictable load.
*   Applications with small teams (2-5 developers).

---

## 1.2 Microservices Architecture

### What It Is
**Microservices** architecture structures an application as a collection of small, autonomous services, modeled around a **business domain**. Each service runs in its own process and communicates with others via lightweight mechanisms (usually HTTP/REST or gRPC). Crucially, **each service owns its own database**.

**Analogy:** Think of a **Professional Kitchen**. You have a Pastry Chef, a Saucier, and a Grill Chef. They work independently but coordinate to deliver a meal. If the Pastry Chef is slow, the Grill Chef can still cook steaks.

### Pros
*   **Independent Scaling:** If "Video Processing" is heavy, you can add 50 servers for that service alone without touching the "User Profile" service.
*   **Fault Isolation:** If the "Reviews" service crashes, the "Product" page still loads (just without reviews).
*   **Technology Freedom:** The Data Science team can use Python, while the Backend team uses Go or Java.
*   **Organizational Scale:** Teams can work independently on their services without blocking each other.

### Cons
*   **Complexity:** You are trading code complexity for infrastructure complexity. You now have to manage network latency, distributed transactions, and service discovery.
*   **Data Consistency:** Keeping data in sync across multiple databases is hard (Eventual Consistency).
*   **Operational Overhead:** Requires advanced DevOps (Kubernetes, CI/CD, Observability) to manage hundreds of services.

### Use Cases
*   Large-scale consumer apps (Netflix, Uber, Amazon).
*   Systems with multiple distinct business domains.
*   Large engineering organizations (50+ developers).

---

## 1.3 The Modular Monolith (The Middle Ground)

### What It Is
A **Modular Monolith** is a single deployable unit (like a standard monolith), but the code is structured into distinct, isolated modules with strict boundaries. Module A cannot import code from Module B directly; it must use a public interface.

### When is it useful?
It is the **ideal starting point** for most serious projects. It gives you the code organization of microservices (separation of concerns) with the operational simplicity of a monolith (single deployment). If a specific module eventually needs to scale independently, it is easy to extract it into a microservice because the boundaries are already defined.

---

# 2. CQRS (Command Query Responsibility Segregation)

## What It Is
CQRS is a pattern that separates the **read** and **write** operations for a data store. Instead of having a single model that handles both, you split the system into two parts:
1.  **Command Side (Write):** Handles `Create`, `Update`, `Delete`. Focuses on business logic and data validation.
2.  **Query Side (Read):** Handles `Read`. Focuses on fetching data quickly.

## How It Works
1.  A user performs an action (e.g., "Book Flight"). This is a **Command**.
2.  The Command Service validates the rule and writes to the **Write Database** (usually a normalized SQL DB).
3.  The system publishes an event (e.g., `FlightBooked`).
4.  A separate process listens to this event and updates a **Read Database** (usually a denormalized NoSQL DB or Cache).
5.  When a user views their bookings, the **Query Service** reads from the fast Read Database.

## When to Use
*   **High Read/Write Disparity:** When you have 1,000 reads for every 1 write (e.g., Social Media feeds).
*   **Complex Views:** When the read patterns require joining 10 tables, but the write patterns are simple. You can pre-calculate the "View" on the read side.

## Pros
*   **Independent Scaling:** You can have 1 Write server and 50 Read servers.
*   **Optimized Schemas:** The write DB is optimized for data integrity (3rd Normal Form), while the read DB is optimized for speed (JSON documents).
*   **Security:** You can lock down the write side strictly while keeping the read side more open.

## Cons
*   **Eventual Consistency:** The read side might be a few milliseconds behind the write side.
*   **Complexity:** You now have to maintain two databases and the synchronization logic between them.

---

# 3. Event-Driven Architecture (EDA)

## What It Is
In an Event-Driven Architecture, services communicate by emitting **events** (facts about what happened) rather than calling each other directly.

**Analogy:**
*   **Request/Response (HTTP):** You walk into a coffee shop, order a latte, and stand at the counter staring at the barista until they hand it to you. You are "blocked."
*   **Event-Driven:** You place an order. The cashier shouts "Latte for Order 10!" (Event). You sit down. The barista hears the event and starts making coffee. When done, they shout "Order 10 Ready!" (Event). You hear it and pick it up.

## How It Works
1.  **Producer:** Service A completes a task and publishes an event (e.g., `UserSignedUp`) to an **Event Broker** (like Kafka or RabbitMQ).
2.  **Broker:** Stores the event and routes it.
3.  **Consumer:** Service B, Service C, and Service D all subscribe to `UserSignedUp`. They react independently (Send Welcome Email, Create Wallet, Update Analytics).

## Examples
*   **E-commerce:** `OrderPlaced` event triggers `InventoryService` (reserve stock), `PaymentService` (charge card), and `NotificationService` (email user).

## Pros
*   **Decoupling:** The Producer doesn't know who is listening. You can add new consumers without changing the producer.
*   **Asynchronous:** The producer doesn't wait for the consumers. It's fast.
*   **Throttling:** If the consumers are slow, the events just pile up in the broker. The system doesn't crash.

## Cons
*   **Complexity:** Harder to trace the flow of a request ("Where did this data come from?").
*   **Eventual Consistency:** Data takes time to propagate.

---

# 4. Saga Pattern (Distributed Transactions)

## The Problem
In Microservices, a single business process (like "Book a Trip") spans multiple services (Flight, Hotel, Car). You cannot use a traditional database transaction (ACID) because the data lives in different databases. If the Hotel booking fails, you must undo the Flight booking.

## The Solution
A **Saga** is a sequence of local transactions. Each local transaction updates the database and publishes an event or message to trigger the next local transaction in the saga. If a local transaction fails, the saga executes a series of **compensating transactions** that undo the changes made by the preceding local transactions.

## Types of Sagas

### 1. Choreography (Event-based)
Services talk to each other via events. There is no central coordinator.
*   **Flow:** `Order Service` emits `OrderCreated` → `Payment Service` listens, charges card, emits `PaymentProcessed` → `Inventory Service` listens, reserves stock.
*   **Pros:** Simple, loose coupling.
*   **Cons:** Hard to visualize complex flows; cyclic dependencies risk.

### 2. Orchestration (Command-based)
A central **Orchestrator** (a specific service) tells participants what to do.
*   **Flow:** `Order Orchestrator` calls `Payment Service`. If success, it calls `Inventory Service`. If `Inventory` fails, the Orchestrator calls `Payment Service` to **Refund**.
*   **Pros:** Central logic, easy to manage complex flows.
*   **Cons:** The Orchestrator can become a bottleneck or a "God Service."

## Use Cases
*   E-commerce checkout.
*   Travel booking systems.
*   Financial money transfers across banks.

---

# 5. Backpressure Pattern

## What It Is
Backpressure is a mechanism for a consumer to signal to a producer that it is overwhelmed and cannot handle more data. It prevents a fast producer from drowning a slow consumer.

**Analogy:** Putting your hand up to say "Stop!" when someone is throwing balls at you faster than you can catch them.

## How It Works
1.  **Buffer Filling:** The consumer has a queue of incoming tasks.
2.  **Signal:** When the queue fills up (e.g., 80% full), the consumer signals the producer.
3.  **Action:** The producer slows down, pauses, or drops requests.

## Strategies
*   **Control Flow (TCP):** The receiver advertises a "Window Size." If it drops to 0, the sender stops sending.
*   **Blocking:** The producer is blocked from writing to the queue until space opens up.
*   **Dropping (Load Shedding):** The consumer simply drops new requests and returns a `503 Service Unavailable` error.

## Use Cases
*   Video streaming (client buffer full → server pauses).
*   Log ingestion pipelines.
*   Protecting databases from massive write spikes.

---

# 6. Rate Limiting Pattern

## What It Is
Rate limiting controls the number of requests a user or client can send to a system within a specific time period. It protects your system from abuse (DDoS) and ensures fair usage.

## Algorithms

### 1. Token Bucket
*   **Concept:** A bucket holds tokens. Tokens are added at a fixed rate (e.g., 10 per second). Each request consumes a token. If the bucket is empty, the request is rejected.
*   **Feature:** Allows for **bursts** of traffic (you can use all accumulated tokens at once).
*   **Use Case:** General API rate limiting where short bursts are okay.

### 2. Leaky Bucket
*   **Concept:** Requests enter a bucket and "leak" out (are processed) at a constant rate. If the bucket overflows, new requests are discarded.
*   **Feature:** Smooths out traffic. It converts bursty traffic into a steady stream.
*   **Use Case:** Writing to a database or a system that needs a stable load.

### 3. Fixed Window Counter
*   **Concept:** Counts requests in a fixed time window (e.g., 10:00 to 10:01). If count > limit, reject. Reset at 10:01.
*   **Issue:** **Edge case spikes.** If a user sends 100 requests at 10:00:59 and 100 at 10:01:01, they sent 200 requests in 2 seconds, potentially overloading the system.

### 4. Sliding Window Log/Counter
*   **Concept:** Similar to Fixed Window, but the window "slides" smoothly. It calculates the rate based on the *exact* past minute, not the clock minute.
*   **Feature:** Most accurate, prevents the edge-case spike.
*   **Use Case:** Strict API quotas.

---

# 7. Circuit Breaker Pattern

## What It Is
The Circuit Breaker pattern prevents an application from repeatedly trying to execute an operation that's likely to fail. It wraps a protected function call in a circuit breaker object that monitors for failures.

**Analogy:** Electrical circuit breakers in your house. If there is a short circuit (fault), the breaker flips (Open) to stop the flow of electricity and prevent a fire.

## States
1.  **Closed (Normal):** Requests pass through. The system counts errors.
2.  **Open (Tripped):** If the error rate exceeds a threshold (e.g., 50%), the breaker opens. All requests fail immediately (Fail Fast) without calling the downstream service. This gives the failing service time to recover.
3.  **Half-Open (Recovery):** After a timeout, the breaker allows a limited number of "test" requests. If they succeed, it resets to **Closed**. If they fail, it goes back to **Open**.

## Use Cases
*   Preventing **Cascading Failures**.
*   Wrapping calls to external APIs (Payment Gateways, 3rd party integrations).
*   Database connections.

---

# 8. Bulkhead Pattern

## What It Is
The Bulkhead pattern isolates elements of an application into pools so that if one fails, the others will continue to function.

**Analogy:** A ship's hull is divided into watertight compartments (bulkheads). If the hull is breached in one section, only that section floods. The ship stays afloat.

## How It Works
You partition resources (threads, connection pools, memory) for different services.
*   **Without Bulkhead:** A single thread pool handles all requests. If "Image Processing" hangs, it consumes all threads. "User Login" requests wait for threads and eventually time out. The whole app dies.
*   **With Bulkhead:** You assign 10 threads to "Image Processing" and 50 to "User Login." If "Image Processing" hangs, it only uses its 10 threads. "User Login" continues to work fine.

## Use Cases
*   Isolating critical services (Login, Checkout) from non-critical ones (Recommendations, Reviews).
*   Multi-tenant architectures (preventing one heavy tenant from starving others).

---

# 9. Cache-Aside Pattern (Lazy Loading)

## What It Is
The most common caching pattern where the **application code** is responsible for managing the cache.

## How It Works
1.  **Read:** The application checks the Cache.
    *   **Hit:** Return data.
    *   **Miss:** Application queries the Database, returns the data to the user, and **writes** the data to the Cache for next time.
2.  **Write:** The application writes to the Database and then **invalidates** (deletes) the corresponding entry in the Cache.

## Pros
*   **Resilient:** If the cache fails, the system still works (just slower) by hitting the DB.
*   **Data Model:** Cache data structure can be different from DB structure.
*   **Cost:** Only requested data is cached (no waste).

## Cons
*   **Stale Data:** There is a small window where data in the DB is updated but the cache hasn't been invalidated yet.
*   **Initial Latency:** The first request is always slow (Cache Miss).

---

# 10. Write-Ahead Log (WAL) Pattern

## What It Is
WAL is a standard method for ensuring data integrity. The rule is: **Modifications are written to a log file on disk *before* they are applied to the actual data pages.**

## How It Works
1.  System receives a write request.
2.  It appends the command to the end of a log file (sequential write = fast).
3.  It acknowledges "Success" to the user.
4.  Later (asynchronously), it updates the complex database structures (indexes, tables).

## Why is it needed? (Durability)
If the power fails after step 3 but before step 4, the data in memory is lost. However, upon restart, the database reads the WAL, sees the pending operation, and "replays" it to restore the state.

## Advantages
*   **Crash Recovery:** Guarantees ACID durability.
*   **Performance:** Appending to a file is much faster than random updates to database tables.

---

# 11. Idempotency Pattern

## What It Is
Idempotency means that making the same request multiple times has the **same effect** as making it once.
> Mathematical definition: `f(f(x)) = f(x)`

## Why It Is Needed
In distributed systems, networks are unreliable. A client might send a "Pay $10" request, the server processes it, but the acknowledgement is lost. The client retries. Without idempotency, the user is charged $20.

## Implementation
1.  **Idempotency Key:** The client generates a unique ID (UUID) for the request (e.g., `req_123`).
2.  **Check:** When the server receives the request, it checks a dedicated table/cache: "Have I processed `req_123`?"
3.  **Logic:**
    *   **Yes:** Return the stored result immediately. Do not process again.
    *   **No:** Process the payment, save the result against `req_123`, and return it.

## Use Cases
*   Payment processing (Critical).
*   Form submissions (preventing double posts).
*   Message queue consumers (handling duplicate messages).

---

# 12. Strangler Fig Pattern (Migration)

## What It Is
A pattern for migrating a legacy monolithic application to a microservices architecture by gradually replacing specific functionality with new services.

**Analogy:** The Strangler Fig tree grows around a host tree. It starts small, but eventually, its roots encompass the host, and the host tree dies and rots away, leaving only the Fig.

## How It Works
1.  **Identify:** Pick one feature of the legacy monolith (e.g., "User Profile").
2.  **Build:** Create a new Microservice for "User Profile."
3.  **Route:** Configure the Load Balancer/API Gateway to route traffic for `/user` to the new service, while all other traffic goes to the old Monolith.
4.  **Repeat:** Do this for "Orders," "Payments," etc.
5.  **Retire:** Once all features are moved, decommission the Monolith.

## Benefits
*   **Low Risk:** No "Big Bang" rewrite. You migrate piece by piece.
*   **Value:** You deliver value (new tech, better performance) immediately for the migrated parts.

---

# 13. Fanout / Fan-in Pattern

## Fan-out
**What It Is:** One request triggers parallel tasks in multiple downstream services.
**Example:** A user uploads a video. The system "fans out" requests to:
1.  Generate Thumbnail Service.
2.  Transcode to 1080p Service.
3.  Transcode to 480p Service.
4.  Content Moderation Service.

**Pros:** Reduces total latency (tasks run in parallel).
**Cons:** High load on infrastructure (1 request = N internal requests).

## Fan-in
**What It Is:** Aggregating the results from parallel tasks into a single response.
**Example:** The "Dashboard" service calls "Weather," "News," and "Stock" services in parallel, waits for all of them (Fan-in), and returns a single JSON to the user.

**Optimization:**
*   **Timeout:** If "News" is slow, don't wait forever. Return what you have after 200ms.
*   **Async:** If the result isn't needed immediately, use a message queue for the fan-out.

---

# 14. Mental Map & Summary Table

| Pattern | Solves Problem | Real-World Example |
| :--- | :--- | :--- |
| **Microservices** | Scaling teams & components | Netflix, Uber |
| **CQRS** | Read/Write load imbalance | Social Media Feeds |
| **Event-Driven** | Decoupling services | E-commerce Order Processing |
| **Saga** | Distributed transactions | Travel Booking (Flight + Hotel) |
| **Backpressure** | System overload | Video Streaming Buffering |
| **Rate Limiting** | Abuse / Fairness | Public APIs (Twitter API) |
| **Circuit Breaker** | Cascading failures | Payment Gateway Integration |
| **Bulkhead** | Fault isolation | Ship compartments / Thread Pools |
| **Cache-Aside** | High read latency | News Site Article Loading |
| **WAL** | Data durability | Database Crash Recovery |
| **Idempotency** | Duplicate requests | Payment Processing |
| **Strangler Fig** | Legacy migration | Moving from Mainframe to Cloud |

---

# 15. Conclusion & Final Thoughts

System design is not about memorizing a list of patterns; it is about **matching the right tool to the right problem**.

*   Don't use **Microservices** if you are a team of 3 people. A **Modular Monolith** is better.
*   Don't use **CQRS** if your app is a simple CRUD tool. It adds unnecessary complexity.
*   Don't use **Sagas** if you can just put everything in one database transaction.

**The Senior Engineer's Mindset:**
1.  **Start Simple:** Always default to the simplest architecture (Monolith, Synchronous calls).
2.  **Identify Pain:** Wait until you hit a specific limit (e.g., "Reads are too slow," "One service is crashing the whole app").
3.  **Apply Pattern:** Apply the specific pattern that solves that pain (e.g., "Add Read Replicas/CQRS," "Implement Bulkheads").

> **Patterns are tools, not goals. The goal is a working, reliable system.**
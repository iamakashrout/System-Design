# 2.1 How to Approach a High-Level System Design Problem  
> *If you remember only one thing about system design, remember this.*

High-Level System Design (HLD) is not about drawing boxes.
It is about **thinking clearly under ambiguity**.

### What is High-Level Design?

Think of building a skyscraper.
- **High-Level Design (HLD)** is the architect's blueprint. It defines the foundation, the steel structure, the elevator shafts, and where the water mains enter the building. It answers *what* components we need and *how* they connect.
- **Low-Level Design (LLD)** is the interior designer's plan. It decides the carpet color, the specific wiring in a room, and the brand of faucets. It answers *how* a specific component is implemented internally.

**Actual Example:**
Imagine designing **Uber**.
- **HLD decisions:** "We need a 'Driver Location Service' to track cars, a 'Matching Service' to pair riders with drivers, and a 'Payment Service' to handle money. These services will communicate via a message queue to handle spikes."
- **LLD decisions:** "How do we implement the geospatial index for the driver location? Should we use a QuadTree or a Geohash? Which specific library do we use for the WebSocket connection?"

In an HLD interview, you are the **Architect**, not the coder.

This section defines that process.

---

## The Senior Engineer Mindset (Before You Even Start)

A senior engineer does **not** jump to components or technologies.
They do not say, *"Let's use Redis"* just because Redis is fast. They say, *"We need sub-millisecond latency for session storage, and we can tolerate minor data loss, so an in-memory store like Redis is appropriate."*

They optimize for:

- **Clarity before cleverness**
- **Constraints before solutions**
- **Trade-offs over “best” answers**
- **Evolution over perfection**

Your goal is **not** to design the final system.

Your goal is to design a system that:
- works today
- survives growth
- fails gracefully
- can evolve without rewrites

> Think in *iterations*, not *end states*.

**Examples of the Mindset Shift:**

| Junior Mindset | Senior Mindset |
| :--- | :--- |
| "I'll use Kafka because it's popular." | "Do we need the complexity of Kafka, or is a simple database table sufficient for this throughput?" |
| "The database will handle the load." | "The database is a single point of failure. How do we shard it when writes exceed 10k/sec?" |
| "I'll design the perfect system." | "I'll design a simple system first, then identify where it breaks and patch it." |

---

## Step 1 — Clarify the Problem (Requirements First, Always)

If you skip this step, everything that follows is random.

### 1.1 Functional Requirements  
**What must the system do?**

Ask explicitly:
- What are the **core user actions**?
- What is **in-scope**?
- What is **out-of-scope**?

**Why?** You cannot design a "car" without knowing if it's a Ferrari (speed) or a Dump Truck (capacity).

#### Example: URL Shortener

Core requirements:
- Create a short URL
- Redirect short URL → original URL

Optional / nice-to-have:
- Custom aliases
- Expiration
- Click analytics

✨ **Senior move:**  
Say what you are *not* supporting.

> “For this design, I won’t support authenticated users or link editing after creation.”

This prevents scope creep and shows ownership.

---

### 1.2 Non-Functional Requirements  
**What qualities matter?**

These determine your **entire architecture**.

Ask about:
- Scale (users, QPS, data size)
- Latency (especially read paths)
- Availability expectations
- Consistency requirements
- Cost sensitivity
- Read-heavy vs write-heavy

**Explanation of Key Metrics:**
- **Read/Write Ratio:** A 100:1 ratio (Twitter) needs heavy caching. A 1:1 ratio (Chat) needs high write throughput.
- **Consistency:** Does the user need to see the data *instantly* (Banking) or is a 5-second delay okay (Social Media likes)?
- **Availability:** Can the system go down for 1 hour a year (99.99%) or 3 days a year (99%)?

#### Example Clarification

- 100M total URLs
- 10K writes/sec
- 1M reads/sec
- Redirect latency < 100ms
- High availability
- Eventual consistency acceptable

⚠️ If you skip this, your design choices have no justification.

---

### 1.3 Constraints & Assumptions

If the interviewer doesn’t specify — **you must**.

Examples:
- Global users or single region?
- Web only or mobile too?
- Authentication required?
- Data retention / GDPR concerns?
- **Budget:** "Are we a startup with \$5k/month or Google with infinite resources?"
- **Team:** "Do we have a team of 5 or 500?"

State them out loud:

> “I’ll assume global users and a multi-region read-heavy workload.”

This signals maturity and leadership.

---

## Step 2 — Define the System Boundary

Before boxes and arrows, answer one question:

**What is inside my system, and what is outside?**

### Inside the system
These are the components you must build and manage.
- APIs
- Application services
- Databases
- Caches
- Queues / streams

### Outside the system
These are external dependencies you interact with but do not build.
- Clients (web, mobile)
- External services (payments, email, push)
- CDNs (sometimes)

**Example: Designing Instagram**
- **Inside:** The Feed generation service, the Image storage (S3 wrapper), the User database.
- **Outside:** The Push Notification Network (Apple APNS / Google FCM), the CDN (Cloudflare/Akamai), the Analytics tool (Google Analytics).

Why this matters:
- Prevents overdesign
- Keeps the interview focused
- Avoids solving problems you don’t own

> Senior engineers control scope. Juniors let scope control them.

---

## Step 3 — Identify Core Entities & Data Flow

Now think in **nouns and verbs**, not technologies.

Ask:
- What are the **core entities**?
- How does data **flow end-to-end**?

This step bridges the gap between "Requirements" and "Architecture". If you understand the data, the database schema designs itself.

### Example: URL Shortener

**Core entity**
- `URLMapping { shortKey, originalURL, createdAt, expiry }`

**Data flow**
- Client → API → Service
- Service → DB (write)
- Service → Cache
- Redirect: Client → API → Cache → DB (fallback)

### Example: E-Commerce Checkout
**Entities:** `User`, `Cart`, `Order`, `Payment`.
**Flow:**
1. User clicks "Checkout" → `Order Service` creates pending order.
2. `Order Service` reserves inventory (calls `Inventory Service`).
3. `Order Service` initiates payment (calls `Payment Gateway`).
4. If success → Update Order status to "Paid" → Trigger Email.

This naturally leads to:
- API design
- Read vs write paths
- Caching strategy

> Seniors think in flows. Juniors think in tools.

---

## Step 4 — High-Level Architecture (Happy Path First)

Now draw the **first clean diagram**.

Start with:
- Client
- Load balancer
- Stateless application services
- Primary data store

**Do NOT** add caches, queues, or secondary databases yet. Keep it dead simple.

Ignore failures **for now**.

Why?
- You need a baseline before complexity
- Interviewers want structured iteration

Say this explicitly:

> “I’ll start with the happy path, then layer scale and failure handling.”

That sentence alone signals seniority.

---

## Step 5 — Scale the Bottlenecks (One by One)

Now ask:

**Where will this system break first?**

Typical bottlenecks:
- Single database instance
- Hot keys
- Read amplification
- Write contention

Then introduce solutions **only when justified**:
- Caching (what, where, TTL)
- Partitioning / sharding
- Replication
- Asynchronous processing

### Example

Problem:
- Redirects are read-heavy (1M QPS)

**Analysis:** A single PostgreSQL instance can handle ~5,000 reads/sec. 1M QPS will crush it instantly.

Solution:
- Cache shortKey → originalURL
- TTL based on expiration

Explain:
- Cache hit path
- Cache miss behavior
- Cache failure behavior

### Example 2
Problem:
- Video processing takes 10 minutes (CPU intensive). User uploads block the web server.

**Solution:**
- Introduce a **Message Queue** (RabbitMQ).
- Web server accepts upload → saves to S3 → puts message in Queue → returns "Processing" to user.
- Worker servers pull from Queue and process in background.

> Every new component must solve a specific problem.

---

## Step 6 — Trade-offs (Say Them Out Loud)

This is where seniors shine.

For every decision, state:
- What you gain
- What you lose

Examples:

- **Eventual consistency**
  - ✅ Faster reads, higher availability
  - ❌ Temporary staleness

- **Sharding**
  - ✅ Horizontal scale
  - ❌ Operational complexity
  - *Context:* "I'm choosing to shard by UserID. This makes fetching a user's data fast, but makes analytics across all users slow."

- **Caching**
  - ✅ Latency improvement
  - ❌ Invalidation complexity
  - *Context:* "I'm using a Write-Through cache. It slows down writes slightly but ensures the cache is always fresh, which is critical for this pricing data."

Interviewers test **judgment**, not memory.

---

## Step 7 — Failure Modes & Reliability (HLD View)

Now zoom out.

Ask:
**What happens if this component dies?**

Cover at HLD level:
- Stateless services → easy restart
- Database failures → replicas, failover
- Cache failures → graceful degradation
- Queue backlog → retries, DLQs

**Practical Examples:**
1.  **Load Balancer dies:** "We use DNS Round Robin or a floating IP with a passive standby LB (Keepalived) to detect failure and switch traffic."
2.  **Database Master dies:** "The system detects the crash. A consensus algorithm (like Raft/Paxos) promotes a Read Replica to be the new Master. There might be 30 seconds of write downtime."
3.  **Cache Eviction (Thundering Herd):** "If the cache clears, 10,000 requests hit the DB. We implement 'Request Coalescing' (single flight) so only one request goes to the DB and shares the result with others."
4.  **Worker Crash:** "The message in the queue is not acknowledged (NACK). It becomes visible again after a timeout and another worker picks it up."

Avoid implementation details.
Focus on **behavior and guarantees**.

---

## Step 8 — Extensions & Iteration

If time permits:
- Add optional features
- Improve weak points
- Discuss future scale (10×, 100×)

Examples:
- Analytics pipeline
- Rate limiting
- Multi-region writes
- **Telemetry:** "How do we monitor this? We need metrics (Prometheus), logs (ELK), and tracing (Jaeger)."
- **Security:** "We need to encrypt PII data at rest and rotate API keys."

This shows:
- Long-term thinking
- Ownership mindset
- Product + engineering alignment

---

## Canonical Interview Flow (Memorize This)

Your design should *feel* like this:

1. Clarify requirements
2. Define scale & constraints
3. Identify core entities
4. Draw baseline architecture
5. Scale critical paths
6. Discuss trade-offs
7. Handle failures
8. Optional extensions

If you follow this flow, you will never feel lost.

---

## End-to-End Example: Design a "Pastebin"

**1. Clarify Requirements**
- **User:** Paste text, get a unique URL.
- **Scale:** 1M pastes/day. Read-heavy (10 reads per 1 write).
- **Constraints:** Text only. Max 10MB. Links expire after 1 month.

**2. Boundaries**
- **In:** Web App, API, DB, Storage.
- **Out:** Analytics, Ad networks.

**3. Entities**
- `Paste { id: string, content_location: string, created_at: timestamp, expires_at: timestamp }`

**4. Baseline Architecture**
- Client → Load Balancer → Web Server → Database (PostgreSQL).
- *Storage Decision:* Storing large text in a Relational DB is expensive.
- *Refinement:* Store metadata in DB, actual text content in Object Storage (S3).

**5. Scale Bottlenecks**
- **ID Generation:** How do we get a unique 6-char string (e.g., `abc123`)?
    - *Option A:* Hash the content? No, duplicates allowed.
    - *Option B:* Random string? Collision risk.
    - *Option C:* Pre-generated Key Service (KGS).
    - *Decision:* Use KGS to generate tokens offline and store in a "Unused Tokens" table. Fast, no collisions.
- **Read Latency:** 10M reads/day.
    - *Decision:* Add a Redis Cache (LRU policy) for popular pastes.

**6. Trade-offs**
- "I chose S3 for text storage because it's cheaper than SSD-based DB storage, even though it adds a slight network hop."
- "I chose a Key Generation Service. It adds complexity (a new service), but guarantees short, unique URLs without collision checks on every write."

**7. Failures**
- **KGS runs out of keys:** Monitor the "Unused" table size. Alert when < 10% full.
- **Cache fails:** Fallback to DB. System slows down but works.

**8. Extensions**
- Add a "Burn on Read" feature (delete after 1 view).
- Add User Accounts to manage pastes.

---

## Conclusion

System design is an art form grounded in engineering principles.

- It is not about memorizing the architecture of Netflix or Uber.
- It is about deriving the architecture of Netflix or Uber from **first principles**.

When you focus on requirements, constraints, and trade-offs, the "boxes" draw themselves.
Practice this flow, and you will shift from being a coder who writes functions to an architect who builds systems.

---

## One-Liner Mental Model

**HLD = Requirements → Flows → Bottlenecks → Trade-offs → Evolution**

If you remember only one thing, remember this.

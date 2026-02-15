# 2.5 Reliability, Availability & Failure Handling (HLD View)
> Most candidates design for scale.  
> **Senior engineers design for failure first.**

Real-world systems fail **all the time**: servers crash, networks partition, humans deploy bugs.  
Your job is not to prevent failure — **it is to survive failure**.

---

# 1. Core Definitions (Say These Clearly)

## Reliability

**Probability that a system performs correctly over time.**

Meaning:
- When the system is up, it behaves correctly
- No wrong results, corruption, or silent bugs
- It does what the user expects it to do, consistently.

### Example
- A payment system that never double-charges users → **high reliability**
- A chat app that sometimes loses messages → **low reliability**

---

## Availability

**Percentage of time the system is operational and reachable.**

| Availability | Downtime per year |
|-------------|-------------------|
| 99% | ~3.6 days |
| 99.9% | ~8.7 hours |
| 99.99% | ~52 minutes |
| 99.999% | ~5 minutes |

---

## Important Distinction

A system can be:

### Reliable but not available
- Correct when up, but often down  
- Example: Banking system with frequent maintenance windows

### Available but unreliable
- Always up, but buggy  
- Example: Social app that shows wrong data sometimes

---

# 2. Single Point of Failure (SPOF) — The First Enemy

## What is a SPOF?

Any component whose failure **brings the system down**.

---

## Examples

- **Single database instance**
  - *Why it's bad:* If the disk corrupts or the OS crashes, all user data becomes inaccessible. The entire application stops working until you restore from a backup (which could take hours).
- **Single load balancer**
  - *Why it's bad:* This is the front door. If the door is locked, it doesn't matter if the house (servers) inside is fine. No traffic can enter.
- **Single cache node**
  - *Why it's bad:* If your database relies on the cache to absorb 90% of the load, the cache dying will cause all that traffic to hit the database instantly, likely crashing the database too (Cascading Failure).
- **Single region deployment**
  - *Why it's bad:* If AWS `us-east-1` has a major outage (which happens), your entire business is offline.
- **Single message queue**
  - *Why it's bad:* If the queue fills up or crashes, asynchronous workflows (emails, order processing) stop entirely.

---

## Senior Principle

> **Every critical component must be redundant.**

This sentence is **interview gold**.

---

# 3. Redundancy Patterns

Redundancy means **duplicate critical components** so failure doesn’t stop the system.

---

## 3.1 Service Redundancy (Stateless Services)

### Pattern
- Multiple service instances behind a load balancer
- Auto-scaling groups replace failed instances automatically

### Example
Imagine a "User Profile Service". Instead of running it on one server, we run it on 3 servers (Nodes A, B, C).
- **Client** sends request to **Load Balancer**.
- **Load Balancer** forwards to **Node A**.
- If **Node A** crashes, the Load Balancer detects the health check failure and routes the next request to **Node B**.

### Failure Impact
- One instance dies → traffic shifts to others  
- Users do not notice

---

## 3.2 Database Redundancy

### Patterns
- Primary + replicas
- Multi-AZ deployments
- Automated failover (e.g., RDS Multi-AZ)

### Example
Using PostgreSQL in a Master-Slave setup.
- **Master:** Accepts all Writes (INSERT/UPDATE).
- **Slave (Replica):** Copies data from Master in real-time. Handles Reads.
- If **Master** dies, a consensus algorithm (like Raft or Paxos) promotes the **Slave** to be the new Master.

### Failure Impact
- Primary fails → replica promoted  
- Temporary read-only or short downtime

---

## 3.3 Load Balancer Redundancy

### Patterns
- Managed cloud LB (already HA)
- DNS-based failover (Route53, Cloudflare)
- Multiple LBs across regions

### Example
Using DNS Round Robin or a Floating IP (VIP).
- **DNS** points to two different Load Balancer IPs.
- If LB 1 stops responding, the DNS health check removes it, and browsers are directed to LB 2.

### Why It Matters
If your LB fails → **nothing works**.

---

# 4. Failover Strategies

Failover = switching traffic when something fails.

---

## 4.1 Active–Passive

### How it works
- Primary handles all traffic  
- Standby waits idle  
- On failure → standby becomes primary  

### Pros
- Simple
- Easier consistency

### Cons
- Failover delay (seconds to minutes)
- Idle resources wasted

### Example
- Primary DB + standby replica
- Disaster recovery region in cold standby

---

## 4.2 Active–Active

### How it works
- Multiple nodes/regions serve traffic simultaneously
- Traffic is usually split by geography or user ID

### Pros
- Zero downtime
- Lower latency for global users

### Cons
- Conflict resolution needed
- Complex consistency models

### Example
- Multi-region DynamoDB
- Global CDNs

---

# 5. Graceful Degradation (Senior-Level Concept)

## Meaning
**When parts fail, the system should degrade, not die.**
It is the philosophy that "partial functionality is better than no functionality." If a dependency is slow or down, the system should cut it off and serve a simplified version of the experience.

---

## Examples

### Feed System
- **Scenario:** The "Personalized Ranking Service" (AI model) is down.
- **Bad Response:** The feed returns Error 500. The app crashes.
- **Graceful Response:** The system detects the failure and falls back to a "Chronological Feed" (simple DB query) or a "Cached Feed". The user sees content, even if it's not perfectly personalized.

### E-commerce
- **Scenario:** The "Reviews Service" is slow.
- **Bad Response:** The product page loads forever.
- **Graceful Response:** The product page loads the title, image, and price immediately. The "Reviews" section shows a "Loading..." spinner or is simply hidden. The "Buy" button still works.

---

## Interview Line

> “Non-critical features degrade first; core user path remains operational.”

---

# 6. Cascading Failures (The Hidden Killer)

## What is Cascading Failure?

One failure triggers retries → overloads others → entire system collapses.
It is a positive feedback loop where a small issue amplifies into a total outage.

---

## Real Causes

### Retry Storms
- **Scenario:** Service A calls Service B. Service B slows down.
- **The Spark:** Service A times out and retries immediately.
- **The Fire:** Now Service B has to handle the original request *plus* the retry. It gets even slower. Service A retries *again*.
- **Result:** Service B is hammered with 10x traffic and crashes completely.

### Unbounded Queues
- **Scenario:** A worker process slows down.
- **The Spark:** The queue starts filling up with tasks.
- **The Fire:** The queue consumes all available RAM. The server crashes (OOM Kill).
- **Result:** All tasks in memory are lost, and the system is down.

### Synchronous Dependencies
- **Scenario:** Service A calls B, B calls C, C calls D.
- **The Spark:** Service D hangs.
- **The Fire:** Service C waits for D, holding a thread. Service B waits for C, holding a thread. Service A waits for B.
- **Result:** All threads in A, B, and C are blocked waiting. No new requests can be handled.


---

## Mitigation Techniques (Name These)

### Circuit Breakers
Stop calling a failing service temporarily.
- **How it works:**
  1. **Closed (Normal):** Requests pass through.
  2. **Open (Tripped):** If error rate > 50%, the breaker opens. All requests fail immediately (Fail Fast) without calling the downstream service.
  3. **Half-Open:** After a timeout, let 1 request through. If it succeeds, close the breaker.
- **Example:** If the Payment Gateway is down, the Checkout Service stops trying to charge cards and immediately returns "Try again later" to save resources.

### Bulkheads
Isolate resources per service (like ship compartments).
- **How it works:** Partition thread pools or connection pools.
- **Example:** Service A has 100 threads. We allocate 10 threads for "Image Processing" and 90 for "User Requests". If Image Processing hangs, it only consumes 10 threads. The other 90 are free to serve users.

### Timeouts
Never wait forever.
- **How it works:** Set a hard limit (e.g., 2 seconds) on every network call.
- **Example:** If the database doesn't reply in 2s, cancel the request and return an error. This frees up the thread.

### Backpressure
Reject or slow requests when overloaded.
- **How it works:** If the system is at capacity, return HTTP 503 or drop messages.
- **Example:** A video processing worker tells the queue "I'm full, don't send more" until it finishes current jobs.

---

# 7. Retries & Idempotency (HLD View)

Retries are **necessary but dangerous**.

---

## Rules for Retries

### Bounded Retries
- Retry only limited times
- **Example:** Max 3 retries. If it fails 3 times, log an error and give up.

### Exponential Backoff
- Wait 1s → 2s → 4s → 8s
- **Why:** Gives the failing system time to recover.

### Jitter
- Random delay to avoid synchronized retries
- **Example:** Instead of waiting exactly 2s, wait `2s + random(0-500ms)`. This prevents "Thundering Herd" where 10,000 clients retry at the exact same millisecond.

---

## Idempotency

Calling the same request multiple times should **not cause duplicate effects**.

### Example
Payment API:
- Retry should not double-charge user.
- **Implementation:**
  1. Client generates a unique `idempotency_key` (UUID).
  2. Client sends `POST /charge { amount: 10, key: "abc-123" }`.
  3. Server checks DB: "Have I seen key `abc-123`?"
     - **No:** Charge card, save key `abc-123`, return Success.
     - **Yes:** Do nothing, return the *previous* Success response.

---

# 8. SLOs, SLAs & Error Budgets (Senior Interview Gold)

These terms define "how reliable is reliable enough?"

---

## SLO (Service Level Objective)
Internal reliability target.
- **Definition:** The goal your engineering team aims for.
- **Purpose:** Drives engineering decisions (e.g., "Do we need to freeze features to fix bugs?").

Example:
99.9% uptime
p95 latency < 200ms


---

## SLA (Service Level Agreement)
Legal contract with customers.
- **Definition:** The promise you make to paying users.
- **Purpose:** Defines penalties (refunds/credits) if you fail.
- **Note:** SLA is usually *lower* than SLO (e.g., SLO 99.9%, SLA 99%) to give you a buffer.

Breaking SLA → penalties.

---

## Error Budget

Error Budget = 1 − SLO

- **Definition:** The amount of unreliability you are allowed to have.
- **Purpose:** If you have budget left, you can ship risky features. If you burn the budget, you must stop feature work and focus on stability.

Example:
- 99.9% SLO → 0.1% downtime allowed  
- ~8.7 hours/year to deploy, experiment, fail

---

## Comparison Table

| Term | Stands For | Audience | Purpose | Consequence of Breach |
| :--- | :--- | :--- | :--- | :--- |
| **SLI** | Service Level Indicator | Engineers | The actual metric (e.g., "Latency is 150ms") | N/A |
| **SLO** | Service Level Objective | Product/Eng | The goal (e.g., "Latency < 200ms") | Slow down releases |
| **SLA** | Service Level Agreement | Lawyers/Customers | The contract (e.g., "Latency < 500ms") | Financial Penalty |

---

## Senior Statement

> “We design features within the error budget to balance velocity and reliability.”

This is **Staff/Principal-level language**.

---

# 9. Disaster Recovery (DR)

When entire region or cluster dies.

---

## 9.1 Backup & Restore

- Periodic snapshots
- Cheapest
- Recovery time = hours/days

### Use case
- Internal tools
- Non-critical systems

---

## 9.2 Pilot Light

- Minimal infra always running
- Database is replicated, but app servers are off (or very few)
- Scale during disaster

### Use case
- Cost-sensitive production systems

---

## 9.3 Warm Standby

- Fully functional secondary region
- Low traffic
- Can scale quickly

### Use case
- Business-critical services

---

## 9.4 Hot Standby / Active-Active

- Multiple regions live
- Instant failover

### Use case
- Payments, cloud platforms, global SaaS

---

# 10. Observability (HLD Level)

Reliability requires **visibility**.
Observability is not just "monitoring".
- **Monitoring:** Tells you *when* something is wrong (e.g., "CPU is 99%").
- **Observability:** Tells you *why* it is wrong (e.g., "CPU is 99% because User X ran a regex query on the logs").

---

## Metrics
- QPS
- Latency (p50, p95, p99)
- Error rate
- CPU, memory
- **What they are:** Aggregatable numbers collected over time.
- **Purpose:** High-level health overview. Cheap to store.
- **Example:** `http_requests_total`, `memory_usage_bytes`.

---

## Logs
- Debugging failures
- Audit trails
- **What they are:** Discrete events (text/JSON) emitted by the code.
- **Purpose:** High-fidelity debugging of specific errors. Expensive to store.
- **Example:** `[Error] Payment failed for user 123: Insufficient Funds`.

---

## Tracing
- Distributed tracing (Jaeger, OpenTelemetry)
- Understand request flows across services
- **What it is:** Tracks a single request as it hops between microservices.
- **Purpose:** Finding latency bottlenecks in complex architectures.
- **Example:** "The request took 2s. 50ms in API Gateway, 100ms in Auth Service, and 1.8s in the Database."

---

## Alerts & Dashboards
- PagerDuty alerts
- Grafana dashboards

---

# 11. Chaos Engineering (Bonus Senior Topic)

## Meaning
Intentionally inject failures to test resilience.

---

## Examples
- **Chaos Monkey:** Randomly terminates EC2 instances in production to ensure the auto-scaling group works.
- **Latency Injection:** Artificially add 5 seconds of latency to the database calls to test if the frontend handles timeouts gracefully.
- **Blackhole:** Block all network traffic to the "Recommendation Service" to verify the "Graceful Degradation" fallback logic.

---

## Interview Line

> “We can use chaos testing to validate failover mechanisms.”

---

# 12. Reliability Design Principles (Memorize)

Let's apply these principles to a flow: **"User Checkout in an Online Store"**.

---

## Principle 1 — Assume Everything Fails
Hardware fails  
Networks fail  
Humans fail  

Design accordingly.
*Example:* "I assume the Payment Gateway might be down. I will build a queue to retry payments later if that happens."

---

## Principle 2 — Fail Fast, Recover Faster
- Timeouts
- Automated failover
- Self-healing systems
*Example:* "If the Inventory Service is slow, I will timeout in 200ms instead of letting the user wait 10s. I will show 'Stock status unknown' but let them buy."

---

## Principle 3 — Prefer Availability or Consistency Explicitly
This ties directly into CAP theorem.
*Example:* "For the 'Cart Item Count', I prefer Availability. If it says 5 items but there are actually 6, that's okay. But for 'Charging the Card', I prefer Consistency."

---

## Principle 4 — Isolate Blast Radius
Failures should not spread across the system.
*Example:* "If the 'Reviews Service' crashes, it should not crash the 'Checkout Service'. They are isolated by a circuit breaker."

---

# 13. Summary & Interview Power Lines

Combine these mental models and phrases to sound like a senior engineer.

### The Mental Model
1.  **Redundancy** prevents immediate downtime (Backup generators).
2.  **Failover** restores service when redundancy kicks in (Switching to the generator).
3.  **Isolation** prevents one failure from killing everything (Circuit breakers).
4.  **Observability** tells you what is broken (The dashboard).
5.  **Disaster Recovery** saves you when the building burns down.

### The Power Lines (Memorize These)
- "We eliminate single points of failure via **redundancy**."
- "We use **graceful degradation** to keep core flows alive when dependencies fail."
- "**Circuit breakers** prevent cascading failures in our microservices."
- "We use **retries with exponential backoff** to avoid retry storms."
- "We monitor **SLOs** and manage **error budgets** to balance speed and stability."
- "Our **Disaster Recovery** strategy depends on the business criticality of the data."

---

## Final Senior Insight

> **Systems don’t fail because of scale.**
> **They fail because of unexpected failure modes.**
> **Designing for failure is what separates senior engineers from everyone else.**
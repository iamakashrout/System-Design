# 1.6 Reliability & Fault Tolerance (System Design View)

Reliability and fault tolerance determine whether a system **survives real-world failures**.

In large distributed systems, failures are not edge cases — they are normal.

---

## 1.6.1 Understanding Failures & Reliability

### What Are Failures?
A failure occurs when a component of the system stops performing its required function. In distributed systems, failures are inevitable due to the sheer number of components involved.

**Common Types of Failures:**
1.  **Hardware Failures:** Hard disks crashing, RAM corruption, power supply units burning out.
    *   *Example:* A server rack in a data center loses power, taking down 40 nodes.
2.  **Software Failures:** Bugs, memory leaks, unhandled exceptions, or bad deployments.
    *   *Example:* A new code deployment introduces a NullPointerException in the payment processing logic.
3.  **Network Failures:** Packet loss, high latency, severed cables, or DNS outages.
    *   *Example:* A construction crew accidentally cuts a fiber optic cable, isolating a region.

### What Is Reliability?
Reliability is the probability that a system will perform its intended function **correctly** and consistently over a specific period. It is about **trust** and **correctness**.

**Key Aspects of Reliability:**
-   **Correctness:** The system produces the right output (e.g., calculating a bill correctly).
-   **Data Integrity:** Data is not lost or corrupted.
-   **Resilience:** The system handles errors without crashing.

**Example:**
A reliable file storage system ensures that if you upload a photo, it is never corrupted and never lost, even if a hard drive fails.

---

## 1.6.2 Reliability vs Availability (Detailed)

While often used interchangeably, these terms describe different properties.

### Reliability
> "Does the system work **correctly**?"

Focuses on data integrity and accurate processing. A system can be reliable even if it is down for maintenance (it is not producing *wrong* results, it is just producing *no* results).

### Availability
> "Is the system **operational and accessible**?"

Focuses on uptime. Can I access the service right now? A system can be available but unreliable (e.g., it loads, but shows the wrong user balance).

### Analogy: The Bank Teller
-   **Reliable but Unavailable:** The teller is excellent and never makes a mistake. However, they go on lunch break for 1 hour. During that hour, you cannot get service (Unavailable), but your money is safe (Reliable).
-   **Available but Unreliable:** The teller is always at the desk (Available 24/7), but they are bad at math and frequently give you the wrong change (Unreliable).

### Real-World Examples
1.  **Banking System (Prioritizes Reliability):**
    *   If the network is flaky, the bank might block transactions.
    *   *Result:* You can't send money (Low Availability), but the bank ensures you don't double-spend or lose money (High Reliability).
2.  **Social Media Feed (Prioritizes Availability):**
    *   If the database is slow, the system might show you cached posts from 10 minutes ago.
    *   *Result:* You see old data (Lower Reliability/Consistency), but the app loads and feels fast (High Availability).

---

## 1.6.3 Redundancy (The Foundation)

### What Is Redundancy?
Redundancy is the practice of duplicating critical components of a system to increase reliability. The core idea is to eliminate **Single Points of Failure (SPOF)**. If one component fails, a redundant copy takes over.

### Why Is It Needed?
Hardware has a Mean Time Between Failures (MTBF). If you have 1,000 servers, and each fails once every 3 years, you will see roughly one failure per day. Without redundancy, your system would be down daily.

### Types of Redundancy

#### 1) Service Redundancy (Stateless)
Running multiple copies (replicas) of your application code behind a Load Balancer.
-   **How it works:** Instead of one Web Server, you run three.
-   **Need:** If Server A crashes due to a memory leak, the Load Balancer routes traffic to Server B and C. The user notices nothing.
-   **Example:** Kubernetes Pods scaling from 1 to 10 replicas.

#### 2) Infrastructure Redundancy (Hardware/Zone)
Distributing hardware across different physical locations.
-   **How it works:** Placing servers in different racks, different Availability Zones (AZs), or different Data Centers.
-   **Need:** Protects against physical disasters (fire, flood, power outage). If an entire data center loses power, your app runs in the backup data center.
-   **Example:** AWS Multi-AZ deployments.

#### 3) Data Redundancy
Storing multiple copies of data.
-   **How it works:** Writing data to a primary hard drive and immediately mirroring it to a secondary drive (RAID) or a secondary server.
-   **Need:** Hard drives are the most likely component to fail. Without data redundancy, a disk crash means permanent data loss.
-   **Example:** HDFS (Hadoop) storing 3 copies of every file block.

---

## 1.6.4 Replication (Data Reliability)

Replication is the specific process of sharing information to ensure consistency between redundant resources, such as software or hardware components, to improve reliability, fault-tolerance, or accessibility.

### 1) Leader–Follower (Master-Slave)
-   **How it works:** One node (Leader) handles all writes. Changes are shipped to other nodes (Followers). Followers handle reads.
-   **Advantages:** Simple to understand; strict consistency is easier to manage on the single leader.
-   **Disadvantages:** The Leader is a bottleneck for writes. If the Leader dies, a failover process is needed to promote a Follower.
-   **Use Cases:** MySQL, PostgreSQL, MongoDB (default).

### 2) Multi-Leader (Master-Master)
-   **How it works:** Multiple nodes can accept writes. They sync with each other.
-   **Advantages:** Higher write availability; tolerates network partitions better; great for multi-region apps (user writes to nearest region).
-   **Disadvantages:** **Write Conflicts**. If User A updates a record in US-East and User B updates the same record in EU-West simultaneously, the system must resolve the conflict.
-   **Use Cases:** Google Docs (collaborative editing), global calendar apps.

### 3) Leaderless (Peer-to-Peer)
-   **How it works:** No leader. Client writes to multiple nodes (e.g., 3 out of 5) and reads from multiple nodes. Quorum consensus determines the truth.
-   **Advantages:** Extremely high availability. No single point of failure for writes. No complex failover (just write to others).
-   **Disadvantages:** Eventual consistency; complex application logic to handle version vectors.
-   **Use Cases:** Cassandra, DynamoDB (shopping cart).

---

## 1.6.5 Replication vs Backups

These are often confused but serve different purposes.

| Feature | Replication | Backups |
| :--- | :--- | :--- |
| **Goal** | High Availability & Uptime | Disaster Recovery & Archiving |
| **Speed** | Real-time (milliseconds) | Periodic (Hourly/Daily) |
| **Data State** | Live (Dynamic) | Snapshot (Static) |
| **Protects Against** | Hardware failure, Network crash | Human error, Corruption, Ransomware |

**Scenario:**
An admin accidentally runs `DELETE FROM users;`.
-   **Replication:** The delete command is instantly replicated to all followers. All data is lost on all nodes. (Replication fails to protect here).
-   **Backup:** You go to last night's backup, restore the database, and recover the data.

**Conclusion:** You need **both**. Replication keeps you running; backups keep you safe.

---

## 1.6.6 Failover

Failover is the operational process of switching to a redundant or standby computer server, system, hardware component, or network upon the failure or abnormal termination of the previously active application.

### How It Works
1.  **Detection:** The system (e.g., a Load Balancer or Orchestrator) detects a failure via Health Checks (heartbeats).
2.  **Decision:** The system decides the primary is dead.
3.  **Switch:** Traffic is rerouted to the standby component.

### Types
-   **Active-Passive:** The backup server sits idle (or does low-priority work) until the primary fails.
    *   *Advantage:* Safer, no performance degradation on failover.
    *   *Disadvantage:* Waste of resources (paying for idle hardware).
-   **Active-Active:** Both servers handle traffic. If one fails, the other takes 100% of the load.
    *   *Advantage:* Efficient resource usage.
    *   *Disadvantage:* The remaining server might be overwhelmed by the doubled load.

### Advantages
-   Minimizes downtime (often reduces outages from hours to seconds).
-   Automates recovery, reducing the need for human intervention at 3 AM.

---

## 1.6.7 Health Checks & Monitoring

### Health Checks
Mechanisms to ask a service, "Are you okay?"
-   **Liveness Probe:** "Is the process running?" If no, restart the container.
-   **Readiness Probe:** "Can you accept traffic?" (e.g., is the DB connection established?). If no, don't send traffic, but don't restart yet.
-   **Purpose:** Allows Load Balancers to route traffic *only* to healthy nodes.
-   **Advantage:** Prevents requests from being sent to a "zombie" process that can't answer, avoiding user errors.

### Monitoring
The collection and analysis of metrics to understand system state.
-   **Metrics:** Latency, Error Rate, Traffic (QPS), Saturation (CPU/Memory).
-   **Purpose:** To detect trends (e.g., "Memory usage is growing 1% per hour") and alert engineers *before* a crash.
-   **Advantage:** Moves operations from "Reactive" (fixing after crash) to "Proactive" (fixing before crash).

---

## 1.6.8 Graceful Degradation

Graceful degradation is the ability of a system to maintain limited functionality even when a large portion of it has been destroyed or rendered inoperative.

### Concept
"It is better to give the user *something* than *nothing*."

### Examples
1.  **E-commerce Search:** If the personalized recommendation engine fails, show a static list of "Best Selling Items" instead of an error page.
2.  **Video Streaming:** If bandwidth is low or the HD server is overloaded, automatically downgrade the video quality to 480p instead of stopping playback.
3.  **Typo Tolerance:** If the spell-check service is down, search for the exact keyword instead of crashing.

### Advantages
-   **User Retention:** Users are less likely to leave if the core app still works.
-   **System Survival:** By cutting off heavy non-critical features, you reduce load on the struggling system, helping it recover.

---

## 1.6.9 Circuit Breakers

A design pattern used to detect failures and encapsulate the logic of preventing a failure from constantly recurring.

### How It Works
It acts like an electrical circuit breaker.
1.  **Closed (Normal):** Requests flow through to the service. The breaker counts errors.
2.  **Open (Tripped):** If errors exceed a threshold (e.g., 50% failure rate), the breaker "trips". All future calls fail *immediately* without hitting the downstream service.
3.  **Half-Open (Recovery):** After a timeout, allow a few "test" requests. If they succeed, close the breaker (resume normal). If they fail, open it again.

### Uses
-   **Prevent Cascading Failure:** If Service A calls Service B, and B is slow, A's threads will wait and pile up, eventually crashing A. A circuit breaker stops this by failing fast.
-   **Resource Protection:** Gives the failing service time to recover without being hammered by retries.

---

## 1.6.10 Backpressure

Backpressure is a feedback mechanism where a consumer tells a producer to slow down because it cannot keep up with the rate of work.

### Methods
1.  **Blocking/Slowing:** The consumer stops reading from the socket, forcing the producer's write buffer to fill up, eventually blocking the producer's write calls.
2.  **Dropping:** The consumer simply drops incoming events (e.g., video frames) if the buffer is full.
3.  **Rejecting:** Returning HTTP 503 (Service Unavailable) or 429 (Too Many Requests).

### Examples
-   **Video Streaming:** If your internet is slow, the video player buffer empties. The player pauses (stops consuming). The server stops sending data until the buffer has space.
-   **Queue Processing:** If a worker is processing 10 jobs/sec but the queue is receiving 100 jobs/sec, the queue fills up. Once full, the API rejects new jobs until the workers catch up.

### Use Cases
-   Preventing OutOfMemory errors.
-   Ensuring system stability during traffic spikes.

---

## 1.6.11 Disaster Recovery (DR)

Disaster Recovery involves a set of policies, tools, and procedures to enable the recovery or continuation of vital technology infrastructure and systems following a natural or human-induced disaster.

### Real-Life Scenarios
-   **Natural:** Earthquake destroys the primary data center.
-   **Technical:** Ransomware encrypts all production data.
-   **Human:** A disgruntled employee deletes the root database.

### Key Concepts
-   **RTO (Recovery Time Objective):** "How long can we be down?"
    *   Target time to restore the system (e.g., 4 hours).
-   **RPO (Recovery Point Objective):** "How much data can we lose?"
    *   Target maximum age of files recovered from backup (e.g., 1 hour). If RPO is 1 hour, you might lose the last hour of transactions.

### Difference from High Availability (HA)
-   **HA** is about keeping the system running during *minor* failures (server crash). It is automated and seamless.
-   **DR** is about surviving *major* catastrophes (site destruction). It often involves manual triggers, restoring from cold backups, and switching DNS to a different region.

---

## 1.6.12 Reliability Anti-Patterns

Avoid these common design mistakes that undermine reliability.

1.  **Single Point of Failure (SPOF):**
    *   *What:* Relying on a single database, load balancer, or server.
    *   *Why Bad:* If it breaks, your business stops. Always have N+1 redundancy.
2.  **Infinite Retries:**
    *   *What:* Retrying a failed request forever without a limit.
    *   *Why Bad:* Creates a "Thundering Herd". If a service is struggling, hammering it with infinite retries ensures it will never recover. Always use exponential backoff and limits.
3.  **Lack of Health Checks:**
    *   *What:* Load balancers sending traffic blindly.
    *   *Why Bad:* Requests are sent to dead or hanging servers, resulting in user-facing errors.
4.  **Tight Coupling (Synchronous Chains):**
    *   *What:* Service A calls B, B calls C, C calls D.
    *   *Why Bad:* If D fails, the error propagates all the way to A. The availability of the system is the product of all components (99% * 99% * 99% * 99% = ~96%).
5.  **Manual Failover:**
    *   *What:* Requiring a human to SSH into a server to switch a database master.
    *   *Why Bad:* Humans are slow and make mistakes under pressure. Failover should be automated.
6.  **Ignoring Backpressure:**
    *   *What:* Accepting infinite work into an unbounded queue.
    *   *Why Bad:* The system will eventually run out of RAM and crash hard (OOM Kill).

---

## Key Interview Takeaways

-   **Failures are normal:** Design for them, don't just hope they won't happen.
-   **Redundancy is key:** It is the only way to achieve higher availability than your hardware allows.
-   **Replication ≠ Backup:** You need replication for uptime and backups for data safety.
-   **Graceful Degradation:** Smart systems bend rather than break.
-   **Circuit Breakers & Backpressure:** These patterns protect your system from cascading failures and overload.

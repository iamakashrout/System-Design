## 0.4 Trade-offs & CAP Theorem

System design is the art of choosing what to sacrifice.  
There is no perfect system—every design optimizes some properties at the cost of others.

---

### Trade-off Thinking

Every design decision involves trade-offs.

Common trade-offs:
- Consistency vs Availability
- Latency vs Accuracy
- Cost vs Scale
- Simplicity vs Flexibility

Good system designers:
- Identify trade-offs early
- Choose based on requirements
- Can clearly justify their decisions

---

### CAP Theorem (Practical)

CAP theorem applies to distributed systems and states:

In the presence of a network partition, a system can guarantee either Consistency or Availability, but not both.

Since network partitions are unavoidable, the real choice is between Consistency and Availability.

---

### Consistency (C)

Consistency means:
- Every read returns the most recent write
- All nodes see the same data at the same time

Implications:
- Strong coordination between nodes
- Higher latency during failures
- Requests may be rejected to preserve correctness

Examples:
- Banking systems
- Financial transactions
- Inventory systems

---

### Availability (A)

Availability means:
- Every request receives a non-error response
- The system remains responsive even during failures

Implications:
- Responses may contain stale data
- Eventual consistency is common

Examples:
- Social media feeds
- Content delivery systems
- Recommendation engines

---

### Partition Tolerance (P)

Partition tolerance means:
- The system continues operating despite network failures between nodes

In real-world distributed systems:
- Network partitions are unavoidable
- Partition tolerance is mandatory

---

### Practical Interpretation

Because partitions are inevitable:
- CP systems prioritize consistency and may reject requests
- AP systems prioritize availability and may return stale data

CAP is not about normal operation—it defines behavior during failures.

---

### Important Clarification

CAP theorem is not:
- A strict rule
- Something you can opt out of
- A formula to memorize

CAP is a mental model to reason about trade-offs in distributed systems.

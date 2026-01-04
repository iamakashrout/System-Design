## 0.3 Core Metrics (Intuition-Based)

At this stage, the goal is not precise capacity planning.  
The goal is to build comfort with scale and reason about system behavior.

---

### Latency

Latency is the time taken to serve a request.

Common percentiles:
- p50: Median response time
- p95 / p99: Tail latency (critical for user experience)

#### Why Tail Latency Matters
- One slow dependency can delay the entire request
- Distributed systems amplify slow responses
- Users remember bad experiences more than average ones

Design focus:
- Reduce dependency chains
- Add timeouts and fallbacks
- Cache frequently accessed paths

---

### Throughput

Throughput is the number of requests handled per second (QPS).

Example:
- 1 million users
- Each makes 10 requests per day
- ~10 million requests/day
- ~115 QPS on average

Key points:
- Estimates are order-of-magnitude, not precise
- Traffic spikes matter more than averages

---

### Availability

Availability is the probability that the system is operational.

| Availability | Downtime per year |
|-------------|------------------|
| 99%         | ~3.6 days        |
| 99.9%       | ~8.7 hours       |
| 99.99%     | ~52 minutes     |

Insight:
- High availability is expensive and complex
- Each additional "9" increases system complexity

Design focus:
- Eliminate single points of failure
- Use replication and failover
- Make conscious availability trade-offs

---

### Storage Growth (Rough)

Ask:
- What data is stored?
- How fast does it grow?
- What is the retention period?

Rough estimates early help avoid scaling issues later.

# Canonical Problem 6: Rate Limiter (High-Level Design)

A Rate Limiter is a critical component of distributed systems that controls the amount of traffic a user or service can send or receive. It's a defensive mechanism to prevent system overload, ensure fair resource usage, and protect against malicious attacks like Denial of Service (DoS).

Platforms like Stripe, GitHub, and Twitter rely heavily on rate limiting to maintain the stability and availability of their APIs.

---

## 1. Step 1: Requirements Clarification

### 1.1 Functional Requirements

*   **Limit Request Rates:** The core function is to limit the number of requests a client can make within a specified time window (e.g., 100 requests per minute).
*   **Multiple Dimensions:** The limits should be configurable based on various dimensions, such as `user_id`, IP address, API key, or even globally for a specific service endpoint.
*   **Error Response:** When a user exceeds their limit, the system must reject the request with a clear error message, typically an `HTTP 429 Too Many Requests` status code.
*   **Configurability:** Different services or endpoints should have different rules (e.g., the login API might be stricter than the read-only content API).

### 1.2 Non-Functional Requirements

*   **Low Latency:** The rate limiter sits in the critical request path. Its overhead must be minimal, ideally adding less than a few milliseconds of latency to each request.
*   **High Availability:** If the rate limiter fails, it could either block all traffic or let all traffic through, both of which are undesirable. The system must be highly available.
*   **Scalability:** The system must handle a massive volume of requests from millions of users, scaling horizontally as traffic grows.
*   **Memory Efficiency:** The solution must be memory-efficient, as it needs to track counters for a vast number of clients.

**Core Observation:** The rate limiter must be extremely fast and resilient. The entire design should prioritize performance and fault tolerance.

---

## 2. Where Does the Rate Limiter Live?

The placement of the rate limiter has significant architectural implications.

### 2.1 In the Application Code
The simplest approach is to implement the logic directly within each microservice.
*   **Pros:** Easy to implement for a single service.
*   **Cons:**
    *   Logic is duplicated across multiple services.
    *   Inconsistent enforcement; each service has its own view of the limit.
    *   Hard to manage global or cross-service limits.

### 2.2 In Middleware
A slightly better approach is to have a middleware component that runs before the main application logic.
*   **Pros:** Centralizes the logic within a single application.
*   **Cons:** Still tied to a specific service; doesn't solve the problem for a distributed system with many services.

### 2.3 At the API Gateway (Most Common)
This is the most popular and effective placement. The API Gateway is the single entry point for all external traffic.
```text
Client ---> [API Gateway + Rate Limiter] ---> [Backend Services]
```
*   **Pros:**
    *   **Centralized Control:** All rules are managed in one place.
    *   **Protects All Downstream Services:** The backend services don't need to be aware of rate limiting.
    *   **Consistent Enforcement:** Provides a single, unified view of request counts.
*   **Cons:** The API Gateway can become a single point of failure if not made highly available.

---

## 3. Algorithms for Rate Limiting

This is the core of the design, with several trade-offs between performance, memory, and accuracy.

### 3.1 Fixed Window Counter
This algorithm uses a fixed time window (e.g., one minute) to track request counts.
*   **How it works:** A counter is maintained for each user for the current window. For a rule of "100 requests per minute," we might use a key like `user123:1678886400` (where the number is the Unix timestamp for the start of the current minute). When a request arrives, we increment the counter. If the count exceeds 100, the request is rejected.
*   **Problem (Edge Burst):** This algorithm can allow up to double the limit in a short period.
    *   Imagine a user makes 100 requests at `12:00:59`. The limit is reached for the `12:00` window.
    *   At `12:01:00`, a new window starts. The user can immediately make another 100 requests.
    *   **Result:** The user successfully made 200 requests in just two seconds, potentially overwhelming the backend.

### 3.2 Sliding Window Log
This algorithm tracks the timestamp of every single request to achieve perfect accuracy.
*   **How it works:** A list (or log) of timestamps for each user's requests is stored. When a new request arrives:
    1.  Remove all timestamps from the list that are older than the time window (e.g., older than 60 seconds).
    2.  Count the number of remaining timestamps in the list.
    3.  If the count is less than the limit, add the new request's timestamp to the list and allow the request. Otherwise, reject it.
*   **Problem (Memory Inefficiency):** This approach is not scalable. If a user can make 500 requests per minute, you need to store 500 timestamps for that user. For millions of users, the memory consumption would be enormous.

### 3.3 Sliding Window Counter (The Hybrid Solution)
This is the most practical and widely used algorithm. It combines the memory efficiency of the fixed window with the better accuracy of the sliding window.
*   **How it works:** The time window is divided into smaller "buckets." For a 1-minute window, we might use six 10-second buckets.
*   When a request arrives, we increment the counter for the current bucket.
*   The total request count for the window is calculated by summing the counts of all buckets within the last minute.
*   **Approximation:** To get the count for the sliding window, we can approximate the count from the oldest bucket. For example, if the current time is `12:01:15`, the window is from `12:00:15` to `12:01:15`. We would sum the full counts of the buckets from `12:00:20` to `12:01:10`, and then add an estimated 50% of the count from the `12:00:10 - 12:00:20` bucket.
*   **Benefits:** It's much more memory-efficient than the sliding window log and avoids the edge burst problem of the fixed window counter.

### 3.4 Token Bucket
This algorithm is intuitive and excellent for controlling burstiness. It's used by major platforms like Amazon and Google.
*   **Concept:** Imagine each user has a bucket of a fixed size. This bucket is continuously refilled with "tokens" at a steady rate.
    *   **Bucket Size:** The maximum number of tokens the bucket can hold. This represents the maximum allowed burst of requests.
    *   **Refill Rate:** The number of tokens added to the bucket per second. This represents the sustained request rate.
*   **How it works:**
    1.  When a request arrives, the system checks if there is at least one token in the bucket.
    2.  If a token is available, it is consumed, and the request is allowed.
    3.  If the bucket is empty, the request is rejected.
*   **Behavior:** This naturally allows for bursts. A user can consume all 100 tokens in their bucket instantly. However, after that, they can only make new requests at the rate the tokens are refilled (e.g., 10 tokens per second).

---

## 4. Storage and Implementation

The rate limiter needs an extremely fast way to store and update counters.

*   **Storage Choice: Redis**
    An in-memory data store like Redis is the perfect choice for this task. It's incredibly fast because it operates in RAM.
*   **Why Redis?**
    *   **Speed:** Reads and writes are sub-millisecond.
    *   **Atomic Operations:** Redis provides commands like `INCR` that increment a key's value and return the new value in a single, atomic operation. This is crucial for avoiding race conditions in a distributed environment.
    *   **Key Expiration:** Redis can automatically delete keys after a set time-to-live (TTL) using the `EXPIRE` command. This is perfect for managing time windows without needing a separate cleanup process.

*   **Example Redis Implementation (Fixed Window):**
    To implement a limit of 100 requests per minute for `user123`:
    1.  Generate a key: `rate:user123:1678886400`
    2.  Execute `INCR rate:user123:1678886400`.
    3.  If this is the first request in this window, set an expiration: `EXPIRE rate:user123:1678886400 60`.
    4.  If the returned value from `INCR` is greater than 100, reject the request.
    *   These operations can be combined into a single atomic script using Redis Lua scripting for even better performance.

---

## 5. Distributed Architecture

In a real-world system, you will have multiple instances of your API gateway running.

### 5.1 The Race Condition Problem
If each gateway instance has its own local rate limiter, the limits will be inaccurate.
*   **Scenario:** The limit is 100 requests/minute.
*   Gateway A receives 60 requests from `user123`. It thinks the user is within the limit.
*   Gateway B receives 50 requests from `user123`. It also thinks the user is within the limit.
*   **Result:** `user123` has made a total of 110 requests, exceeding the limit, but both gateways allowed the traffic.

### 5.2 The Centralized Store Solution
The solution is to use a centralized data store that is shared by all gateway instances. **Redis** is the ideal candidate for this. All gateways read from and write to the same Redis instance (or cluster), ensuring that the request count is consistent and accurate across the entire system.

### 5.3 Scaling the Centralized Store
A single Redis instance can become a bottleneck. To handle millions of users, we need to scale Redis itself.
*   **Sharding:** We can use a **Redis Cluster** to partition the data. The keys (e.g., `rate:user_id`) are distributed across multiple Redis nodes.
*   **Partition Key:** The `user_id` or IP address is used as the partition key. A hash function (`hash(user_id) % num_shards`) determines which Redis node is responsible for that user's counter, ensuring that all requests for a single user go to the same node.

---

## 6. Failure Handling

What happens if the rate limiter's data store (Redis) goes down?

1.  **Fail Open:** The system allows all requests to pass through.
    *   **Pros:** Prioritizes availability. Legitimate users are not blocked.
    *   **Cons:** The backend is left unprotected and could be overwhelmed during the outage.
2.  **Fail Closed:** The system rejects all requests.
    *   **Pros:** Prioritizes protecting the backend services.
    *   **Cons:** Causes a complete outage for users, even if the backend is healthy.

**Decision:** Most systems choose to **fail open**. The reasoning is that a temporary surge is often better than a complete service disruption. The backend should have its own basic overload protection as a last line of defense.

---

## 7. Final Architecture Diagram

This diagram shows a complete, scalable, and resilient rate limiting architecture.

```text
[Client] <--> [DNS] <--> [CDN / Edge Network]
                              (Optional Edge Rate Limiting)
                                     |
                                     v
                           [Global Load Balancer]
                                     |
                  +------------------+------------------+
                  |                                     |
                  v                                     v
        [API Gateway 1]                         [API Gateway 2]
                  |                                     |
                  | (INCR, EXPIRE)                      |
                  |                                     |
                  v                                     v
        +-------------------------------------------------+
        |             Redis Cluster (Sharded)             |
        |  [Shard 1]   [Shard 2]   [Shard 3]   [Shard N]  |
        +-------------------------------------------------+
                  ^                                     ^
                  | (Allow / Reject)                    |
                  |                                     |
                  +------------------+------------------+
                                     | (If Allowed)
                                     v
                             [Backend Services]
```

---

## 8. Senior-Level Summary

For a production-grade rate limiter, the design would be a distributed system centered around an API Gateway. The core logic would use a **Sliding Window Counter** or **Token Bucket** algorithm for its balance of accuracy and performance. State management (counters) would be handled by a **sharded Redis cluster** to ensure low latency, atomicity, and horizontal scalability. The partition key for the Redis cluster would be derived from the dimension we are limiting by, such as `user_id` or IP address. The system would be configured to **fail open** to prioritize availability in case of a Redis outage. For very large-scale systems, an additional layer of rate limiting at the **CDN/Edge** can be used to block abusive traffic before it even reaches the main infrastructure. Monitoring key metrics like blocked vs. allowed requests and Redis latency is crucial for observability and detecting attacks.

### Key Interview Insight: Rate Limiting Dimensions

A common mistake is to only consider limiting by user. A robust system must support rules across multiple dimensions, often in combination. For example, a rule might be:
*   Limit by **IP Address** for anonymous users.
*   Limit by **API Key** for authenticated services.
*   A stricter limit on a specific **endpoint** (e.g., `/login`).
*   A global limit for an entire **organization** or customer tier.

A truly scalable design allows these rules to be configured and applied dynamically.
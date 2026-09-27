# 01. Basics

This is where system design stops being abstract. The prerequisites section gave you a way of thinking, requirements, trade-offs, metrics. This section gives you the actual building blocks that get combined to build every system in the rest of this repo: networking, scalability, databases, caching, messaging, reliability, and consistency.

Almost every HLD discussion later boils down to picking and combining pieces from this section. If you understand these seven topics well, HLD stops feeling like magic and starts feeling like assembly.

## What's covered

**01. Networking**
How requests actually travel through a system. HTTP and HTTPS, stateless vs stateful services, REST, gRPC vs REST, the full request lifecycle from DNS to load balancer to backend to database and back, timeouts and retries with exponential backoff and jitter, and common networking anti-patterns like long synchronous chains and chatty APIs.

**02. Scalability**
What scalability actually means and what happens when a system doesn't have it. Vertical vs horizontal scaling, stateless vs stateful services from a scaling lens, load balancing (types, algorithms, health checks), auto-scaling, bottlenecks and single points of failure, ways to scale a database (read replicas, sharding, NoSQL), and the reminder that scaling isn't just about adding servers, caching, async processing, CDNs, and rate limiting all play a part.

**03. Databases**
Why the database is usually the bottleneck and how to choose one. The core questions to ask before picking a database, SQL and its ACID guarantees, the four major NoSQL categories (key-value, document, wide-column, graph) with when to use each, a direct SQL vs NoSQL comparison, read-heavy vs write-heavy architecture patterns, how indexes work and their write-side cost, data growth and retention with hot/warm/cold tiers, and database anti-patterns like using a table as a message queue or over-indexing.

**04. Caching**
Why caching exists (the latency hierarchy from CPU cache to cross-region network calls says it all), where caching can live (client, CDN, server-side local or distributed), the four cache access patterns (cache-aside, read-through, write-through, write-behind), eviction policies (LRU, LFU, FIFO, TTL), the classic cache consistency problems (stale data, thundering herd, cache penetration), and failure modes like cache avalanche, cache drift, and the cold start problem.

**05. Messaging**
Why asynchronous processing exists, framed around what happens to a "Buy Now" click if every step has to finish synchronously. Sync vs async communication, how message queues work end to end (publish, receive, process, acknowledge), delivery semantics (at-least-once, at-most-once, exactly-once, and why at-least-once with idempotent consumers is the practical default), queues vs event streams, a look at RabbitMQ, Kafka, SQS, and Pub/Sub, when to reach for async messaging and when not to, retry strategies and dead-letter queues, and why ordering and idempotency matter.

**06. Reliability**
How systems survive real-world failure. The difference between reliability (is the output correct) and availability (is the system up), redundancy at the service, infrastructure, and data level, replication strategies (leader-follower, multi-leader, leaderless) and how replication differs from backups, failover (active-passive vs active-active), health checks and monitoring, graceful degradation, circuit breakers, backpressure, disaster recovery with RTO and RPO, and common reliability anti-patterns like infinite retries and manual failover.

**07. Consistency**
Probably the most misunderstood topic in system design, so this file builds it up from intuition. What consistency actually means (when will others see my write), why it's fundamentally a trade-off tied to CAP, strong consistency vs eventual consistency with where each is appropriate, read-your-writes as a middle ground, why cache consistency specifically is hard, how distributed databases achieve consistency (leader-based replication, quorum reads and writes, multi-leader conflict resolution, vector clocks), a decision framework for picking a consistency level, and consistency anti-patterns.

## How to go through this section

Go in order. Networking and scalability set up the vocabulary (stateless services, load balancers, replicas) that databases, caching, messaging, reliability, and consistency all build on. Databases and caching pair closely together, read them back to back if you can. Messaging, reliability, and consistency are the three topics that show up the most in HLD interviews once you get past the basic "design X" prompt, so don't rush them.

For each file, the same approach from the prerequisites section applies: read once for the big picture, then again slower, actually working through the trade-offs and the "why," not just the "what." A few of these files (scalability, databases, messaging, reliability, consistency) are long. That's intentional, these are the topics that come up again and again across every canonical problem in `02-hld`, so the time spent here pays off repeatedly later.

Once this section feels solid, you're ready for `02-hld`, where these building blocks get combined to design actual systems end to end.
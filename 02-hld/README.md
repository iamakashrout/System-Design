# 02. High-Level Design (HLD)

This is where the repo turns into interview prep for real. Everything in `00-prerequisites` and `01-basics` was about building vocabulary and understanding individual pieces. This section is about the actual skill interviewers are testing: given a vague problem statement and a blank whiteboard, can you turn it into a working architecture, explain your reasoning, and defend your trade-offs.

HLD answers the question "what components do we need and how do they fit together," as opposed to LLD (`03-lld`), which answers "how do I actually code one of those components." If `00` and `01` gave you the vocabulary, this section is where you learn to hold a conversation in it.

This is also, by a wide margin, the meatiest section in the repo so far. The seven concept files here run 300 to 550 lines each. Take your time.

## What's covered

**01. HLD Approach**
The master framework for tackling any HLD problem, and honestly the one file in this whole repo you could argue is the most important. Opens with the classic distinction between HLD (the architect's blueprint) and LLD (the interior designer's plan), then walks through the senior engineer mindset (clarity before cleverness, constraints before solutions) before laying out an eight step process: clarify requirements, define the system boundary, identify core entities and data flow, build the happy path architecture, scale the bottlenecks one at a time, state trade-offs out loud, handle failure modes, and plan extensions. Closes with a full worked example (designing Pastebin) and a memorizable canonical interview flow.

**02. API Design & Data Modelling**
Why APIs and data models are the hardest things to change once they're live, so they deserve more thought upfront than anything else. Covers resource-oriented design (nouns, not verbs), the asymmetry between read and write APIs, idempotency at the HLD level, chatty vs chunky interfaces, API versioning, designing data models around access patterns rather than "what looks clean," normalization vs denormalization, the trade-offs between auto-increment IDs, UUIDs, and Snowflake-style generated keys, and the rule that each service should own its own data.

**03. Core HLD Components**
The actual Lego bricks you'll be assembling in every design: load balancers (L4 vs L7, algorithms, health checks), stateless application services (the "cattle vs pets" framing), databases as the source of truth, caches, message queues and streams, and blob/object storage. Ends with a canonical high-level architecture template and a worked "flash sale system" example that shows all these pieces working together.

**04. Data Partitioning & Scaling Strategies**
What happens when a single machine physically can't hold your data or handle your traffic anymore. Covers vertical vs horizontal scaling, the three sharding strategies (range-based, hash-based, consistent hashing) and how to choose a good partition key, replication for read scaling and availability, hot keys and data skew (and how to fix them), the pain of rebalancing and resharding, and geo-partitioning for global systems.

**05. Reliability, Availability & Failure Handling**
An HLD-flavored deep dive into keeping systems alive. Reliability vs availability as distinct properties, single points of failure, redundancy patterns at the service, database, and load balancer level, active-passive vs active-active failover, graceful degradation, cascading failures and how to stop them (circuit breakers, bulkheads, timeouts, backpressure), retries and idempotency, SLOs vs SLAs vs error budgets, the four levels of disaster recovery (backup and restore, pilot light, warm standby, hot standby), observability, and even a section on chaos engineering.

**06. Consistency, Performance & Trade-offs**
CAP theorem from an interview-answering angle, CP vs AP systems with concrete examples, the three consistency models (strong, eventual, causal/session), when to prioritize correctness over latency and vice versa, read-optimized vs write-optimized system design, sync vs async processing, throughput vs latency, storage trade-offs (memory vs SSD vs disk, structured vs blob), cache consistency strategies, and a comparison of two-phase commit vs the Saga pattern for distributed transactions. Closes with a five-question decision framework you can run through for any design.

**07. Common System Design Patterns**
A reference catalogue of the patterns that show up again and again: monolith vs microservices vs modular monolith, CQRS, event-driven architecture, the Saga pattern (choreography vs orchestration), backpressure, rate limiting (token bucket, leaky bucket, fixed and sliding window), circuit breaker, bulkhead, cache-aside, write-ahead log, idempotency, the strangler fig pattern for migrations, and fan-out/fan-in. Less narrative than the other files, more of a lookup table for when you're deep dive.

**09. HLD Interview Communication**
Not architecture, communication. How to actually run an HLD interview: the 6-step structure (clarify requirements, define APIs, high-level architecture, deep dive, scaling, trade-offs) with rough time budgets for each, how to design iteratively instead of jumping to the final system, thinking out loud, using diagrams well, handling interruptions, asking the interviewer for direction instead of guessing, managing your time, common mistakes candidates make, and what interviewers are actually scoring you on. Worth rereading the night before an interview.

### `08-canonical-problems/`
Where everything above gets applied end to end. Seven fully worked designs, each following the process from file `01`: requirements, data model, API design, baseline architecture, then scaling it up piece by piece, handling failures, and landing on a final production-grade architecture (usually with a diagram).

| Problem | What it's really teaching you |
|---|---|
| **01. URL Shortener** | The "hello world" of HLD. Read-heavy scaling, caching, ID generation, hot key handling. The right first problem to attempt. |
| **02. Feed System** | Fan-out on write vs fan-out on read, the celebrity problem (what happens when one account has 50 million followers), feed ranking. |
| **03. Chat System** | The most detailed file in the whole directory. Real-time connections (WebSockets), message ordering, offline sync, delivery guarantees, presence, partitioning by conversation. |
| **04. Distributed Storage System** | Dropbox/Drive style design. Chunking and deduplication, sync conflict resolution, separating metadata from actual file storage. |
| **05. Notification System** | Fan-out across channels (push, email, SMS), retries, and reliability when a downstream provider (APNS, Twilio, SES) is flaky. |
| **06. Rate Limiter** | Where a rate limiter should live in your stack, the four major algorithms, and how to make rate limiting work across a distributed fleet instead of just one box. |
| **07. Payments System** | The deepest and most senior-level problem here. Authorization vs capture, idempotency as a hard requirement (not a nice-to-have), webhooks, ledger systems, fraud detection, refunds. Save this one for last. |

## How to go through this section (this is the part that matters)

**Read 01 first, no exceptions.** It's the framework everything else hangs off. If you try to jump into a canonical problem without internalizing the eight-step process from file 01, you'll end up pattern-matching memorized architectures instead of actually reasoning through a new problem, which falls apart the moment an interviewer changes one constraint on you.

**Then read 02, 03, 04, 05, 06 in order, and treat 07 as a reference, not a read-through.** Files 02 to 06 build the muscle you actually flex during a design: shaping APIs and data, knowing your components, scaling data, keeping things reliable, and reasoning about consistency trade-offs. File 07 (patterns) is denser and more list-like by design, it's meant to be skimmed once so you know it exists, then revisited whenever a canonical problem or a real design needs a specific pattern.

**Only after that, move to `08-canonical-problems/`.** Go in the order listed in the table above, it's intentionally easy-to-hard: URL Shortener first to get the process under your fingers on a simple problem, Payments last because it pulls on almost everything in files 01 to 07 at once (idempotency from 02, reliability from 05, transactions from 06, several patterns from 07, all at once).

For every canonical problem, resist the urge to just read it top to bottom. Read the requirements section, stop, and try to sketch your own baseline architecture and scaling plan before reading the repo's version. The value of this section is in the struggle, not in recognition. Comparing your attempt against the worked solution afterward is what actually builds the instinct.

**Read file 09 last, or alongside your first mock interview.** It's not architecture content, it's about performance under interview conditions, and it lands better once you've actually built a few designs and have a feel for where you personally tend to ramble, freeze, or skip steps.

Once this section feels solid, `03-lld` is next, where the "how do I actually code this component" question that HLD deliberately leaves open finally gets answered.
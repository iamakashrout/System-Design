# 00. Prerequisites

Before you can design any system, you need a shared vocabulary and a way of thinking. This section is that starting point. It doesn't touch databases, caching, or any specific technology yet. It just builds the mental model everything else in this repo sits on top of.

If you skip this section and jump straight to HLD, you'll keep running into terms and reasoning patterns that assume you already have this foundation. Five short files, but they carry a lot of weight.

## What's covered

**01. What Is System Design**
Clears up what system design actually means, and just as importantly, what it isn't (it's not drawing fancy diagrams or name-dropping Kafka and Redis). Breaks the process down into three steps: understanding requirements, decomposing the problem into components, and choosing trade-offs. Walks through a URL shortener as a running example to show the thinking in action.

**02. Functional vs Non-Functional Requirements**
The difference between what a system must do (functional) and how well it should do it (non-functional: latency, availability, scalability, consistency, durability, security). Explains why two systems with identical features can end up with completely different architectures depending on their non-functional requirements.

**03. Core Metrics**
An intuition level, not a spreadsheet level, introduction to latency (and why tail latency at p95/p99 matters more than the average), throughput and QPS, availability (and what each extra "9" actually costs in engineering effort), and rough storage growth estimation.

**04. Trade-offs and CAP Theorem**
System design is really the art of deciding what to give up. Covers common trade-offs (consistency vs availability, latency vs accuracy, cost vs scale) and then goes into CAP theorem the practical way: not as a formula to memorize, but as a lens for understanding how a system behaves specifically during a network partition, and why real systems end up leaning CP or AP.

**05. API-First Thinking**
Why designing your APIs before writing any code matters, what a basic request lifecycle looks like from client to load balancer to service to storage and back, the difference between synchronous and asynchronous APIs and when to use each, and idempotency: why retries are inevitable in distributed systems and why your APIs need to survive being called twice by accident.

## How to go through this section

Read the five files in order, they're numbered that way on purpose. Each one is short enough to read in a few minutes, but don't rush it. The goal at this stage isn't to memorize definitions, it's to build the instinct of asking "what are the requirements, what are the trade-offs" before jumping to a solution. That instinct is what the rest of the repo assumes you already have.

Once this feels comfortable, move on to `01-basics`, where these ideas start getting applied to actual system components.
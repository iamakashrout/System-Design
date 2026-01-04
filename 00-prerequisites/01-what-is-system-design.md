## 0.1 What Is System Design?

System design is the process of defining architecture, components, data flow, and trade-offs to build a system that meets given requirements at scale.

At its core, system design answers:
> Given a real-world problem and constraints, how do we build a system that works correctly today and continues to work as users, data, and traffic grow?

---

### What System Design Is NOT

- Drawing fancy diagrams without understanding
- Memorizing architectures of big tech companies
- Randomly naming tools like Kafka, Redis, Kubernetes

A complex-looking system that solves no real constraint is a bad design.

---

### What System Design Actually Is

#### 1. Understanding Requirements
Before designing anything, clarify:
- Functional requirements (what the system must do)
- Non-functional requirements (scale, latency, availability, consistency, cost)

Example:
> Build a URL shortener

Questions to ask:
- How many users?
- How many URLs per day?
- Is low latency more important than strong consistency?
- Do we need analytics?

Without clear requirements, design becomes guesswork.

---

#### 2. Decomposing the Problem
Large systems are broken into smaller components, each with a clear responsibility.

Example (URL shortener):
- API service to accept URLs
- ID generation logic
- Database to store URL mappings
- Redirect service
- Optional analytics service

Good design comes from clear separation of concerns.

---

#### 3. Choosing Trade-offs
You cannot optimize everything at once.

Common trade-offs:
- Consistency vs Availability
- Cost vs Performance
- Simplicity vs Scalability

Good system design is about choosing the right trade-off for the problem, not the “best” or most complex solution.

---

### Simple Example

For a URL shortener with:
- 10 million users
- 100 million redirects per day
- Read-heavy traffic

Reasonable choices:
- Use a relational database for correctness
- Add caching to reduce database load
- Accept eventual consistency for analytics
- Avoid over-engineering early

System design is:
Requirements → Decomposition → Trade-offs → Justified decisions

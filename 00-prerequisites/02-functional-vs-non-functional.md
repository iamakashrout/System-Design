## 0.2 Functional vs Non-Functional Requirements

Requirements define what you build and how you build it.  
Even a well-structured architecture fails if requirements are misunderstood.

---

### Functional Requirements

Functional requirements describe what the system must do.

They answer:
> What capabilities must the system provide?

Examples:
- User can shorten a URL
- User can retrieve the original URL
- User can send a message
- User can upload a file

Key characteristics:
- Feature-focused
- Describe system behavior
- Do not include performance or scale

Functional requirements are usually explicit and straightforward.

---

### Non-Functional Requirements

Non-functional requirements describe how well the system should perform.

They answer:
> How fast, reliable, secure, and scalable should the system be?

Common non-functional requirements:
- Latency (response time)
- Availability (uptime)
- Scalability
- Consistency
- Durability
- Security

These requirements heavily influence architecture and introduce trade-offs.

---

### Why This Matters

Two systems with the same functional requirements can have completely different designs based on non-functional requirements.

Examples:
- If availability > consistency → choose highly available systems
- If low latency is critical → caching becomes essential
- If durability is required → persistent and replicated storage is needed

Ignoring non-functional requirements leads to fragile or over-engineered systems.

---

### Example: URL Shortener

#### Functional Requirements
- Create a short URL
- Redirect to the original URL

#### Non-Functional Requirements
- Read-heavy system
- Low latency redirects
- High availability
- Eventual consistency acceptable

#### Design Impact
- Optimize for reads using caching
- Replicate data for availability
- Accept eventual consistency for better scalability

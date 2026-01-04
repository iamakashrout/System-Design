## 0.5 API-First Thinking

APIs are the contracts of a system.  
Once APIs are in use, changing them becomes expensive.

Designing APIs first helps define system boundaries and makes systems easier to evolve.

---

### Why API-First?

APIs define:
- How clients interact with the system
- How internal services communicate
- Clear system boundaries

Well-designed APIs:
- Reduce coupling
- Allow independent scaling
- Enable safe evolution of the system

Poor APIs make systems rigid and fragile.

---

### Basic Request Lifecycle

Most systems follow this request flow:

1. Client sends a request
2. Load balancer routes it to a healthy service
3. Service processes business logic
4. Data is read from or written to storage
5. Response is returned to the client

This pattern appears in almost every backend system.

---

### Synchronous vs Asynchronous APIs

#### Synchronous

In synchronous APIs:
- Client waits for the response
- Immediate success or failure is returned

Used for:
- Reads
- Validations
- User-facing operations

Trade-offs:
- Higher latency
- Tighter coupling between services

---

#### Asynchronous

In asynchronous APIs:
- Client does not wait for completion
- Work is processed in the background

Used for:
- Emails and notifications
- Logging and analytics
- Background jobs

Trade-offs:
- Increased system complexity
- Requires retries and monitoring

---

### Idempotency

Idempotency means calling an API multiple times produces the same result as calling it once.

Why it matters:
- Network failures cause retries
- Duplicate requests are common in distributed systems

Examples:
- Retrying a payment request should not charge twice
- Retrying order creation should not create duplicate orders

Idempotency is essential for correctness in distributed systems.

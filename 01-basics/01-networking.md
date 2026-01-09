## 1.1 Networking Basics (System Design View)

Networking defines how requests move through a system.  
Every system design ultimately reduces to a request traveling over the network and a response coming back.

---

### 1.1.1 Why Networking Matters in System Design

Networking impacts:
- Latency
- Reliability
- Scalability

Poor networking choices lead to slow, fragile systems.

---

### 1.1.2 HTTP (What You Must Know)

HTTP is a stateless request–response protocol used between clients and servers.

Key properties:
- Stateless
- Text-based
- Widely supported

#### HTTP Request (Conceptual)
- Method (GET, POST, PUT, DELETE)
- URL
- Headers
- Body (optional)

#### HTTP Response
- Status code
- Headers
- Body

---

### Common HTTP Methods (Design Relevance)

| Method | Use Case |
|------|--------|
| GET | Fetch data |
| POST | Create resource |
| PUT | Update resource |
| DELETE | Remove resource |

---

### Status Codes (High-Level)

| Code Range | Meaning |
|----------|--------|
| 2xx | Success |
| 4xx | Client error |
| 5xx | Server error |

Too many 5xx → reliability issues  
Too many 4xx → API misuse or bad clients

---

### 1.1.3 HTTPS (Why It’s Non-Negotiable)

HTTPS = HTTP + TLS (Transport Layer Security) encryption.

It ensures that data traveling between the client and server cannot be read or tampered with by attackers.

#### How TLS Works (Simplified)
1. **Asymmetric Encryption (Handshake):**
   - The server has a **Public Key** (shared with everyone) and a **Private Key** (kept secret).
   - When a client connects, the server sends its Public Key via a digital certificate.
   - The client uses the Public Key to encrypt a secret and sends it back. Only the server's Private Key can decrypt this.

2. **Symmetric Encryption (Session):**
   - Once the identity is established, both sides generate a shared **Session Key**.
   - This Session Key is used to encrypt the actual data transfer because it is much faster than asymmetric encryption.

This process guarantees:
- **Encryption:** Only the client and server can read the data.
- **Authentication:** You know you are talking to the real server (verified by the certificate).
- **Integrity:** The data has not been modified in transit.

All production traffic is expected to be HTTPS.

---

### 1.1.4 Stateless vs Stateful Services

#### Stateless Services
In a stateless architecture, the server does not retain any information (state) about the client's previous requests in its memory.

- **How it works:** Every request must contain all the necessary information (like a user ID or token) for the server to process it.
- **Scaling:** Since no server holds specific user data, any server in a cluster can handle any request. You can add more servers easily.
- **Example:** A "Shopping Cart" service where the cart items are stored in a database or Redis cache, not on the server itself. If the server crashes, the cart is safe in the database.

#### Stateful Services
In a stateful architecture, the server remembers data from previous requests in its local memory or disk.

- **How it works:** The server maintains a session for the user.
- **Sticky Sessions:** The load balancer must ensure that all requests from a specific user go to the *same* server that holds their state.
- **Scaling Issues:** If that specific server goes down, the user's session data (e.g., their login status or unsaved work) is lost. Adding new servers is harder because existing sessions are tied to specific machines.

Rule:
Prefer stateless services for backend systems.  
Externalize state using a database or distributed cache (like Redis) to allow easy scaling.

---

### 1.1.5 REST APIs (System Design Perspective)

REST stands for **Representational State Transfer**. It is the most common architectural style for web APIs.

#### Core Concepts
- **Resources:** Everything is a resource (e.g., a User, an Order, a Product).
- **Uniform Interface:** You interact with resources using standard HTTP methods (GET, POST, PUT, DELETE) rather than custom function names.
- **Stateless:** The server does not keep client context between requests.
- **Representation:** Resources are usually represented as JSON (JavaScript Object Notation) or XML.

#### Example
Instead of an endpoint like `/createNewUser` or `/updateUserAddress`, REST uses:
- `POST /users` (Create a user)
- `PUT /users/123` (Update user 123)
- `GET /users/123` (Get details of user 123)

REST is preferred for its simplicity, scalability, and the fact that it works over standard HTTP.

---

### 1.1.6 gRPC vs REST

gRPC (Google Remote Procedure Call) is a modern, high-performance framework for API communication.

#### Key Differences from REST
- **Protocol:** Uses **HTTP/2**, which allows multiple requests to be sent over a single connection (multiplexing), making it faster than REST's HTTP/1.1.
- **Data Format:** Uses **Protocol Buffers (Protobuf)** instead of JSON. Protobuf is binary, meaning the data is compressed into a much smaller size, reducing network usage.
- **Strict Contracts:** You define the API structure in a `.proto` file, and code is automatically generated for both client and server. This prevents errors where the client sends the wrong data type.

#### When to use gRPC?
- **Internal Microservices:** Great for communication between backend services where low latency is critical.
- **Mobile Apps:** Good for saving bandwidth.
- **Not for Browsers:** Browser support is limited compared to REST/JSON.

| Aspect | REST | gRPC |
|------|------|------|
| Protocol | HTTP/1.1 | HTTP/2 |
| Data | JSON (Text) | Protobuf (Binary) |
| Performance | Moderate | High |
| Debugging | Easy (Human readable) | Harder (Binary data) |
| Browser support | Excellent | Limited |

Default to REST for public APIs; consider gRPC for internal service-to-service communication.

---

### 1.1.7 Request Lifecycle

Understanding the journey of a request helps identify bottlenecks.

1. **Client sends request:** The user clicks a button, and the browser/app initiates an HTTP request.
2. **DNS Resolution:** The system translates the domain name (e.g., `api.mysite.com`) into an IP address.
3. **Load Balancer:** The request hits the entry point of the data center. The load balancer selects a healthy server to handle the work.
4. **Backend Processing:** The application server runs business logic (e.g., "Is this password correct?").
5. **Data Access:** The server queries a database or cache to fetch or save data. This is often the slowest part.
6. **Response:** The server sends the result back through the load balancer to the client.

---

### 1.1.8 Timeouts & Retries

Distributed systems fail. How you handle failure defines reliability.

#### Timeouts
A timeout is the maximum time a client waits for a response.
- **Why:** Without timeouts, a slow service can cause the client to hang forever, consuming resources (threads/memory) until the system crashes.
- **Advice:** Always set a timeout (e.g., 5 seconds). If the server doesn't answer, give up.

#### Retries
If a request fails (e.g., network blip), trying again might fix it.
- **Exponential Backoff:** Don't retry immediately. Wait 1s, then 2s, then 4s. This gives the failing server time to recover.
- **Jitter:** Add random time to the wait (e.g., 1.1s, 2.3s) so that all clients don't retry at the exact same moment (thundering herd problem).

---

### 1.1.9 Networking Anti-Patterns

Avoid these common mistakes to build scalable systems.

- **Long Synchronous Chains:** Service A calls B, which calls C, which calls D. If D is slow, A is slow. If D fails, A fails. This creates a "fragile" system.
- **Missing Timeouts:** Assuming the network is always fast. This leads to cascading failures where one slow service takes down the whole system.
- **Chatty APIs:** Making 10 separate requests to get user details, orders, and preferences. Instead, use a single API call (e.g., GraphQL or a composite endpoint) to fetch everything at once.
- **Stateful Backend Services:** Storing user sessions on specific servers makes auto-scaling difficult and deployment risky.

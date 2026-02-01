# 2.2 API Design & Data Modeling (HLD Perspective)
> APIs and data models are long-term contracts.  
> Bad ones force bad architectures later.

This phase is about **shaping the system**.

You can fix slow queries.
You can add caches.
You can scale infra.

But **poor APIs and sloppy data models are extremely expensive to undo**.

Senior engineers treat APIs and data models as **foundational decisions**, not implementation details.

---

## 1. Senior Mindset for APIs & Data Models

### 1.1 Why APIs Matter
APIs are not just URL endpoints; they are **boundaries between teams and systems**.

-   **Boundaries:** An API defines where your responsibility ends and another team's begins. A messy API blurs these lines, leading to "distributed monoliths" where teams cannot deploy independently.
-   **Contracts:** Once you release an API, you cannot easily change it. Clients (mobile apps, 3rd party partners) depend on the specific structure. Breaking an API breaks their application.
-   **Scaling Shape:** Your API dictates how your system scales. If you expose a "batch upload" API, you must design for high-throughput writes. If you expose a "real-time stream", you need WebSockets.

**Example:**
-   *Junior:* "I'll just change the JSON key from `userId` to `user_id` because it looks cleaner."
-   *Senior:* "I cannot change that key because 1 million mobile apps have the old version installed. I must support both or version the API."

### 1.2 Why Data Models Matter
Data models are the **source of truth**.

-   **Gravity:** Data is heavy. Moving 100TB of data from one schema to another is a massive engineering project. Code is light; you can rewrite a service in a week. You cannot migrate a massive database in a week.
-   **Constraint on Evolution:** If you model a "User" to only have one "Email", and later business wants multiple emails, you have to run complex migrations on live tables.

**Example:**
-   *Junior:* "I'll store the user's address as a comma-separated string in the `users` table."
-   *Senior:* "We need to query users by city later. We must normalize this into an `addresses` table or use a JSONB column with an index, otherwise, that query will require a full table scan."

---

## 2. Start with Resources, Not Endpoints

A common beginner mistake is designing APIs based on **verbs** (actions). This leads to RPC (Remote Procedure Call) style APIs which are brittle and hard to learn.

### The Problem with Verbs
❌ **Bad (Action-based):**
-   `/getUser`
-   `/createPost`
-   `/updateThing`
-   `/deleteComment`

**Why it fails:**
1.  **Explosion of Endpoints:** You end up with thousands of unique URLs like `/updateUserEmail`, `/updateUserAddress`, `/suspendUser`.
2.  **Unpredictable:** A developer has to read documentation for every single action to know the URL.

### The Senior Approach: Resources (Nouns)
Design around **Resources** — the "things" your system manages.

✅ **Good (Resource-based):**
-   `User`
-   `Post`
-   `Comment`

**Why it works:**
-   You use standard HTTP verbs (`GET`, `POST`, `PUT`, `DELETE`) on these nouns.
-   If you know the resource is `users`, you can guess the API: `GET /users/123`.

**Example:**
Instead of `/banUser?id=123`, you treat the "Ban" as a state of the User resource or a separate resource:
-   `PATCH /users/123` with body `{"status": "banned"}`
-   OR `POST /users/123/bans` (Creating a "Ban" resource).

---

## 3. Resource-Oriented API Design (HLD-Level)

Resource-Oriented Design (often implemented as REST) uses standard HTTP methods to manipulate resources.

### 3.1 What it looks like
You have **Collections** (lists of things) and **Singletons** (individual things).

| Action | HTTP Verb | URL Pattern | Description |
| :--- | :--- | :--- | :--- |
| **Create** | `POST` | `/orders` | Creates a new order in the collection. |
| **Read (List)** | `GET` | `/orders` | Fetches a list of orders. |
| **Read (One)** | `GET` | `/orders/{id}` | Fetches a specific order. |
| **Update** | `PUT` | `/orders/{id}` | Replaces the entire order. |
| **Patch** | `PATCH` | `/orders/{id}` | Partially updates fields (e.g., status). |
| **Delete** | `DELETE` | `/orders/{id}` | Removes the order. |

### 3.2 Advantages
1.  **Predictability:** Developers can guess your API structure.
2.  **Caching:** HTTP has built-in caching semantics for `GET` requests.
3.  **Statelessness:** Each request contains all info needed, allowing easy scaling.

### 3.3 Issues if ignored
If you build an ad-hoc API (e.g., everything is a `POST`):
-   **No Caching:** CDNs and browsers won't cache `POST` requests, increasing server load.
-   **Tight Coupling:** Clients are tied to specific function names on the server.
-   **Tooling:** You lose the ecosystem of tools (Swagger/OpenAPI) that expect RESTful patterns.

### 3.4 Example: URL Shortener
**Resource:** `URLMapping`

-   `POST /urls`: Create a short link.
-   `GET /urls/{short_code}`: Get the original link (or redirect).
-   `DELETE /urls/{short_code}`: Remove the link.

This is simple, standard, and robust.

---

## 4. Read vs Write APIs (Critical HLD Insight)

In high-scale systems, **Reads** and **Writes** have fundamentally different requirements.

### 4.1 The Asymmetry
-   **Reads:** Often 100x or 1000x more frequent than writes (e.g., Twitter: millions read a tweet, one person wrote it). They need **speed** and **caching**.
-   **Writes:** Need **consistency**, **validation**, and **durability**. They are slower.

### 4.2 Designing for the Difference (CQRS Light)
Do not force reads and writes to use the same model or infrastructure.

**Write Path (`POST /tweets`):**
1.  Validate input (length, profanity).
2.  Write to Primary Database (Source of Truth).
3.  Trigger async jobs (Fan-out to followers, Analytics).
4.  **Goal:** Correctness.

**Read Path (`GET /feed`):**
1.  Read from a Read Replica or a Cache (Redis).
2.  Return pre-computed data.
3.  **Goal:** Low Latency.

### 4.3 Example: E-Commerce Product
-   **Write (`POST /products`):** Complex. Checks inventory, updates search index, resizes images. Latency: 500ms.
-   **Read (`GET /products/{id}`):** Simple. Fetches JSON from CDN or Cache. Latency: 20ms.

If you coupled these, your read performance would be limited by your write complexity.

---

## 5. Idempotency (Without LLD Details)

### 5.1 Definition
Idempotency means that making the same request multiple times has the **same effect** as making it once.
> `f(f(x)) = f(x)`

### 5.2 Why it matters
Networks are unreliable.
1.  Client sends `POST /pay` ($100).
2.  Server charges card.
3.  Server sends "Success" response.
4.  **Network fails.** Client never gets the response.
5.  Client retries `POST /pay`.

**Without Idempotency:** The user is charged $200.
**With Idempotency:** The server sees it's the same request and returns "Success" without charging again.

### 5.3 How to do it (Briefly)
Use an **Idempotency Key** (a unique ID generated by the client, e.g., UUID).
-   Client sends `Idempotency-Key: abc-123`.
-   Server checks: "Have I seen `abc-123`?"
    -   **Yes:** Return the saved response.
    -   **No:** Process payment and save `abc-123`.

### 5.4 Consequence of Absence
-   Duplicate transactions.
-   Corrupted data counters.
-   Loss of user trust.

---

## 6. API Granularity & Chatty Interfaces

Granularity refers to how much data a single API call returns.

### 6.1 Chatty Interfaces (Bad)
A "chatty" interface requires the client to make many small requests to get what they need.

**Scenario:** Render a User Profile.
1.  `GET /users/123` (Get Name)
2.  `GET /users/123/posts` (Get Post IDs)
3.  `GET /posts/1` (Get Post 1)
4.  `GET /posts/2` (Get Post 2)
...

**Problems:**
-   **Latency:** Mobile networks have high overhead per request. 10 requests = 10x latency.
-   **Battery:** Drains mobile battery.
-   **Complexity:** Client handles error states for 10 different calls.

### 6.2 Chunky / Composite Interfaces (Good)
A "chunky" interface returns aggregated data relevant to a business use case.

**Scenario:** Render a User Profile.
1.  `GET /users/123?include=posts,recent_comments`

**Result:** One request returns the user, their last 5 posts, and metadata.

**Implementation:**
-   **Backend for Frontend (BFF):** A specific API layer that aggregates data for the UI.
-   **GraphQL:** Allows clients to ask for exactly what they want in one request.

---

## 7. Versioning & Evolution (HLD View)

APIs are contracts, but business requirements change. Versioning allows you to evolve without breaking existing clients.

### 7.1 Why it matters
You cannot force every user to update their mobile app instantly. You will have users on App v1.0 and App v2.0 hitting your servers simultaneously.

### 7.2 How it is done
1.  **URI Versioning (Most Common):**
    -   `https://api.example.com/v1/users`
    -   `https://api.example.com/v2/users`
    -   *Pros:* Explicit, easy to cache.
2.  **Header Versioning:**
    -   `Accept: application/vnd.example.v1+json`
    -   *Pros:* Cleaner URLs. *Cons:* Harder to test in browser.

### 7.3 Backward Compatibility
Always prefer **non-breaking changes** over new versions.
-   **Safe:** Adding a new field (`phone_number`). Old clients just ignore it.
-   **Unsafe:** Renaming a field (`email` -> `email_address`) or deleting a field. This breaks old clients.

**Strategy:**
> "We will support v1 for 6 months after v2 is released."

---

## 8. Data Modeling — Start with Access Patterns

In HLD, you do not design the database schema based on "real life objects". You design it based on **how the application queries the data**.

### 8.1 What are Access Patterns?
These are the specific questions your application asks the database.
-   "Get user by ID"
-   "Get all orders for a user, sorted by date"
-   "Get top 10 posts in the last hour"

### 8.2 The Process
1.  List all Read/Write operations.
2.  Prioritize them (which ones happen 1000x/sec?).
3.  Design the model to make the priority queries fast (O(1) or O(log n)).

### 8.3 Example: Chat Application
**Bad Model (Relational thinking without access patterns):**
-   Table `Messages` with `id`, `text`.
-   Query: "Show me the last 50 messages for Room A".
-   If you didn't index `room_id` and `timestamp`, this query scans the whole table.

**Good Model (Access Pattern First):**
-   **Access Pattern:** "Get messages by Room, sorted by Time".
-   **Model:**
-   **Partition Key:** `room_id` (Groups messages together).
-   **Sort Key:** `timestamp` (Keeps them sorted physically).
-   **Result:** The database jumps directly to `Room A` and reads the last 50 items sequentially. Extremely fast.

---

## 9. Normalization vs Denormalization (HLD Framing)

### 9.1 Normalization
Organizing data to reduce redundancy. Data lives in one place.
-   **Example:** `User` table has `name`. `Orders` table references `user_id`.
-   **Pros:** Consistency. Update name in one place, it updates everywhere.
-   **Cons:** Reads require **JOINS** (`Orders` + `User`). Joins are slow at massive scale.
-   **Use Case:** User profiles, payment info, inventory (things that change and need consistency).

### 9.2 Denormalization
Duplicating data to optimize reads.
-   **Example:** `Orders` table stores `user_id` AND `user_name`.
-   **Pros:** **No Joins**. You can fetch the order and display the user name instantly.
-   **Cons:** Complexity on Update. If user changes name, you must update `User` table AND all rows in `Orders` table.
-   **Use Case:** Social feeds, comments, historical data (e.g., an invoice should keep the address *at the time of purchase*, even if the user moves).

### 9.3 Senior Decision
> "We will normalize core entities (Users) but denormalize read-heavy views (Feeds) to avoid joins."

---

## 10. Identifiers & Keys (Design Signal)

Choosing the primary key affects scalability and physics.

### 10.1 Auto-Increment Integer (`1, 2, 3...`)
-   **Pros:** Small storage (4-8 bytes), easy to read.
-   **Cons:**
    -   **Security:** Leaks volume (User 100 knows there are only 100 users).
    -   **Sharding:** Hard to distribute. If DB1 has 1-1M and DB2 has 1M-2M, DB2 is empty until DB1 fills up (Hotspot).
-   **Use Case:** Small internal tools.

### 10.2 UUID (Universally Unique Identifier)
-   **Pros:** Globally unique. Can be generated by the client (offline support). Stateless.
-   **Cons:** Large (16 bytes). Unordered (bad for database indexing performance/fragmentation).
-   **Use Case:** Public resource IDs.

### 10.3 Snowflake / KGS (Key Generation Service)
-   **Pros:** Sortable by time (roughly), unique, distributed, fits in 64 bits (smaller than UUID).
-   **Cons:** Requires a dedicated service or complex setup (Twitter Snowflake).
-   **Use Case:** High-scale systems (Twitter Tweets, Instagram Posts) where sorting by time is critical.

---

## 11. Data Ownership & Service Boundaries

### 11.1 The Golden Rule
> **One Service, One Database.**

### 11.2 Why?
If Service A and Service B both talk to the same Database:
-   **Coupling:** Service A changes the schema, Service B crashes.
-   **Logic Leakage:** Service A writes data one way, Service B writes it another way. Data corruption ensues.

### 11.3 The Pattern
-   **Service A** owns the data.
-   **Service B** wants the data.
-   **Solution:** Service B calls Service A's API. Service B **never** touches Service A's database.

### 11.4 Example
-   **Order Service:** Owns `Orders` table.
-   **Payment Service:** Needs to know order total.
-   **Bad:** Payment Service queries `SELECT * FROM orders`.
-   **Good:** Payment Service calls `GET /orders/123/total`.

---

## 12. Strong HLD Signal Statements (Use These)

These phrases show you understand the **implications** of your design.

-   **"This API reflects the system boundary."**
    -   *Impact:* You are thinking about decoupling teams and services.
-   **"This data model optimizes for our dominant read path."**
    -   *Impact:* You prioritized the user experience (reads) over developer convenience (writes).
-   **"We accept denormalization to reduce latency."**
    -   *Impact:* You understand the trade-off between storage/consistency and speed.
-   **"Writes are centralized; reads are horizontally scalable."**
    -   *Impact:* You know how to scale databases (Read Replicas).
-   **"The API contract is stable even if implementation changes."**
    -   *Impact:* You value backward compatibility and client stability.

---

## Final Mental Model

APIs define boundaries.
Data models define reality.
Everything else adapts around them.

If these are solid, scaling becomes an engineering problem — not a rewrite.
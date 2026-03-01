# Canonical Problem 3: Chat System (e.g., WhatsApp, Slack)

This document outlines the High-Level Design (HLD) for a scalable chat system.

---

## 1. Step 1 — Clarify Requirements

Defining the scope is the most critical step. A chat application can be deceptively complex, so we must distinguish between the core MVP and features that can be added later.

### 1.1 Functional Requirements

**Core Features (MVP):**
1.  **1:1 Messaging:** The system's primary function. A user must be able to select another user and send them a text-based message.
2.  **Near Real-Time Delivery:** When both users are online, a message sent by User A should appear on User B's screen almost instantly. This implies a persistent connection model (like WebSockets) rather than simple HTTP polling.
3.  **Message Ordering:** Within a single conversation, messages must be delivered in the exact order they were sent. If a user sends "Message 1" then "Message 2", the recipient must see them in that sequence. This is a strict requirement.
4.  **Message Persistence:** Messages should not be lost if the user closes the app or loses their connection. All conversations must be stored durably on the server and be retrievable when the user logs back in or installs the app on a new device.
5.  **Online/Offline Support:** The system must gracefully handle users who are not actively connected. Messages sent to an offline user must be stored and delivered as soon as they come back online. This requires a presence system to track user status.
6.  **Message Status:** Users need feedback on the state of their sent messages. This includes:
    *   **Sent:** The message has been successfully received by the server.
    *   **Delivered:** The message has been pushed to the recipient's device.
    *   **Read:** The recipient has opened the conversation and viewed the message.

**Extended Features (In-Scope for this design):**
*   **Group Chat:** Users should be able to create conversations with multiple participants.

**Out of Scope (for this design):**
*   Media Messages (images, videos, files)
*   End-to-End Encryption
*   Typing Indicators
*   Message Edits/Deletes
*   Push Notifications

By explicitly defining what we are *not* building, we can focus on creating a robust core system.

### 1.2 Non-Functional Requirements

These requirements dictate the architecture and infrastructure choices.

*   **Scale:** The system must be designed for a massive, global user base.
    *   **Users:** 100 Million Daily Active Users (DAU).
    *   **Connections:** 10 Million concurrent connections at peak times.
    *   **Throughput:** 1 Million messages per second during peak load.
*   **Latency:** For online users, the end-to-end message delivery latency should be **< 100ms** (p95). This is the threshold for a "real-time" feel.
*   **Availability:** The system requires very high availability (e.g., 99.99%). A messaging system is a critical communication utility; downtime is highly disruptive. Every component must be redundant.
*   **Durability (No Message Loss):** Once the server acknowledges a message (i.e., the "Sent" status), it must never be lost. This implies durable storage and reliable processing.
*   **Consistency:** Eventual consistency is acceptable for features like read receipts across a user's multiple devices. However, **strong ordering consistency** is required for messages within a single conversation.

### Key Observation

This is a **stateful, connection-heavy, and write-heavy system**.
*   **Stateful & Connection-Heavy:** Unlike a stateless REST API where a client connects, gets a response, and disconnects, a chat system requires servers to maintain persistent connections for millions of users simultaneously. The server must keep track of the state of each connection (e.g., which user is connected to which server).
*   **Write-Heavy:** While users may read old messages, the primary load comes from new messages being written to the system, especially at a scale of 1 million messages per second. The architecture must be optimized for high-throughput writes.

---

## 2. High-Level Architecture Overview

To meet our requirements, we will separate the system into logical layers. This allows each layer to be scaled independently based on its specific load profile.

```text
Client (Mobile/Web)
        ↓
Load Balancer
        ↓
Chat Gateway (WebSocket / TCP Servers)
        ↓
Message Service (Stateless Logic)
        ↓
   ┌────┴────┬──────────┐
   ↓         ↓          ↓
Message   Kafka      Redis
Store     (Stream)   (Presence/Cache)
(Database)
```

### Component Breakdown

*   **Client (Mobile/Web):** The user-facing application. Its primary responsibility in this architecture is to initiate and maintain a persistent connection (e.g., a WebSocket) to a Chat Gateway server.

*   **Load Balancer (LB):** The entry point to our system. It handles the initial HTTP requests for establishing a connection and distributes them across the available Chat Gateway servers.

*   **Chat Gateway:** A fleet of servers optimized for one job: managing hundreds of thousands of persistent connections per server. This is the **stateful** part of our connection layer. It receives messages from online clients and is responsible for pushing messages down to other online clients. It contains minimal business logic.

*   **Message Service:** A stateless service that contains the core business logic. When a gateway receives a message, it forwards it here. The Message Service is responsible for:
    1.  Authenticating the user.
    2.  Assigning a unique `message_id`.
    3.  Persisting the message to the Message Store.
    4.  Publishing the message to an event stream (Kafka) for asynchronous delivery.

*   **Message Store (Database):** The source of truth for all messages. This will be a distributed NoSQL database (like Apache Cassandra) that is partitioned by `conversation_id`. This choice supports the high write throughput and ensures all messages for a single chat are co-located for efficient queries.

*   **Kafka (Event Stream):** The asynchronous backbone of the system. Publishing a message to Kafka decouples the initial write from the fan-out and delivery process. This makes the system highly resilient. Separate worker services will consume from Kafka to handle pushing the message to the recipient, updating analytics, etc.

*   **Redis (Cache & Presence):** A distributed in-memory key-value store used for two critical, high-speed functions:
    1.  **Presence Tracking:** Storing the online/offline status of all users and mapping each online `user_id` to the specific Chat Gateway they are connected to.
    2.  **Caching:** Caching frequently accessed data like user profiles or conversation metadata to reduce database load.

### The Importance of Separation

This separation of concerns is a key design decision. We isolate different types of workloads so they can be scaled independently:
*   The **Connection Layer** (Gateways) is connection-heavy.
*   The **Message Processing Layer** (Message Service) is CPU-heavy.
*   The **Storage Layer** (Database) is I/O-heavy.
If we need to handle more concurrent users, we add more Gateway servers. If message processing becomes a bottleneck, we add more instances of the Message Service. This modularity is essential for building a scalable and resilient system.

## 3. Real-Time Connection Layer

Standard HTTP is insufficient for a chat application because it is request-response based. We cannot ask the user to refresh their screen every second to check for new messages (polling); this would drain battery and overload servers.

### 3.1 The Solution: Persistent Connections (WebSockets)
Instead of HTTP, we use **WebSockets**.
*   **How it works:** The client establishes a handshake with the server. Once established, this connection remains **open**.
*   **Bi-directional:** Data can flow both ways instantly. The server can "push" a message to the client the moment it arrives.

### 3.2 Analogy: The Phone Call vs. The Mailbox
*   **HTTP (Polling):** Imagine checking your physical mailbox. You walk to the curb, open the box, look inside. Empty. You walk back. 10 minutes later, you do it again. This is inefficient and slow.
*   **WebSocket:** Imagine a telephone call. You dial the number, the other side picks up, and the line stays open. You don't hang up. When the other person speaks, you hear it *immediately*. You don't need to ask "did you say anything?" repeatedly.

### 3.3 The Chat Gateway
Maintaining millions of open phone lines (WebSockets) requires a specialized component. We call this the **Chat Gateway**.
*   **Role:** It is a "dumb" pipe. Its only job is to hold the connection open and route bytes. It does not process business logic.
*   **Stateful:** This layer is **stateful**. Gateway-1 knows that "User A" is connected to it. Gateway-2 knows "User B" is connected to it.
*   **Scaling:** Because it holds open TCP connections, we scale this layer based on the number of concurrent users (RAM/File Descriptors), not CPU.

---

## 4. Message Flow (End-to-End)

Let's trace the lifecycle of a single message to understand how the components interact.

**Scenario:** Alice sends "Hello" to Bob.

### Step 1: Sending (Alice → Gateway → Service)
1.  Alice types "Hello" and hits send.
2.  Her app sends the payload over her persistent WebSocket connection to **Gateway A**.
3.  **Gateway A** forwards the request (via gRPC or HTTP) to the **Message Service**.

### Step 2: Processing (Message Service)
The **Message Service** is the brain. It performs the following actions:
1.  **Validation:** Is Alice allowed to message Bob?
2.  **ID Generation:** Generates a unique, sortable `message_id` (e.g., using Snowflake).
3.  **Persistence:** Saves the message to the **Message Store** (Database).
4.  **Acknowledgment:** Sends an "ACK" back to Alice so her app shows a single checkmark (Sent).

### Step 3: Routing (Service → Presence → Gateway)
Now the system must deliver the message to Bob.
1.  The Message Service queries **Redis (Presence)**: "Is Bob online? If so, which gateway is he on?"
2.  **Scenario A (Bob is Online):** Redis returns "Bob is on Gateway B".
    *   The Service sends the message to **Gateway B**.
    *   **Gateway B** pushes the message down Bob's WebSocket.
    *   Bob's app displays the message and sends an ACK back.
3.  **Scenario B (Bob is Offline):** Redis returns null.
    *   The Service triggers a **Push Notification** (via Apple APNS / Google FCM) to wake up Bob's device.

### Step 4: Fan-out (Async)
In parallel to Step 3, the Message Service publishes the message to a **Kafka** topic.
*   **Why?** Other services need this data.
    *   **Search Service:** Indexes the message so Alice can search for "Hello" later.
    *   **Analytics:** Updates daily message counts.
    *   **Push Service:** Handles the offline notification logic if needed.

---

## 5. Message Data Model

The database schema is critical for performance. We need to store billions of messages and retrieve conversation history quickly.

### 5.1 The Schema
We use a **Wide-Column NoSQL Database** (like Cassandra or ScyllaDB).

**Table: `messages`**
*   `conversation_id` (Partition Key): The unique ID of the chat (1:1 or Group).
*   `message_id` (Clustering Key): A time-sortable unique ID (e.g., Snowflake).
*   `sender_id`: Who sent it.
*   `content`: The text body (encrypted).
*   `status`: Sent/Delivered/Read.
*   `created_at`: Timestamp.

### 5.2 Why this Model?
1.  **Partitioning by `conversation_id`:**
    *   This is the most important decision. It ensures that **all messages for a specific chat live on the same database node**.
    *   **Read Efficiency:** When Alice opens her chat with Bob, the database goes to *one* partition and reads the messages sequentially from disk. This is an extremely fast O(1) lookup, regardless of how many other users are in the system.
2.  **Clustering by `message_id`:**
    *   Within the partition, messages are physically sorted by `message_id`. Since we use time-sortable IDs (Snowflake), this automatically keeps the chat history in chronological order. We don't need to sort the data in memory after fetching it.
3.  **Write Throughput:**
    *   Chat systems are write-heavy. Cassandra is optimized for high-speed writes (LSM Trees), making it ideal for ingesting millions of messages per second.

---

## 6. Message Ordering

A critical requirement is that messages within a single chat must appear in the exact order they were sent.

### The Challenge
In a distributed system with multiple servers, if Alice sends "Message 1" and then "Message 2", these two messages could be processed by different servers and get reordered. We must prevent this.

### The Solution: Kafka Partitions
The solution is to ensure that all messages from the same conversation are processed by the same, single "thread" in a serial fashion.
1.  **Kafka Guarantees:** Kafka provides a strict ordering guarantee *within a single partition*.
2.  **Partitioning by `conversation_id`:** We configure our Kafka topic to use the `conversation_id` as the partition key. A hash of this key determines which partition the message lands in.
3.  **The Result:** Since all messages for the same `conversation_id` will have the same hash, they are guaranteed to be placed in the **same partition**. A consumer group will then have a single consumer responsible for reading from that partition, processing the messages one by one in the exact order they were received.

**Analogy:** Imagine a set of mail sorters (Kafka partitions). If all letters for a specific address (`conversation_id`) are always put into the same sorting bin (partition), and one person (consumer) sorts that bin from top to bottom, the letters will always be in the correct order.

---

## 7. Offline Support & Message Sync

A chat system is useless if messages are lost when a user is offline.

### How it Works
1.  **Persistence is Key:** When Alice sends a message to Bob (who is offline), the message is still processed by the `Message Service` and saved durably in the `Message Store` (Cassandra). The system doesn't consider the message "sent" until it's persisted.
2.  **Presence Check:** The service checks Redis and sees Bob is offline. No attempt is made to deliver the message in real-time.
3.  **User Reconnects:** When Bob comes back online, his client needs to ask the server, "What did I miss?"
4.  **Syncing with an Offset:** The client sends the `message_id` of the last message it successfully received for each conversation. This is the "offset". The server then queries the `Message Store` for all messages in that conversation where `message_id` is greater than the offset provided by the client.

**Analogy:** Think of it as a post office holding your mail while you're on vacation. The mail (messages) is safely stored in your mailbox (the database). When you get back, you tell the postmaster the date of the last letter you received (the offset). They then give you all the mail that has arrived since that date.

---

## 8. Delivery Guarantees & Idempotency

We need to ensure messages are delivered reliably, which means handling network failures.

### At-Least-Once Delivery
We choose an **"at-least-once"** delivery semantic. This means the system guarantees a message will be delivered, but under certain failure conditions (like a network timeout), it might be delivered more than once. This is a trade-off for reliability over efficiency.

### The Problem with Duplicates
If a message is delivered twice, we don't want it to appear twice in the chat UI. The client must be able to handle duplicates.

### The Solution: Acknowledgement (ACK) and Idempotency
1.  **Unique ID:** Every message has a globally unique `message_id`.
2.  **ACK Flow:**
    *   The server pushes a message to the client.
    *   The client's UI displays the message.
    *   The client immediately sends an "ACK" (acknowledgment) packet back to the server, containing the `message_id` it just received.
    *   The server records that this user has successfully received this message.
3.  **Idempotent Client:** The client maintains a list of `message_id`s it has already processed and displayed. If the server, due to a network error, resends a message the client has already ACKed, the client will see the `message_id` in its list and simply ignore the duplicate. This makes the client's message-handling logic **idempotent**.

**Analogy:** This is like signing for a package. The delivery driver (server) gives you a package (message). You sign their device (send an ACK). If their device glitches and doesn't record your signature, they will try to deliver the same package to you again tomorrow. Because you're smart, you check the tracking number (`message_id`) and tell them, "I already signed for this one," and you don't take the duplicate package.

---

## 9. Group Chat Scaling

Group chats introduce a fan-out problem. When a user sends a message to a group of 1,000 people, we don't want to write 1,000 copies of that message to the database. That would be a "write storm".

### The Solution: Store Once, Deliver Many
The correct approach is to store the message once and deliver a "pointer" to it to all group members.

1.  **Single Write:** The message is written only **once** to the `messages` table, tagged with the `conversation_id` of the group.
2.  **Fan-out on Delivery:** The `Message Service` fetches the list of all members in that group.
3.  **Individual State Tracking:** For each member in the group, the system treats it like a 1:1 delivery.
    *   It checks the member's presence status in Redis.
    *   If the member is online, it pushes the message to their connected gateway.
    *   If the member is offline, nothing happens in real-time.
4.  **Personalized Cursors:** The key is that each user maintains their own "read cursor" (e.g., `last_read_message_id`) for every conversation they are in, including groups. When a user opens a group chat, their client asks for all messages in that `conversation_id` after their personal cursor.

This model is highly efficient. A message to a group with a million members still only results in a single database write. The "fan-out" work is handled by the stateless delivery and notification systems, which are designed to scale horizontally.

**Analogy:** A professor posts a single notice on a central university bulletin board (the `Message Store`). All students in the class (group members) are responsible for reading it. The professor doesn't write 1,000 individual copies. Each student remembers the last notice they read (their personal `last_read_message_id`). When they check the board, they just read the new notices posted since their last visit.

---

## 10. Presence System

The presence system is the "who's online?" service. It's a high-throughput, low-latency service that tracks two things:
1.  The online/offline status of every user.
2.  For online users, which specific Chat Gateway server they are connected to.

### Implementation with Redis
Redis is perfect for this due to its speed and support for key expiration.
*   **Connecting:** When a user connects to `Gateway-A`, the gateway executes a Redis command: `SET user_id:Alice gateway:Gateway-A EX 60`. This sets a key for Alice, points it to her gateway, and sets a 60-second Time-To-Live (TTL).
*   **Heartbeat:** The client sends a small "heartbeat" message to the gateway every 30 seconds. When the gateway receives a heartbeat, it re-runs the `SET ... EX 60` command, refreshing the TTL.
*   **Disconnecting:** If the user disconnects gracefully, the gateway can explicitly `DEL user_id:Alice`.
*   **Crashing:** If the client or gateway crashes, the heartbeats stop. The 60-second TTL expires, and Redis automatically deletes the key. The user is now considered offline. This is a simple and robust self-healing mechanism.

---

## 11. Partitioning Strategy

Partitioning (or sharding) is how we scale our databases horizontally. We split the data across multiple servers so no single server is a bottleneck. The choice of partition key is critical.

*   **Messages Database (Cassandra):**
    *   **Partition Key: `conversation_id`**.
    *   **Why:** This is the most important decision in the design. It ensures all messages for a single chat are physically stored together on the same node. When a user opens a chat, the database can perform a highly efficient sequential read from one location, rather than gathering data from multiple nodes. This makes fetching chat history extremely fast.

*   **Presence Database (Redis Cluster):**
    *   **Partition Key: `user_id`**.
    *   **Why:** A user's presence is independent of other users. Hashing the `user_id` distributes the presence data evenly across all nodes in the Redis cluster, balancing the load.

---

## 12. Failure Handling

A distributed system must be designed with the assumption that components will fail.

*   **Chat Gateway Failure:**
    *   **Symptom:** A gateway server crashes. All WebSocket connections to it are terminated.
    *   **Recovery:** The client's logic will detect the dropped connection and automatically attempt to reconnect. The Load Balancer will not route new connections to the dead gateway and will instead direct the client to a healthy one. The client then uses its last known `message_id` offset to sync any messages it missed during the downtime.

*   **Message Service / Kafka Failure:**
    *   **Symptom:** The service that processes messages or the Kafka queue itself becomes slow or unresponsive.
    *   **Recovery:** Kafka is designed for this. It acts as a massive buffer. Messages from the gateways will pile up in Kafka's durable log. They are not lost. When the downstream services recover, they will begin processing the backlog of messages. The system experiences a delay, but no data is lost.

*   **Database Failure:**
    *   **Symptom:** The primary database node for a partition goes down.
    *   **Recovery:** We use a primary-replica configuration. All writes go to the primary, which replicates them to multiple replicas. If the primary fails, an automated system detects the failure and promotes one of the replicas to be the new primary. This might cause a few seconds of write-unavailability for the affected partitions, but the system remains largely available and no data is lost.

---

## 13. Write Path vs. Read Path

This is a **write-heavy system**. While users do read old messages, the dominant load at scale comes from new messages being written (1 million per second). Therefore, the write path must be exceptionally fast.

*   **Write Path Optimization:**
    *   A new message is a simple `INSERT` into our database.
    *   In a Log-Structured Merge-tree (LSM) database like Cassandra, an insert is an append-only operation to an in-memory table (memtable) and a sequential write to a commit log. This is extremely fast.
    *   The `Message Service` can further optimize by batching multiple messages together into a single write operation to the database.

*   **Read Path Behavior:**
    *   The most common read is fetching the recent history for a single conversation.
    *   Because we partitioned our database by `conversation_id`, this read is a highly-optimized sequential scan on a single database node. It's fast, but the primary architectural focus is on making the write path scale.

---

## 14. Scaling Connections

Handling 10 million concurrent connections is a major infrastructure challenge.

*   **Back-of-the-Envelope Math:**
    *   **Goal:** 10,000,000 concurrent connections.
    *   **Capacity per Gateway:** Assume a single gateway server can handle 100,000 persistent connections.
    *   **Result:** We need at least **100 Chat Gateway servers** (10,000,000 / 100,000).

*   **Connection Management:**
    *   We need a **Layer 4 (TCP) Load Balancer** that can maintain "sticky sessions". Once a client establishes a WebSocket connection with `Gateway-57`, all subsequent packets for that connection must be routed to `Gateway-57`.
    *   **Consistent Hashing** can be used to map users to gateways. This helps distribute the load evenly and minimizes disruptions when we add or remove gateways from the pool.

---

## 15. Latency Breakdown (p95)

To achieve a sub-100ms real-time feel, we must budget our latency at each step for an online-to-online message.

1.  **Client → Gateway:** Network latency over the public internet. (Variable, but let's budget **~20ms**).
2.  **Gateway → Message Service:** Internal network call (e.g., gRPC). ( **~5ms**).
3.  **Message Service Processing:**
    *   Generate ID, validate, etc. ( **~5ms**).
    *   Write to Kafka & Message Store (Cassandra). These are parallel, fast appends. ( **~15ms**).
4.  **Message Service → Recipient's Gateway:** Another internal network call. ( **~5ms**).
5.  **Gateway → Recipient's Client:** Push message down the recipient's WebSocket. ( **~20ms**).

**Total Estimated Latency:** 20 + 5 + 5 + 15 + 5 + 20 = **70ms**. This is well within our <100ms target, leaving buffer for network variability.

---

## 16. Advanced Topics (To Mention in an Interview)

*   **End-to-End Encryption (E2EE):** To implement E2EE, the server becomes "dumb" to message content. The client encrypts the message with the recipient's public key before sending. The server just transports this encrypted blob. The recipient's client then decrypts it with their private key. The server cannot read the message.

*   **Message Search:** The primary message store is not optimized for full-text search. To enable search, a separate consumer reads messages from Kafka and indexes them in a dedicated search cluster like **Elasticsearch**. User search queries are then routed to this cluster.

*   **Backfill / Sync on New Device:** When a user logs in on a new phone, it has no chat history. The client will make API calls to fetch the history for recent conversations. To avoid downloading gigabytes of data, this is often limited to the last N messages or conversations, with older history being fetched on-demand when the user scrolls up.

*   **Multi-Region Deployment:** To serve a global user base with low latency, the entire architecture is duplicated in multiple geographic regions (e.g., US-East, EU-West, AP-Southeast). Geo-DNS routes users to their nearest region. Keeping a user's data and connections within their "home" region is a key strategy to reduce cross-region latency.

---

## 17. Common Interview Pitfalls

A weak chat system design often misses one or more of these critical aspects. A strong design must address them.

*   **Ignoring Persistent Connections:** Failing to use WebSockets or a similar technology and relying on HTTP polling, which is not scalable for real-time communication.
*   **No Message Ordering Strategy:** Not having a clear plan for how to prevent messages in a single chat from being delivered out of order.
*   **No Offline Handling:** Designing only for the "happy path" where both users are online, without a mechanism to store and forward messages for offline users.
*   **No Delivery/Read Acknowledgement:** Lacking a mechanism for the client to confirm message delivery, which is essential for reliability and features like read receipts.
*   **No Partitioning Plan:** Treating the database as a single black box without explaining how it will scale to handle billions of messages.

---

## 18. Senior-Level Summary

A scalable chat system is a stateful, connection-heavy, and write-heavy system that prioritizes reliability and ordered delivery. The architecture is fundamentally event-driven, built around a core of persistent WebSocket connections managed by a dedicated Gateway layer. The write path is optimized for high throughput using append-only storage models and asynchronous processing via event streams like Kafka. Message ordering is strictly guaranteed on a per-conversation basis by partitioning both the storage and processing layers by `conversation_id`. Reliability is achieved through an "at-least-once" delivery semantic, with idempotency handled at the client and service layers to prevent duplicates.

The key sentence to deliver this with confidence is:

> **“We will shard messages by `conversation_id` to co-locate chat history for fast reads, use Kafka partitions keyed by the same `conversation_id` to guarantee ordered processing, manage millions of persistent WebSocket connections via a dedicated gateway layer, and rely on an ACK-based, idempotent delivery mechanism to ensure reliability.”**

---

## 19. Final Production Architecture Diagram

This diagram illustrates the end-to-end flow of messages and data through the various components of the system.

```text
                                       [Push Notification Service]
                                         ^ (APNS / FCM)
                                         | (If Offline)
[Client] <---> [DNS] <---> [Global Load Balancer (L4)]
                                         |
                                         v
                           [Chat Gateway Cluster (Stateful)]
                           (Manages WebSocket Connections)
                                         |
                                         | (gRPC/HTTP)
                                         v
                                [Message Service (Stateless)]
                               /        |         \
                              /         |          \
                 (Check Presence)       | (Persist)   \ (Publish Event)
                            v           v            v
                     [Presence DB]  [Message Store]  [Kafka Topic]
                     (Redis Cluster)  (Cassandra)   (new_messages)
                                                       |
                                                       v
                                               [Worker Services]
                                               (Search Indexer, Analytics, etc.)
```
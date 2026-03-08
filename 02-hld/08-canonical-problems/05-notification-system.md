# Canonical Problem 5: Notification System (High-Level Design)

This document outlines the High-Level Design (HLD) for a scalable notification system capable of sending millions of alerts via Email, SMS, Mobile Push, and In-App notifications.

---

## 1. Step 1: Requirements Clarification

### 1.1 Functional Requirements
*   **Multi-Channel Support:** The system must support sending notifications via:
    *   **Mobile Push:** (iOS APNS, Android FCM)
    *   **Email:** (e.g., Welcome emails, receipts, marketing)
    *   **SMS:** (e.g., 2FA codes, urgent alerts)
    *   **In-App:** (Notification bell inside the web/mobile app)
*   **User Preferences:** Users must be able to opt-in/out of specific channels or notification types (e.g., "Don't send me marketing emails, but send me order updates via SMS").
*   **Templating:** Support for dynamic content insertion (e.g., "Hello {name}, your order {id} is ready").
*   **Scheduling:** Ability to schedule notifications for a future time.

### 1.2 Non-Functional Requirements
*   **Scale:**
    *   100 Million registered users.
    *   10 Billion notifications per day (mostly during peak hours or marketing campaigns).
*   **Reliability:** Critical notifications (2FA, Password Reset) must never be lost. Marketing notifications can tolerate minor loss.
*   **Latency:** Real-time notifications (messages, security alerts) should be delivered in seconds.
*   **Spiky Traffic:** The system must handle massive bursts (e.g., breaking news, Black Friday sales) without crashing.
*   **Deduplication:** Users should not receive the same notification multiple times due to retries.

---

## 2. Core Entities and Data Model

### 2.1 Entities
*   **Notification Request:** Represents the raw event triggering a notification.
    *   `event_id`, `user_id`, `event_type` (e.g., ORDER_PLACED), `payload` (JSON).
*   **User Preference:** Stores settings for each user.
    *   `user_id`, `channel_type`, `is_enabled`, `frequency_cap`.
*   **Notification Log:** Tracks the status of every sent message.
    *   `notification_id`, `user_id`, `channel`, `status` (PENDING, SENT, FAILED), `retry_count`, `created_at`.

### 2.2 Database Choice
*   **Preferences DB:** A relational database (PostgreSQL/MySQL) or a document store (MongoDB) works well. It requires fast reads and is often cached.
*   **Notification Log DB:** This is write-heavy and grows indefinitely. A NoSQL database like **Cassandra** or **DynamoDB** is ideal because of high write throughput and easy TTL (Time-To-Live) management for old logs.

---

## 3. High-Level Architecture

The system is designed as a **Producer-Consumer** architecture using message queues to decouple the services triggering notifications from the services delivering them.

### 3.1 Components
1.  **Notification Service (API):** The entry point. Internal services (Billing, Shipping, Social) call this API to trigger a notification. It performs basic validation and rate limiting.
2.  **Message Queue (Kafka/RabbitMQ):** Acts as a buffer. It absorbs traffic spikes and ensures no data is lost if downstream workers are slow.
3.  **Workers:** Stateless servers that consume messages from the queue, process them (render templates, check preferences), and call external providers.
4.  **Third-Party Providers:** External services that actually deliver the messages (e.g., Amazon SES for Email, Twilio for SMS, FCM/APNS for Push).

---

## 4. Detailed Design & Workflows

### 4.1 The Send Flow
1.  **Trigger:** An internal service (e.g., Order Service) calls `POST /send` on the Notification Service.
2.  **Validation:** The service validates the payload and ensures the user exists.
3.  **Enqueue:** The service publishes a message to a **Notification Queue**. It returns `202 Accepted` to the caller immediately.
4.  **Processing:** A **Notification Worker** pulls the message.
5.  **Preference Check:** The worker queries the **Preferences Cache/DB**. If the user has opted out, the process stops.
6.  **Template Rendering:** The worker fetches the appropriate template (e.g., HTML for email) and fills in the user's data.
7.  **Delivery:** The worker calls the appropriate 3rd-party provider API.
8.  **Logging:** The result (Success/Failure) is written to the **Notification Log DB**.

### 4.2 Handling Spikes (The Buffer)
During a marketing campaign, millions of requests might arrive in minutes.
*   **Without a Queue:** The database or 3rd-party providers would be overwhelmed, causing timeouts and failures.
*   **With a Queue:** The queue fills up. Workers process messages at their maximum sustainable rate. The system experiences latency (lag), but it does not crash and no messages are lost.

### 4.3 Priority Queues
Not all notifications are equal. A password reset email (Critical) should not be stuck behind 5 million marketing emails (Low Priority).
*   **Solution:** Use separate queues/topics.
    *   `Critical_Queue`: For OTPs, Security Alerts.
    *   `Standard_Queue`: For Social updates, Order updates.
    *   `Batch_Queue`: For Marketing campaigns.
*   **Workers:** Configure more workers to consume from the Critical Queue, or ensure workers always check the Critical Queue before the others.

### 4.4 Notification Fanout (The "Celebrity" Problem)
When a user with 1 million followers posts content, we cannot simply loop through 1 million users and call the API synchronously.
*   **The Flow:**
    1.  **Ingest:** The `Post Service` emits a `POST_CREATED` event.
    2.  **Fanout Service:** A dedicated worker fetches the follower list from the Graph DB.
    3.  **Batching:** The followers are split into batches (e.g., 1,000 users per batch).
    4.  **Enqueue:** Each batch is published as a task to the **Notification Queue**.
    5.  **Parallel Processing:** Hundreds of workers pick up these batches concurrently and send the notifications.

### 4.5 Channel-Specific Considerations
Each delivery channel has unique constraints:
*   **Mobile Push (APNS/FCM):**
    *   Requires managing device tokens (which can change when the app is reinstalled).
    *   **iOS (APNS):** Uses HTTP/2 persistent connections.
    *   **Android (FCM):** Google's infrastructure handles the delivery to the device.
*   **SMS:**
    *   **Cost:** Expensive. Use only for critical alerts (OTP).
    *   **Length:** Strict 160-character limit.
    *   **Compliance:** Requires handling "STOP" messages for opt-out.
*   **Email:**
    *   **Deliverability:** Must handle "Hard Bounces" (invalid email) to maintain sender reputation.
    *   **Size:** HTML templates can be large; avoid embedding heavy images (use links instead).

---

## 5. Reliability and Retries

External providers (Email/SMS gateways) often fail or time out. We need a robust retry mechanism.

### 5.1 Retry Strategy
We do not block the main worker thread waiting for a retry.
1.  **Soft Failure:** If a provider returns a 500 error or timeout, the worker publishes the message to a **Retry Queue** with a delay.
2.  **Exponential Backoff:**
    *   Retry 1: Wait 10s
    *   Retry 2: Wait 60s
    *   Retry 3: Wait 5 mins
3.  **Dead Letter Queue (DLQ):** If the message fails after max retries (e.g., 5), it is moved to a DLQ for manual inspection and alerting.

### 5.2 Deduplication (Idempotency)
If a worker crashes after sending an email but before updating the DB/Queue, the message might be processed again.
*   **Solution:** Use an **Idempotency Key** (e.g., `event_id`). The worker checks a Redis cache to see if `event_id` was already processed in the last hour.

---

## 6. Advanced Features

### 6.1 Rate Limiting
To prevent spamming users or hitting provider quotas.
*   **User-Level:** "Max 5 SMS per hour per user." Implemented using a Redis Token Bucket or Leaky Bucket algorithm.
*   **System-Level:** "Max 10,000 emails per second total." Throttles the workers to protect the 3rd-party provider account reputation.

### 6.2 Notification Templates
Templates should not be hardcoded in the application code.
*   Store templates in S3 or a database.
*   Cache them heavily in Redis.
*   Allow non-engineers to update templates via a dashboard.

### 6.3 In-App Notifications
Unlike Push/Email, In-App notifications are pulled by the client.
*   **Storage:** Store the notification payload in a database (e.g., MongoDB or Cassandra).
*   **Retrieval:** When the user opens the app, the client calls `GET /notifications`.
*   **Real-time:** Use WebSockets to push a "new notification badge" event to the client if they are currently online.

---

## 7. Final Architecture Diagram

```text
                                  [Internal Services]
                                (Billing, Social, etc.)
                                          |
                                          v
                                [Notification Service] <---> [Rate Limiter (Redis)]
                                          |
                                          | (1. Publish Event)
                                          v
                                   [Message Queue]
                                   (Kafka / RabbitMQ)
                                  /       |       \
                           (Critical) (Standard) (Batch)
                                /         |         \
                               v          v          v
                                [Notification Workers] <---> [Preferences DB]
                               /          |           \      (Cache + DB)
                              /           |            \
                 (3. Send Email)    (3. Send SMS)    (3. Send Push)
                        |                 |                 |
                        v                 v                 v
                  [Email Provider]   [SMS Provider]   [Push Provider]
                   (Amazon SES)        (Twilio)        (FCM / APNS)
                        |                 |                 |
                        v                 v                 v
                     [User]            [User]            [User]

           [Notification Log DB] <-------/ (4. Log Status)
           (Cassandra / DynamoDB)
```
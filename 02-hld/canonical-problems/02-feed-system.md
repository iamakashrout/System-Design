# Canonical Problem 2: Social Media Feed System (High-Level Design)

This document outlines the High-Level Design (HLD) for a scalable social media feed system, similar to the home feed on Twitter or Instagram. The design process focuses on handling massive read traffic and the "fan-out" problem.

---

## 1. Step 1: Requirements Clarification

A clear understanding of the requirements is essential to guide the architectural decisions.

### 1.1 Functional Requirements

*   **Post Creation:** Users must be able to publish content (posts) to their followers.
*   **Follow Graph:** Users must be able to follow and unfollow other users.
*   **Home Feed:** Users must see a personalized feed consisting of posts from the users they follow. For the initial design, this feed will be sorted in reverse-chronological order.

**Out of Scope (for initial design):**
*   A complex ranking algorithm (e.g., based on likes, relevance).
*   Likes, comments, and other engagement features.
*   Media uploads (photos, videos). We will assume posts are text-based, but the design will accommodate media URLs.
*   Ads, stories, or advanced privacy controls.

### 1.2 Non-Functional Requirements

*   **Scale:** The system must be designed for a large user base.
    *   **Users:** 100 million Daily Active Users (DAU) with 10 million concurrent users at peak.
    *   **Writes:** 1 million new posts per day. This averages to ~12 posts/sec, but we must handle peak loads of up to **10,000 posts/sec**.
    *   **Reads:** The system is extremely read-heavy. We will assume an average of 5 million feed reads per second during peak hours.
*   **Latency:** The feed must load quickly to ensure a good user experience. The target is **< 200ms** for p95 latency (95% of requests should be faster than 200ms).
*   **Availability:** The system must be highly available (e.g., 99.99%). A feed outage is a critical failure.
*   **Consistency:** Eventual consistency is acceptable. If a user makes a post, it is acceptable for it to take a few seconds to appear in their followers' feeds.

**Core Observation:** This is an extreme read-heavy system (5M reads/sec vs. 10K writes/sec). The entire architecture must be optimized for fast feed reads.

---

## 2. Core Entities and Data Model

We can identify three primary entities. The choice of database for each will depend on its specific access patterns.

*   **User:**
    *   `user_id` (Primary Key)
    *   `username`, `profile_photo_url`, `metadata`
    *   **Database Choice:** A standard RDBMS or Document DB (like MongoDB) is suitable.

*   **Post:**
    *   `post_id` (Primary Key)
    *   `author_id` (Indexed for fetching a user's posts)
    *   `content` (text, media_url)
    *   `created_at`
    *   **Database Choice:** A wide-column NoSQL database like Cassandra is excellent here, as it is optimized for high-throughput writes.

*   **Follow (or UserGraph):**
    *   `follower_id` (The user doing the following)
    *   `followee_id` (The user being followed)
    *   `created_at`
    *   **Database Choice:** While a relational database can model this with a join table, a dedicated **Graph Database** (like Neo4j or Amazon Neptune) is highly optimized for traversing these relationships (e.g., "find all followers of user X").

**The Critical Design Question:** How do we efficiently generate a user's home feed, which involves data from all three entities? This is the central challenge.

---

## 3. Feed Generation Models: The Core Architectural Decision

There are two primary approaches to generating a user's feed. The choice here has massive implications for performance and scalability.

### Model A: Fan-out on Read (Pull Model)

This is the most intuitive but least scalable approach.

*   **How it works:** When a user requests their feed:
    1.  The system first queries the `Follow` table to get a list of all `followee_id`s for the user.
    2.  It then queries the `Post` table for the most recent posts from each of those `followee_id`s.
    3.  Finally, it merges all these posts, sorts them by `created_at`, and returns the top N results.
*   **Problem:** If a user follows 1,000 people, this requires over 1,000 separate database queries, followed by a complex merge-sort operation, all while the user is waiting. At 5 million reads per second, this would instantly overwhelm the database. This model is not viable at scale.

### Model B: Fan-out on Write (Push Model)

This model pre-computes the work to make reads extremely fast.

*   **How it works:** When a user creates a post:
    1.  The system writes the post to the `Post` database.
    2.  It then looks up all the `follower_id`s of the author.
    3.  For each follower, it **pushes** (inserts) the new `post_id` into a pre-computed feed list for that follower. This list is often stored in a fast cache like Redis.
*   **Reading the Feed:** When a user requests their feed, the system simply reads this pre-computed list from the cache. This is a single, fast O(1) operation.
*   **Problem (The "Celebrity" Problem):** This model works well for most users. However, if a celebrity with 50 million followers creates a post, the system must perform 50 million writes to the cache. This "write storm" can overload the system and cause significant delays.

### The Solution: A Hybrid Approach

Neither model is perfect. The industry-standard solution is a hybrid model that combines the strengths of both.
*   **For Normal Users (e.g., < 10,000 followers):** Use the **Fan-out on Write (Push)** model. Writes are manageable, and reads are lightning-fast.
*   **For Celebrities (e.g., > 10,000 followers):** Use the **Fan-out on Read (Pull)** model, but only for them. We do not push their posts to followers' feeds.

When a user requests their feed, the system will:
1.  Fetch the user's pre-computed feed (from normal users they follow).
2.  Separately, fetch the latest posts from any celebrities they follow.
3.  Merge these two lists in the application logic before sending to the client.

This hybrid model optimizes for the common case (fast reads) while gracefully handling the edge case (celebrities).

---

## 4. Detailed System Architecture

### 4.1 Write Path (Creating a Post)

The write path is designed to be asynchronous to keep the user-perceived latency low.

1.  **API Request:** The client sends a `POST /posts` request to the API Gateway / Load Balancer.
2.  **Post Service:** The request is routed to a stateless `Post Service`. This service writes the post content and metadata to the main `Post DB` (e.g., Cassandra).
3.  **Publish Event:** After successfully saving the post, the `Post Service` publishes a `NewPostEvent` message to a message queue like **Apache Kafka**. The message contains `post_id` and `author_id`.
4.  **Fan-out Workers:** A separate group of services, called `Fan-out Workers`, consumes events from the Kafka topic.
5.  **Process Event:** For each event, a worker checks if the `author_id` belongs to a celebrity.
    *   **If Normal User:** The worker fetches the user's followers from the `Follow Graph DB`. It then iterates through the followers and inserts the `post_id` into each follower's feed list in the `Feed Store` (Redis).
    *   **If Celebrity:** The worker does nothing.

### 4.2 Read Path (Fetching the Feed)

The read path is optimized for speed.

1.  **API Request:** The client sends a `GET /feed` request.
2.  **Feed Service:** The request is routed to the `Feed Service`.
3.  **Fetch Pre-computed Feed:** The service queries the `Feed Store` (a sharded Redis cluster) to get the list of `post_id`s for the current `user_id`.
4.  **Handle Celebrities (Hybrid Logic):** The service also checks which celebrities the user follows. It makes separate, parallel requests to fetch the latest posts for those celebrities (likely from another cache layer).
5.  **Merge and Hydrate:** The `Feed Service` merges the two lists of `post_id`s. It then "hydrates" these IDs by fetching the full post content (author, text, etc.) from a cache or the `Post DB`.
6.  **Return Feed:** The final, sorted list of post objects is returned to the client.

---

## 5. Feed Storage and The Celebrity Problem

This section details the core logic of how feeds are stored and how the system handles the critical edge case of users with millions of followers.

### 5.1 Feed Storage Design

The goal of the feed store is to hold a ready-to-display list of post IDs for each user, enabling sub-200ms feed loads.

*   **Technology Choice: Redis**
    A distributed in-memory cache like Redis is chosen because reading from RAM is orders of magnitude faster than reading from disk. To meet the <200ms latency requirement at 5 million reads per second, a disk-based database would be too slow.

*   **Data Structure: Redis `ZSET` (Sorted Set)**
    A Sorted Set is the perfect data structure for this task. Here’s how it's used:
    *   **Key:** A unique identifier for each user's feed, e.g., `feed:<user_id>`.
    *   **Value (the ZSET):**
        *   **Member:** The `post_id`.
        *   **Score:** The post's creation timestamp (e.g., a Unix timestamp).
    *   **Benefit:** Redis automatically stores and maintains the members sorted by their score. When we need to fetch the 50 most recent posts, we can simply ask Redis for the top 50 members with the highest scores. This is an extremely fast and efficient operation.

*   **Memory Management and Feed Trimming**
    A user's feed cannot grow infinitely in memory. We must cap its size.
    *   When a new post ID is pushed to a user's feed, we add it using the `ZADD` command.
    *   Immediately after, we trim the sorted set to a fixed size, for example, the most recent 1000 posts. This is done using a command like `ZREMRANGEBYRANK`, which removes the oldest items. This ensures that memory usage per user is bounded and predictable.

### 5.2 The Celebrity Problem Explained

The "Fan-out on Write" model has a critical flaw: celebrities.

*   **The Problem (A "Write Storm"):** Imagine a celebrity with 50 million followers creates a post. Our fan-out worker would attempt to perform 50 million `ZADD` operations across the Redis cluster. This massive spike in write traffic would:
    *   Overwhelm the fan-out workers and the message queue.
    *   Saturate the network bandwidth to the Redis cluster.
    *   Cause significant delays in feed updates for all other users, as the system struggles to process this single event.

*   **The Hybrid Solution in Detail:**
    We handle celebrities differently to prevent this.
    1.  **Identifying Celebrities:** A user is marked as a "celebrity" with a flag in the User database (e.g., `is_celebrity: true`). This can be set manually or automatically when their follower count exceeds a threshold (e.g., 20,000).
    2.  **Write Path for Celebrities:** When a celebrity posts, the fan-out worker consumes the event, checks the `is_celebrity` flag, and **does nothing**. The post is simply saved in the Post DB, and no fan-out occurs.
    3.  **Read Path (Merging Feeds):** When a regular user who follows celebrities requests their feed, the `Feed Service` performs these steps:
        *   It fetches the user's pre-computed feed from Redis (`feed:<user_id>`). This list contains posts from all the *normal* users they follow.
        *   It fetches the list of celebrities the user follows (this can be cached).
        *   It then performs a "fan-out on read" *only for those celebrities*. It queries a cache for the most recent posts from each celebrity (e.g., from keys like `posts:celebrity:<celeb_id>`).
        *   Finally, the service merges these two lists (the pre-computed feed and the celebrity posts) in memory, sorts them by timestamp, and returns the final, combined feed.

This approach contains the expensive "pull" operation to a small, per-user level, effectively solving the celebrity problem.

---

## 6. Scaling the System

Scaling is addressed by optimizing the read and write paths independently and partitioning the data.

### 6.1 Scaling the Write Path
The write path is scaled using asynchronous processing and horizontal scaling of workers.
*   **Asynchronous Processing with Kafka:** When a user creates a post, the API returns a success message immediately after placing a `NewPostEvent` into a Kafka topic. This makes the user-facing action extremely fast. The heavy work of fanning out the post to follower feeds happens in the background.
*   **Load Buffering:** Kafka acts as a buffer. During a traffic spike (e.g., a major world event), it can absorb millions of incoming post events, allowing the fan-out workers to process them at a steady rate without crashing.
*   **Horizontal Scaling of Workers:** If the event queue in Kafka starts growing, we can simply add more `Fan-out Worker` instances. This allows us to process more events in parallel, thus scaling the fan-out capacity.

### 6.2 Scaling the Read Path
The read path is scaled with multiple layers of caching and content delivery.
*   **Feed Store (Redis):** The primary scaling mechanism is the sharded Redis cluster that holds the pre-computed feeds. We can add more nodes to the cluster to handle more users and more read traffic.
*   **Post Content Cache:** The feed store only contains `post_id`s. To avoid hitting the database to fetch the full content of each post (a process called "hydration"), we use another layer of caching. A large Memcached or Redis cluster will hold the full post objects, keyed by `post_id`.
*   **CDN for Media:** All static assets like images and videos are served from a Content Delivery Network (CDN). The post object contains a URL pointing to the CDN. This offloads the vast majority of bandwidth from our servers and delivers content faster to users globally.

### 6.3 Database Partitioning Strategy
All databases are partitioned (sharded) to distribute data and load across multiple servers.
*   **Feed Store (Redis):** Sharded by `user_id`. A user's feed is a self-contained object, so it makes sense for all data for a single user's feed to live on the same shard.
*   **Post DB (Cassandra):** Sharded by `post_id`. Cassandra is a write-optimized database that scales horizontally, making it ideal for ingesting a high volume of new posts.
*   **User Graph DB (Neo4j/RDBMS):** Sharded by `follower_id`. This keeps the list of people a user follows on a single shard, making the query "get all followees for user X" very efficient.

---

## 7. Advanced Features: Feed Ranking

A simple reverse-chronological feed is not engaging. Feed ranking aims to show users the most relevant content first.

### 7.1 How Ranking Works
Instead of just fetching the latest posts, the process becomes more intelligent:
1.  **Candidate Generation:** The system fetches a large pool of potential posts, for instance, the 500 most recent posts from the user's pre-computed feed.
2.  **Feature Collection:** For each post, a `Ranking Service` gathers data points (features), such as:
    *   **Post Features:** Number of likes, comments, shares; age of the post.
    *   **Author Features:** Is this an author the user interacts with frequently?
    *   **User-Post Interaction:** Has the user liked posts from this author before? Have they hidden posts from this author?
3.  **Scoring:** A Machine Learning model takes these features and calculates a "relevance score" for each post.
4.  **Re-sorting:** The feed is then sorted by this relevance score instead of the timestamp.

### 7.2 Ranking Implementation
*   **Offline Ranking:** The most common approach is to pre-compute ranked feeds for active users periodically (e.g., every hour). A background job generates the ranked feed and stores it in a new Redis key (e.g., `ranked_feed:<user_id>`). This keeps read latency extremely low, at the cost of the feed not being perfectly real-time. A hybrid approach can inject very recent posts (from the last hour) on top of this pre-ranked feed.
*   **Online Ranking:** For every feed request, the `Feed Service` calls the `Ranking Service` in real-time. This provides the freshest ranking but adds latency and requires a highly optimized ML inference service.

---

## 8. Handling Hot Partitions

A hot partition (or hot spot) is a shard in your database or cache that receives a disproportionately high amount of traffic, creating a bottleneck.

### 8.1 Mitigation Strategies
1.  **Effective Caching:** This is the first line of defense. The data for a viral post or a celebrity's profile should be served from a cache like Redis or Memcached.
2.  **CDN for Content:** For the actual content of a viral post (e.g., a video), the CDN is the most effective solution. It moves the content to edge servers globally, meaning the traffic never even hits your data center.
3.  **Request Coalescing (or "Single Flight"):** If a popular item misses the cache, thousands of requests might try to fetch it from the database simultaneously. Request coalescing ensures that only the *first* request goes to the database, while the others wait for its result. This prevents a "thundering herd" from overwhelming the database.
4.  **Key Salting:** For extreme hot spots on a single key (e.g., a like counter for a viral post), we can split the key. Instead of one key `likes:post123`, we can write to 10 different keys: `likes:post123:1`, `likes:post123:2`, etc., chosen randomly. To get the total count, we read all 10 keys and sum the results. This distributes the load for a single logical item across multiple physical shards.

---

## 9. Core Trade-offs and Design Rationale

Every system design involves trade-offs. Here are the key decisions for the feed system.

*   **Trade-off: Fan-out on Write vs. Fan-out on Read**
    *   **Reasoning:** The system is extremely read-heavy (5M reads/sec vs 10K writes/sec). The primary goal must be to make reads as fast as possible.
    *   **Decision:** We choose **Fan-out on Write (Push Model)** as the default. This pre-computes the feed, making read requests a simple, fast O(1) cache lookup. We accept a higher cost on the write side to gain extremely low latency on the read side.

*   **Trade-off: Why a Hybrid Model is Necessary**
    *   **Reasoning:** A pure push model fails for celebrities, causing system-wide "write storms." A pure pull model fails for everyone at our scale, as it would require thousands of database queries per feed load.
    *   **Decision:** The **Hybrid Model** is a pragmatic compromise. It uses the most efficient strategy for each user type. It optimizes for the common case (normal users) with a fast push model, while handling the edge case (celebrities) with a manageable pull model.

*   **Trade-off: Synchronous vs. Asynchronous Processing**
    *   **Reasoning:** Performing the fan-out operation synchronously would mean the user has to wait for the entire process to finish before their post is confirmed. For a user with thousands of followers, this could take several seconds, leading to a poor user experience.
    *   **Decision:** We use an **asynchronous, event-driven architecture** with Kafka. This decouples the user's request from the heavy background processing. The API can respond instantly, providing low user-perceived latency. This also adds resilience, as a slowdown in the fan-out process won't impact the user's ability to create new posts.

---

## 10. Reliability and Failure Handling

*   **Graceful Degradation:** If the `Ranking Service` fails, the system must fall back to a simple reverse-chronological feed. This is a critical principle: non-essential features should not bring down core functionality.
*   **Fan-out Worker Failure:** Kafka's consumer group mechanism ensures that if a worker crashes, another worker will pick up the message. Kafka's persistence guarantees that events are not lost.
*   **Feed Store (Redis) Failure:** Redis clusters should be configured with replicas. If a primary node fails, a replica can be promoted. In a catastrophic failure, feeds can be regenerated from the `Post DB`, but this is a slow, expensive recovery process.

---

## 11. Final Production Architecture

### Summary
To achieve a scalable and low-latency feed, this design employs a hybrid fan-out strategy. For the majority of users, we use a **fan-out-on-write (push)** model where new posts trigger asynchronous workers via Kafka to update followers' feeds, which are stored in a Redis sorted set. This makes the read path a simple, fast cache lookup. For celebrity users with millions of followers, we switch to a **fan-out-on-read (pull)** model to prevent massive write amplification. During feed generation, the system merges the pre-computed feed with the latest posts from followed celebrities. The entire system is partitioned by `user_id` to scale horizontally and is designed for high availability with graceful degradation for non-critical components.

### Diagram

```text
                                       [CDN]
                                         ^
                                         | (Media)
[Client] <---> [DNS] <---> [Global Load Balancer]
                                         |
                                         v
                                [API Gateway]
                                /            \
                               /              \
               (Write Path)   /                \   (Read Path)
                             v                  v
                     [Post Service]       [Feed Service] <---------> [Ranking Service]
                           |                    |                         |
                           |                    | (1. Get Feed IDs)       | (ML Model)
                           v                    v                         v
                     [Post DB]           [Feed Store Cluster]      [Feature Store]
                    (Cassandra)          (Redis - Sharded)
                           |
                           | (Async)
                           v
                     [Kafka Topic]
                    (NewPostEvent)
                           |
                           v
                   [Fan-out Workers]
                  /                 \
                 / (Fetch Followers) \ (Push ID)
                v                     v
       [User Graph DB]          [Feed Store Cluster]
       (Neo4j / SQL)            (Redis - Sharded)
```
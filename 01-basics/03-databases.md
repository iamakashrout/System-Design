## 1.3 Databases & Storage (System Design View)

Database choice directly impacts latency, scalability, consistency, cost, and operational complexity.

There is no universally "best" database—only the one that best fits the system’s requirements and access patterns.

---

### 1.3.1 Why Database Choice Matters

The database is often the bottleneck of a system. Your choice has profound and practical effects:

-   **Latency:** How fast can you retrieve data? For a user session cache, a key-value store like Redis provides sub-millisecond latency. Trying to do the same with a complex SQL query could take hundreds of milliseconds, making the application feel slow.
-   **Scalability:** How does the system handle more load? A write-heavy logging system would quickly overwhelm a single SQL server. A wide-column store like Cassandra, however, is designed to scale horizontally by simply adding more machines to handle the write traffic.
-   **Consistency:** How up-to-date is the data you read? A banking application requires the strong consistency of a SQL database to ensure a money transfer is never partially complete (ACID guarantees). In contrast, a social media "like" counter can be eventually consistent, where it's acceptable if the exact count is delayed by a few seconds across different regions.
-   **Cost:** Storing petabytes of log data in a high-end commercial SQL database is financially prohibitive. A more cost-effective solution is using a database that runs on commodity hardware (like Cassandra) or archiving data to cheap object storage (like Amazon S3).
-   **Operational Complexity:** A managed cloud database like Amazon DynamoDB handles sharding, replication, and backups for you. Self-hosting a distributed database like a Cassandra cluster requires a dedicated operations team with specialized skills.

---

### 1.3.2 Core Questions Before Choosing a Database

Your choice should be driven by answering these fundamental questions about your system's needs.

-   **What data are we storing?**
    -   **Explanation:** Is your data highly structured with clear relationships, like user profiles and their orders? Or is it semi-structured or unstructured, like product catalog items with varying attributes or JSON blobs from an external API?
    -   **Impact:** Structured data with relationships fits perfectly into a **SQL** database. Semi-structured data is often easier to manage in a **NoSQL Document Store** like MongoDB, which doesn't require a predefined schema.

-   **Is the system read-heavy or write-heavy?**
    -   **Explanation:** A blog or news site is read-heavy (content is written once but read millions of times). A system collecting real-time metrics from IoT devices is write-heavy.
    -   **Impact:** Read-heavy systems are optimized with **caching and read replicas**. Write-heavy systems require a different architecture, often involving **sharding or specialized databases** like wide-column stores.

-   **What are the access patterns?**
    -   **Explanation:** How will your application query the data? Will it be simple lookups by a primary key (e.g., fetch user by `user_id`)? Or will you need to run complex analytical queries with joins, filtering, and aggregations?
    -   **Impact:** Simple key-based lookups are extremely fast in a **Key-Value store** like Redis. Complex queries are the primary strength of **SQL databases**.

-   **What are the consistency requirements?**
    -   **Explanation:** Does every read need to return the absolute most recent write (strong consistency)? Or is it acceptable for reads to be slightly out-of-date for a short period (eventual consistency)?
    -   **Impact:** Financial transactions demand the **strong consistency** of SQL. Social media feeds can tolerate the **eventual consistency** of NoSQL in exchange for higher availability and better performance.

-   **What is the required scale?**
    -   **Explanation:** Are you building a small internal application for 100 users or a web-scale service for 100 million?
    -   **Impact:** For a startup's MVP, a single powerful **PostgreSQL** instance is simple and effective (vertical scaling). For a system designed for massive scale from day one, a horizontally scalable database like **Cassandra or DynamoDB** is a more appropriate, albeit more complex, choice.

---

### 1.3.3 SQL (Relational Databases)

Relational databases, which use Structured Query Language (SQL), organize data into tables (relations). Each table consists of rows (records) and columns (attributes).

**Core Concepts:**
-   **Schema:** A predefined structure that dictates the data types and constraints for each column. For example, a `users` table might have an `id` (integer, primary key), `email` (varchar, unique), and `created_at` (timestamp).
-   **Relationships:** Foreign keys are used to link rows between tables. For example, an `orders` table would have a `user_id` column that references the `id` in the `users` table, enforcing that every order belongs to a valid user.
-   **ACID Guarantees:** These databases are known for providing `ACID` (Atomicity, Consistency, Isolation, Durability) properties for their transactions, which is crucial for reliability. A bank transfer, for instance, must be atomic—either both the debit and credit happen, or neither does.

**Examples:** PostgreSQL, MySQL, Microsoft SQL Server, Oracle Database.

**Best Suited For:**
-   Systems requiring strong data integrity and consistency (e.g., financial, e-commerce backends).
-   Applications with complex queries that require joining data from multiple tables.

---

### 1.3.4 NoSQL Databases (Big Picture)

NoSQL ("Not Only SQL") databases emerged to address the limitations of relational databases, particularly regarding scale and schema flexibility. They generally favor availability and speed over the strong consistency guarantees of SQL databases.

#### Key–Value Stores
-   **How it works:** The simplest NoSQL model. It's like a giant dictionary or hash map where you store a `value` (which can be anything from a string to a complex object) and retrieve it with a unique `key`.
-   **When to use:** Caching, user session storage, real-time leaderboards. Anything requiring extremely fast (sub-millisecond) reads and writes on a known key.
-   **Examples:** **Redis**, **Memcached**.

#### Document Stores
-   **How it works:** Stores data in flexible, self-describing documents, typically in a JSON-like format (e.g., BSON). Each document can have a different structure.
-   **When to use:** Content management, user profiles, product catalogs where attributes vary between items. It's great when your data structure is evolving.
-   **Examples:** **MongoDB**, **Couchbase**.

#### Wide-Column Stores
-   **How it works:** Think of a two-dimensional key-value store. You have a row key, but instead of a single value, it maps to a set of columns. These databases are optimized for high write throughput and queries over massive datasets.
-   **When to use:** Time-series data (metrics, logs), IoT sensor data, analytics. Systems that need to handle massive amounts of writes and scale horizontally.
-   **Examples:** **Apache Cassandra**, **Google Bigtable**.

#### Graph Databases
-   **How it works:** Designed specifically to store entities (nodes) and the relationships (edges) between them. They excel at traversing these relationships.
-   **When to use:** Social networks ("friends of my friends"), fraud detection (finding hidden connections between fraudulent accounts), and recommendation engines ("users who bought X also bought Y").
-   **Examples:** **Neo4j**, **Amazon Neptune**.

---

### 1.3.5 SQL vs NoSQL

The choice is a fundamental trade-off:

| Aspect | SQL (e.g., PostgreSQL) | NoSQL (e.g., MongoDB, Cassandra) |
| :--- | :--- | :--- |
| **Schema** | Rigid, predefined schema. Enforces structure. | Dynamic/flexible schema. Data can have varied structures. |
| **Consistency** | Prioritizes strong consistency (ACID). | Often prioritizes availability, offering eventual consistency. |
| **Scaling** | Traditionally scales **vertically** (buy a bigger server). | Designed to scale **horizontally** (add more cheap servers). |
| **Queries** | Powerful, standardized query language (SQL) for complex joins. | Queries are often simpler, typically limited to a single collection/table. |
| **Data Model** | Relational (tables with rows and columns). | Non-relational (documents, key-value pairs, graphs, etc.). |

---

### 1.3.6 Read-Heavy vs Write-Heavy Systems

#### Read-Heavy
-   **Practical Application:** A news website, a blog, or an e-commerce product catalog. The content is written once but read millions of times.
-   **Architecture & Optimization:** The goal is low read latency and high availability for consumers.
    -   **Read Replicas:** A primary database handles all writes. The data is then replicated to one or more secondary, read-only databases (replicas). All application read traffic is directed to these replicas, spreading the load and preventing the primary write database from becoming a bottleneck.
    -   **Caching:** An in-memory cache like Redis is placed in front of the database. When data is requested, the application checks the cache first. If it's there (a *cache hit*), it's returned instantly. If not (a *cache miss*), the app queries the database, returns the data to the client, and saves a copy in the cache for subsequent requests. This is the single most effective way to reduce database load.

#### Write-Heavy
-   **Practical Application:** A system collecting logs from thousands of servers, an analytics platform tracking user clicks in real-time, or an IoT application receiving constant sensor data.
-   **Architecture & Optimization:** The goal is to handle a high volume of incoming writes without losing data or slowing down.
    -   **Sharding (Partitioning):** The database is horizontally split across multiple servers (shards). For example, data for users with names A-M might be written to Server 1, and N-Z to Server 2. This distributes the write load across multiple machines.
    -   **Asynchronous Processing:** Instead of writing to the database while the client waits, the write operation is published as a message to a queue (like RabbitMQ or Kafka). The client receives an immediate acknowledgment. Separate background workers then consume messages from the queue and write them to the database, often in efficient batches. This decouples the system and smooths out write spikes.

---

### 1.3.7 Indexes

An index is a data structure that improves the speed of data retrieval operations on a database table at the cost of additional writes and storage space.

**How it works:** Think of the index at the back of a book. Instead of scanning every page to find a topic (a "full table scan"), you look up the topic in the index and go directly to the correct page. Databases typically use a B-Tree data structure for indexes, which allows for fast searching, sorting, and retrieval.

**Example:**
Imagine a `Users` table with millions of rows: `(user_id, name, email, signup_date)`.
A query like `SELECT * FROM Users WHERE email = 'test@example.com';` would be very slow without an index, as the database would have to check the `email` of every single row.

**Implementation:** You create an index on the `email` column:
`CREATE INDEX idx_users_email ON Users(email);`

Now, the database maintains a separate, sorted B-Tree of emails. When the query runs, the database can efficiently search this tree to find the exact location of the matching row on disk, avoiding a full table scan.

**Advantages:**
-   Dramatically speeds up `SELECT` queries with `WHERE` clauses on indexed columns.
-   Improves the performance of `JOIN` operations and `ORDER BY` clauses.

**Disadvantages:**
-   **Slower Writes:** Every `INSERT`, `UPDATE`, or `DELETE` on the table requires an additional write operation to update the index. A table with many indexes will have slower write performance.
-   **Storage Overhead:** Indexes take up disk space, sometimes significantly.

---

### 1.3.8 Data Growth & Retention

This is about planning for data volume over time. It's not just about total size, but also about data "temperature" (how frequently it's accessed).

**Example:** Consider a system that logs every user action for analytics.
-   **Data Growth:** The system generates 1 TB of log data per day. In a year, this is over 365 TB.
-   **Retention Policy:** A tiered policy is essential.
    -   **Hot Data (0-7 days):** Logs are needed for real-time dashboards. This data must be in a fast, indexed database for low-latency queries.
    -   **Warm Data (8-90 days):** Logs are needed for monthly reports but don't require instant access. This data can be moved to cheaper storage or aggregated.
    -   **Cold Data (>90 days):** Logs must be kept for compliance but are rarely accessed. This data should be archived to very cheap object storage like Amazon S3 Glacier.

**Impact:** A clear retention policy prevents tables from growing infinitely, keeps the primary "hot" database fast and manageable, and dramatically reduces storage costs.

---

### 1.3.9 Database Anti-Patterns

Avoid these common mistakes that lead to unscalable and fragile systems.

-   **Using the Database as a Message Queue**
    -   **What it is:** Creating a `jobs` table with a `status` column. Workers poll this table with `SELECT ... FOR UPDATE` to grab a job.
    -   **Why it's bad:** This is highly inefficient. It puts constant polling load on the database, and row-level locking causes contention, killing performance.
    -   **What to do instead:** Use a dedicated message queue like **RabbitMQ**, **Apache Kafka**, or **AWS SQS**. They are designed for this exact purpose.

-   **Over-Indexing**
    -   **What it is:** Adding an index on every column "just in case" it's needed.
    -   **Why it's bad:** Every index slows down all write operations (`INSERT`, `UPDATE`, `DELETE`) and consumes disk space. An unused index is pure overhead.
    -   **What to do instead:** Be deliberate. Analyze your query patterns. Add indexes only to columns that are frequently used in `WHERE` clauses to fix slow queries.

-   **Large, Unbounded Tables**
    -   **What it is:** Having a single, ever-growing table for events or logs that accumulates millions of rows per day with no cleanup plan.
    -   **Why it's bad:** Query performance degrades, index sizes balloon, and database maintenance (backups, schema changes) becomes a nightmare.
    -   **What to do instead:** Use **partitioning** to split the table into smaller chunks (e.g., by month). Implement a **data retention and archiving policy** to move old data out of the primary database.

-   **Tight Schema Coupling in Microservices**
    -   **What it is:** Service A directly queries the database of Service B.
    -   **Why it's bad:** This breaks the core principle of microservices: encapsulation. If the team for Service B changes their database schema, it will instantly break Service A, creating a fragile "distributed monolith."
    -   **What to do instead:** Services must **only** communicate through well-defined **APIs**. Service B should expose an API that Service A calls. This API acts as a stable contract, allowing Service B's internal database to evolve independently.

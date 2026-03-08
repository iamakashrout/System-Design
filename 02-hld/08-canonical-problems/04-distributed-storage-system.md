# Canonical Problem 4: Distributed Storage System (e.g., Dropbox, Google Drive, S3)

This document outlines the High-Level Design (HLD) for a scalable file storage and synchronization service.

---

## 1. Step 1: Clarify Requirements

Designing a storage system is different from a social network. We deal with large binary data, not just text, and the requirements for durability and availability are paramount.

### 1.1 Functional Requirements

**Core Features (MVP):**
1.  **Upload and Download:** Users must be able to upload files (documents, photos, videos) from their device and download them to any other device.
2.  **File Versioning:** If a user modifies a file and re-uploads it, the system must retain previous versions. This allows users to recover from accidental changes by rolling back to an older version.
3.  **Folder Hierarchy:** Users need to organize their files in a nested directory structure, just like on a local computer.
4.  **Cross-Device Sync:** Changes made on one device (e.g., adding a file on a laptop) must be automatically reflected on the user's other linked devices (e.g., their phone).
5.  **Sharing:** Users should be able to share files or entire folders with other users, controlling whether the recipient has read-only or read-write permissions.

**Extended Features (In-Scope):**
*   **Large File Support:** The system must efficiently handle files ranging from a few kilobytes to many gigabytes.
*   **Offline Editing:** A user can edit files on their device while offline. When they reconnect to the internet, the client application must automatically sync the changes.

**Out of Scope:**
*   **Real-time Collaborative Editing:** Features like Google Docs, where multiple users edit the same file simultaneously, are out of scope. This requires complex Operational Transformation (OT) or Conflict-free Replicated Data Types (CRDTs) and is a separate system design problem.
*   **Web Hosting Features:** The system will not act as a web server to host static websites.

### 1.2 Non-Functional Requirements

*   **Scale:** The system must be designed for a massive, global user base.
    *   **Users:** 100 Million total users, with 10 Million Daily Active Users (DAU).
    *   **Storage Volume:** This is the most significant constraint. If 10M users upload an average of 10MB of new data per day, that is 100 Terabytes of new data *every day*. The system must be designed to store **Petabytes** of data.
*   **Durability:** This is the most critical requirement. We must never lose user data. Durability needs to be extremely high, such as **99.999999999% (11 nines)**. This means that if you store 10 million files, you can expect to lose only one file every 10,000 years. This is achieved through massive redundancy.
*   **Availability:** Users expect their files to be accessible whenever they need them. The system should have high availability (e.g., 99.99%), meaning it can be down for no more than about 52 minutes per year.
*   **Consistency:**
    *   **Metadata:** Strong consistency is required for file system operations. If a user renames a file, they should see the new name immediately on all devices.
    *   **File Sync:** Eventual consistency is acceptable. If a file is added on one device, it's okay if it takes a few seconds to appear on another.

---

## 2. Core Design Principle: Separation of Concerns

The most important architectural decision in a storage system is to separate **Metadata** from **File Content (Blobs)**. These two types of data have fundamentally different characteristics and scaling requirements.

*   **Metadata:** This is the information *about* the file. It includes the file's name, size, owner, folder location, permissions, and creation date. This data is small, highly structured, and requires transactional support (e.g., moving a file is an atomic operation).
*   **File Content (Blob):** This is the actual binary data of the file itself—the bytes that make up the image, video, or document. This data is large, unstructured, and immutable (a new version of a file is a new blob, the old one isn't changed).

**Analogy:** Think of a massive public library.
*   The **Card Catalog** is the **Metadata**. It's a highly organized, indexed system that tells you the title, author, and shelf location of every book. It's small and easy to search.
*   The **Books** themselves are the **Blobs**. They are the large, heavy, physical objects stored on shelves.

You would never store the books inside the card catalog drawers; you use two different, specialized systems for each.

**Why separate them?**
*   **Different Scaling Needs:** The metadata database needs to handle millions of small, fast queries and transactions. The blob storage needs to handle petabytes of data and high-bandwidth streaming. Mixing them would mean neither system could be optimized effectively.
*   **Performance:** You don't want to load a 1GB video file into your database just to check its file name or creation date. Separating them allows for lightning-fast metadata operations.

---

## 3. High-Level Architecture

```text
Client (Mobile/Desktop App)
        |
        v
   [Load Balancer]
        |
        v
   [API Gateway / Web Servers (Stateless)]
        |
   +----+-------------------------+
   |                              |
   v                              v
[Metadata Service]       [Block Service]
   |                              |
   v                              v
[Metadata DB]            [Cloud Object Storage]
(Sharded SQL DB)         (e.g., S3, GCS)
   |
   v
[Notification Service] -> (Pushes sync changes to other clients)
```

### Component Breakdown

1.  **Client:** The application running on the user's device. It runs a background "watcher" process to detect file changes on the local disk.
2.  **Metadata Service:** Manages file attributes, folder structure, and versions. It talks to a relational database.
3.  **Block Service:** Manages the upload/download of actual file chunks. It talks to the Object Storage.
4.  **Object Storage (S3):** A specialized storage system for unstructured data. It handles the hard work of replication and durability for the massive binary files.
5.  **Notification Service:** Uses Long-Polling or WebSockets to tell connected clients, "Hey, a file changed, please sync."

---

## 4. Data Model

### 4.1 Metadata Database (Relational)

We use a relational database because file systems are hierarchical, and we need ACID guarantees (moving a folder should be atomic).

**Table: Files**
*   `file_id` (Primary Key)
*   `owner_id` (Indexed)
*   `parent_folder_id` (For hierarchy)
*   `name`
*   `is_folder` (Boolean)
*   `latest_version`

**Table: Versions**
*   `version_id` (PK)
*   `file_id` (FK)
*   `device_id` (Who uploaded it)
*   `created_at`

**Table: Block_Map**
*   `version_id`
*   `block_index` (Order of the block: 1, 2, 3...)
*   `block_hash` (The unique ID of the chunk)

### 4.2 Blob Storage (Object Store)

We do not store the whole file as one object. We split files into **Chunks** (or Blocks).
*   **Storage Key:** `block_hash` (SHA-256 of the chunk content).
*   **Value:** The 4MB binary chunk.

---

## 5. The Magic of Chunking & Deduplication

Instead of uploading a 1GB file as a single unit, the client splits it into smaller blocks (e.g., 4MB each).

### Why Chunking?
1.  **Resumability:** If the network fails after 900MB, you don't restart from zero. You just retry the failed 4MB chunk.
2.  **Speed:** You can upload 4 chunks in parallel.
3.  **Delta Sync:** If you change one sentence in a large document, only the chunk containing that sentence changes. The client only uploads that one new chunk, not the whole file.

### Why Deduplication?
We use **Content-Addressable Storage**. The name of the blob in S3 is the **hash** of its content.

**Scenario:**
*   User A uploads `Batman.mkv` (2GB). The client splits it into 500 chunks and uploads them.
*   User B uploads the exact same `Batman.mkv`.
*   The client calculates the hash of the chunks.
*   The server checks the database: "Do I already have a block with hash `abc123`?"
*   **Yes:** The server tells User B, "I already have this data. No need to upload."
*   **Result:** Instant upload for User B, and zero extra storage cost for the company.

---

## 6. Detailed Workflows

### 6.1 Upload Flow
1.  **Chunking:** Client splits file into 4MB chunks.
2.  **Hashing:** Client calculates SHA-256 hash for each chunk.
3.  **Check:** Client sends the list of hashes to the **Metadata Service**.
4.  **Filter:** Metadata Service checks which chunks already exist in the Object Store (Deduplication). It returns a list of *only* the missing chunks.
5.  **Upload:** Client uploads the missing chunks to the **Block Service** (or directly to S3 via Pre-signed URLs).
6.  **Commit:** Once all chunks are uploaded, the Client calls `commitFile()` on the Metadata Service. The service creates a new `Version` entry linking the file to these specific block hashes.

### 6.2 Download Flow
1.  **Get Metadata:** Client requests the file info.
2.  **Get Block List:** Metadata Service returns the list of `block_hashes` that make up the file.
3.  **Fetch Blocks:** Client downloads the blocks in parallel from the Object Store.
4.  **Reassemble:** Client joins the blocks to recreate the file on the local disk.

### 6.3 Sync Flow (The "Watcher")
1.  **Detect:** The client's background process detects a local file change.
2.  **Upload:** It executes the Upload Flow.
3.  **Notify:** The Metadata Service updates the DB and sends a message to the **Notification Service**.
4.  **Push:** The Notification Service sends a message to all other active devices belonging to that user: "File X has changed."
5.  **Pull:** The other devices receive the message and execute the Download Flow.

---

## 7. Conflict Resolution

What if two users edit the same file offline and then both come online?

**Strategy: Conflict Copy**
We do not try to merge binary files automatically (it's impossible for images/PDFs).
1.  User A syncs first. Their version becomes `v2`.
2.  User B tries to sync. The server sees User B is trying to update `v1`, but the current version is `v2`.
3.  The server rejects the update or accepts it as a **new file**.
4.  The system renames User B's file to `File_Name (Conflicted Copy).txt`.
5.  Both files appear in the folder. The user must manually decide which one to keep.

**Rule:** Never silently overwrite data.

---

## 8. Partitioning & Scaling

### Metadata Database
We have billions of rows. We must shard.
*   **Shard Key:** `user_id` (or `owner_id`).
*   **Reasoning:** All file operations (list folder, upload, search) are usually scoped to a single user. Storing all of Alice's metadata on Shard A makes queries very fast.

### Object Storage
We rely on the cloud provider (AWS S3, Google Cloud Storage) to handle the scaling of the petabytes of blobs. They partition internally based on the hash key.

---

## 9. Security

### Encryption at Rest
*   **Metadata:** Encrypted columns in the database.
*   **Blobs:** The Object Store encrypts data on disk (Server-Side Encryption). For higher security, the client can encrypt chunks *before* uploading (Client-Side Encryption), so the server never sees the raw data (Privacy).

### Encryption in Transit
*   All transfers occur over **HTTPS (TLS)**.

---

## 10. Summary

A distributed storage system is a masterclass in **separation of concerns**.

1.  **Split Metadata and Data:** Metadata goes to a sharded SQL DB; Data goes to an Object Store.
2.  **Chunking is Key:** It enables resumable uploads, parallel transfers, delta sync, and deduplication.
3.  **Deduplication saves money:** Hashing chunks allows us to store common data only once.
4.  **Reliability over Speed:** We prioritize data durability (not losing files) over instant consistency.

**Key Interview Sentence:**
"I will design a system that separates metadata from blob storage. I'll use client-side chunking and hashing to enable deduplication and delta sync, ensuring efficient bandwidth usage and storage costs."
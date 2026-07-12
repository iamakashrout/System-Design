# Topic 5: Logging and Observability

## 1. Intuition

A system that only works when you're watching it in a debugger isn't production-ready. The moment code runs on a server you can't attach a debugger to, at 3 AM, serving real traffic, your only window into what actually happened is what you chose to record *in advance*. Observability is the discipline of designing that window deliberately, before you need it — not scrambling to add print statements after an incident is already underway.

This connects directly to distributed systems intuition you already have: in a single-service, single-machine world, "what went wrong" is answerable by reading code and reproducing locally. Once a request crosses multiple services (exactly the world HLD lives in), no single machine has the whole picture — you *need* a deliberate, structured record that can be stitched back together across process boundaries. Logging, metrics, and tracing are the three tools for that, and they answer three genuinely different questions, not one question three ways.

## 2. Core Concepts

### 2.1 What to log, and what not to log

**Log:**
- Business-significant events: a booking was created, a payment was authorized, an order shipped.
- Decision points and *why* a decision was made: "rejected — room unavailable for requested dates," not just "rejected."
- Enough context to reconstruct the situation: entity IDs, the operation attempted, the outcome.
- Errors and their full context (including the exception chain from Topic 3 — always log `cause`, never just the top-level message).

**Never log:**
- Passwords, API keys, tokens, session secrets — under any circumstance, even at DEBUG level, even in a "trusted" internal environment.
- Full PII where a masked/partial form would do (a card number should be logged as `**** **** **** 1234`, not in full — mirroring the ATM exception messages from Topic 3).
- Anything a compliance or data-protection policy classifies as sensitive, in plaintext, ever.

The test worth applying: **if this log line leaked in a breach, would it hand an attacker something useful, or embarrass someone whose data it is?** If yes, it doesn't belong in a log at that level of detail, full stop.

### 2.2 Log levels — what each one actually means

| Level | Meaning | Example |
|---|---|---|
| **TRACE** | Extremely fine-grained, step-by-step flow — normally off even in dev | "Entering `validateDates()` with checkIn=2026-08-10" |
| **DEBUG** | Detail useful while actively developing/debugging, too noisy for normal production volume | "Computed nightly rate: 150.0 × 2 nights = 300.0" |
| **INFO** | Notable business events — the "what happened" narrative of the system, always on in production | "Booking BK-42 created for room R101" |
| **WARN** | Something unexpected but handled — the system recovered or degraded gracefully, but a human should eventually notice the pattern | "Room R101 unavailable for requested dates — booking rejected" |
| **ERROR** | An operation failed and could not be completed as requested — usually warrants investigation, sometimes paging someone | "Failed to persist booking BK-42 — database connection refused" |

A very common real-world mistake: using **ERROR** for expected business outcomes (a customer's card was declined — that's normal business operation, log it as INFO or WARN, not ERROR), which trains on-call engineers to ignore ERROR-level alerts because most of them are noise. Reserve ERROR for things that genuinely need a human's attention.

### 2.3 Structured logging

Free-text logs (`"Booking failed for room 101 because dates overlap"`) are fine for a human reading a terminal in the moment, but they're nearly useless at scale — you cannot query "show me every rejected booking for room R101 in the last hour" against a string. **Structured logging** emits logs as key-value pairs (often serialized as JSON), so every log line is a queryable, filterable record:

```
{"level":"WARN","event":"booking_rejected","roomId":"R101","checkIn":"2026-08-11","reason":"room_unavailable","correlationId":"a1b2c3"}
```

The message is still human-readable, but the *fields* are what make the log searchable and aggregable across millions of lines — you can now ask "how often does `booking_rejected` happen, broken down by `reason`" as a query, not a manual grep.

### 2.4 Correlation IDs

In a system where one incoming request triggers calls across multiple internal components (or, in a distributed system, multiple services), a single **correlation ID** generated at the entry point and threaded through every subsequent log line lets you reconstruct the *entire* story of one request by filtering on that one ID — even if the components involved never call each other directly and only communicate through shared logs. Without it, you have a pile of unrelated-looking log lines and no way to prove which ones belong to the same request, especially under concurrent load where interleaved log lines from different requests are indistinguishable otherwise.

### 2.5 Logging vs. metrics vs. tracing

These are commonly conflated but answer different questions:

- **Logging** — *what happened*, as a discrete, timestamped event with context. "Booking BK-42 was created."
- **Metrics** — *how much / how often*, as aggregated numeric time series. "Bookings created per minute," "p99 booking latency." Metrics are cheap to store at high volume precisely because they're pre-aggregated numbers, not full event records — you lose the individual story but gain the ability to alert on trends ("error rate crossed 5%") cheaply.
- **Tracing** — *where time was spent*, as a tree of timed spans across the steps (and, in a distributed system, services) a single request passed through. A trace tells you "this request spent 4ms in validation, 180ms waiting on the database, 2ms serializing the response" — the thing a log line alone can't easily show you: the *shape* of where time went.

In distributed systems terms: logging is the diary, metrics are the dashboard, tracing is the flight recorder. All three, correlated by the same request/correlation ID, are what "observability" means as a whole — not any single one of them alone.

## 3. Real-World Analogy

Think of an aircraft's black box versus its cockpit instrument panel versus the airline's fleet-wide on-time-performance dashboard. The cockpit instruments (metrics) tell the pilot the current altitude and speed in aggregate, right now. The black box (tracing/detailed logs) records exactly what happened, moment to moment, so investigators can reconstruct one specific flight's full timeline after the fact. The fleet dashboard (metrics aggregated further) tells the airline whether flights are, in general, running late this month. None of these three replaces the others — an airline that only had the fleet dashboard could never investigate a single incident; one that only had cockpit instruments could never spot a fleet-wide trend.

## 4. Implementation

See `LoggingObservability.java`. It instruments the Hotel Booking domain from Topic 4 with production-quality observability:

1. A **structured `Logger`** abstraction (`Logger` interface + `ConsoleLogger` implementation) emitting key-value structured log lines rather than free text, at the appropriate level (DEBUG/INFO/WARN/ERROR) for each event.
2. A **`CorrelationContext`** (`ThreadLocal`-backed) that generates a correlation ID once per "request" and threads it automatically into every subsequent log line for that request — without every method having to pass the ID around explicitly as a parameter.
3. An **instrumented `BookingService`**, logging business events at INFO (booking created), expected-but-notable outcomes at WARN (room unavailable), and simulated failures at ERROR (with the full exception cause chain, per Topic 3) — alongside a masked example showing what *not* to log in full.
4. A minimal **`Metrics`** counter and a **`Span`** (timing) class, run side-by-side with the logs for the same request, to make the logging-vs-metrics-vs-tracing distinction concrete rather than abstract.
5. Clearly labeled **anti-pattern demonstrations**: unstructured free-text logging, logging sensitive data in full, and misusing ERROR for an expected business outcome.

## 5. Anti-Patterns

- **Unstructured, free-text-only logs**: `log.info("Booking failed room 101")` cannot be queried or aggregated at scale — at minimum, key fields (roomId, reason, correlationId) should be structured, even alongside a human-readable message.
- **Logging secrets or full PII**: passwords, tokens, and unmasked sensitive data must never appear in logs — logs are frequently retained for months, shipped to third-party aggregators, and read by more people than the original code's author ever anticipated.
- **Using ERROR for expected business outcomes**: a declined card or an unavailable room is a normal, anticipated business result — logging it at ERROR trains engineers to tune out ERROR-level alerts, which is how a real ERROR gets missed during an actual incident.
- **No correlation ID at all**: under concurrent load, log lines from different requests interleave in the output. Without a shared ID threading them together, reconstructing "what happened for this one request" becomes forensic guesswork.
- **Logging inside hot loops at INFO/WARN**: emitting a log line per iteration of a tight loop that runs thousands of times per request floods the log stream, drowns out signal, and can itself become a performance problem — this belongs at TRACE (usually disabled) or should be aggregated into a single summary log line after the loop.
- **Confusing metrics with logs**: trying to answer "what's our error rate over the last hour" by grepping and counting log lines works at small scale and falls over completely at production volume — that's what metrics exist for.

## 6. Trade-offs

- **Structured logging has a small overhead**: constructing key-value pairs (or JSON) per log line costs more than a raw string concatenation. This is negligible compared to the query/debugging time it saves, except in the very hottest of hot paths — a case for TRACE-level suppression rather than avoiding structure altogether.
- **Too much logging has real costs**: storage, ingestion cost in a log aggregation platform, and signal-to-noise ratio for a human during an incident all degrade if every level is logged indiscriminately at INFO in production. Level discipline (Section 2.2) exists specifically to manage this trade-off.
- **Correlation IDs require discipline to propagate correctly**: in a truly distributed system, the ID must be passed across process/service boundaries (e.g., in an HTTP header) — forgetting this at just one hop breaks the entire chain for that request. `ThreadLocal`-based propagation (as used in the implementation here) only works within a single thread/process; distributed tracing systems solve the cross-process version of this same problem.
- **Tracing has higher instrumentation cost than logging**: adding spans throughout a codebase is more invasive than adding log lines, and sampling (only tracing some percentage of requests) is often necessary at high volume to keep the overhead manageable — a trade-off logging and metrics don't usually need to make.

## 7. Key Takeaways

1. Logging, metrics, and tracing answer three different questions — *what happened*, *how much/how often*, and *where did time go* — and real observability requires all three, correlated together, not just one.
2. Use log levels with discipline: reserve ERROR for things that genuinely need human attention; expected business outcomes belong at INFO or WARN.
3. Structured (key-value) logging turns log lines into queryable data — essential once log volume exceeds what a human can manually grep.
4. Never log secrets or unmasked sensitive data, regardless of log level or environment.
5. A correlation ID, threaded through every log line for a single request, is what makes it possible to reconstruct one request's full story out of an interleaved, concurrent stream of log output.

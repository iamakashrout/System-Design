# Notification System — LLD Design Notes

## Problem Summary

Design a multi-channel notification system supporting Email, SMS, Push, and
WhatsApp. Users opt into channels. Messages are template-based. Failed
deliveries retry with backoff, fall back to alternate channels, and ultimately
land in a dead-letter queue.

---

## Five Dimensions of Complexity

| Dimension | Problem | Solution |
|-----------|---------|----------|
| Multiple channels | Different API, payload, failure mode per channel | Strategy — NotificationChannel |
| User preferences | Fan out only to opted-in channels | Observer — UserPreferences as subscriber set |
| Message structure | Subject/body vary per event type | Template — NotificationTemplate |
| Retry + fallback | Gateway timeouts, expired tokens | Chain of Responsibility — DeliveryHandler chain |
| Extensibility | New channels without modifying existing code | Factory + OCP |

---

## Patterns Used

### 1. Strategy — `NotificationChannel`

```
NotificationChannel (interface)
├── EmailChannel     — SMTP / SendGrid
├── SmsChannel       — Twilio / gateway
├── PushChannel      — FCM / APNs
└── WhatsAppChannel  — WhatsApp Business API
```

`NotificationService` calls `channel.send(notification)` — it never knows
which channel type it's dealing with. Adding a `SlackChannel` is one new
class and one `ChannelFactory.register()` call. Zero changes elsewhere.

### 2. Observer — `UserPreferences` as subscriber registry

```java
for (ChannelType channelType : prefs.getChannels()) {
    Notification n = template.render(userId, data, channelType, Priority.NORMAL);
    deliver(n, channelType);
}
```

The service doesn't enumerate channels — it iterates what the user is
subscribed to. This is Observer at the application level: the "event" is
a notification trigger; the "subscribers" are the user's opted-in channels.

### 3. Factory — `ChannelFactory`

```java
factory.register(ChannelType.SMS, new SmsChannel(failFirstN));
NotificationChannel channel = factory.get(channelType);
```

`EnumMap<ChannelType, NotificationChannel>` is the registry — type-safe,
no string keys, O(1) lookup. `register()` allows injection of test doubles
without framework-level mocking (demonstrated in the demo's fault scenarios).

### 4. Chain of Responsibility — `DeliveryHandler`

```
RetryHandler(3 attempts, 100ms backoff)
    → FallbackHandler(channel-type → fallback channel map)
        → DeadLetterHandler
```

The chain is assembled externally in `main()` — handlers are unaware of
each other. This is the key OCP application: inserting a `ThrottleHandler`
or `AuditHandler` requires zero changes to `RetryHandler` or `FallbackHandler`.

```java
retryHandler.then(fallbackHandler).then(dlq);
```

The fluent `then()` builder makes chain composition readable.

### 5. Template — `NotificationTemplate`

```java
new NotificationTemplate(
    "ORDER_SHIPPED",
    "Your order #{orderId} has shipped!",    // subjectPattern
    "Hi {name}, your order #{orderId}...",   // bodyPattern
    ChannelType.EMAIL, Priority.NORMAL
)
```

`render(userId, data)` substitutes `{key}` placeholders. Rendering is
per-channel — the same template can render to SMS (short body) or Email
(full body) with different channel targets.

---

## Key Design Decisions

### `DeliveryHandler.then()` — fluent chain assembly

Rather than a constructor chain or a `List<DeliveryHandler>`, the fluent
`then()` builder matches how you'd describe the chain verbally:
"retry, then fall back, then dead-letter." This also means chain topology
is visible at the call site, not buried in handler constructors.

### Exponential backoff in `RetryHandler`

```java
long backoff = initialBackoffMs;
for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    if (channel.send(notification)) return success;
    backoff *= 2;  // 100ms → 200ms → 400ms
}
```

Exponential backoff prevents thundering-herd when a downstream service
(SMS gateway) recovers — clients don't all retry simultaneously.
Production implementation uses `Thread.sleep(backoff)` or a scheduled
`ExecutorService`; here the delay is commented to keep the demo fast.

### `FallbackHandler` uses a channel-type → channel map

```java
fallbacks.put(ChannelType.SMS, new PushChannel());   // SMS down → Push
fallbacks.put(ChannelType.EMAIL, new SmsChannel(0)); // Email down → SMS
```

Fallback relationships are domain-specific (configured per deployment),
not hardcoded in the handler. The handler is a pure routing mechanism.

### `EnumMap` for channel registry

`EnumMap` over `HashMap<String, ...>` because:
- Type-safe — `ChannelType` enum keys, no string typos
- O(1) with smaller constant than `HashMap` (array-backed)
- Iteration in enum declaration order (predictable)

### `Notification` as an immutable value object

All fields are `final`. The object is safe to pass through the entire
handler chain, potentially across threads (if the service were async),
without defensive copies. Each `render()` call produces a fresh instance.

### `UserPreferences` using `EnumSet`

`EnumSet.copyOf(...)` for the channel set — compact, fast, type-safe.
Significantly cheaper than `HashSet<ChannelType>` for a small enum.

---

## Delivery Flow

```
notify(userId, templateId, data)
    │
    ├── render template per opted-in channel
    │
    └── for each channel:
            deliver(notification, channel)
                │
                RetryHandler.handle()
                    ├── attempt 1 → success → SENT
                    ├── attempt 2 → failure → backoff
                    ├── attempt 3 → failure → passToNext()
                    │
                    FallbackHandler.handle()
                        ├── fallback channel found → send → SENT
                        └── no fallback → passToNext()
                                │
                                DeadLetterHandler.handle()
                                    └── add to DLQ → DEAD_LETTERED
```

---

## Class Summary

```
ChannelType, Priority, DeliveryStatus (enums)

Notification       — immutable: userId, subject, body, channelType, priority
DeliveryResult     — immutable: notification, status, message, attemptsMade

NotificationTemplate — subjectPattern + bodyPattern → render(userId, data)

NotificationChannel (interface)
├── EmailChannel, SmsChannel, PushChannel, WhatsAppChannel

ChannelFactory     — EnumMap<ChannelType, NotificationChannel>; register() + get()
UserPreferences    — userId + EnumSet<ChannelType>; optIn/optOut/isOptedIn

DeliveryHandler (abstract)
├── RetryHandler       — maxAttempts + exponential backoff
├── FallbackHandler    — ChannelType → fallback NotificationChannel
└── DeadLetterHandler  — List<Notification> dead letter queue

NotificationService — user prefs registry, template registry,
                      fan-out loop, audit log
```

---

## Complexity

| Operation | Time | Notes |
|-----------|------|-------|
| `notifyViaTemplate` | O(C × H) | C = opted-in channels; H = handler chain length |
| `channel.send()` per attempt | O(1) | channel-specific I/O |
| `ChannelFactory.get()` | O(1) | EnumMap array lookup |
| `UserPreferences.isOptedIn()` | O(1) | EnumSet contains |
| Template `render()` | O(P) | P = number of placeholders |

---

## Concurrency Design

Not applied here — `NotificationService` is single-threaded in this model.

In a production system:
- `notifyViaTemplate` would submit each channel delivery to a thread pool
  (`ExecutorService.submit()`) for parallel fan-out
- `RetryHandler` would use a `ScheduledExecutorService` for real backoff
  (`schedule(task, delay, TimeUnit.MILLISECONDS)`)
- `DeadLetterHandler` would publish to a persistent queue (Kafka, SQS)
- `auditLog` would need a `ConcurrentLinkedQueue` or database persistence

---

## Extension Possibilities

- **New channel (Slack)**: implement `NotificationChannel`, register in
  `ChannelFactory`. No existing class changes.
- **Rate limiting**: insert a `ThrottleHandler` before `RetryHandler` in
  the chain. No changes to retry or fallback logic.
- **Priority routing**: high-priority notifications bypass `RetryHandler`
  and go directly to `FallbackHandler` (or a premium channel). Configurable
  in `NotificationService.deliver()`.
- **Async delivery**: wrap `deliver()` in `CompletableFuture.supplyAsync()`.
  `Notification` is already immutable — safe for cross-thread sharing.
- **Per-user template overrides**: extend `NotificationTemplate.render()`
  to accept a `UserPreferences` and select short/long body based on channel.

---

## Phase Connections

| Concept | Where learned | Where applied |
|---------|--------------|---------------|
| Strategy pattern | Phase 3 (GoF Behavioral) | NotificationChannel per channel type |
| Observer pattern | Phase 3 (GoF Behavioral) | Fan-out via UserPreferences |
| Factory pattern | Phase 3 (GoF Creational) | ChannelFactory |
| Chain of Responsibility | Phase 3 (GoF Behavioral) | DeliveryHandler chain |
| Value objects (immutable) | Phase 2 (Object Modeling) | Notification, DeliveryResult |
| SRP / OCP | Phase 1 (SOLID) | Each handler one responsibility; new handlers don't modify existing |
| `EnumMap` / `EnumSet` | Phase 3.5 (Enums) | Type-safe, compact channel registries |
| Fluent builder | Phase 3 (Builder pattern) | `handler.then(next)` chain assembly |

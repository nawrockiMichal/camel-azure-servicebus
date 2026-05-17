# Queue Configuration

This lesson covers how an Azure Service Bus queue is configured — what properties you
set when creating one, what they control at runtime, and how the emulator config file
reflects those choices.

---

## The Namespace

A **namespace** is the top-level Azure resource that acts as a container for all your
queues, topics, and subscriptions. Think of it as the "server". You connect to it via:

```
Endpoint=sb://<namespace-name>.servicebus.windows.net/...
```

The emulator creates a local namespace called `sbemulatorns`. That name lives only
inside Docker — it does not correspond to any Azure resource.

### Standard vs Premium tier

The tier you pick when creating the namespace controls which features are available:

| | Standard | Premium |
|-|----------|---------|
| Pricing | Pay per million operations | Fixed hourly per messaging unit |
| Max message size | 256 KB | 100 MB |
| Partitioning | Up to 16 partitions per entity | Not available |
| Geo-Disaster Recovery | No | Yes (paired secondary namespace) |
| VNet / Private Endpoint | No | Yes |
| Sessions | Yes | Yes |

The emulator simulates **Standard tier**. All configuration in this lesson applies to
Standard unless noted otherwise.

---

## Queue Properties

When you create a queue — in the Azure Portal, via Bicep, or in the emulator config —
these are the properties you set:

| Property | Default | What it controls |
|----------|---------|-----------------|
| `DefaultMessageTimeToLive` | P14D (14 days) | How long the broker keeps a message before it expires. Expired messages are deleted or moved to the DLQ if `DeadLetteringOnMessageExpiration` is true. |
| `LockDuration` | PT1M (60 s) | How long a PEEK_LOCK holds before the broker assumes the consumer failed and re-delivers the message. Maximum 5 minutes. |
| `MaxDeliveryCount` | 10 | Number of failed PEEK_LOCK deliveries allowed before the broker automatically dead-letters the message. |
| `DeadLetteringOnMessageExpiration` | false | If true, expired messages go to the DLQ instead of being silently deleted. Useful for debugging. |
| `MaxSizeInMegabytes` | 1024 | Maximum storage the queue can hold. Sending to a full queue fails with `QuotaExceededException`. |
| `RequiresDuplicateDetection` | false | If true, the broker rejects messages whose `messageId` was seen within the detection window. Requires `messageId` to be set explicitly by the sender — the auto-generated UUID changes each send, so deduplication only works when the sender controls the ID. |
| `DuplicateDetectionHistoryTimeWindow` | PT10M (10 min) | How far back the broker checks for duplicate `messageId` values. |
| `RequiresSession` | false | If true, every message must carry a `sessionId`. Enables strictly ordered, session-locked processing — see Session vs Partition below. |
| `EnablePartitioning` | false | If true, the queue is distributed across 16 internal partitions (Standard tier only) — see Partitions below. |
| `AutoDeleteOnIdle` | never | Automatically removes the queue after this period of inactivity. |

### PEEK_LOCK — how it works

Azure Service Bus offers two receive modes:

| Mode | What happens on receive |
|------|------------------------|
| `RECEIVE_AND_DELETE` | The broker removes the message the moment it is delivered. If the consumer crashes mid-processing the message is gone. |
| `PEEK_LOCK` | The broker keeps the message but hides it from other consumers for `LockDuration`. The consumer must explicitly settle it when done. |

The Camel consumer uses **PEEK_LOCK** by default, which is the safe choice for any
pipeline where losing a message is not acceptable.

**The lock lifecycle:**

```
1. Consumer calls receiveMessages()
      → broker delivers message + lockToken
      → broker starts the LockDuration countdown
      → message is invisible to all other consumers

2. Consumer (Camel route) processes the message
      → Step1 → Step2 → Step3 → Step4 → Step5 → Step6 → Step7 → forwards to output queue

3a. Success: consumer calls complete(lockToken)
      → broker deletes the message from the input queue
      → deliveryCount is not incremented

3b. Failure / lock expires: broker re-delivers the message
      → deliveryCount is incremented by 1
      → if deliveryCount == MaxDeliveryCount, broker dead-letters automatically
```

**LockDuration and this pipeline:**

The pipeline has 7 steps, but only Steps 1–3 carry a configurable delay (15 s each by
default). Steps 4–7 execute immediately. Total processing time is ~45 s. The default
`LockDuration` of 60 s covers this with 15 s of headroom. If steps were slower you
would either:
- Increase `LockDuration` (max 5 minutes), or
- Call `receiver.renewMessageLock(message)` from inside a processor to reset the countdown.

**MaxDeliveryCount and dead-lettering:**

After `MaxDeliveryCount` failed deliveries the broker moves the message to the Dead
Letter Queue (DLQ) automatically, setting `deadLetterReason = "MaxDeliveryCountExceeded"`.
The message is then retrievable from `{queue}/$DeadLetterQueue` for investigation.

### How queue properties appear in a received message

These queue configuration properties directly shape the fields you see in `MessageView`
(covered in Message Anatomy):

| Queue property | Message field | Where you see it |
|----------------|--------------|-----------------|
| `DefaultMessageTimeToLive` | `timeToLive`, `expiresAt` | `timeToLive` shows the TTL set on the message (or inherited from the queue default). `expiresAt` = `enqueuedTime + timeToLive`. |
| `LockDuration` | `lockToken`, `lockedUntil` | Only populated under PEEK_LOCK. `lockedUntil` shows when the current lock expires. Null when using `peekMessages()`. |
| `MaxDeliveryCount` | `deliveryCount` | Starts at 0. Incremented each time a PEEK_LOCK delivery is abandoned or its lock expires. At `MaxDeliveryCount` the broker dead-letters the message. |
| `DeadLetteringOnMessageExpiration` | `deadLetterReason`, `deadLetterErrorDescription`, `deadLetterSource` | Populated only when the message is in the DLQ. When this flag is true, `deadLetterReason` will be `"TTLExpiredException"` for expired messages. |
| `RequiresDuplicateDetection` | `messageId` | The broker uses `messageId` as the deduplication key. For deduplication to work the sender must set a stable, deterministic `messageId` — the SDK's auto-generated UUID is different each time. |
| `RequiresSession` | `sessionId` | Every message must carry a `sessionId` when sessions are enabled. The broker uses it to group and lock related messages together. |
| `EnablePartitioning` | `partitionKey` | The broker uses `partitionKey` (or `messageId` if not set) to route the message to one of the 16 partitions. |

---

## Partitions

### What a partition is

When `EnablePartitioning` is true, Azure Service Bus splits the queue across
**16 independent partition stores**. Each partition is a separate FIFO sub-queue
backed by its own message store.

```
Queue "input" (partitioned)
  ├── partition 0   [msg A, msg D, ...]
  ├── partition 1   [msg B, ...]
  ├── ...
  └── partition 15  [msg C, msg E, ...]
```

Benefits:
- **Throughput** — 16 concurrent writers and readers instead of one.
- **Availability** — a transient issue in one partition store does not affect the others.

### How the broker assigns a partition

Without a `partitionKey` on the message, the broker hashes the `messageId` (a UUID)
to pick a partition. Distribution is roughly even but uncontrolled.

### partitionKey — pinning messages to a partition

Setting `partitionKey` on a message tells the broker: _put this message, and every
other message with this same key, into the same partition._

```java
msg.setPartitionKey("order-99");
```

All messages for `order-99` land in the same partition and are delivered in strict
arrival order. Messages for `order-100` land in a different partition and are also
in order — but relative ordering between `order-99` and `order-100` is not guaranteed.

**Example:**

| Message | partitionKey | Assigned partition | Ordering guarantee |
|---------|-------------|-------------------|-------------------|
| OrderPlaced | `"order-99"` | 7 | ✓ within order-99 |
| OrderShipped | `"order-99"` | 7 | ✓ within order-99 |
| OrderPlaced | `"order-100"` | 3 | ✓ within order-100 |
| (no key) | — | any (by messageId hash) | ✗ none |

### When to use partitionKey

Use it when you need **per-entity ordering** without serialising all traffic through
a single consumer. Typical cases:

- Events for the same customer or order must be processed in sequence.
- Inventory updates for the same SKU must not overtake each other.

If global ordering across all messages matters, use a single-partition queue (or a
session-enabled queue with one session).

### Sessions vs Partitions

Both provide ordering guarantees but at different levels:

| | Sessions (`RequiresSession`) | Partitions (`EnablePartitioning`) |
|-|------------------------------|----------------------------------|
| Key field | `sessionId` | `partitionKey` |
| Consumer behaviour | Consumer acquires a session lock — only that consumer receives all messages in the session | Normal competing consumers share all partitions |
| Ordering | Strict within a session | Strict within a partition |
| Use case | One consumer owns a conversation or workflow end-to-end | High-throughput with per-key ordering |

On a session-enabled + partitioned queue, the broker uses `sessionId` as the
partition key automatically.

### Partitioning on Premium tier

Premium tier does not use the 16-partition model. Instead it allocates dedicated
**Messaging Units** (MUs) per namespace — isolated compute and memory. Capacity
scales by adding MUs, not by internal sharding. `EnablePartitioning` has no effect
on Premium.

---

## Regions and Geo-Disaster Recovery

A namespace is deployed to one Azure **region** (e.g., West Europe, East US 2).
All queues in the namespace live in that region. The region appears in the endpoint:

```
sb://my-namespace.servicebus.windows.net/
```

### Standard tier — no built-in redundancy

On Standard, if the region has an outage your namespace is unavailable. You manage
redundancy yourself (for example, dual-writing to two namespaces in different regions
and routing consumers to whichever is healthy).

### Premium tier — Geo-Disaster Recovery

On Premium you can **pair** a primary and secondary namespace in different regions.
The pairing creates an alias DNS entry:

```
sb://my-namespace-alias.servicebus.windows.net/
```

Both primary and secondary use the same alias. If the primary region goes down you
trigger a failover — the alias re-points to the secondary and clients reconnect
without changing connection strings. Metadata (queues, topics, subscriptions) is
replicated; in-flight messages may be lost depending on the replication mode.

The emulator has no concept of regions or replication — it runs locally as a single
instance.

---

## Emulator Config

The `servicebus-emulator-config.json` file at the project root mirrors the queue
properties from above. Each queue now has an explicit `Properties` block:

```json
{
  "Name": "input",
  "Properties": {
    "DefaultMessageTimeToLive": "PT1H",
    "LockDuration": "PT1M",
    "MaxDeliveryCount": 10,
    "DeadLetteringOnMessageExpiration": true,
    "MaxSizeInMegabytes": 1024,
    "RequiresDuplicateDetection": false,
    "RequiresSession": false,
    "EnablePartitioning": false
  }
}
```

Notes on the chosen values:

| Property | Value | Why |
|----------|-------|-----|
| `DefaultMessageTimeToLive` | PT1H (1 hour) | Short TTL so the emulator stays clean between dev sessions. Production would use P14D or longer. |
| `LockDuration` | PT1M (60 s) | Covers the 3 × 15 s delayed steps (Steps 1–3) plus immediate Steps 4–7, with headroom. |
| `MaxDeliveryCount` | 10 | Default. After 10 failed deliveries the broker dead-letters the message. |
| `DeadLetteringOnMessageExpiration` | true | Moves expired messages to the DLQ instead of silently deleting them — useful when learning. |
| `EnablePartitioning` | false | Not needed for this single-consumer pipeline. The emulator accepts the flag but does not enforce partition routing the same way real Azure does. |

Time values use ISO 8601 duration format: `PT1M` = 1 minute, `PT1H` = 1 hour, `P14D` = 14 days.

---

← [Back to lessons](../README.md)

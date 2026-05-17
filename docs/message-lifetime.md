# Message Lifetime

You may have noticed that messages sent to the input queue with a runner disappear even
when the main app is not running and nothing consumed them. This lesson explains why,
and walks through three scenarios that determine what happens to a message.

---

## Why messages disappear — DefaultMessageTimeToLive

The input queue is configured with `DefaultMessageTimeToLive: PT5M`. The broker tracks
the age of every message sitting in the queue. After 5 minutes without being consumed,
the message is considered stale.

Because `DeadLetteringOnMessageExpiration: true` is also set, the broker does not silently
delete the expired message — it moves it to the **Dead Letter Queue (DLQ)**. The DLQ is a
system sub-queue attached to every queue. Messages there are safe to inspect and can be
reprocessed if needed.

---

## Scenario 1 — Nobody picks up the message

Stop the main app if it is running. Use both runners to put messages onto the input queue:

```bash
./mvnw test -Dtest=EventGridBlobEventRunner -DfailIfNoTests=false
./mvnw test -Dtest=AzureFunctionMessageRunner -DfailIfNoTests=false
```

Confirm the messages are present:

```bash
curl http://localhost:8081/queues/input/messages
```

Wait 5 minutes. Peek the input queue again — it will be empty. Then check the DLQ:

```bash
curl http://localhost:8081/queues/input/deadletter
```

You will see both messages there with:

| Field | Value |
|-------|-------|
| `deadLetterReason` | `"TTLExpiredException"` |
| `deadLetterSource` | `"input"` |
| `state` | `"Deadlettered"` |

The broker moved them here because no consumer picked them up within the TTL window.

---

## Scenario 2 — The app takes too long (lock expiry)

When the Camel consumer picks up a message it does so under **PEEK_LOCK**. The broker:

1. Delivers the message to the consumer
2. Hides it from all other consumers
3. Starts the `LockDuration` countdown — **PT1M (60 seconds)**

The pipeline takes ~45 seconds (3 steps × 15 s), leaving 15 seconds of headroom. If
processing takes longer than 60 seconds the lock expires and the broker assumes the
consumer failed. It then:

- Makes the message visible again for re-delivery
- Increments `deliveryCount` by 1

This repeats until `deliveryCount` reaches `MaxDeliveryCount` (10). At that point the
broker stops re-delivering and moves the message to the DLQ with:

| Field | Value |
|-------|-------|
| `deadLetterReason` | `"MaxDeliveryCountExceeded"` |
| `deliveryCount` | `10` |

The message is never lost — it ends up in the DLQ where it can be inspected.

> Lock expiry is a real risk if step delays grow or if a downstream call becomes slow.
> The options are to increase `LockDuration` (maximum 5 minutes) or to call
> `renewMessageLock()` from inside a processor to reset the countdown.

---

## Scenario 3 — The app throws an exception

When the Camel route throws an unhandled exception during processing:

1. Camel abandons the message — it releases the lock without calling `complete()`
2. The broker makes the message visible again
3. `deliveryCount` increments by 1
4. The broker re-delivers the message on the next poll

The same `MaxDeliveryCount` limit applies. After 10 failed deliveries the broker moves
the message to the DLQ with `deadLetterReason: "MaxDeliveryCountExceeded"`.

The message is never lost. The DLQ acts as a safety net for messages the application
could not process, giving you the opportunity to investigate and replay them.

---

## When is a message successfully consumed?

When the Camel route completes without exception, Camel calls `complete(lockToken)` on
the Azure Service Bus SDK. The broker:

- **Permanently deletes** the message from the input queue
- Does **not** increment `deliveryCount`
- Releases the lock

This is why the input queue is empty after the main app processes messages — they are
deleted on success, not moved anywhere.

---

## Checking the DLQ

The inspection app exposes DLQ endpoints for both queues:

```bash
# Input DLQ
curl http://localhost:8081/queues/input/deadletter

# Output DLQ
curl http://localhost:8081/queues/output/deadletter
```

Key fields to examine when a message lands in the DLQ:

| Field | What it tells you |
|-------|------------------|
| `deadLetterReason` | `"TTLExpiredException"` — nobody consumed it in time. `"MaxDeliveryCountExceeded"` — the app failed or was too slow too many times. |
| `deliveryCount` | How many times the broker attempted delivery before giving up. `0` means the message expired before a single delivery attempt. |
| `deadLetterSource` | Which queue the message came from. |
| `enqueuedTime` | When the original message was first accepted by the broker. |

---

← [Back to lessons](../README.md)

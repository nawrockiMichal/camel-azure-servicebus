# Message Anatomy

This lesson explains what an Azure Service Bus message is made of, why certain fields
are null, and what Camel silently adds on top when it receives a message.

---

## The Three Layers of a Message

An Azure Service Bus message has three distinct layers:

```
┌─────────────────────────────────────────────────────┐
│  Layer 3 — Camel exchange headers                   │  ← Camel-internal only
│  CamelAzureServiceBusMessageId                      │    not on the wire
│  CamelAzureServiceBusApplicationProperties          │    only in your JVM
│  CamelAzureServiceBusCorrelationId  ...             │
├─────────────────────────────────────────────────────┤
│  Layer 2 — System / broker properties               │  ← Azure SB sets these
│  messageId, enqueuedTime, sequenceNumber            │
│  deliveryCount, state, lockToken, expiresAt ...     │
├─────────────────────────────────────────────────────┤
│  Layer 1 — Application data                         │  ← your code sets these
│  body                "Hello from component test"    │
│  applicationProperties  { stage: "processed" }      │
│  correlationId, subject, contentType, replyTo ...   │
└─────────────────────────────────────────────────────┘
```

Only Layers 1 and 2 travel over the wire to Azure Service Bus.
Layer 3 exists only inside a running Camel route.

---

## Layer 1 — Application Data

These are the fields **the sender** controls.

### Who is "the sender"?

The sender is whoever calls `sender.sendMessage(msg)` using the Azure Service Bus SDK.
In this project there are three senders:

| Where | Who sends |
|-------|-----------|
| Runners | `EventGridBlobEventRunner` and `AzureFunctionMessageRunner` — each creates a `ServiceBusMessage`, sets the Layer 1 fields relevant to its scenario, and sends it to the **input** queue. |
| Component test | `ServiceBusComponentTest` — same as a runner but also spins up the full application and asserts the result. |
| Inside the Camel route | The Camel producer — when `Step3Processor` passes the exchange to `.to("azure-servicebus:output?...")`, the Camel `ServiceBusProducer` builds a `ServiceBusMessage` from the exchange and calls the SDK to put it onto the **output** queue. Camel is the sender here, not your processor code. |

### The body

The payload — whatever string, bytes, or binary data you put in:

```java
new ServiceBusMessage("Hello from component test")
```

In the inspection response:
```json
"body": "Hello from component test"
```

The body passes through all three processors untouched.
Step2Processor reads it for logging but never modifies it.

### applicationProperties

A free-form key-value map. The broker stores it alongside the body and delivers it
to the consumer unchanged. **The broker never reads or interprets these values.**

In your pipeline:
```json
"applicationProperties": {
  "stage": "processed"
}
```

- The **test sender** wrote `stage=initial`
- **Step2Processor** changed it to `stage=processed`
- The **Camel producer** (sender to the output queue) preserved the updated value
- The broker had no opinion on either value at any point

This is the primary mechanism for passing metadata between services.
Think of it as the equivalent of HTTP headers on a message.

### Why correlationId is null — but messageId is not

`messageId` is populated even though the sender never called `msg.setMessageId(...)`.
`correlationId` is null or set depending entirely on who the sender is. The difference:

| Field | Who fills it | What happens if you don't set it |
|-------|-------------|----------------------------------|
| `messageId` | The **Azure SDK** auto-generates a UUID before calling the broker. | Always has a value. |
| `correlationId` | Only **your application** sets it. Azure SB never auto-generates it. | Stays null. |

Azure Service Bus has no idea what "correlating a message" means in your business context —
is it a request ID? an order ID? a session token? Only your application knows, so the broker
leaves it to you.

This is visible when comparing the two runners: the Event Grid runner leaves `correlationId`
null because Event Grid has no business context to provide. The Azure Function runner sets it
explicitly to a trace ID, because the function owns the business flow and can track it.

### The other sender-set fields that are null

Stop the main app if it is running (Ctrl+C). With nothing consuming from the input queue,
messages will sit there long enough to observe.

Run both runners to put two messages onto the input queue:

```bash
./mvnw test -Dtest=EventGridBlobEventRunner -DfailIfNoTests=false
./mvnw test -Dtest=AzureFunctionMessageRunner -DfailIfNoTests=false
```

Peek the input queue:

```bash
curl http://localhost:8081/queues/input/messages
```

Comparing the two messages shows exactly which fields each sender sets. The fields below
are null on the Event Grid message because Event Grid has no business context to provide.
The Azure Function message sets some of them — compare the two responses to see which:

| Field | Why null on the Event Grid message |
|-------|-----------------------------------|
| `correlationId` | Event Grid does not track business correlation — see above. |
| `replyTo` | Only relevant in request/reply patterns. Neither sender uses it. |
| `replyToSessionId` | Only relevant when `replyTo` targets a session-enabled queue. |
| `sessionId` | Only relevant when using session-enabled queues for ordered processing. |
| `partitionKey` | Only relevant on partitioned entities (Premium tier). |
| `scheduledEnqueueTime` | Message was enqueued immediately. Neither sender schedules delivery. |

**These fields being null is normal and expected.** They are all optional features of
Azure Service Bus that you opt into when you need them.

---

## Layer 2 — System / Broker Properties

These are set **by the Azure Service Bus broker** the moment your message arrives.
You cannot set them from your application code — they are always broker-assigned.

The messages sent by the runners in the previous section are still sitting in the input queue.
Peek it to see the broker fields:

```bash
curl http://localhost:8081/queues/input/messages
```

| Field | Example value | What it means |
|-------|---------------|---------------|
| `messageId` | `"cc042729..."` | A UUID the SDK generated automatically because neither runner called `setMessageId()`. |
| `enqueuedTime` | `"2026-05-15T21:29:40.615Z"` | The exact moment the broker accepted the message. |
| `sequenceNumber` | `6` | Ordinal position in this queue since the emulator started. Monotonically increasing. |
| `expiresAt` | `enqueuedTime + 5 min` | The input queue has `DefaultMessageTimeToLive: PT5M`, so the broker sets this to `enqueuedTime + 5 minutes`. |
| `deliveryCount` | `0` | This message has never been delivered under PEEK_LOCK. The inspection app uses `peekMessages()` which does not count as a delivery. |
| `state` | `"ACTIVE"` | Ready for a consumer to pick up. |

### Why deliveryCount is 0

The inspection app calls `peekMessages()`, not `receiveMessages()`.
Peek does not lock or deliver the message, so the broker never increments `deliveryCount`.
Only a `PEEK_LOCK` receive followed by an abandon or lock expiry would increment it.

### The null broker fields

| Field | Why null |
|-------|----------|
| `lockToken` | No lock was acquired. Peek mode does not lock. Under PEEK_LOCK receive this would be a UUID. |
| `lockedUntil` | No lock, so no expiry time. |
| `enqueuedSequenceNumber` | `0` — the message was never forwarded or auto-transferred from another entity. |
| `deadLetterReason` | Message is healthy. Only populated when a message is in the DLQ. |
| `deadLetterErrorDescription` | Same — only populated in the DLQ. |
| `deadLetterSource` | Same — only populated in the DLQ. |

For a full description of every field you see in the inspection response, see `MessageView`
and `CamelMessageView` in the `inspection` package — each field is documented with its type,
source, and behaviour.

---

## Layer 3 — What Camel Adds (Internal Only)

When the Camel consumer receives a message from the input queue it maps every SDK field
to a named exchange header (`CamelAzureServiceBus*`) and spreads each `applicationProperties`
entry as its own individual header. These headers exist only inside the Camel runtime — they
are never written to Azure Service Bus and will not appear on any message you peek from the
output queue.

This project does not use these headers directly. The full mapping — every header name, its
type, and whether it is read-only or read/write — is documented in `CamelMessageView` in the
`inspection` package.

---

## Summary

```
What YOU write:
  body                    → the payload, unchanged by the broker
  applicationProperties   → free-form metadata, unchanged by the broker
  correlationId / subject / replyTo / ...  → optional, all null when not needed

What the BROKER writes:
  messageId (if you did not set one)
  enqueuedTime, sequenceNumber, expiresAt
  deliveryCount, state
  lockToken, lockedUntil  (only under PEEK_LOCK receive)
  deadLetter* fields      (only in the DLQ)

What CAMEL adds (JVM-internal, not on the wire):
  CamelAzureServiceBus* headers  → one per SDK field, for use inside processors
  individual spread headers       → one per applicationProperties key
  producerOperation               → controls send vs schedule (Camel-only concept)
  transactionContext              → groups operations into a transaction (Camel-only concept)
```

The distinction matters because:
- If you want to **inspect** a message in Azure Service Bus (e.g. via this inspection app
  or the Azure portal), you only see Layers 1 and 2.
- If you want to **influence** what Camel does when routing a message, you work with
  Layer 3 headers inside your processors.

---

← [Back to lessons](../README.md)

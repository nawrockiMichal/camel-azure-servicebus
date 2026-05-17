# Inspecting Queue Messages

In this lesson you use the inspection REST API to peek at messages sitting in the queues
without consuming or locking them. You will send a message through the live pipeline and
watch it appear in the output queue, then explore the two available views.

---

## What the Inspection App Does

The inspection app calls `peekMessages()` on the Azure Service Bus SDK.
`peek` is a special read mode that:

- Returns a copy of the message
- Does **not** lock it (no `lockToken` is acquired)
- Does **not** count as a delivery (no `deliveryCount` increment)
- Does **not** remove it from the queue

This means you can call the endpoint as many times as you like without affecting the pipeline.

---

## Step 1 — Send a Message and Peek the Output Queue

Before inspecting, send a message through the live pipeline using a runner. With the main
app running, open a new terminal and run:

```bash
./mvnw test -Dtest=EventGridBlobEventRunner -DfailIfNoTests=false
```

Wait ~45 seconds for the message to travel through all steps (3 steps × 15 s delay), then
peek the output queue:

```bash
curl http://localhost:8081/queues/output/messages
```

Or open in a browser: `http://localhost:8081/queues/output/messages`

You will see the processed message:

```json
[
  {
    "messageId": "cc042729b7ed4ef4b42afa374b8f2b62",
    "correlationId": null,
    "body": "Hello from EventGridBlobEventRunner",
    "applicationProperties": {
      "stage": "processed"
    },
    "enqueuedTime": "2026-05-15T21:29:40.615Z",
    "sequenceNumber": 6,
    "deliveryCount": 0,
    "state": "ACTIVE",
    "lockToken": null,
    "deadLetterReason": null,
    ...
  }
]
```

### Key fields to notice

| Field | Value | What it tells you |
|-------|-------|-------------------|
| `applicationProperties.stage` | `processed` | Step2Processor did its job |
| `deliveryCount` | `0` | The message has never been delivered under PEEK_LOCK — the test used `peekMessages()` to assert, so the broker never counted it as a delivery |
| `state` | `ACTIVE` | Ready for a consumer to pick up |
| `lockToken` | `null` | No lock — we peeked, not received |
| `deadLetterReason` | `null` | Message is healthy, not dead-lettered |

---

## Step 2 — Peek the Input Queue

The input queue should be empty after the test consumed the message through the Camel route:

```bash
curl http://localhost:8081/queues/input/messages
```

Expected: `[]`

---

## Step 3 — Peek the Dead Letter Queue

Every queue automatically has a sub-queue called the Dead Letter Queue (DLQ).
Messages land there when the broker gives up delivering them (e.g. `MaxDeliveryCountExceeded`)
or when the application explicitly dead-letters them.

```bash
curl http://localhost:8081/queues/output/deadletter
```

Expected: `[]` — nothing has failed yet.

---

## Step 4 — The Camel View

Add `?view=camel` to see an extended view that shows, for every field, the Camel exchange
header constant you would use to read or set it inside a Camel processor.

```bash
curl "http://localhost:8081/queues/output/messages?view=camel"
```

Each field now has a companion `*CamelHeader` entry showing the exchange header constant
you would use inside a processor. For example, the first message (sent by the Azure Function
simulation in the component test):

```json
{
  "messageId": "cc042729b7ed4ef4b42afa374b8f2b62",
  "messageIdCamelHeader": "CamelAzureServiceBusMessageId",

  "correlationId": null,
  "correlationIdCamelHeader": "CamelAzureServiceBusCorrelationId",

  "subject": "OrderReceived",
  "subjectCamelHeader": "CamelAzureServiceBusSubject",

  "contentType": "text/plain",
  "contentTypeCamelHeader": "CamelAzureServiceBusContentType",

  "body": "Hello from component test",

  "applicationProperties": { "stage": "processed" },
  "applicationPropertiesCamelHeader": "CamelAzureServiceBusApplicationProperties",

  "spreadAsIndividualHeaders": { "stage": "processed" },

  "deliveryCount": 0,
  "deliveryCountCamelHeader": "CamelAzureServiceBusDeliveryCount",

  "lockToken": null,
  "lockTokenCamelHeader": "CamelAzureServiceBusLockToken",

  "producerOperation": null,
  "producerOperationCamelHeader": "CamelAzureServiceBusProducerOperation",

  "transactionContextCamelHeader": "CamelAzureServiceBusServiceBusTransactionContext"
}
```

### Header access legend

| Marker | Meaning |
|--------|---------|
| `R` | Set by the **consumer** on the inbound exchange. Read in a processor with `exchange.getMessage().getHeader("CamelAzureServiceBus...")`. The **producer** does NOT write it back when sending. |
| `R/W` | Set by the consumer **and** read back by the producer when sending. Setting it in a processor affects the outgoing message. |
| `CFG` | Not a per-message header. Configured globally in `application.properties`. |

### Two fields that exist only in Camel (no Azure SB equivalent)

| Field | Header | Purpose |
|-------|--------|---------|
| `producerOperation` | `CamelAzureServiceBusProducerOperation` | Switch between `sendMessages` (immediate) and `scheduleMessages` (delayed) |
| `transactionContext` | `CamelAzureServiceBusServiceBusTransactionContext` | Carry a transaction across multiple send/complete operations |

---

## Step 5 — Send Another Message and Watch It Appear

Run another runner to send a second message and watch a new entry appear in the output queue.
The available runners and what each one sends are listed in the `runner` package under
`src/test/java`. For example, to simulate a message with a validation failure:

```bash
./mvnw test -Dtest=MissingRequiredFieldRunner -DfailIfNoTests=false
```

Then peek the input DLQ (this message will fail Step 3 and dead-letter after 3 attempts):

```bash
curl http://localhost:8081/queues/input/deadletter
```

> To make the wait shorter, run the main app with the test profile (1-second delays):
> ```bash
> SPRING_PROFILES_ACTIVE=test ./mvnw spring-boot:run
> ```

---

## Endpoint Reference

| Endpoint | Description |
|----------|-------------|
| `GET /queues/{name}/messages` | Peek up to 10 messages from the queue |
| `GET /queues/{name}/messages?count=50` | Peek up to 50 messages |
| `GET /queues/{name}/messages?view=camel` | Add Camel exchange header info to each field |
| `GET /queues/{name}/deadletter` | Peek the Dead Letter Queue |
| `GET /queues/{name}/deadletter?view=camel` | DLQ with Camel view |

---

← [Back to lessons](../README.md)

# Exception Handling

This lesson walks through four exception scenarios in the Camel route. Each is triggered
by a dedicated runner and produces a different outcome depending on whether and how the
exception is handled.

---

## How Camel settles a message on failure

The Camel Azure Service Bus consumer receives messages under **PEEK_LOCK**. When a route
completes, Camel settles the message with the broker in one of two ways:

| Route outcome | Settlement | Result |
|---------------|-----------|--------|
| Exchange succeeds | `complete(lockToken)` | Message permanently deleted from the queue |
| Exchange fails (unhandled or `handled(false)`) | `abandon(lockToken)` | Message returned to the queue; broker increments `deliveryCount` |

An `onException` clause with `handled(true)` makes Camel treat the exchange as a success
even though an exception occurred — so it calls `complete()`, not `abandon()`.

---

## Scenario 1 — No exception handler (NullPointerException)

**Runner:** `MissingRequiredFieldRunner`

**What happens in the code:**

`Step3Processor` calls `Objects.requireNonNull` on the `source` application property.
The runner sets `triggerNpe=true` but deliberately omits `source`. The call throws
`NullPointerException` with the message `'source' application property is required but
was not set by the sender`.

There is no `onException(NullPointerException.class)` in the route. Camel's default error
handler catches it and abandons the message back to the broker.

**What to observe:**

Run the runner with the main app running:

```bash
./mvnw test -Dtest=MissingRequiredFieldRunner -DfailIfNoTests=false
```

The broker requeues the message and Camel picks it up again — the same exception fires
on every attempt. Each failed attempt increments `deliveryCount`. After `MaxDeliveryCount`
exhausted attempts the broker dead-letters the message automatically.

Check the input DLQ:

```bash
curl http://localhost:8081/queues/input/deadletter
```

| Field | Value |
|-------|-------|
| `deadLetterReason` | `"MaxDeliveryCountExceeded"` |
| `deliveryCount` | equal to `MaxDeliveryCount` configured on the queue |

**Key takeaway:** an unhandled exception means the message is retried by the broker until
the delivery limit is reached, then dead-lettered. The message is never lost, but it
occupies the queue and consumes retries on every delivery.

---

## Scenario 2 — Transient failure, abandon to broker immediately (DownstreamServiceException)

**Runner:** `DownstreamServiceFailureRunner`

**What happens in the code:**

`Step4Processor` checks for `simulateHttpError=true` and throws `DownstreamServiceException`
simulating an HTTP 500 received from a downstream REST client.

The route has:

```java
onException(DownstreamServiceException.class)
    .handled(false)
    .log("downstream service unavailable, abandoning to broker for redelivery");
```

Camel abandons the message immediately on the first failure — no internal retries.
The broker requeues it and increments `deliveryCount` on each redelivery. After
`MaxDeliveryCount` exhausted attempts the broker dead-letters the message.

The broker behaviour here is **identical to Scenario 1** — the message is abandoned and
requeued in both cases. The difference is intent: an explicit `onException` clause with a
custom log makes it clear this exception is known and expected, whereas Scenario 1 has no
handler because the `NullPointerException` is an unexpected bug. Same outcome, different
meaning in code.

**What to observe:**

Run the runner:

```bash
./mvnw test -Dtest=DownstreamServiceFailureRunner -DfailIfNoTests=false
```

Peek the input queue immediately after the failure log appears:

```bash
curl http://localhost:8081/queues/input/messages
```

The message is back in the queue with `deliveryCount` incremented. After `MaxDeliveryCount`
broker redeliveries the message is dead-lettered.

```bash
curl http://localhost:8081/queues/input/deadletter
```

**Why not use Camel-internal retries?**

Camel does offer `maximumRedeliveries(N)` + `redeliveryDelay(Ms)` which retries the route
in-memory before abandoning. This looks convenient but is a risky pattern with PEEK_LOCK:

- The message lock is **held throughout all Camel retry attempts**. The broker still sees
  the message as locked — it cannot be redelivered to another consumer and `deliveryCount`
  does not increment until Camel finally abandons.
- If the total retry time (delays + processing) exceeds `LockDuration`, the lock expires
  mid-retry. The broker makes the message available again while Camel is still trying to
  process it, leading to duplicate delivery.
- If the app crashes during a Camel retry, the message stays invisible to all consumers
  for the full remaining `LockDuration` before the broker releases it. Longer retry delays
  mean longer recovery windows.

Abandoning to the broker is the safer pattern — the broker owns the retry schedule,
`deliveryCount` is accurate, and a crash releases the message immediately once the lock
expires.

**What if a delay between redeliveries is needed?**

Azure Service Bus does not support abandon-with-delay — abandoned messages are requeued
immediately. One workaround is to catch the exception inside the processor, `Thread.sleep`,
and rethrow, so the delay happens before Camel abandons. This is fragile: the sleep time
plus all remaining route processing must stay well within `LockDuration`, and any mistake
risks the lock expiring mid-sleep. Treat this as a last resort and keep the delay short.

**Key takeaway:** let the broker handle redelivery timing. `maximumRedeliveries(0)` with
`handled(false)` is the correct pattern for transient failures in a PEEK_LOCK consumer —
it keeps the lock window short and leaves retry scheduling to the broker.

---

## Scenario 3 — Business requirement changes: fail immediately instead of retrying

**Runner:** `DownstreamServiceFailureRunner` (same as Scenario 2)

**The code does not change — the business requirement does.**

In Scenario 2 the team decided that an HTTP 500 from the downstream service is a transient
failure worth retrying via broker redelivery. Suppose the requirement now changes: the
operations team wants any downstream failure to go straight to the DLQ on the first
attempt so they can inspect and reprocess it manually, rather than letting it cycle through
the queue multiple times.

**The observability problem with retries**

When a message is retried and eventually dead-lettered by the broker, the DLQ entry shows:

```
deadLetterReason: MaxDeliveryCountExceeded
deadLetterErrorDescription: Message could not be consumed after 3 delivery attempts.
```

This tells you the message exhausted its retries — but gives no information about *why*
it failed. To find the actual exception you have to search the application logs by
`correlationId` or `messageId`, correlating log entries across multiple delivery attempts.
In a high-volume system this investigation can be slow and error-prone.

With `enable-dead-lettering=true` the message goes to the DLQ on the first failure with
the exception class and message as `deadLetterReason` and the full stack trace as
`deadLetterErrorDescription` — the cause is visible directly in the DLQ without any log
search. The trade-off is that you lose the automatic retry window.

No code changes are needed. This is a configuration change.

---

**Configuration change**

In `application.properties`, set:

```properties
service-bus-route.enable-dead-lettering=true
```

Then **restart the application** — this is a consumer startup setting and is not picked up
at runtime.

---

**What changes under the hood**

With `enable-dead-lettering=true` the Camel Azure Service Bus consumer calls `deadLetter()`
instead of `abandon()` when an exchange fails. The message goes directly to the DLQ on the
first failure with:

- `deadLetterReason` — exception class name and message, e.g.:
  `uk.selflearning.apache.camel.exception.DownstreamServiceException: HTTP 500: Service Unavailable`
- `deadLetterErrorDescription` — full stack trace
- `deliveryCount` — `1` (only one attempt was made, broker never requeued it)

---

**What to observe**

Run the same runner as Scenario 2:

```bash
./mvnw test -Dtest=DownstreamServiceFailureRunner -DfailIfNoTests=false
```

Check the DLQ immediately after the failure log appears — the message arrives there on the
first attempt with no broker redelivery cycle:

```bash
curl http://localhost:8081/queues/input/deadletter
```

| Field | Value |
|-------|-------|
| `deadLetterReason` | exception class + message |
| `deadLetterErrorDescription` | stack trace |
| `deliveryCount` | `1` |

Compare this with Scenario 2 where `deliveryCount` equals `MaxDeliveryCount` and
`deadLetterReason` is `MaxDeliveryCountExceeded`.

---

**Effect on other exception handlers**

`enable-dead-lettering` is a consumer-level setting — it applies to **every failure** from
the input queue consumer, not only `DownstreamServiceException`:

| Scenario | With `enable-dead-lettering=false` | With `enable-dead-lettering=true` |
|----------|------------------------------------|-----------------------------------|
| 1 — NullPointerException | Broker retries → DLQ after `MaxDeliveryCount` | Dead-letters on first failure |
| 2 — DownstreamServiceException | Broker retries → DLQ after `MaxDeliveryCount` | Dead-letters on first failure |
| 3 — DownstreamServiceException | same as above | Dead-letters on first failure ← intended |
| 4 — MessageFormattingException | `complete()` — unchanged | `complete()` — unchanged (`handled(true)` is unaffected) |

Scenarios 1 and 2 lose their broker retry window when this setting is on. Remember to set
it back to `false` and restart the app before running those scenarios again.

**Key takeaway:** the same exception handler produces a different settlement outcome purely
through configuration. `enable-dead-lettering=true` skips broker retries and provides a
richer dead-letter reason, but it is a global setting — enable it deliberately and revert
it when done.

---

## Scenario 4 — Handled exception, message silently consumed (MessageFormattingException)

**Runner:** `MessageFormattingFailureRunner`

**What happens in the code:**

`Step5Processor` checks for `simulateFormattingError=true` and throws
`MessageFormattingException`.

The route has:

```java
onException(MessageFormattingException.class)
    .handled(true)
    .log("message formatting failed, message consumed but not forwarded to output queue");
```

`handled(true)` makes Camel treat the exchange as successfully completed. Camel calls
`complete(lockToken)` — the message is permanently deleted from the input queue. Because
the exception interrupted the route before reaching `.to("azure-servicebus:output")`,
nothing is forwarded to the output queue.

**What to observe:**

Run the runner:

```bash
./mvnw test -Dtest=MessageFormattingFailureRunner -DfailIfNoTests=false
```

Check both queues — the message is absent from both:

```bash
curl http://localhost:8081/queues/input/messages    # empty — message consumed
curl http://localhost:8081/queues/output/messages   # no new message — never forwarded
```

The message is also not in the DLQ:

```bash
curl http://localhost:8081/queues/input/deadletter  # empty — message was completed, not abandoned
```

**Key takeaway:** `handled(true)` silently consumes the message. The broker considers it
successfully processed, but the application chose not to forward it. Use this pattern
carefully — it is the easiest way to lose a message with no trace in the DLQ.

---

## Summary

| Scenario | Exception | `maximumRedeliveries` | `handled` | Settlement | Message fate |
|----------|-----------|-----------------------|-----------|-----------|--------------|
| No handler | `NullPointerException` | — | — | `abandon()` | Broker requeues → DLQ after `MaxDeliveryCount` |
| Transient failure (`enable-dead-lettering=false`) | `DownstreamServiceException` | — | `false` | `abandon()` | Broker requeues → DLQ after `MaxDeliveryCount` |
| Fail immediately (`enable-dead-lettering=true`) | `DownstreamServiceException` | — | `false` | `deadLetter()` | Straight to DLQ on first failure |
| Formatting failure | `MessageFormattingException` | — | `true` | `complete()` | Consumed — disappears from input, never reaches output |

---

← [Back to lessons](../README.md)

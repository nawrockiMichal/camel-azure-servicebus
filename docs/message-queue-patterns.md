# Message Queue Patterns

This lesson covers a small number of patterns that are directly relevant to this project.
Message-based systems improve scalability and resilience by separating services and
allowing work to happen asynchronously, but they introduce a different class of problems
that require specific architectural patterns to control risk. Failures, retries, delays,
and inconsistencies are expected behaviour rather than exceptional situations.

---

## Dead Letter Queue (DLQ)

Messages that cannot be processed after a configured number of attempts are moved to a
separate queue rather than being discarded. The DLQ acts as a safety net — no message is
lost, unprocessable messages are isolated so they do not block healthy ones, and the
operations team can inspect, fix, and reprocess them when ready.

**In this project:** every failure scenario lands in the DLQ. This is the central
architectural decision — nothing is ever silently discarded.

**DLQ as a backlog for new systems**

The DLQ is especially useful when building a system incrementally. When a system is first
deployed it may only handle a fraction of the message types it will eventually support.
Rather than blocking the launch, the rule is:

> **"We will implement it later — until then, it must land in the DLQ."**

Unknown or not-yet-implemented message types fail and wait in the DLQ. Nothing is lost.
As each case is implemented and deployed, messages of that type stop reaching the DLQ.
The DLQ becomes the backlog for unimplemented behaviour — each sprint that ships a new
handler is a sprint that reduces it.

---

## Retry Pattern

On failure the consumer retries the same message, giving transient downstream failures
time to recover. The number of retries is bounded to prevent a permanently broken message
from consuming resources forever. When retries are exhausted the message moves to the DLQ.

The key distinction is between **transient failures** (network timeout, service
temporarily unavailable — worth retrying) and **permanent failures** (invalid data,
authentication error — retrying will never succeed). Applying retries to permanent
failures wastes resources and delays the message reaching the DLQ where it belongs.

**In this project:** Azure Service Bus handles retries at the broker level via
`MaxDeliveryCount`. The exception handling lesson shows how the same exception handler
produces different outcomes depending on whether the failure is treated as transient or
permanent.

---

## Idempotent Consumer

With at-least-once delivery a message may be processed more than once — due to retries,
lock expiry, or a consumer crash mid-processing. An idempotent consumer produces the same
result whether it processes a message once or multiple times.

This is achieved by checking whether the work for a given `messageId` has already been
done before acting, typically using a durable store (database, cache) as the record of
completed work.

**Responsibility belongs to the consumer, not the producer.** A producer cannot reliably
prevent sending a message more than once — it may crash after sending but before
acknowledging the original trigger, so the trigger is redelivered and the message is sent
again. Attempting to track "what I already sent" from the producer side just relocates
the same problem. The consumer is the correct place to implement the check because it is
the one with the side effect and access to its own persistence layer.

This becomes especially important in fan-out scenarios — when a single input message
triggers multiple downstream messages. If the original trigger is redelivered, the entire
fan-out happens again. Each downstream consumer must independently protect against
duplicates. The producer's only responsibility is to generate a **deterministic,
reproducible `messageId`** for each outgoing message so consumers can identify duplicates
regardless of how many times the message is delivered.

```
// Fan-out: one trigger generates N child messages, each with a deterministic ID
function onTriggerReceived(triggerId, items):
    for each item in items:
        childMessageId = triggerId + ":" + item.id   // stable — same on every redeliver
        queue.send(topic="items", messageId=childMessageId, payload=item)

// Consumer at the end of the chain implements idempotency
function onItemReceived(messageId, item):
    if processedIds.contains(messageId):
        log.info("duplicate — skipping: " + messageId)
        return

    processedIds.add(messageId)       // mark before acting (durable store)
    externalSystem.write(item)        // side effect — happens exactly once
```

The producer generates stable IDs. The consumer checks them. Idempotency is guaranteed
at the point where the side effect occurs, regardless of how many times the message
arrives or how many hops it took to get there.

**In this project:** the processors do not implement idempotency — they are intentionally
simple. In a production system, any processor with a side effect (writing to a database,
calling an external API) should be idempotent to handle redelivery safely.

---

## Correlation ID

A unique identifier attached to a message and carried through every step of processing,
including log entries, downstream calls, and error records. When a failure occurs the
correlation ID makes it possible to trace the full history of a message across services
and time without guessing which log lines belong together.

**In this project:** the exception handling lesson shows why this matters — when a message
is retried by the broker and eventually dead-lettered with `MaxDeliveryCountExceeded`,
the only way to find the original failure reason is to search application logs by
`messageId` or `correlationId`. Without it, the investigation is a manual search across
unrelated log lines.

---

## Single Responsibility

Each component should do one thing and do it well. In a message-driven pipeline this
means each processor handles exactly one concern rather than mixing multiple
responsibilities in a single step.

This makes individual steps easier to test, reason about, and replace. It also makes
failure handling cleaner: an exception thrown by a processor with a single responsibility
has a clear meaning, whereas a processor that does five things can fail for five different
reasons, making the error harder to handle correctly.

**In this project:** the pipeline is split into numbered processors (Step 1 through Step 7)
each with a distinct responsibility. The exception scenarios in the exception handling
lesson are possible precisely because each step has a clear, single failure mode.

---

## Fire-and-Forget

Some messages are low-value and high-volume — metrics, heartbeats, real-time position
updates, telemetry. The data is only useful if it arrives quickly; a stale reading
delivered after a retry is often worthless. In these cases accepting message loss is a
deliberate design decision rather than a failure.

A fire-and-forget producer sends a message and does not wait for acknowledgement. There
are no retries and no DLQ. If the broker or consumer is unavailable the message is
dropped, and that is acceptable.

```
function publishHeartbeat(serviceId, timestamp):
    try:
        broker.send(topic="heartbeats", payload={serviceId, timestamp})
        // no ack, no retry — if it is lost the next heartbeat arrives in 10 seconds
    catch BrokerUnavailableException:
        log.warn("heartbeat dropped — broker unavailable")
        // swallow the exception; the monitoring system handles gaps in the series
```

The critical question before applying this pattern is: **what is the cost of losing one
message?** For a heartbeat — negligible. For an order confirmation — unacceptable.
Fire-and-forget is only appropriate when the answer is "negligible."

---

## Circuit Breaker

The name comes from electrical engineering: a circuit breaker trips when current exceeds
a safe threshold, cutting the flow before damage spreads. In software the idea is the
same — when calls to a downstream service exceed a failure threshold, the circuit breaker
stops forwarding requests and fails immediately, preventing the problem from propagating
further. After a cooldown period it allows a single probe call through; if that succeeds,
normal operation resumes.

The circuit breaker serves two purposes simultaneously, and both are equally valid
reasons to use it.

**Protecting your own service.** When a downstream call hangs or times out, your
consumer thread is blocked for the full duration of the timeout. Under sustained failures
this adds up: threads pile up waiting for responses, message processing slows, queue
depth grows, and eventually your service becomes unresponsive to new messages. The
circuit breaker eliminates the wait — when the circuit is open it throws immediately, the
thread is freed, and your service continues processing other messages at full speed.

**Protecting the downstream service.** A struggling service that is flooded with requests
it cannot handle has no chance to recover. The circuit breaker stops sending requests
during the cooldown period, giving the downstream service breathing room. Less traffic
at the right moment is what allows it to stabilise.

Both effects happen at the same time. The circuit breaker is a mechanism for managing
the failure boundary between two services — it benefits the caller and the callee.

The circuit breaker switches between three states:

- **Closed** — normal operation, calls pass through.
- **Open** — failure threshold exceeded; calls are rejected immediately without contacting
  the downstream service.
- **Half-open** — after a cooldown period, one probe call is allowed through. Success
  closes the circuit; failure reopens it and resets the cooldown.

```
state        = CLOSED
failureCount = 0
threshold    = 5
cooldownUntil = null

function callDownstream(message):
    if state == OPEN:
        if now() < cooldownUntil:
            throw CircuitOpenException("circuit open — downstream unavailable")
        else:
            state = HALF_OPEN          // cooldown elapsed — allow one probe

    try:
        result = downstreamService.call(message)
        failureCount = 0
        state = CLOSED                 // probe succeeded — resume normal operation
        return result

    catch Exception as e:
        failureCount++
        if failureCount >= threshold:
            state = OPEN
            cooldownUntil = now() + 30s
            log.warn("circuit opened — downstream failures: " + failureCount)
        throw e
```

**Message lock timeout risk with Azure Service Bus**

When a message is received under PEEK_LOCK it holds a lock for a configured duration
(e.g. 60 seconds). If processing exceeds that duration the broker considers the lock
expired, makes the message available again, and another consumer picks it up —
incrementing `deliveryCount` and risking duplicate processing.

The circuit breaker introduces two specific risks:

1. **While the circuit is accumulating failures** — each failing call waits for the
   downstream HTTP timeout before the exception is thrown. If the HTTP timeout is 30
   seconds and the circuit opens after 5 failures, the fifth failure alone may have held
   the lock for 30 seconds. Add earlier steps and the total can exceed `LockDuration`.

2. **When the circuit is open** — the circuit breaker throws immediately, which is safe.
   The message is abandoned and redelivered by the broker after the lock expires. No lock
   is held during the cooldown. This is the correct behaviour — **never sleep inside a
   processor while holding a lock**.

```
// WRONG — sleeping inside the processor while the message lock is held
function callDownstream(message):
    if state == OPEN:
        Thread.sleep(cooldownRemaining)   // lock expires during sleep — duplicate delivery
        ...

// CORRECT — throw immediately, let the broker redeliver the message later
function callDownstream(message):
    if state == OPEN:
        throw CircuitOpenException("circuit open — downstream unavailable")
        // lock is released when the message is abandoned; broker redelivers after lock expiry
```

The practical rule: **the downstream HTTP timeout must be well within `LockDuration`**.
If `LockDuration` is 60 seconds and each step takes a few seconds, the HTTP timeout
should be set to 10–20 seconds at most, leaving enough headroom for the rest of the
route. If the timeout cannot be kept short, consider renewing the lock before the call,
though that adds complexity.

**In this project:** Step 4 makes a downstream call. A circuit breaker on that step would
stop hammering the downstream service after five consecutive failures and allow it to
recover, while also protecting the consumer from blocking threads on every attempt.

---

## Which pattern to apply

| Situation                                            | Pattern              |
|------------------------------------------------------|----------------------|
| Message loss is unacceptable                         | DLQ                  |
| Downstream service is temporarily unavailable        | Retry + DLQ          |
| Downstream keeps failing repeatedly                  | Circuit Breaker      |
| Message may be delivered more than once              | Idempotent Consumer  |
| High-volume, low-value data where loss is acceptable | Fire-and-Forget      |
| Tracing a failure across services or log lines       | Correlation ID       |
| Processing pipeline with multiple steps              | Single Responsibility |
| System is being built incrementally                  | DLQ as backlog       |

These patterns are not mutually exclusive. A production pipeline typically combines
several: Correlation ID runs everywhere, Idempotent Consumer protects any step with a
side effect, Circuit Breaker guards downstream calls, and DLQ catches whatever slips
through.


---

← [Back to lessons](../README.md)

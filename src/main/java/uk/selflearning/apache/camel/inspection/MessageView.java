package uk.selflearning.apache.camel.inspection;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.ServiceBusMessageState;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

public class MessageView {

    // ── Sender-controlled ─────────────────────────────────────────────────────
    // These fields are set by the producing application before calling sender.sendMessage().
    // Raw SDK: call the corresponding setter on ServiceBusMessage before sending.

    // Unique message identifier used for duplicate detection.
    // Auto-generated as a UUID by the SDK if the sender does not call msg.setMessageId().
    public String messageId;

    // Application-defined token for correlating related messages (e.g. request/reply).
    // Raw SDK: msg.setCorrelationId("req-123")
    public String correlationId;

    // Short label describing the message purpose, equivalent to an email subject.
    // Raw SDK: msg.setSubject("OrderPlaced")
    public String subject;

    // MIME type of the body (e.g. application/json, text/plain).
    // Raw SDK: msg.setContentType("application/json")
    public String contentType;

    // Destination address hint used in broker-side routing topologies.
    // Raw SDK: msg.setTo("queue://target")
    public String to;

    // Address where the receiver should send a reply. Used in request/reply patterns.
    // Raw SDK: msg.setReplyTo("queue://replies")
    public String replyTo;

    // Session ID the reply should target. Pairs with replyTo for session-enabled reply queues.
    // Raw SDK: msg.setReplyToSessionId("session-abc")
    public String replyToSessionId;

    // Groups related messages into one ordered session on a session-enabled queue.
    // Consumers lock an entire session and process its messages in sequence.
    // Raw SDK: msg.setSessionId("order-42")
    public String sessionId;

    // Routes the message to a specific partition, guaranteeing ordering for the same key.
    // Raw SDK: msg.setPartitionKey("customer-99")
    public String partitionKey;

    // How long the broker keeps the message before expiring and dead-lettering it.
    // Two sources: (1) sender sets it per-message — msg.setTimeToLive(Duration.ofHours(1));
    //              (2) queue DefaultMessageTimeToLive config if the sender does not set it.
    // PT0S here is an emulator quirk — real Azure shows the queue-configured TTL (e.g. P14D).
    public Duration timeToLive;

    // Future timestamp at which the message becomes visible. Null means enqueue immediately.
    // Raw SDK: msg.setScheduledEnqueueTime(OffsetDateTime.now().plusMinutes(30))
    public OffsetDateTime scheduledEnqueueTime;

    // The message payload.
    // Raw SDK: new ServiceBusMessage("text") or new ServiceBusMessage(BinaryData.fromBytes(bytes))
    public String body;

    // Arbitrary key-value pairs for application-level metadata (e.g. stage=processed).
    // The broker stores and forwards these without interpreting them.
    // Raw SDK: msg.getApplicationProperties().put("stage", "initial")
    public Map<String, Object> applicationProperties;

    // ── Broker-stamped ────────────────────────────────────────────────────────
    // Set by the Azure Service Bus broker when the message is enqueued. Not settable by the sender.

    // When the broker accepted and made the message available in the queue.
    public OffsetDateTime enqueuedTime;

    // Monotonically increasing number per queue assigned by the broker.
    // Used to retrieve a deferred message by its exact position.
    public long sequenceNumber;

    // Sequence number in the original entity before the message was forwarded or moved.
    // 0 if the message was never transferred to another entity.
    public long enqueuedSequenceNumber;

    // When the message expires and is auto-dead-lettered: enqueuedTime + timeToLive.
    // Control it via timeToLive on the message or the queue's DefaultMessageTimeToLive.
    public OffsetDateTime expiresAt;

    // How many times the broker has delivered this message.
    // Starts at 0; incremented each time a PEEK_LOCK delivery is abandoned or its lock expires.
    // At MaxDeliveryCount (default 10) the broker moves the message to the DLQ.
    public long deliveryCount;

    // ACTIVE: ready for delivery.
    // DEFERRED: set aside by a consumer via receiver.defer(); retrieve by sequenceNumber.
    // SCHEDULED: waiting for scheduledEnqueueTime before becoming ACTIVE.
    public ServiceBusMessageState state;

    // ── Peek-lock only ────────────────────────────────────────────────────────
    // Populated only under PEEK_LOCK receive mode. Null when obtained via peekMessages().

    // Opaque token for the current delivery lock.
    // Pass to receiver.complete/abandon/deadLetter to settle the message.
    public String lockToken;

    // When the current lock expires. Extend with receiver.renewMessageLock().
    public OffsetDateTime lockedUntil;

    // ── Dead Letter Queue ─────────────────────────────────────────────────────
    // Populated only when the message is in the Dead Letter Queue. Null for normal messages.

    // Short reason code: MaxDeliveryCountExceeded, TTLExpiredException (broker),
    // or a custom value set via receiver.deadLetter(msg, new DeadLetterOptions().setDeadLetterReason("..."))
    public String deadLetterReason;

    // Human-readable explanation accompanying deadLetterReason.
    // Set via DeadLetterOptions.setDeadLetterErrorDescription("...").
    public String deadLetterErrorDescription;

    // Name of the queue or subscription from which the message was originally dead-lettered.
    // Useful when multiple entities share one DLQ.
    public String deadLetterSource;

    // ── Factory ───────────────────────────────────────────────────────────────

    public static MessageView from(ServiceBusReceivedMessage msg) {
        MessageView v = new MessageView();
        populate(v, msg);
        return v;
    }

    protected static void populate(MessageView v, ServiceBusReceivedMessage msg) {
        v.messageId = msg.getMessageId();
        v.correlationId = msg.getCorrelationId();
        v.subject = msg.getSubject();
        v.contentType = msg.getContentType();
        v.to = msg.getTo();
        v.replyTo = msg.getReplyTo();
        v.replyToSessionId = msg.getReplyToSessionId();
        v.sessionId = msg.getSessionId();
        v.partitionKey = msg.getPartitionKey();
        v.timeToLive = msg.getTimeToLive();
        v.scheduledEnqueueTime = msg.getScheduledEnqueueTime();
        v.body = msg.getBody() != null ? msg.getBody().toString() : null;
        v.applicationProperties = msg.getApplicationProperties();
        v.enqueuedTime = msg.getEnqueuedTime();
        v.sequenceNumber = msg.getSequenceNumber();
        v.enqueuedSequenceNumber = msg.getEnqueuedSequenceNumber();
        v.expiresAt = msg.getExpiresAt();
        v.deliveryCount = msg.getDeliveryCount();
        v.state = msg.getState();
        v.lockToken = msg.getLockToken();
        v.lockedUntil = msg.getLockedUntil();
        v.deadLetterReason = msg.getDeadLetterReason();
        v.deadLetterErrorDescription = msg.getDeadLetterErrorDescription();
        v.deadLetterSource = msg.getDeadLetterSource();
    }
}

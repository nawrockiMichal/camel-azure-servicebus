package uk.selflearning.apache.camel.inspection;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import org.apache.camel.component.azure.servicebus.ServiceBusConstants;

import java.util.Map;

// Extends MessageView with the Camel exchange header constant for each field.
// These header names are how you read or set each field inside a Camel processor.
//
// Header access modes (shown in the *CamelHeader value itself):
//   R   = set by the Camel consumer on the inbound exchange; producer does NOT write it back.
//         Read in a processor: exchange.getMessage().getHeader("CamelAzureServiceBus...")
//   R/W = consumer sets it AND producer reads it back when building the outgoing SDK message.
//         Setting it in a processor affects the value on the wire.
//   CFG = not a per-message header; configured globally in application.properties.
//
// All CamelAzureServiceBus* headers are internal to Camel — they never travel over the wire.
// Azure Service Bus has no knowledge of them.
public class CamelMessageView extends MessageView {

    // ── Sender-controlled ─────────────────────────────────────────────────────

    // R — consumer sets this header from msg.getMessageId().
    // Producer does NOT read it back; setting it in a processor has no effect on the outgoing messageId.
    public String messageIdCamelHeader;

    // R/W — consumer sets it; producer reads it and calls msg.setCorrelationId() on the outgoing message.
    // Set in a processor: exchange.getMessage().setHeader(ServiceBusConstants.CORRELATION_ID, "req-123")
    public String correlationIdCamelHeader;

    // R — consumer sets this header. Producer does NOT write it back.
    // Use applicationProperties to carry a label through a Camel route instead.
    public String subjectCamelHeader;

    // R — consumer sets this header. Producer does NOT write it back directly.
    // ServiceBusUtils promotes the value to the AMQP content-type field only if
    // "Content-Type" appears as a key in applicationProperties.
    public String contentTypeCamelHeader;

    // R — consumer sets this header. Producer does NOT write it back.
    public String toCamelHeader;

    // R — consumer sets this header. Producer does NOT write it back.
    public String replyToCamelHeader;

    // R — consumer sets this header. Producer does NOT write it back.
    public String replyToSessionIdCamelHeader;

    // CFG — sessionId is not set per-message via an exchange header.
    // Configure globally: camel.component.azure-servicebus.session-id=my-session
    // Consumer still exposes the received sessionId via CamelAzureServiceBusSessionId.
    public String sessionIdCamelConfig;

    // R — consumer sets this header. Producer does NOT write it back.
    public String partitionKeyCamelHeader;

    // R — consumer sets this header. Producer does NOT write it back.
    // To control TTL via Camel use a raw ServiceBusSenderClient in a custom processor.
    public String timeToLiveCamelHeader;

    // R — consumer sets this header.
    // For scheduled sending use the scheduleMessages producer operation instead.
    public String scheduledEnqueueTimeCamelHeader;

    // No header constant — the Camel exchange body is used directly as the message payload.
    // Set in a processor: exchange.getMessage().setBody("payload")

    // R/W — consumer sets the full Map; producer reads it and passes it to the SDK.
    // Two ways to set in a processor:
    //   (1) exchange.getMessage().setHeader(ServiceBusConstants.APPLICATION_PROPERTIES, myMap)
    //   (2) exchange.getMessage().setHeader("myKey", "myValue") — any non-Camel-prefixed
    //       String header is merged into applicationProperties by propagateHeaders() on send.
    public String applicationPropertiesCamelHeader;

    // The applicationProperties keys that Camel also spreads as individual exchange headers.
    // The consumer calls headers.putAll(applicationProperties) so each key is directly readable:
    //   exchange.getMessage().getHeader("stage") == "processed"
    // IMPORTANT: propagateHeaders() on the producer side reads these back and merges them into
    // applicationProperties, so a direct header overwrites the same key in the map.
    // This is why Step2Processor sets both the map AND the direct "stage" header.
    public Map<String, Object> spreadAsIndividualHeaders;

    // ── Broker-stamped ────────────────────────────────────────────────────────
    // R — broker-set, consumer exposes each as a header. All read-only.

    public String enqueuedTimeCamelHeader;
    public String sequenceNumberCamelHeader;
    public String enqueuedSequenceNumberCamelHeader;
    public String expiresAtCamelHeader;

    // R — useful in error handlers: if deliveryCount == maxDeliveryCount the next
    // failure will dead-letter the message automatically.
    public String deliveryCountCamelHeader;

    // state has no dedicated Camel header constant

    // ── Peek-lock only ────────────────────────────────────────────────────────

    // R — set by consumer in PEEK_LOCK mode.
    // The Camel consumer uses this internally to settle (complete/abandon) after processing.
    // Null when obtained via peekMessages().
    public String lockTokenCamelHeader;

    // R — set by consumer in PEEK_LOCK mode. Null via peekMessages().
    public String lockedUntilCamelHeader;

    // ── Dead Letter Queue ─────────────────────────────────────────────────────
    // R — set by consumer when reading from a DLQ. Read-only. Null outside the DLQ.

    public String deadLetterReasonCamelHeader;
    public String deadLetterErrorDescriptionCamelHeader;
    public String deadLetterSourceCamelHeader;

    // ── Camel-only (no Azure Service Bus equivalent) ──────────────────────────

    // Controls what the Camel producer does with the message.
    // Values: sendMessages (default, immediate) or scheduleMessages (delayed, uses scheduledEnqueueTime).
    // Set in a processor: exchange.getMessage().setHeader(ServiceBusConstants.PRODUCER_OPERATION,
    //                         ServiceBusProducerOperationDefinition.scheduleMessages)
    // Null on a received message — only meaningful on the sending side.
    public String producerOperation;
    public String producerOperationCamelHeader;

    // Carries a ServiceBusTransactionContext so multiple send/complete operations are atomic.
    // Set in a processor: exchange.getMessage().setHeader(ServiceBusConstants.SERVICE_BUS_TRANSACTION_CONTEXT, txContext)
    // Null here — no transaction was active when this message was received.
    public String transactionContextCamelHeader;

    // ── Factory ───────────────────────────────────────────────────────────────

    public static CamelMessageView from(ServiceBusReceivedMessage msg) {
        CamelMessageView v = new CamelMessageView();
        MessageView.populate(v, msg);

        // R/W — Camel producer reads these back and writes them to the outgoing message.
        // Non-null signals: setting this header in a processor affects what is sent.
        v.correlationIdCamelHeader         = ServiceBusConstants.CORRELATION_ID;
        v.applicationPropertiesCamelHeader = ServiceBusConstants.APPLICATION_PROPERTIES;
        v.spreadAsIndividualHeaders        = msg.getApplicationProperties();

        // Camel-only — no Azure SB equivalent; controls producer behaviour.
        v.producerOperationCamelHeader  = ServiceBusConstants.PRODUCER_OPERATION;
        v.transactionContextCamelHeader = ServiceBusConstants.SERVICE_BUS_TRANSACTION_CONTEXT;

        // All R-only *CamelHeader fields are left null.
        // Null signals: the Camel producer does not write this field back on send;
        // setting it in a processor has no effect on the outgoing message.

        return v;
    }
}

package uk.selflearning.apache.camel.component;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.messaging.servicebus.models.SubQueue;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component test for the Service Bus processing route.
 *
 * Prerequisites: start the Azure Service Bus Emulator before running tests:
 *   docker compose up -d
 *
 * The test uses step delays of 1s (configured in application-test.properties)
 * so the full pipeline completes in ~3s instead of 45s.
 *
 * Assertion strategy: peekMessages() is used so processed messages remain
 * in the queue after the test and are visible via the inspection endpoint.
 */
@SpringBootTest
@CamelSpringBootTest
@ActiveProfiles("test")
class ServiceBusComponentTest {

    private static final String INPUT_QUEUE = "input";
    private static final String OUTPUT_QUEUE = "output";
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(30);
    // 3 delivery attempts × ~3s each (3 delayed steps at 1s) + broker overhead
    private static final Duration DLQ_TIMEOUT = Duration.ofSeconds(60);

    @Value("${servicebus.connection-string}")
    private String connectionString;

    private ServiceBusSenderClient inputSender;
    private ServiceBusReceiverClient outputReceiver;
    private ServiceBusReceiverClient dlqReceiver;

    @BeforeEach
    void setUp() {
        inputSender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(INPUT_QUEUE)
                .buildClient();

        // drain output queue so peekMessages() only sees messages produced by this test
        try (ServiceBusReceiverClient drain = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .receiver()
                .queueName(OUTPUT_QUEUE)
                .receiveMode(ServiceBusReceiveMode.RECEIVE_AND_DELETE)
                .buildClient()) {
            drain.receiveMessages(100, Duration.ofSeconds(2)).forEach(msg -> {});
        }

        // drain input DLQ so peekMessages() only sees messages produced by this test
        try (ServiceBusReceiverClient dlqDrain = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .receiver()
                .queueName(INPUT_QUEUE)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                .receiveMode(ServiceBusReceiveMode.RECEIVE_AND_DELETE)
                .buildClient()) {
            dlqDrain.receiveMessages(100, Duration.ofSeconds(2)).forEach(msg -> {});
        }

        // peek receivers — non-destructive, messages stay in the queue after assertion
        outputReceiver = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .receiver()
                .queueName(OUTPUT_QUEUE)
                .buildClient();

        dlqReceiver = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .receiver()
                .queueName(INPUT_QUEUE)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                .buildClient();
    }

    @AfterEach
    void tearDown() {
        if (inputSender != null) inputSender.close();
        if (outputReceiver != null) outputReceiver.close();
        if (dlqReceiver != null) dlqReceiver.close();
    }

    @Test
    void shouldProcessMessageAndSetStageToProcessed() {
        ServiceBusMessage inputMessage = new ServiceBusMessage("Hello from component test");
        inputMessage.setSubject("OrderReceived");
        inputMessage.setContentType("text/plain");
        inputSender.sendMessage(inputMessage);

        ServiceBusReceivedMessage outputMessage = Awaitility.await()
                .atMost(RECEIVE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .until(
                        () -> StreamSupport.stream(outputReceiver.peekMessages(1).spliterator(), false)
                                .findFirst()
                                .orElse(null),
                        msg -> msg != null
                );

        assertThat(outputMessage.getBody().toString()).isEqualTo("Hello from component test");
        assertThat(outputMessage.getApplicationProperties()).containsEntry("stage", "processed");
    }

    @Test
    void shouldPreserveMessageBodyThroughAllSteps() {
        String messageBody = "Persistent message body";
        inputSender.sendMessage(new ServiceBusMessage(messageBody));

        ServiceBusReceivedMessage outputMessage = Awaitility.await()
                .atMost(RECEIVE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .until(
                        () -> StreamSupport.stream(outputReceiver.peekMessages(1).spliterator(), false)
                                .findFirst()
                                .orElse(null),
                        msg -> msg != null
                );

        assertThat(outputMessage.getBody().toString()).isEqualTo(messageBody);
        assertThat(outputMessage.getApplicationProperties()).containsEntry("stage", "processed");
    }

    @Test
    void shouldDeadLetterMessageAfterExhaustedRetries() {
        // Step3 calls requireNonNull on 'source' when triggerNpe=true.
        // No onException handler exists for NullPointerException — Camel abandons the
        // message back to the broker on every delivery attempt. After MaxDeliveryCount (3)
        // failed deliveries the broker moves it to the DLQ automatically.
        ServiceBusMessage inputMessage = new ServiceBusMessage("message-for-dlq");
        inputMessage.getApplicationProperties().put("triggerNpe", true);
        // 'source' deliberately omitted — its absence causes the NullPointerException
        inputSender.sendMessage(inputMessage);

        ServiceBusReceivedMessage deadLettered = Awaitility.await()
                .atMost(DLQ_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .until(
                        () -> StreamSupport.stream(dlqReceiver.peekMessages(1).spliterator(), false)
                                .findFirst()
                                .orElse(null),
                        msg -> msg != null
                );

        assertThat(deadLettered.getBody().toString()).isEqualTo("message-for-dlq");
        assertThat(deadLettered.getDeadLetterReason()).isEqualTo("MaxDeliveryCountExceeded");
        assertThat(deadLettered.getApplicationProperties()).containsEntry("triggerNpe", true);
    }
}

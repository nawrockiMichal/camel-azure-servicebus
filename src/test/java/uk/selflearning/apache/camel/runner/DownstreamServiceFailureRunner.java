package uk.selflearning.apache.camel.runner;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Exception scenario runner — triggers a DownstreamServiceException in Step4Processor.
 *
 * Prerequisites: docker compose up -d && ./mvnw spring-boot:run (main app running)
 *
 * Sets simulateHttpError=true in applicationProperties. Step4 detects this flag and
 * throws DownstreamServiceException simulating an HTTP 500 received from a REST client.
 *
 * The Camel exception handler catches DownstreamServiceException with:
 *   handled(false) — exchange is marked as failed, Camel calls abandon() immediately
 *
 * The broker requeues the message and increments deliveryCount on each redelivery.
 * After MaxDeliveryCount exhausted attempts the broker dead-letters automatically.
 *
 * WHY no Camel-internal retries here: using maximumRedeliveries(N) + redeliveryDelay
 * holds the PEEK_LOCK throughout all retry attempts. Long delays risk lock expiry
 * mid-retry, and a crash leaves the message invisible for the full LockDuration.
 * Letting the broker handle redelivery is the safer pattern.
 *
 * If a delay between redeliveries is needed, one option is to catch the exception in
 * the processor, sleep, and rethrow — but the total sleep time plus route processing
 * must stay well within LockDuration, which makes this fragile and hard to reason about.
 *
 * Observe the outcome after the first failure:
 *   curl http://localhost:8081/queues/input/messages   (message back, deliveryCount incremented)
 *
 * After MaxDeliveryCount exhausted attempts:
 *   curl http://localhost:8081/queues/input/deadletter
 *
 * Run with: ./mvnw test -Dtest=DownstreamServiceFailureRunner -DfailIfNoTests=false
 */
class DownstreamServiceFailureRunner {

    private static final String CONNECTION_STRING =
            "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;" +
            "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";

    @Test
    void run() {
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(CONNECTION_STRING)
                .sender()
                .queueName("input")
                .buildClient()) {

            ServiceBusMessage msg = new ServiceBusMessage("Hello from DownstreamServiceFailureRunner");
            msg.getApplicationProperties().putAll(Map.of(
                    "simulateHttpError", true
            ));
            sender.sendMessage(msg);
            System.out.println("Message sent to input queue.");
        }
    }
}

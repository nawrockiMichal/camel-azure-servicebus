package uk.selflearning.apache.camel.runner;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Exception scenario runner — triggers a NullPointerException in Step3Processor.
 *
 * Prerequisites: docker compose up -d && ./mvnw spring-boot:run (main app running)
 *
 * Sets triggerNpe=true in applicationProperties but deliberately omits the 'source'
 * field that Step3 requires. Step3 calls Objects.requireNonNull on 'source', which
 * throws NullPointerException because the sender did not provide it.
 *
 * There is no Camel exception handler for NullPointerException. Camel's default
 * error handler catches it, abandons the message back to the broker, and the broker
 * increments deliveryCount. After MaxDeliveryCount exhausted attempts the broker
 * dead-letters the message with deadLetterReason="MaxDeliveryCountExceeded".
 *
 * Observe the outcome:
 *   curl http://localhost:8081/queues/input/deadletter
 *
 * Run with: ./mvnw test -Dtest=MissingRequiredFieldRunner -DfailIfNoTests=false
 */
class MissingRequiredFieldRunner {

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

            ServiceBusMessage msg = new ServiceBusMessage("Hello from MissingRequiredFieldRunner");
            msg.getApplicationProperties().putAll(Map.of(
                    "triggerNpe", true
                    // 'source' intentionally omitted — Step3 requires it
            ));
            sender.sendMessage(msg);
            System.out.println("Message sent to input queue.");
        }
    }
}

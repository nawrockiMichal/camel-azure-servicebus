package uk.selflearning.apache.camel.runner;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Exception scenario runner — triggers a MessageFormattingException in Step5Processor.
 *
 * Prerequisites: docker compose up -d && ./mvnw spring-boot:run (main app running)
 *
 * Sets simulateFormattingError=true in applicationProperties. Step5 detects this flag
 * and throws MessageFormattingException simulating a failure to format the output message.
 *
 * The Camel exception handler catches MessageFormattingException with handled(true).
 * Camel marks the exchange as successfully completed and calls complete() on the broker,
 * deleting the message from the input queue. Because the exception interrupted the route
 * before reaching .to("azure-servicebus:output"), nothing is forwarded to the output queue.
 *
 * The message is silently consumed — it disappears from the input queue and never appears
 * on the output queue. This is the lost message scenario.
 *
 * Observe the outcome:
 *   curl http://localhost:8081/queues/input/messages    (empty — message consumed)
 *   curl http://localhost:8081/queues/output/messages   (message absent — never forwarded)
 *
 * Run with: ./mvnw test -Dtest=MessageFormattingFailureRunner -DfailIfNoTests=false
 */
class MessageFormattingFailureRunner {

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

            ServiceBusMessage msg = new ServiceBusMessage("Hello from MessageFormattingFailureRunner");
            msg.getApplicationProperties().putAll(Map.of(
                    "simulateFormattingError", true
            ));
            sender.sendMessage(msg);
            System.out.println("Message sent to input queue.");
        }
    }
}

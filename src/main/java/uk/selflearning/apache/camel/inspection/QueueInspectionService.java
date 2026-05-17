package uk.selflearning.apache.camel.inspection;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.models.SubQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.StreamSupport;

@Service
public class QueueInspectionService {

    @Value("${servicebus.connection-string}")
    private String connectionString;

    public List<MessageView> peekMessages(String queueName, int count, boolean camel) {
        try (ServiceBusReceiverClient receiver = buildReceiver(queueName, false)) {
            return toViews(receiver, count, camel);
        }
    }

    public List<MessageView> peekDeadLetterMessages(String queueName, int count, boolean camel) {
        try (ServiceBusReceiverClient receiver = buildReceiver(queueName, true)) {
            return toViews(receiver, count, camel);
        }
    }

    private ServiceBusReceiverClient buildReceiver(String queueName, boolean deadLetter) {
        ServiceBusClientBuilder.ServiceBusReceiverClientBuilder builder = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .receiver()
                .queueName(queueName);

        if (deadLetter) {
            builder.subQueue(SubQueue.DEAD_LETTER_QUEUE);
        }

        return builder.buildClient();
    }

    private List<MessageView> toViews(ServiceBusReceiverClient receiver, int count, boolean camel) {
        return StreamSupport.stream(receiver.peekMessages(count).spliterator(), false)
                .map(msg -> camel ? CamelMessageView.from(msg) : MessageView.from(msg))
                .toList();
    }
}

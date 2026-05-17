package uk.selflearning.apache.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.azure.servicebus.ServiceBusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
public class Step3Processor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(Step3Processor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        String body = exchange.getMessage().getBody(String.class);
        Map<String, Object> appProperties = exchange.getMessage()
                .getHeader(ServiceBusConstants.APPLICATION_PROPERTIES, Map.class);

        if (Boolean.TRUE.equals(appProperties != null ? appProperties.get("triggerNpe") : null)) {
            // 'source' is expected to be set by the sender — throws NullPointerException if missing
            String source = appProperties != null ? (String) appProperties.get("source") : null;
            Objects.requireNonNull(source, "'source' application property is required but was not set by the sender");
        }

        log.info("Step 3 — forwarding message to output queue. Body: {}, Properties: {}", body, appProperties);
    }
}
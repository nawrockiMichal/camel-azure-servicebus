package uk.selflearning.apache.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.azure.servicebus.ServiceBusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class Step2Processor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(Step2Processor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        String body = exchange.getMessage().getBody(String.class);
        log.info("Step 2 — message body: {}", body);

        Map<String, Object> existing = exchange.getMessage()
                .getHeader(ServiceBusConstants.APPLICATION_PROPERTIES, Map.class);
        Map<String, Object> appProperties = existing != null ? new HashMap<>(existing) : new HashMap<>();
        appProperties.put("stage", "processed");

        exchange.getMessage().setHeader(ServiceBusConstants.APPLICATION_PROPERTIES, appProperties);
        exchange.getMessage().setHeader("stage", "processed");
        log.info("Step 2 — set stage=processed on message application properties");
    }
}
package uk.selflearning.apache.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.azure.servicebus.ServiceBusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.selflearning.apache.camel.exception.MessageFormattingException;

import java.util.Map;

@Component
public class Step5Processor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(Step5Processor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> appProperties = exchange.getMessage()
                .getHeader(ServiceBusConstants.APPLICATION_PROPERTIES, Map.class);

        if (Boolean.TRUE.equals(appProperties != null ? appProperties.get("simulateFormattingError") : null)) {
            log.warn("Step 5 — simulating message formatting failure");
            throw new MessageFormattingException("Failed to format output message body");
        }

        log.info("Step 5 — message formatted successfully");
    }
}

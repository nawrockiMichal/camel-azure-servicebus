package uk.selflearning.apache.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.azure.servicebus.ServiceBusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.selflearning.apache.camel.exception.DownstreamServiceException;

import java.util.Map;

@Component
public class Step4Processor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(Step4Processor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> appProperties = exchange.getMessage()
                .getHeader(ServiceBusConstants.APPLICATION_PROPERTIES, Map.class);

        if (Boolean.TRUE.equals(appProperties != null ? appProperties.get("simulateHttpError") : null)) {
            log.warn("Step 4 — simulating HTTP 500 from downstream REST client");
            throw new DownstreamServiceException("HTTP 500: Service Unavailable");
        }

        log.info("Step 4 — downstream call succeeded");
    }
}

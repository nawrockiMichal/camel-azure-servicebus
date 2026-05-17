package uk.selflearning.apache.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class Step1Processor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(Step1Processor.class);

    @Override
    public void process(Exchange exchange) {
        String body = exchange.getMessage().getBody(String.class);
        log.info("Step 1 — received message from input queue: {}", body);
    }
}
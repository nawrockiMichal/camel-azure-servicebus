package uk.selflearning.apache.camel.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class Step6Processor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(Step6Processor.class);

    @Override
    public void process(Exchange exchange) {
        String body = exchange.getMessage().getBody(String.class);
        List<String> items = Arrays.asList(body.split(","));
        exchange.getMessage().setBody(items);
        exchange.setProperty("itemCount", items.size());
        log.info("Step 6 — split body into {} item(s): {}", items.size(), items);
    }
}

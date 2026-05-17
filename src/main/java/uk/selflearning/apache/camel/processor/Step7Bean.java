package uk.selflearning.apache.camel.processor;

import org.apache.camel.Body;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;
import org.apache.camel.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// Spring bean used as a Camel processing step via .bean() DSL.
// Demonstrates all Camel bean binding options — see docs/camel-code-patterns.md.
@Component
public class Step7Bean {

    private static final Logger log = LoggerFactory.getLogger(Step7Bean.class);

    public String summarise(
            @Body List<String> items,
            @Header("stage") String stage,
            @Headers Map<String, Object> allHeaders,
            @ExchangeProperty("itemCount") int itemCount) {

        log.info("Step 7 — stage header: {}", stage);
        log.info("Step 7 — all headers: {}", allHeaders);
        log.info("Step 7 — itemCount property (set by Step 6, not forwarded): {}", itemCount);

        String result = String.join(" | ", items);
        log.info("Step 7 — summarised {} item(s): {}", itemCount, result);
        return result;
    }
}

package uk.selflearning.apache.camel.route;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.selflearning.apache.camel.exception.DownstreamServiceException;
import uk.selflearning.apache.camel.exception.MessageFormattingException;
import uk.selflearning.apache.camel.processor.Step1Processor;
import uk.selflearning.apache.camel.processor.Step2Processor;
import uk.selflearning.apache.camel.processor.Step3Processor;
import uk.selflearning.apache.camel.processor.Step4Processor;
import uk.selflearning.apache.camel.processor.Step5Processor;
import uk.selflearning.apache.camel.processor.Step6Processor;
import uk.selflearning.apache.camel.processor.Step7Bean;

@Component
public class ServiceBusRoute extends RouteBuilder {

    @Value("${service-bus-route.step-delay-ms:15000}")
    private long stepDelayMs;

    // When true, Camel calls deadLetter() instead of abandon() on any failure.
    // The message goes directly to the DLQ on the first failure with the exception
    // as deadLetterReason — broker retries are skipped entirely.
    // WARNING: this setting affects ALL exception handlers, not just DownstreamServiceException.
    // See docs/exception-handling.md (Scenario 3) for full explanation.
    @Value("${service-bus-route.enable-dead-lettering:false}")
    private boolean enableDeadLettering;

    @Autowired private Step1Processor step1Processor;
    @Autowired private Step2Processor step2Processor;
    @Autowired private Step3Processor step3Processor;
    @Autowired private Step4Processor step4Processor;
    @Autowired private Step5Processor step5Processor;
    @Autowired private Step6Processor step6Processor;
    @Autowired private Step7Bean step7Bean;

    @Override
    public void configure() {

        // Transient downstream failure — abandon immediately to the broker.
        // The broker requeues the message for redelivery; deliveryCount increments each time.
        // After MaxDeliveryCount exhausted attempts the broker dead-letters automatically.
        // NOTE: if enableDeadLettering=true, this exception dead-letters on the first failure
        // instead of being requeued — see Scenario 3 in the exception-handling lesson.
        onException(DownstreamServiceException.class)
                .handled(false)
                .log("Step 4 — downstream service unavailable");

        // Formatting failure — mark the exchange as handled so Camel calls complete() and
        // the message is removed from the input queue, but the route never reaches .to(output).
        // The message is consumed and not forwarded — lost message scenario.
        // NOTE: handled(true) calls complete() regardless of enableDeadLettering.
        onException(MessageFormattingException.class)
                .handled(true)
                .log("Step 5 — message formatting failed, message consumed but not forwarded to output queue");

        from("azure-servicebus:input?enableDeadLettering=" + enableDeadLettering)
                .routeId("service-bus-processing-route")
                .delay(stepDelayMs)
                .process(step1Processor)
                .delay(stepDelayMs)
                .process(step2Processor)
                .delay(stepDelayMs)
                .process(step3Processor)
                .process(step4Processor)
                .process(step5Processor)
                .process(step6Processor)
                .bean(step7Bean, "summarise")
                .to("azure-servicebus:output");
    }
}

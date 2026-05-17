# Camel Code Patterns

As routes grow it is tempting to add logic directly inside them — conditions, data
manipulation, branching. The result is a route that mixes orchestration with detail,
is hard to read at a glance, and cannot be reused. This lesson covers patterns that
keep routes clean, make logic testable, and turn common building blocks into something
that can be shared across projects.

---

## Route and sub-route

A Camel route defined with `from(...)` is a single entry point. The `direct:` component
allows one route to call another synchronously and in-memory — effectively a method call
between routes. The called route is a sub-route.

```java
// Main route — reads as a table of contents
from("azure-servicebus:input")
    .to("direct:validate")
    .to("direct:process")
    .to("azure-servicebus:output");

// Sub-routes — defined in separate RouteBuilder classes
from("direct:validate")
    .process(schemaValidator)
    .process(mandatoryFieldsCheck);

from("direct:process")
    .process(enrichProcessor)
    .process(transformProcessor);
```

`direct:` works only within the **same CamelContext**. Sub-routes defined in their own
`RouteBuilder` classes can be packaged into a shared library — when that library is
included in another project, its `RouteBuilder` beans are picked up by Spring and
registered in the same CamelContext, so the `direct:` endpoints resolve correctly.
A validation sub-route packaged this way works the same whether the entry point is
Azure Service Bus, Kafka, or an HTTP endpoint — the consuming project just calls
`direct:validate`.

The key decision when designing sub-routes is the boundary between reusable and
project-specific. A sub-route that references project-specific configuration or
infrastructure is not truly reusable — it carries hidden assumptions that will break
in a different context.

**Route Templates** are a more powerful alternative when the sub-route itself needs to
vary across instances — different queue names, different endpoints, different timing.
A Route Template is a parameterised blueprint: `route template + parameters → concrete route`.
Where `direct:` connects fixed routes, a Route Template instantiates new routes with
different configurations each time. For most business logic sub-routes `direct:` is
sufficient; Route Templates are for infrastructure-level reuse where configuration varies.

---

## Processor or Bean?

Camel offers two approaches for a processing step: implementing the `Processor` interface
or calling a Spring bean via the `.bean()` DSL. Both receive the message, do something,
and pass it on — but they have very different type contracts.

**Processor** — a single interface method that receives the raw `Exchange`. Everything
inside — body, headers, properties — is accessed through `exchange.getMessage()` which
returns `Object`. There is no compile-time contract for what enters or leaves.

**Bean** — a plain Spring `@Component` with a regular method. Camel binds the message
body and headers to method parameters by type and annotation, and sets the return value
as the new body. The method signature is the contract.

```java
// Processor — no visible contract
public void process(Exchange exchange) throws Exception { ... }

// Bean — contract is in the signature
public String summarise(List<String> items, @Header("stage") String stage) { ... }
```

The route calls them differently:

```java
.process(step6Processor)          // Processor
.bean(step7Bean, "summarise")     // Spring bean — method name prevents ambiguity
```

---

## The processor contract problem

The `Processor` interface has one method:

```java
void process(Exchange exchange) throws Exception;
```

`exchange.getMessage().getBody()` returns `Object`. There is no compile-time contract for
what a processor expects to receive or what it is expected to produce. Two processors
in sequence share only an implicit convention — the first one puts something in the
body, the second reads it. Nothing enforces this, and reading the method signature
communicates nothing.

```java
// You cannot tell what this requires or produces without reading it
public class Step6Processor implements Processor {
    @Override
    public void process(Exchange exchange) throws Exception {
        String body = exchange.getMessage().getBody(String.class); // is it always a String?
        List<String> items = Arrays.asList(body.split(","));
        exchange.getMessage().setBody(items);                      // body type just changed silently
    }
}
```

In a long chain every step adds another implicit convention. The route becomes opaque —
each processor is a black box whose behaviour can only be understood by reading its
implementation.

---

## Bean binding

When using `.bean()`, Camel maps the message to method parameters using annotations.
`Step7Bean` demonstrates all binding options:

```java
public String summarise(
        @Body List<String> items,
        @Header("stage") String stage,
        @Headers Map<String, Object> allHeaders,
        @ExchangeProperty("itemCount") int itemCount) { ... }
```

| Annotation | Parameter type | What Camel provides | If absent |
|---|---|---|---|
| `@Body` | Any type | Message body, type-converted | `NoTypeConversionAvailableException` |
| `@Header("name")` | Any type | Single named header | `null` (or `NullPointerException` for primitives) |
| `@Headers` | `Map<String, Object>` | All message headers | Empty map, never null |
| `@ExchangeProperty("name")` | Any type | Exchange-scoped property | `null` (or `NullPointerException` for primitives) |
| `Exchange` (no annotation) | `Exchange` | Full exchange object | N/A — always present |

The **return value** becomes the new message body. Returning `void` leaves the body
unchanged.

**Headers vs exchange properties** — headers are part of the message and are forwarded
to downstream services (Azure Service Bus application properties, HTTP headers). Exchange
properties are internal Camel metadata, scoped to the current route execution only, and
never forwarded. `Step6Processor` sets `itemCount` as an exchange property — it arrives
in `Step7Bean` but will not appear on the output queue message.

**Binding exceptions** — if the body cannot be converted to the declared parameter type,
Camel throws `NoTypeConversionAvailableException`. If `.bean()` is called without a
method name and multiple public methods match, Camel throws `AmbiguousMethodCallException`.
Both propagate as normal route failures and are handled by the active exception handlers.

---

## Processor as a thin adapter

When a step needs direct access to the `Exchange` — reading or writing multiple headers,
inspecting exchange properties, handling the exception object — the `Processor` interface
is still the right tool. Keep business logic out of the processor and in a typed service
class. The processor becomes a thin adapter: extract from the exchange, call the service,
write the result back.

```java
// Typed service — explicit contract, no Camel dependency, easy to unit test
public class MessageEnrichmentService {
    public EnrichedMessage enrich(RawMessage input) { ... }
}

// Processor — thin adapter, no business logic
public class Step3Processor implements Processor {
    private final MessageEnrichmentService service;

    @Override
    public void process(Exchange exchange) throws Exception {
        RawMessage input = exchange.getMessage().getBody(RawMessage.class);
        EnrichedMessage result = service.enrich(input);
        exchange.getMessage().setBody(result);
    }
}
```

The adapter is so simple it barely needs testing. The service is plain Java with no
Camel dependency — easy to unit test, easy to reuse, easy to reason about.

---

## When to use Processor vs Bean

| Situation | Approach |
|---|---|
| Pure body transformation with a clear typed contract | Bean via `.bean()` |
| Need to read or write multiple headers alongside the body | Bean with `@Headers` or `@Header` params |
| Need access to exchange properties | Bean with `@ExchangeProperty` params |
| Need to manipulate the Exchange directly (copy headers, inspect exceptions) | Processor (thin adapter) |
| Complex header manipulation that annotations cannot express cleanly | Processor (thin adapter) |

---

## No logic in the route

A route should read like a table of contents, not like a program. No `if` statements,
no data manipulation, no business decisions — only the sequence of steps and the
routing decisions between them.

```java
// Intent visible at a glance
from("azure-servicebus:input")
    .process(validateProcessor)
    .process(enrichProcessor)
    .process(transformProcessor)
    .to("azure-servicebus:output");
```

When the route contains logic the intent is buried. When the route is pure orchestration
the intent is visible. Any future reader can understand the flow without reading a single
processor implementation.

The discipline also enforces correct placement: logic belongs in processors and services
where it is named, typed, and testable.

---

## Where does testing start?

The thin adapter pattern changes the testing strategy. With business logic inside the
Processor the only way to test it is through a Camel Exchange — awkward mocking,
Camel infrastructure, tests that verify plumbing rather than business rules. With logic
in a typed service the picture is cleaner.

**Service level — unit tests.** Test the service class directly. No Camel, no Exchange,
no infrastructure. Fast and straightforward, clear input and output, easy to cover edge
cases and failure paths.

```java
@Test
void enriches_message_with_source_application() {
    RawMessage input = new RawMessage("payload");
    EnrichedMessage result = service.enrich(input);
    assertThat(result.getSource()).isEqualTo("expected-source");
}
```

**Processor level — minimal or none.** If the processor is a three-line adapter the only
thing to test is that it calls the service and writes the result back. This is rarely
worth a dedicated test — the logic is already covered by the service test, and a wiring
mistake will surface in the route-level test.

**Route level — integration tests.** Test the full route by sending a message and
verifying the outcome. This covers orchestration: correct processor sequence, exception
handling, and message settlement. Use Camel's test support (`CamelTestSupport` or
`@SpringBootTest`) to start a real Camel context.

Real external endpoints (Azure Service Bus, HTTP services) cannot be started in a test.
`AdviceWith` solves this — it lets you modify a route before the test starts, replacing
real endpoints with `mock:` or `direct:` equivalents without changing production code.

```java
@SpringBootTest
class ServiceBusRouteTest extends CamelTestSupport {

    @Test
    void message_reaches_output_after_all_steps() throws Exception {
        AdviceWith.adviceWith(context, "service-bus-processing-route", route -> {
            route.replaceFromWith("direct:test-input");        // replace real consumer
            route.mockEndpointsAndSkip("azure-servicebus:*"); // replace real producer
        });

        MockEndpoint output = getMockEndpoint("mock:azure-servicebus:output");
        output.expectedMessageCount(1);

        template.sendBody("direct:test-input", testPayload);

        output.assertIsSatisfied();
    }
}
```

The testing boundary mirrors the responsibility boundary:

| Layer | Owns | Tested by |
|-------|------|-----------|
| Service | Business logic | Unit tests |
| Processor | Camel adapter | Route integration test |
| Route | Orchestration and error handling | Route integration test |

**In this project:** the processors contain both adapter code and simple logic (checking
headers, throwing exceptions). In a production system these would be split: logic moves
into services, processors become adapters, and unit tests target the service layer.

---

← [Back to lessons](../README.md)

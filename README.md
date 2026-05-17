# Apache Camel on Azure Service Bus — A Developer's Guide

A hands-on learning project built by **Michal Nawrocki** and **Claude** to explore how
reliable message processing works in practice — not just the happy path.

The lessons cover how a queue is configured, how a message travels from arrival to
settlement, what happens when processing fails, and how exception handling, retries, and
the Dead Letter Queue fit together. Architectural patterns such as Circuit Breaker,
Idempotent Consumer, and Correlation ID are explained in the context of real trade-offs
rather than in the abstract. The final lessons move into Apache Camel code design —
how to structure processors and Spring beans, where to put business logic, and where
component testing should begin.

Everything runs locally using the Azure Service Bus Emulator inside Docker, so there is
no Azure subscription required and no cost to experiment.

---

## Lessons

| # | Title |
|---|-------|
| 1 | [Running the App](docs/running-the-app.md) |
| 2 | [Queue Configuration](docs/queue-configuration.md) |
| 3 | [Inspecting Queue Messages](docs/inspecting-queue-messages.md) |
| 4 | [Message Anatomy](docs/message-anatomy.md) |
| 5 | [Message Lifetime](docs/message-lifetime.md) |
| 6 | [Exception Handling](docs/exception-handling.md) |
| 7 | [Message Queue Patterns](docs/message-queue-patterns.md) |
| 8 | [Camel Code Patterns](docs/camel-code-patterns.md) |

---

## Tech stack

- Java 17
- Spring Boot 4.0.6
- Apache Camel 4.20.0 (`camel-azure-servicebus-starter`)
- Azure Service Bus Emulator (Docker, ARM64)

---

## Suggestions and feedback

Questions, corrections, or ideas? Open an issue or email
[michas.n@gmail.com](mailto:michas.n@gmail.com).

---


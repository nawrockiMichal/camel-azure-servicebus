# Running the App

By the end of this lesson you will have the Azure Service Bus emulator running locally,
the component tests passing, and both the main app and the inspection app started.

> All commands are run from the project root directory. You will need Java 17+, the included Maven wrapper, and Docker Desktop.

---

## Step 1 — Start the Azure Service Bus Emulator

The emulator replaces a real Azure Service Bus namespace. It runs two containers:
- **sql-edge** — stores message state in an embedded SQL database
- **servicebus-emulator** — speaks the AMQP protocol on port `5672`

```bash
docker compose up -d
```

Wait until the emulator is ready (takes ~15 s on first run):

```bash
docker compose logs -f servicebus-emulator
```

You are ready when you see:

```
Emulator Service is Successfully Up!
```

Press `Ctrl+C` to stop following the logs. The containers keep running in the background.

### What was configured?

The emulator is configured by `servicebus-emulator-config.json`. It creates one namespace with two queues:

```json
"Queues": [
  { "Name": "input" },
  { "Name": "output" }
]
```

The connection string used by the app is:

```
Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;
```

`UseDevelopmentEmulator=true` tells the SDK to connect to the local emulator instead of Azure.

---

## Step 2 — Run the Component Tests

The component tests spin up the full Spring Boot + Camel application inside the JVM,
send a message to the **input** queue, and assert that a processed message arrives on the **output** queue.

The test profile uses 1-second step delays instead of 15 seconds, so the full pipeline completes in ~3 s.

```bash
./mvnw test
```

Expected output:

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: xx.xx s
[INFO] BUILD SUCCESS
```

---

## Step 3 — Start the Main App

The main app runs the Camel route continuously, consuming from **input** and producing to **output**.

```bash
./mvnw spring-boot:run
```

Wait until you see:

```
Started ApacheCamelPersistingStepsApplication
```

The app listens on port **8080**. Leave this terminal open.

---

## Step 4 — Start the Inspection App

The inspection app is a separate Spring Boot application (no Camel, no queues consumed).
It exposes a REST API that lets you peek at queue contents without disturbing them.

Open a **second terminal** and run:

```bash
./mvnw spring-boot:run -Pinspection
```

Wait until you see:

```
Started InspectionApplication
```

The inspection app listens on port **8081**. Leave this terminal open.

---

## Step 5 — Understanding Component Tests and Runners

The project has two kinds of JUnit classes with very different purposes.

### Component tests

Component tests are part of the Maven build lifecycle (`./mvnw test`). They spin up the full
Spring Boot + Camel application inside the JVM, send a message, and assert the result — end to end.
They verify the complete flow works correctly and leave processed messages in the output queue,
ready to inspect with the inspection app.

### Runners

Runners live in the `runner` package and are **excluded from the Maven build lifecycle**.
They are not tests — they are one-shot helpers that let you inject a specific message into
the running app so you can observe what happens.

Use a runner when you want to send a real message through the live pipeline, watch it
travel through the Camel route, and peek at the result via the inspection app — without
the assertions and Spring Boot bootstrap overhead of a component test.

Run a runner explicitly by class name (the main app must already be running):

```bash
./mvnw test -Dtest=<RunnerClassName> -DfailIfNoTests=false
```

You are now ready for **Inspecting Queue Messages**, where you will use the inspection API to look at that message.

---

## Quick-reference: all commands

```bash
# 1. Start emulator
docker compose up -d

# 2. Run tests
./mvnw test

# 3. Start main app (keep terminal open)
./mvnw spring-boot:run

# 4. Start inspection app in a second terminal (keep terminal open)
./mvnw spring-boot:run -Pinspection

# 5a. Run component tests with both apps running
./mvnw test

# 5b. Or use a runner to inject a single message manually
./mvnw test -Dtest=<RunnerClassName> -DfailIfNoTests=false
```

### Stopping everything

```bash
# Stop the apps — Ctrl+C in each terminal

# Stop and remove the Docker containers
docker compose down
```

---

← [Back to lessons](../README.md)

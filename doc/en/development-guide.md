# OpenAN Observability SDK for Java: Development Guide

[简体中文](../zh/开发指南.md) · [API Reference](api-reference.md)

This guide explains how to integrate the Java SDK, configure a Collector, record the three signal categories, and maintain the signal definitions packaged with the SDK.

## 1. Prerequisites and dependency

- JDK 17 or later;
- Maven;
- a reachable OpenTelemetry Collector;
- for SDK development, run `mvn -B -ntp install` at the repository root before consuming the local artifact. Released versions are consumed from Maven Central.

```xml
<dependency>
  <groupId>net.openan</groupId>
  <artifactId>observability-sdk</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 2. Initialization and lifecycle

Create one `ObservabilitySdk` per process in the usual case. Builder values override environment variables. Flush and close the SDK before a service stops, a command-line program exits, or a test completes.

```java
try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
        .serviceName("diagnosis-agent")
        .endpoint("http://localhost:4317")
        .protocol("grpc")
        .build())) {
    var observe = sdk.observe();
    // Record application signals.
    if (!sdk.flush(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("OTLP export failed or timed out");
    }
}
```

A `false` result from `flush` means that the wait timed out or an export failure was observed since the last call. Handle it in application shutdown, batch jobs, and integration tests.

## 3. Recording the three signal categories

### 3.1 A2A / A2A-T

Create a Client or Server span for an A2A request. A2A-T messages and logs require `extension.name`; its value selects the allowed extension attributes.

| `extension.name` | Example attributes |
|---|---|
| `task-t` | `task.type`, `task.object` |
| `negotiation-t` | `negotiation.id`, `negotiation.round`, `negotiation.result`, `negotiation.performative` |
| `notification-t` | `notification.topic`, `push.notification.url` |
| `authorization-t` | `authorization.stage`, `authorization.policy.operation.type` |

Task-T spans, logs, `task.duration`, and `task.count` all require a non-blank `task.type`.

```java
var task = Map.of("extension.name", "task-t", "task.type", "diagnosis");
try (var span = observe.trace("message/send", SignalKind.CLIENT, task)) {
    observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO,
        "diagnosis completed", task, span);
    observe.metric("task.duration", 42_000, Map.of("task.type", "diagnosis"));
    observe.metric("task.count", 1, Map.of("task.type", "diagnosis"));
    span.ok();
}
```

### 3.2 Workflow

The workflow trace name is `a2at.workflow` and requires `workflow.id`, `workflow.type`, and `workflow.status`. A step trace uses `a2at.step.<step-name>` and requires the step name, index, and status.

```java
var workflow = Map.of(
    "workflow.id", "wf-001",
    "workflow.type", "fault-diagnosis",
    "workflow.status", "running");
try (var workflowSpan = observe.trace("a2at.workflow", SignalKind.INTERNAL, workflow);
     var workflowScope = workflowSpan.makeCurrent()) {
    var step = Map.of(
        "workflow.step", "retrieve-alarms",
        "workflow.step.index", 1,
        "workflow.step.status", "completed");
    try (var stepSpan = observe.trace("a2at.step.retrieve-alarms", SignalKind.INTERNAL, step)) {
        observe.metric("step.duration", 75, Map.of(
            "workflow.step", "retrieve-alarms",
            "gen_ai.agent.name", "diagnosis-agent"));
        stepSpan.ok();
    }
    observe.metric("workflow.duration", 180, Map.of(
        "workflow.type", "fault-diagnosis", "workflow.status", "completed"));
    workflowSpan.ok();
}
```

`workflow.duration` and `workflow.count` require `workflow.type` and `workflow.status`; `step.duration` requires `workflow.step` and `gen_ai.agent.name`.

### 3.3 Agent

An agent substep trace uses `a2at.substep.<substep-name>` and requires the agent name, substep name, and substep status. `agent.skills` is a list of strings.

```java
var substep = Map.of(
    "gen_ai.agent.name", "diagnosis-agent",
    "substep.name", "retrieve-alarms",
    "substep.status", "completed");
try (var span = observe.trace("a2at.substep.retrieve-alarms", SignalKind.INTERNAL, substep)) {
    observe.metric("substep.duration", 50, Map.of(
        "gen_ai.agent.name", "diagnosis-agent", "substep.name", "retrieve-alarms"));
    observe.metric("gen_ai.client.token.usage", 120, Map.of(
        "gen_ai.agent.name", "diagnosis-agent",
        "gen_ai.request.model", "model-1",
        "gen_ai.token.type", "input"));
    observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, "agent completed", Map.of(
        "gen_ai.agent.name", "diagnosis-agent",
        "agent.skills", List.of("alarm-search"),
        "agent.state", "completed"), span);
    span.ok();
}
```

## 4. Cross-process trace context and errors

Use `TraceContextPropagation.inject` at the caller to write the current W3C `traceparent`, and `extractToCurrent` at the receiver before creating its server span.

```java
try (var client = observe.trace("message/send", SignalKind.CLIENT, attributes);
     var scope = client.makeCurrent()) {
    TraceContextPropagation.inject(headers, Map::put);
}

try (var scope = TraceContextPropagation.extractToCurrent(headers, headerGetter);
     var server = observe.trace("message/send", SignalKind.SERVER, attributes)) {
    try {
        handleRequest();
        server.ok();
    } catch (RuntimeException error) {
        server.error(error);
        throw error;
    }
}
```

`error(Throwable)` records the exception and sets span status to error. `error()` updates the status only.

## 5. Collector configuration

`examples/collector-config.yaml` exposes the following OTLP receivers:

| Protocol | Address |
|---|---|
| gRPC | `0.0.0.0:4317` |
| HTTP/Protobuf | `0.0.0.0:4318` |

For a local check:

```powershell
otelcol --config=examples/collector-config.yaml
```

This configuration sends all three signals to the Collector debug exporter. In a deployment, replace the exporter in each pipeline with the required storage or forwarding target. `examples/collector-auth-tls-config.yaml` shows a TLS and Bearer Token receiver.

See the [API Reference](api-reference.md#sdkconfig-and-sdkconfigbuilder) for endpoint, request-header, trusted-CA, and export-timeout settings.

## 6. Signal definitions and validation

Three definitions are packaged in the JAR:

| File | Coverage |
|---|---|
| `src/main/resources/observability/a2at.yaml` | A2A and four A2A-T extensions |
| `src/main/resources/observability/workflow.yaml` | Workflow and step |
| `src/main/resources/observability/agent.yaml` | Agent, substep, and token usage |

Each YAML file declares its `version`, attribute types and OpenTelemetry names, trace/log allowlists and required attributes, and metric types and dimensions. At runtime, the SDK:

1. finds the trace, metric, or log definition by signal name;
2. validates attribute names against that signal's allowlist, then validates required attributes and types;
3. selects the A2A-T message trace/log allowlist from `extension.name`;
4. validates metric dimensions, required dimensions, and numeric type.

Other than `extension.name`, the SDK does not restrict business attribute values to enumerations. Attribute names, types, signal membership, and requiredness remain validated.

### Updating definitions

Change the relevant YAML file and increment its `version`. Update at the same time:

1. unit tests covering the new name, attribute, and rejection cases;
2. a runnable example;
3. the relevant tables or examples in both language versions of these docs;
4. the pending-release section in `CHANGELOG.md`.

YAML is currently packaged inside the JAR. A definition change requires a new SDK build and release; an already running application does not reload it.

## 7. Tests and examples

Run all tests and compile the examples from the repository root:

```powershell
mvn -B -ntp -Pexamples clean verify
```

Run the workflow and agent example:

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'grpc'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4317'
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.WorkflowAgentExample' '-Dexec.args=local' '-Dexec.cleanupDaemonThreads=false'
```

Run the independent Maven consumer check:

```powershell
mvn -B -ntp -DskipTests install
Push-Location consumer-smoke
mvn -B -ntp package exec:java '-Dexec.mainClass=net.openan.observability.consumer.ConsumerSmoke' '-Dexec.args=local' '-Dexec.cleanupDaemonThreads=false'
Pop-Location
```

More A2A, A2A-T extension, and cross-process examples are under `examples/src/main/java/net/openan/observability/example/`.

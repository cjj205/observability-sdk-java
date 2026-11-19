# Changelog

## 0.1.0 — planned

### Included

- A2A/A2A-T, Workflow, and Agent Trace, Metric, and Log APIs.
- Versioned signal definitions in `a2at.yaml`, `workflow.yaml`, and `agent.yaml`.
- OTLP gRPC and HTTP/Protobuf export, request headers, trusted CA certificates, export timeout, and flush failure reporting.
- W3C Trace Context injection and extraction helpers for transport integrations.
- Local examples for A2A/A2A-T and Workflow/Agent signals, plus an independent Maven consumer smoke application.

### Release checks

- CI builds the SDK, runs the test suite, installs the artifact locally, and runs the independent consumer through an OTLP Collector.
- The `Release verification` workflow creates and verifies signed Maven artifacts without calling `deploy`.
- The `Release to Maven Central` workflow remains tag-gated and is the only workflow that calls `deploy`.

### Known limits

- Signal YAML files are bundled in the SDK JAR. Updating a definition requires a new SDK build.
- Client certificate and private-key configuration for mTLS are not available in this version.
- Sampling, retry, and batch queue settings use OpenTelemetry SDK defaults.

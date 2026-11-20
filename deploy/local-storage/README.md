# Local Collector and storage stack

This Compose stack persists the three SDK signal types through an OpenTelemetry Collector:

```text
SDK → Collector
  ├─ Metric → Prometheus OTLP receiver → Thanos Sidecar → Thanos Query
  ├─ Log    → Data Prepper → OpenSearch index a2at-logs-v2
  └─ Trace  → Jaeger v2 → OpenSearch index jaeger-*
```

It is a local validation environment. The Thanos object store uses a filesystem volume; use an S3-compatible object store and configure credentials, TLS, backup, and lifecycle policies for a deployment environment.

## Start

In WSL, change to this directory from the SDK repository root, then run:

```powershell
cd deploy/local-storage
docker compose up -d
```

| Component | Address | Purpose |
|---|---|---|
| Collector | `127.0.0.1:14317` gRPC; `127.0.0.1:14318` HTTP | SDK OTLP endpoint |
| Prometheus | `http://127.0.0.1:19090` | Metric ingestion and PromQL |
| Thanos Query | `http://127.0.0.1:11902` | Metric query endpoint |
| OpenSearch | `http://127.0.0.1:19200` | Log and Trace indexes |
| Jaeger UI | `http://127.0.0.1:16686` | Trace search and detail |

## Send SDK examples

From the repository root:

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'grpc'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:14317'
$runId = 'a2at-local-storage'
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.ExtensionsExample' "-Dexec.args=$runId" '-Dexec.cleanupDaemonThreads=false'
```

## Query data

```powershell
Invoke-RestMethod 'http://127.0.0.1:19090/api/v1/query?query=gen_ai_agent_a2at_task_count_total'
Invoke-RestMethod 'http://127.0.0.1:11902/api/v1/query?query=gen_ai_agent_a2at_task_count_total'
Invoke-RestMethod 'http://127.0.0.1:19200/a2at-logs-v2/_search?pretty'
```

In Jaeger UI, search by the SDK `service.name`. Prometheus counters use the `_total` suffix. Query OpenSearch logs by `resource.attributes.service.name` when separating test runs.

## Run the storage verification

`verify.ps1` starts the Compose stack, installs the SDK artifact, runs the independent
consumer, and waits until the metric, log, and trace can each be read from their
storage query endpoint. It writes a local JSON report named
`verification-report-<run-id>.json`; reports are ignored by Git. Containers and volumes
are retained after a successful run so that the stored data can be inspected.

```powershell
Set-Location deploy/local-storage
.\verify.ps1
```

Use a fixed run ID when comparing runs, and use `-Shutdown` only when the stack may be
stopped after verification:

```powershell
.\verify.ps1 -RunId 'storage-check-001' -Shutdown
```

The script requires Docker Compose, JDK 17, and Maven. It assumes Docker Desktop or a
WSL Docker engine is already running.

## Stop

```powershell
cd deploy/local-storage
docker compose down
```

`docker compose down` retains volumes. Use `docker compose down -v` only when the local stored data may be removed.

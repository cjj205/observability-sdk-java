# OpenAN Observability SDK for Java

将 A2A-T 观测数据转换为 OpenTelemetry 信号，并通过 OTLP 上报到 OpenTelemetry Collector。

文档导航：本文件介绍 SDK 接入和配置；[OTLP 连接配置与验证记录](docs/OTLP连接配置与验证记录.md)记录双协议、TLS/认证和后端查询证据；[文档索引](docs/README.md)列出 Collector 样例及其适用范围。工作区的 Grafana/Mimir/Loki/Tempo Compose 样例位于并列仓 a2a-t-observability/observability-sdk/storage。

## 项目简介

本模块供 A2A 和 A2A-T SDK 接入。采集方在消息发送、接收或业务结果产生时打点，提供协议属性、测量值或消息摘要。Observability Core 校验白名单并转换数据，由内置的 OpenTelemetry Java SDK 完成聚合、批处理和导出。

```text
A2A / A2A-T 采集方 → Observability Core → OpenTelemetry SDK → OTLP → Collector
```

## 核心能力

| 能力 | 当前实现 |
|---|---|
| 信号校验 | 按信号和扩展类型检查名称、允许字段、必填项和取值。非法输入在写入 OpenTelemetry 前被拒绝。 |
| OTel 转换 | A2A 支持 12 种 Span 和 2 项 Metric。A2A-T 支持四种扩展的 13 项协议属性、消息 Log 和 8 项 Metric。Workflow 支持 2 个 Trace、2 个 Log 和 3 项 Metric；Agent 支持子步骤 Trace、2 项 Metric 和 2 个 Log。 |
| 统一上报 | Trace、Metric、Log 共用一套 Collector 地址、OTLP 协议和服务名。SDK 统一管理三个 Exporter 的初始化、刷新和关闭。 |

## 项目结构

| 位置 | 内容 |
|---|---|
| [`src/main/java/net/openan/observability/`](src/main/java/net/openan/observability/) | Java 接口和 OTel 导出配置 |
| [`src/main/resources/observability/`](src/main/resources/observability/) | 版本化信号配置：`a2at.yaml`、`agent.yaml`、`workflow.yaml`；指标、扩展属性及 Workflow 信号规则由 YAML 加载 |
| [`internal/`](src/main/java/net/openan/observability/internal/) | 内部字段定义、校验与转换，不作为稳定公开 API |
| [`examples/`](examples/) | `TaskTExample` 演示任务场景，`ExtensionsExample` 验证四种扩展，`A2AExample` 演示 A2A Span 和 Metric |
| [`consumer-smoke/`](consumer-smoke/) | 单独的 Maven 应用，通过依赖引入 SDK 并验证生命周期与打点 |
| [`src/test/java/`](src/test/java/) | 字段校验、信号转换、上下文和配置测试 |
| [`examples/collector-config.yaml`](examples/collector-config.yaml) | 本地联调配置，接收三类 OTLP 信号并输出到 Collector 日志 |
| [`examples/collector-auth-tls-config.yaml`](examples/collector-auth-tls-config.yaml) | 本地联调的 TLS 与 Bearer Token 接收配置 |

## 快速开始

需要 JDK 17 或更高版本、Maven，以及可运行的 OpenTelemetry Collector。以下命令在仓库根目录执行。

**1. 启动 Collector。** 将 `otelcol` 加入 `PATH`，在第一个终端运行：

```powershell
otelcol --config=examples/collector-config.yaml
```

**2. 运行四种扩展的样例。** 在第二个终端运行：

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'grpc'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4317'
$runId = 'a2at-extensions-grpc-20261117'
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.ExtensionsExample' "-Dexec.args=$runId" '-Dexec.cleanupDaemonThreads=false'
```

验证 HTTP/Protobuf 时，将协议改为 `http/protobuf`、地址改为 `http://localhost:4318`，并给 `$runId` 换一个值后运行同一条 Maven 命令。样例用该参数设置 `service.name`，便于按本次运行筛选数据。

**3. 查看 Collector 输出。** 每次运行应收到 4 个 `message/send` Span、4 条 `gen_ai.agent.a2at.message` Log 和 8 项 Metric。日志的 Trace ID 和 Span ID 应与对应 Span 一致。按本次传入的 `service.name` 和时间范围筛选。

例如 `task.duration` 的 Histogram 应为 `count=1`、`sum=42000`、单位 `ms`，`task.count` 应为 `1`。样例也会提交非法输入，本地出现 `Observation ... rejected` 是预期行为。Collector 中不应出现 `invalid-marker`，非法增量 `999` 不应改变 `task.count`。刷新和关闭可能分别导出一次相同的累计值。

## 接入应用

从本目录执行 `mvn -B -ntp install`，在接入方的 `pom.xml` 中添加本地构建的依赖。

```xml
<dependency>
    <groupId>net.openan</groupId>
    <artifactId>observability-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

应用启动时创建一个 `ObservabilitySdk`，将 `sdk.observe()` 交给采集方。下面用固定的任务耗时演示三个入口。实际接入时，由采集方在业务结果到达时提交测量值。

```java
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

class Example {
    public static void main(String[] args) {
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName("my-agent").build())) {
            var observe = sdk.observe();
            var attrs = Map.of("extension.name", "task-t", "task.type", "diagnosis");
            try (var span = observe.trace("message/send", SignalKind.CLIENT, attrs)) {
                observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "诊断完成", attrs, span);
                observe.metric("task.duration", 42_000, Map.of("task.type", "diagnosis"));
                observe.metric("task.count", 1, Map.of("task.type", "diagnosis"));
                span.ok();
            }
            if (!sdk.flush(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Observability flush failed or timed out");
            }
        }
    }
}
```

在真实业务代码中，操作失败时可调用 `span.error()`，用 `try-with-resources` 确保 Span 结束。`span.makeCurrent()` 可在同线程建立上下文，返回的 `Scope` 用后关闭。跨线程时可显式传入父 `ObservationSpan` 或日志关联的 `ObservationSpan`。应用退出时关闭 SDK，短进程可在结束 Span 后先调用 `flush()`。

`flush(timeout, unit)` 同时等待三个信号的刷新。若 exporter 在本次刷新期间报告导出错误，或调用方等待时间先到，返回 `false`；OpenTelemetry exporter 的日志保留传输层错误详情。一次成功的刷新后再调用会重新统计后续批次的错误。

### Collector 配置

Java builder 显式值优先，其次读取环境变量，最后使用默认值。

| Builder 方法 | 环境变量 | 默认值 |
|---|---|---|
| `endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | gRPC 为 `http://localhost:4317`，HTTP 为 `http://localhost:4318` |
| `protocol` | `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc`，也支持 `http/protobuf` |
| `serviceName` | `OTEL_SERVICE_NAME` | `unknown_service` |
| `metricInterval` | `OTEL_METRIC_EXPORT_INTERVAL` | `60000` 毫秒，builder 接收 `Duration` |
| `headers(Map<String, String>)` / `header(name, value)` | `OTEL_EXPORTER_OTLP_HEADERS` | 不添加请求头 |
| `trustedCertificate(Path)` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | 使用系统默认信任库 |
| `exportTimeout(Duration)` | `OTEL_EXPORTER_OTLP_TIMEOUT` | `10000` 毫秒；`0` 表示不设导出时限 |

builder 显式值优先于环境变量。HTTP 配置填写基地址，SDK 会分别拼接 `/v1/traces`、`/v1/metrics`、`/v1/logs`。Collector 需要启用对应的 OTLP receiver，并接入三类信号的 pipeline。

认证头由三类 exporter 共用。环境变量按 `key=value,key2=value2` 格式填写；头名称会统一转成小写，以兼容 gRPC metadata。环境变量中的头值使用百分号编码，例如 `authorization=Bearer%20token%2Cpart`；builder Map 直接填写原始值。无效头配置的错误信息不会回显头值。

endpoint 的 `https` scheme 启用 TLS，`http` 使用明文。`trustedCertificate` 接收 PEM 或 DER X.509 CA 证书路径，用于验证 Collector 证书；设置此项时 endpoint 必须使用 `https`。证书读取或格式错误只报告 `OTEL_EXPORTER_OTLP_CERTIFICATE` 配置名。当前未开放客户端证书和私钥配置；如目标 Collector 要求 mTLS，再按接入要求扩展。

`exportTimeout` 限制每批 Trace、Metric 或 Log 导出的最长时间，与 `flush(timeout, unit)` 的调用等待上限独立生效。默认超时为 10 秒；默认重试、批处理和队列行为仍由 OpenTelemetry exporter 管理。

## 当前支持范围

### A2A Trace 和 Metric

`trace()` 接收以下 A2A Span 名称。`message/send` 和 `message/stream` 同时可以带已支持的 A2A-T `extension.name` 及该扩展允许的字段。

| Span 名 | Kind |
|---|---|
| `message/send`、`message/stream` | `CLIENT` / `SERVER` |
| `message/stream-event`、`message/stream-error` | `CLIENT` |
| `tasks/get`、`tasks/cancel`、`tasks/list`、`tasks/resubscribe` | `CLIENT` / `SERVER` |
| `tasks/pushNotificationConfig/set`、`tasks/pushNotificationConfig/get`、`tasks/pushNotificationConfig/list`、`tasks/pushNotificationConfig/delete` | `CLIENT` / `SERVER` |

Span 可接收 `operation.name`、`task_id`、`context_id`、`message_id`、`Role`、`Extensions`、`parts.number`、`config_id`、`Request`、`Response`、`error.type`。`streaming-event` 仅用于 `message/stream-event`。创建 `message/stream-event` 时，可把流式调用的 Span 作为第四个参数传入，SDK 将其记录为 OTel Span Link。`Role` 取 `user` 或 `agent`，`parts.number` 传非负整数。`Request`、`Response` 和 `streaming-event` 由采集方传入经过脱敏、允许上报的内容。

A2A 自定义属性可用短名或 `gen_ai.agent.a2a.` 全名。`error.type` 和 `gen_ai.system` 使用原名。Span 名按表中原样导出。

| Metric ID | 类型 | 单位 | 维度 |
|---|---|---|---|
| `gen_ai.client.operation.duration` | Histogram | `s` | `gen_ai.agent.a2a.operation.name`、`gen_ai.system`、`error.type` |
| `gen_ai.agent.a2a.streaming.duration` | Histogram | `s` | `gen_ai.agent.a2a.operation.name`、`gen_ai.system`、`error.type` |

两项 Metric 都要求提供 `operation.name`，SDK 将 `gen_ai.system` 设为 `A2A`。失败时可另传 `error.type`。使用与快速开始相同的 Collector 配置和环境变量运行样例。可在 Collector 输出中按 `service.name=a2a-demo` 查看 5 个 Span 和 2 项 Histogram。

```powershell
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.A2AExample' '-Dexec.cleanupDaemonThreads=false'
```

### A2A-T Span 和 Log

在 `message/send` 或 `message/stream` 上记录 A2A-T 属性时，以及提交 `gen_ai.agent.a2at.message` Log 时，须提供 `extension.name`。扩展类型决定允许的协议字段。Log 正文传处理后的消息摘要。

| `extension.name` | Span 和 Log 允许的其他属性 | 仅 Log 允许 |
|---|---|---|
| `task-t` | `task.type`（必填且非空白）、`task.object` | — |
| `negotiation-t` | `negotiation.id`、`negotiation.round`、`negotiation.total_rounds`、`negotiation.result`、`negotiation.performative` | `negotiation.max_rounds` |
| `notification-t` | `notification.topic`、`push.notification.url` | — |
| `authorization-t` | `authorization.stage`、`authorization.policy.operation.type` | — |

轮次属性传整数，其余属性传字符串。除 `extension.name` 外，SDK 不限制属性字符串取值，也不对其进行大小写转换。

### A2A-T Metric

指标定义由 `src/main/resources/observability/a2at.yaml` 加载，当前版本为 `1.0.0`。SDK 先校验指标名称，再按该指标的 `attributes.allowed` 校验属性白名单，并检查 `attributes.required`。例如 `task.duration` 和 `task.count` 允许 `task.type`、`task.object`，其中 `task.type` 必填。

| 指标 ID | 类型 | 单位 | 允许的维度 |
|---|---|---|---|
| `task.duration` | Histogram | `ms` | `task.type`（必填且非空白）、`task.object` |
| `task.count` | Counter | — | `task.type`（必填且非空白）、`task.object` |
| `negotiation.rounds` | Histogram | `{round}` | `negotiation.id`、`negotiation.result` |
| `negotiation.count` | Counter | — | `negotiation.result` |
| `notification.count` | Counter | — | `notification.topic` |
| `authorization.preauthorize.count` | Counter | — | `authorization.policy.operation.type` |
| `authorization.preauthorize.duration` | Histogram | `ms` | `authorization.policy.operation.type` |
| `authorization.execute.count` | Counter | — | `authorization.policy.operation.type` |

Histogram 接收单次测量值，由 OTel 聚合。Counter 接收单次计数增量。测量值须为有限非负数，轮次和计数传整数。表中未标必填的维度可省略。

### Workflow Trace、Metric 和 Log

Workflow 信号由 `src/main/resources/observability/workflow.yaml` 加载，当前版本为 `1.0.0`。新增或调整 Workflow 指标、属性白名单时更新该文件并递增版本。Workflow Trace 只接受 `INTERNAL` Kind。

| 信号类型 | 名称 | 允许属性 | 必填属性 |
|---|---|---|---|
| Trace | `a2at.workflow` | `workflow.id`、`workflow.type`、`workflow.status`、`workflow.session_id` | `workflow.id`、`workflow.type`、`workflow.status` |
| Trace | `a2at.step.<name>` | `workflow.step`、`workflow.step.index`、`workflow.step.status`、`workflow.step.owner_agent` | `workflow.step`、`workflow.step.index`、`workflow.step.status` |
| Metric | `workflow.duration` | `workflow.type`、`workflow.status` | 两者必填，Histogram，`ms` |
| Metric | `workflow.count` | `workflow.type`、`workflow.status` | 两者必填，Counter |
| Metric | `step.duration` | `workflow.step`、`gen_ai.agent.name` | 两者必填，Histogram，`ms` |
| Log | `gen_ai.agent.a2at.workflow` | `workflow.id`、`workflow.type`、`workflow.status`、`workflow.session_id`、`business.domain`、`business.customer` | `workflow.id`、`workflow.type`、`workflow.status` |
| Log | `gen_ai.agent.a2at.step` | `workflow.step`、`workflow.step.index`、`workflow.step.status`、`workflow.step.owner_agent` | `workflow.step`、`workflow.step.index`、`workflow.step.status` |

`workflow.step.index` 传整数。`workflow.status`、`workflow.step.status` 保留接入方给出的字符串值。示例：

```java
var workflow = Map.of(
    "workflow.id", "wf-001",
    "workflow.type", "fault-diagnosis",
    "workflow.status", "running");
try (var span = observe.trace("a2at.workflow", SignalKind.INTERNAL, workflow)) {
    observe.metric("workflow.duration", 42, Map.of(
        "workflow.type", "fault-diagnosis",
        "workflow.status", "completed"));
    observe.log("gen_ai.agent.a2at.workflow", LogSeverity.INFO,
        "workflow completed", Map.of(
            "workflow.id", "wf-001",
            "workflow.type", "fault-diagnosis",
            "workflow.status", "completed"), span);
}
```

### Agent Trace、Metric 和 Log

Agent 信号由 `src/main/resources/observability/agent.yaml` 加载，当前版本为 `1.0.0`。子步骤 Trace 只接受 `INTERNAL` Kind。`agent.skills` 传非空字符串列表，SDK 作为 OTel 字符串数组导出；Token 指标使用官方名称 `gen_ai.client.token.usage`，不添加 A2A-T 前缀。

| 信号类型 | 名称 | 允许属性 | 必填属性 |
|---|---|---|---|
| Trace | `a2at.substep.<name>` | `gen_ai.agent.name`、`substep.name`、`substep.status` | 三者必填 |
| Metric | `substep.duration` | `gen_ai.agent.name`、`substep.name` | 两者必填，Histogram，`ms` |
| Metric | `gen_ai.client.token.usage` | `gen_ai.agent.name`、`gen_ai.request.model`、`gen_ai.token.type` | 三者必填，Counter，`token` |
| Log | `gen_ai.agent.a2at.agent` | `gen_ai.agent.name`、`agent.role`、`agent.skills`、`agent.state` | `gen_ai.agent.name`、`agent.state` |
| Log | `gen_ai.agent.a2at.substep` | `gen_ai.agent.name`、`substep.name`、`substep.status`、`workflow.step`、`workflow.step.index` | `gen_ai.agent.name`、`substep.name`、`substep.status` |

`agent.state`、`substep.status`、`gen_ai.token.type` 保留接入方给出的字符串值。生命周期 Log 当前采用 `gen_ai.agent.a2at.agent`。如果接入端已使用 `gen_ai.agent.a2at.agent.lcm`，需在统一命名确认后增加兼容别名。

```java
var substep = Map.of(
    "gen_ai.agent.name", "diagnosis-agent",
    "substep.name", "retrieve-alarms",
    "substep.status", "active");
try (var span = observe.trace("a2at.substep.retrieve-alarms", SignalKind.INTERNAL, substep)) {
    observe.metric("gen_ai.client.token.usage", 120, Map.of(
        "gen_ai.agent.name", "diagnosis-agent",
        "gen_ai.request.model", "model-1",
        "gen_ai.token.type", "input"));
    observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO,
        "agent started", Map.of(
            "gen_ai.agent.name", "diagnosis-agent",
            "agent.state", "running"), span);
}
```

### 输入规则与接入边界

- A2A-T 属性名和指标 ID 可使用表中的短名，也可带 `gen_ai.agent.a2at.` 前缀。导出统一使用全名。同一属性同时用短名和全名传入时，整条信号会被拒绝。
- 诊断日志省略输入值。SDK 会处理字符串中的常见凭证，去掉 URL 的用户信息、查询串和片段，并将单个字符串限制在 2048 字符以内。采集方负责提供允许上报的数据和处理后的日志摘要。
- 当前实现通过显式 Java 接口接入，SDK 持有自己的 OTel 实例。同一打点迁移时应切换到单一上报通道。`TaskTHttpExample` 的合成 HTTP 请求已验证 W3C `traceparent` 传播和客户端/服务端父子关系；真实 OpenAN SDK 的打点迁移仍待联调。OTLP 共用认证头、私有 CA 信任和导出超时已开放；客户端证书与私钥的 mTLS 配置待目标 Collector 接入要求明确后再扩展。

## 开发与测试

```powershell
mvn -B -ntp -Pexamples clean verify
```

JUnit 测试覆盖 A2A Span、流事件 Link 和 Metric，A2A-T 字段白名单、三类信号转换和 Span 与 Log 关联，以及 Collector 连接配置、导出失败、超时和同实例恢复。默认 JAR 不包含样例类；`examples` profile 会编译样例。

`OtlpExporterConfigurationTest` 使用本机接收端检查三类请求的头、受信 CA、错误凭证返回的 HTTP 401、不受信 CA、单批导出超时、调用方等待上限，以及 Collector 同一实例从 503 恢复后的再次导出。恢复场景会比较失败与成功请求体：新 Span、Log 和 Metric 都能送达；原失败 Span、Log 没有在成功请求中重现，原 Metric 在后续累计导出中仍可见。HTTPS 用例收到非空 Protobuf 请求，但不解析数据或写入后端。真实 Collector 和 Mimir、Loki、Tempo 的两种协议联调结果见 [`docs/OTLP连接配置与验证记录.md`](docs/OTLP连接配置与验证记录.md)。配置测试覆盖 builder 优先级、环境变量解析、非法输入和错误信息脱敏。

### 跨进程 Task-T 样例

`TaskTHttpExample` 用两个 JVM 发送和接收一条带 Task-T 属性的合成 HTTP 请求。样例的发送端写入 W3C `traceparent`，接收端读取并建立父子 Span。两个进程使用相同的 Collector 配置。

先执行 `mvn -B -ntp -Pexamples test-compile`，再在两个终端依次运行服务端和客户端。每次联调换一个 `run-id`，两个进程使用相同的值。服务端收到一次请求后退出，最多等待 30 秒。

```powershell
# 终端一
mvn -B -ntp -Pexamples exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.TaskTHttpExample' '-Dexec.args=server 18080 run-001' '-Dexec.cleanupDaemonThreads=false'
# 终端二，看到 Listening on 18080 后执行
mvn -B -ntp -Pexamples exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.TaskTHttpExample' '-Dexec.args=client 18080 run-001' '-Dexec.cleanupDaemonThreads=false'
```

按服务名 `taskt-run-001-client` 和 `taskt-run-001-server` 查询。两端的 `message/send` Span 共用 Trace ID，服务端的 `parentSpanId` 等于客户端的 Span ID。日志关联服务端 Span。客户端记录一次请求耗时（秒），服务端记录一次任务耗时（毫秒）和一次任务计数。

如果 Windows 上的 JDK 21 在启动示例服务时提示 `Unable to establish loopback connection`，可在服务端终端临时将 `-Djdk.net.unixdomain.tmpdir=C:/path/to/nonexistent-directory` 加到 `MAVEN_OPTS`，让 JDK 在 Unix 域套接字无法绑定时回退到 TCP。

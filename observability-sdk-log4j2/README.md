# OpenAN Observability SDK Log4j2 bridge

This optional artifact forwards Log4j2 events to an existing `Observability` instance. It has no effect unless the event carries the configured Marker.

```java
import net.openan.observability.log4j2.OpenanObservabilityLog4j2Appender;
import org.apache.logging.log4j.core.config.LoggerConfig;

var appender = OpenanObservabilityLog4j2Appender.create(
    "openan-observability", sdk.observe(), "A2A_OBSERVABILITY", "gen_ai.agent.a2at.workflow");
appender.start();
LoggerConfig root = context.getConfiguration().getRootLogger();
root.addAppender(appender, null, null);
context.updateLoggers();
```

Use an SDK log signal name that accepts the MDC attributes produced by the application. The bridge maps Log4j2 levels to `LogSeverity`. It reads `traceId` and `spanId` from MDC when there is no current OpenTelemetry context; both values must be valid W3C hexadecimal IDs. Exception-body redaction remains the application's responsibility.

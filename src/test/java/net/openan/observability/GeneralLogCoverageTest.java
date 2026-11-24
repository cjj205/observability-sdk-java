package net.openan.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneralLogCoverageTest {
    @Test void exportsUnstructuredApplicationLogWithStandardFields() {
        var logs = InMemoryLogRecordExporter.create();
        try (var sdk = OpenTelemetrySdk.builder().setLoggerProvider(SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build()).build()) {
            new Observability(sdk).logRecord("task.completed", LogSeverity.INFO, "Task completed",
                Map.of("logger.name", "a2a-t-server", "thread.name", "worker-1", "exception.message", "task not found"),
                Instant.parse("2026-11-24T01:02:03Z"), new IllegalStateException("task not found"), null);

            assertEquals(1, logs.getFinishedLogRecordItems().size());
            var record = logs.getFinishedLogRecordItems().get(0);
            assertEquals("task.completed", record.getEventName());
            assertEquals("Task completed", record.getBodyValue().asString());
            assertEquals("a2a-t-server", record.getAttributes().get(AttributeKey.stringKey("logger.name")));
            assertEquals("java.lang.IllegalStateException: task not found", record.getAttributes().get(AttributeKey.stringKey("exception.message")));
            assertEquals(Instant.parse("2026-11-24T01:02:03Z").toEpochMilli() * 1_000_000, record.getTimestampEpochNanos());
        }
    }
}

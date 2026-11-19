package net.openan.observability;

import java.time.Duration;

/**
 * Settings for an OpenTelemetry batch processor.
 *
 * <p>The settings apply independently to trace and log pipelines. They do not affect metric
 * export; use {@link SdkConfig.Builder#metricInterval(Duration)} for metrics.
 */
public final class BatchConfig {
    final int maxQueueSize;
    final int maxExportBatchSize;
    final Duration scheduleDelay;
    final Duration exportTimeout;

    private BatchConfig(Builder builder) {
        maxQueueSize = builder.maxQueueSize;
        maxExportBatchSize = builder.maxExportBatchSize;
        scheduleDelay = builder.scheduleDelay;
        exportTimeout = builder.exportTimeout;
        if (maxQueueSize < 1 || maxExportBatchSize < 1 || maxExportBatchSize > maxQueueSize
            || scheduleDelay.isNegative() || exportTimeout.isNegative()) {
            throw new IllegalArgumentException("Invalid batch processor configuration");
        }
    }

    /** Returns a builder initialized with the supplied values. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link BatchConfig}. */
    public static final class Builder {
        private int maxQueueSize = 2048;
        private int maxExportBatchSize = 512;
        private Duration scheduleDelay = Duration.ofSeconds(5);
        private Duration exportTimeout = Duration.ofSeconds(30);

        /** Sets the maximum number of signals retained by the processor queue. */
        public Builder maxQueueSize(int value) { maxQueueSize = value; return this; }

        /** Sets the maximum number of signals sent by one exporter call. */
        public Builder maxExportBatchSize(int value) { maxExportBatchSize = value; return this; }

        /** Sets the maximum wait before a partially filled batch is exported. */
        public Builder scheduleDelay(Duration value) {
            scheduleDelay = requireDuration(value, "scheduleDelay");
            return this;
        }

        /** Sets the maximum processing time for one exporter batch. Zero means no deadline. */
        public Builder exportTimeout(Duration value) {
            exportTimeout = requireDuration(value, "exportTimeout");
            return this;
        }

        /** Validates the values and returns an immutable batch configuration. */
        public BatchConfig build() { return new BatchConfig(this); }

        private static Duration requireDuration(Duration value, String name) {
            if (value == null) throw new IllegalArgumentException(name + " is required");
            return value;
        }
    }
}

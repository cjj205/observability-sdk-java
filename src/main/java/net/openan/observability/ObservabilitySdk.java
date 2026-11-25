/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package net.openan.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Owns the OpenTelemetry providers used by this SDK.
 *
 * <p>Create one instance for an application process and close it during application shutdown.
 */
public final class ObservabilitySdk implements AutoCloseable {
    private final OpenTelemetrySdk otel;
    private final Observability observe;
    private final AtomicLong exportFailures;
    private final AtomicLong reportedExportFailures = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean enabled;
    private final AtomicBoolean reconfiguring = new AtomicBoolean();
    private final TrackingSpanExporter spanExporter;
    private final TrackingMetricExporter metricExporter;
    private final TrackingLogRecordExporter logExporter;
    private final SdkConfig providerConfig;

    private ObservabilitySdk(OpenTelemetrySdk otel, AtomicLong exportFailures, boolean enabled,
        TrackingSpanExporter spanExporter, TrackingMetricExporter metricExporter, TrackingLogRecordExporter logExporter,
        SdkConfig providerConfig) {
        this.otel = otel;
        this.enabled = new AtomicBoolean(enabled);
        this.observe = new Observability(otel, this.enabled);
        this.exportFailures = exportFailures;
        this.spanExporter = spanExporter;
        this.metricExporter = metricExporter;
        this.logExporter = logExporter;
        this.providerConfig = providerConfig;
    }

    /** Starts the SDK and configures OTLP exporters from the supplied configuration. */
    public static ObservabilitySdk start(SdkConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        String endpoint = config.endpoint.toString().replaceAll("/+$", "");
        SpanExporter spans;
        MetricExporter metrics;
        LogRecordExporter logs;
        AtomicLong exportFailures = new AtomicLong();

        if (config.protocol.equals("grpc")) {
            spans = configureExporter(
                OtlpGrpcSpanExporter.builder().setEndpoint(endpoint), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpGrpcSpanExporterBuilder::setTimeout,
                OtlpGrpcSpanExporterBuilder::setTrustedCertificates,
                OtlpGrpcSpanExporterBuilder::setClientTls,
                OtlpGrpcSpanExporterBuilder::build);
            metrics = configureExporter(
                OtlpGrpcMetricExporter.builder().setEndpoint(endpoint), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpGrpcMetricExporterBuilder::setTimeout,
                OtlpGrpcMetricExporterBuilder::setTrustedCertificates,
                OtlpGrpcMetricExporterBuilder::setClientTls,
                OtlpGrpcMetricExporterBuilder::build);
            logs = configureExporter(
                OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpGrpcLogRecordExporterBuilder::setTimeout,
                OtlpGrpcLogRecordExporterBuilder::setTrustedCertificates,
                OtlpGrpcLogRecordExporterBuilder::setClientTls,
                OtlpGrpcLogRecordExporterBuilder::build);
        } else {
            spans = configureExporter(
                OtlpHttpSpanExporter.builder().setEndpoint(endpoint + "/v1/traces"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpSpanExporterBuilder::setTimeout,
                OtlpHttpSpanExporterBuilder::setTrustedCertificates,
                OtlpHttpSpanExporterBuilder::setClientTls,
                OtlpHttpSpanExporterBuilder::build);
            metrics = configureExporter(
                OtlpHttpMetricExporter.builder().setEndpoint(endpoint + "/v1/metrics"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpMetricExporterBuilder::setTimeout,
                OtlpHttpMetricExporterBuilder::setTrustedCertificates,
                OtlpHttpMetricExporterBuilder::setClientTls,
                OtlpHttpMetricExporterBuilder::build);
            logs = configureExporter(
                OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint + "/v1/logs"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpLogRecordExporterBuilder::setTimeout,
                OtlpHttpLogRecordExporterBuilder::setTrustedCertificates,
                OtlpHttpLogRecordExporterBuilder::setClientTls,
                OtlpHttpLogRecordExporterBuilder::build);
        }

        TrackingSpanExporter trackingSpans = new TrackingSpanExporter(spans, exportFailures);
        TrackingMetricExporter trackingMetrics = new TrackingMetricExporter(metrics, exportFailures);
        TrackingLogRecordExporter trackingLogs = new TrackingLogRecordExporter(logs, exportFailures);

        Resource resource = Resource.getDefault().toBuilder()
            .put(AttributeKey.stringKey("service.name"), config.serviceName)
            .build();
        SdkTracerProvider tracer = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(trackingSpans)
                .setMaxQueueSize(config.spanBatch.maxQueueSize)
                .setMaxExportBatchSize(config.spanBatch.maxExportBatchSize)
                .setScheduleDelay(config.spanBatch.scheduleDelay)
                .setExporterTimeout(config.spanBatch.exportTimeout)
                .build())
            .build();
        SdkMeterProvider meter = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(trackingMetrics).setInterval(config.metricInterval).build())
            .build();
        SdkLoggerProvider logger = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(trackingLogs)
                .setMaxQueueSize(config.logBatch.maxQueueSize)
                .setMaxExportBatchSize(config.logBatch.maxExportBatchSize)
                .setScheduleDelay(config.logBatch.scheduleDelay)
                .setExporterTimeout(config.logBatch.exportTimeout)
                .build())
            .build();
        return new ObservabilitySdk(OpenTelemetrySdk.builder()
            .setTracerProvider(tracer)
            .setMeterProvider(meter)
            .setLoggerProvider(logger)
            .build(), exportFailures, config.enabled, trackingSpans, trackingMetrics, trackingLogs, config);
    }

    private static Exporters buildExporters(SdkConfig config) {
        String endpoint = config.endpoint.toString().replaceAll("/+$", "");
        if (config.protocol.equals("grpc")) {
            return new Exporters(
                configureExporter(OtlpGrpcSpanExporter.builder().setEndpoint(endpoint), config,
                    (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                    OtlpGrpcSpanExporterBuilder::setTimeout, OtlpGrpcSpanExporterBuilder::setTrustedCertificates,
                    OtlpGrpcSpanExporterBuilder::setClientTls, OtlpGrpcSpanExporterBuilder::build),
                configureExporter(OtlpGrpcMetricExporter.builder().setEndpoint(endpoint), config,
                    (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                    OtlpGrpcMetricExporterBuilder::setTimeout, OtlpGrpcMetricExporterBuilder::setTrustedCertificates,
                    OtlpGrpcMetricExporterBuilder::setClientTls, OtlpGrpcMetricExporterBuilder::build),
                configureExporter(OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint), config,
                    (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                    OtlpGrpcLogRecordExporterBuilder::setTimeout, OtlpGrpcLogRecordExporterBuilder::setTrustedCertificates,
                    OtlpGrpcLogRecordExporterBuilder::setClientTls, OtlpGrpcLogRecordExporterBuilder::build));
        }
        return new Exporters(
            configureExporter(OtlpHttpSpanExporter.builder().setEndpoint(endpoint + "/v1/traces"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpSpanExporterBuilder::setTimeout, OtlpHttpSpanExporterBuilder::setTrustedCertificates,
                OtlpHttpSpanExporterBuilder::setClientTls, OtlpHttpSpanExporterBuilder::build),
            configureExporter(OtlpHttpMetricExporter.builder().setEndpoint(endpoint + "/v1/metrics"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpMetricExporterBuilder::setTimeout, OtlpHttpMetricExporterBuilder::setTrustedCertificates,
                OtlpHttpMetricExporterBuilder::setClientTls, OtlpHttpMetricExporterBuilder::build),
            configureExporter(OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint + "/v1/logs"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpLogRecordExporterBuilder::setTimeout, OtlpHttpLogRecordExporterBuilder::setTrustedCertificates,
                OtlpHttpLogRecordExporterBuilder::setClientTls, OtlpHttpLogRecordExporterBuilder::build));
    }

    private record Exporters(SpanExporter spans, MetricExporter metrics, LogRecordExporter logs) {}

    private static <B, E> E configureExporter(
        B builder,
        SdkConfig config,
        BiConsumer<B, Map.Entry<String, String>> addHeader,
        BiConsumer<B, Duration> setTimeout,
        BiConsumer<B, byte[]> setTrustedCertificates,
        ClientTlsConfigurer<B> setClientTls,
        Function<B, E> build) {
        config.headers.entrySet().forEach(entry -> addHeader.accept(builder, entry));
        setTimeout.accept(builder, config.exportTimeout);
        if (config.trustedCertificates != null) {
            setTrustedCertificates.accept(builder, config.trustedCertificates.clone());
        }
        if (config.clientCertificate != null) {
            setClientTls.configure(builder, config.clientPrivateKey.clone(), config.clientCertificate.clone());
        }
        return build.apply(builder);
    }

    @FunctionalInterface
    private interface ClientTlsConfigurer<B> {
        void configure(B builder, byte[] privateKey, byte[] certificate);
    }

    /** Returns the signal recording facade associated with this SDK instance. */
    public Observability observe() {
        return observe;
    }

    /** Enables or disables creation of new trace, metric, and log records. */
    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    /** Returns whether creation of new telemetry is enabled. */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Replaces the OTLP exporters used by existing trace, metric, and log pipelines.
     *
     * <p>The resource identity, metric interval, and batch processor settings cannot change on a running
     * provider. Build a new SDK instance when those settings need to change. The method first builds all
     * replacement exporters and flushes the current providers. If either step fails, the active exporters
     * remain in use.
     */
    public CompletableResultCode updateExporters(SdkConfig config) {
        if (config == null || closed.get() || !reconfiguring.compareAndSet(false, true)) {
            return CompletableResultCode.ofFailure();
        }
        if (!sameProviderSettings(config)) {
            reconfiguring.set(false);
            return CompletableResultCode.ofFailure();
        }
        final Exporters replacement;
        try {
            replacement = buildExporters(config);
        } catch (RuntimeException e) {
            reconfiguring.set(false);
            return CompletableResultCode.ofExceptionalFailure(e);
        }
        CompletableResultCode drained = CompletableResultCode.ofAll(List.of(
            otel.getSdkTracerProvider().forceFlush(),
            otel.getSdkMeterProvider().forceFlush(),
            otel.getSdkLoggerProvider().forceFlush()));
        CompletableResultCode result = new CompletableResultCode();
        drained.whenComplete(() -> {
            if (!drained.isSuccess()) {
                closeExporters(replacement);
                reconfiguring.set(false);
                result.fail();
                return;
            }
            CompletableResultCode retired = CompletableResultCode.ofAll(List.of(
                spanExporter.replace(replacement.spans()),
                metricExporter.replace(replacement.metrics()),
                logExporter.replace(replacement.logs())));
            retired.whenComplete(() -> {
                reconfiguring.set(false);
                if (retired.isSuccess()) result.succeed(); else result.fail();
            });
        });
        return result;
    }

    private boolean sameProviderSettings(SdkConfig config) {
        return config.serviceName.equals(providerConfig.serviceName)
            && config.metricInterval.equals(providerConfig.metricInterval)
            && sameBatch(config.spanBatch, providerConfig.spanBatch)
            && sameBatch(config.logBatch, providerConfig.logBatch);
    }

    private static boolean sameBatch(BatchConfig left, BatchConfig right) {
        return left.maxQueueSize == right.maxQueueSize
            && left.maxExportBatchSize == right.maxExportBatchSize
            && left.scheduleDelay.equals(right.scheduleDelay)
            && left.exportTimeout.equals(right.exportTimeout);
    }

    private static void closeExporters(Exporters exporters) {
        exporters.spans().shutdown();
        exporters.metrics().shutdown();
        exporters.logs().shutdown();
    }

    /**
     * Waits for pending signals to be exported.
     *
     * @return {@code false} when a provider exceeds the supplied timeout or an exporter has failed since the
     * previous flush
     */
    public synchronized boolean flush(long timeout, TimeUnit unit) {
        long previousFailureCount = reportedExportFailures.get();
        CompletableResultCode result = CompletableResultCode.ofAll(List.of(
            otel.getSdkTracerProvider().forceFlush(),
            otel.getSdkMeterProvider().forceFlush(),
            otel.getSdkLoggerProvider().forceFlush()));
        result.join(timeout, unit);
        long currentFailureCount = exportFailures.get();
        reportedExportFailures.set(currentFailureCount);
        return result.isSuccess() && currentFailureCount == previousFailureCount;
    }

    /** Closes all providers and releases exporter resources. This operation is idempotent. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            otel.close();
        }
    }

    private static void trackExport(CompletableResultCode result, AtomicLong exportFailures) {
        if (result == null) {
            exportFailures.incrementAndGet();
            return;
        }
        result.whenComplete(() -> {
            if (!result.isSuccess()) {
                exportFailures.incrementAndGet();
            }
        });
    }

    private static final class TrackingSpanExporter implements SpanExporter {
        private volatile SpanExporter delegate;
        private final AtomicLong exportFailures;

        private TrackingSpanExporter(SpanExporter delegate, AtomicLong exportFailures) {
            this.delegate = delegate;
            this.exportFailures = exportFailures;
        }

        @Override
        public CompletableResultCode export(java.util.Collection<SpanData> spans) {
            try {
                CompletableResultCode result = delegate.export(spans);
                trackExport(result, exportFailures);
                return result;
            } catch (RuntimeException | Error e) {
                exportFailures.incrementAndGet();
                throw e;
            }
        }

        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
        @Override public void close() { delegate.close(); }

        synchronized CompletableResultCode replace(SpanExporter replacement) {
            SpanExporter previous = delegate;
            delegate = replacement;
            return previous.shutdown();
        }
    }

    private static final class TrackingMetricExporter implements MetricExporter {
        private volatile MetricExporter delegate;
        private final AtomicLong exportFailures;

        private TrackingMetricExporter(MetricExporter delegate, AtomicLong exportFailures) {
            this.delegate = delegate;
            this.exportFailures = exportFailures;
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return delegate.getAggregationTemporality(instrumentType);
        }

        @Override
        public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
            return delegate.getDefaultAggregation(instrumentType);
        }

        @Override
        public MemoryMode getMemoryMode() {
            return delegate.getMemoryMode();
        }

        @Override
        public CompletableResultCode export(java.util.Collection<MetricData> metrics) {
            try {
                CompletableResultCode result = delegate.export(metrics);
                trackExport(result, exportFailures);
                return result;
            } catch (RuntimeException | Error e) {
                exportFailures.incrementAndGet();
                throw e;
            }
        }

        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
        @Override public void close() { delegate.close(); }

        synchronized CompletableResultCode replace(MetricExporter replacement) {
            MetricExporter previous = delegate;
            delegate = replacement;
            return previous.shutdown();
        }
    }

    private static final class TrackingLogRecordExporter implements LogRecordExporter {
        private volatile LogRecordExporter delegate;
        private final AtomicLong exportFailures;

        private TrackingLogRecordExporter(LogRecordExporter delegate, AtomicLong exportFailures) {
            this.delegate = delegate;
            this.exportFailures = exportFailures;
        }

        @Override
        public CompletableResultCode export(java.util.Collection<LogRecordData> logs) {
            try {
                CompletableResultCode result = delegate.export(logs);
                trackExport(result, exportFailures);
                return result;
            } catch (RuntimeException | Error e) {
                exportFailures.incrementAndGet();
                throw e;
            }
        }

        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
        @Override public void close() { delegate.close(); }

        synchronized CompletableResultCode replace(LogRecordExporter replacement) {
            LogRecordExporter previous = delegate;
            delegate = replacement;
            return previous.shutdown();
        }
    }
}

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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * OTLP export configuration.
 *
 * <p>Explicit builder values take precedence over the corresponding OpenTelemetry environment variables.
 */
public final class SdkConfig {
    final URI endpoint;
    final String protocol;
    final String serviceName;
    final Duration metricInterval;
    final Duration exportTimeout;
    final Map<String, String> headers;
    final byte[] trustedCertificates;
    final byte[] clientCertificate;
    final byte[] clientPrivateKey;
    final boolean enabled;
    final BatchConfig spanBatch;
    final BatchConfig logBatch;

    private SdkConfig(Builder builder, Map<String, String> env) {
        protocol = select(builder.protocol, env.get("OTEL_EXPORTER_OTLP_PROTOCOL"), "grpc")
            .toLowerCase(Locale.ROOT);
        if (!protocol.equals("grpc") && !protocol.equals("http/protobuf")) {
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_PROTOCOL");
        }

        String defaultEndpoint = protocol.equals("grpc")
            ? "http://localhost:4317"
            : "http://localhost:4318";
        try {
            endpoint = URI.create(select(builder.endpoint, env.get("OTEL_EXPORTER_OTLP_ENDPOINT"), defaultEndpoint));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_ENDPOINT");
        }
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
            || endpoint.getHost() == null
            || endpoint.getUserInfo() != null
            || endpoint.getQuery() != null
            || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_ENDPOINT");
        }

        serviceName = select(builder.serviceName, env.get("OTEL_SERVICE_NAME"), "unknown_service");
        try {
            metricInterval = builder.metricInterval != null
                ? builder.metricInterval
                : Duration.ofMillis(Long.parseLong(select(null, env.get("OTEL_METRIC_EXPORT_INTERVAL"), "60000")));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid OTEL_METRIC_EXPORT_INTERVAL");
        }
        try {
            if (metricInterval.isZero() || metricInterval.isNegative() || metricInterval.toMillis() < 1) {
                throw new IllegalArgumentException("Invalid OTEL_METRIC_EXPORT_INTERVAL");
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid OTEL_METRIC_EXPORT_INTERVAL");
        }

        try {
            exportTimeout = builder.exportTimeout != null
                ? builder.exportTimeout
                : Duration.ofMillis(Long.parseLong(select(null, env.get("OTEL_EXPORTER_OTLP_TIMEOUT"), "10000")));
            if (exportTimeout.isNegative()) {
                throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_TIMEOUT");
            }
            // The OTLP exporters use zero to mean no export deadline.
            exportTimeout.toNanos();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid OTLP export timeout");
        }

        Map<String, String> configuredHeaders = builder.headers != null
            ? builder.headers
            : parseHeaders(env.get("OTEL_EXPORTER_OTLP_HEADERS"));
        headers = validateAndNormalizeHeaders(configuredHeaders);

        String certificatePath = builder.trustedCertificate != null
            ? builder.trustedCertificate.toString()
            : select(null, env.get("OTEL_EXPORTER_OTLP_CERTIFICATE"), null);
        if (certificatePath == null) {
            trustedCertificates = null;
        } else {
            if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("OTEL_EXPORTER_OTLP_CERTIFICATE requires an https endpoint");
            }
            trustedCertificates = readTrustedCertificates(certificatePath);
        }

        String clientCertificatePath = builder.clientCertificate == null ? null : builder.clientCertificate.toString();
        String clientPrivateKeyPath = builder.clientPrivateKey == null ? null : builder.clientPrivateKey.toString();
        if ((clientCertificatePath == null) != (clientPrivateKeyPath == null)) {
            throw new IllegalArgumentException("Client certificate and private key must be configured together");
        }
        if (clientCertificatePath == null) {
            clientCertificate = null;
            clientPrivateKey = null;
        } else {
            if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("Client TLS requires an https endpoint");
            }
            clientCertificate = readClientCertificate(clientCertificatePath);
            clientPrivateKey = readPrivateKey(clientPrivateKeyPath);
        }
        enabled = builder.enabled;
        spanBatch = builder.spanBatch;
        logBatch = builder.logBatch;
    }

    private static String select(String explicit, String environment, String fallback) {
        return explicit != null ? explicit : environment == null || environment.isBlank() ? fallback : environment;
    }

    private static Map<String, String> parseHeaders(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            for (String pair : value.split(",", -1)) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalArgumentException();
                }
                String name = pair.substring(0, separator);
                String headerValue = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                if (headers.putIfAbsent(name, headerValue) != null) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException e) {
            // Header values can contain credentials. Keep configuration errors value-free.
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_HEADERS");
        }
        return headers;
    }

    private static Map<String, String> validateAndNormalizeHeaders(Map<String, String> configuredHeaders) {
        Map<String, String> normalized = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> entry : configuredHeaders.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || value == null) {
                    throw new IllegalArgumentException();
                }
                String normalizedName = name.toLowerCase(Locale.ROOT);
                // gRPC metadata keys are lowercase; using the common safe subset works for HTTP too.
                if (!normalizedName.matches("[a-z0-9][a-z0-9_.-]*")
                    || value.chars().anyMatch(character -> character == '\r' || character == '\n' || character == 0x7f
                        || character < 0x20 && character != '\t')) {
                    throw new IllegalArgumentException();
                }
                if (normalized.putIfAbsent(normalizedName, value) != null) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid OTLP request headers");
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static byte[] readTrustedCertificates(String certificatePath) {
        try {
            byte[] certificateBytes = Files.readAllBytes(Path.of(certificatePath));
            var certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(certificateBytes));
            if (certificates.isEmpty()) {
                throw new IOException("No certificates found");
            }
            return certificateBytes;
        } catch (Exception e) {
            // Do not include the path or certificate contents in diagnostics.
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_CERTIFICATE");
        }
    }

    private static byte[] readClientCertificate(String certificatePath) {
        try {
            byte[] certificateBytes = Files.readAllBytes(Path.of(certificatePath));
            if (!new String(certificateBytes, StandardCharsets.US_ASCII).contains("-----BEGIN CERTIFICATE-----")) {
                throw new IOException("Client certificate must be PEM");
            }
            if (CertificateFactory.getInstance("X.509").generateCertificates(
                new ByteArrayInputStream(certificateBytes)).isEmpty()) {
                throw new IOException("No certificates found");
            }
            return certificateBytes;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid OTLP client certificate");
        }
    }

    private static byte[] readPrivateKey(String privateKeyPath) {
        try {
            byte[] key = Files.readAllBytes(Path.of(privateKeyPath));
            String pem = new String(key, StandardCharsets.US_ASCII);
            if (!pem.contains("-----BEGIN ") || !pem.contains("PRIVATE KEY-----")
                || !pem.contains("-----END ")) {
                throw new IOException("Private key must be PEM");
            }
            return key;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid OTLP client private key");
        }
    }

    /** Creates an empty builder that can be combined with OpenTelemetry environment variables. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SdkConfig}. */
    public static final class Builder {
        private String endpoint;
        private String protocol;
        private String serviceName;
        private Duration metricInterval;
        private Duration exportTimeout;
        private Map<String, String> headers;
        private Path trustedCertificate;
        private Path clientCertificate;
        private Path clientPrivateKey;
        private boolean enabled = true;
        private BatchConfig spanBatch = BatchConfig.builder().build();
        private BatchConfig logBatch = BatchConfig.builder().scheduleDelay(Duration.ofSeconds(1)).build();

        /** Sets the Collector base endpoint, for example {@code http://localhost:4317}. */
        public Builder endpoint(String value) {
            endpoint = value;
            return this;
        }

        /** Sets {@code grpc} or {@code http/protobuf}. */
        public Builder protocol(String value) {
            protocol = value;
            return this;
        }

        /** Sets the OpenTelemetry {@code service.name} resource attribute. */
        public Builder serviceName(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("serviceName is empty");
            }
            serviceName = value;
            return this;
        }

        /** Sets the periodic metric export interval. */
        public Builder metricInterval(Duration value) {
            metricInterval = value;
            return this;
        }

        /** Sets request headers for traces, metrics and logs, replacing the environment value. */
        public Builder headers(Map<String, String> value) {
            if (value == null) {
                throw new IllegalArgumentException("headers are required");
            }
            headers = new LinkedHashMap<>(value);
            return this;
        }

        /** Adds one request header and makes builder headers take precedence over the environment value. */
        public Builder header(String name, String value) {
            if (headers == null) {
                headers = new LinkedHashMap<>();
            }
            headers.put(name, value);
            return this;
        }

        /** Sets the maximum duration for each OTLP export batch. Zero means no exporter deadline. */
        public Builder exportTimeout(Duration value) {
            if (value == null) {
                throw new IllegalArgumentException("exportTimeout is required");
            }
            exportTimeout = value;
            return this;
        }

        /** Sets a PEM or DER X.509 CA certificate used to verify the Collector's TLS certificate. */
        public Builder trustedCertificate(Path value) {
            if (value == null) {
                throw new IllegalArgumentException("trustedCertificate is required");
            }
            trustedCertificate = value;
            return this;
        }

        /** Sets the PEM X.509 client certificate used for mutual TLS. */
        public Builder clientCertificate(Path value) {
            if (value == null) throw new IllegalArgumentException("clientCertificate is required");
            clientCertificate = value;
            return this;
        }

        /** Sets the PEM private key paired with {@link #clientCertificate(Path)} for mutual TLS. */
        public Builder clientPrivateKey(Path value) {
            if (value == null) throw new IllegalArgumentException("clientPrivateKey is required");
            clientPrivateKey = value;
            return this;
        }

        /** Controls whether recording starts enabled. The setting can later be changed on the SDK instance. */
        public Builder enabled(boolean value) { enabled = value; return this; }

        /** Sets trace batch queue, batch-size, schedule-delay, and processor-timeout settings. */
        public Builder spanBatch(BatchConfig value) {
            if (value == null) throw new IllegalArgumentException("spanBatch is required");
            spanBatch = value;
            return this;
        }

        /** Sets log batch queue, batch-size, schedule-delay, and processor-timeout settings. */
        public Builder logBatch(BatchConfig value) {
            if (value == null) throw new IllegalArgumentException("logBatch is required");
            logBatch = value;
            return this;
        }

        /** Validates the builder and environment values, then creates immutable SDK configuration. */
        public SdkConfig build() {
            return new SdkConfig(this, System.getenv());
        }

        SdkConfig build(Map<String, String> env) {
            return new SdkConfig(this, env);
        }
    }
}

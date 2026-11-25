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

package net.openan.observability.log4j2;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import net.openan.observability.LogSeverity;
import net.openan.observability.Observability;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Optional Log4j2 appender that forwards only marked events to an {@link Observability} instance.
 *
 * <p>Choose an SDK log signal name that is valid for the supplied MDC attributes. When an event has
 * no active OpenTelemetry context, {@code traceId} and {@code spanId} values in MDC are used when
 * both are valid W3C hexadecimal identifiers.
 */
public final class OpenanObservabilityLog4j2Appender extends AbstractAppender {
    private final Observability observe;
    private final String markerName;
    private final String eventName;

    private OpenanObservabilityLog4j2Appender(String name, Observability observe, String markerName, String eventName) {
        super(name, (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        this.observe = observe;
        this.markerName = markerName;
        this.eventName = eventName;
    }

    /** Creates an appender for programmatic registration with the Log4j2 configuration. */
    public static Appender create(String name, Observability observe, String markerName, String eventName) {
        if (name == null || name.isBlank() || observe == null || markerName == null || markerName.isBlank()
            || eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("name, observe, markerName, and eventName are required");
        }
        return new OpenanObservabilityLog4j2Appender(name, observe, markerName, eventName);
    }

    @Override
    public void append(LogEvent event) {
        Marker marker = event.getMarker();
        if (marker == null || !marker.isInstanceOf(markerName)) return;
        Map<String, Object> attributes = new LinkedHashMap<>();
        event.getContextData().forEach((key, value) -> attributes.put(key, value));
        observe.log(eventName, severity(event.getLevel()), event.getMessage().getFormattedMessage(), attributes,
            context(attributes));
    }

    private static Context context(Map<String, Object> attributes) {
        String traceId = stringAttribute(attributes, "traceId");
        String spanId = stringAttribute(attributes, "spanId");
        if (traceId == null || spanId == null) return Context.current();
        SpanContext spanContext = SpanContext.createFromRemoteParent(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        return spanContext.isValid() ? Context.root().with(Span.wrap(spanContext)) : Context.current();
    }

    private static String stringAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.remove(key);
        return value instanceof String text ? text : null;
    }

    private static LogSeverity severity(Level level) {
        if (level.isMoreSpecificThan(Level.FATAL)) return LogSeverity.FATAL;
        if (level.isMoreSpecificThan(Level.ERROR)) return LogSeverity.ERROR;
        if (level.isMoreSpecificThan(Level.WARN)) return LogSeverity.WARN;
        if (level.isMoreSpecificThan(Level.INFO)) return LogSeverity.INFO;
        if (level.isMoreSpecificThan(Level.DEBUG)) return LogSeverity.DEBUG;
        return LogSeverity.TRACE;
    }
}

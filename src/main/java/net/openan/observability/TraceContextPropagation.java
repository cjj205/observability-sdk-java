package net.openan.observability;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;

/** W3C Trace Context helpers for application transport carriers. */
public final class TraceContextPropagation {
    /** Sets one propagation header on a caller-owned carrier. */
    @FunctionalInterface
    public interface Setter<C> {
        /** Writes one W3C propagation header to the caller-owned carrier. */
        void set(C carrier, String key, String value);
    }

    /** Reads propagation headers from a caller-owned carrier. */
    public interface Getter<C> {
        /** Returns header names available on the caller-owned carrier. */
        Iterable<String> keys(C carrier);
        /** Returns a propagation header value, or {@code null} when it is absent. */
        String get(C carrier, String key);
    }

    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();

    private TraceContextPropagation() { }

    /** Injects the current context as W3C headers, including {@code traceparent} when a valid span is current. */
    public static <C> void inject(C carrier, Setter<C> setter) {
        Objects.requireNonNull(carrier, "carrier is required");
        Objects.requireNonNull(setter, "setter is required");
        PROPAGATOR.inject(Context.current(), carrier, setter::set);
    }

    /** Extracts W3C headers and makes the resulting context current until the returned scope is closed. */
    public static <C> Scope extractToCurrent(C carrier, Getter<C> getter) {
        Objects.requireNonNull(carrier, "carrier is required");
        Objects.requireNonNull(getter, "getter is required");
        Context extracted = PROPAGATOR.extract(Context.current(), carrier, new io.opentelemetry.context.propagation.TextMapGetter<C>() {
            @Override public Iterable<String> keys(C target) { return getter.keys(target); }
            @Override public String get(C target, String key) { return getter.get(target, key); }
        });
        return extracted.makeCurrent();
    }
}

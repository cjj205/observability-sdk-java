package net.openan.observability;

import net.openan.observability.internal.AttributesConverter;
import net.openan.observability.internal.Definitions;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Handle for an SDK-created span.
 *
 * <p>Use it with try-with-resources to ensure that the span is ended.
 */
public final class ObservationSpan implements AutoCloseable {
    private static final Logger DIAGNOSTICS = Logger.getLogger(ObservationSpan.class.getName());
    private final Span span;
    private final Context context;
    private final Definitions.Signal definition;
    private final AtomicBoolean ended = new AtomicBoolean();
    ObservationSpan(Span span, Context parent, Definitions.Signal definition) { this.span = span; this.context = parent.with(span); this.definition = definition; }
    static ObservationSpan noop() { return new ObservationSpan(Span.getInvalid(), Context.root(), null); }
    /** Adds configured attributes to this span. Invalid attributes are discarded. */
    public void attributes(Map<String, ?> attrs) {
        if (definition == null) return;
        try { span.setAllAttributes(AttributesConverter.convertSignal(attrs, 'S', definition, false)); }
        catch (RuntimeException ex) { DIAGNOSTICS.warning("Observation span attributes rejected"); }
    }
    /** Marks this span as successful. */
    public void ok() { span.setStatus(StatusCode.OK); }
    /** Marks this span as failed without attaching an exception. */
    public void error() { span.setStatus(StatusCode.ERROR); }
    /** Records an exception on this span and marks it as failed. */
    public void error(Throwable error) {
        span.recordException(Objects.requireNonNull(error, "error is required"));
        span.setStatus(StatusCode.ERROR);
    }
    /** Makes this span's context current until the returned scope is closed. */
    public Scope makeCurrent() { return context().makeCurrent(); }
    Context context() { return definition == null ? Context.current() : context; }
    @Override public void close() { if (ended.compareAndSet(false, true)) span.end(); }
    /** Ends this span. Calling it more than once has no effect. */
    public void end() { close(); }
}

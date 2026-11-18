package net.openan.observability.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Internal signal definitions; not a supported public API. */
public final class Definitions {
    public static final String PREFIX = "gen_ai.agent.a2at.";
    public static final String A2A_PREFIX = "gen_ai.agent.a2a.";
    public static final String MESSAGE_SPAN = "message/send";
    public static final String STREAM_SPAN = "message/stream";
    public static final String STREAM_EVENT = "message/stream-event";
    public static final String STREAM_ERROR = "message/stream-error";
    public static final String MESSAGE_LOG = PREFIX + "message";
    public record Attribute(String name, String signals, boolean integer, boolean stringArray, Set<String> values) {}
    public record Metric(boolean histogram, boolean integer, String unit, Set<String> dimensions, Set<String> requiredDimensions) {}
    public record Signal(String extension, Set<String> attributes, Set<String> required) {}
    public record NamedSignal(String name, String namePrefix, Signal signal) {
        public boolean matches(String candidate) { return name != null ? name.equals(candidate) : candidate.startsWith(namePrefix) && candidate.length() > namePrefix.length(); }
    }
    public static final Set<String> A2A_SPAN_NAMES = Set.of(MESSAGE_SPAN, STREAM_SPAN, STREAM_EVENT, STREAM_ERROR,
        "tasks/get", "tasks/cancel", "tasks/list", "tasks/resubscribe", "tasks/pushNotificationConfig/set",
        "tasks/pushNotificationConfig/get", "tasks/pushNotificationConfig/list", "tasks/pushNotificationConfig/delete");
    private static final Set<String> A2A_SPAN_ATTRIBUTES = Set.of("operation.name", "task_id", "context_id",
        "message_id", "Role", "Extensions", "parts.number", "config_id", "Request", "Response", "error.type");
    private static final Signal A2A_SPAN = new Signal(null, A2A_SPAN_ATTRIBUTES, Set.of());
    private static final Signal A2A_EVENT = new Signal(null, union(A2A_SPAN_ATTRIBUTES, Set.of("streaming-event")), Set.of());
    private static final SignalConfigurationLoader.Catalog CONFIGURATION = SignalConfigurationLoader.load();
    public static final Map<String, Signal> SPANS = CONFIGURATION.spans();
    public static final Map<String, Signal> LOGS = CONFIGURATION.logs();
    public static final Map<String, NamedSignal> NAMED_TRACES = CONFIGURATION.traces();
    public static final Map<String, NamedSignal> NAMED_LOGS = CONFIGURATION.namedLogs();
    public static final Map<String, String> CONFIGURATION_VERSIONS = CONFIGURATION.versions();
    static final Map<String, Attribute> ATTRIBUTES = Map.ofEntries(
        entry("extension.name", "SML", false, "task-t", "negotiation-t", "notification-t", "authorization-t"),
        entry("task.type", "SML", false), entry("task.object", "SML", false),
        entry("negotiation.id", "SML", false), entry("negotiation.round", "SML", true),
        entry("negotiation.total_rounds", "SL", true), entry("negotiation.max_rounds", "L", true),
        entry("negotiation.result", "SML", false, "accept", "reject", "timeout"),
        entry("negotiation.performative", "SML", false, "propose", "reject", "accept"),
        entry("notification.topic", "SML", false), entry("push.notification.url", "SL", false),
        entry("authorization.stage", "SL", false, "preauthorize", "execute"),
        entry("authorization.policy.operation.type", "SML", false),
        entry("workflow.id", "SML", false), entry("workflow.type", "SML", false),
        entry("workflow.status", "SML", false, "running", "completed", "failed"),
        entry("workflow.session_id", "SL", false), entry("workflow.step", "SML", false),
        entry("workflow.step.index", "SML", true),
        entry("workflow.step.status", "SML", false, "active", "failed", "done"),
        entry("workflow.step.owner_agent", "SL", false),
        entry("business.domain", "SL", false), entry("business.customer", "SL", false),
        attribute("gen_ai.agent.name", "gen_ai.agent.name", "SML", false),
        entry("agent.role", "SML", false), arrayEntry("agent.skills", "SL"),
        entry("agent.state", "SML", false, "idle", "running", "success", "failed"),
        entry("substep.name", "SML", false), entry("substep.status", "SML", false, "active", "failed", "done"),
        attribute("gen_ai.request.model", "gen_ai.request.model", "SML", false),
        attribute("gen_ai.response.model", "gen_ai.response.model", "SL", false),
        attribute("gen_ai.token.type", "gen_ai.token.type", "M", false, "input", "output"),
        attribute("operation.name", A2A_PREFIX + "operation.name", "SM", false),
        attribute("task_id", A2A_PREFIX + "task_id", "S", false),
        attribute("context_id", A2A_PREFIX + "context_id", "S", false),
        attribute("message_id", A2A_PREFIX + "message_id", "S", false),
        attribute("Role", A2A_PREFIX + "Role", "S", false, "user", "agent"),
        attribute("Extensions", A2A_PREFIX + "Extensions", "S", false),
        attribute("parts.number", A2A_PREFIX + "parts.number", "S", true),
        attribute("config_id", A2A_PREFIX + "config_id", "S", false),
        attribute("Request", A2A_PREFIX + "Request", "S", false),
        attribute("Response", A2A_PREFIX + "Response", "S", false),
        attribute("streaming-event", A2A_PREFIX + "streaming-event", "S", false),
        attribute("error.type", "error.type", "SM", false),
        attribute("gen_ai.system", "gen_ai.system", "M", false));
    public static final Map<String, Metric> METRICS = CONFIGURATION.metrics();
    public static final String A2A_OPERATION_DURATION = "gen_ai.client.operation.duration";
    public static final String A2A_STREAMING_DURATION = A2A_PREFIX + "streaming.duration";
    private static final Metric A2A_DURATION = new Metric(true, false, "s",
        Set.of("operation.name", "gen_ai.system", "error.type"), Set.of("operation.name"));
    public static final Map<String, Metric> A2A_METRICS = Map.of(
        A2A_OPERATION_DURATION, A2A_DURATION, A2A_STREAMING_DURATION, A2A_DURATION);

    public static Signal span(String name, Signal extension) {
        if (extension == null) return STREAM_EVENT.equals(name) ? A2A_EVENT : A2A_SPAN;
        if (!MESSAGE_SPAN.equals(name) && !STREAM_SPAN.equals(name)) throw new IllegalArgumentException();
        return new Signal(extension.extension(), union(A2A_SPAN_ATTRIBUTES, extension.attributes()), extension.required());
    }
    public static Signal namedTrace(String name) { return find(NAMED_TRACES, name); }
    public static Signal namedLog(String name) { return find(NAMED_LOGS, name); }
    private static Signal find(Map<String, NamedSignal> definitions, String name) {
        return definitions.values().stream().filter(definition -> definition.matches(name)).map(NamedSignal::signal).findFirst().orElse(null);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> values = new HashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }

    private static Map.Entry<String, Attribute> entry(String name, String signals, boolean integer, String... values) {
        return attribute(name, PREFIX + name, signals, integer, false, values);
    }
    private static Map.Entry<String, Attribute> arrayEntry(String name, String signals) {
        return attribute(name, PREFIX + name, signals, false, true);
    }
    private static Map.Entry<String, Attribute> attribute(String key, String name, String signals, boolean integer, String... values) {
        return attribute(key, name, signals, integer, false, values);
    }
    private static Map.Entry<String, Attribute> attribute(String key, String name, String signals, boolean integer, boolean stringArray, String... values) {
        return Map.entry(key, new Attribute(name, signals, integer, stringArray, Set.of(values)));
    }
    public static String metricName(String name) { return name.startsWith("gen_ai.") ? name : PREFIX + name; }
    public static String shortName(String name) { return name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name; }
    public static String attributeName(String key) {
        Attribute attribute = ATTRIBUTES.get(key);
        return attribute == null ? PREFIX + key : attribute.name();
    }
    private Definitions() {}
}

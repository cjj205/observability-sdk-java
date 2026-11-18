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
    public enum AttributeType { STRING, INTEGER, STRING_ARRAY }
    public record Attribute(String name, String signals, AttributeType type, Set<String> values) {}
    public record Metric(String name, boolean histogram, boolean integer, String unit, Set<String> dimensions, Set<String> requiredDimensions) {}
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
    private static final Map<String, Attribute> NATIVE_A2A_ATTRIBUTES = Map.ofEntries(
        nativeAttribute("operation.name", A2A_PREFIX + "operation.name", "SM", AttributeType.STRING),
        nativeAttribute("task_id", A2A_PREFIX + "task_id", "S", AttributeType.STRING),
        nativeAttribute("context_id", A2A_PREFIX + "context_id", "S", AttributeType.STRING),
        nativeAttribute("message_id", A2A_PREFIX + "message_id", "S", AttributeType.STRING),
        nativeAttribute("Role", A2A_PREFIX + "Role", "S", AttributeType.STRING),
        nativeAttribute("Extensions", A2A_PREFIX + "Extensions", "S", AttributeType.STRING),
        nativeAttribute("parts.number", A2A_PREFIX + "parts.number", "S", AttributeType.INTEGER),
        nativeAttribute("config_id", A2A_PREFIX + "config_id", "S", AttributeType.STRING),
        nativeAttribute("Request", A2A_PREFIX + "Request", "S", AttributeType.STRING),
        nativeAttribute("Response", A2A_PREFIX + "Response", "S", AttributeType.STRING),
        nativeAttribute("streaming-event", A2A_PREFIX + "streaming-event", "S", AttributeType.STRING),
        nativeAttribute("error.type", "error.type", "SM", AttributeType.STRING),
        nativeAttribute("gen_ai.system", "gen_ai.system", "M", AttributeType.STRING));
    public static final Map<String, Metric> METRICS = CONFIGURATION.metrics();
    public static final String A2A_OPERATION_DURATION = "gen_ai.client.operation.duration";
    public static final String A2A_STREAMING_DURATION = A2A_PREFIX + "streaming.duration";
    private static final Metric A2A_DURATION = new Metric(A2A_OPERATION_DURATION, true, false, "s",
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

    private static Map.Entry<String, Attribute> nativeAttribute(String key, String name, String signals, AttributeType type) {
        return Map.entry(key, new Attribute(name, signals, type, Set.of()));
    }
    public static String shortName(String name) { return name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name; }
    public static Attribute attribute(String key) {
        Attribute configured = CONFIGURATION.attributes().get(key);
        return configured == null ? NATIVE_A2A_ATTRIBUTES.get(key) : configured;
    }
    public static String attributeName(String key) {
        Attribute attribute = attribute(key);
        return attribute == null ? PREFIX + key : attribute.name();
    }
    private Definitions() {}
}

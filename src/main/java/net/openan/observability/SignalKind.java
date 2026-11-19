package net.openan.observability;

/** OpenTelemetry span kinds accepted by the SDK trace API. */
public enum SignalKind { INTERNAL, CLIENT, SERVER, PRODUCER, CONSUMER }

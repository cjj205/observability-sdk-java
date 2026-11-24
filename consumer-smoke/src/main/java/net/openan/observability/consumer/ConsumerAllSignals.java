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

package net.openan.observability.consumer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservationSpan;
import net.openan.observability.Observability;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/**
 * Independent consumer that emits every currently configured signal family.
 *
 * <p>This is intentionally separate from {@link ConsumerSmoke}, which remains the fast minimal
 * artifact and Collector connectivity check.
 */
public final class ConsumerAllSignals {
    private static final String MESSAGE_LOG = "gen_ai.agent.a2at.message";

    private ConsumerAllSignals() { }

    public static void main(String[] args) {
        String runId = args.length == 0 ? "local" : args[0];
        String serviceName = "sdk-consumer-all-" + runId;
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName(serviceName).build())) {
            var observe = sdk.observe();
            emitA2aTraces(observe, runId);
            emitA2aMetrics(observe);
            emitExtensions(observe, runId);
            emitExtensionMetrics(observe);
            emitWorkflowSignals(observe, runId);
            emitAgentSignals(observe, runId);
            if (!sdk.flush(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OTLP export failed or flush timed out");
            }
        }
        System.out.println("All configured signals emitted for " + serviceName);
    }

    private static void emitA2aTraces(Observability observe, String runId) {
        for (String operation : List.of(
            "SendMessage", "SendStreamingMessage", "GetTask", "CancelTask", "ListTasks",
            "SubscribeToTask", "CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig",
            "ListTaskPushNotificationConfigs", "DeleteTaskPushNotificationConfig")) {
            emitTrace(observe, operation, SignalKind.CLIENT, a2aAttributes(operation, runId));
            emitTrace(observe, operation, SignalKind.SERVER, a2aAttributes(operation, runId));
        }

        emitTrace(observe, "GetExtendedAgentCard", SignalKind.CLIENT, a2aAttributes("GetExtendedAgentCard", runId));
        emitTrace(observe, "SendPushNotification", SignalKind.CLIENT, a2aAttributes("SendPushNotification", runId));

        try (var stream = observe.trace("SendStreamingMessage", SignalKind.CLIENT,
            a2aAttributes("SendStreamingMessage", runId))) {
            emitTrace(observe, "SendStreamingMessage-event", SignalKind.CLIENT, Map.ofEntries(
                Map.entry("operation.name", "SendStreamingMessage"), Map.entry("task_id", "task-" + runId),
                Map.entry("context_id", "context-" + runId), Map.entry("message_id", "message-" + runId),
                Map.entry("streaming-event", "status-update"), Map.entry("task.status.state", "working"),
                Map.entry("artifact.name", "report"), Map.entry("artifact.append", true),
                Map.entry("artifact.last_chunk", false), Map.entry("usage.input_tokens", 13),
                Map.entry("usage.output_tokens", 21)), stream);
            emitTrace(observe, "SendStreamingMessage-end", SignalKind.CLIENT, Map.of(
                "operation.name", "SendStreamingMessage", "task_id", "task-" + runId,
                "error.type", "none"), stream);
            stream.ok();
        }

        emitTrace(observe, "SendStreamingMessage-negotiation", SignalKind.SERVER, Map.of(
            "extension.name", "negotiation-t", "negotiation.id", "negotiation-" + runId,
            "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.max_rounds", 3,
            "negotiation.result", "accepted", "negotiation.performative", "propose"));
    }

    private static Map<String, Object> a2aAttributes(String operation, String runId) {
        return Map.ofEntries(
            Map.entry("operation.name", operation), Map.entry("task_id", "task-" + runId),
            Map.entry("context_id", "context-" + runId), Map.entry("message_id", "message-" + runId),
            Map.entry("role", "agent"), Map.entry("extensions", "a2at"), Map.entry("parts.number", 1),
            Map.entry("config_id", "config-1"), Map.entry("request", "https://example.com/request"),
            Map.entry("response", "completed"), Map.entry("gen_ai.provider.name", "openan"),
            Map.entry("gen_ai.operation.name", operation), Map.entry("gen_ai.agent.name", "consumer-agent"),
            Map.entry("gen_ai.agent.id", "agent-1"), Map.entry("gen_ai.agent.version", "1.0.0"),
            Map.entry("push_notification.event_kind", "status"), Map.entry("transport", "http"),
            Map.entry("protocol.version", "0.3"), Map.entry("gen_ai.response.model", "model-1"),
            Map.entry("usage.input_tokens", 13), Map.entry("usage.output_tokens", 21));
    }

    private static void emitA2aMetrics(Observability observe) {
        observe.metric("gen_ai.client.operation.duration", 0.12, Map.of("operation.name", "SendMessage"));
        observe.metric("gen_ai.agent.a2a.streaming.duration", 0.45, Map.of(
            "operation.name", "SendStreamingMessage", "error.type", "none"));
    }

    private static void emitExtensions(Observability observe, String runId) {
        emitExtension(observe, "task-t", Map.of(
            "extension.name", "task-t", "task.type", "consumer-all", "task.object", runId), Map.of(
            "extension.name", "task-t", "task.type", "consumer-all", "task.object", runId));
        emitExtension(observe, "negotiation-t", Map.of(
            "extension.name", "negotiation-t", "negotiation.id", "negotiation-" + runId,
            "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.result", "accepted",
            "negotiation.performative", "propose"), Map.of(
            "extension.name", "negotiation-t", "negotiation.id", "negotiation-" + runId,
            "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.max_rounds", 3,
            "negotiation.result", "accepted", "negotiation.performative", "propose"));
        emitExtension(observe, "notification-t", Map.of(
            "extension.name", "notification-t", "notification.topic", "consumer-all",
            "push.notification.url", "https://example.com/notifications"), Map.of(
            "extension.name", "notification-t", "notification.topic", "consumer-all",
            "push.notification.url", "https://example.com/notifications"));
        emitExtension(observe, "authorization-t", Map.of(
            "extension.name", "authorization-t", "authorization.stage", "execute",
            "authorization.policy.operation.type", "create"), Map.of(
            "extension.name", "authorization-t", "authorization.stage", "execute",
            "authorization.policy.operation.type", "create"));
    }

    private static void emitExtension(Observability observe, String extension, Map<String, ?> spanAttributes,
            Map<String, ?> logAttributes) {
        try (var span = observe.trace("SendMessage", SignalKind.CLIENT, spanAttributes)) {
            observe.log(MESSAGE_LOG, LogSeverity.INFO, "Consumer all " + extension, logAttributes, span);
            span.ok();
        }
        emitTrace(observe, "SendStreamingMessage", SignalKind.CLIENT, spanAttributes);
    }

    private static void emitExtensionMetrics(Observability observe) {
        observe.metric("task.duration", 42.0, Map.of("task.type", "consumer-all", "task.object", "full"));
        observe.metric("task.count", 1, Map.of("task.type", "consumer-all", "task.object", "full"));
        observe.metric("negotiation.rounds", 2, Map.of("negotiation.id", "negotiation-1", "negotiation.result", "accepted"));
        observe.metric("negotiation.count", 1, Map.of("negotiation.result", "accepted"));
        observe.metric("notification.count", 1, Map.of("notification.topic", "consumer-all"));
        observe.metric("authorization.preauthorize.count", 1, Map.of("authorization.policy.operation.type", "create"));
        observe.metric("authorization.preauthorize.duration", 5.0, Map.of("authorization.policy.operation.type", "create"));
        observe.metric("authorization.execute.count", 1, Map.of("authorization.policy.operation.type", "create"));
    }

    private static void emitWorkflowSignals(Observability observe, String runId) {
        Map<String, Object> workflow = Map.of(
            "workflow.id", "workflow-" + runId, "workflow.type", "consumer-all", "workflow.status", "completed",
            "workflow.session_id", "session-" + runId);
        Map<String, Object> step = Map.of(
            "workflow.step", "collect", "workflow.step.index", 1, "workflow.step.status", "completed",
            "workflow.step.owner_agent", "consumer-agent");
        try (var workflowSpan = observe.trace("a2at.workflow", SignalKind.INTERNAL, workflow)) {
            observe.log("gen_ai.agent.a2at.workflow", LogSeverity.INFO, "Workflow completed", Map.ofEntries(
                Map.entry("workflow.id", "workflow-" + runId), Map.entry("workflow.type", "consumer-all"),
                Map.entry("workflow.status", "completed"), Map.entry("workflow.session_id", "session-" + runId),
                Map.entry("business.domain", "observability"), Map.entry("business.customer", "consumer")), workflowSpan);
            try (var stepSpan = observe.trace("a2at.step.collect", SignalKind.INTERNAL, step, workflowSpan)) {
                observe.log("gen_ai.agent.a2at.step", LogSeverity.INFO, "Workflow step completed", step, stepSpan);
                stepSpan.ok();
            }
            workflowSpan.ok();
        }
        observe.metric("workflow.duration", 90.0, Map.of("workflow.type", "consumer-all", "workflow.status", "completed"));
        observe.metric("workflow.count", 1, Map.of("workflow.type", "consumer-all", "workflow.status", "completed"));
        observe.metric("step.duration", 40.0, Map.of("workflow.step", "collect", "gen_ai.agent.name", "consumer-agent"));
    }

    private static void emitAgentSignals(Observability observe, String runId) {
        Map<String, Object> substep = Map.of(
            "gen_ai.agent.name", "consumer-agent", "substep.name", "analyze", "substep.status", "completed");
        try (var span = observe.trace("a2at.substep.analyze", SignalKind.INTERNAL, substep)) {
            observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, "Agent completed", Map.of(
                "gen_ai.agent.name", "consumer-agent", "agent.role", "observer",
                "agent.skills", List.of("analyze", "report"), "agent.state", "completed"), span);
            observe.log("gen_ai.agent.a2at.substep", LogSeverity.INFO, "Agent substep completed", Map.of(
                "gen_ai.agent.name", "consumer-agent", "substep.name", "analyze", "substep.status", "completed",
                "workflow.step", "collect", "workflow.step.index", 1), span);
            span.ok();
        }
        observe.metric("substep.duration", 30.0, Map.of("gen_ai.agent.name", "consumer-agent", "substep.name", "analyze"));
        observe.metric("gen_ai.client.token.usage", 34, Map.of(
            "gen_ai.agent.name", "consumer-agent", "gen_ai.request.model", "model-1", "gen_ai.token.type", "input"));
    }

    private static void emitTrace(Observability observe, String name, SignalKind kind, Map<String, ?> attributes) {
        try (var span = observe.trace(name, kind, attributes)) {
            span.ok();
        }
    }

    private static void emitTrace(Observability observe, String name, SignalKind kind, Map<String, ?> attributes,
            ObservationSpan parent) {
        try (var span = observe.trace(name, kind, attributes, parent)) {
            span.ok();
        }
    }
}

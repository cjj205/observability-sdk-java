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

package net.openan.observability.example;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/** Fixed inputs for checking exported A2A traces and metrics. */
public final class A2AExample {
    public static void main(String[] args) {
        String service = args.length == 0 ? "a2a-demo" : args[0];
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName(service).build())) {
            var observe = sdk.observe();
            try (var span = observe.trace("SendMessage", SignalKind.CLIENT, Map.of(
                "operation.name", "SendMessage", "task_id", "demo-task", "context_id", "demo-context",
                "message_id", "demo-message", "role", "user", "extensions", "task-t", "parts.number", 1,
                "request", "Demo request"))) {
                span.attributes(Map.of("response", "Demo response"));
                span.ok();
            }
            try (var stream = observe.trace("SendStreamingMessage", SignalKind.CLIENT, Map.of(
                "operation.name", "SendStreamingMessage", "extension.name", "task-t", "task.type", "diagnosis"))) {
                try (var event = observe.trace("SendStreamingMessage-event", SignalKind.CLIENT,
                    Map.of("streaming-event", "Demo status update"), stream)) {
                    event.ok();
                }
                try (var error = observe.trace("SendStreamingMessage-end", SignalKind.CLIENT,
                    Map.of("error.type", "demo_timeout"), stream)) {
                    error.error();
                }
                stream.error();
            }
            try (var span = observe.trace("GetTaskPushNotificationConfig", SignalKind.SERVER,
                Map.of("config_id", "demo-config"))) {
                span.ok();
            }
            observe.metric("gen_ai.client.operation.duration", 0.125, Map.of("operation.name", "SendMessage"));
            observe.metric("gen_ai.agent.a2a.streaming.duration", 0.5,
                Map.of("operation.name", "SendStreamingMessage", "error.type", "demo_timeout"));
            if (!sdk.flush(10, TimeUnit.SECONDS)) throw new IllegalStateException("Flush failed or timed out");
        }
    }
}

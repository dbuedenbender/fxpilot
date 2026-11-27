/*
 * Copyright 2026 FxPilot contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dbuedenbender.fxpilot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent entry points are normally loaded by the JVM through {@code -javaagent}; these tests call
 * them directly. {@code Instrumentation} is not used by the implementation, so {@code null} is enough.
 */
class FxPilotAgentTest {

    @AfterEach
    void stop() {
        FxPilotServer.stop();
        System.clearProperty("fxpilot.port");
    }

    @Test
    void startsEndpointFromAgentArguments() {
        FxPilotAgent.premain("port = 0, token = secret", null);
        assertTrue(FxPilotServer.isRunning());
        assertTrue(FxPilotServer.port() > 0);
    }

    @Test
    void fallsBackToThePortProperty() {
        System.setProperty("fxpilot.port", "0");
        FxPilotAgent.agentmain(null, null);
        assertTrue(FxPilotServer.isRunning());
    }

    @Test
    void ignoresUnknownArgumentsAndABlankPort() {
        FxPilotAgent.premain("unknown,also=ignored,port= ", null);
        assertFalse(FxPilotServer.isRunning());
    }

    @Test
    void rejectsAnInvalidPort() {
        FxPilotAgent.premain("port=not-a-number", null);
        assertFalse(FxPilotServer.isRunning());
    }
}

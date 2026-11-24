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
package io.github.buedi.fxpilot;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point: {@code -javaagent:fxpilot.jar=port=8765[,token=secret]}.
 * Starts the FxPilot endpoint without any change to the application code. Requests that arrive
 * before the JavaFX toolkit is up are answered with HTTP 503.
 */
public final class FxPilotAgent {

    private FxPilotAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args);
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args);
    }

    private static void start(String args) {
        String port = null;
        String token = null;
        if (args != null) {
            for (String part : args.split(",")) {
                int eq = part.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                if (key.equals("port")) {
                    port = value;
                } else if (key.equals("token")) {
                    token = value;
                }
            }
        }
        if (port == null) {
            port = System.getProperty("fxpilot.port", System.getenv("FXPILOT_PORT"));
        }
        if (token == null) {
            token = System.getProperty("fxpilot.token", System.getenv("FXPILOT_TOKEN"));
        }
        if (port == null || port.isBlank()) {
            System.err.println("[fxpilot] agent loaded without port, use -javaagent:fxpilot.jar=port=8765");
            return;
        }
        if (!JavaFxCheck.supported()) {
            return;
        }
        try {
            FxPilotServer.start(Integer.parseInt(port.trim()), token);
        } catch (NumberFormatException e) {
            System.err.println("[fxpilot] invalid port '" + port + "'");
        }
    }
}

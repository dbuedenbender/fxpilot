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

/**
 * FxPilot: a local endpoint that lets an AI agent drive a JavaFX application, and the MCP server that talks
 * to it. The API is {@code FxPilotServer} and {@code FxPilotAgent} in {@code io.github.dbuedenbender.fxpilot}
 * and {@code FxPilotMcpServer} in {@code io.github.dbuedenbender.fxpilot.mcp}; {@code ...fxpilot.internal}
 * is shared code that is not exported.
 *
 * <p>JavaFX is optional for the module: the app that hosts the endpoint provides it, and the MCP server
 * process runs without it.
 */
module io.github.dbuedenbender.fxpilot {
    requires static javafx.controls;
    requires jdk.httpserver;
    requires java.net.http;
    requires java.desktop;
    requires static transitive java.instrument;
    requires static java.logging;

    exports io.github.dbuedenbender.fxpilot;
    exports io.github.dbuedenbender.fxpilot.mcp;
}

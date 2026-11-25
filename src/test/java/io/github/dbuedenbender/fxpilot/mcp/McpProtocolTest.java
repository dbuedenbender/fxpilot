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
package io.github.dbuedenbender.fxpilot.mcp;

import io.github.dbuedenbender.fxpilot.internal.Json;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpProtocolTest {

    @Test
    void negotiatesTheProtocolVersion() {
        assertEquals("2025-06-18", FxPilotMcpServer.negotiate("2025-06-18"));
        assertEquals("2024-11-05", FxPilotMcpServer.negotiate("2024-11-05"));
        String newest = FxPilotMcpServer.SUPPORTED_PROTOCOLS.get(0);
        assertEquals(newest, FxPilotMcpServer.negotiate("1999-01-01"), "unknown versions get the newest supported one");
        assertEquals(newest, FxPilotMcpServer.negotiate(null));
    }

    @Test
    void initializeAdvertisesToolsAndInstructions() throws Exception {
        Map<?, ?> r = (Map<?, ?>) FxPilotMcpServer.dispatch("initialize", Map.of("protocolVersion", "2099-01-01"));
        assertEquals(FxPilotMcpServer.SUPPORTED_PROTOCOLS.get(0), r.get("protocolVersion"));
        assertTrue(((Map<?, ?>) r.get("capabilities")).containsKey("tools"));
        assertTrue(String.valueOf(r.get("instructions")).contains("fx_health"));
        assertTrue(String.valueOf(r.get("instructions")).contains("java -jar fxpilot.jar --replay"));
    }

    @Test
    void unknownMethodIsAJsonRpcError() {
        RpcError e = assertThrows(RpcError.class, () -> FxPilotMcpServer.dispatch("resources/list", Map.of()));
        assertEquals(-32601, e.code);
    }

    @Test
    void writesNonFiniteNumbersAsNull() {
        assertEquals("[null,null,1.5]", Json.write(List.of(Double.NaN, Float.POSITIVE_INFINITY, 1.5)));
        assertEquals(List.of(1L, 2.5), Json.parse("[1, 2.5]"));
    }

    /**
     * Smoke test of the real process: stdout carries only JSON-RPC, and a tool call without a running app
     * is a tool error instead of a crash.
     */
    @Test
    void stdioServerAnswersWithoutARunningApp() throws Exception {
        List<String> launch = new ArrayList<>();
        launch.add("-cp");
        launch.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        launch.add(FxPilotMcpServer.class.getName());
        assertStdioServer(launch);
    }

    /** The jar is also a JPMS module: the MCP server must start from the module path, without JavaFX. */
    @Test
    void stdioServerRunsOnTheModulePath() throws Exception {
        assertStdioServer(List.of("--module-path", Path.of("target", "classes").toString(),
                "-m", "io.github.dbuedenbender.fxpilot/" + FxPilotMcpServer.class.getName()));
    }

    @Test
    void moduleExportsOnlyTheApiPackages() {
        ModuleDescriptor module = ModuleFinder.of(Path.of("target", "classes"))
                .find("io.github.dbuedenbender.fxpilot").orElseThrow().descriptor();
        Set<String> exported = new TreeSet<>();
        for (ModuleDescriptor.Exports e : module.exports()) {
            exported.add(e.source());
        }
        assertEquals(Set.of("io.github.dbuedenbender.fxpilot", "io.github.dbuedenbender.fxpilot.mcp"), exported);
        assertTrue(module.packages().contains("io.github.dbuedenbender.fxpilot.internal"));
    }

    private static void assertStdioServer(List<String> launch) throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        String unixTmp = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (unixTmp != null) {
            command.add("-Djdk.net.unixdomain.tmpdir=" + unixTmp);
        }
        command.addAll(launch);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("FXPILOT_URL", "http://127.0.0.1:" + freePort);
        pb.environment().remove("FXPILOT_TOKEN");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        try (Writer in = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
             BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n");
            in.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
            in.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n");
            in.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"fx_health\",\"arguments\":{}}}\n");
            in.flush();
            Map<?, ?> init = (Map<?, ?>) Json.parse(out.readLine());
            assertEquals(1L, init.get("id"));
            assertEquals("2025-06-18", ((Map<?, ?>) init.get("result")).get("protocolVersion"));
            Map<?, ?> list = (Map<?, ?>) Json.parse(out.readLine());
            assertEquals(2L, list.get("id"));
            assertTrue(((List<?>) ((Map<?, ?>) list.get("result")).get("tools")).size() > 10);
            Map<?, ?> call = (Map<?, ?>) Json.parse(out.readLine());
            assertEquals(3L, call.get("id"));
            Map<?, ?> result = (Map<?, ?>) call.get("result");
            assertEquals(true, result.get("isError"));
            String text = Results.textOf(Results.asMap(result));
            assertTrue(text.contains("Cannot reach the app") || text.contains("cannot create the HTTP client"), text);
        } finally {
            p.destroy();
            p.waitFor(10, TimeUnit.SECONDS);
        }
    }
}

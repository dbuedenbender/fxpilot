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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stdio loop must answer protocol errors and keep running for the next request. */
class McpServerStdioTest {

    @Test
    void answersProtocolErrorsWithoutStopping() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String input = "\n" // blank lines are ignored
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n" // notification: no reply
                + "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n" // client response: no reply
                + "{not json\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"fx_nope\"}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"fx_health\",\"arguments\":5}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"initialize\",\"params\":[]}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}\n";
        try (BufferedReader in = new BufferedReader(new StringReader(input));
             PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            FxPilotMcpServer.serve(in, out);
        }

        BufferedReader out = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8));

        Map<?, ?> parseError = response(out);
        assertNull(parseError.get("id"));
        assertEquals(-32700, errorCode(parseError), "malformed JSON is a parse error");

        Map<?, ?> unknownMethod = response(out);
        assertEquals(1L, unknownMethod.get("id"));
        assertEquals(-32601, errorCode(unknownMethod), "unknown methods are method-not-found");

        Map<?, ?> ping = response(out);
        assertEquals(2L, ping.get("id"));
        assertTrue(((Map<?, ?>) ping.get("result")).isEmpty());

        Map<?, ?> unknownTool = response(out);
        assertEquals(3L, unknownTool.get("id"));
        assertEquals(-32602, errorCode(unknownTool));
        assertTrue(String.valueOf(errorMessage(unknownTool)).contains("Unknown tool"));

        Map<?, ?> badArguments = response(out);
        assertEquals(4L, badArguments.get("id"));
        assertEquals(-32600, errorCode(badArguments));
        assertTrue(String.valueOf(errorMessage(badArguments)).contains("expected JSON object"));

        Map<?, ?> badParams = response(out);
        assertEquals(5L, badParams.get("id"));
        assertEquals(-32600, errorCode(badParams));

        Map<?, ?> list = response(out);
        assertEquals(6L, list.get("id"));
        assertTrue(((List<?>) ((Map<?, ?>) list.get("result")).get("tools")).size() > 10);

        assertNull(out.readLine(), "the server must not send unsolicited lines");
    }

    @Test
    void replayWithoutAPlanPrintsUsageAndExitsWithTwo() throws Exception {
        ProcessBuilder pb = server();
        pb.command().add("--replay");
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals(2, p.exitValue());
        assertTrue(stderr.contains("Usage: java -jar fxpilot.jar --replay"), stderr);
    }

    private static ProcessBuilder server() {
        List<String> command = javaCommand();
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(FxPilotMcpServer.class.getName());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("FXPILOT_URL", "http://127.0.0.1:1");
        pb.environment().remove("FXPILOT_TOKEN");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb;
    }

    private static List<String> javaCommand() {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        return command;
    }

    private static Map<?, ?> response(BufferedReader out) throws Exception {
        String line = out.readLine();
        assertTrue(line != null, "server closed stdout before answering");
        return (Map<?, ?>) Json.parse(line);
    }

    private static int errorCode(Map<?, ?> response) {
        return ((Number) ((Map<?, ?>) response.get("error")).get("code")).intValue();
    }

    private static Object errorMessage(Map<?, ?> response) {
        return ((Map<?, ?>) response.get("error")).get("message");
    }
}

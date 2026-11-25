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
import io.github.dbuedenbender.fxpilot.FxPilotServer;
import io.github.dbuedenbender.fxpilot.TestEndpoint;
import io.github.dbuedenbender.fxpilot.TestUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP tools against the real endpoint and the headless fixture UI. The endpoint runs with
 * {@code token=auto}, so these tests also cover the token file exchange.
 */
class McpToolsUiTest {

    @TempDir
    Path tokenDir;

    @TempDir
    Path work;

    private int port;

    @BeforeEach
    void start() throws Exception {
        System.setProperty("fxpilot.tokenDir", tokenDir.toString());
        TestUi.show();
        FxPilotServer.start(0, "auto");
        port = TestEndpoint.port();
        assertTrue(Files.exists(tokenDir.resolve(port + ".token")));
    }

    @AfterEach
    void stop() {
        FxPilotServer.stop();
        System.clearProperty("fxpilot.tokenDir");
        ToolCatalog.configure(ToolCatalog.Profile.ALL, true);
    }

    private Map<String, Object> call(String tool, Object... kv) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        args.put("port", port);
        return ToolCatalog.callTool(tool, args);
    }

    private String ok(String tool, Object... kv) throws Exception {
        Map<String, Object> r = call(tool, kv);
        assertFalse(Boolean.TRUE.equals(r.get("isError")), tool + ": " + Results.textOf(r));
        return Results.textOf(r);
    }

    private String status() throws Exception {
        String node = ok("fx_get_node", "selector", "status");
        Map<?, ?> m = (Map<?, ?>) Json.parse(node);
        return String.valueOf(m.get("text"));
    }

    @Test
    void healthUsesTheTokenFromTheTokenFile() throws Exception {
        assertTrue(ok("fx_health").contains(TestUi.TITLE));
    }

    @Test
    void wrongTokenIsReported() throws Exception {
        Files.writeString(tokenDir.resolve(port + ".token"), "wrong");
        Map<String, Object> r = call("fx_health");
        assertEquals(true, r.get("isError"));
        assertTrue(Results.textOf(r).contains("UNAUTHORIZED"), Results.textOf(r));
    }

    @Test
    void coreToolsDriveTheUi() throws Exception {
        ok("fx_set_text", "selector", "nameField", "text", "Sample Inc.");
        ok("fx_click", "selector", "okButton");
        assertEquals("Saved Sample Inc.", status());
        ok("fx_select", "selector", "country", "value", "AT");
        ok("fx_set_checked", "selector", "agree", "checked", true);
        ok("fx_assert", "selector", "agree", "checked", true);
        ok("fx_assert", "selector", "country", "value", "AT");
        ok("fx_wait_for", "selector", "status", "text", "agree=true", "timeoutMs", 2000);
        assertTrue(ok("fx_find", "selector", "text=Dup").contains("\"count\":2"));
    }

    @Test
    void assertReportsMismatches() throws Exception {
        Map<String, Object> r = call("fx_assert", "selector", "nameField", "text", "nope");
        assertEquals(true, r.get("isError"));
        assertTrue(Results.textOf(r).startsWith("FAIL"), Results.textOf(r));
        Map<String, Object> count = call("fx_assert", "selector", "text=Dup", "count", 3);
        assertTrue(Results.textOf(count).contains("count expected 3 but was 2"), Results.textOf(count));
    }

    @Test
    void endpointErrorCodesReachTheClient() throws Exception {
        Map<String, Object> r = call("fx_click", "selector", "noSuchNode");
        assertEquals(true, r.get("isError"));
        assertTrue(Results.textOf(r).startsWith("NODE_NOT_FOUND"), Results.textOf(r));
    }

    @Test
    void screenshotReturnsAnImage() throws Exception {
        Map<String, Object> r = call("fx_screenshot", "selector", "okButton");
        Map<?, ?> content = (Map<?, ?>) ((List<?>) r.get("content")).get(0);
        assertEquals("image", content.get("type"));
        assertEquals("image/png", content.get("mimeType"));
        Path baseline = work.resolve("ok.png");
        assertTrue(ok("fx_screenshot_diff", "selector", "okButton", "baseline", baseline.toString()).startsWith("BASELINE"));
        assertTrue(ok("fx_screenshot_diff", "selector", "okButton", "baseline", baseline.toString()).startsWith("PASS"));
    }

    @Test
    void batchRunsCallsInOrder() throws Exception {
        String summary = ok("fx_batch", "calls", List.of(
                Map.of("tool", "fx_set_text", "args", Map.of("selector", "nameField", "text", "B")),
                Map.of("tool", "fx_click", "args", Map.of("selector", "okButton"))));
        assertTrue(summary.contains("failed=0"), summary);
        assertEquals("Saved B", status());
    }

    @Test
    void recordedPlanReplaysAgainstAFreshUi() throws Exception {
        Path plan = work.resolve("plan.json");
        ok("fx_record_start", "name", "checkout");
        ok("fx_set_expanded", "selector", "orders", "value", "Order 1001");
        ok("fx_select", "selector", "orders", "value", "Item A");
        ok("fx_set_text", "selector", "nameField", "text", "Sample Inc.");
        ok("fx_click", "selector", "okButton");
        ok("fx_assert", "selector", "status", "text", "Saved Sample Inc.");
        ok("fx_get_view");
        ok("fx_record_stop", "file", plan.toString());
        String json = Files.readString(plan);
        assertTrue(json.contains("fx_set_expanded"), json);
        assertFalse(json.contains("fx_get_view"), "reads are not recorded");
        assertFalse(json.contains("\"port\""), "plans are portable");

        TestUi.show();
        assertEquals("", status());
        String replay = ok("fx_replay", "file", plan.toString(), "delayMs", 0);
        assertTrue(replay.contains("status=PASS"), replay);
        assertTrue(replay.contains("passed=5"), replay);
        assertEquals("Saved Sample Inc.", status());
    }

    @Test
    void aPlanThatReplaysItselfFailsInsteadOfRecursing() throws Exception {
        Path plan = work.resolve("loop.json");
        Files.writeString(plan, "{\"steps\":[{\"tool\":\"fx_replay\",\"args\":{\"file\":"
                + Json.write(plan.toString()) + "}}]}");
        Map<String, Object> r = call("fx_replay", "file", plan.toString(), "delayMs", 0);
        assertEquals(true, r.get("isError"));
        assertTrue(Results.textOf(r).contains("cannot run inside a replayed plan"), Results.textOf(r));
    }

    @Test
    void withoutDangerousFilesAreOnlyWrittenBelowTheWorkingDirectory() throws Exception {
        ToolCatalog.configure(ToolCatalog.Profile.ALL, false);
        ok("fx_record_start");
        ok("fx_click", "selector", "okButton");
        Map<String, Object> outside = call("fx_record_stop", "file", work.resolve("plan.json").toString());
        assertEquals(true, outside.get("isError"));
        assertTrue(Results.textOf(outside).contains("FXPILOT_DANGEROUS=false"), Results.textOf(outside));
        Map<String, Object> diff = call("fx_screenshot_diff", "selector", "okButton",
                "baseline", work.resolve("b.png").toString());
        assertEquals(true, diff.get("isError"));
        ok("fx_record_start");
        Path inside = Path.of("target", "mcp-test", "plan.json");
        ok("fx_record_stop", "file", inside.toString());
        assertTrue(Files.exists(inside));
    }

    @Test
    void execReachesOperationsWithoutADedicatedTool() throws Exception {
        ToolCatalog.configure(ToolCatalog.Profile.CORE, true);
        assertEquals(true, call("fx_table", "selector", "customers", "action", "cell").get("isError"));
        String cell = ok("fx_exec", "op", "tableCell", "args", Map.of("selector", "customers", "row", 1, "column", "Name"));
        assertTrue(cell.contains("Acme"), cell);
    }
}

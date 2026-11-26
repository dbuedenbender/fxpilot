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
    void passwordFieldTextIsNotRecordedAndReplaysFromVariables() throws Exception {
        Path plan = work.resolve("login.json");
        ok("fx_record_start", "name", "login");
        ok("fx_set_text", "selector", "password", "text", "s3cret-one");
        ok("fx_type", "selector", "password", "text", "-two");
        ok("fx_set_text", "selector", "#password", "text", "s3cret-one");
        ok("fx_exec", "op", "textAction", "args", Map.of("selector", "password", "action", "append", "value", "!"));
        ok("fx_set_text", "selector", "nameField", "text", "Sample Inc.");
        String stopped = ok("fx_record_stop", "file", plan.toString());
        String json = Files.readString(plan);
        assertFalse(json.contains("s3cret") || json.contains("-two"), json);
        assertEquals(List.of("password", "password2", "password3"), ((Map<?, ?>) Json.parse(json)).get("secrets"), json);
        assertTrue(json.contains("Sample Inc."), "text of other fields stays in the plan: " + json);
        assertTrue(stopped.contains("${password}"), stopped);

        TestUi.show();
        Map<String, Object> missing = call("fx_replay", "file", plan.toString(), "delayMs", 0,
                "variables", Map.of("password", "a"));
        assertEquals(true, missing.get("isError"));
        assertTrue(Results.textOf(missing).contains("needs the password variable(s) password2, password3"),
                Results.textOf(missing));
        assertEquals("geheim", passwordText(), "nothing ran");

        String replay = ok("fx_replay", "file", plan.toString(), "delayMs", 0,
                "variables", Map.of("password", "a", "password2", "b", "password3", "c"));
        assertTrue(replay.contains("passed=5"), replay);
        assertEquals("ac", passwordText());
    }

    private static String passwordText() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> text = new java.util.concurrent.atomic.AtomicReference<>();
        TestUi.onFx(() -> {
            for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                javafx.scene.Node n = w.getScene() == null ? null : w.getScene().lookup("#password");
                if (n instanceof javafx.scene.control.PasswordField) {
                    text.set(((javafx.scene.control.PasswordField) n).getText());
                }
            }
        });
        return text.get();
    }

    @Test
    void aReplayInsideARecordingAddsNoStrayAsserts() throws Exception {
        Path plan = work.resolve("inner.json");
        Files.writeString(plan, "{\"steps\":[{\"tool\":\"fx_click\",\"args\":{\"selector\":\"okButton\"}},"
                + "{\"tool\":\"fx_assert\",\"args\":{\"selector\":\"okButton\"}}]}");
        Path outer = work.resolve("outer.json");
        ok("fx_record_start");
        assertTrue(ok("fx_replay", "file", plan.toString(), "delayMs", 0).contains("status=PASS"));
        ok("fx_record_stop", "file", outer.toString());
        String json = Files.readString(outer);
        assertFalse(json.contains("fx_assert"), "replayed asserts would check steps the plan does not contain: " + json);
    }

    @Test
    void withoutDangerousPlansAndBaselinesAreOnlyReadBelowTheWorkingDirectory() throws Exception {
        Path plan = work.resolve("outside.json");
        Files.writeString(plan, "{\"steps\":[]}");
        Path baseline = work.resolve("outside.png");
        Files.write(baseline, new byte[]{1, 2, 3});
        ToolCatalog.configure(ToolCatalog.Profile.ALL, false);
        Map<String, Object> replay = call("fx_replay", "file", plan.toString());
        assertEquals(true, replay.get("isError"));
        assertTrue(Results.textOf(replay).contains("Reading"), Results.textOf(replay));
        Map<String, Object> diff = call("fx_screenshot_diff", "selector", "okButton", "baseline", baseline.toString());
        assertEquals(true, diff.get("isError"));
        assertTrue(Results.textOf(diff).contains("Reading"), Results.textOf(diff));
    }

    @Test
    void aSymbolicLinkCannotLeadAWriteOutOfTheWorkingDirectory() throws Exception {
        Path link = Path.of("target", "mcp-test", "escape-link");
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        try {
            Files.createSymbolicLink(link, work.toAbsolutePath());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links not available here: " + e);
        }
        try {
            ToolCatalog.configure(ToolCatalog.Profile.ALL, false);
            ok("fx_record_start");
            Map<String, Object> r = call("fx_record_stop", "file", link.resolve("plan.json").toString());
            assertEquals(true, r.get("isError"), Results.textOf(r));
            assertFalse(Files.exists(work.resolve("plan.json")));
        } finally {
            Files.deleteIfExists(link);
        }
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

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument validation and batch handling of the MCP tool wrappers, without a running app. */
class ToolHandlersTest {

    @Test
    void retryRejectsMissingNestedAndUnknownTools() {
        rpcError(() -> ToolHandlers.retryTool(Map.of()));
        rpcError(() -> ToolHandlers.retryTool(Map.of("tool", " ")));
        rpcError(() -> ToolHandlers.retryTool(Map.of("tool", "fx_retry")));
        rpcError(() -> ToolHandlers.retryTool(Map.of("tool", "fx_unknown")));
    }

    @Test
    void execGenericRequiresAnOperation() {
        rpcError(() -> ToolHandlers.execGeneric(Map.of(), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.execGeneric(Map.of("op", " "), "http://127.0.0.1:1"));
    }

    @Test
    void selectionTablePropertyWebMediaAndCanvasValidateTheirAction() {
        rpcError(() -> ToolHandlers.selection(Map.of(), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.selection(Map.of("action", "nope"), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.table(Map.of(), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.table(Map.of("action", "nope"), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.property(Map.of(), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.web(Map.of(), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.web(Map.of("action", "nope"), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.media(Map.of(), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.canvas(Map.of(), "http://127.0.0.1:1"));
    }

    @Test
    void menuAndWindowWaitsRequireTheirTarget() {
        rpcError(() -> ToolHandlers.clickMenu(Map.of(), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.waitForWindow(Map.of(), "http://127.0.0.1:1"));
        rpcError(() -> ToolHandlers.waitForWindow(Map.of("title", " "), "http://127.0.0.1:1"));
    }

    @Test
    void screenshotDiffRequiresABaseline() {
        rpcError(() -> ToolHandlers.screenshotDiff(Map.of(), "http://127.0.0.1:1"));
    }

    @Test
    void batchRejectsNonArraysAndReportsEachFailure() throws Exception {
        rpcError(() -> ToolHandlers.batch(Map.of("calls", "not-a-list")));

        Map<String, Object> empty = ToolHandlers.batch(Map.of("calls", List.of()));
        assertFalse(Boolean.TRUE.equals(empty.get("isError")));
        assertTrue(Results.textOf(empty).contains("failed=0"), Results.textOf(empty));

        Map<String, Object> nested = ToolHandlers.batch(Map.of("calls", List.of(Map.of("tool", "fx_batch"))));
        assertEquals(true, nested.get("isError"));
        assertTrue(Results.textOf(nested).contains("nested fx_batch is not supported"), Results.textOf(nested));

        Map<String, Object> unknown = ToolHandlers.batch(Map.of("calls",
                List.of(Map.of("tool", "fx_unknown", "args", Map.of()), Map.of("tool", "fx_also_unknown")),
                "port", 1));
        assertEquals(true, unknown.get("isError"));
        assertTrue(Results.textOf(unknown).contains("failed=2"), Results.textOf(unknown));
        assertTrue(Results.textOf(unknown).contains("Unknown tool"), Results.textOf(unknown));
    }

    @Test
    void waitForTurnsAnUnreachableAppIntoAToolError() throws Exception {
        Map<String, Object> result = ToolHandlers.waitFor(
                Map.of("selector", "#x", "timeoutMs", 0), "http://127.0.0.1:1");
        assertEquals(true, result.get("isError"));
        assertTrue(Results.textOf(result).contains("Timeout after 0 ms"), Results.textOf(result));
    }

    @Test
    void waitTimeoutsAreCappedSoOneCallCannotBlockTheServer() {
        assertEquals(5000L, ToolHandlers.timeoutMs(Map.of()));
        assertEquals(0L, ToolHandlers.timeoutMs(Map.of("timeoutMs", -5)));
        assertEquals(ToolHandlers.MAX_TIMEOUT_MS, ToolHandlers.timeoutMs(Map.of("timeoutMs", Long.MAX_VALUE)));
    }

    @Test
    void selectorAndPickHelpersKeepOnlyPresentArguments() {
        Map<String, Object> source = Map.of("selector", "#x", "within", "#p", "nth", 2, "extra", "v");
        Map<String, Object> selected = ToolHandlers.withSelector(source, "extra", "missing");
        assertEquals("#x", selected.get("selector"));
        assertEquals("#p", selected.get("within"));
        assertEquals(2, selected.get("nth"));
        assertEquals("v", selected.get("extra"));
        assertFalse(selected.containsKey("missing"));

        Map<String, Object> picked = ToolHandlers.pick(source, "selector", "missing");
        assertEquals(Map.of("selector", "#x"), picked);
    }

    private static void rpcError(Executable executable) {
        assertThrows(RpcError.class, executable);
    }
}

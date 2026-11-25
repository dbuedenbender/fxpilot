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

import io.github.dbuedenbender.fxpilot.mcp.ToolCatalog.Entry;
import io.github.dbuedenbender.fxpilot.mcp.ToolCatalog.Kind;
import io.github.dbuedenbender.fxpilot.mcp.ToolCatalog.Profile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogTest {

    @AfterEach
    void restoreDefaults() {
        ToolCatalog.configure(Profile.ALL, true);
    }

    private static Set<String> listedNames() {
        Set<String> names = new HashSet<>();
        for (Object o : ToolCatalog.list()) {
            names.add(String.valueOf(((Map<?, ?>) o).get("name")));
        }
        return names;
    }

    @Test
    void everyToolHasADefinitionWithSchemaAndAnnotations() {
        for (Entry e : ToolCatalog.entries()) {
            Map<String, Object> d = e.definition;
            assertEquals(e.name, d.get("name"));
            assertTrue(String.valueOf(d.get("description")).length() > 10, e.name);
            Map<?, ?> schema = (Map<?, ?>) d.get("inputSchema");
            assertEquals("object", schema.get("type"));
            assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("port"), e.name);
            Map<?, ?> annotations = (Map<?, ?>) d.get("annotations");
            assertEquals(e.kind == Kind.READ, annotations.get("readOnlyHint"), e.name);
            assertEquals(false, annotations.get("openWorldHint"));
        }
        assertEquals(57, ToolCatalog.entries().size(), "tool count changed; update README and the profiles");
    }

    @Test
    void annotationsMatchWhatTheToolsDo() {
        Map<?, ?> view = annotations("fx_get_view");
        assertEquals(true, view.get("readOnlyHint"));
        assertEquals(false, view.get("destructiveHint"));
        Map<?, ?> click = annotations("fx_click");
        assertEquals(false, click.get("readOnlyHint"));
        assertEquals(true, click.get("destructiveHint"));
        assertEquals(false, annotations("fx_record_start").get("destructiveHint"));
    }

    private static Map<?, ?> annotations(String tool) {
        for (Entry e : ToolCatalog.entries()) {
            if (e.name.equals(tool)) {
                return (Map<?, ?>) e.definition.get("annotations");
            }
        }
        throw new AssertionError(tool);
    }

    @Test
    void profilesAreNestedAndCoreStaysSmall() {
        ToolCatalog.configure(Profile.CORE, true);
        Set<String> core = listedNames();
        ToolCatalog.configure(Profile.STANDARD, true);
        Set<String> standard = listedNames();
        ToolCatalog.configure(Profile.ALL, true);
        Set<String> all = listedNames();
        assertTrue(standard.containsAll(core));
        assertTrue(all.containsAll(standard));
        assertTrue(core.size() <= 20, "core has " + core.size() + " tools");
        assertTrue(core.contains("fx_exec"), "fx_exec keeps every operation reachable");
        assertTrue(core.contains("fx_health") && core.contains("fx_get_view") && core.contains("fx_click"));
        assertEquals(57, all.size());
    }

    @Test
    void parsesProfileNames() {
        assertEquals(Profile.ALL, ToolCatalog.parseProfile(null));
        assertEquals(Profile.CORE, ToolCatalog.parseProfile(" core "));
        assertEquals(Profile.STANDARD, ToolCatalog.parseProfile("Standard"));
        assertEquals(Profile.ALL, ToolCatalog.parseProfile("everything"));
    }

    @Test
    void hiddenToolsAreRejectedWithAHint() throws Exception {
        ToolCatalog.configure(Profile.CORE, true);
        Map<String, Object> r = ToolCatalog.callTool("fx_web", Map.of("selector", "x", "action", "html"));
        assertEquals(true, r.get("isError"));
        assertTrue(Results.textOf(r).contains("FXPILOT_TOOLS=core"), Results.textOf(r));
    }

    @Test
    void dangerousToolsAreHiddenAndDangerousSubActionsBlocked() throws Exception {
        ToolCatalog.configure(Profile.ALL, false);
        Set<String> names = listedNames();
        assertFalse(names.contains("fx_web") || names.contains("fx_property") || names.contains("fx_image"));
        assertTrue(names.contains("fx_window"));
        assertTrue(Results.textOf(ToolCatalog.callTool("fx_web", Map.of("action", "execute"))).contains("FXPILOT_DANGEROUS"));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "webExecute")));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "webQuery")));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "webHtml")));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "webClick")));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "propertySet")));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_window", Map.of("action", "close")));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "window", "args", Map.of("action", "close"))));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_tab", Map.of("action", "close")));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "tab", "args", Map.of("action", "close"))));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_clipboard", Map.of("action", "get")));
        assertNotNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "clipboardGet")));
        assertNull(ToolCatalog.blockedByPolicy("fx_clipboard", Map.of("action", "set")));
        assertNull(ToolCatalog.blockedByPolicy("fx_tab", Map.of("action", "select")));
        assertNull(ToolCatalog.blockedByPolicy("fx_window", Map.of("action", "resize")));
        assertNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "tableCell")));
        assertNotNull(ToolCatalog.dangerousBlocked("fx_web", Map.of("action", "html")), "replay must not bypass it");
        assertNull(ToolCatalog.dangerousBlocked("fx_click", Map.of()));
        ToolCatalog.configure(Profile.ALL, true);
        assertNull(ToolCatalog.blockedByPolicy("fx_exec", Map.of("op", "webExecute")));
        assertNull(ToolCatalog.dangerousBlocked("fx_web", Map.of("action", "execute")));
    }

    @Test
    void recordingCoversMutatingToolsOnly() {
        assertTrue(ToolCatalog.isRecorded("fx_click"));
        assertTrue(ToolCatalog.isRecorded("fx_set_expanded"), "expanding changes what later steps see");
        assertTrue(ToolCatalog.isRecorded("fx_wait_for"));
        assertFalse(ToolCatalog.isRecorded("fx_get_view"));
        assertFalse(ToolCatalog.isRecorded("fx_assert"), "fx_assert records itself only when it passes");
        assertFalse(ToolCatalog.isRecorded("fx_batch"), "batch steps are recorded one by one");
        for (Entry e : ToolCatalog.entries()) {
            if (e.kind == Kind.READ && e.recorded) {
                assertEquals("fx_wait_for", e.name);
            }
        }
    }

    // ---- the MCP server is a client of the endpoint; both sides must agree on these sets

    @Test
    void readOperationsMatchTheEndpoint() throws Exception {
        assertEquals(endpointOperations("READ"), ToolHandlers.EXEC_READ_OPS);
    }

    @Test
    void dangerousOperationsMatchTheEndpoint() throws Exception {
        assertEquals(endpointOperations("DANGEROUS"), ToolCatalog.DANGEROUS_OPS);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<String> endpointOperations(String kind) throws Exception {
        Class<?> ops = Class.forName("io.github.dbuedenbender.fxpilot.Operations");
        Class kindType = Class.forName("io.github.dbuedenbender.fxpilot.Operations$Kind");
        Method names = ops.getDeclaredMethod("names", kindType);
        names.setAccessible(true);
        return (Set<String>) names.invoke(null, Enum.valueOf(kindType, kind));
    }

    @Test
    void exampleConfigurationsOnlyNameExistingTools() {
        Set<String> all = new HashSet<>();
        for (Entry e : ToolCatalog.entries()) {
            all.add(e.name);
        }
        for (String t : List.of("fx_health", "fx_get_view", "fx_click", "fx_record_start", "fx_replay")) {
            assertTrue(all.contains(t), t);
        }
    }
}

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

import javafx.scene.Node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static io.github.dbuedenbender.fxpilot.Params.action;
import static io.github.dbuedenbender.fxpilot.Params.intVal;
import static io.github.dbuedenbender.fxpilot.Params.req;

/**
 * Registry of the extended operations behind {@code /exec}. Each entry says whether it only reads
 * (allowed with GET and in read-only mode) and whether it is dangerous (arbitrary code, reflective
 * setters, file writes), so the endpoint checks both in one place.
 */
final class Operations {

    enum Kind { READ, WRITE, DANGEROUS }

    static final class Operation {
        final String name;
        final Kind kind;
        final Function<Map<String, String>, Map<String, Object>> handler;

        Operation(String name, Kind kind, Function<Map<String, String>, Map<String, Object>> handler) {
            this.name = name;
            this.kind = kind;
            this.handler = handler;
        }
    }

    private static final Map<String, Operation> OPS = new LinkedHashMap<>();

    static {
        // keyboard, clipboard, mouse
        write("pressKey", InputOps::pressKey);
        write("hotkey", InputOps::hotkey);
        read("clipboardGet", p -> InputOps.clipboardGet());
        write("clipboardSet", InputOps::clipboardSet);
        write("click", InputOps::click);
        write("wheel", InputOps::wheel);
        write("scrollTo", p -> action(p, n -> InputOps.scrollTo(n, p)));
        write("drag", InputOps::drag);
        write("focus", p -> action(p, Node::requestFocus));
        write("popup", p -> action(p, n -> InputOps.popup(n, p)));
        write("step", p -> action(p, n -> InputOps.step(n, p)));
        // text and selection
        write("textAction", p -> action(p, n -> SelectionOps.textAction(n, p.get("action"), p.get("value"),
                intVal(p, "start", 0), intVal(p, "end", -1))));
        write("selectRows", p -> action(p, n -> SelectionOps.selectRows(n, p.get("indices"))));
        write("selectAll", p -> action(p, SelectionOps::selectAll));
        write("clearSelection", p -> action(p, SelectionOps::clearSelection));
        // tables and trees
        read("tableCell", TableOps::tableCell);
        write("tableEdit", p -> action(p, n -> TableOps.tableEdit(n, p)));
        write("tableCellSet", p -> action(p, n -> TableOps.tableCellSet(n, p)));
        write("tableSort", p -> action(p, n -> TableOps.tableSort(n, p)));
        write("tableColumn", p -> action(p, n -> TableOps.tableColumn(n, p)));
        write("tableSelect", p -> action(p, n -> TableOps.tableSelect(n, p)));
        write("tableScroll", p -> action(p, n -> TableOps.tableScroll(n, p)));
        write("tree", p -> action(p, n -> NavigationOps.treeAction(n, p)));
        // menus, tabs, windows
        write("menu", NavigationOps::menuAction);
        write("contextMenu", NavigationOps::contextMenu);
        write("contextMenuItem", NavigationOps::contextMenuItem);
        write("tab", p -> action(p, n -> NavigationOps.tabAction(n, p)));
        write("accordion", p -> action(p, n -> NavigationOps.accordionAction(n, p)));
        write("pagination", p -> action(p, n -> NavigationOps.paginationAction(n, p)));
        write("window", WindowOps::windowAction);
        // properties and rich content
        read("propertyGet", PropertyOps::propertyGet);
        dangerous("propertySet", PropertyOps::propertySet);
        dangerous("webExecute", ContentOps::webExecute);
        read("webQuery", ContentOps::webQuery);
        write("webClick", p -> {
            Policy.requireDangerous("WebView JavaScript");
            return action(p, n -> ContentOps.webClick(n, req(p, "css")));
        });
        read("webHtml", ContentOps::webHtml);
        read("chart", ContentOps::chartData);
        read("canvasPixel", ContentOps::canvasPixel);
        write("canvasClick", p -> action(p, n -> ContentOps.canvasClick(n, p)));
        write("media", p -> action(p, n -> ContentOps.mediaAction(n, p)));
        read("mediaStatus", p -> FxThread.query(() -> NodeInfo.info(Params.node(p))));
        dangerous("image", ContentOps::imageAction);
        read("tooltip", ContentOps::tooltip);
        read("idle", p -> FxThread.idle());
    }

    private Operations() {
    }

    private static void read(String name, Function<Map<String, String>, Map<String, Object>> handler) {
        OPS.put(name, new Operation(name, Kind.READ, handler));
    }

    private static void write(String name, Function<Map<String, String>, Map<String, Object>> handler) {
        OPS.put(name, new Operation(name, Kind.WRITE, handler));
    }

    private static void dangerous(String name, Function<Map<String, String>, Map<String, Object>> handler) {
        OPS.put(name, new Operation(name, Kind.DANGEROUS, handler));
    }

    static Operation get(String name) {
        if (name == null || name.isBlank()) {
            throw new FxPilotException(400, Codes.INVALID_OPERATION, "op is required");
        }
        Operation op = OPS.get(name);
        if (op == null) {
            throw new FxPilotException(400, Codes.INVALID_OPERATION, "unknown operation '" + name + "'");
        }
        return op;
    }

    static Map<String, Object> exec(String name, Map<String, String> params) {
        return get(name).handler.apply(params);
    }

    /** Names of the operations of one kind, sorted. */
    static Set<String> names(Kind kind) {
        Set<String> names = new TreeSet<>();
        for (Operation op : OPS.values()) {
            if (op.kind == kind) {
                names.add(op.name);
            }
        }
        return Collections.unmodifiableSet(names);
    }
}

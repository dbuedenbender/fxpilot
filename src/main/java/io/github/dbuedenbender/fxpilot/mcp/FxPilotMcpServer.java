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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * MCP server (stdio, JSON-RPC 2.0) that exposes the FxPilot endpoint of a running JavaFX
 * application as tools. No dependencies; run with {@code java -jar fxpilot.jar} (JDK 11+).
 *
 * <p>Environment: FXPILOT_URL (default http://127.0.0.1:8765), FXPILOT_TOKEN (optional; without it the
 * token file of an endpoint started with {@code token=auto} is used), FXPILOT_TOOLS
 * ({@code core|standard|all}, default {@code standard}) and FXPILOT_DANGEROUS ({@code false} hides fx_web, fx_property
 * and fx_image). Every tool also accepts an optional "port" argument to address another app on 127.0.0.1.
 */
public final class FxPilotMcpServer {

    /** Protocol versions this server implements, newest first. */
    static final List<String> SUPPORTED_PROTOCOLS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    static final String VERSION = serverVersion();

    private FxPilotMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && ("--replay".equals(args[0]) || "-r".equals(args[0]))) {
            System.exit(Recorder.replayMain(args));
        }
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        System.setOut(System.err);
        serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out);
    }

    /**
     * Reads JSON-RPC lines from {@code in} until EOF and writes replies to {@code out}. Requests run one at a
     * time and in order on a worker thread, so tools need no locking; the reader stays free to take a
     * {@code notifications/cancelled}, which interrupts the request it names (or drops it if it has not
     * started). A cancelled request gets no reply, as the MCP specification asks.
     */
    static void serve(BufferedReader in, PrintStream out) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fxpilot-mcp-worker");
            t.setDaemon(true);
            return t;
        });
        Map<String, Future<?>> running = new ConcurrentHashMap<>();
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Object parsed;
                try {
                    parsed = Json.parse(line);
                } catch (Json.ParseException e) {
                    parsed = e;
                }
                String cancelled = cancelledRequest(parsed);
                if (cancelled != null) {
                    Future<?> request = running.remove(cancelled);
                    if (request != null) {
                        request.cancel(true);
                    }
                    continue;
                }
                Object message = parsed;
                String key = requestKey(parsed);
                AtomicReference<FutureTask<Void>> self = new AtomicReference<>();
                FutureTask<Void> task = new FutureTask<>(() -> {
                    try {
                        handle(message, out, self.get()::isCancelled);
                    } finally {
                        if (key != null) {
                            running.remove(key, self.get());
                        }
                    }
                    return null;
                });
                self.set(task);
                if (key != null) {
                    running.put(key, task);
                }
                worker.execute(task);
            }
        } finally {
            // EOF: let the requests already read finish and answer
            worker.shutdown();
            worker.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        }
    }

    /** {@code requestId} of a {@code notifications/cancelled}, as a {@link #requestKey} key; otherwise null. */
    static String cancelledRequest(Object parsed) {
        if (!(parsed instanceof Map) || !"notifications/cancelled".equals(((Map<?, ?>) parsed).get("method"))) {
            return null;
        }
        Object params = ((Map<?, ?>) parsed).get("params");
        Object id = params instanceof Map ? ((Map<?, ?>) params).get("requestId") : null;
        return id == null ? null : Json.write(id);
    }

    /** JSON text of the request id, so that 1 and "1" stay different; null for notifications and errors. */
    private static String requestKey(Object parsed) {
        if (!(parsed instanceof Map) || ((Map<?, ?>) parsed).get("method") == null
                || ((Map<?, ?>) parsed).get("id") == null) {
            return null;
        }
        return Json.write(((Map<?, ?>) parsed).get("id"));
    }

    /** Answers one message read by {@link #serve}; nothing is written once the request was cancelled. */
    private static void handle(Object parsed, PrintStream out, BooleanSupplier cancelled) {
        Object id = null;
        Map<String, Object> response;
        try {
            if (parsed instanceof Json.ParseException) {
                throw (Json.ParseException) parsed;
            }
            if (!(parsed instanceof Map)) {
                throw new RpcError(-32600, "Invalid Request: expected a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) parsed;
            id = msg.get("id");
            Object methodValue = msg.get("method");
            if (methodValue == null) {
                if (msg.containsKey("id") && !msg.containsKey("result") && !msg.containsKey("error")) {
                    throw new RpcError(-32600, "Invalid Request: method is missing");
                }
                return; // a response to a request of ours
            }
            if (!(methodValue instanceof String)) {
                throw new RpcError(-32600, "Invalid Request: method must be a string");
            }
            String method = (String) methodValue;
            if (!msg.containsKey("id")) {
                return; // notification
            }
            Map<String, Object> params = msg.get("params") == null ? Map.of() : Results.asMap(msg.get("params"));
            response = result(id, dispatch(method, params));
        } catch (RpcError e) {
            response = error(id, e.code, e.getMessage());
        } catch (Json.ParseException e) {
            response = error(id, -32700, "Parse error: " + e.getMessage());
        } catch (InterruptedException e) {
            response = error(id, -32603, "interrupted");
        } catch (Exception | StackOverflowError e) {
            // a StackOverflowError (e.g. from a pathological plan) must not end the server
            response = error(id, -32603, e.toString());
        }
        if (!cancelled.getAsBoolean()) {
            out.println(Json.write(response));
        }
    }

    static Object dispatch(String method, Map<String, Object> params) throws Exception {
        switch (method) {
            case "initialize": {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("protocolVersion", negotiate(params.get("protocolVersion")));
                r.put("capabilities", Map.of("tools", Map.of()));
                r.put("serverInfo", Map.of("name", "fxpilot", "version", VERSION));
                r.put("instructions", instructions());
                return r;
            }
            case "ping":
                return Map.of();
            case "tools/list":
                return Map.of("tools", ToolCatalog.list());
            case "tools/call":
                if (!(params.get("name") instanceof String)) {
                    throw new RpcError(-32602, "Invalid params: name must be a string");
                }
                return ToolCatalog.callTool((String) params.get("name"),
                        params.get("arguments") == null ? Map.of() : Results.asMap(params.get("arguments")));
            default:
                throw new RpcError(-32601, "Method not found: " + method);
        }
    }

    /**
     * The client instructions. The tools named here are the everyday ones; when the active profile hides the
     * extended tools, a note says so instead of pointing the model at calls that would be rejected.
     */
    static String instructions() {
        String text = "Drives a running JavaFX application through its local FxPilot endpoint ("
                + EndpointClient.BASE_URL + ", other apps via the optional 'port' argument). Call fx_health first: it returns "
                + "app-specific notes and warnings (e.g. buttons that print or write to production systems) - "
                + "respect them. Then use fx_get_tree or fx_get_view to learn node ids/refs, act with fx_click / fx_set_text / "
                + "fx_select / fx_set_value / fx_set_expanded / fx_set_checked / fx_scroll / fx_click_menu and verify with "
                + "fx_screenshot or fx_assert. Selectors: fx:id (e.g. textField), CSS (#id, .class, Button), "
                + "id=<exact fx:id>[@<n>] for literal ids (e.g. containing dots), accessible=<text>, label=<text>, "
                + "ref=<path from fx_find>, text=<label> (exact, prefers visible nodes) or text*=<part> (substring). "
                + "Use fx_hotkey for shortcuts (CTRL+S), fx_click(button=SECONDARY, mode=mouse) for real mouse paths, "
                + "fx_wheel for scroll events, fx_drag, fx_table, fx_tree, fx_menu, fx_context_menu, fx_window, "
                + "fx_text, fx_property, fx_web, fx_chart, fx_canvas and fx_media for component-specific operations. "
                + "Errors contain a stable code such as "
                + "NODE_NOT_FOUND; use it to decide the next step. When an action matches several nodes the "
                + "result contains matches>1 and a warning - call fx_find to list them, then use nth=<index>, "
                + "within=<scope selector> or a more specific selector. Use fx_batch to run several calls in one "
                + "request. For regression checks, record a run with "
                + "fx_record_start/fx_record_stop and replay it with fx_replay or "
                + "'java -jar fxpilot.jar --replay plan.json' in CI.";
        ToolCatalog.Profile profile = ToolCatalog.profile();
        if (profile == ToolCatalog.Profile.ALL) {
            return text;
        }
        return text + " This session offers the " + profile.name().toLowerCase(Locale.ROOT)
                + " tool profile, so tools outside it are hidden: set FXPILOT_TOOLS=all to use fx_robot_*, fx_clipboard, "
                + "fx_canvas, fx_web, fx_chart, fx_media, fx_image and fx_property directly; fx_exec reaches every "
                + "operation either way.";
    }

    /**
     * The client's version if this server implements it, otherwise the newest one it does; the client
     * then decides whether it can continue (MCP lifecycle, version negotiation).
     */
    static String negotiate(Object requested) {
        String v = requested == null ? null : String.valueOf(requested);
        return v != null && SUPPORTED_PROTOCOLS.contains(v) ? v : SUPPORTED_PROTOCOLS.get(0);
    }

    private static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message == null ? "error" : message));
        return m;
    }

    /** Implementation-Version from the jar manifest, "dev" when running without one. */
    private static String serverVersion() {
        Package pkg = FxPilotMcpServer.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }
}

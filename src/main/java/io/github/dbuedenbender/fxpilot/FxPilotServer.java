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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dbuedenbender.fxpilot.FxPilotEngine.FxPilotException;
import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Local HTTP endpoint that lets an FxPilot client (the FxPilot MCP server) inspect and drive
 * a JavaFX UI.
 *
 * <p>Usage as library: call {@link #startIfEnabled()} after {@code stage.show()} and {@link #stop()} in
 * {@code Application.stop()}. Usage without code changes: {@code -javaagent:fxpilot.jar=port=8765}.
 *
 * <p>Disabled unless the system property {@code fxpilot.port} (or env {@code FXPILOT_PORT}) is set.
 * Binds to the loopback interface only. If {@code fxpilot.token} / {@code FXPILOT_TOKEN} is set,
 * every request must send it in the {@code X-FxPilot-Token} header. {@link #setNotes(String)} or
 * {@code -Dfxpilot.notes=...} publish application-specific hints (e.g. dangerous buttons) via /health.
 */
public final class FxPilotServer {

    private static FxPilotServer instance;
    private static volatile String notes;

    private static final int MAX_BODY_BYTES = 1 << 20;
    private static final Set<String> READ_OPS = Set.of(
            "clipboardGet", "propertyGet", "chart", "canvasPixel", "webQuery", "webHtml",
            "tooltip", "idle", "tableCell", "mediaStatus");

    private final HttpServer server;
    private final ExecutorService executor;
    private final String token;
    private final boolean readOnly;

    private FxPilotServer(int port, String token) throws IOException {
        this.token = token;
        this.readOnly = Boolean.parseBoolean(config("fxpilot.readonly", "FXPILOT_READONLY"));
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fxpilot-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    /**
     * HttpServer creates its "HTTP-Dispatcher" thread in start() without setting the daemon flag, so the
     * thread inherits it from the caller. Starting from a daemon thread keeps the endpoint from holding the
     * JVM open after the application has ended without calling {@link #stop()} (typical in agent mode).
     */
    private void startAsDaemon() {
        Thread starter = new Thread(server::start, "fxpilot-start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Starts the endpoint if {@code fxpilot.port} / {@code FXPILOT_PORT} is configured. */
    public static synchronized void startIfEnabled() {
        String port = config("fxpilot.port", "FXPILOT_PORT");
        if (port == null || port.isBlank() || !JavaFxCheck.supported()) {
            return;
        }
        try {
            start(Integer.parseInt(port.trim()), config("fxpilot.token", "FXPILOT_TOKEN"));
        } catch (NumberFormatException e) {
            System.err.println("[fxpilot] invalid port '" + port + "'");
        }
    }

    /** Starts the endpoint on 127.0.0.1:{@code port}. Does nothing if it is already running. */
    public static synchronized void start(int port, String token) {
        if (instance != null) {
            return;
        }
        try {
            instance = new FxPilotServer(port, token);
            FxPilotEngine.setDenyPatterns(parsePatterns(config("fxpilot.deny", "FXPILOT_DENY")));
            instance.startAsDaemon();
            System.err.println("[fxpilot] listening on http://127.0.0.1:" + instance.server.getAddress().getPort());
        } catch (IOException e) {
            instance = null;
            System.err.println("[fxpilot] could not start on port " + port + ": " + e);
        }
    }

    public static synchronized boolean isRunning() {
        return instance != null;
    }

    /** Bound port, or -1 if the endpoint is not running. Used by tests. */
    static int port() {
        FxPilotServer s = instance;
        return s == null ? -1 : s.server.getAddress().getPort();
    }

    /** Application-specific hints for the FxPilot client, returned by /health. */
    public static void setNotes(String text) {
        notes = text;
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.server.stop(0);
            instance.executor.shutdownNow();
            instance = null;
        }
    }

    private static String config(String property, String env) {
        String v = System.getProperty(property);
        return v != null ? v : System.getenv(env);
    }

    private static List<String> parsePatterns(String raw) {
        List<String> list = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    list.add(p);
                }
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) {
                return;
            }
            if (readOnly && isPost(ex)) {
                sendJson(ex, 403, error(Codes.READ_ONLY, "Endpoint is read-only (fxpilot.readonly=true)"));
                return;
            }
            Map<String, String> p = params(ex);
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/":
                case "/health":
                    sendJson(ex, 200, FxPilotEngine.query(FxPilotServer::health));
                    break;
                case "/windows":
                    sendJson(ex, 200, FxPilotEngine.query(FxPilotEngine::listWindows));
                    break;
                case "/tree":
                    boolean full = Boolean.parseBoolean(p.get("full"));
                    int maxDepth = p.containsKey("maxDepth")
                            ? Math.max(1, Math.min(200, Integer.parseInt(p.get("maxDepth")))) : 50;
                    sendJson(ex, 200, FxPilotEngine.query(() -> {
                        Node root = p.containsKey("selector")
                                ? FxPilotEngine.find(p.get("selector"), p.get("within"), p.get("nth"))
                                : FxPilotEngine.window(p.get("window")).getScene().getRoot();
                        return FxPilotEngine.tree(root, full, 0, maxDepth);
                    }));
                    break;
                case "/node":
                    sendJson(ex, 200, FxPilotEngine.query(() -> FxPilotEngine.info(FxPilotEngine.find(p.get("selector"), p.get("within"), p.get("nth")))));
                    break;
                case "/matches":
                    int limit = p.containsKey("limit")
                            ? Math.max(1, Math.min(200, Integer.parseInt(p.get("limit")))) : 20;
                    sendJson(ex, 200, FxPilotEngine.query(() -> FxPilotEngine.matches(p.get("selector"), p.get("within"), limit)));
                    break;
                case "/view":
                    int viewDepth = p.containsKey("maxDepth")
                            ? Math.max(1, Math.min(200, Integer.parseInt(p.get("maxDepth")))) : 50;
                    int viewLimit = p.containsKey("limit")
                            ? Math.max(1, Math.min(500, Integer.parseInt(p.get("limit")))) : 200;
                    sendJson(ex, 200, FxPilotEngine.query(() ->
                            FxPilotEngine.view(FxPilotEngine.window(p.get("window")), viewDepth, viewLimit)));
                    break;
                case "/exec":
                    String op = required(p, "op");
                    if (!READ_OPS.contains(op)) {
                        requirePost(ex);
                    }
                    sendJson(ex, 200, FxPilotExtensions.exec(op, p));
                    break;
                case "/click":
                    requirePost(ex);
                    int clicks = p.containsKey("clicks") ? Integer.parseInt(p.get("clicks")) : 1;
                    sendJson(ex, 200, action(p, n -> FxPilotEngine.click(n, clicks)));
                    break;
                case "/hover":
                    requirePost(ex);
                    sendJson(ex, 200, action(p, FxPilotEngine::hover));
                    break;
                case "/setText":
                    requirePost(ex);
                    String text = required(p, "text");
                    sendJson(ex, 200, action(p, n -> FxPilotEngine.setText(n, text)));
                    break;
                case "/type":
                    requirePost(ex);
                    String typed = required(p, "text");
                    sendJson(ex, 200, action(p, n -> FxPilotEngine.type(n, typed)));
                    break;
                case "/key":
                    requirePost(ex);
                    sendJson(ex, 200, pressKey(p));
                    break;
                case "/select":
                    requirePost(ex);
                    sendJson(ex, 200, action(p, n -> FxPilotEngine.select(n, p.get("value"), p.get("index"))));
                    break;
                case "/value":
                    requirePost(ex);
                    String value = required(p, "value");
                    sendJson(ex, 200, action(p, n -> FxPilotEngine.setValue(n, value)));
                    break;
                case "/expand":
                    requirePost(ex);
                    boolean expanded = !p.containsKey("expanded") || Boolean.parseBoolean(p.get("expanded"));
                    sendJson(ex, 200, FxPilotEngine.query(() -> FxPilotEngine.setExpanded(
                            FxPilotEngine.find(p.get("selector"), p.get("within"), p.get("nth")), p.get("value"), p.get("index"), expanded)));
                    break;
                case "/check":
                    requirePost(ex);
                    boolean checked = Boolean.parseBoolean(required(p, "checked"));
                    sendJson(ex, 200, action(p, n -> FxPilotEngine.setChecked(n, checked)));
                    break;
                case "/scroll":
                    requirePost(ex);
                    double amount = p.containsKey("amount") ? Double.parseDouble(p.get("amount")) : 1;
                    sendJson(ex, 200, action(p, n -> FxPilotEngine.scroll(n, amount)));
                    break;
                case "/menu":
                    requirePost(ex);
                    String menu = required(p, "menu");
                    sendJson(ex, 200, FxPilotEngine.actionMenu(p.get("selector"), p.get("within"), p.get("nth"), menu, p.get("item")));
                    break;
                case "/robotClick":
                    requirePost(ex);
                    double x = Double.parseDouble(required(p, "x"));
                    double y = Double.parseDouble(required(p, "y"));
                    FxPilotEngine.query(() -> {
                        FxPilotEngine.robotClick(FxPilotEngine.window(p.get("window")), x, y);
                        return null;
                    });
                    sendJson(ex, 200, Map.of("status", "done"));
                    break;
                case "/robotDrag":
                    requirePost(ex);
                    double fromX = Double.parseDouble(required(p, "x1"));
                    double fromY = Double.parseDouble(required(p, "y1"));
                    double toX = Double.parseDouble(required(p, "x2"));
                    double toY = Double.parseDouble(required(p, "y2"));
                    FxPilotEngine.query(() -> {
                        FxPilotEngine.robotDrag(FxPilotEngine.window(p.get("window")), fromX, fromY, toX, toY);
                        return null;
                    });
                    sendJson(ex, 200, Map.of("status", "done"));
                    break;
                case "/screenshot":
                    Window w = FxPilotEngine.query(() -> FxPilotEngine.window(p.get("window")));
                    Node node = p.containsKey("selector") ? FxPilotEngine.query(() -> FxPilotEngine.find(p.get("selector"), p.get("within"), p.get("nth"))) : null;
                    double shotX = p.containsKey("x") ? Double.parseDouble(p.get("x")) : 0;
                    double shotY = p.containsKey("y") ? Double.parseDouble(p.get("y")) : 0;
                    double shotWidth = p.containsKey("width") ? Double.parseDouble(p.get("width")) : -1;
                    double shotHeight = p.containsKey("height") ? Double.parseDouble(p.get("height")) : -1;
                    byte[] png = FxPilotEngine.screenshot(w, node, shotX, shotY, shotWidth, shotHeight);
                    ex.getResponseHeaders().set("Content-Type", "image/png");
                    ex.sendResponseHeaders(200, png.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(png);
                    }
                    break;
                default:
                    sendJson(ex, 404, error(Codes.UNKNOWN_ENDPOINT, "unknown endpoint " + path));
            }
        } catch (FxPilotException e) {
            sendError(ex, e.status, e.code, e.getMessage());
        } catch (NumberFormatException e) {
            sendError(ex, 400, Codes.INVALID_NUMBER, "invalid number: " + e.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, Codes.INTERNAL_ERROR, e.toString());
        } finally {
            ex.close();
        }
    }

    private static Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("javafx", System.getProperty("javafx.runtime.version", System.getProperty("javafx.version")));
        List<String> titles = new ArrayList<>();
        for (Window win : FxPilotEngine.windows()) {
            if (win instanceof Stage && ((Stage) win).getTitle() != null) {
                titles.add(((Stage) win).getTitle());
            }
        }
        m.put("windows", FxPilotEngine.windows().size());
        m.put("titles", titles);
        String n = notes != null ? notes : System.getProperty("fxpilot.notes");
        if (n != null && !n.isBlank()) {
            m.put("notes", n);
        }
        return m;
    }

    /** Presses a key on the selector, or on the focused node / scene root when no selector is given. */
    private static Map<String, Object> pressKey(Map<String, String> p) {
        String key = required(p, "key");
        return FxPilotEngine.query(() -> {
            Window win = FxPilotEngine.window(p.get("window"));
            String selector = p.get("selector");
            Node target;
            if (selector != null && !selector.isBlank()) {
                target = FxPilotEngine.find(selector);
            } else if (win.getScene().getFocusOwner() != null) {
                target = win.getScene().getFocusOwner();
            } else {
                target = win.getScene().getRoot();
            }
            FxPilotEngine.pressKey(target, key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "done");
            result.put("node", FxPilotEngine.info(target));
            return result;
        });
    }

    private boolean authorized(HttpExchange ex) throws IOException {
        if (token != null && !token.isEmpty()) {
            String given = ex.getRequestHeaders().getFirst("X-FxPilot-Token");
            if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                sendJson(ex, 401, error(Codes.UNAUTHORIZED, "missing or wrong X-FxPilot-Token"));
                return false;
            }
            return true;
        }
        if (isPost(ex) && ex.getRequestHeaders().getFirst("X-FxPilot-Client") == null) {
            sendJson(ex, 403, error(Codes.FORBIDDEN, "POST requires the header X-FxPilot-Client. It stops browser pages from "
                    + "triggering actions on the local endpoint; the FxPilot MCP server sends it automatically."));
            return false;
        }
        return true;
    }

    private static boolean isPost(HttpExchange ex) {
        return "POST".equalsIgnoreCase(ex.getRequestMethod());
    }

    private static void requirePost(HttpExchange ex) {
        if (!isPost(ex)) {
            throw new FxPilotException(405, Codes.METHOD_NOT_ALLOWED, "use POST");
        }
    }

    /** Sends an error response unless the headers were already sent (e.g. a screenshot failed while streaming). */
    private static void sendError(HttpExchange ex, int status, String code, String message) {
        try {
            sendJson(ex, status, error(code, message));
        } catch (IOException | RuntimeException ignored) {
            // response already started, nothing left to report
        }
    }

    private static Map<String, Object> action(Map<String, String> p, Consumer<Node> action) {
        return FxPilotEngine.action(p.get("selector"), p.get("within"), p.get("nth"),
                Boolean.parseBoolean(p.get("requireVisible")),
                Boolean.parseBoolean(p.get("requireEnabled")), action);
    }

    private static String required(Map<String, String> p, String name) {
        String v = p.get(name);
        if (v == null) {
            throw new FxPilotException(400, Codes.BAD_REQUEST, "parameter '" + name + "' is required");
        }
        return v;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        m.put("code", code);
        return m;
    }

    /** Query string plus application/x-www-form-urlencoded body. */
    private static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> p = new HashMap<>();
        parse(ex.getRequestURI().getRawQuery(), p);
        if (isPost(ex)) {
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new FxPilotException(413, Codes.BODY_TOO_LARGE, "request body larger than " + MAX_BODY_BYTES + " bytes");
            }
            parse(new String(body, StandardCharsets.UTF_8), p);
        }
        return p;
    }

    static void parse(String raw, Map<String, String> into) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            into.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body instanceof List || body instanceof Map ? body : String.valueOf(body))
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}

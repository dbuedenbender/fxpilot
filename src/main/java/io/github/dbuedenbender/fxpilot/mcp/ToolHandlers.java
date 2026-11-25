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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Tool implementations that need more than a single endpoint call.
 */
final class ToolHandlers {

    /** Endpoint operations that only read and therefore use GET (also allowed in read-only mode). */
    static final Set<String> EXEC_READ_OPS = Set.of(
            "canvasPixel", "chart", "clipboardGet", "idle", "mediaStatus", "propertyGet", "tableCell", "tooltip",
            "webHtml", "webQuery");

    private ToolHandlers() {
    }

    static Map<String, Object> retryTool(Map<String, Object> a) throws Exception {
        String tool = Results.str(a.get("tool"));
        if (tool == null || tool.isBlank() || "fx_retry".equals(tool)) {
            throw new RpcError(-32602, "tool must name a non-retry tool");
        }
        Map<String, Object> args = a.get("args") instanceof Map
                ? new LinkedHashMap<>(Results.asMap(a.get("args"))) : new LinkedHashMap<>();
        int attempts = a.get("attempts") instanceof Number ? ((Number) a.get("attempts")).intValue() : 3;
        attempts = Math.max(1, Math.min(20, attempts));
        long delay = a.get("delayMs") instanceof Number ? ((Number) a.get("delayMs")).longValue() : 300;
        delay = Math.max(0, Math.min(10_000, delay));
        Map<String, Object> result = null;
        for (int i = 0; i < attempts; i++) {
            result = ToolCatalog.callTool(tool, args);
            if (!Boolean.TRUE.equals(result.get("isError"))) {
                return result;
            }
            if (i < attempts - 1) {
                Thread.sleep(Math.max(0, delay));
            }
        }
        return result;
    }

    static Map<String, Object> exec(String op, Map<String, Object> a, String base) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>(a);
        args.put("op", op);
        return execRaw(args, base);
    }

    static Map<String, Object> execGeneric(Map<String, Object> a, String base) throws Exception {
        String op = Results.str(a.get("op"));
        if (op == null || op.isBlank()) {
            throw new RpcError(-32602, "op is required");
        }
        Map<String, Object> args = a.get("args") instanceof Map
                ? new LinkedHashMap<>(Results.asMap(a.get("args"))) : new LinkedHashMap<>();
        args.put("op", op);
        if (a.get("port") != null) {
            args.put("port", a.get("port"));
        }
        return execRaw(args, base);
    }

    private static Map<String, Object> execRaw(Map<String, Object> args, String base) throws Exception {
        String op = Results.str(args.get("op"));
        return Results.text(EXEC_READ_OPS.contains(op) ? EndpointClient.get("/exec", args, base) : EndpointClient.post("/exec", args, base));
    }

    static Map<String, Object> clipboard(Map<String, Object> a, String base) throws Exception {
        String action = Results.str(a.get("action"));
        return exec("set".equalsIgnoreCase(action) ? "clipboardSet" : "clipboardGet", a, base);
    }

    static Map<String, Object> selection(Map<String, Object> a, String base) throws Exception {
        String action = Results.str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        switch (action) {
            case "rows": return exec("selectRows", a, base);
            case "all": return exec("selectAll", a, base);
            case "clear": return exec("clearSelection", a, base);
            default: throw new RpcError(-32602, "Unknown selection action: " + action);
        }
    }

    static Map<String, Object> table(Map<String, Object> a, String base) throws Exception {
        String action = Results.str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        switch (action) {
            case "cell": return exec("tableCell", a, base);
            case "edit": return exec("tableEdit", a, base);
            case "cellSet": return exec("tableCellSet", a, base);
            case "sort": return exec("tableSort", a, base);
            case "column": return exec("tableColumn", a, base);
            case "select": return exec("tableSelect", a, base);
            case "scroll": return exec("tableScroll", a, base);
            default: throw new RpcError(-32602, "Unknown table action: " + action);
        }
    }

    static Map<String, Object> property(Map<String, Object> a, String base) throws Exception {
        String action = Results.str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        return exec("set".equalsIgnoreCase(action) ? "propertySet" : "propertyGet", a, base);
    }

    static Map<String, Object> web(Map<String, Object> a, String base) throws Exception {
        String action = Results.str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        switch (action) {
            case "execute": return exec("webExecute", a, base);
            case "query": return exec("webQuery", a, base);
            case "click": return exec("webClick", a, base);
            case "html": return exec("webHtml", a, base);
            default: throw new RpcError(-32602, "Unknown web action: " + action);
        }
    }

    static Map<String, Object> media(Map<String, Object> a, String base) throws Exception {
        String action = Results.str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        return exec("status".equalsIgnoreCase(action) ? "mediaStatus" : "media", a, base);
    }

    static Map<String, Object> canvas(Map<String, Object> a, String base) throws Exception {
        String action = Results.str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        return exec("click".equalsIgnoreCase(action) ? "canvasClick" : "canvasPixel", a, base);
    }

    static Map<String, Object> clickMenu(Map<String, Object> a, String base) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>(a);
        if (a.get("path") == null) {
            String menu = Results.str(a.get("menu"));
            if (menu == null || menu.isBlank()) {
                throw new RpcError(-32602, "menu or path is required");
            }
            String path = menu;
            String item = Results.str(a.get("item"));
            if (item != null && !item.isBlank()) {
                path += " > " + item;
            }
            args.put("path", path);
            args.remove("menu");
            args.remove("item");
        }
        return exec("menu", args, base);
    }

    static Map<String, Object> contextMenu(Map<String, Object> a, String base) throws Exception {
        Map<String, Object> first = exec("contextMenu", a, base);
        if (Boolean.TRUE.equals(first.get("isError")) || a.get("item") == null) {
            return first;
        }
        Thread.sleep(150);
        Map<String, Object> args = new LinkedHashMap<>(a);
        args.remove("selector");
        args.remove("within");
        args.remove("nth");
        return exec("contextMenuItem", args, base);
    }

    static Map<String, Object> waitForWindow(Map<String, Object> a, String base) throws Exception {
        String title = Results.str(a.get("title"));
        if (title == null || title.isBlank()) {
            throw new RpcError(-32602, "title is required");
        }
        long timeout = a.get("timeoutMs") instanceof Number ? ((Number) a.get("timeoutMs")).longValue() : 5000;
        long end = System.currentTimeMillis() + Math.max(0, timeout);
        String last = "";
        while (true) {
            last = EndpointClient.get("/windows", a, base);
            Object parsed = Json.parse(last);
            if (parsed instanceof List) {
                for (Object item : (List<?>) parsed) {
                    Map<String, Object> window = Results.asMap(item);
                    String windowTitle = Results.str(window.get("title"));
                    if (windowTitle != null && windowTitle.toLowerCase(java.util.Locale.ROOT)
                            .contains(title.toLowerCase(java.util.Locale.ROOT))) {
                        return Results.text(Json.write(window));
                    }
                }
            }
            if (System.currentTimeMillis() >= end) {
                return Results.toolError("Timeout after " + timeout + " ms waiting for window '" + title + "'. Last: " + last);
            }
            Thread.sleep(200);
        }
    }

    /** selector/within/nth plus the named extra arguments, skipping absent ones. */
    static Map<String, Object> withSelector(Map<String, Object> a, String... extra) {
        Map<String, Object> m = pick(a, "selector", "within", "nth");
        for (String k : extra) {
            if (a.get(k) != null) {
                m.put(k, a.get(k));
            }
        }
        return m;
    }

    static Map<String, Object> batch(Map<String, Object> a) throws Exception {
        Object raw = a.get("calls");
        if (!(raw instanceof List)) {
            throw new RpcError(-32602, "calls must be an array of {tool, args} objects");
        }
        List<?> calls = (List<?>) raw;
        StringBuilder log = new StringBuilder();
        int failed = 0;
        for (int i = 0; i < calls.size(); i++) {
            Map<String, Object> call = Results.asMap(calls.get(i));
            String tool = Results.str(call.get("tool"));
            Map<String, Object> args = call.get("args") instanceof Map
                    ? new LinkedHashMap<>(Results.asMap(call.get("args")))
                    : new LinkedHashMap<>();
            if (a.get("port") != null && args.get("port") == null) {
                args.put("port", a.get("port"));
            }
            Map<String, Object> result;
            try {
                result = "fx_batch".equals(tool)
                        ? Results.toolError("nested fx_batch is not supported")
                        : ToolCatalog.callTool(tool, args);
            } catch (Exception e) {
                result = Results.toolError(tool + " failed: " + e);
            }
            boolean ok = !Boolean.TRUE.equals(result.get("isError"));
            if (!ok) {
                failed++;
            }
            log.append(i + 1).append(' ').append(tool).append(": ").append(ok ? "ok" : "FAIL").append('\n');
            if (!ok) {
                log.append("  ").append(Results.textOf(result)).append('\n');
            }
        }
        String summary = "Batch: " + calls.size() + " call(s), failed=" + failed + "\n" + log;
        return failed == 0 ? Results.text(summary) : Results.toolError(summary);
    }

    static Map<String, Object> screenshot(Map<String, Object> a, String base) throws Exception {
        HttpResponse<byte[]> r = EndpointClient.http().send(EndpointClient.request("/screenshot", a, false, base), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw EndpointClient.failure(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        }
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("type", "image");
        img.put("data", Base64.getEncoder().encodeToString(r.body()));
        img.put("mimeType", "image/png");
        return Map.of("content", List.of(img));
    }

    static Map<String, Object> screenshotDiff(Map<String, Object> a, String base) throws Exception {
        Object baselineArg = a.get("baseline");
        if (baselineArg == null || String.valueOf(baselineArg).isBlank()) {
            throw new RpcError(-32602, "baseline is required (path to a PNG file)");
        }
        Path baseline = Path.of(String.valueOf(baselineArg));
        if (Boolean.TRUE.equals(a.get("update")) || !Files.exists(baseline)) {
            ToolCatalog.writablePath(baseline.toString());
        }
        boolean update = Boolean.TRUE.equals(a.get("update"));
        double tolerance = a.get("tolerance") instanceof Number ? ((Number) a.get("tolerance")).doubleValue() : 0.01;
        HttpResponse<byte[]> r = EndpointClient.http().send(EndpointClient.request("/screenshot", withSelector(a, "window"), false, base),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw EndpointClient.failure(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        }
        BufferedImage current = ImageIO.read(new ByteArrayInputStream(r.body()));
        if (current == null) {
            return Results.toolError("Screenshot is not a readable PNG");
        }
        if (update || !Files.exists(baseline)) {
            if (baseline.getParent() != null) {
                Files.createDirectories(baseline.getParent());
            }
            ImageIO.write(current, "png", baseline.toFile());
            return Results.text((update ? "UPDATED" : "BASELINE CREATED") + " " + baseline.toAbsolutePath());
        }
        BufferedImage reference = ImageIO.read(baseline.toFile());
        if (reference == null) {
            return Results.toolError("Cannot read baseline PNG " + baseline);
        }
        if (reference.getWidth() != current.getWidth() || reference.getHeight() != current.getHeight()) {
            return Results.toolError("FAIL size " + current.getWidth() + "x" + current.getHeight()
                    + " differs from baseline " + reference.getWidth() + "x" + reference.getHeight());
        }
        int width = current.getWidth();
        int height = current.getHeight();
        int diffPixels = 0;
        BufferedImage diffImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean changed = channelDiff(current.getRGB(x, y), reference.getRGB(x, y)) > 8;
                if (changed) {
                    diffPixels++;
                }
                diffImage.setRGB(x, y, changed ? 0xFFFF0000 : 0x22000000);
            }
        }
        double ratio = (double) diffPixels / ((double) width * height);
        String diffPath = Results.str(a.get("saveDiff"));
        if (diffPath != null && !diffPath.isBlank()) {
            Path p = ToolCatalog.writablePath(diffPath);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            ImageIO.write(diffImage, "png", p.toFile());
        }
        String summary = (ratio <= tolerance ? "PASS" : "FAIL") + " diff="
                + String.format(java.util.Locale.ROOT, "%.4f", ratio) + " (" + diffPixels + " px), tolerance="
                + tolerance + ", baseline=" + baseline;
        return ratio <= tolerance ? Results.text(summary) : Results.toolError(summary);
    }

    private static int channelDiff(int a, int b) {
        int alpha = Math.abs(((a >> 24) & 0xFF) - ((b >> 24) & 0xFF));
        int red = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int green = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int blue = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(Math.max(alpha, red), Math.max(green, blue));
    }

    static Map<String, Object> waitFor(Map<String, Object> a, String base) throws Exception {
        long timeout = a.get("timeoutMs") instanceof Number ? ((Number) a.get("timeoutMs")).longValue() : 5000;
        long started = System.currentTimeMillis();
        long end = started + Math.max(0, timeout);
        List<String> problems = List.of("no attempt yet");
        while (true) {
            try {
                problems = Recorder.evaluate(a).problems;
            } catch (java.io.IOException e) {
                problems = List.of("app not reachable at " + base + " (" + e.getMessage() + ")");
            }
            if (problems.isEmpty()) {
                return Results.text("PASS " + a.get("selector") + " after " + (System.currentTimeMillis() - started) + " ms");
            }
            if (System.currentTimeMillis() >= end) {
                break;
            }
            Thread.sleep(200);
        }
        return Results.toolError("Timeout after " + timeout + " ms: " + String.join("; ", problems));
    }

    static Map<String, Object> pick(Map<String, Object> a, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) {
            if (a.get(k) != null) {
                m.put(k, a.get(k));
            }
        }
        return m;
    }
}

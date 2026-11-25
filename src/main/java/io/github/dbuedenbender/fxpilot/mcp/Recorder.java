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

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Record &amp; replay of tool calls, plus the assertions shared by {@code fx_assert} and {@code fx_wait_for}.
 */
final class Recorder {

    /**
     * Recording state. The stdio loop handles one request at a time, so no locking is needed.
     * {@code recording == null} means "not recording".
     */
    private static List<Object> recording;
    private static String recordingName;
    private static String recordingStartedAt;

    private Recorder() {
    }

    static Map<String, Object> startRecording(Map<String, Object> a) {
        if (recording != null) {
            return Results.toolError("Already recording '" + recordingName + "'. Call fx_record_stop first.");
        }
        Object name = a.get("name");
        recording = new ArrayList<>();
        recordingName = name == null || String.valueOf(name).isBlank() ? "recording" : String.valueOf(name);
        recordingStartedAt = Instant.now().toString();
        return Results.text("Recording '" + recordingName + "' started. Mutating tools and passing fx_assert calls are "
                + "captured; fx_record_stop returns or writes the JSON plan.");
    }

    static Map<String, Object> stopRecording(Map<String, Object> a) throws Exception {
        if (recording == null) {
            return Results.toolError("Not recording. Call fx_record_start first.");
        }
        List<Object> steps = recording;
        recording = null;
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("fxpilot", FxPilotMcpServer.VERSION);
        plan.put("name", recordingName);
        plan.put("createdAt", recordingStartedAt);
        plan.put("steps", steps);
        Object override = a.get("name");
        if (override != null && !String.valueOf(override).isBlank()) {
            plan.put("name", String.valueOf(override));
        }
        String json = Json.write(plan);
        Object file = a.get("file");
        if (file == null || String.valueOf(file).isBlank()) {
            return Results.text(json);
        }
        Path path = ToolCatalog.writablePath(String.valueOf(file));
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return Results.text("Recording '" + plan.get("name") + "' stopped: " + steps.size()
                + " step(s) written to " + path.toAbsolutePath());
    }

    static void recordStep(String tool, Map<String, Object> args, Map<String, Object> result) {
        if (recording == null || Boolean.TRUE.equals(result.get("isError")) || !ToolCatalog.isRecorded(tool)) {
            return;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", tool);
        step.put("args", copyArgs(args));
        recording.add(step);
    }

    private static void recordAssert(Map<String, Object> args) {
        if (recording == null) {
            return;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", "fx_assert");
        step.put("args", copyArgs(args));
        recording.add(step);
    }

    /** Recorded plans must be portable, so the target port is not part of the plan. */
    private static Map<String, Object> copyArgs(Map<String, Object> args) {
        Map<String, Object> copy = new LinkedHashMap<>(args);
        copy.remove("port");
        return copy;
    }

    static Map<String, Object> assertNode(Map<String, Object> a) throws Exception {
        Object selector = a.get("selector");
        if (selector == null || String.valueOf(selector).isBlank()) {
            throw new RpcError(-32602, "selector is required");
        }
        Evaluation e;
        try {
            e = evaluate(a);
        } catch (java.io.IOException ex) {
            return Results.toolError("Cannot reach the app at " + EndpointClient.baseUrl(a.get("port")) + ": " + ex);
        }
        if (!e.problems.isEmpty()) {
            return Results.toolError("FAIL " + selector + ": " + String.join("; ", e.problems));
        }
        StringBuilder pass = new StringBuilder("PASS ").append(selector);
        if (e.count != null) {
            pass.append(" count=").append(e.count);
        }
        String expectedText = Results.str(a.get("text"));
        String expectedValue = Results.str(a.get("value"));
        if (expectedText != null) {
            pass.append(" text=\"").append(expectedText).append('"');
        }
        if (expectedValue != null) {
            pass.append(" value=\"").append(expectedValue).append('"');
        }
        Map<String, Object> result = Results.text(pass.toString());
        recordAssert(a);
        return result;
    }

    static final class Evaluation {
        final List<String> problems;
        final Integer count;

        Evaluation(List<String> problems, Integer count) {
            this.problems = problems;
            this.count = count;
        }
    }

    /** Runs the selector/count/state checks shared by fx_assert and fx_wait_for. */
    static Evaluation evaluate(Map<String, Object> a) throws Exception {
        Object selector = a.get("selector");
        if (selector == null || String.valueOf(selector).isBlank()) {
            throw new RpcError(-32602, "selector is required");
        }
        String base = EndpointClient.baseUrl(a.get("port"));
        String expectedText = Results.str(a.get("text"));
        String expectedValue = Results.str(a.get("value"));
        boolean hasState = expectedText != null || expectedValue != null
                || a.get("visible") != null || a.get("enabled") != null || a.get("checked") != null
                || a.get("selected") != null || a.get("selectedIndex") != null || a.get("selectedIndices") != null
                || a.get("rowCount") != null || a.get("focused") != null || a.get("progress") != null
                || a.get("tooltip") != null || a.get("accessibleText") != null || a.get("property") != null;
        boolean expectExists = !Boolean.FALSE.equals(a.get("exists"));
        List<String> problems = new ArrayList<>();
        Integer actualCount = null;

        Object countArg = a.get("count");
        if (countArg != null) {
            int want;
            try {
                want = Integer.parseInt(String.valueOf(countArg).trim());
            } catch (NumberFormatException e) {
                throw new RpcError(-32602, "count must be an integer");
            }
            HttpResponse<String> c = EndpointClient.http().send(EndpointClient.request("/matches", ToolHandlers.withSelector(a), false, base),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (c.statusCode() != 200) {
                problems.add("HTTP " + c.statusCode() + ": " + c.body());
            } else {
                Object count = Results.asMap(Json.parse(c.body())).get("count");
                actualCount = count instanceof Number ? ((Number) count).intValue() : null;
                if (actualCount == null || actualCount != want) {
                    problems.add("count expected " + want + " but was " + actualCount);
                }
            }
        }

        boolean needNode = hasState || a.get("exists") != null || countArg == null;
        if (countArg != null && Integer.valueOf(0).equals(actualCount) && hasState) {
            problems.add("count is 0, so there is no node to check");
            needNode = false;
        }
        if (needNode) {
            HttpResponse<String> r = EndpointClient.http().send(EndpointClient.request("/node", ToolHandlers.withSelector(a), false, base),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 404) {
                if (expectExists) {
                    problems.add("node does not exist");
                }
            } else if (r.statusCode() != 200) {
                problems.add("HTTP " + r.statusCode() + ": " + r.body());
            } else if (a.get("exists") != null && !expectExists) {
                problems.add("node exists but should not");
            } else if (hasState) {
                checkState(a, Results.asMap(Json.parse(r.body())), expectedText, expectedValue, problems);
            }
        }
        if (a.get("property") != null) {
            Map<String, Object> args = new LinkedHashMap<>(a);
            args.put("op", "propertyGet");
            args.put("name", String.valueOf(a.get("property")));
            HttpResponse<String> pr = EndpointClient.http().send(EndpointClient.request("/exec", args, false, base),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (pr.statusCode() != 200) {
                problems.add("property HTTP " + pr.statusCode() + ": " + pr.body());
            } else {
                Object actual = Results.asMap(Json.parse(pr.body())).get("value");
                String expected = Results.str(a.get("propertyValue"));
                if (expected != null && !sameValue(actual, expected)) {
                    problems.add("property " + a.get("property") + " expected \"" + expected
                            + "\" but was \"" + actual + "\"");
                }
            }
        }
        return new Evaluation(problems, actualCount);
    }

    private static void checkState(Map<String, Object> a, Map<String, Object> node,
            String expectedText, String expectedValue, List<String> problems) {
        if (expectedText != null) {
            String actual = node.get("text") == null ? "" : String.valueOf(node.get("text"));
            if (!actual.equals(expectedText)) {
                problems.add("text expected \"" + expectedText + "\" but was \"" + actual + "\"");
            }
        }
        if (expectedValue != null && !sameValue(node.get("value"), expectedValue)) {
            problems.add("value expected \"" + expectedValue + "\" but was \"" + node.get("value") + "\"");
        }
        if (a.get("visible") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("visible")));
            boolean actual = Boolean.TRUE.equals(node.get("visible"));
            if (want != actual) {
                problems.add("visible expected " + want + " but was " + actual);
            }
        }
        if (a.get("enabled") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("enabled")));
            boolean actual = !Boolean.TRUE.equals(node.get("disabled"));
            if (want != actual) {
                problems.add("enabled expected " + want + " but was " + actual);
            }
        }
        if (a.get("checked") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("checked")));
            boolean actual = Boolean.TRUE.equals(node.get("selected"));
            if (want != actual) {
                problems.add("checked expected " + want + " but was " + actual);
            }
        }
        if (a.get("selected") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("selected")));
            boolean actual = Boolean.TRUE.equals(node.get("selected"));
            if (want != actual) {
                problems.add("selected expected " + want + " but was " + actual);
            }
        }
        if (a.get("selectedIndex") != null) {
            int want = Integer.parseInt(String.valueOf(a.get("selectedIndex")).trim());
            int actual = node.get("selectedIndex") instanceof Number
                    ? ((Number) node.get("selectedIndex")).intValue() : -1;
            if (want != actual) {
                problems.add("selectedIndex expected " + want + " but was " + actual);
            }
        }
        if (a.get("selectedIndices") != null) {
            String want = normalizeIndices(String.valueOf(a.get("selectedIndices")));
            String actual = normalizeIndices(String.valueOf(node.get("selectedIndices")));
            if (!want.equals(actual)) {
                problems.add("selectedIndices expected " + want + " but was " + actual);
            }
        }
        if (a.get("rowCount") != null) {
            int want = Integer.parseInt(String.valueOf(a.get("rowCount")).trim());
            int actual = node.get("rowCount") instanceof Number ? ((Number) node.get("rowCount")).intValue() : -1;
            if (want != actual) {
                problems.add("rowCount expected " + want + " but was " + actual);
            }
        }
        if (a.get("focused") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("focused")));
            boolean actual = Boolean.TRUE.equals(node.get("focused"));
            if (want != actual) {
                problems.add("focused expected " + want + " but was " + actual);
            }
        }
        if (a.get("progress") != null) {
            double want = Double.parseDouble(String.valueOf(a.get("progress")));
            double actual = node.get("progress") instanceof Number
                    ? ((Number) node.get("progress")).doubleValue() : Double.NaN;
            if (Math.abs(want - actual) > 1e-6) {
                problems.add("progress expected " + want + " but was " + actual);
            }
        }
        if (a.get("tooltip") != null) {
            String want = String.valueOf(a.get("tooltip"));
            String actual = node.get("tooltip") == null ? "" : String.valueOf(node.get("tooltip"));
            if (!want.equals(actual)) {
                problems.add("tooltip expected \"" + want + "\" but was \"" + actual + "\"");
            }
        }
        if (a.get("accessibleText") != null) {
            String want = String.valueOf(a.get("accessibleText"));
            String actual = node.get("accessibleText") == null ? "" : String.valueOf(node.get("accessibleText"));
            if (!want.equals(actual)) {
                problems.add("accessibleText expected \"" + want + "\" but was \"" + actual + "\"");
            }
        }
    }

    private static String normalizeIndices(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : raw.replace("[", "").replace("]", "").split("[,;\\s]+")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return String.join(",", parts);
    }

    private static String readPlan(Path path) throws Exception {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        // Windows editors (and PowerShell) may write a UTF-8 BOM, which is not part of the JSON.
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static boolean sameValue(Object actual, String expected) {
        if (actual instanceof Number) {
            try {
                return Math.abs(((Number) actual).doubleValue() - Double.parseDouble(expected)) < 1e-9;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return actual != null && String.valueOf(actual).equals(expected);
    }

    /** Replays in progress; a plan that replays a plan would recurse without end. */
    private static int replayDepth;

    static Map<String, Object> replay(Map<String, Object> a) throws Exception {
        if (replayDepth > 0) {
            return Results.toolError("fx_replay cannot run inside a replayed plan");
        }
        replayDepth++;
        try {
            return replayPlan(a);
        } finally {
            replayDepth--;
        }
    }

    private static Map<String, Object> replayPlan(Map<String, Object> a) throws Exception {
        Object file = a.get("file");
        if (file == null || String.valueOf(file).isBlank()) {
            throw new RpcError(-32602, "file is required (path to a recorded plan)");
        }
        Path path = Path.of(String.valueOf(file));
        if (!Files.isReadable(path)) {
            return Results.toolError("Cannot read plan file " + path.toAbsolutePath());
        }
        Map<String, Object> plan = Results.asMap(Json.parse(readPlan(path)));
        Object rawSteps = plan.get("steps");
        if (!(rawSteps instanceof List)) {
            return Results.toolError("Plan " + path.getFileName() + " has no steps array");
        }
        List<?> steps = (List<?>) rawSteps;
        boolean stopOnError = !Boolean.FALSE.equals(a.get("stopOnError"));
        boolean screenshotOnFailure = Boolean.TRUE.equals(a.get("screenshotOnFailure"));
        int retries = a.get("retries") instanceof Number ? ((Number) a.get("retries")).intValue() : 0;
        retries = Math.max(0, Math.min(10, retries));
        Map<String, Object> variables = a.get("variables") instanceof Map
                ? new LinkedHashMap<>(Results.asMap(a.get("variables"))) : new LinkedHashMap<>();
        long delayMs = a.get("delayMs") instanceof Number ? ((Number) a.get("delayMs")).longValue() : 300;
        delayMs = Math.max(0, Math.min(10_000, delayMs));
        long started = System.currentTimeMillis();
        StringBuilder failures = new StringBuilder();
        StringBuilder report = new StringBuilder();
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = Results.asMap(steps.get(i));
            String tool = Results.str(step.get("tool"));
            Map<String, Object> args = step.get("args") instanceof Map
                    ? new LinkedHashMap<>(Results.asMap(step.get("args")))
                    : new LinkedHashMap<>();
            args = resolveArgs(args, variables);
            if (a.get("port") != null) {
                args.put("port", a.get("port"));
            }
            Map<String, Object> result = null;
            for (int attempt = 0; attempt <= retries; attempt++) {
                try {
                    String blocked = ToolCatalog.dangerousBlocked(tool, args);
                    result = blocked != null ? Results.toolError(blocked)
                            : "fx_assert".equals(tool) ? assertNode(args)
                            : ToolCatalog.invoke(tool == null ? "" : tool, args, EndpointClient.baseUrl(args.get("port")));
                } catch (Exception e) {
                    result = Results.toolError(tool + " failed: " + e);
                }
                if (!Boolean.TRUE.equals(result.get("isError"))) {
                    break;
                }
                if (attempt < retries) {
                    Thread.sleep(200);
                }
            }
            boolean failedStep = result == null || Boolean.TRUE.equals(result.get("isError"));
            if (failedStep) {
                failed++;
                String detail = result == null ? "no result" : Results.textOf(result);
                failures.append("  step ").append(i + 1).append(' ').append(tool).append(": ").append(detail).append('\n');
                report.append("      <testcase name=\"step ").append(i + 1).append(' ').append(xml(tool))
                        .append("\" classname=\"fxpilot\"><failure message=\"").append(xml(detail))
                        .append("\"/></testcase>\n");
                if (screenshotOnFailure) {
                    saveFailureScreenshot(args, "fxpilot/failure-step-" + (i + 1) + ".png", EndpointClient.baseUrl(args.get("port")));
                }
                if (stopOnError) {
                    break;
                }
            } else {
                passed++;
                report.append("      <testcase name=\"step ").append(i + 1).append(' ').append(xml(tool))
                        .append("\" classname=\"fxpilot\"/>\n");
            }
            if (i < steps.size() - 1 && delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        long duration = System.currentTimeMillis() - started;
        String junitReport = Results.str(a.get("junitReport"));
        if (junitReport != null && !junitReport.isBlank()) {
            Path reportPath = ToolCatalog.writablePath(junitReport);
            if (reportPath.getParent() != null) {
                Files.createDirectories(reportPath.getParent());
            }
            String reportXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<testsuite name=\"fxpilot\" tests=\"" + steps.size() + "\" failures=\"" + failed + "\">\n"
                    + report + "</testsuite>\n";
            Files.writeString(reportPath, reportXml, StandardCharsets.UTF_8);
        }
        String summary = "Replay " + path.getFileName() + ": " + (passed + failed) + "/" + steps.size()
                + " step(s), passed=" + passed + ", failed=" + failed + ", durationMs=" + duration
                + ", status=" + (failed == 0 ? "PASS" : "FAIL");
        return failed == 0 ? Results.text(summary) : Results.toolError(summary + "\n" + failures);
    }

    private static Map<String, Object> resolveArgs(Map<String, Object> args, Map<String, Object> variables) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), variables));
        }
        return resolved;
    }

    private static Object resolveValue(Object value, Map<String, Object> variables) {
        if (value instanceof String) {
            String text = (String) value;
            for (Map.Entry<String, Object> var : variables.entrySet()) {
                text = text.replace("${" + var.getKey() + "}", String.valueOf(var.getValue()));
            }
            return text;
        }
        if (value instanceof Map) {
            return resolveArgs(Results.asMap(value), variables);
        }
        if (value instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object item : (List<?>) value) {
                list.add(resolveValue(item, variables));
            }
            return list;
        }
        return value;
    }

    private static void saveFailureScreenshot(Map<String, Object> args, String file, String base) {
        try {
            HttpResponse<byte[]> response = EndpointClient.http().send(
                    EndpointClient.request("/screenshot", ToolHandlers.withSelector(args, "window"), false, base),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                Path path = ToolCatalog.writablePath(file);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.write(path, response.body());
            }
        } catch (Exception ignored) {
            // A failed screenshot must not hide the original replay failure.
        }
    }

    private static String xml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** CLI mode: java -jar fxpilot.jar --replay plan.json [--port N] [--delay MS] [--continue-on-error] */
    static int replayMain(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java -jar fxpilot.jar --replay <plan.json> "
                    + "[--port <n>] [--delay <ms>] [--continue-on-error] [--retry <n>] "
                    + "[--screenshot-on-failure] [--report <xml>] [--var key=value]");
            return 2;
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("file", args[1]);
        Map<String, Object> variables = new LinkedHashMap<>();
        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--port":
                        a.put("port", Integer.parseInt(args[++i]));
                        break;
                    case "--delay":
                        a.put("delayMs", Long.parseLong(args[++i]));
                        break;
                    case "--continue-on-error":
                        a.put("stopOnError", false);
                        break;
                    case "--retry":
                        a.put("retries", Integer.parseInt(args[++i]));
                        break;
                    case "--screenshot-on-failure":
                        a.put("screenshotOnFailure", true);
                        break;
                    case "--report":
                        a.put("junitReport", args[++i]);
                        break;
                    case "--var": {
                        String assignment = args[++i];
                        int eq = assignment.indexOf('=');
                        if (eq <= 0) {
                            System.err.println("Invalid --var, use key=value");
                            return 2;
                        }
                        variables.put(assignment.substring(0, eq), assignment.substring(eq + 1));
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.err.println("Invalid argument: " + e.getMessage());
            return 2;
        }
        if (!variables.isEmpty()) {
            a.put("variables", variables);
        }
        Map<String, Object> result;
        try {
            result = replay(a);
        } catch (ToolError e) {
            System.err.println(e.getMessage());
            return 2;
        }
        System.out.println(Results.textOf(result));
        return Boolean.TRUE.equals(result.get("isError")) ? 1 : 0;
    }
}

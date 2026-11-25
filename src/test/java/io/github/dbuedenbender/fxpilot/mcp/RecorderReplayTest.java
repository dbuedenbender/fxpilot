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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay parsing, failure handling and the CLI argument parser, without a running app. */
class RecorderReplayTest {

    @TempDir
    Path work;

    @BeforeEach
    void allowWrites() {
        ToolCatalog.configure(ToolCatalog.Profile.ALL, true);
    }

    @AfterEach
    void restoreDefaults() {
        ToolCatalog.configure(ToolCatalog.Profile.ALL, true);
    }

    @Test
    void replayRequiresAFileAndReportsUnreadablePlans() throws Exception {
        assertThrows(RpcError.class, () -> Recorder.replay(Map.of()));

        Map<String, Object> missing = Recorder.replay(Map.of("file", work.resolve("missing.json").toString()));
        assertEquals(true, missing.get("isError"));
        assertTrue(Results.textOf(missing).contains("Cannot read plan file"), Results.textOf(missing));
    }

    @Test
    void planMustContainAStepsArray() throws Exception {
        Map<String, Object> result = Recorder.replay(Map.of("file", write("no-steps.json", "{\"name\":\"x\"}").toString()));
        assertEquals(true, result.get("isError"));
        assertTrue(Results.textOf(result).contains("has no steps array"), Results.textOf(result));
    }

    @Test
    void malformedPlanIsAParseError() throws Exception {
        Path path = write("broken.json", "{not json");
        assertThrows(Json.ParseException.class, () -> Recorder.replay(Map.of("file", path.toString())));
    }

    @Test
    void planWithUtf8BomIsRead() throws Exception {
        Path path = work.resolve("bom.json");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] json = "{\"steps\":[]}".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + json.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(json, 0, bytes, bom.length, json.length);
        Files.write(path, bytes);

        Map<String, Object> result = Recorder.replay(Map.of("file", path.toString(), "port", 1));
        assertFalse(Boolean.TRUE.equals(result.get("isError")), Results.textOf(result));
        assertTrue(Results.textOf(result).contains("0/0 step(s)"), Results.textOf(result));
    }

    @Test
    void emptyPlanPasses() throws Exception {
        Map<String, Object> result = Recorder.replay(Map.of("file", write("empty.json", "{\"steps\":[]}").toString()));
        assertFalse(Boolean.TRUE.equals(result.get("isError")), Results.textOf(result));
        assertTrue(Results.textOf(result).contains("status=PASS"), Results.textOf(result));
    }

    @Test
    void failingStepStopsByDefaultAndWritesTheJUnitReport() throws Exception {
        Path report = work.resolve("reports/failure.xml");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("file", write("fail.json",
                "{\"steps\":[{\"tool\":\"fx_unknown\",\"args\":{}},{\"tool\":\"fx_unknown\",\"args\":{}}]}").toString());
        args.put("port", 1);
        args.put("delayMs", 0);
        args.put("screenshotOnFailure", true);
        args.put("junitReport", report.toString());

        Map<String, Object> result = Recorder.replay(args);

        assertEquals(true, result.get("isError"));
        assertTrue(Results.textOf(result).contains("passed=0, failed=1"), Results.textOf(result));
        assertTrue(Files.exists(report));
        String xml = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<failure "), xml);
        assertTrue(xml.contains("fx_unknown"), xml);
    }

    @Test
    void continueOnErrorRunsEveryStep() throws Exception {
        Map<String, Object> result = Recorder.replay(Map.of(
                "file", write("continue.json",
                        "{\"steps\":[{\"tool\":\"fx_unknown\",\"args\":{}},{\"tool\":\"fx_also_unknown\",\"args\":{}}]}").toString(),
                "port", 1,
                "delayMs", 0,
                "stopOnError", false));

        assertEquals(true, result.get("isError"));
        assertTrue(Results.textOf(result).contains("failed=2"), Results.textOf(result));
        assertTrue(Results.textOf(result).contains("step 2 fx_also_unknown"), Results.textOf(result));
    }

    @Test
    void retriesTheFailingStep() throws Exception {
        Map<String, Object> result = Recorder.replay(Map.of(
                "file", write("retry.json", "{\"steps\":[{\"tool\":\"fx_unknown\",\"args\":{}}]}").toString(),
                "port", 1,
                "delayMs", 0,
                "retries", 1));

        assertEquals(true, result.get("isError"));
        assertTrue(Results.textOf(result).contains("failed=1"), Results.textOf(result));
    }

    @Test
    void aPlanCannotReplayAPlan() throws Exception {
        Map<String, Object> result = Recorder.replay(Map.of(
                "file", write("nested.json", "{\"steps\":[{\"tool\":\"fx_replay\",\"args\":{\"file\":\"other.json\"}}]}").toString(),
                "port", 1,
                "delayMs", 0));

        assertEquals(true, result.get("isError"));
        assertTrue(Results.textOf(result).contains("cannot run inside a replayed plan"), Results.textOf(result));
    }

    @Test
    void variablesAreResolvedInStringsMapsAndLists() throws Exception {
        Path path = write("variables.json",
                "{\"steps\":[{\"tool\":\"fx_unknown\",\"args\":{\"selector\":\"${selector}\","
                        + "\"nested\":{\"value\":\"${value}\"},\"list\":[\"${value}\"]}}]}");
        Map<String, Object> result = Recorder.replay(Map.of(
                "file", path.toString(),
                "port", 1,
                "delayMs", 0,
                "variables", Map.of("selector", "#save", "value", "v")));

        assertEquals(true, result.get("isError"));
        assertTrue(Results.textOf(result).contains("fx_unknown failed"), Results.textOf(result));
    }

    @Test
    void replayMainParsesEveryOption() throws Exception {
        Path report = work.resolve("reports/cli.xml");
        int code = Recorder.replayMain(new String[]{
                "--replay", write("cli.json", "{\"steps\":[]}").toString(),
                "--port", "1", "--delay", "0", "--retry", "1", "--continue-on-error",
                "--screenshot-on-failure", "--report", report.toString(), "--var", "key=value"});

        assertEquals(0, code);
        assertTrue(Files.exists(report), "the JUnit report is written even for an empty plan");
    }

    @Test
    void replayMainRejectsBadArguments() throws Exception {
        assertEquals(2, Recorder.replayMain(new String[]{"--replay"}));
        assertEquals(2, Recorder.replayMain(new String[]{"--replay", "plan.json", "--var", "invalid"}));
        assertEquals(2, Recorder.replayMain(new String[]{"--replay", "plan.json", "--port"}));
        assertEquals(2, Recorder.replayMain(new String[]{"--replay", "plan.json", "--port", "x"}));
    }

    @Test
    void replayMainReturnsOneForAnUnreadablePlan() throws Exception {
        assertEquals(1, Recorder.replayMain(new String[]{"--replay", work.resolve("missing.json").toString()}));
    }

    private Path write(String name, String content) throws Exception {
        Path path = work.resolve(name);
        Files.createDirectories(path.getParent() == null ? work : path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}

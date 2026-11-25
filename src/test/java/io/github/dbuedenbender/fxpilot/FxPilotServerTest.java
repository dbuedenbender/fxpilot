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

import io.github.dbuedenbender.fxpilot.internal.TokenFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxPilotServerTest {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void stopServer() {
        FxPilotServer.stop();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + FxPilotServer.port() + path;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(5)).GET();
        if (token != null) {
            b.header("X-FxPilot-Token", token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-FxPilot-Client", "test")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bindsToLoopbackOnTheGivenPort() {
        FxPilotServer.start(0, null);
        assertTrue(FxPilotServer.isRunning());
        assertTrue(FxPilotServer.port() > 0);
    }

    @Test
    void rejectsRequestsWithoutOrWithWrongToken() throws Exception {
        FxPilotServer.start(0, "secret");
        HttpResponse<String> missing = get("/health", null);
        assertEquals(401, missing.statusCode());
        assertTrue(missing.body().contains("\"code\":\"UNAUTHORIZED\""));
        HttpResponse<String> wrong = get("/health", "wrong");
        assertEquals(401, wrong.statusCode());
        assertTrue(wrong.body().contains("\"code\":\"UNAUTHORIZED\""));
    }

    @Test
    void acceptsRequestsWithCorrectToken() throws Exception {
        FxPilotServer.start(0, "secret");
        assertEquals(404, get("/does-not-exist", "secret").statusCode());
    }

    @Test
    void rejectsPostWithoutClientHeaderWhenNoTokenIsSet() throws Exception {
        FxPilotServer.start(0, null);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/click")))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("selector=okButton"))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("\"code\":\"FORBIDDEN\""));
    }

    @Test
    void requiresPostForActions() throws Exception {
        FxPilotServer.start(0, null);
        HttpResponse<String> r = get("/click?selector=okButton", null);
        assertEquals(405, r.statusCode());
        assertTrue(r.body().contains("\"code\":\"METHOD_NOT_ALLOWED\""));
    }

    @Test
    void rejectsOversizedBody() throws Exception {
        FxPilotServer.start(0, null);
        HttpResponse<String> r = post("/setText", "text=" + "x".repeat(1 << 20));
        assertEquals(413, r.statusCode());
        assertTrue(r.body().contains("\"code\":\"BODY_TOO_LARGE\""));
    }

    @Test
    void unknownEndpointReturns404() throws Exception {
        FxPilotServer.start(0, null);
        HttpResponse<String> r = get("/does-not-exist", null);
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("\"code\":\"UNKNOWN_ENDPOINT\""));
    }

    @Test
    void readOnlyModeRejectsActions() throws Exception {
        System.setProperty("fxpilot.readonly", "true");
        try {
            FxPilotServer.start(0, null);
            HttpResponse<String> r = post("/click", "selector=okButton");
            assertEquals(403, r.statusCode());
            assertTrue(r.body().contains("\"code\":\"READ_ONLY\""));
            assertEquals(404, get("/does-not-exist", null).statusCode());
        } finally {
            System.clearProperty("fxpilot.readonly");
        }
    }

    @Test
    void unknownExtendedOperationReturnsInvalidOperation() throws Exception {
        FxPilotServer.start(0, null);
        HttpResponse<String> r = post("/exec", "op=doesNotExist");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("\"code\":\"INVALID_OPERATION\""));
    }

    @Test
    void missingParameterHasBadRequestCode() throws Exception {
        FxPilotServer.start(0, null);
        HttpResponse<String> r = post("/setText", "");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("\"code\":\"BAD_REQUEST\""));
        assertTrue(r.body().contains("parameter 'text' is required"));
    }

    /** Raw request, because HttpClient does not let callers set the Host header. */
    private static String rawStatusLine(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", FxPilotServer.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line).append('\n');
            }
            return response.toString();
        }
    }

    @Test
    void rejectsForeignHostHeaderAgainstDnsRebinding() throws Exception {
        FxPilotServer.start(0, null);
        String response = rawStatusLine("GET /does-not-exist HTTP/1.1\r\nHost: evil.example:" + FxPilotServer.port()
                + "\r\nConnection: close\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"), response);
        assertTrue(response.contains("\"code\":\"HOST_REJECTED\""), response);
    }

    @Test
    void rejectsForeignHostEvenWithTheRightToken() throws Exception {
        FxPilotServer.start(0, "secret");
        String response = rawStatusLine("GET /does-not-exist HTTP/1.1\r\nHost: evil.example\r\n"
                + "X-FxPilot-Token: secret\r\nConnection: close\r\n\r\n");
        assertTrue(response.contains("\"code\":\"HOST_REJECTED\""), response);
    }

    @Test
    void acceptsLoopbackHostNames() throws Exception {
        FxPilotServer.start(0, null);
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]", "LOCALHOST"}) {
            String response = rawStatusLine("GET /does-not-exist HTTP/1.1\r\nHost: " + host + ":" + FxPilotServer.port()
                    + "\r\nConnection: close\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 404"), host + ": " + response);
        }
        assertFalse(FxPilotServer.isLoopbackHost(null, 8765));
        assertFalse(FxPilotServer.isLoopbackHost("127.0.0.1:8766", 8765));
        assertFalse(FxPilotServer.isLoopbackHost("127.0.0.1.evil.example:8765", 8765));
    }

    @Test
    void rejectsRequestsWithOriginHeader() throws Exception {
        FxPilotServer.start(0, null);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url("/health")))
                .timeout(Duration.ofSeconds(5)).header("Origin", "http://evil.example").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("\"code\":\"ORIGIN_REJECTED\""));
    }

    @Test
    void rejectsMethodsOtherThanGetAndPost() throws Exception {
        FxPilotServer.start(0, null);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url("/health")))
                .timeout(Duration.ofSeconds(5)).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
        assertFalse(r.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }

    @Test
    void autoTokenIsWrittenToTheTokenFileAndRemovedOnStop(@TempDir Path dir) throws Exception {
        System.setProperty("fxpilot.tokenDir", dir.toString());
        try {
            FxPilotServer.start(0, "auto");
            Path file = dir.resolve(FxPilotServer.port() + ".token");
            assertTrue(Files.exists(file));
            String token = Files.readString(file).trim();
            assertTrue(token.length() >= 40, token);
            assertEquals(401, get("/does-not-exist", null).statusCode());
            assertEquals(401, get("/does-not-exist", "auto").statusCode());
            assertEquals(404, get("/does-not-exist", token).statusCode());
            FxPilotServer.stop();
            assertFalse(Files.exists(file));
        } finally {
            System.clearProperty("fxpilot.tokenDir");
        }
    }

    @Test
    void tokenFileDirectoryDefaultsPerUser() {
        String saved = System.getProperty("fxpilot.tokenDir");
        System.clearProperty("fxpilot.tokenDir");
        try {
            Path dir = TokenFiles.directory();
            assertTrue(dir.endsWith(Path.of("FxPilot", "ports")) || dir.endsWith(Path.of(".fxpilot", "ports")), dir.toString());
        } finally {
            if (saved != null) {
                System.setProperty("fxpilot.tokenDir", saved);
            }
        }
    }

    @Test
    void parsesSimpleQueryString() {
        Map<String, String> p = new HashMap<>();
        FxPilotServer.parse("a=1&b=hello", p);
        assertEquals("1", p.get("a"));
        assertEquals("hello", p.get("b"));
    }

    @Test
    void urlDecodesKeysAndValues() {
        Map<String, String> p = new HashMap<>();
        FxPilotServer.parse("name=Hello%20World&x=a%26b", p);
        assertEquals("Hello World", p.get("name"));
        assertEquals("a&b", p.get("x"));
    }

    @Test
    void handlesKeyWithoutValue() {
        Map<String, String> p = new HashMap<>();
        FxPilotServer.parse("flag", p);
        assertEquals("", p.get("flag"));
    }

    @Test
    void ignoresEmptyPairsAndNullOrEmptyInput() {
        Map<String, String> p = new HashMap<>();
        FxPilotServer.parse(null, p);
        FxPilotServer.parse("", p);
        FxPilotServer.parse("&&a=1&", p);
        assertEquals(1, p.size());
        assertEquals("1", p.get("a"));
    }
}
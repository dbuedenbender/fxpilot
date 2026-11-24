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
package io.github.buedi.fxpilot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
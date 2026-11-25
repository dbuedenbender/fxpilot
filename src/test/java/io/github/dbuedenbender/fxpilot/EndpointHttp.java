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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minimal HTTP client for the endpoint tests; sends the same client header as the MCP server. */
final class EndpointHttp {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private EndpointHttp() {
    }

    static String url(String path) {
        return "http://127.0.0.1:" + FxPilotServer.port() + path;
    }

    static HttpResponse<String> get(String path, String... kv) throws Exception {
        String q = form(kv);
        return HTTP.send(HttpRequest.newBuilder(URI.create(url(path) + (q.isEmpty() ? "" : "?" + q)))
                .timeout(Duration.ofSeconds(15))
                .header("X-FxPilot-Client", "test")
                .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static HttpResponse<byte[]> getBytes(String path, String... kv) throws Exception {
        String q = form(kv);
        return HTTP.send(HttpRequest.newBuilder(URI.create(url(path) + (q.isEmpty() ? "" : "?" + q)))
                .timeout(Duration.ofSeconds(15))
                .header("X-FxPilot-Client", "test")
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static HttpResponse<String> post(String path, String... kv) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-FxPilot-Client", "test")
                .POST(HttpRequest.BodyPublishers.ofString(form(kv))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** POST that must succeed; returns the body. */
    static String ok(String path, String... kv) throws Exception {
        HttpResponse<String> r = post(path, kv);
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    /** GET that must succeed; returns the body. */
    static String read(String path, String... kv) throws Exception {
        HttpResponse<String> r = get(path, kv);
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    static void assertError(HttpResponse<String> r, int status, String code) {
        assertEquals(status, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"code\":\"" + code + "\""), r.body());
    }

    /** Text of the #status label, which the fixture's handlers write to. */
    static String status() throws Exception {
        String body = read("/node", "selector", "status");
        int i = body.indexOf("\"text\":\"");
        if (i < 0) {
            return "";
        }
        int start = i + 8;
        return body.substring(start, body.indexOf('"', start));
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}

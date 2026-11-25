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

import io.github.dbuedenbender.fxpilot.internal.TokenFiles;
import io.github.dbuedenbender.fxpilot.internal.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * HTTP client for the FxPilot endpoint of the target app (default {@code FXPILOT_URL}, other apps
 * on 127.0.0.1 via the {@code port} argument).
 */
final class EndpointClient {

    /** Sent on every request; the endpoint rejects POSTs without it when no token is configured. */
    private static final String CLIENT_HEADER = "fxpilot-mcp";
    static final String BASE_URL = env("FXPILOT_URL", "http://127.0.0.1:8765").replaceAll("/+$", "");
    private static final String TOKEN = env("FXPILOT_TOKEN", "");
    private static HttpClient http;

    private EndpointClient() {
    }

    /**
     * Created on first use: building an HttpClient opens a loopback channel, and if that fails (sandboxes,
     * blocked temp directories) the MCP server must still start and report the problem as a tool error.
     */
    static synchronized HttpClient http() throws java.io.IOException {
        if (http == null) {
            try {
                http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            } catch (RuntimeException e) {
                throw new java.io.IOException("cannot create the HTTP client: " + e.getMessage(), e);
            }
        }
        return http;
    }

    /**
     * FXPILOT_TOKEN for the endpoint in FXPILOT_URL. For other ports (the {@code port} argument) only the
     * token file that an endpoint started with {@code fxpilot.token=auto} wrote for that port is used, so the
     * token is never sent to an unrelated service that happens to listen on a port a client named.
     */
    static String token(String base) {
        if (!TOKEN.isEmpty() && sameEndpoint(base, BASE_URL)) {
            return TOKEN;
        }
        int port = URI.create(base).getPort();
        if (port < 0) {
            return "";
        }
        Path file = TokenFiles.file(port);
        try {
            return Files.isReadable(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }


    /**
     * True when two base URLs name the same endpoint. Loopback aliases (127.0.0.1, localhost, [::1]) are
     * equivalent, so a tool that passes the port of {@code FXPILOT_URL} still gets the configured token.
     */
    static boolean sameEndpoint(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            URI a = URI.create(first);
            URI b = URI.create(second);
            if (a.getScheme() == null || b.getScheme() == null || a.getHost() == null || b.getHost() == null
                    || !a.getScheme().equalsIgnoreCase(b.getScheme())) {
                return false;
            }
            if (port(a) != port(b)) {
                return false;
            }
            return a.getHost().equalsIgnoreCase(b.getHost())
                    || isLoopback(a.getHost()) && isLoopback(b.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "[::1]".equals(host) || "::1".equals(host);
    }

    /** Default endpoint, or 127.0.0.1 on the port passed by the caller (for several apps at once). */
    static String baseUrl(Object port) {
        if (port == null) {
            return BASE_URL;
        }
        long p;
        try {
            p = port instanceof Number ? ((Number) port).longValue() : Long.parseLong(String.valueOf(port).trim());
        } catch (NumberFormatException e) {
            throw new RpcError(-32602, "port must be an integer");
        }
        if (p < 1 || p > 65535) {
            throw new RpcError(-32602, "port must be between 1 and 65535");
        }
        return "http://127.0.0.1:" + p;
    }

    static String get(String path, Map<String, Object> a, String base) throws Exception {
        return send(request(path, a, false, base));
    }

    static String post(String path, Map<String, Object> a, String base) throws Exception {
        return send(request(path, a, true, base));
    }

    static HttpRequest request(String path, Map<String, Object> a, boolean post, String base) {
        String form = form(a);
        HttpRequest.Builder b;
        if (post) {
            b = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .header("X-FxPilot-Client", CLIENT_HEADER)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        } else {
            b = HttpRequest.newBuilder(URI.create(base + path + (form.isEmpty() ? "" : "?" + form)))
                    .header("X-FxPilot-Client", CLIENT_HEADER)
                    .GET();
        }
        String token = token(base);
        if (!token.isEmpty()) {
            b.header("X-FxPilot-Token", token);
        }
        return b.timeout(Duration.ofSeconds(30)).build();
    }

    private static String send(HttpRequest req) throws Exception {
        HttpResponse<String> r = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 400) {
            throw failure(r.statusCode(), r.body());
        }
        return r.body();
    }

    /** Turns an error body into an HttpFailure that carries the stable FxPilot error code. */
    static HttpFailure failure(int status, String body) {
        String code = null;
        String message = null;
        try {
            Map<String, Object> e = Results.asMap(Json.parse(body));
            code = Results.str(e.get("code"));
            message = Results.str(e.get("error"));
        } catch (RuntimeException ignored) {
            // not a JSON error body, fall back to the raw text
        }
        return new HttpFailure(status, code, message == null ? body : message);
    }

    private static String form(Map<String, Object> a) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            Object v = e.getValue();
            String s = v instanceof Double && ((Double) v) == Math.rint((Double) v) ? String.valueOf(((Double) v).longValue()) : String.valueOf(v);
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(s, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}

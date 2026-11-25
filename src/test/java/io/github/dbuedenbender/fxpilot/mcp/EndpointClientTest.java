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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointClientTest {

    @Test
    void treatsLoopbackAliasesAsTheSameEndpoint() {
        assertTrue(EndpointClient.sameEndpoint("http://127.0.0.1:8765", "http://localhost:8765"));
        assertTrue(EndpointClient.sameEndpoint("http://localhost:8765", "http://[::1]:8765"));
        assertTrue(EndpointClient.sameEndpoint("http://localhost", "http://127.0.0.1:80"));
        assertTrue(EndpointClient.sameEndpoint("https://localhost:443", "https://127.0.0.1"));
    }

    @Test
    void requiresSchemeAndPortToMatch() {
        assertFalse(EndpointClient.sameEndpoint("http://127.0.0.1:8765", "http://127.0.0.1:8766"));
        assertFalse(EndpointClient.sameEndpoint("http://127.0.0.1:8765", "https://127.0.0.1:8765"));
        assertFalse(EndpointClient.sameEndpoint("http://example.com:8765", "http://127.0.0.1:8765"));
        assertFalse(EndpointClient.sameEndpoint("http://127.0.0.1:8765", "not a url"));
        assertFalse(EndpointClient.sameEndpoint(null, "http://127.0.0.1:8765"));
    }
}

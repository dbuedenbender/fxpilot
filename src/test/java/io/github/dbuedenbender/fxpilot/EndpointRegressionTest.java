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

import javafx.scene.control.CheckBox;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Findings of the 1.1.0 review: each test failed before its fix. */
class EndpointRegressionTest extends UiTestBase {

    private static final String SECRET = "geheim";

    @Test
    void passwordIsNotReadableThroughBeanProperties() throws Exception {
        for (String name : new String[]{"text", "characters", "selectedText", "content"}) {
            String body = get("/exec", "op", "propertyGet", "selector", "password", "name", name).body();
            assertFalse(body.contains(SECRET), name + ": " + body);
        }
        assertError(get("/exec", "op", "propertyGet", "selector", "password", "name", "characters"), 403, "NODE_DENIED");
    }

    @Test
    void passwordIsNotMatchableByTextSelectors() throws Exception {
        assertTrue(read("/matches", "selector", "text=" + SECRET).contains("\"count\":0"));
        assertTrue(read("/matches", "selector", "text*=geh").contains("\"count\":0"));
    }

    @Test
    void passwordIsNotPrintedInErrorMessages() throws Exception {
        HttpResponse<String> tooFar = get("/node", "selector", "role=textbox", "nth", "99");
        assertEquals(404, tooFar.statusCode());
        assertFalse(tooFar.body().contains(SECRET), tooFar.body());
        System.setProperty("fxpilot.strictSelectors", "true");
        HttpResponse<String> ambiguous = post("/click", "selector", "role=textbox");
        assertEquals(409, ambiguous.statusCode());
        assertFalse(ambiguous.body().contains(SECRET), ambiguous.body());
    }

    @Test
    void inlineIdIndexWorksForActions() throws Exception {
        ok("/click", "selector", "id=twin@1");
        assertEquals("twin B", status());
        ok("/exec", "op", "click", "selector", "id=twin@0");
        assertEquals("twin A", status());
    }

    @Test
    void disabledMenuItemsAreNotFiredByPath() throws Exception {
        assertError(post("/exec", "op", "menu", "path", "File > Off"), 409, "NODE_DISABLED");
        assertError(post("/menu", "menu", "File", "item", "Off"), 409, "NODE_DISABLED");
        assertFalse(status().contains("disabled item fired"));
    }

    @Test
    void triStateCheckBoxEndsInTheRequestedState() throws Exception {
        ok("/check", "selector", "tri", "checked", "true");
        assertEquals("true/false", triState());
        ok("/check", "selector", "tri", "checked", "false");
        assertEquals("false/false", triState());
        TestUi.onFx(() -> ((CheckBox) Selectors.find("tri")).setIndeterminate(true));
        ok("/check", "selector", "tri", "checked", "false");
        assertEquals("false/false", triState());
    }

    private static String triState() {
        return FxThread.query(() -> {
            CheckBox cb = (CheckBox) Selectors.find("tri");
            return cb.isSelected() + "/" + cb.isIndeterminate();
        });
    }

    @Test
    void badInputIsAClientError() throws Exception {
        assertError(post("/exec", "op", "hotkey", "keys", "+"), 400, "INVALID_KEY");
        assertError(post("/select", "selector", "list", "index", "abc"), 400, "INVALID_NUMBER");
        HttpResponse<String> malformed = send(HttpRequest.newBuilder(URI.create(url("/click")))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-FxPilot-Client", "test")
                .POST(HttpRequest.BodyPublishers.ofString("selector=%zz")).build());
        assertError(malformed, 400, "BAD_REQUEST");
    }

    @Test
    void robotPointsOutsideTheSceneAreRejected() throws Exception {
        assertError(post("/robotClick", "x", "-500", "y", "10"), 400, "INVALID_VALUE");
        assertError(post("/robotDrag", "x1", "10", "y1", "10", "x2", "99999", "y2", "10"), 400, "INVALID_VALUE");
    }

    @Test
    void keyEndpointHonoursNthAndModifiers() throws Exception {
        String body = ok("/key", "selector", "text=Dup", "nth", "1", "key", "A", "modifiers", "SHIFT");
        assertTrue(body.contains("\"id\":\"dupB\""), body);
    }
}

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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code fxpilot.dangerous=false} and {@code fxpilot.readonly=true} against the real UI. */
class EndpointPolicyTest {

    @Nested
    class DangerousDisabled extends UiTestBase {

        @Override
        void configure() {
            System.setProperty("fxpilot.dangerous", "false");
        }

        @Test
        void dangerousOperationsAreRejected() throws Exception {
            assertError(post("/exec", "op", "propertySet", "selector", "nameField", "name", "text", "value", "x"),
                    403, "DANGEROUS_DISABLED");
            assertError(post("/exec", "op", "webExecute", "selector", "nameField", "script", "1"), 403,
                    "DANGEROUS_DISABLED");
            assertError(post("/exec", "op", "image", "selector", "nameField", "file", "x.png"), 403,
                    "DANGEROUS_DISABLED");
            assertError(post("/exec", "op", "window", "action", "close"), 403, "DANGEROUS_DISABLED");
            assertError(post("/exec", "op", "tab", "selector", "tabs", "tab", "Closable", "action", "close"), 403,
                    "DANGEROUS_DISABLED");
            assertError(get("/exec", "op", "clipboardGet"), 403, "DANGEROUS_DISABLED");
            assertTrue(read("/windows").contains(TestUi.TITLE), "window must still be open");
        }

        @Test
        void ordinaryOperationsStillWork() throws Exception {
            ok("/exec", "op", "window", "action", "resize", "width", "800");
            assertTrue(read("/exec", "op", "propertyGet", "selector", "okButton", "name", "text").contains("OK"));
        }
    }

    @Nested
    class ReadOnly extends UiTestBase {

        @Override
        void configure() {
            System.setProperty("fxpilot.readonly", "true");
        }

        @Test
        void readsWorkAndActionsAreRejected() throws Exception {
            assertTrue(read("/view").contains("okButton"));
            assertTrue(read("/exec", "op", "tableCell", "selector", "customers", "row", "0").contains("Sample Inc."));
            assertError(post("/click", "selector", "okButton"), 403, "READ_ONLY");
            assertError(post("/exec", "op", "focus", "selector", "okButton"), 403, "READ_ONLY");
        }
    }
}

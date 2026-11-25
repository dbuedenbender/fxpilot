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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Shows a fresh fixture window and starts the endpoint on a free port for every test. */
abstract class UiTestBase {

    private static final String[] PROPERTIES = {
            "fxpilot.readonly", "fxpilot.deny", "fxpilot.strictSelectors", "fxpilot.dangerous", "fxpilot.token"};

    @BeforeEach
    void showUi() throws Exception {
        TestUi.show();
        configure();
        FxPilotServer.start(0, token());
    }

    /** Hook for subclasses that need system properties before the endpoint starts. */
    void configure() {
    }

    String token() {
        return null;
    }

    @AfterEach
    void stopEndpoint() {
        FxPilotServer.stop();
        for (String p : PROPERTIES) {
            System.clearProperty(p);
        }
        DenyList.setDenyPatterns(null);
    }
}

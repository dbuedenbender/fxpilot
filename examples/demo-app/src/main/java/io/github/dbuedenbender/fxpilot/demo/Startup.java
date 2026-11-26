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
package io.github.dbuedenbender.fxpilot.demo;

import java.util.logging.Logger;

/** Startup helpers shared by the demo's entry points. */
final class Startup {

    private Startup() {
    }

    /**
     * This demo runs JavaFX from the class path on purpose (plain {@code java -cp} and the replay
     * script). JavaFX logs an "Unsupported JavaFX configuration" notice once for that; it is
     * expected here, so drop just that message instead of the whole logger.
     */
    static void silenceClasspathNotice() {
        Logger.getLogger("javafx").setFilter(record ->
                !String.valueOf(record.getMessage()).startsWith("Unsupported JavaFX configuration"));
    }
}

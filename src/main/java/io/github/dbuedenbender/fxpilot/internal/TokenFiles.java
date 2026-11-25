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
package io.github.dbuedenbender.fxpilot.internal;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Location of the token files of {@code fxpilot.token=auto}, shared by the endpoint (writes them) and the
 * MCP server (reads them). Not part of the FxPilot API; may change in any release.
 */
public final class TokenFiles {

    private TokenFiles() {
    }

    /**
     * {@code fxpilot.tokenDir} / {@code FXPILOT_TOKEN_DIR} if set, otherwise {@code %LOCALAPPDATA%\FxPilot\ports}
     * on Windows and {@code ~/.fxpilot/ports} elsewhere.
     */
    public static Path directory() {
        String dir = System.getProperty("fxpilot.tokenDir", System.getenv("FXPILOT_TOKEN_DIR"));
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir);
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                && localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "FxPilot", "ports");
        }
        return Path.of(System.getProperty("user.home"), ".fxpilot", "ports");
    }

    /** Token file of the endpoint on {@code port}. */
    public static Path file(int port) {
        return directory().resolve(port + ".token");
    }
}

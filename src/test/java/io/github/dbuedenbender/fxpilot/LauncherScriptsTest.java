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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The launchers pin the default version and its SHA-256 in three places; a release must update all of them. */
class LauncherScriptsTest {

    private static String value(String script, String regex) throws Exception {
        String text = Files.readString(Path.of("scripts", script), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        assertTrue(m.find(), script + " has no match for " + regex);
        return m.group(1);
    }

    @Test
    void launchersPinTheSameVersionAndChecksum() throws Exception {
        String version = value("fxpilot-mcp.sh", "^DEFAULT_VERSION=\"([^\"]+)\"");
        String sha256 = value("fxpilot-mcp.sh", "^DEFAULT_SHA256=\"([0-9a-f]{64})\"");
        assertEquals(version, value("fxpilot-mcp.cmd", "^set \"DEFAULT_VERSION=([^\"]+)\""));
        assertEquals(sha256, value("fxpilot-mcp.cmd", "^set \"DEFAULT_SHA256=([0-9a-f]{64})\""));
        assertEquals(version, value("fxpilot-jar.ps1", "^\\$FxPilotDefaultVersion = \"([^\"]+)\""));
        assertEquals(sha256, value("fxpilot-jar.ps1", "^\\$FxPilotDefaultSha256 = \"([0-9a-f]{64})\""));
    }
}

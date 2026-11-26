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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Java source carries the Apache header, including the example apps: redistributions made from this
 * repository must state the license and the warranty disclaimer.
 */
class LicenseHeadersTest {

    private static final String OPEN = "/*";
    private static final String COPYRIGHT = " * Copyright 2026 FxPilot contributors";
    private static final String LICENSE = " * Licensed under the Apache License, Version 2.0 (the \"License\");";

    @Test
    void javaSourcesCarryTheLicenseHeader() throws Exception {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (String root : List.of("src", "examples")) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path file : files.filter(LicenseHeadersTest::isJavaSource).collect(Collectors.toList())) {
                    checked++;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    // header, then the package declaration (src) or the module javadoc (module-info.java)
                    if (lines.size() < 4 || !lines.get(0).equals(OPEN)
                            || !lines.get(1).equals(COPYRIGHT) || !lines.get(3).equals(LICENSE)) {
                        missing.add(file.toString());
                    }
                }
            }
        }
        assertTrue(checked > 30, "found only " + checked + " Java sources; are the paths right?");
        assertEquals(List.of(), missing, "files without the Apache header");
    }

    private static boolean isJavaSource(Path file) {
        String path = file.toString();
        return path.endsWith(".java") && !path.contains("target");
    }
}
